import Foundation

// MARK: - PendingActionType

/// Mirrors the `type` discriminator of Android's `PendingAction` Room entity (PendingAction.kt).
enum PendingActionType: String, Codable {
    case checkIn
    case deposit
    case withdraw
}

// MARK: - PendingAction

/// A single queued action that could not be delivered while the device was offline.
///
/// Generalises `PendingCheckIn` to cover all mutating vault operations that can be
/// durably queued and retried when connectivity returns. Mirrors Android's `PendingAction`
/// Room entity (PendingAction.kt).
///
/// - `id`: UUID string — uniquely identifies the action so deposit/withdraw items can
///   coexist without deduplication.
/// - `amount`: non-nil for `.deposit` / `.withdraw`; always nil for `.checkIn`.
struct PendingAction: Codable, Equatable {
    let id: String
    let type: PendingActionType
    let vaultId: String
    let amount: Int64?
    let queuedAt: Date
}

// MARK: - PendingActionStore

/// Durable, disk-backed queue for offline vault actions.
///
/// Generalises `PendingCheckInStore` to handle all three action types. Mirrors the
/// full set of cases in Android's `PendingActionDao` (PendingActionDatabase.kt):
/// - insert on offline operation
/// - delete on successful delivery
/// - read by `CheckInSyncTask` when connectivity returns
///
/// **Deduplication policy** (mirrors Android's `OnConflictStrategy.REPLACE` for
/// check-ins, and unique-id semantics for deposits/withdrawals):
/// - `.checkIn` — deduplicated by `vaultId`: inserting a second check-in for the
///   same vault replaces the earlier one (only the most-recent queued time matters).
/// - `.deposit` / `.withdraw` — no deduplication; each carries a unique UUID `id`
///   and must be delivered independently.
///
/// Storage: a JSON file in the app's Application Support directory so it survives
/// app restarts. All mutations are dispatched on a private serial queue.
final class PendingActionStore {
    static let shared = PendingActionStore()

    private let fileURL: URL
    private let queue = DispatchQueue(label: "com.ethosprotocol.PendingActionStore")

    // Injected for unit tests so we don't touch the real filesystem.
    init(fileURL: URL? = nil) {
        if let url = fileURL {
            self.fileURL = url
        } else {
            let support = FileManager.default
                .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("EthosProtocol", isDirectory: true)
            try? FileManager.default.createDirectory(at: support, withIntermediateDirectories: true)
            self.fileURL = support.appendingPathComponent("pending_actions.json")
        }
    }

    // MARK: - Public API

    /// Returns all queued actions, oldest first.
    func getAll() -> [PendingAction] {
        queue.sync { load() }
    }

    /// Returns the current queue count (used by the notification badge and UI banners).
    var count: Int { getAll().count }

    /// Enqueue an action.
    ///
    /// For `.checkIn`: idempotent — an existing check-in for the same vault is replaced
    /// (only the freshest queued time matters; retrying the same check-in twice is
    /// equivalent to a single attempt).
    ///
    /// For `.deposit` / `.withdraw`: always appended — each carries a unique `id`, so
    /// two deposits of different amounts for the same vault are independent operations.
    /// - Throws: The underlying write error if the queue could not be persisted, so
    ///   callers can surface that the action was not saved and retry later.
    func insert(_ item: PendingAction) throws {
        try queue.sync {
            var items = load()
            switch item.type {
            case .checkIn:
                // Deduplicate by vaultId: replace any existing queued check-in for
                // this vault, mirroring PendingCheckInStore's idempotent insert.
                items.removeAll { $0.vaultId == item.vaultId && $0.type == .checkIn }
            case .deposit, .withdraw:
                // No deduplication — each deposit/withdraw has a unique UUID id.
                break
            }
            items.append(item)
            try save(items)
        }
    }

    /// Remove a successfully-delivered action from the queue.
    ///
    /// - Throws: The underlying write error if the queue could not be persisted.
    func delete(_ item: PendingAction) throws {
        try queue.sync {
            var items = load()
            items.removeAll { $0.id == item.id }
            try save(items)
        }
    }

    /// Wipe the entire queue (used in tests and on sign-out).
    ///
    /// - Throws: The underlying write error if the queue could not be persisted.
    func deleteAll() throws {
        try queue.sync { try save([]) }
    }

    // MARK: - Private helpers

    private func load() -> [PendingAction] {
        guard let data = try? Data(contentsOf: fileURL) else { return [] }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return (try? decoder.decode([PendingAction].self, from: data)) ?? []
    }

    private func save(_ items: [PendingAction]) throws {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = .prettyPrinted
        let data = try encoder.encode(items)
        // The queue holds vault IDs and check-in data, so require the file to be
        // unreadable while the device is locked. `.completeFileProtection` is the
        // strictest class; fall back to `.completeFileProtectionUnlessOpen` only
        // where the platform requires it (e.g. the file must be writable while locked).
        var options: Data.WritingOptions = [.atomic, .completeFileProtection]
        do {
            try data.write(to: fileURL, options: options)
        } catch let error as NSError where error.domain == NSCocoaErrorDomain {
            options = [.atomic, .completeFileProtectionUnlessOpen]
            try data.write(to: fileURL, options: options)
        }
    }
}
