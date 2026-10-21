import Foundation

/// Issue #479: local vault action history and analytics.
///
/// Like `VaultTagStore`, this is a client-local log — there is no server-side
/// audit trail endpoint for vault actions, so history is recorded per-device
/// in UserDefaults as a capped, append-only JSON array.
enum VaultActionType: String, Codable {
    case checkIn, view, deposit, withdraw
}

struct VaultActionEntry: Codable, Identifiable, Equatable {
    let id: UUID
    let vaultId: String
    let type: VaultActionType
    let timestamp: Date

    init(vaultId: String, type: VaultActionType, timestamp: Date = Date()) {
        self.id = UUID()
        self.vaultId = vaultId
        self.type = type
        self.timestamp = timestamp
    }
}

/// Per-action-type count for a vault, used to render frequency statistics.
struct VaultActionFrequency {
    let type: VaultActionType
    let count: Int
}

struct VaultActionLog {
    private static let userDefaultsKey = "com.ethosprotocol.vault_action_log"
    /// Caps on-device storage growth — oldest entries are dropped past this count.
    private static let maxEntries = 2_000

    private static var allEntries: [VaultActionEntry] {
        get {
            guard let data = UserDefaults.standard.data(forKey: userDefaultsKey),
                  let decoded = try? JSONDecoder().decode([VaultActionEntry].self, from: data)
            else { return [] }
            return decoded
        }
        set {
            if let data = try? JSONEncoder().encode(newValue) {
                UserDefaults.standard.set(data, forKey: userDefaultsKey)
            }
        }
    }

    /// Records an action. Call sites: VaultStore.checkIn/deposit/withdraw, and
    /// VaultDetailView.onAppear for `.view`.
    static func record(_ type: VaultActionType, for vaultId: String) {
        var entries = allEntries
        entries.append(VaultActionEntry(vaultId: vaultId, type: type))
        if entries.count > maxEntries {
            entries.removeFirst(entries.count - maxEntries)
        }
        allEntries = entries
    }

    /// History for one vault, most recent first.
    static func history(for vaultId: String) -> [VaultActionEntry] {
        allEntries.filter { $0.vaultId == vaultId }.sorted { $0.timestamp > $1.timestamp }
    }

    /// Action-frequency breakdown for one vault, most frequent first.
    static func frequency(for vaultId: String) -> [VaultActionFrequency] {
        let grouped = Dictionary(grouping: history(for: vaultId), by: \.type)
        return grouped
            .map { VaultActionFrequency(type: $0.key, count: $0.value.count) }
            .sorted { $0.count > $1.count }
    }

    /// Exports a vault's history as CSV text (vaultId, type, timestamp), suitable
    /// for the system share sheet.
    static func exportCSV(for vaultId: String) -> String {
        let formatter = ISO8601DateFormatter()
        var lines = ["vaultId,type,timestamp"]
        for entry in history(for: vaultId) {
            lines.append("\(entry.vaultId),\(entry.type.rawValue),\(formatter.string(from: entry.timestamp))")
        }
        return lines.joined(separator: "\n")
    }

    static func clearHistory(for vaultId: String) {
        allEntries = allEntries.filter { $0.vaultId != vaultId }
    }
}
