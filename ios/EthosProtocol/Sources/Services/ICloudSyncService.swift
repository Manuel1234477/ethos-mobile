import Foundation

/// Manages optional iCloud sync for passkey-to-vault association metadata.
/// Only non-sensitive data (vault IDs, credential IDs) is synced — never private keys.
final class ICloudSyncService {
    static let shared = ICloudSyncService()
    private init() {}

    private let store = NSUbiquitousKeyValueStore.default
    private let enabledKey = "com.ethosprotocol.icloud_sync_enabled"
    private let associationsKey = "com.ethosprotocol.vault_associations"

    /// The server account ID whose associations this device is currently operating on.
    /// Associations are keyed by "accountID/vaultID" (#618) so that a second Ethos account
    /// signing in under the same Apple ID cannot inherit another account's mappings.
    var currentAccountID: String?

    // MARK: - Association Model

    struct VaultAssociation: Codable, Equatable {
        let credentialID: String
        let timestamp: TimeInterval
    }

    // MARK: - Toggle

    var isSyncEnabled: Bool {
        get { store.bool(forKey: enabledKey) }
        set {
            store.set(newValue, forKey: enabledKey)
            store.synchronize()
            if newValue { pushAssociations(loadLocalAssociations()) }
        }
    }

    // MARK: - Associations

    /// Save a vault-to-credential association locally; push to iCloud if sync is on.
    ///
    /// Conflict rule (#205): last-write-wins per vault ID, compared by `timestamp` — the
    /// moment this device durably persisted the association, immediately following the
    /// server-accepted passkey registration or check-in it records. `pushAssociations`
    /// merges against the current remote state rather than overwriting it outright, so two
    /// devices racing to sync after each queued an offline check-in for the same vault
    /// converge on the newer association instead of one device's push silently discarding
    /// the other's.
    func save(vaultID: String, credentialID: String) {
        guard let accountID = currentAccountID else { return }
        var assoc = loadLocalAssociations()
        assoc[Self.associationKey(accountID: accountID, vaultID: vaultID)] = VaultAssociation(credentialID: credentialID, timestamp: Date().timeIntervalSince1970)
        persist(assoc)
        if isSyncEnabled { pushAssociations(assoc) }
    }

    /// Return the credential ID associated with a vault, resolved through the same
    /// last-write-wins merge used by restoreFromICloud (#617): a stale remote association
    /// must not override a newer local one.
    func credentialID(for vaultID: String) -> String? {
        guard let accountID = currentAccountID else { return nil }
        let key = Self.associationKey(accountID: accountID, vaultID: vaultID)
        let local = loadLocalAssociations()
        let merged = isSyncEnabled ? Self.merge(local: local, remote: remoteAssociations()) : local
        return merged[key]?.credentialID
    }

    /// Clears this device's local associations for the current account only (used on sign-out).
    /// Other accounts' local entries are preserved, and the iCloud key-value store itself is
    /// deliberately left untouched — wiping that would also erase the association for any
    /// other device still signed in; restoreFromICloud() repopulates local storage from iCloud
    /// on the next sign-in if sync is re-enabled. Keying by account ID (#618) ensures a
    /// different account signing in under the same Apple ID cannot inherit these mappings.
    func clearLocalAssociations() {
        guard let accountID = currentAccountID else { return }
        let prefix = "\(accountID)/"
        var assoc = loadLocalAssociations()
        assoc = assoc.filter { !$0.key.hasPrefix(prefix) }
        persist(assoc)
    }

    /// Pull associations from iCloud and merge into local storage using last-write-wins strategy.
    func restoreFromICloud() {
        guard isSyncEnabled else { return }
        persist(Self.merge(local: loadLocalAssociations(), remote: remoteAssociations()))
    }

    /// Association map key: scopes a vault ID to its owning server account (#618).
    static func associationKey(accountID: String, vaultID: String) -> String {
        "\(accountID)/\(vaultID)"
    }

    /// Merges two association maps using the last-write-wins conflict rule (#205): for a
    /// vault ID present on both sides, the entry with the newer `timestamp` wins; a vault ID
    /// present on only one side is kept as-is. `internal` (not `private`) so
    /// ICloudSyncServiceTests can exercise the merge/conflict rule directly, without needing
    /// the iCloud entitlement NSUbiquitousKeyValueStore requires (unavailable in CI).
    static func merge(local: [String: VaultAssociation], remote: [String: VaultAssociation]) -> [String: VaultAssociation] {
        var merged = local
        for (vaultID, remoteValue) in remote {
            if let localValue = merged[vaultID] {
                if remoteValue.timestamp > localValue.timestamp { merged[vaultID] = remoteValue }
            } else {
                merged[vaultID] = remoteValue
            }
        }
        return merged
    }

    // MARK: - Private helpers

    private func loadLocalAssociations() -> [String: VaultAssociation] {
        guard let data = UserDefaults.standard.data(forKey: associationsKey),
              let dict = try? JSONDecoder().decode([String: VaultAssociation].self, from: data) else { return [:] }
        return dict
    }

    private func persist(_ associations: [String: VaultAssociation]) {
        guard let data = try? JSONEncoder().encode(associations) else { return }
        UserDefaults.standard.set(data, forKey: associationsKey)
    }

    private func pushAssociations(_ associations: [String: VaultAssociation]) {
        // #205: merge against the current remote state instead of overwriting it wholesale —
        // otherwise a second device's push, racing this one, could clobber an association the
        // first device just wrote for a vault the second device never touched locally.
        let merged = Self.merge(local: associations, remote: remoteAssociations())
        guard let data = try? JSONEncoder().encode(merged) else { return }
        store.set(data, forKey: associationsKey)
        store.synchronize()
    }

    private func remoteAssociations() -> [String: VaultAssociation] {
        guard let data = store.data(forKey: associationsKey),
              let dict = try? JSONDecoder().decode([String: VaultAssociation].self, from: data) else { return [:] }
        return dict
    }
}
