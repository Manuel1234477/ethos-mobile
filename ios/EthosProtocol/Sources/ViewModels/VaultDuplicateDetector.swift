import Foundation

/// Issue #474: heuristic duplicate-vault detection.
///
/// Vault IDs are already deduplicated exactly (`VaultStore.deduplicateVaults`).
/// A "duplicate" here means a *different* vault the user likely created twice
/// by mistake for the same intended recipient — detected by matching
/// beneficiary address, since that's the field a repeat setup would share.
struct VaultDuplicateGroup: Identifiable {
    var id: String { vaults.map(\.id).sorted().joined(separator: ",") }
    let beneficiary: String
    let vaults: [Vault]
}

enum VaultDuplicateDetector {
    static func findDuplicateGroups(in vaults: [Vault]) -> [VaultDuplicateGroup] {
        Dictionary(grouping: vaults, by: \.beneficiary)
            .filter { $0.value.count > 1 }
            .map { VaultDuplicateGroup(beneficiary: $0.key, vaults: $0.value) }
    }
}
