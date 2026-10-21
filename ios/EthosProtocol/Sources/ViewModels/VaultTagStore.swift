import Foundation

/// Issue #477: local vault tagging and organization.
///
/// Tags are a client-only organizational aid (like `VaultSearchPreferences`'s
/// search/filter/sort state) — there is no server-side concept of a vault tag,
/// so tags are stored per-device in UserDefaults, keyed by vault id.
struct VaultTagStore {
    private static let userDefaultsKey = "com.ethosprotocol.vault_tags"

    /// All tag assignments, vaultId -> set of tag names.
    private static var allTags: [String: [String]] {
        get {
            guard let data = UserDefaults.standard.data(forKey: userDefaultsKey),
                  let decoded = try? JSONDecoder().decode([String: [String]].self, from: data)
            else { return [:] }
            return decoded
        }
        set {
            if let data = try? JSONEncoder().encode(newValue) {
                UserDefaults.standard.set(data, forKey: userDefaultsKey)
            }
        }
    }

    /// Tags assigned to a single vault, in the order they were added.
    static func tags(for vaultId: String) -> [String] {
        allTags[vaultId] ?? []
    }

    /// Adds `tag` to `vaultId` if not already present. Tags are trimmed and
    /// case-normalized (lowercased) so "Family" and "family" are the same tag.
    static func addTag(_ tag: String, to vaultId: String) {
        let normalized = tag.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !normalized.isEmpty else { return }
        var all = allTags
        var vaultTags = all[vaultId] ?? []
        guard !vaultTags.contains(normalized) else { return }
        vaultTags.append(normalized)
        all[vaultId] = vaultTags
        allTags = all
    }

    static func removeTag(_ tag: String, from vaultId: String) {
        var all = allTags
        all[vaultId]?.removeAll { $0 == tag }
        if all[vaultId]?.isEmpty == true { all.removeValue(forKey: vaultId) }
        allTags = all
    }

    /// Every distinct tag in use across all vaults, alphabetically — drives the
    /// filter chip row so users only see tags that are actually assigned.
    static func allKnownTags() -> [String] {
        Set(allTags.values.flatMap { $0 }).sorted()
    }

    /// Removes all tag data for a vault (called when a vault is deleted locally).
    static func clearTags(for vaultId: String) {
        var all = allTags
        all.removeValue(forKey: vaultId)
        allTags = all
    }
}
