import Foundation
import Combine
import SwiftUI

// Runs `mutation` only if the current Task hasn't been cancelled. Guards
// @Published/@State writes that happen after an `await` — if whatever launched
// the request has since gone away and cancelled its Task (e.g. a view
// disappeared), the stale write is dropped instead of mutating state nobody is
// observing anymore. See VaultStore/AuthStore and VaultDetailView.
@MainActor
func ifNotCancelled(_ mutation: () -> Void) {
    guard !Task.isCancelled else { return }
    mutation()
}

@MainActor
final class AuthStore: ObservableObject {
    @Published var isAuthenticated = false
    @Published var isLoading = false
    @Published var error: ErrorPresentation?
    @Published var isLocked = false
    @Published var showPINSetup = false

    // Injected for testing; defaults to the real APIClient call. See
    // BackgroundRefreshService.vaultListProvider for the same pattern.
    var unregisterPushToken: (String) async throws -> Void = { token in
        try await APIClient.shared.unregisterPushToken(token)
    }

    // How long before AuthToken.expiresAt to proactively refresh (#3), and how long to
    // wait before retrying after a transient (non-auth) refresh failure.
    private let refreshLeadTime: TimeInterval = 60
    private let refreshRetryDelay: TimeInterval = 30
    private var refreshTask: Task<Void, Never>?
    private var backgroundedAt: Date?

    // Injectable seams for testing (mirrors BackgroundRefreshService.vaultListProvider):
    // ASAuthorizationController-driven ceremonies can't run in a unit test, so AuthStore's
    // own orchestration (single ceremony on register, proactive refresh scheduling,
    // delete-and-reauth fallback) is exercised against these instead.
    // Registration now also yields the server-issued opaque WebAuthn user handle
    // (#624). It is persisted so the same handle is reused for every subsequent
    // ceremony on this account, rather than one derived from the username.
    var passkeyRegister: (String) async throws -> PasskeyRegistrationResult = {
        try await PasskeyService.shared.register(username: $0)
    }
    var passkeyAuthenticate: () async throws -> AuthToken = { try await PasskeyService.shared.authenticate() }
    var refreshToken: () async throws -> AuthToken = { try await APIClient.shared.refreshToken() }
    var linkAdditionalPasskey: (String, AccountRecoveryProof) async throws -> String = { username, proof in
        try await PasskeyService.shared.linkAdditionalPasskey(username: username, existingAccountProof: proof)
    }

    init() {
        isAuthenticated = KeychainService.shared.loadToken() != nil
        if isAuthenticated, let expiresAt = KeychainService.shared.loadTokenExpiry() {
            scheduleRefresh(before: expiresAt)
        }
    }

    func signIn() async {
        isLoading = true; error = nil
        do {
            let token = try await passkeyAuthenticate()
            KeychainService.shared.saveToken(token.token, expiresAt: token.expiresAt)
            ifNotCancelled {
                isAuthenticated = true
                BiometricTimeoutIndicatorService.shared.startTimeout()
                scheduleRefresh(before: token.expiresAt)
            }
        } catch {
            ifNotCancelled { self.error = ErrorPresentation(error) }
        }
        // Unlike the writes above, always reset regardless of cancellation: it's
        // a loading-spinner flag, not stale request data, and leaving it true
        // would strand the UI mid-spin if this Task gets cancelled.
        isLoading = false
    }

    // A single passkey ceremony: PasskeyService.register() already registers with the
    // backend (which returns a session token directly) and persists the credential ID
    // atomically with that call (#4) — there is no second, redundant authenticate()
    // ceremony / biometric prompt here (#2).
    func register(username: String) async {
        isLoading = true; error = nil
        do {
            let result = try await passkeyRegister(username)
            // Persist the opaque user handle before anything else so it survives a
            // crash between registration and the first authenticated request.
            KeychainService.shared.saveUserHandle(result.userHandle)
            let token = result.token
            KeychainService.shared.saveToken(token.token, expiresAt: token.expiresAt)
            ifNotCancelled {
                isAuthenticated = true
                BiometricTimeoutIndicatorService.shared.startTimeout()
                scheduleRefresh(before: token.expiresAt)
            }
        } catch {
            ifNotCancelled { self.error = ErrorPresentation(error) }
        }
        isLoading = false
    }

    /// Links a newly created passkey to an existing vault-owning account (for a user who
    /// lost their original device), then signs in with it. `linkAdditionalPasskey` only
    /// attaches the credential server-side and returns no session — a normal passkey
    /// authenticate() ceremony against the freshly-linked credential is what actually
    /// establishes the session, mirroring signIn().
    func recoverAccess(email: String, backupCode: String, username: String) async {
        isLoading = true; error = nil
        do {
            _ = try await linkAdditionalPasskey(username, AccountRecoveryProof(email: email, backupCode: backupCode))
            let token = try await passkeyAuthenticate()
            KeychainService.shared.saveToken(token.token, expiresAt: token.expiresAt)
            ifNotCancelled {
                isAuthenticated = true
                BiometricTimeoutIndicatorService.shared.startTimeout()
                scheduleRefresh(before: token.expiresAt)
            }
        } catch {
            ifNotCancelled { self.error = ErrorPresentation(error) }
        }
        isLoading = false
    }

    func signOut() async {
        refreshTask?.cancel()
        refreshTask = nil
        BiometricTimeoutIndicatorService.shared.stopTimeout()
        // Unregister before dropping the auth token: the request needs the
        // still-valid Bearer token to authenticate, or the server rejects it.
        if let pushToken = KeychainService.shared.loadPushToken() {
            try? await unregisterPushToken(pushToken)
            KeychainService.shared.deletePushToken()
        }
        KeychainService.shared.deleteToken()
        // The passkey credential ID and its iCloud-synced vault association both belong to
        // this account — a subsequent user signing in on the same device must not inherit
        // them.
        KeychainService.shared.deleteCredentialID()
        // The opaque WebAuthn user handle (#624) is account-scoped too — clear it so a
        // subsequent user on this device never reuses the previous account's handle.
        KeychainService.shared.deleteUserHandle()
        ICloudSyncService.shared.clearLocalAssociations()
        // Ties into #10: a subsequent user signing in on the same device must never be served
        // the previous user's cached vault data while offline.
        OfflineCache.shared.clearAll()
        isAuthenticated = false
        isLocked = false
        backgroundedAt = nil
    }

    /// Called from RootView's `.onChange(of: scenePhase)`. Records when the app leaves the
    /// foreground and, once it returns, re-locks the vault behind a fresh biometric check if
    /// it was backgrounded for at least the configured re-lock timeout. `now:` is injectable
    /// so tests can simulate elapsed time without real waits.
    func handleScenePhaseChange(_ phase: ScenePhase, now: Date = Date()) {
        switch phase {
        case .background:
            backgroundedAt = now
            BiometricTimeoutIndicatorService.shared.stopTimeout()
        case .active:
            if let backgroundedAt, isAuthenticated,
               now.timeIntervalSince(backgroundedAt) >= ReLockTimeoutOption.current.seconds {
                isLocked = true
                BiometricTimeoutIndicatorService.shared.stopTimeout()
            } else if isAuthenticated && !isLocked {
                BiometricTimeoutIndicatorService.shared.startTimeout()
            }
            backgroundedAt = nil
        case .inactive:
            break
        @unknown default:
            break
        }
    }

    // MARK: - Token refresh (#3)
    //
    // AuthToken.expiresAt was decoded but never read; a 401 was handled purely
    // reactively by deleting the token and forcing a full passkey re-authentication,
    // which can interrupt the user mid-task (e.g. mid check-in). This schedules a
    // proactive refresh shortly before expiry instead. The reactive delete-and-reauth
    // behavior in APIClient.execute() remains as-is and is the fallback used here
    // whenever the refresh call itself is rejected by the server.

    private func scheduleRefresh(before expiresAt: Date) {
        scheduleRefresh(at: expiresAt.addingTimeInterval(-refreshLeadTime))
    }

    private func scheduleRefresh(at fireDate: Date) {
        refreshTask?.cancel()
        let delay = max(0, fireDate.timeIntervalSinceNow)
        refreshTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard !Task.isCancelled else { return }
            await self?.performRefresh()
        }
    }

    private func performRefresh() async {
        do {
            let token = try await refreshToken()
            KeychainService.shared.saveToken(token.token, expiresAt: token.expiresAt)
            ifNotCancelled { scheduleRefresh(before: token.expiresAt) }
        } catch APIError.unauthorized {
            // The server rejected the token outright — APIClient.execute() already
            // deleted it locally. Fall back to forcing a full re-authentication.
            ifNotCancelled { isAuthenticated = false }
        } catch {
            // Transient failure (e.g. offline) — retry later rather than signing the
            // user out over a network hiccup.
            ifNotCancelled { scheduleRefresh(at: Date().addingTimeInterval(refreshRetryDelay)) }
        }
    }
}

/// How long the app can sit in the background before `AuthStore` requires biometrics again.
/// Persisted so the choice survives relaunch; configurable from SettingsView.
enum ReLockTimeoutOption: Int, CaseIterable, Identifiable {
    case immediately = 0
    case thirtySeconds = 30
    case oneMinute = 60
    case fiveMinutes = 300
    case fifteenMinutes = 900
    case never = -1

    var id: Int { rawValue }

    var seconds: TimeInterval {
        self == .never ? .infinity : TimeInterval(rawValue)
    }

    var label: String {
        switch self {
        case .immediately:    return "Immediately"
        case .thirtySeconds:  return "30 Seconds"
        case .oneMinute:      return "1 Minute"
        case .fiveMinutes:    return "5 Minutes"
        case .fifteenMinutes: return "15 Minutes"
        case .never:          return "Never"
        }
    }

    private static let userDefaultsKey = "com.ethosprotocol.relock_timeout"

    static var current: ReLockTimeoutOption {
        get {
            guard let stored = UserDefaults.standard.object(forKey: userDefaultsKey) as? Int,
                  let option = ReLockTimeoutOption(rawValue: stored) else {
                return .oneMinute
            }
            return option
        }
        set { UserDefaults.standard.set(newValue.rawValue, forKey: userDefaultsKey) }
    }
}

/// Backs SessionsView (#208): the list of devices currently holding a valid JWT for this
/// account, plus the "Sign out this device" / "Sign out all other devices" actions. Both
/// mutating actions are expected to be gated behind a biometric prompt by the caller (the
/// view), same as VaultStore.withdraw — this store just performs the already-authorized action.
@MainActor
final class SessionsStore: ObservableObject {
    @Published var sessions: [Session] = []
    @Published var isLoading = false
    @Published var error: ErrorPresentation?

    // Injected for testing; defaults to the real APIClient calls.
    var listSessions: () async throws -> [Session] = { try await APIClient.shared.listSessions() }
    var revokeSession: (String) async throws -> Void = { try await APIClient.shared.revokeSession(id: $0) }
    var revokeOtherSessions: () async throws -> Void = { try await APIClient.shared.revokeOtherSessions() }

    func load() async {
        isLoading = true; error = nil
        do {
            sessions = try await listSessions()
        } catch {
            self.error = ErrorPresentation(error)
        }
        isLoading = false
    }

    /// Signs out the device behind `session`. Removes it from the local list immediately on
    /// success rather than requiring a full reload.
    func revoke(_ session: Session) async {
        error = nil
        do {
            try await revokeSession(session.id)
            sessions.removeAll { $0.id == session.id }
        } catch {
            self.error = ErrorPresentation(error)
        }
    }

    /// Signs out every device except the current one, then reloads so the list reflects the
    /// server's view (rather than assuming every non-current session was in `sessions`).
    func revokeAllOthers() async {
        error = nil
        do {
            try await revokeOtherSessions()
            await load()
        } catch {
            self.error = ErrorPresentation(error)
        }
    }
}

@MainActor
final class VaultStore: ObservableObject {
    @Published var vaults: [Vault] = []
    @Published var isLoading = false
    @Published var isLoadingMore = false
    @Published var error: ErrorPresentation?
    @Published var pendingDeepLink: UniversalLinkRouter.DeepLink?
    @Published private(set) var nextCursor: String?
    /// How long ago the currently-displayed `vaults` were fetched, when served from the
    /// offline cache (APIClient.vaultsCacheAge()) rather than a fresh network response.
    @Published var vaultsCacheAge: TimeInterval?
    /// Mirrors PendingCheckInStore's count for while the app is foregrounded — drives the
    /// in-app "N check-ins queued" banner alongside NotificationService's queued indicator.
    @Published private(set) var queuedCheckInCount = 0
    /// Current WebSocket connection state for the real-time event stream (#255).
    @Published private(set) var socketConnectionState: VaultEventSocket.ConnectionState = .disconnected
    /// Timestamp of the last successful vault sync, used for displaying refresh feedback.
    @Published private(set) var lastSyncTime: Date?
    /// Search text for filtering vaults by ID/name
    @Published var searchText: String = "" {
        didSet { saveSearchPreferences() }
    }
    /// Status filter for vault list (Healthy, Expiring Soon, etc.)
    @Published var statusFilter: VaultStatusFilter = .all {
        didSet { saveSearchPreferences() }
    }
    /// Sort option for vault list (by expiry, name, creation date)
    @Published var sortOption: VaultSortOption = .expiryDate {
        didSet { saveSearchPreferences() }
    }
    /// Issue #477: filter to vaults carrying this tag, or nil for no tag filter.
    @Published var tagFilter: String?

    private var eventSocket: VaultEventSocket?

    /// Highest `Vault.version` applied per vault ID. A `GET /vaults` poll started before a
    /// `vault_updated` push can complete after it; without this, the older poll response
    /// would overwrite the newer pushed state (#606). Any incoming vault whose version is
    /// not greater than the recorded one is dropped instead of applied.
    private var appliedVersions: [String: Int] = [:]

    /// Whether a further page is available for VaultListView's "Load More".
    var hasMorePages: Bool { nextCursor != nil }

    /// Filtered and sorted vaults based on search text, status filter, sort option,
    /// and (#477) tag filter.
    var filteredAndSortedVaults: [Vault] {
        // Issue #474: vaults merged away into another vault are hidden from
        // the main list — they still exist and VaultMergeStore can un-merge
        // them, but they no longer clutter the primary view.
        let hidden = VaultMergeStore.hiddenVaultIds()
        return vaults
            .filter { vault in
                !hidden.contains(vault.id) &&
                statusFilter.matches(vault: vault) &&
                (searchText.isEmpty || vault.id.lowercased().contains(searchText.lowercased())) &&
                (tagFilter == nil || VaultTagStore.tags(for: vault.id).contains(tagFilter!))
            }
            .sorted { lhs, rhs in
                sortOption.compare(lhs: lhs, rhs: rhs)
            }
    }

    /// Issue #474: duplicate-vault groups (same beneficiary, different vault
    /// IDs), excluding any pair where one side has already been merged away.
    var duplicateGroups: [VaultDuplicateGroup] {
        let hidden = VaultMergeStore.hiddenVaultIds()
        return VaultDuplicateDetector.findDuplicateGroups(in: vaults.filter { !hidden.contains($0.id) })
    }

    init() {
        loadSearchPreferences()
    }

    private func loadSearchPreferences() {
        let preferences = VaultSearchPreferences.current
        searchText = preferences.searchText
        if let filter = VaultStatusFilter(rawValue: preferences.statusFilter) {
            statusFilter = filter
        }
        if let sort = VaultSortOption(rawValue: preferences.sortOption) {
            sortOption = sort
        }
    }

    private func saveSearchPreferences() {
        var preferences = VaultSearchPreferences.current
        preferences.searchText = searchText
        preferences.statusFilter = statusFilter.rawValue
        preferences.sortOption = sortOption.rawValue
        VaultSearchPreferences.current = preferences
    }

    private func updateQueuedIndicator() {
        queuedCheckInCount = PendingCheckInStore.shared.count
    }

    func load() async {
        isLoading = true; error = nil
        if NetworkMonitor.shared.isConnected {
            await CheckInSyncTask.shared.performSync()
            updateQueuedIndicator()
        }
        do {
            let page = try await APIClient.shared.listVaults()
            ifNotCancelled {
                let (dedupedVaults, duplicateCount) = deduplicateVaults(page.vaults)
                vaults = mergePolledVaults(dedupedVaults)
                nextCursor = page.nextCursor
                if duplicateCount > 0 {
                    let message = duplicateCount == 1
                        ? "Removed 1 duplicate vault from your list"
                        : "Removed \(duplicateCount) duplicate vaults from your list"
                    self.error = ErrorPresentation(message: message)
                    DuplicateVaultLogger.shared.logDeduplication(count: duplicateCount)
                }
                scheduleReminders()
                // Donate all active vaults to Spotlight so users can find them from
                // the home screen without opening the app (#440).
                let activeVaults = dedupedVaults.filter { $0.status == .active }
                SpotlightService.shared.donateAll(vaults: activeVaults)
            }
        } catch APIError.networkUnavailable {
            // Vaults already populated from offline cache via APIClient
            ifNotCancelled { vaultsCacheAge = APIClient.shared.vaultsCacheAge() }
        } catch {
            ifNotCancelled { self.error = ErrorPresentation(error) }
        }
        isLoading = false
    }

    /// Fetches the next page after `nextCursor` and appends it to `vaults`, for
    /// VaultListView's "Load More" row. No-ops if a fetch is already in flight or
    /// there is no further page.
    func loadMore() async {
        guard !isLoadingMore, let cursor = nextCursor else { return }
        isLoadingMore = true; error = nil
        do {
            let page = try await APIClient.shared.listVaults(cursor: cursor)
            ifNotCancelled {
                vaults += page.vaults
                nextCursor = page.nextCursor
            }
        } catch {
            ifNotCancelled { self.error = ErrorPresentation(error) }
        }
        isLoadingMore = false
    }

    /// Fetches all vault pages via cursor-based pagination (#112) and replaces the local list.
    func loadAll(limit: Int = 20) async {
        isLoading = true; error = nil
        do {
            var accumulated: [Vault] = []
            var cursor: String? = nil
            repeat {
                let page = try await APIClient.shared.listVaults(cursor: cursor, limit: limit)
                accumulated.append(contentsOf: page.vaults)
                cursor = page.nextCursor
                if Task.isCancelled { return }
            } while cursor != nil
            ifNotCancelled {
                let (dedupedVaults, duplicateCount) = deduplicateVaults(accumulated)
                vaults = mergePolledVaults(dedupedVaults)
                if duplicateCount > 0 {
                    let message = duplicateCount == 1
                        ? "Removed 1 duplicate vault from your list"
                        : "Removed \(duplicateCount) duplicate vaults from your list"
                    self.error = ErrorPresentation(message: message)
                    DuplicateVaultLogger.shared.logDeduplication(count: duplicateCount)
                }
                scheduleReminders()
            }
        } catch APIError.networkUnavailable {
            // Served from offline cache — keep whatever is already in `vaults`.
        } catch {
            ifNotCancelled { self.error = ErrorPresentation(error) }
        }
        isLoading = false
    }

    func checkIn(vault: Vault) async {
        // Generated once and reused for both this attempt and any subsequent queued retry, so
        // a resubmission after a process death is identifiable to the server as a duplicate of
        // this specific attempt rather than a brand-new check-in.
        let idempotencyKey = UUID().uuidString
        do {
            // `checkIn(vaultID:idempotencyKey:)` is ambiguous between APIClient's original
            // throwing/Void signature and the CheckInSyncTask.APIClientProtocol conformance's
            // overload — pin the reference to the original before calling it.
            let performCheckIn: (String, String?) async throws -> Void = APIClient.shared.checkIn(vaultID:idempotencyKey:)
            try await performCheckIn(vault.id, idempotencyKey)
            VaultActionLog.record(.checkIn, for: vault.id) // #479
            if !Task.isCancelled { await load() }
        } catch APIError.networkUnavailable {
            // Offline: queue the check-in durably so it is retried when connectivity
            // returns, mirroring Android's VaultViewModel.checkIn → PendingCheckInDao
            // + CheckInSyncWorker pattern.
            let item = PendingCheckIn(vaultId: vault.id, queuedAt: Date(), idempotencyKey: idempotencyKey)
            do {
                // A check-in is the most time-sensitive action the app queues, so a failed
                // write must not be swallowed (#615): surface it as a blocking alert rather
                // than letting the user believe the check-in is safely queued.
                try PendingCheckInStore.shared.insert(item)
            } catch {
                ifNotCancelled {
                    self.error = ErrorPresentation(message: "Offline — your check-in could not be saved and was not queued. Please try again.")
                }
                return
            }
            let count = PendingCheckInStore.shared.count
            NotificationService.shared.showQueuedCheckIn(count: count)
            CheckInSyncTask.shared.scheduleSync()
            ifNotCancelled {
                queuedCheckInCount = count
                self.error = ErrorPresentation(message: "Offline — check-in queued and will retry automatically")
            }
        } catch {
            ifNotCancelled { self.error = ErrorPresentation(error) }
        }
    }

    /// Deposits `amount` stroops into the vault and reloads the vault list on success.
    func deposit(vault: Vault, amount: Int64) async {
        error = nil
        do {
            _ = try await APIClient.shared.deposit(vaultID: vault.id, amount: amount)
            VaultActionLog.record(.deposit, for: vault.id) // #479
            if !Task.isCancelled { await load() }
        } catch {
            ifNotCancelled { self.error = ErrorPresentation(error) }
        }
    }

    /// Withdraws `amount` stroops from the vault (biometric gate must be called by the UI
    /// before invoking this) and reloads the vault list on success.
    func withdraw(vault: Vault, amount: Int64) async {
        error = nil
        do {
            _ = try await APIClient.shared.withdraw(vaultID: vault.id, amount: amount)
            VaultActionLog.record(.withdraw, for: vault.id) // #479
            if !Task.isCancelled { await load() }
        } catch {
            ifNotCancelled { self.error = ErrorPresentation(error) }
        }
    }

    /// Updates the beneficiary address for a vault (biometric gate must be called by the UI
    /// before invoking this) and reloads the vault list on success.
    func updateBeneficiary(vault: Vault, newBeneficiary: String) async {
        error = nil
        do {
            _ = try await APIClient.shared.updateBeneficiary(vaultID: vault.id, newBeneficiary: newBeneficiary)
            if !Task.isCancelled { await load() }
        } catch {
            ifNotCancelled { self.error = ErrorPresentation(error) }
        }
    }

    /// Replaces the vault matching `updated.id` in place (preserving list order), or
    /// appends it if it isn't currently in `vaults` — e.g. a vault created from another
    /// device that this session hasn't loaded yet.
    ///
    /// Called for a `vault_updated` push (`subscribeToEvents`). Ignores the update if a
    /// newer version has already been applied, so an out-of-order push can't regress state.
    /// See the "Reconciling a poll/push disagreement" rule in api-contract.md (#223, #606).
    func applyUpdate(_ updated: Vault) {
        guard isNewer(updated) else { return }
        recordApplied(updated)
        if let index = vaults.firstIndex(where: { $0.id == updated.id }) {
            vaults[index] = updated
        } else {
            vaults.append(updated)
        }
    }

    /// Merges a polled page into `vaults`, keeping whichever side has the higher version for
    /// each vault. A poll response that started before a `vault_updated` push but completed
    /// after it carries older data; dropping it here prevents the stale overwrite (#606).
    private func mergePolledVaults(_ polled: [Vault]) -> [Vault] {
        var merged: [Vault] = []
        merged.reserveCapacity(polled.count)
        for incoming in polled {
            if let existing = vaults.first(where: { $0.id == incoming.id }), !isNewer(incoming) {
                // The already-applied copy is at least as new — keep it.
                merged.append(existing)
            } else {
                recordApplied(incoming)
                merged.append(incoming)
            }
        }
        return merged
    }

    /// Whether `incoming` is strictly newer than the version already applied for its vault.
    /// Vaults never seen before are always accepted.
    private func isNewer(_ incoming: Vault) -> Bool {
        guard let applied = appliedVersions[incoming.id] else { return true }
        return incoming.version > applied
    }

    private func recordApplied(_ vault: Vault) {
        appliedVersions[vault.id] = max(appliedVersions[vault.id] ?? vault.version, vault.version)
    }

    /// Subscribes to real-time vault events (#20) and applies incoming updates to
    /// `vaults` in place, so balance/status changes made from another device show up
    /// without waiting for the next poll.
    func subscribeToEvents(vaultID: String, socket: VaultEventSocket) {
        eventSocket = socket
        socket.onStateChange = { [weak self] state in
            self?.socketConnectionState = state
        }
        // Sync the current state immediately in case socket was already connected
        socketConnectionState = socket.state
        socket.onEvent = { [weak self] event in
            guard let self else { return }
            switch event {
            case .vaultUpdated(let updated):
                self.applyUpdate(updated)
            case .vaultExpired(let expiredVaultID, _):
                // Neither payload carries the full vault, and both change status (and, for
                // a release, the balance) — refetch rather than patching fields locally.
                // Recorded before the refetch so a same-instant push (#232) sees it.
                NotificationDeliveryLog.shared.record(kind: .delivered, source: .websocket,
                                                       eventType: "vault_expired", vaultID: expiredVaultID)
                // Remove the vault from Spotlight — it's no longer active (#440).
                SpotlightService.shared.revoke(vaultID: expiredVaultID)
                Task { await self.load() }
            case .vaultReleased(let releasedVaultID, _, _):
                NotificationDeliveryLog.shared.record(kind: .delivered, source: .websocket,
                                                       eventType: "vault_released", vaultID: releasedVaultID)
                // Remove the vault from Spotlight — it's no longer active (#440).
                SpotlightService.shared.revoke(vaultID: releasedVaultID)
                Task { await self.load() }
            case .ping:
                // Server keepalive — no state change.
                break
            case .subscribed:
                break // acknowledgement only — no state change
            case .error(let code, let message):
                self.error = ErrorPresentation(message: "Vault event stream error (\(code)): \(message)")
            case .unknown:
                break
            }
        }
        socket.connect(vaultID: vaultID)
    }

    func unsubscribeFromEvents() {
        eventSocket?.stop()
        eventSocket = nil
        socketConnectionState = .disconnected
    }

    /// Clears the per-vault applied-version bookkeeping. Called on sign-out so a subsequent
    /// user on the same device isn't served stale comparisons from the previous account.
    func resetVersionTracking() {
        appliedVersions.removeAll()
    }

    private func deduplicateVaults(_ vaults: [Vault]) -> (deduplicated: [Vault], duplicateCount: Int) {
        var seenIDs = Set<String>()
        var deduplicated: [Vault] = []
        var duplicateCount = 0

        for vault in vaults {
            if seenIDs.contains(vault.id) {
                duplicateCount += 1
            } else {
                seenIDs.insert(vault.id)
                deduplicated.append(vault)
            }
        }

        return (deduplicated, duplicateCount)
    }

    private func scheduleReminders() {
        for vault in vaults {
            guard vault.status == .active, let ttl = vault.ttlRemaining else { continue }
            NotificationService.shared.scheduleCheckInReminder(
                vaultID: vault.id, vaultName: vault.id, ttlRemaining: ttl,
                checkInInterval: vault.checkInInterval)
            VaultExpiryNotificationService.shared.scheduleVaultExpiryNotifications(
                vaultID: vault.id, vaultName: vault.id, ttlRemaining: ttl)
        }
    }
}

// MARK: - #606 Stale poll/push reconciliation

#if DEBUG
/// Exercises the out-of-order race from #606: a `GET /vaults` poll started before a
/// `vault_updated` push, completing after it, must not overwrite the newer pushed state.
enum VaultStoreVersionRaceTests {
    @MainActor
    static func run() {
        let store = VaultStore()
        let stale = Vault.fixture(id: "v1", version: 1, balance: 100)
        let fresh = Vault.fixture(id: "v1", version: 2, balance: 200)

        // Push arrives first with the newer version.
        store.applyUpdate(fresh)
        assert(store.vaults.first?.balance == 200, "push should apply")

        // Stale poll response completes afterwards — must be ignored.
        store.applyUpdate(stale)
        assert(store.vaults.first?.balance == 200, "stale push must not overwrite newer state")

        // A newer push still applies.
        let newer = Vault.fixture(id: "v1", version: 3, balance: 300)
        store.applyUpdate(newer)
        assert(store.vaults.first?.balance == 300, "newer push should apply")
    }
}
#endif

// MARK: - #120 Disable2FACoordinator

/// Encapsulates the two-step "authenticate then disable 2FA" sequence so it can
/// be unit-tested independently of the view layer. Both `biometric` and
/// `apiClient` are injected, letting tests supply mocks/spies.
///
/// Usage in the view:
///   ```swift
///   let coordinator = Disable2FACoordinator()
///   try await coordinator.run(vaultID: vault.id)
///   ```
struct Disable2FACoordinator {
    var biometric: BiometricAuthenticating = BiometricService.shared
    var apiDisable: (String) async throws -> Void = { id in
        try await APIClient.shared.disable2FA(vaultID: id)
    }

    /// Runs biometric authentication and, on success, calls the disable-2FA API.
    /// Throws `BiometricService.BiometricError` if authentication fails/is cancelled,
    /// or an `APIError` if the network call fails — both propagate unmodified so the
    /// call site can surface the right message.
    func run(vaultID: String) async throws {
        try await biometric.authenticate(reason: "Confirm disabling two-factor authentication")
        try await apiDisable(vaultID)
    }
}
