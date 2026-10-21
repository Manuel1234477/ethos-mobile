import Foundation

/// Issue #476: multi-account support, phase 1.
///
/// Scope: this manages a roster of saved auth sessions and switches which
/// one's token is "active" (the single fixed Keychain slot AuthStore,
/// APIClient, background refresh, and the TTL widget all already read from).
/// Switching accounts therefore does NOT yet isolate per-account app data —
/// the vault list cache, tags (VaultTagStore), action history
/// (VaultActionLog), and notification preferences are still global to the
/// device, not scoped per account. A user switching accounts sees a fresh
/// vault list fetched from the server for the new session, but locally-only
/// data like tags and action history from the previous account would still
/// show through if it happens to apply to the same vault IDs. Full
/// per-account data isolation across those stores is follow-up work; this
/// phase covers "support multiple authenticated sessions, switch between
/// them, and don't lose a session's auth state when switching away."
struct Account: Codable, Identifiable, Equatable {
    let id: String
    var label: String
    let addedAt: Date
}

@MainActor
final class AccountManager: ObservableObject {
    static let shared = AccountManager()

    @Published private(set) var accounts: [Account] = []
    @Published private(set) var activeAccountId: String?

    private static let accountsDefaultsKey = "com.ethosprotocol.accounts"
    private static let activeAccountDefaultsKey = "com.ethosprotocol.active_account_id"

    private init() {
        accounts = Self.loadAccounts()
        activeAccountId = UserDefaults.standard.string(forKey: Self.activeAccountDefaultsKey)
    }

    /// Saves the *currently signed-in* session (the token already in
    /// KeychainService's active slot) into the roster under `label`, so it
    /// can be switched back to later without re-authenticating. Call this
    /// right after a successful sign-in if the user wants to keep multiple
    /// accounts available.
    @discardableResult
    func saveCurrentSessionAsAccount(label: String) -> Account? {
        guard let token = KeychainService.shared.loadToken() else { return nil }
        let expiresAt = KeychainService.shared.loadTokenExpiry()
        let account = Account(id: UUID().uuidString, label: label, addedAt: Date())
        KeychainService.shared.saveToken(token, expiresAt: expiresAt, accountId: account.id)
        accounts.append(account)
        persistAccounts()
        activeAccountId = account.id
        UserDefaults.standard.set(account.id, forKey: Self.activeAccountDefaultsKey)
        return account
    }

    /// Switches the active session to `accountId`. Archives the outgoing
    /// active token (if the outgoing account is tracked in the roster) into
    /// its own scoped slot first, so it isn't lost, then loads the target
    /// account's token into the active slot.
    ///
    /// Returns `false` (and leaves the active session unchanged) if
    /// `accountId` isn't in the roster or has no saved token — the caller
    /// should fall back to prompting the user to sign in again for that
    /// account rather than treating this as a silent sign-out.
    @discardableResult
    func switchTo(_ accountId: String) -> Bool {
        guard accounts.contains(where: { $0.id == accountId }) else { return false }
        guard let targetToken = KeychainService.shared.loadToken(accountId: accountId) else { return false }
        let targetExpiry = KeychainService.shared.loadTokenExpiry(accountId: accountId)

        if let outgoingId = activeAccountId,
           let outgoingToken = KeychainService.shared.loadToken() {
            let outgoingExpiry = KeychainService.shared.loadTokenExpiry()
            KeychainService.shared.saveToken(outgoingToken, expiresAt: outgoingExpiry, accountId: outgoingId)
        }

        KeychainService.shared.saveToken(targetToken, expiresAt: targetExpiry)
        activeAccountId = accountId
        UserDefaults.standard.set(accountId, forKey: Self.activeAccountDefaultsKey)
        return true
    }

    /// Removes an account from the roster and deletes its saved credentials.
    /// Does not touch the active session even if `accountId` is currently
    /// active — call `switchTo` another account (or sign out) first.
    func removeAccount(_ accountId: String) {
        KeychainService.shared.deleteToken(accountId: accountId)
        accounts.removeAll { $0.id == accountId }
        persistAccounts()
        if activeAccountId == accountId {
            activeAccountId = nil
            UserDefaults.standard.removeObject(forKey: Self.activeAccountDefaultsKey)
        }
    }

    private func persistAccounts() {
        if let data = try? JSONEncoder().encode(accounts) {
            UserDefaults.standard.set(data, forKey: Self.accountsDefaultsKey)
        }
    }

    private static func loadAccounts() -> [Account] {
        guard let data = UserDefaults.standard.data(forKey: accountsDefaultsKey),
              let decoded = try? JSONDecoder().decode([Account].self, from: data)
        else { return [] }
        return decoded
    }
}
