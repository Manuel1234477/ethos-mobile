import Foundation

/// Issue #475: expiry trend analysis across the current vault list.
///
/// There's no retained history for vaults once they expire/release (the
/// server only returns the caller's current vaults), so this analyzes the
/// currently-visible vault list rather than a true historical time series.
/// "Lifespan" here is each vault's configured `checkInInterval` — how long
/// it can go between check-ins before releasing to the beneficiary — since
/// that's the only duration this client actually has, not a creation-to-
/// expiry span (vault creation dates aren't retained either).
enum VaultExpiryAnalytics {
    struct LifespanBucket: Identifiable {
        var id: String { label }
        let label: String
        let count: Int
    }

    struct UpcomingExpiry: Identifiable {
        var id: String { vaultID }
        let vaultID: String
        let predictedDate: Date
    }

    /// Distribution of vaults by check-in interval, bucketed into human-sized
    /// ranges (≤1 day, ≤1 week, ≤1 month, >1 month).
    static func lifespanDistribution(for vaults: [Vault]) -> [LifespanBucket] {
        let day: UInt64 = 86_400
        let week = day * 7
        let month = day * 30

        var buckets: [(String, Range<UInt64>)] = [
            ("≤ 1 day", 0..<(day + 1)),
            ("≤ 1 week", (day + 1)..<(week + 1)),
            ("≤ 1 month", (week + 1)..<(month + 1)),
            ("> 1 month", (month + 1)..<UInt64.max),
        ]
        var counts = [String: Int](minimumCapacity: buckets.count)
        for vault in vaults {
            if let bucket = buckets.first(where: { $0.1.contains(vault.checkInInterval) }) {
                counts[bucket.0, default: 0] += 1
            }
        }
        return buckets.map { LifespanBucket(label: $0.0, count: counts[$0.0] ?? 0) }
    }

    /// Mean check-in interval across all vaults, in seconds. Zero if there are no vaults.
    static func averageLifespanSeconds(for vaults: [Vault]) -> Double {
        guard !vaults.isEmpty else { return 0 }
        let total = vaults.reduce(0.0) { $0 + Double($1.checkInInterval) }
        return total / Double(vaults.count)
    }

    /// Each vault's predicted next deadline — `ttlRemaining` when the server
    /// has supplied a fresh snapshot, else `lastCheckIn + checkInInterval` —
    /// sorted soonest first. This is the same deterministic projection as
    /// Vault.predictedExpiryDate would be, computed directly here so this
    /// analytics module doesn't depend on that property existing.
    static func upcomingExpirations(for vaults: [Vault], limit: Int = 5) -> [UpcomingExpiry] {
        vaults
            .map { vault in
                let date: Date
                if let ttl = vault.ttlRemaining {
                    date = Date().addingTimeInterval(TimeInterval(ttl))
                } else {
                    date = vault.lastCheckIn.addingTimeInterval(TimeInterval(vault.checkInInterval))
                }
                return UpcomingExpiry(vaultID: vault.id, predictedDate: date)
            }
            .sorted { $0.predictedDate < $1.predictedDate }
            .prefix(limit)
            .map { $0 }
    }
}
