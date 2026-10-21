import Network
import Foundation

// ADR-0001: Offline-first architecture — see docs/adr/adr-0001-offline-first.md

/// Abstracts NWPathMonitor so NetworkMonitor's cold-start behavior can be exercised in tests
/// without a real network stack. NWPath itself has no public initializer, so tests can't
/// construct one to fake `pathUpdateHandler` callbacks — this narrows the surface to the two
/// things NetworkMonitor actually needs down to plain, fakeable types.
protocol NetworkPathProvider {
    var isCurrentlySatisfied: Bool { get }
    func startMonitoring(_ handler: @escaping (Bool) -> Void)
}

struct NWPathMonitorProvider: NetworkPathProvider {
    private let monitor = NWPathMonitor()

    var isCurrentlySatisfied: Bool { monitor.currentPath.status == .satisfied }

    func startMonitoring(_ handler: @escaping (Bool) -> Void) {
        monitor.pathUpdateHandler = { path in handler(path.status == .satisfied) }
        monitor.start(queue: DispatchQueue(label: "NetworkMonitor"))
    }
}

final class NetworkMonitor {
    static let shared = NetworkMonitor()

    // `private(set)` so production code can only read this; tests observe it via a fresh
    // instance constructed with a mock provider instead of mutating `.shared`.
    private(set) var isConnected: Bool

    // `internal` (not `private`): lets tests construct a NetworkMonitor with a mock
    // NetworkPathProvider via `@testable import`, mirroring APIClient's testable init.
    init(provider: NetworkPathProvider = NWPathMonitorProvider()) {
        // NWPathMonitor.currentPath reflects the system's last-known path synchronously,
        // even before `start(queue:)` attaches pathUpdateHandler — reading it here instead of
        // defaulting to `true` closes the cold-start window where a request made before the
        // first async path update would otherwise assume connectivity and attempt (and time
        // out on) a real network call while actually offline.
        isConnected = provider.isCurrentlySatisfied
        provider.startMonitoring { [weak self] satisfied in
            self?.isConnected = satisfied
        }
    }
}

// MARK: - CacheTelemetry

/// Tracks cache access events for debug/support diagnostics.
///
/// Counters are intentionally non-atomic integers: they are only incremented on
/// `OfflineCache` load operations (typically called on a background thread but never
/// contended to the extent that torn reads matter for diagnostic purposes). Use
/// `snapshot()` to capture a point-in-time copy for display or logging.
final class CacheTelemetry {
    static let shared = CacheTelemetry()
    private init() {}

    /// Number of cache loads that returned valid, non-expired data.
    private(set) var hits: Int = 0
    /// Number of cache loads where no entry existed on disk.
    private(set) var misses: Int = 0
    /// Number of cache loads where an entry existed but had exceeded `maxAge`
    /// and was therefore refused (not served to the caller).
    private(set) var staleServed: Int = 0

    func recordHit() { hits += 1 }
    func recordMiss() { misses += 1 }
    func recordStaleServed() { staleServed += 1 }

    /// Reset all counters to zero. Called from `SupportDebugView` when the user
    /// taps "Reset Counters".
    func reset() { hits = 0; misses = 0; staleServed = 0 }

    struct Snapshot {
        let hits: Int
        let misses: Int
        let staleServed: Int
    }

    /// Returns an immutable point-in-time copy of the current counters.
    func snapshot() -> Snapshot { Snapshot(hits: hits, misses: misses, staleServed: staleServed) }
}

// MARK: - OfflineCache

/// Simple disk-based cache for offline reads. Entries are timestamped so callers can surface
/// staleness ("as of 3 days ago") and so entries older than `maxAge` can be treated as absent.
/// Bounded to `maxBytes` total via LRU eviction (least-recently-*loaded* entry evicted first).
final class OfflineCache {
    static let shared = OfflineCache()
    private let dir: URL

    /// Entries older than this are treated as absent by `load(for:)`/`age(for:)`. Defaults
    /// to 24 hours so the UI can surface staleness clearly without serving arbitrarily old data.
    var maxAge: TimeInterval?

    /// Total on-disk size cap across all cached entries. Exceeding this on `save(_:for:)`
    /// evicts the least-recently-loaded entries first until back under the cap.
    var maxBytes: Int = 20 * 1_024 * 1_024 // 20 MB

    private init() {
        dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("EthosProtocolOfflineCache", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        // Default to 24 h so stale entries are refused after a day without connectivity
        // rather than being served indefinitely (#243).
        maxAge = 24 * 3_600
    }

    func save(_ data: Data, for key: String) {
        let file = dataFile(for: key)
        do {
            // Write the payload first; only stamp the timestamp once it has landed on disk so
            // "data as of X" can never name a time that doesn't match the cached payload.
            try data.write(to: file)
        } catch {
            // Leave the previous `.meta` untouched: the old timestamp still matches the old
            // payload that remains on disk, rather than pairing it with a failed write.
            LogRedactor.shared.error("OfflineCache: failed to write payload for key \(key): \(error)")
            return
        }
        do {
            let stamp = Data(Date().timeIntervalSince1970.description.utf8)
            try stamp.write(to: metaFile(for: key))
        } catch {
            LogRedactor.shared.error("OfflineCache: failed to write timestamp for key \(key): \(error)")
        }
        enforceSizeCap()
    }

    func load(for key: String) -> Data? {
        guard !isExpired(key) else {
            // Entry exists on disk but has exceeded maxAge — record as stale and refuse it.
            CacheTelemetry.shared.recordStaleServed()
            return nil
        }
        let file = dataFile(for: key)
        guard let data = try? Data(contentsOf: file) else {
            // No entry on disk at all — record as miss.
            CacheTelemetry.shared.recordMiss()
            return nil
        }
        // Served valid, non-expired data — record as hit.
        CacheTelemetry.shared.recordHit()
        // Bump the entry's mtime so it's treated as recently used for LRU eviction, without
        // touching the separate `.meta` timestamp `age(for:)` reports — a cache hit shouldn't
        // reset how stale the underlying data actually is.
        try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: file.path)
        return data
    }

    func delete(for key: String) {
        try? FileManager.default.removeItem(at: dataFile(for: key))
        try? FileManager.default.removeItem(at: metaFile(for: key))
    }

    /// Timestamp the entry for `key` was cached, or nil if no entry exists.
    func cachedAt(for key: String) -> Date? {
        guard let raw = try? String(contentsOf: metaFile(for: key), encoding: .utf8),
              let interval = TimeInterval(raw) else { return nil }
        return Date(timeIntervalSince1970: interval)
    }

    /// How long ago the entry for `key` was cached, or nil if no entry exists.
    func age(for key: String) -> TimeInterval? {
        cachedAt(for: key).map { Date().timeIntervalSince($0) }
    }

    /// Removes every cached entry. Called on sign-out so a subsequent user on the same
    /// device can't be served the previous user's cached vault data while offline.
    func clearAll() {
        try? FileManager.default.removeItem(at: dir)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    private func isExpired(_ key: String) -> Bool {
        guard let maxAge, let cachedAt = cachedAt(for: key) else { return false }
        return Date().timeIntervalSince(cachedAt) > maxAge
    }

    private func dataFile(for key: String) -> URL {
        dir.appendingPathComponent(key.sha256Hex)
    }

    private func metaFile(for key: String) -> URL {
        dataFile(for: key).appendingPathExtension("meta")
    }

    private func enforceSizeCap() {
        guard let contents = try? FileManager.default.contentsOfDirectory(
            at: dir, includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey]) else { return }

        let entries = contents
            .filter { $0.pathExtension != "meta" }
            .compactMap { url -> (url: URL, size: Int, modified: Date)? in
                guard let values = try? url.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey]),
                      let size = values.fileSize, let modified = values.contentModificationDate else { return nil }
                return (url, size, modified)
            }

        var totalSize = entries.reduce(0) { $0 + $1.size }
        guard totalSize > maxBytes else { return }

        for entry in entries.sorted(by: { $0.modified < $1.modified }) {
            guard totalSize > maxBytes else { break }
            try? FileManager.default.removeItem(at: entry.url)
            try? FileManager.default.removeItem(at: entry.url.appendingPathExtension("meta"))
            totalSize -= entry.size
        }
    }
}

import CryptoKit
extension String {
    var sha256Hex: String {
        let digest = SHA256.hash(data: Data(utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }
}
