import Foundation

// `public` here (and on the members below): TTLWidget.swift's WidgetKit
// timeline provider reads Vault fields from APIClient.listAllVaults() across
// a real module boundary in the SPM build (Package.swift declares TTLWidget
// as a separate target depending on the EthosProtocol target) — internal
// (the Swift default) is invisible outside the defining module.
public struct Vault: Codable, Identifiable, Equatable {
    public let id: String
    public let owner: String
    public let beneficiary: String
    public let balance: Int64
    public let checkInInterval: UInt64
    public let lastCheckIn: Date
    public let ttlRemaining: UInt64?
    public let status: VaultStatus
    /// Which Stellar asset `balance` is denominated in (#222). Every vault today
    /// holds native XLM; this — and `assetIssuer` — exist so a future non-XLM
    /// vault doesn't need a breaking schema change. Decoded defensively:
    /// absent on a server response (every response today) defaults to `"XLM"`.
    public let assetCode: String
    /// The issuing account for `assetCode`, or `nil` for native XLM (mirrors
    /// `AcceptedAsset.issuer`'s convention). See api-contract.md §Vault (#222).
    public let assetIssuer: String?
    /// Monotonic server-side version of this vault's state (#606), used to reject
    /// out-of-order updates: a `GET /vaults/{id}` started before a `vault_updated`
    /// push but completed after it must not overwrite the newer pushed state.
    /// Decoded defensively: absent on a server response (older servers) defaults
    /// to `0`, which compares as "older than any versioned update".
    public let version: UInt64

    public enum VaultStatus: String, Codable {
        case active, expired, released, paused
    }

    public init(id: String, owner: String, beneficiary: String, balance: Int64,
                checkInInterval: UInt64, lastCheckIn: Date, ttlRemaining: UInt64?,
                status: VaultStatus, assetCode: String = "XLM", assetIssuer: String? = nil,
                version: UInt64 = 0) {
        self.id = id
        self.owner = owner
        self.beneficiary = beneficiary
        self.balance = balance
        self.checkInInterval = checkInInterval
        self.lastCheckIn = lastCheckIn
        self.ttlRemaining = ttlRemaining
        self.status = status
        self.assetCode = assetCode
        self.assetIssuer = assetIssuer
        self.version = version
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        owner = try container.decode(String.self, forKey: .owner)
        beneficiary = try container.decode(String.self, forKey: .beneficiary)
        balance = try container.decode(Int64.self, forKey: .balance)
        checkInInterval = try container.decode(UInt64.self, forKey: .checkInInterval)
        lastCheckIn = try container.decode(Date.self, forKey: .lastCheckIn)
        ttlRemaining = try container.decodeIfPresent(UInt64.self, forKey: .ttlRemaining)
        status = try container.decode(VaultStatus.self, forKey: .status)
        assetCode = try container.decodeIfPresent(String.self, forKey: .assetCode) ?? "XLM"
        assetIssuer = try container.decodeIfPresent(String.self, forKey: .assetIssuer)
        version = try container.decodeIfPresent(UInt64.self, forKey: .version) ?? 0
    }

    /// Whether `incoming` is newer than this vault's state and should replace it
    /// (#606). An unversioned response (`version == 0`) is only accepted when the
    /// currently applied state is also unversioned, so a stale poll can never
    /// clobber a newer versioned push.
    public func shouldApply(_ incoming: Vault) -> Bool {
        incoming.version > version || (incoming.version == 0 && version == 0)
    }

    public var isExpiringSoon: Bool {
        guard let ttl = ttlRemaining else { return false }
        return ttl < 86_400 // < 24 hours
    }

    /// Issue #480: predicted check-in deadline, computed from `lastCheckIn` +
    /// `checkInInterval` when the server hasn't supplied a fresher `ttlRemaining`
    /// snapshot. This is a deterministic projection of the existing rule (the
    /// vault releases to the beneficiary if the owner doesn't check in within
    /// `checkInInterval` of `lastCheckIn`), not a statistical forecast — there is
    /// no hidden uncertainty to model here, only a date arithmetic the UI wasn't
    /// previously surfacing directly.
    public var predictedExpiryDate: Date {
        if let ttl = ttlRemaining {
            return Date().addingTimeInterval(TimeInterval(ttl))
        }
        return lastCheckIn.addingTimeInterval(TimeInterval(checkInInterval))
    }

    public enum ExpiryRiskLevel: String {
        case safe, approaching, critical
    }

    /// Issue #480: a simple planning signal — what fraction of the check-in
    /// window has elapsed since the last check-in, bucketed into three tiers.
    /// This is a heuristic on the existing deterministic deadline, not a
    /// learned model of user behavior (that would need historical check-in
    /// timing data this client doesn't currently retain).
    public func expiryRiskLevel(asOf now: Date = Date()) -> ExpiryRiskLevel {
        guard checkInInterval > 0 else { return .safe }
        let elapsed = now.timeIntervalSince(lastCheckIn)
        let fractionElapsed = elapsed / TimeInterval(checkInInterval)
        if fractionElapsed >= 0.9 { return .critical }
        if fractionElapsed >= 0.7 { return .approaching }
        return .safe
    }

    /// Formats `balance` (stroops) in `assetCode`. Assumes the 7-decimal stroop
    /// scale that applies to every Stellar classic asset regardless of code —
    /// only the unit label varies (#222).
    /// Uses device locale for decimal separator and digit grouping.
    public var formattedBalance: String {
        let amount = Double(balance) / 10_000_000
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        formatter.minimumFractionDigits = 7
        formatter.maximumFractionDigits = 7
        formatter.usesGroupingSeparator = true
        let formattedAmount = formatter.string(from: NSNumber(value: amount)) ?? String(format: "%.7f", amount)
        return formattedAmount + " " + assetCode
    }
}

/// Client-side countdown derived from a server-provided TTL snapshot (`GET
/// /vaults/{id}/ttl`), ticked locally between refreshes so the displayed value
/// counts down in real time instead of visibly freezing until the next poll or
/// `vault_updated` push (#221).
struct TTLCountdown: Equatable {
    /// The TTL value (seconds remaining) last reported by the server.
    private(set) var serverValue: UInt64
    /// When `serverValue` was fetched — the baseline the local tick counts down from.
    private(set) var fetchedAt: Date

    init(serverValue: UInt64, fetchedAt: Date = Date()) {
        self.serverValue = serverValue
        self.fetchedAt = fetchedAt
    }

    /// Seconds remaining as of `now`, ticking down from `serverValue`. Never goes
    /// below zero, even once the local tick has run past a stale server value.
    func remaining(at now: Date = Date()) -> UInt64 {
        let elapsed = now.timeIntervalSince(fetchedAt)
        guard elapsed > 0 else { return serverValue }
        let remaining = Double(serverValue) - elapsed
        return remaining > 0 ? UInt64(remaining) : 0
    }

    /// Reconciles with a fresh server value, from either a poll or a `vault_updated`
    /// push (#223: both are treated identically — whichever arrives is applied). The
    /// server value always wins over wherever the local tick has drifted to: this
    /// replaces the baseline outright rather than comparing against it.
    mutating func reconcile(serverValue: UInt64, at now: Date = Date()) {
        self.serverValue = serverValue
        self.fetchedAt = now
    }
}

// Shared amount parsing/validation for the deposit and withdraw flows, which both
// take a user-entered XLM string and need to convert/validate it against the
// vault's stroop-denominated balance the same way.
enum VaultAmount {
    static let stroopsPerXLM = 10_000_000.0

    /// Parses a user-entered XLM amount string into stroops, or nil if the input
    /// isn't a positive, finite number.
    static func parseStroops(_ input: String) -> Int64? {
        guard let value = Double(input), value.isFinite, value > 0 else { return nil }
        let stroops = value * stroopsPerXLM
        guard stroops <= Double(Int64.max) else { return nil }
        return Int64(stroops)
    }

    static func hasSufficientBalance(amount: Int64, vaultBalance: Int64) -> Bool {
        amount > 0 && amount <= vaultBalance
    }
}

/// Structured, user-facing presentation of an error — derived from APIError when
/// possible so decode/server failures surface a concrete next step ("Try Again" /
/// "Contact Support") instead of a bare message with no available action.
struct ErrorPresentation: Equatable {
    let message: String
    let recoverySuggestion: String?
    let showsRetry: Bool
    let showsContactSupport: Bool

    init(_ error: Error) {
        if let apiError = error as? APIError {
            message = apiError.errorDescription ?? "Something went wrong"
            recoverySuggestion = apiError.recoverySuggestion
            showsRetry = apiError.isRetryable
            showsContactSupport = apiError.suggestsContactSupport
        } else {
            message = error.localizedDescription
            recoverySuggestion = nil
            showsRetry = false
            showsContactSupport = false
        }
    }

    init(message: String, recoverySuggestion: String? = nil, showsRetry: Bool = false, showsContactSupport: Bool = false) {
        self.message = message
        self.recoverySuggestion = recoverySuggestion
        self.showsRetry = showsRetry
        self.showsContactSupport = showsContactSupport
    }
}

// Client-side mirror of the backend's username policy for passkey registration, so
// malformed input is caught before it's sent as the WebAuthn userID/display name instead
// of surfacing as a generic backend rejection.
enum UsernameValidation {
    static let minLength = 3
    static let maxLength = 30
    private static let allowedCharacters = CharacterSet(charactersIn:
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-")

    enum ValidationError: LocalizedError, Equatable {
        case tooShort
        case tooLong
        case invalidCharacters

        var errorDescription: String? {
            switch self {
            case .tooShort:
                return "Username must be at least \(UsernameValidation.minLength) characters."
            case .tooLong:
                return "Username must be \(UsernameValidation.maxLength) characters or fewer."
            case .invalidCharacters:
                return "Username can only contain letters, numbers, underscores, and hyphens."
            }
        }
    }

    /// Trims leading/trailing whitespace, then validates length and character set.
    /// Returns the trimmed username on success, or the first validation failure found.
    static func validate(_ input: String) -> Result<String, ValidationError> {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count >= minLength else { return .failure(.tooShort) }
        guard trimmed.count <= maxLength else { return .failure(.tooLong) }
        guard trimmed.unicodeScalars.allSatisfy(allowedCharacters.contains) else { return .failure(.invalidCharacters) }
        return .success(trimmed)
    }
}

/// Guards an irreversible action (delete/archive a vault, etc.) behind a typed
/// confirmation rather than a plain Yes/No tap — codified now as a guardrail
/// before any delete/archive endpoint is wired up on either client (#220),
/// given the financial and beneficiary implications of getting this wrong.
/// `requiredText` is typically the vault's own name/ID, so confirming requires
/// the user to actually read and type it back exactly.
struct DestructiveConfirmation: Equatable {
    let requiredText: String
    var enteredText: String = ""

    var isConfirmed: Bool {
        !requiredText.isEmpty && enteredText == requiredText
    }

    /// Runs `action` only if `isConfirmed` — the single choke point the
    /// destructive-confirmation views use, so "the button is disabled" and
    /// "the underlying action never fires" can't drift apart from each other.
    func confirmIfMatched(_ action: () -> Void) {
        guard isConfirmed else { return }
        action()
    }
}

enum BeneficiaryUpdate {
    /// A new beneficiary address is only valid if it's non-empty (after trimming)
    /// and actually differs from the vault's current beneficiary.
    static func isValidNewBeneficiary(_ input: String, currentBeneficiary: String) -> Bool {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        return !trimmed.isEmpty && trimmed != currentBeneficiary
    }
}

struct AuthChallenge: Codable {
    let challenge: String
    let expiresAt: Date
    // Credential IDs already registered to the account this challenge is for. Empty
    // when there are none yet (e.g. a brand-new account) or when the server predates
    // this field — decoded defensively so older/partial responses still parse.
    let existingCredentialIds: [String]

    init(challenge: String, expiresAt: Date, existingCredentialIds: [String] = []) {
        self.challenge = challenge
        self.expiresAt = expiresAt
        self.existingCredentialIds = existingCredentialIds
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        challenge = try container.decode(String.self, forKey: .challenge)
        expiresAt = try container.decode(Date.self, forKey: .expiresAt)
        existingCredentialIds = try container.decodeIfPresent([String].self, forKey: .existingCredentialIds) ?? []
    }
}

struct AuthToken: Codable {
    let token: String
    let expiresAt: Date
}

/// Proof of identity for an existing vault-owning account that lost its original
/// passkey-holding device, gathered by RecoverAccessView before a new passkey may
/// be linked to that account (see PasskeyService.linkAdditionalPasskey).
struct AccountRecoveryProof: Codable {
    let email: String
    let backupCode: String
}

struct PushRegistration: Codable {
    let token: String
    let platform: String  // "ios" | "android"
}

// MARK: - 2FA Models

enum TwoFactorMethod: String, Codable, CaseIterable {
    case totp
    case sms
    case email
}

/// #227: `availableMethods` lists the methods the server accepts for this account.
/// Clients filter the method-selection UI to only what is listed here.
/// Decoded defensively: absent field (older server) defaults to all three methods.
struct TwoFactorStatus: Codable {
    let vaultId: String
    let enabled: Bool
    let method: TwoFactorMethod?
    let verified: Bool
    let phone: String?
    let email: String?
    /// #227: Methods the server currently makes available for this account/vault.
    let availableMethods: [TwoFactorMethod]

    init(
        vaultId: String,
        enabled: Bool,
        method: TwoFactorMethod?,
        verified: Bool,
        phone: String?,
        email: String?,
        availableMethods: [TwoFactorMethod] = TwoFactorMethod.allCases
    ) {
        self.vaultId = vaultId
        self.enabled = enabled
        self.method = method
        self.verified = verified
        self.phone = phone
        self.email = email
        self.availableMethods = availableMethods
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        vaultId = try container.decode(String.self, forKey: .vaultId)
        enabled = try container.decode(Bool.self, forKey: .enabled)
        method = try container.decodeIfPresent(TwoFactorMethod.self, forKey: .method)
        verified = try container.decodeIfPresent(Bool.self, forKey: .verified) ?? false
        phone = try container.decodeIfPresent(String.self, forKey: .phone)
        email = try container.decodeIfPresent(String.self, forKey: .email)
        // #227: Default to all methods when the server doesn't send this field (backward-compat).
        availableMethods = try container.decodeIfPresent([TwoFactorMethod].self, forKey: .availableMethods)
            ?? TwoFactorMethod.allCases
    }
}

struct Enable2FARequest: Codable {
    let method: TwoFactorMethod
    let phone: String?
    let email: String?
}

struct Enable2FAResponse: Codable {
    let vaultId: String
    let method: TwoFactorMethod
    let secret: String?
    let provisioningUri: String?
}

/// #226: `trustDevice` opt-in — when true the server issues a device trust token valid 30 days.
struct Verify2FARequest: Codable {
    let otp: String
    let trustDevice: Bool

    init(otp: String, trustDevice: Bool = false) {
        self.otp = otp
        self.trustDevice = trustDevice
    }
}

/// #226: Response when `trust_device: true` — carries the opaque trust token and its expiry.
struct Verify2FAResponse: Codable {
    let deviceTrustToken: String?
    let expiresAt: Date?
}

// MARK: - #226 Trusted-Device Models

struct TrustDeviceRequest: Codable {
    let trustDevice: Bool
}

struct TrustDeviceResponse: Codable {
    let deviceTrustToken: String
    let expiresAt: Date
}

// MARK: - #224 Backup Codes Models

struct BackupCodesResponse: Codable {
    let codes: [String]
    let generatedAt: Date
}

struct BackupCodesStatus: Codable {
    let generated: Bool
    let remainingCount: Int
}

// MARK: - #225 Switch 2FA Method Models

struct Switch2FARequest: Codable {
    let newMethod: TwoFactorMethod
    let phone: String?
    let email: String?

    init(newMethod: TwoFactorMethod, phone: String? = nil, email: String? = nil) {
        self.newMethod = newMethod
        self.phone = phone
        self.email = email
    }
}

// MARK: - Pagination Models (#112)

/// Paginated response for `GET /vaults`. See api-contract.md §Pagination.
struct VaultPage: Codable {
    let vaults: [Vault]
    /// Opaque cursor for the next page, or `nil` if this is the last page.
    let nextCursor: String?
    let hasMore: Bool
}
