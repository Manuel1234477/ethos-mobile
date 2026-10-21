import Foundation
import CryptoKit

/// #489: Deduplicates notifications by content hash to prevent duplicate delivery
class NotificationDeduplicator {
    private let maxCacheSize = 1000
    private var notificationCache: [String: Date] = [:]
    private let queue = DispatchQueue(label: "com.ethosprotocol.notif-dedup")
    
    /// Computes SHA256 hash of notification content for deduplication
    static func contentHash(title: String, body: String, data: [String: String]) -> String {
        let content = "\(title)|\(body)|\(data.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: "|"))"
        let digest = SHA256.hash(data: content.data(using: .utf8) ?? Data())
        return digest.compactMap { String(format: "%02x", $0) }.joined()
    }
    
    /// Returns true if this notification is a duplicate of a recent one
    func isDuplicate(hash: String) -> Bool {
        queue.sync {
            if let lastSeen = notificationCache[hash] {
                // Consider duplicates within 5 seconds
                return Date().timeIntervalSince(lastSeen) < 5
            }
            return false
        }
    }
    
    /// Records a notification hash and cleans up old entries
    func recordNotification(hash: String) {
        queue.async { [weak self] in
            self?.notificationCache[hash] = Date()
            
            // Clean up old entries when cache grows too large
            if self?.notificationCache.count ?? 0 > self?.maxCacheSize ?? 1000 {
                let cutoff = Date().addingTimeInterval(-60) // Keep last 60 seconds
                self?.notificationCache.removeAll { $0.value < cutoff }
            }
        }
    }
}
