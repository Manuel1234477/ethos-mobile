import Foundation

/// Per-category notification preferences and optional quiet hours.
/// Persisted to UserDefaults and registered with the push-token endpoint on change
/// so preferences survive reinstall. Mirrors Android NotificationPreferences.
struct NotificationPreferences: Codable, Equatable {
    /// Whether TTL-expiry warning notifications are enabled.
    var ttlWarningsEnabled: Bool
    /// Whether check-in reminder notifications are enabled.
    var checkInRemindersEnabled: Bool
    /// Whether quiet hours are active (notifications suppressed in the quiet window).
    var quietHoursEnabled: Bool
    /// Start of quiet hours (hour component, 0–23, local time).
    var quietHoursStart: Int
    /// End of quiet hours (hour component, 0–23, local time).
    var quietHoursEnd: Int

    static let `default` = NotificationPreferences(
        ttlWarningsEnabled: true,
        checkInRemindersEnabled: true,
        quietHoursEnabled: false,
        quietHoursStart: 22,
        quietHoursEnd: 8
    )

    private static let userDefaultsKey = "com.ethosprotocol.notification_preferences"

    static var current: NotificationPreferences {
        get {
            guard let data = UserDefaults.standard.data(forKey: userDefaultsKey),
                  let prefs = try? JSONDecoder().decode(NotificationPreferences.self, from: data)
            else { return .default }
            return prefs
        }
        set {
            if let data = try? JSONEncoder().encode(newValue) {
                UserDefaults.standard.set(data, forKey: userDefaultsKey)
            }
        }
    }

    /// Returns true if a notification should be suppressed right now based on quiet hours.
    func isSuppressedByQuietHours(at date: Date = Date(), calendar: Calendar = .current) -> Bool {
        guard quietHoursEnabled else { return false }
        let hour = calendar.component(.hour, from: date)
        if quietHoursStart <= quietHoursEnd {
            // e.g. 09:00–17:00
            return hour >= quietHoursStart && hour < quietHoursEnd
        } else {
            // Wraps midnight, e.g. 22:00–08:00
            return hour >= quietHoursStart || hour < quietHoursEnd
        }
    }

    /// Issue #478: the next moment a notification scheduled "now" should actually
    /// be delivered, respecting quiet hours. Returns `date` unchanged when quiet
    /// hours don't apply right now; otherwise returns the next occurrence of
    /// `quietHoursEnd` today or tomorrow.
    ///
    /// `isSuppressedByQuietHours` on its own was dead code — nothing called it,
    /// so near-immediate local notifications (TTL warnings, queued check-in
    /// alerts, vault-expired alerts) fired regardless of quiet hours. This is
    /// what those scheduling call sites should use instead of a fixed delay.
    func nextAllowedDeliveryDate(from date: Date = Date(), calendar: Calendar = .current) -> Date {
        guard isSuppressedByQuietHours(at: date, calendar: calendar) else { return date }
        var components = calendar.dateComponents([.year, .month, .day], from: date)
        components.hour = quietHoursEnd
        components.minute = 0
        components.second = 0
        guard let todayEnd = calendar.date(from: components) else { return date }
        // Quiet hours wrap midnight (e.g. 22:00-08:00) and `date` is past
        // midnight but still inside the window (e.g. 02:00) — today's "end"
        // time has already passed, so the real end is today's end + 24h.
        return todayEnd > date ? todayEnd : calendar.date(byAdding: .day, value: 1, to: todayEnd) ?? todayEnd
    }
}
