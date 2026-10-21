import XCTest
@testable import EthosProtocol

// MARK: - Issue #27: NetworkMonitor Cold-Start Tests

final class NetworkMonitorColdStartTests: XCTestCase {

    private struct MockPathProvider: NetworkPathProvider {
        let isCurrentlySatisfied: Bool
        func startMonitoring(_ handler: @escaping (Bool) -> Void) {
            // Intentionally never calls `handler` — these tests assert on the synchronous
            // snapshot NetworkMonitor reads at init, before any async path update could fire.
        }
    }

    func test_coldStart_offlineDevice_reportsDisconnectedImmediately() {
        let monitor = NetworkMonitor(provider: MockPathProvider(isCurrentlySatisfied: false))
        // No async path update ever fires in this test — if `isConnected` were still
        // defaulting to `true` at this point, the very first request made right after
        // launch would wrongly assume connectivity instead of using the offline cache path.
        XCTAssertFalse(monitor.isConnected)
    }

    func test_coldStart_onlineDevice_reportsConnectedImmediately() {
        let monitor = NetworkMonitor(provider: MockPathProvider(isCurrentlySatisfied: true))
        XCTAssertTrue(monitor.isConnected)
    }

    func test_pathUpdate_afterColdStart_updatesIsConnected() {
        final class RecordingProvider: NetworkPathProvider {
            let isCurrentlySatisfied = true
            var handler: ((Bool) -> Void)?
            func startMonitoring(_ handler: @escaping (Bool) -> Void) { self.handler = handler }
        }
        let provider = RecordingProvider()
        let monitor = NetworkMonitor(provider: provider)
        XCTAssertTrue(monitor.isConnected)

        provider.handler?(false)
        XCTAssertFalse(monitor.isConnected)
    }
}

// MARK: - Issue #25 / #26: OfflineCache Expiry, Age, and Eviction Tests

final class OfflineCacheExpiryTests: XCTestCase {

    override func tearDown() {
        OfflineCache.shared.maxAge = nil
        OfflineCache.shared.maxBytes = 20 * 1_024 * 1_024
        super.tearDown()
    }

    func test_age_forFreshEntry_isNearZero() {
        let key = "age-fresh-\(UUID())"
        OfflineCache.shared.save(Data("x".utf8), for: key)
        let age = OfflineCache.shared.age(for: key)
        XCTAssertNotNil(age)
        XCTAssertLessThan(age ?? .infinity, 2)
    }

    func test_age_forMissingKey_isNil() {
        XCTAssertNil(OfflineCache.shared.age(for: "missing-\(UUID())"))
    }

    func test_cachedAt_isRoughlyNow() {
        let key = "cachedat-\(UUID())"
        let before = Date()
        OfflineCache.shared.save(Data("x".utf8), for: key)
        let cachedAt = OfflineCache.shared.cachedAt(for: key)
        XCTAssertNotNil(cachedAt)
        XCTAssertGreaterThanOrEqual(cachedAt ?? .distantPast, before.addingTimeInterval(-1))
    }

    func test_load_entryOlderThanMaxAge_returnsNil() {
        let key = "expired-\(UUID())"
        OfflineCache.shared.save(Data("stale".utf8), for: key)
        // Backdate the entry directly rather than sleeping in the test.
        setCachedAt(Date().addingTimeInterval(-1000), for: key)

        OfflineCache.shared.maxAge = 500
        XCTAssertNil(OfflineCache.shared.load(for: key))
    }

    func test_load_entryWithinMaxAge_stillReturnsData() {
        let key = "fresh-within-ttl-\(UUID())"
        let data = Data("still good".utf8)
        OfflineCache.shared.save(data, for: key)

        OfflineCache.shared.maxAge = 3_600
        XCTAssertEqual(OfflineCache.shared.load(for: key), data)
    }

    func test_load_withNoMaxAgeSet_neverExpires() {
        let key = "no-ttl-\(UUID())"
        OfflineCache.shared.save(Data("x".utf8), for: key)
        setCachedAt(Date().addingTimeInterval(-1_000_000), for: key)

        XCTAssertNil(OfflineCache.shared.maxAge)
        XCTAssertNotNil(OfflineCache.shared.load(for: key))
    }

    private func setCachedAt(_ date: Date, for key: String) {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("EthosProtocolOfflineCache", isDirectory: true)
        let metaFile = dir.appendingPathComponent(key.sha256Hex + ".meta")
        try? date.timeIntervalSince1970.description.data(using: .utf8)?.write(to: metaFile)
    }
}

final class OfflineCacheEvictionTests: XCTestCase {

    override func setUp() {
        super.setUp()
        OfflineCache.shared.clearAll()
    }

    override func tearDown() {
        OfflineCache.shared.maxBytes = 20 * 1_024 * 1_024
        OfflineCache.shared.clearAll()
        super.tearDown()
    }

    func test_saveBeyondCap_evictsLeastRecentlyUsedFirst() {
        // Three ~1KB entries, capped at ~2KB — the third save must evict exactly one entry.
        OfflineCache.shared.maxBytes = 2_200
        let payload = Data(repeating: 0x41, count: 1_000)

        OfflineCache.shared.save(payload, for: "lru-a")
        OfflineCache.shared.save(payload, for: "lru-b")
        // Set mtimes explicitly rather than relying on OS mtime resolution between two saves
        // microseconds apart, which can collide and make ordering nondeterministic.
        setDataFileModified(Date(), for: "lru-a")
        setDataFileModified(Date().addingTimeInterval(-100), for: "lru-b")
        OfflineCache.shared.save(payload, for: "lru-c")

        XCTAssertNil(OfflineCache.shared.load(for: "lru-b"), "Least-recently-used entry should have been evicted")
        XCTAssertNotNil(OfflineCache.shared.load(for: "lru-a"), "Recently-touched entry should survive eviction")
        XCTAssertNotNil(OfflineCache.shared.load(for: "lru-c"), "Newly-written entry should survive eviction")
    }

    private func setDataFileModified(_ date: Date, for key: String) {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("EthosProtocolOfflineCache", isDirectory: true)
        let dataFile = dir.appendingPathComponent(key.sha256Hex)
        try? FileManager.default.setAttributes([.modificationDate: date], ofItemAtPath: dataFile.path)
    }

    func test_saveUnderCap_evictsNothing() {
        OfflineCache.shared.maxBytes = 1_024 * 1_024
        let payload = Data(repeating: 0x42, count: 100)
        OfflineCache.shared.save(payload, for: "small-a")
        OfflineCache.shared.save(payload, for: "small-b")

        XCTAssertNotNil(OfflineCache.shared.load(for: "small-a"))
        XCTAssertNotNil(OfflineCache.shared.load(for: "small-b"))
    }
}

final class OfflineCacheSignOutTests: XCTestCase {
    func test_clearAll_removesPreviouslySavedEntries() {
        let key = "signout-\(UUID())"
        OfflineCache.shared.save(Data("secret".utf8), for: key)
        XCTAssertNotNil(OfflineCache.shared.load(for: key))

        OfflineCache.shared.clearAll()

        XCTAssertNil(OfflineCache.shared.load(for: key))
    }
}

// MARK: - Issue #616: Cache Write Failure Tests

final class OfflineCacheWriteFailureTests: XCTestCase {

    override func setUp() {
        super.setUp()
        OfflineCache.shared.clearAll()
    }

    override func tearDown() {
        OfflineCache.shared.clearAll()
        super.tearDown()
    }

    func test_successfulSave_writesPayloadAndTimestampConsistently() {
        let key = "consistent-\(UUID())"
        let payload = Data("payload".utf8)
        let before = Date()

        OfflineCache.shared.save(payload, for: key)

        XCTAssertEqual(OfflineCache.shared.load(for: key), payload)
        let cachedAt = OfflineCache.shared.cachedAt(for: key)
        XCTAssertNotNil(cachedAt)
        XCTAssertGreaterThanOrEqual(cachedAt ?? .distantPast, before.addingTimeInterval(-1))
        XCTAssertLessThanOrEqual(cachedAt ?? .distantFuture, Date().addingTimeInterval(1))
    }

    func test_failedPayloadWrite_doesNotUpdateTimestamp() {
        let key = "write-failure-\(UUID())"
        let original = Data("original".utf8)
        OfflineCache.shared.save(original, for: key)

        // Backdate the entry so a timestamp update would be observable.
        let oldDate = Date().addingTimeInterval(-10_000)
        setCachedAt(oldDate, for: key)
        let ageBefore = OfflineCache.shared.age(for: key)
        XCTAssertNotNil(ageBefore)

        // Make the payload path unwritable by placing a directory where the data file belongs.
        let dataFile = dataFileURL(for: key)
        try? FileManager.default.removeItem(at: dataFile)
        do {
            try FileManager.default.createDirectory(at: dataFile, withIntermediateDirectories: true)
        } catch {
            XCTFail("Failed to set up unwritable payload path: \(error)")
            return
        }
        defer { try? FileManager.default.removeItem(at: dataFile) }

        OfflineCache.shared.save(Data("replacement".utf8), for: key)

        // The failed write must not advance the timestamp past the previous successful save.
        let cachedAtAfter = OfflineCache.shared.cachedAt(for: key)
        XCTAssertNotNil(cachedAtAfter)
        XCTAssertEqual(cachedAtAfter?.timeIntervalSince1970 ?? .nan,
                       oldDate.timeIntervalSince1970,
                       accuracy: 0.001,
                       "Timestamp must not be updated when the payload write fails")
        let ageAfter = OfflineCache.shared.age(for: key)
        XCTAssertNotNil(ageAfter)
        XCTAssertGreaterThanOrEqual(ageAfter ?? 0, (ageBefore ?? 0) - 1,
                                    "Age must still reflect the previous successful save")
    }

    private func dataFileURL(for key: String) -> URL {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("EthosProtocolOfflineCache", isDirectory: true)
        return dir.appendingPathComponent(key.sha256Hex)
    }

    private func setCachedAt(_ date: Date, for key: String) {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("EthosProtocolOfflineCache", isDirectory: true)
        let metaFile = dir.appendingPathComponent(key.sha256Hex + ".meta")
        try? date.timeIntervalSince1970.description.data(using: .utf8)?.write(to: metaFile)
    }
}

// MARK: - Issue #244: Concurrent Read/Write Tests

final class OfflineCacheConcurrencyTests: XCTestCase {

    override func setUp() {
        super.setUp()
        OfflineCache.shared.clearAll()
    }

    override func tearDown() {
        OfflineCache.shared.clearAll()
        super.tearDown()
    }

    func test_concurrentReadWrite_doesNotCorruptCache() {
        let iterations = 50
        let writeExpectation = expectation(description: "writer done")
        let readExpectation = expectation(description: "reader done")
        var writeError: Error?
        var readError: Error?

        DispatchQueue.global(qos: .userInitiated).async {
            do {
                for i in 0..<iterations {
                    let data = Data("{\"i\":\(i)}".utf8)
                    OfflineCache.shared.save(data, for: "concurrent-key")
                }
            } catch {
                writeError = error
            }
            writeExpectation.fulfill()
        }

        DispatchQueue.global(qos: .userInitiated).async {
            do {
                for _ in 0..<iterations {
                    _ = OfflineCache.shared.load(for: "concurrent-key")
                }
            } catch {
                readError = error
            }
            readExpectation.fulfill()
        }

        wait(for: [writeExpectation, readExpectation], timeout: 10)
        XCTAssertNil(writeError, "Writer should not throw")
        XCTAssertNil(readError, "Reader should not throw")
        // Final state must be readable — not a torn write
        let result = OfflineCache.shared.load(for: "concurrent-key")
        XCTAssertNotNil(result, "Cache should have a valid entry after concurrent access")
    }

    func test_twoInstancesConcurrently_trackDifferentKeys() {
        // Simulates two widget instances writing their own vault data simultaneously
        let exp1 = expectation(description: "vault-1 done")
        let exp2 = expectation(description: "vault-2 done")

        DispatchQueue.global().async {
            for _ in 0..<20 {
                OfflineCache.shared.save(Data("{\"vault\":\"v1\"}".utf8), for: "widget-vault-1")
            }
            exp1.fulfill()
        }
        DispatchQueue.global().async {
            for _ in 0..<20 {
                OfflineCache.shared.save(Data("{\"vault\":\"v2\"}".utf8), for: "widget-vault-2")
            }
            exp2.fulfill()
        }

        wait(for: [exp1, exp2], timeout: 10)
        let d1 = OfflineCache.shared.load(for: "widget-vault-1")
        let d2 = OfflineCache.shared.load(for: "widget-vault-2")
        XCTAssertNotNil(d1, "vault-1 entry should exist")
        XCTAssertNotNil(d2, "vault-2 entry should exist")
        // Keys must not bleed into each other
        XCTAssertNotEqual(d1, d2)
    }
}
