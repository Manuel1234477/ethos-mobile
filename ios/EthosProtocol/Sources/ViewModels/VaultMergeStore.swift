import Foundation

/// Issue #474: records a local "duplicate merge" decision.
///
/// There is no server-side vault deletion or merge API — vault deletion in
/// this client isn't implemented at all (VaultRowView.performDelete shows a
/// "not yet implemented" error) — so an actual server-side merge of two
/// vaults' funds/state isn't something this client can do. What's
/// implementable, and what this provides: hide the duplicate from the main
/// vault list (it still exists and is still checked-in-able if the user
/// navigates to it directly or un-merges), and surface both vaults' info —
/// including each one's own `lastCheckIn` — together in the merged view, so
/// "preserve check-in history" means neither vault's check-in record is
/// hidden or lost, not that they're combined into one server-side record.
struct VaultMergeRecord: Codable, Equatable {
    /// The vault being hidden/merged away.
    let mergedVaultId: String
    /// The vault it was merged into (stays visible in the main list).
    let keeperVaultId: String
    let mergedAt: Date
}

struct VaultMergeStore {
    private static let userDefaultsKey = "com.ethosprotocol.vault_merges"

    private static var allMerges: [VaultMergeRecord] {
        get {
            guard let data = UserDefaults.standard.data(forKey: userDefaultsKey),
                  let decoded = try? JSONDecoder().decode([VaultMergeRecord].self, from: data)
            else { return [] }
            return decoded
        }
        set {
            if let data = try? JSONEncoder().encode(newValue) {
                UserDefaults.standard.set(data, forKey: userDefaultsKey)
            }
        }
    }

    /// Records that `mergedVaultId` should be hidden, folded into `keeperVaultId`.
    static func merge(_ mergedVaultId: String, into keeperVaultId: String) {
        var merges = allMerges
        merges.removeAll { $0.mergedVaultId == mergedVaultId } // replace any prior decision
        merges.append(VaultMergeRecord(mergedVaultId: mergedVaultId, keeperVaultId: keeperVaultId, mergedAt: Date()))
        allMerges = merges
    }

    /// Reverses a merge decision, making `vaultId` visible in the main list again.
    static func unmerge(_ vaultId: String) {
        allMerges = allMerges.filter { $0.mergedVaultId != vaultId }
    }

    /// Vault IDs currently hidden because they were merged into another vault.
    static func hiddenVaultIds() -> Set<String> {
        Set(allMerges.map(\.mergedVaultId))
    }

    /// The vault IDs merged into `keeperVaultId`, if any.
    static func mergedVaultIds(keptBy keeperVaultId: String) -> [String] {
        allMerges.filter { $0.keeperVaultId == keeperVaultId }.map(\.mergedVaultId)
    }
}
