import CryptoKit
import Foundation
import QuartzCore

enum APIError: LocalizedError {
    case unauthorized
    case notFound
    case serverError(String)
    case networkUnavailable
    case decodingFailed

    var errorDescription: String? {
        switch self {
        case .unauthorized:       return "Authentication required"
        case .notFound:           return "Resource not found"
        case .serverError(let m): return m
        case .networkUnavailable: return "No internet connection"
        case .decodingFailed:     return "We couldn't read the server's response"
        }
    }

    /// A concrete next step to show alongside errorDescription. Without this, a
    /// decode/server failure just tells the user something's wrong with no
    /// indication of whether retrying will help or they should reach out — see
    /// ErrorPresentation, which turns this (plus isRetryable/suggestsContactSupport
    /// below) into the "Try Again" / "Contact Support" affordance shown in-app.
    var recoverySuggestion: String? {
        switch self {
        case .unauthorized:       return "Sign in again to continue."
        case .notFound:           return nil
        case .serverError:        return "Try again. If the problem continues, contact support."
        case .networkUnavailable: return "Check your connection and try again."
        case .decodingFailed:     return "Try again. If the problem continues, contact support — this has been logged for us to investigate."
        }
    }

    /// Whether a "Try Again" affordance makes sense for this error.
    var isRetryable: Bool {
        switch self {
        case .serverError, .networkUnavailable, .decodingFailed: return true
        case .unauthorized, .notFound: return false
        }
    }

    /// Whether "Contact Support" should be offered — reserved for failures the
    /// user can't resolve by retrying alone (a decode failure means the client
    /// and server response shape have drifted; a persistent server error may be
    /// an outage), as opposed to ones with a clear, actionable client-side fix
    /// (sign in again, check your connection).
    var suggestsContactSupport: Bool {
        switch self {
        case .decodingFailed, .serverError: return true
        case .unauthorized, .notFound, .networkUnavailable: return false
        }
    }
}

// `public` here (and on listAllVaults() below): TTLWidget.swift calls
// APIClient.shared.listAllVaults() across a real module boundary in the SPM
// build (Package.swift declares TTLWidget as a separate target depending
// on the EthosProtocol target) — internal (the Swift default) is invisible
// outside the defining module. Other members stay internal since only
// listAllVaults() is called from outside this module.
public final class APIClient {
    public static let shared = APIClient()

    // `internal` (not `private`): VaultEventSocket derives the wss:// URL from
    // the same configured base URL instead of hardcoding a second copy of it.
    let baseURL: URL
    private let session: URLSession
    private let decoder: JSONDecoder
    private let retryPolicy: RetryPolicy
    private let networkMonitor: NetworkMonitor

    private convenience init() {
        let urlString = Bundle.main.object(forInfoDictionaryKey: "API_BASE_URL") as? String
            ?? "https://api.ethos-protocol.app/v1"
        // #117: Create a URLSession backed by PinningDelegate.
        // The delegate enforces public-key pinning against the hash(es) listed in
        // Info.plist under `TLS_PUBLIC_KEY_PINS`. Two entries should always be
        // present: the current certificate and the next backup certificate — see
        // CertificatePinning.swift for the rotation strategy.
        let pinningDelegate = PinningDelegate()
        let session = URLSession(
            configuration: .default,
            delegate: pinningDelegate,
            delegateQueue: nil
        )
        self.init(baseURL: URL(string: urlString)!,
                  session: session,
                  retryPolicy: .networkDefault)
    }

    // `internal` (not `private`): lets tests construct an APIClient with a mock
    // URLSession / RetryPolicy via `@testable import`. `.shared` remains the only
    // production entry point.
    init(baseURL: URL, session: URLSession, retryPolicy: RetryPolicy = .networkDefault, networkMonitor: NetworkMonitor = .shared) {
        self.baseURL = baseURL
        self.session = session
        self.retryPolicy = retryPolicy
        self.networkMonitor = networkMonitor
        decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        decoder.dateDecodingStrategy = .iso8601
    }

    // MARK: - Auth

    func getChallenge() async throws -> AuthChallenge {
        try await post(path: "/auth/challenge", body: EmptyBody())
    }

    func verifyPasskey(credentialID: String, clientDataJSON: String, signature: String) async throws -> AuthToken {
        let body = ["credential_id": credentialID,
                    "client_data_json": clientDataJSON,
                    "signature": signature]
        return try await post(path: "/auth/verify", body: body)
    }

    // `public_key` carries the WebAuthn COSE_Key (RFC 9052) extracted from the
    // attestation object's authData, base64url-encoded — not the attestation object
    // itself (#1, see docs/mobile-passkey-flow.md and shared/api-contract.md's
    // PasskeyRegisterRequest). Matches Android's `PasskeyRegisterRequest.publicKey`
    // (extractCosePublicKey). The backend returns a session token directly so
    // registration only needs a single passkey ceremony (#2) — no separate
    // /auth/verify round trip.
    func registerPasskey(credentialID: String, publicKey: String, clientDataJSON: String) async throws -> AuthToken {
        let body = ["credential_id": credentialID,
                    "public_key": publicKey,
                    "client_data_json": clientDataJSON]
        return try await post(path: "/auth/register", body: body)
    }

    // Proactive refresh (#3): called by AuthStore shortly before AuthToken.expiresAt so
    // an in-progress action isn't interrupted by a reactive 401 delete-and-reauth. Goes
    // through the normal post()/execute() path — including the existing 401 handling
    // below — so a rejected refresh still falls back to deleting the stored token.
    func refreshToken() async throws -> AuthToken {
        try await post(path: "/auth/refresh", body: EmptyBody())
    }

    /// Links a newly created passkey to an existing vault-owning account, for a user who
    /// lost their original device. `existingAccountProof` is verified server-side before
    /// the credential is attached, so this cannot be used to add an unauthorized passkey.
    func linkAdditionalPasskey(existingAccountProof proof: AccountRecoveryProof, credentialID: String, publicKey: String, clientDataJSON: String) async throws {
        let body = LinkPasskeyRequest(email: proof.email,
                                       backupCode: proof.backupCode,
                                       credentialID: credentialID,
                                       publicKey: publicKey,
                                       clientDataJSON: clientDataJSON)
        let _: EmptyBody = try await post(path: "/auth/recover/link", body: body)
    }

    // MARK: - Sessions (#208)

    func listSessions() async throws -> [Session] {
        try await get(path: "/auth/sessions")
    }

    /// "Sign out this device" for a specific session (may be the caller's own current session).
    func revokeSession(id: String) async throws {
        var req = request(path: Self.path("/auth/sessions", id))
        req.httpMethod = "DELETE"
        for (field, value) in Self.makeAntiReplayHeaders() {
            req.setValue(value, forHTTPHeaderField: field)
        }
        _ = try await execute(req)
    }

    /// "Sign out all other devices" — revokes every session except the one making this call.
    func revokeOtherSessions() async throws {
        var req = request(path: "/auth/sessions")
        req.httpMethod = "DELETE"
        for (field, value) in Self.makeAntiReplayHeaders() {
            req.setValue(value, forHTTPHeaderField: field)
        }
        _ = try await execute(req)
    }

    // MARK: - Vaults

    /// One page of `GET /vaults`. See shared/api-contract.md's "List Pagination"
    /// section for the query-parameter/response-header contract this implements.
    struct VaultPage {
        let vaults: [Vault]
        let nextCursor: String?
    }

    static let defaultVaultPageSize = 50
    private static let nextCursorHeader = "X-Next-Cursor"

    /// The inclusive range shared/api-contract.md §Pagination defines for `limit`.
    static let vaultPageSizeRange = 1...100

    /// Fetches a single page of the caller's vaults, starting after `cursor`
    /// (`nil` requests the first page). Drives VaultListView's "Load More".
    ///
    /// `limit` is clamped to the contract's 1...100 range (#599) so a caller
    /// passing 0, a negative, or an oversized value never sends a request the
    /// server must reject or silently reinterpret.
    func listVaults(cursor: String? = nil, limit: Int = APIClient.defaultVaultPageSize) async throws -> VaultPage {
        var req = request(path: "/vaults", queryItems: Self.vaultsQueryItems(cursor: cursor, limit: limit))
        req.httpMethod = "GET"
        let (data, response) = try await execute(req)
        let vaults: [Vault] = try decode(data, path: "/vaults")
        let nextCursor = Self.parseNextCursor(fromHeaderValue: response.value(forHTTPHeaderField: Self.nextCursorHeader))
        return VaultPage(vaults: vaults, nextCursor: nextCursor)
    }

    /// Builds the `GET /vaults` query items for `cursor`/`limit` — split out from
    /// listVaults() so the cursor/limit contract is unit-testable independent of
    /// the network layer. `limit` is clamped to `vaultPageSizeRange` (#599).
    static func vaultsQueryItems(cursor: String?, limit: Int) -> [URLQueryItem] {
        let clampedLimit = min(max(limit, vaultPageSizeRange.lowerBound), vaultPageSizeRange.upperBound)
        var queryItems = [URLQueryItem(name: "limit", value: String(clampedLimit))]
        if let cursor, !cursor.isEmpty {
            queryItems.append(URLQueryItem(name: "cursor", value: cursor))
        }
        return queryItems
    }

    /// An empty `X-Next-Cursor` header means "no further page", same as a missing one.
    static func parseNextCursor(fromHeaderValue value: String?) -> String? {
        (value?.isEmpty ?? true) ? nil : value
    }

    /// Upper bound on the number of pages listAllVaults() will fetch before giving
    /// up. Guards against a server that never stops returning a next cursor (or a
    /// pathologically large account) growing `all` without limit.
    static let maxVaultPages = 100

    /// Fetches every page and concatenates the results, for callers (background
    /// refresh, the TTL widget) that need the complete vault set to compute
    /// accurate TTL warnings rather than a single page at a time.
    ///
    /// Guards against non-advancing cursors and unbounded pagination (#604): if the
    /// server repeats a cursor we've already followed, or we exceed `maxVaultPages`,
    /// we throw instead of looping forever. The error surfaces through
    /// `errorDescription`/`recoverySuggestion` so the UI can present it.
    public func listAllVaults() async throws -> [Vault] {
        var all: [Vault] = []
        var cursor: String?
        var seenCursors = Set<String>()
        var pageCount = 0
        repeat {
            if let cursor {
                guard seenCursors.insert(cursor).inserted else {
                    throw APIError.serverError("Pagination did not advance; please try again")
                }
            }
            guard pageCount < Self.maxVaultPages else {
                throw APIError.serverError("Too many vaults to load at once; please try again")
            }
            pageCount += 1
            let page = try await listVaults(cursor: cursor)
            all.append(contentsOf: page.vaults)
            cursor = page.nextCursor
        } while cursor != nil
        return all
    }

    /// How long ago the cached `/vaults` response was written, or nil if nothing is cached yet.
    /// Lets VaultStore surface "data as of X ago" when a `listVaults()` call was served from
    /// the offline cache. Uses the same cache key `execute(_:)` saves under (the request URL).
    func vaultsCacheAge() -> TimeInterval? {
        OfflineCache.shared.age(for: request(path: "/vaults").url?.absoluteString ?? "")
    }

    func getVault(id: String) async throws -> Vault {
        try await get(path: Self.path("/vaults", id))
    }

    func createVault(beneficiary: String, checkInInterval: UInt64, idempotencyKey: String? = nil) async throws -> Vault {
        let body = CreateVaultRequest(beneficiary: beneficiary, checkInInterval: checkInInterval)
        return try await post(path: "/vaults", body: body, idempotencyKey: idempotencyKey)
    }

    func checkIn(vaultID: String, idempotencyKey: String? = nil) async throws {
        let _: EmptyBody = try await post(path: Self.path("/vaults", vaultID, "checkin"), body: EmptyBody(), idempotencyKey: idempotencyKey)
    }

    func deposit(vaultID: String, amount: Int64) async throws -> Vault {
        try await post(path: Self.path("/vaults", vaultID, "deposit"), body: ["amount": amount])
    }

    func withdraw(vaultID: String, amount: Int64) async throws -> Vault {
        try await post(path: Self.path("/vaults", vaultID, "withdraw"), body: ["amount": amount])
    }

    func updateBeneficiary(vaultID: String, newBeneficiary: String) async throws -> Vault {
        try await post(path: Self.path("/vaults", vaultID, "beneficiary"), body: ["beneficiary": newBeneficiary])
    }

    func acceptBeneficiary(vaultID: String, token: String) async throws {
        let body = ["vault_id": vaultID, "token": token]
        let _: EmptyBody = try await post(path: Self.path("/vaults", vaultID, "accept"), body: body)
    }

    func getTTL(vaultID: String) async throws -> UInt64 {
        let result: [String: UInt64] = try await get(path: Self.path("/vaults", vaultID, "ttl"))
        return result["ttl_remaining"] ?? 0
    }

    // MARK: - 2FA

    func get2FAStatus(vaultID: String) async throws -> TwoFactorStatus {
        try await get(path: Self.path("/vaults", vaultID, "2fa", "status"))
    }

    func enable2FA(vaultID: String, method: TwoFactorMethod, phone: String? = nil, email: String? = nil) async throws -> Enable2FAResponse {
        let body = Enable2FARequest(method: method, phone: phone, email: email)
        return try await post(path: Self.path("/vaults", vaultID, "2fa", "enable"), body: body)
    }

    /// #226: `trustDevice` opt-in — when true the server returns a device trust token.
    func verify2FA(vaultID: String, otp: String, trustDevice: Bool = false) async throws -> Verify2FAResponse {
        try await post(path: Self.path("/vaults", vaultID, "2fa", "verify"), body: Verify2FARequest(otp: otp, trustDevice: trustDevice))
    }

    func disable2FA(vaultID: String) async throws {
        let _: EmptyBody = try await post(path: Self.path("/vaults", vaultID, "2fa", "disable"), body: EmptyBody())
    }

    func challenge2FA(vaultID: String) async throws -> TwoFactorStatus {
        try await post(path: Self.path("/vaults", vaultID, "2fa", "challenge"), body: EmptyBody())
    }

    func clear2FASession(vaultID: String) async throws {
        let _: EmptyBody = try await post(path: Self.path("/vaults", vaultID, "2fa", "session", "clear"), body: EmptyBody())
    }

    // MARK: - #226 Trust Device

    /// Stores a device trust token on the server; call immediately after a successful verify2FA
    /// when the user opted in to "remember this device". The returned token is persisted
    /// in the Keychain and sent as X-Device-Trust-Token on subsequent sensitive requests.
    func trustDevice(vaultID: String) async throws -> TrustDeviceResponse {
        try await post(path: Self.path("/vaults", vaultID, "2fa", "trust-device"), body: TrustDeviceRequest(trustDevice: true))
    }

    // MARK: - #224 Backup Codes

    /// Generates a new set of 8 one-time backup codes (invalidates any previous set).
    /// Codes are shown exactly once — the server stores only hashes thereafter.
    func generateBackupCodes(vaultID: String) async throws -> BackupCodesResponse {
        try await post(path: Self.path("/vaults", vaultID, "2fa", "backup-codes", "generate"), body: EmptyBody())
    }

    /// Returns whether backup codes exist and how many remain (never the code values themselves).
    func getBackupCodesStatus(vaultID: String) async throws -> BackupCodesStatus {
        try await get(path: Self.path("/vaults", vaultID, "2fa", "backup-codes", "status"))
    }

    // MARK: - #225 Switch 2FA Method

    /// Atomically initiates a method switch: sets up the new method in a pending state
    /// and returns provisioning info. The old method remains active until verify2FA
    /// confirms the new one — the account is never left without 2FA protection.
    func switch2FAMethod(vaultID: String, newMethod: TwoFactorMethod, phone: String? = nil, email: String? = nil) async throws -> Enable2FAResponse {
        let body = Switch2FARequest(newMethod: newMethod, phone: phone, email: email)
        return try await post(path: Self.path("/vaults", vaultID, "2fa", "switch"), body: body)
    }

    // MARK: - Push Notifications

    func registerPushToken(_ token: String) async throws {
        let body = PushRegistration(token: token, platform: "ios")
        let _: EmptyBody = try await post(path: "/notifications/register", body: body)
    }

    func unregisterPushToken(_ token: String) async throws {
        var req = request(path: "/notifications/register")
        req.httpMethod = "DELETE"
        req.httpBody = try? JSONEncoder().encode(PushRegistration(token: token, platform: "ios"))
        // Anti-replay: DELETE is a mutation; apply nonce + timestamp (task #121).
        for (field, value) in Self.makeAntiReplayHeaders() {
            req.setValue(value, forHTTPHeaderField: field)
        }
        _ = try await execute(req)
    }

    // #231: Persist notification preferences server-side so they survive reinstall.
    // Best-effort: a failure here does not block the local preference save.
    func updateNotificationPreferences(_ preferences: NotificationPreferences) async throws {
        struct Body: Encodable {
            let ttlWarningsEnabled: Bool
            let checkInRemindersEnabled: Bool
            let quietHoursEnabled: Bool
            let quietHoursStart: Int
            let quietHoursEnd: Int
        }
        let body = Body(
            ttlWarningsEnabled: preferences.ttlWarningsEnabled,
            checkInRemindersEnabled: preferences.checkInRemindersEnabled,
            quietHoursEnabled: preferences.quietHoursEnabled,
            quietHoursStart: preferences.quietHoursStart,
            quietHoursEnd: preferences.quietHoursEnd
        )
        let _: EmptyBody = try await post(path: "/notifications/preferences", body: body)
    }

    // MARK: - Logging Redaction Audit (#111)
    //
    // iOS uses URLSession directly — there is no logging plugin or interceptor in this file.
    // No request or response body, header, or sensitive field is written to os_log, print,
    // NSLog, or any other diagnostic channel anywhere in APIClient.swift.
    //
    // Invariant: any future addition of a logging layer to this client MUST:
    //   1. Be guarded by #if DEBUG ... #endif (or equivalent) so it is stripped from
    //      release builds entirely.
    //   2. Log only HTTP method, path (no query strings bearing tokens), and status code —
    //      never request/response bodies, Authorization headers, 2FA secrets, vault balances,
    //      beneficiary/owner wallet addresses, or acceptance tokens.
    //
    // See shared/api-contract.md §Logging Redaction Policy (#111) for the authoritative
    // cross-platform policy.

    // MARK: - Private helpers

    /// Builds a request path from a fixed prefix plus one or more dynamic
    /// segments (vault/session IDs), percent-encoding each dynamic segment so
    /// an ID containing `?`, `#`, `/`, or `%2F` can never escape its slot and
    /// address a different resource (#598, iOS counterpart of issue 44).
    ///
    /// The first argument is treated as a literal path prefix (e.g. "/vaults");
    /// every subsequent argument is encoded as a single path segment.
    static func path(_ prefix: String, _ segments: String...) -> String {
        var result = prefix
        for segment in segments {
            result += "/" + percentEncodePathSegment(segment)
        }
        return result
    }

    /// Percent-encodes `segment` so it is safe as exactly one path segment.
    /// `.urlPathAllowed` permits "/" (and, on some platforms, other separators),
    /// so those are removed before encoding — otherwise an ID like "a/b" would
    /// still be interpreted as two segments.
    static func percentEncodePathSegment(_ segment: String) -> String {
        var allowed = CharacterSet.urlPathAllowed
        allowed.remove(charactersIn: "/?#%")
        // Fall back to the raw segment only if encoding somehow fails; the
        // characters removed above make that practically impossible.
        return segment.addingPercentEncoding(withAllowedCharacters: allowed) ?? segment
    }

    private func get<T: Decodable>(path: String) async throws -> T {
        var req = request(path: path)
        req.httpMethod = "GET"
        let (data, _) = try await execute(req)
        return try decode(data, path: path)
    }

    private func post<B: Encodable, T: Decodable>(path: String, body: B, idempotencyKey: String? = nil) async throws -> T {
        var req = request(path: path)
        req.httpMethod = "POST"
        req.httpBody = try JSONEncoder().encode(body)
        // Anti-replay: add nonce + timestamp to every mutating request (task #121).
        for (field, value) in Self.makeAntiReplayHeaders() {
            req.setValue(value, forHTTPHeaderField: field)
        }
        // idempotencyKey stays the same across retries of the same logical action (e.g. a
        // queued check-in resubmitted by CheckInSyncTask after a crash), unlike X-Nonce which
        // is intentionally fresh per attempt — see makeAntiReplayHeaders().
        if let idempotencyKey {
            req.setValue(idempotencyKey, forHTTPHeaderField: "X-Idempotency-Key")
        }
        let (data, _) = try await execute(req)
        return try decode(data, path: path)
    }

    private func request(path: String, queryItems: [URLQueryItem]? = nil) -> URLRequest {
        // Build the URL with URLComponents rather than appendingPathComponent so
        // the already-percent-encoded path (see path(_:_:) above) is preserved
        // verbatim instead of being re-encoded or normalized (#598).
        var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false)
        let basePath = components?.percentEncodedPath ?? baseURL.path
        let trimmedBase = basePath.hasSuffix("/") ? String(basePath.dropLast()) : basePath
        let normalizedPath = path.hasPrefix("/") ? path : "/" + path
        components?.percentEncodedPath = trimmedBase + normalizedPath
        if let queryItems, !queryItems.isEmpty {
            components?.queryItems = queryItems
        }
        let finalURL = components?.url ?? baseURL.appendingPathComponent(path)
        var req = URLRequest(url: finalURL)
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let token = KeychainService.shared.loadToken() {
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        return req
    }

    // Anti-replay headers (task #121, see shared/api-contract.md).
    // Applied to every mutating request (POST / DELETE). GET requests are
    // idempotent and do not require replay protection.
    //
    // X-Nonce : 32 cryptographically-random bytes from CryptoKit's
    //           SymmetricKey(size: .bits256), hex-encoded. The server stores
    //           seen nonces and rejects any duplicate within the token's
    //           validity window.
    // X-Timestamp : current Unix epoch in seconds. The server rejects requests
    //               where |server_time − timestamp| > 300 s (5-minute window).
    static func makeAntiReplayHeaders() -> [String: String] {
        // CryptoKit's SymmetricKey draws from the OS CSPRNG (SecRandomCopyBytes
        // underneath), matching shared/api-contract.md's nonce contract.
        let nonceBytes = SymmetricKey(size: .bits256).withUnsafeBytes { Array($0) }
        let nonce = nonceBytes.map { String(format: "%02x", $0) }.joined()
        let timestamp = String(Int(Date().timeIntervalSince1970))
        return ["X-Nonce": nonce, "X-Timestamp": timestamp]
    }

    private func execute(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        // #609: Documented replay_detected recovery (docs/api-error-codes.md). A 400/403
        // whose body is {"error":"replay_detected"} means the server rejected our
        // X-Nonce/X-Timestamp (e.g. clock drift). For mutating requests we regenerate
        // both headers and retry exactly once; GETs are never retried here.
        let (data, http) = try await perform(request)
        guard Self.isReplayDetected(statusCode: http.statusCode, data: data),
              Self.isMutating(method: request.httpMethod) else {
            return (data, http)
        }
        var retryRequest = request
        for (field, value) in Self.makeAntiReplayHeaders() {
            retryRequest.setValue(value, forHTTPHeaderField: field)
        }
        let (retryData, retryHTTP) = try await perform(retryRequest)
        if Self.isReplayDetected(statusCode: retryHTTP.statusCode, data: retryData) {
            // Retry also rejected — surface the normal server error rather than looping.
            let msg = (try? JSONDecoder().decode([String: String].self, from: retryData))?["error"] ?? "Server error"
            throw APIError.serverError(msg)
        }
        return (retryData, retryHTTP)
    }

    /// Whether `data` is the documented `{"error":"replay_detected"}` body for a 400/403.
    static func isReplayDetected(statusCode: Int, data: Data) -> Bool {
        guard statusCode == 400 || statusCode == 403 else { return false }
        return (try? JSONDecoder().decode([String: String].self, from: data))?["error"] == "replay_detected"
    }

    /// POST/DELETE are the mutating verbs this client issues and the only ones that
    /// carry anti-replay headers, so they're the only ones eligible for replay recovery.
    static func isMutating(method: String?) -> Bool {
        method == "POST" || method == "DELETE"
    }

    private func perform(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        // Falling back to a cached response for a mutating request (POST/DELETE — e.g.
        // check-in, withdraw, disable2FA) would make the app report success for an action
        // that never actually reached the server, which is unacceptable for this app.
        let isCacheableRead = request.httpMethod == "GET"

        // APM: capture wall-clock start time before any network work (including retries).
        // CACurrentMediaTime() is monotonic; unaffected by system clock changes.
        let apmStart = CACurrentMediaTime()

        guard networkMonitor.isConnected else {
            if isCacheableRead, let cached = OfflineCache.shared.load(for: request.url?.absoluteString ?? "") {
                // No real HTTP response exists for a cache hit — synthesize a bare 200 with
                // no headers, so e.g. listVaults()'s pagination-cursor header lookup just
                // reports "no more pages" instead of crashing on a missing response.
                let syntheticResponse = HTTPURLResponse(url: request.url ?? baseURL, statusCode: 200, httpVersion: nil, headerFields: nil)!
                // Cache hits are not recorded: no network round-trip occurred, so the
                // duration would not reflect server latency (#APM).
                return (cached, syntheticResponse)
            }
            // Record network-unavailable failures as status -1 so they appear in APM
            // slow-call stats without a valid HTTP status code.
            let durationMs = (CACurrentMediaTime() - apmStart) * 1_000
            PerformanceMonitor.shared.recordAPICall(
                path: request.url?.path ?? "",
                method: request.httpMethod ?? "GET",
                durationMs: durationMs,
                statusCode: -1
            )
            throw APIError.networkUnavailable
        }

        let (data, response): (Data, URLResponse)
        do {
            if Self.isRetryable(method: request.httpMethod) {
                (data, response) = try await withRetry(retryPolicy, isRetryable: Self.isTransientNetworkError) {
                    try await session.data(for: request)
                }
            } else {
                (data, response) = try await session.data(for: request)
            }
        } catch {
            // Transport/TLS/timeout errors that escaped retries: record with statusCode 0.
            let durationMs = (CACurrentMediaTime() - apmStart) * 1_000
            PerformanceMonitor.shared.recordAPICall(
                path: request.url?.path ?? "",
                method: request.httpMethod ?? "GET",
                durationMs: durationMs,
                statusCode: 0
            )
            throw error
        }

        guard let http = response as? HTTPURLResponse else { throw APIError.serverError("Invalid response") }

        // Record duration + status for all responses (2xx, 4xx, 5xx).
        // Per the redaction policy above: only path, method, status, and duration
        // are recorded — no request/response bodies, headers, or tokens.
        let durationMs = (CACurrentMediaTime() - apmStart) * 1_000
        PerformanceMonitor.shared.recordAPICall(
            path: request.url?.path ?? "",
            method: request.httpMethod ?? "GET",
            durationMs: durationMs,
            statusCode: http.statusCode
        )

        switch http.statusCode {
        case 200...299:
            if isCacheableRead {
                OfflineCache.shared.save(data, for: request.url?.absoluteString ?? "")
            }
            return (data, http)
        case 401:
            // The token the server rejected is no longer valid — drop it locally so we
            // don't keep sending it, and so a relaunch correctly shows the sign-in screen.
            KeychainService.shared.deleteToken()
            // #211: a 401 can carry a human-readable reason in its body (e.g. an expired
            // recovery token/proof on /auth/recover/link) — surface it instead of the
            // generic "Authentication required" so the caller isn't left on a dead end.
            // Ordinary session-token 401s have an empty body, so this falls through to the
            // existing .unauthorized behavior unchanged.
            if let message = (try? JSONDecoder().decode([String: String].self, from: data))?["error"] {
                throw APIError.serverError(message)
            }
            throw APIError.unauthorized
        case 404: throw APIError.notFound
        default:
            let msg = (try? JSONDecoder().decode([String: String].self, from: data))?["error"] ?? "Server error"
            throw APIError.serverError(msg)
        }
    }

    private func decode<T: Decodable>(_ data: Data, path: String) throws -> T {
        if T.self == EmptyBody.self { return EmptyBody() as! T }
        do { return try decoder.decode(T.self, from: data) }
        catch {
            DecodingFailureLogger.shared.log(path: path, expectedType: String(describing: T.self), responseBody: data)
            throw APIError.decodingFailed
        }
    }

    // MARK: - Retry

    // GET is the only idempotent verb this client issues — retrying POST/DELETE
    // automatically could double-submit a mutation (check-in, withdrawal, 2FA
    // disable, ...), so only GET requests are eligible for `withRetry`.
    // (The one exception is the #609 replay_detected recovery in execute(_:), which
    // retries a mutation once with fresh anti-replay headers — the server rejected
    // the request before processing it, so no double-submit is possible.)
    static func isRetryable(method: String?) -> Bool { method == "GET" }

    private static func isTransientNetworkError(_ error: Error) -> Bool {
        guard let urlError = error as? URLError else { return false }
        switch urlError.code {
        case .timedOut, .networkConnectionLost, .cannotConnectToHost,
             .cannotFindHost, .dnsLookupFailed, .notConnectedToInternet, .dataNotAllowed:
            return true
        default:
            return false
        }
    }
}

private struct EmptyBody: Codable {}

private struct CreateVaultRequest: Encodable {
    let beneficiary: String
    let checkInInterval: UInt64

    enum CodingKeys: String, CodingKey {
        case beneficiary
        case checkInInterval = "check_in_interval"
    }
}

private struct LinkPasskeyRequest: Encodable {
    let email: String
    let backupCode: String
    let credentialID: String
    let publicKey: String
    let clientDataJSON: String

    enum CodingKeys: String, CodingKey {
        case email
        case backupCode = "backup_code"
        case credentialID = "credential_id"
        case publicKey = "public_key"
        case clientDataJSON = "client_data_json"
    }
}
