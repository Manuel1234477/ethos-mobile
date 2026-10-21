import XCTest
@testable import EthosProtocol

// MARK: - #203 PendingCheckInStore Tests
//
// Regression guard for the duplicate check-in queue implementations reconciled in
// 8d8d59d: `CheckInQueue`/`CheckInSyncService` briefly existed alongside
// `PendingCheckInStore`/`CheckInSyncTask` as a dead, never-enqueued-into duplicate.
// These tests exercise the *surviving* queue directly so a future reintroduction of
// a second queue/store is caught by these paths no longer reflecting real behavior,
// rather than by silence.

final class PendingCheckInStoreTests: XCTestCase {

    func test_insert_thenGetAll_returnsItem() {
        let store = makeTestStore()
        let item = PendingCheckIn(vaultId: "vault-1", queuedAt: Date())

        store.insert(item)

        XCTAssertEqual(store.getAll(), [item])
    }

    func test_insert_isIdempotentPerVault() {
        let store = makeTestStore()
        let first = PendingCheckIn(vaultId: "vault-1", queuedAt: Date())
        let second = PendingCheckIn(vaultId: "vault-1", queuedAt: Date().addingTimeInterval(60))

        store.insert(first)
        store.insert(second)

        XCTAssertEqual(store.getAll(), [second],
            "Re-queuing the same vault must replace, not duplicate, the pending entry")
    }

    func test_delete_removesOnlyMatchingVault() {
        let store = makeTestStore()
        let a = PendingCheckIn(vaultId: "vault-1", queuedAt: Date())
        let b = PendingCheckIn(vaultId: "vault-2", queuedAt: Date())
        store.insert(a)
        store.insert(b)

        store.delete(a)

        XCTAssertEqual(store.getAll(), [b])
    }

    func test_deleteAll_emptiesQueue() {
        let store = makeTestStore()
        store.insert(PendingCheckIn(vaultId: "vault-1", queuedAt: Date()))
        store.insert(PendingCheckIn(vaultId: "vault-2", queuedAt: Date()))

        store.deleteAll()

        XCTAssertEqual(store.count, 0)
    }

    func test_persistsAcrossInstances_sameFile() {
        let fileURL = makeTestFileURL()
        let store1 = PendingCheckInStore(fileURL: fileURL)
        let item = PendingCheckIn(vaultId: "vault-1", queuedAt: Date())
        store1.insert(item)

        let store2 = PendingCheckInStore(fileURL: fileURL)

        XCTAssertEqual(store2.getAll(), [item],
            "The queue must be durable across process restarts, not just in-memory")
    }

    // ── File protection (#613) ──────────────────────────────────────────────

    func test_insert_writesFileWithExplicitFileProtection() throws {
        let fileURL = makeTestFileURL()
        addTeardownBlock { try? FileManager.default.removeItem(at: fileURL) }
        let store = PendingCheckInStore(fileURL: fileURL)

        store.insert(PendingCheckIn(vaultId: "vault-1", queuedAt: Date()))

        let attributes = try FileManager.default.attributesOfItem(atPath: fileURL.path)
        let protection = attributes[.protectionKey] as? FileProtectionType
        XCTAssertTrue(
            protection == .complete || protection == .completeUnlessOpen,
            "Queued check-in file must be written with explicit file protection " +
            "(.completeFileProtection or .completeFileProtectionUnlessOpen), got \(String(describing: protection))"
        )
    }

    // ── Write failure propagation (#615) ────────────────────────────────────

    func test_insert_propagatesWriteError_insteadOfSwallowingIt() {
        // Point the store at a path whose parent directory does not exist, so the
        // atomic write cannot succeed. A `try?` here would silently drop the
        // queued check-in; the error must surface to the caller instead.
        let unwritableURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("PendingCheckInStoreTests-missing-\(UUID().uuidString)")
            .appendingPathComponent("queue.json")
        let store = PendingCheckInStore(fileURL: unwritableURL)

        XCTAssertThrowsError(
            try store.insertThrowing(PendingCheckIn(vaultId: "vault-1", queuedAt: Date())),
            "A failed queue write must propagate the error so the UI can surface it"
        )
    }

    // ── Sole insertion point (#203 regression guard) ────────────────────────
=======

    func test_shared_isASingleton() {
        XCTAssertTrue(PendingCheckInStore.shared === PendingCheckInStore.shared,
            "PendingCheckInStore.shared must always resolve to the same instance")
    }

    func test_checkInSyncTask_defaultStore_isTheSharedInstance() {
        // CheckInSyncTask.store is injectable for tests, but its *default* value —
        // what production code actually runs with — must be the one canonical queue.
        // If a second queue/store were ever reintroduced and wired in here instead,
        // this assertion is the thing that would catch it.
        XCTAssertTrue(CheckInSyncTask.shared.store === PendingCheckInStore.shared,
            "CheckInSyncTask must drain the single canonical PendingCheckInStore, " +
            "not a second competing queue implementation")
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private func makeTestFileURL() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("PendingCheckInStoreTests-\(UUID().uuidString).json")
    }

    private func makeTestStore() -> PendingCheckInStore {
        let fileURL = makeTestFileURL()
        addTeardownBlock { try? FileManager.default.removeItem(at: fileURL) }
        return PendingCheckInStore(fileURL: fileURL)
    }
}
