import UserNotifications
import Foundation
import UIKit

final class NotificationService: NSObject, UNUserNotificationCenterDelegate {
    static let shared = NotificationService()
    private override init() {
        super.init()
        UNUserNotificationCenter.current().delegate = self
    }

    // Injectable for testing (mirrors AuthStore's unregisterPushToken pattern) —
    // lets #234's retry/backoff/pending-persistence logic be exercised against a
    // mock that fails a controlled number of times, instead of a real network call.
    var registerPushTokenCall: (String) async throws -> Void = { token in
        try await APIClient.shared.registerPushToken(token)
    }
    var retryPolicy: RetryPolicy = .networkDefault

    /// Issue #478: delay (seconds, >= `minimum`) before a near-immediate local
    /// notification should actually fire, pushed out past quiet hours if the
    /// user is currently in them.
    private func deferredDelay(minimum: TimeInterval) -> TimeInterval {
        let now = Date()
        let allowed = NotificationPreferences.current.nextAllowedDeliveryDate(from: now)
        return max(minimum, allowed.timeIntervalSince(now))
    }

    func requestPermission() async {
        let granted = (try? await UNUserNotificationCenter.current()
            .requestAuthorization(options: [.alert, .badge, .sound])) ?? false
        if granted { await registerForRemoteNotifications() }
    }

    @MainActor
    private func registerForRemoteNotifications() {
        UIApplication.shared.registerForRemoteNotifications()
    }

    func handleDeviceToken(_ tokenData: Data) {
        let token = tokenData.map { String(format: "%02x", $0) }.joined()
        Task { await registerPushToken(token) }
    }

    /// Registers `token` with retry/backoff (#234). Registration is idempotent
    /// server-side (re-registering the same token is a no-op), which is why —
    /// unlike APIClient's general POST/DELETE policy of never auto-retrying a
    /// mutation — retrying this one specifically is safe.
    ///
    /// If every attempt fails, the token is persisted as "pending" rather than
    /// dropped, so `retryPendingPushTokenRegistrationIfNeeded()` can pick it
    /// back up the next time the app foregrounds instead of silently waiting
    /// on the OS to redeliver the token (which may not happen for a long time).
    func registerPushToken(_ token: String) async {
        do {
            try await withRetry(retryPolicy, isRetryable: { _ in true }) {
                try await self.registerPushTokenCall(token)
            }
            // Persisted only on success so AuthStore.signOut() unregisters a token
            // the server actually has on file for this device.
            KeychainService.shared.savePushToken(token)
            KeychainService.shared.deletePendingPushToken()
        } catch {
            KeychainService.shared.savePendingPushToken(token)
        }
    }

    /// Call when the app foregrounds (RootView's `.onChange(of: scenePhase)`)
    /// to retry a push-token registration that failed even after the initial
    /// retries in `registerPushToken` (#234).
    func retryPendingPushTokenRegistrationIfNeeded() {
        guard let pending = KeychainService.shared.loadPendingPushToken() else { return }
        Task { await registerPushToken(pending) }
    }

    // Schedule a local check-in reminder scaled to the vault's check-in interval.
    // For short intervals (< 24h), schedules two reminders: one at 10% of interval,
    // and another closer to expiry. For longer intervals, schedules one reminder
    // at 10% of interval (capped at 24h).
    func scheduleCheckInReminder(vaultID: String, vaultName: String, ttlRemaining: UInt64, checkInInterval: UInt64) {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: ["checkin-primary-\(vaultID)", "checkin-secondary-\(vaultID)"])

        guard ttlRemaining > 0 else { return }

        // Scale primary reminder to 10% of check-in interval, capped at 24 hours
        let primaryLeadTime = min(checkInInterval / 10, 86_400)
        let primaryFireIn = max(Int(ttlRemaining) - Int(primaryLeadTime), 60)

        // For short intervals, add a secondary reminder 2 hours before expiry
        let hasSecondaryReminder = checkInInterval < 86_400 // < 24h
        let secondaryFireIn = max(Int(ttlRemaining) - 7_200, 60) // 2 hours before

        // Primary reminder
        // #233: identify which vault and how long is left, so the user doesn't have
        // to open the app to find out — but never anything beyond ID/TTL (no
        // balance or beneficiary; those aren't even available to this function).
        let primaryRemaining = max(Int(ttlRemaining) - primaryFireIn, 0)
        let primaryContent = UNMutableNotificationContent()
        primaryContent.title = LocalizedStrings.checkInReminderTitle
        primaryContent.body = LocalizedStrings.checkInReminderBody(vaultID: truncatedVaultID(vaultID), timeRemaining: formatTTLRemaining(primaryRemaining))
        primaryContent.sound = .default
        primaryContent.userInfo = ["vault_id": vaultID]
        primaryContent.categoryIdentifier = "CHECK_IN"

        let primaryTrigger = UNTimeIntervalNotificationTrigger(timeInterval: TimeInterval(primaryFireIn), repeats: false)
        let primaryRequest = UNNotificationRequest(identifier: "checkin-primary-\(vaultID)", content: primaryContent, trigger: primaryTrigger)
        center.add(primaryRequest)
        NotificationDeliveryLog.shared.record(kind: .scheduled, source: .local, eventType: "check_in_reminder", vaultID: vaultID)

        // Secondary reminder for short intervals
        if hasSecondaryReminder && secondaryFireIn > primaryFireIn {
            let secondaryRemaining = max(Int(ttlRemaining) - secondaryFireIn, 0)
            let secondaryContent = UNMutableNotificationContent()
            secondaryContent.title = LocalizedStrings.checkInUrgentTitle
            secondaryContent.body = LocalizedStrings.checkInUrgentBody(vaultID: truncatedVaultID(vaultID), timeRemaining: formatTTLRemaining(secondaryRemaining))
            secondaryContent.sound = .default
            secondaryContent.userInfo = ["vault_id": vaultID]
            secondaryContent.categoryIdentifier = "CHECK_IN"

            let secondaryTrigger = UNTimeIntervalNotificationTrigger(timeInterval: TimeInterval(secondaryFireIn), repeats: false)
            let secondaryRequest = UNNotificationRequest(identifier: "checkin-secondary-\(vaultID)", content: secondaryContent, trigger: secondaryTrigger)
            center.add(secondaryRequest)
            NotificationDeliveryLog.shared.record(kind: .scheduled, source: .local, eventType: "check_in_reminder_urgent", vaultID: vaultID)
        }
    }

    /// Truncated vault ID for notification display — enough to distinguish
    /// vaults without printing the full identifier (#233).
    private func truncatedVaultID(_ vaultID: String) -> String {
        String(vaultID.prefix(12))
    }

    /// Formats a TTL countdown for notification bodies. Never shown at
    /// second-level precision here — these are scheduled ahead of time
    /// (unlike the in-app live countdown, #221), so a coarser unit avoids
    /// implying more precision than a fire-time estimate actually has.
    private func formatTTLRemaining(_ seconds: Int) -> String {
        let clamped = max(seconds, 0)
        let days = UInt64(clamped) / 86_400
        let hours = (UInt64(clamped) % 86_400) / 3_600
        if days > 0 {
            return String(format: NSLocalizedString("%dd %dh", comment: "TTL format with days and hours"), days, hours)
        }
        let minutes = (UInt64(clamped) % 3_600) / 60
        if hours > 0 {
            return String(format: NSLocalizedString("%dh %dm", comment: "TTL format with hours and minutes"), hours, minutes)
        }
        return String(format: NSLocalizedString("%dm", comment: "TTL format with minutes only"), minutes)
    }

    // MARK: - Offline Check-In Queue Indicator

    private static let queuedCheckInIdentifier = "checkin-queued"

    /// Surfaces a persistent local notification while check-ins are queued for retry, mirroring
    /// Android's NotificationHelper.showQueuedCheckIn. iOS has no true "ongoing" notification
    /// (UNNotificationRequest has no non-dismissable/foreground-service equivalent to Android's
    /// setOngoing(true)), so this re-posts the same identifier every time the queue changes;
    /// cancelQueuedCheckIn() removes it once the queue drains. VaultStore's queuedCheckInCount
    /// mirrors this in-app for while the app is foregrounded.
    func showQueuedCheckIn(count: Int) {
        let center = UNUserNotificationCenter.current()
        let content = UNMutableNotificationContent()
        content.title = LocalizedStrings.queuedCheckInTitle
        content.body = LocalizedStrings.queuedCheckInBody(count: count)

        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: deferredDelay(minimum: 1), repeats: false)
        let request = UNNotificationRequest(identifier: Self.queuedCheckInIdentifier, content: content, trigger: trigger)
        center.removePendingNotificationRequests(withIdentifiers: [Self.queuedCheckInIdentifier])
        center.add(request)
    }

    func cancelQueuedCheckIn() {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [Self.queuedCheckInIdentifier])
        center.removeDeliveredNotifications(withIdentifiers: [Self.queuedCheckInIdentifier])
    }

    func showVaultExpiredNotification(vaultId: String) {
        let center = UNUserNotificationCenter.current()
        let identifier = "vault-expired-\(vaultId)"
        center.removePendingNotificationRequests(withIdentifiers: [identifier])
        let content = UNMutableNotificationContent()
        content.title = LocalizedStrings.vaultExpiredTitle
        content.body = LocalizedStrings.vaultExpiredBody
        content.sound = .default
        content.userInfo = ["vault_id": vaultId]
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: deferredDelay(minimum: 1), repeats: false)
        let request = UNNotificationRequest(identifier: identifier, content: content, trigger: trigger)
        center.add(request)
    }

    func removeAllPendingNotifications() {
        UNUserNotificationCenter.current().removeAllPendingNotificationRequests()
    }

    // MARK: - UNUserNotificationCenterDelegate

    /// Called whenever a notification (local or remote/push) arrives while the
    /// app is running, foreground or background. Two responsibilities (#232,
    /// #235):
    ///
    /// - Suppress a push-delivered `vault_expired`/`vault_released` banner if
    ///   the WebSocket already delivered and applied the same event recently
    ///   — otherwise the user could see a duplicate for one state change.
    /// - Log every non-suppressed arrival as `delivered` for support triage.
    ///
    /// Local reminders (check-in, TTL warning, queued) carry no `"type"` key
    /// in `userInfo`, so the dedup check never matches them — they are
    /// always shown, unchanged from before this method existed.
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                 willPresent notification: UNNotification,
                                 withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        let userInfo = notification.request.content.userInfo
        let vaultID = userInfo["vault_id"] as? String ?? "unknown"
        let type = userInfo["type"] as? String

        if let type, type == "vault_expired" || type == "vault_released",
           NotificationDeliveryLog.shared.wasRecentlyDeliveredViaWebSocket(vaultID: vaultID, eventType: type) {
            NotificationDeliveryLog.shared.record(kind: .suppressed, source: .push, eventType: type, vaultID: vaultID)
            completionHandler([])
            return
        }

        let source: NotificationDeliveryEvent.Source = type != nil ? .push : .local
        let eventType = type ?? notification.request.content.categoryIdentifier
        NotificationDeliveryLog.shared.record(kind: .delivered, source: source, eventType: eventType, vaultID: vaultID)
        // Show banner, sound, badge, and list for better visibility of notification actions
        completionHandler([.banner, .sound, .badge, .list])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                 didReceive response: UNNotificationResponse,
                                 withCompletionHandler completionHandler: @escaping () -> Void) {
        let content = response.notification.request.content
        let vaultID = content.userInfo["vault_id"] as? String
        // Issue #473: the same eventType derivation willPresent uses, so tap/
        // dismiss events group under the same key as scheduled/delivered ones.
        let eventType = (content.userInfo["type"] as? String) ?? content.categoryIdentifier
        let source: NotificationDeliveryEvent.Source = content.userInfo["type"] != nil ? .push : .local

        if response.actionIdentifier == UNNotificationDismissActionIdentifier {
            NotificationDeliveryLog.shared.record(kind: .dismissed, source: source, eventType: eventType, vaultID: vaultID ?? "unknown")
        } else {
            NotificationDeliveryLog.shared.record(kind: .tapped, source: source, eventType: eventType, vaultID: vaultID ?? "unknown")
        }

        switch response.actionIdentifier {
        case "CHECK_IN_ACTION":
            guard let id = vaultID else { break }
            // `checkIn(vaultID:idempotencyKey:)` is ambiguous between APIClient's original
            // throwing/Void signature and the CheckInSyncTask.APIClientProtocol conformance's
            // overload — pin the reference to the original before calling it.
            let performCheckIn: (String, String?) async throws -> Void = APIClient.shared.checkIn(vaultID:idempotencyKey:)
            Task {
                do {
                    try await performCheckIn(id, nil)
                    // Issue #473: recorded only on success, separately from the
                    // tap above, so tap rate and completion rate are distinguishable.
                    NotificationDeliveryLog.shared.record(kind: .completed, source: source, eventType: eventType, vaultID: id)
                } catch {
                    // Swallowed deliberately (pre-existing behavior): a failed
                    // tap-to-check-in has no UI to surface an error to here.
                }
            }

        case "SNOOZE_7_DAYS":
            rescheduleNotificationReminder(forVaultID: vaultID, delaySeconds: 7 * 24 * 3_600)

        case "SNOOZE_14_DAYS":
            rescheduleNotificationReminder(forVaultID: vaultID, delaySeconds: 14 * 24 * 3_600)

        default:
            break
        }

        completionHandler()
    }

    /// Reschedules a notification reminder after a snooze action.
    private func rescheduleNotificationReminder(forVaultID vaultID: String?, delaySeconds: Int) {
        guard let vaultID = vaultID else { return }

        let center = UNUserNotificationCenter.current()
        // Remove existing reminder for this vault
        center.removePendingNotificationRequests(withIdentifiers: [
            "checkin-primary-\(vaultID)",
            "checkin-secondary-\(vaultID)",
            "ttl-warning-\(vaultID)"
        ])

        // Schedule new reminder after the snooze duration
        let content = UNMutableNotificationContent()
        content.title = "Check-in Reminder"
        content.body = "Vault \(truncatedVaultID(vaultID)) reminder. Tap to check in and keep it active."
        content.sound = .default
        content.userInfo = ["vault_id": vaultID]
        content.categoryIdentifier = "CHECK_IN"

        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: TimeInterval(delaySeconds), repeats: false)
        let request = UNNotificationRequest(identifier: "checkin-snoozed-\(vaultID)", content: content, trigger: trigger)
        center.add(request)

        NotificationDeliveryLog.shared.record(kind: .scheduled, source: .local, eventType: "snooze_reminder", vaultID: vaultID)
    }

    private static let ttlWarningBatchIdentifier = "ttl-warning-batch"
    private static let ttlWarningBatchDefaultsKey = "com.ethosprotocol.pending_ttl_warning_batch"

    // Fires immediately to warn the user their vault TTL is under 24 hours (called from background refresh).
    //
    // #478: outside quiet hours this behaves as before — one immediate per-vault
    // notification. During quiet hours, instead of firing (and silently queuing)
    // a separate notification per vault as each one's TTL crosses the warning
    // threshold, vault IDs accumulate into one pending batch that's delivered as
    // a single combined notification when quiet hours end.
    func scheduleTTLWarning(vaultID: String, ttlRemaining: UInt64) {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: ["ttl-warning-\(vaultID)"])

        guard NotificationPreferences.current.isSuppressedByQuietHours() else {
            let content = UNMutableNotificationContent()
            content.title = LocalizedStrings.ttlWarningTitle
            content.body = LocalizedStrings.ttlWarningBody(vaultID: truncatedVaultID(vaultID), timeRemaining: formatTTLRemaining(Int(ttlRemaining)))
            content.sound = .default
            content.userInfo = ["vault_id": vaultID]
            content.categoryIdentifier = "CHECK_IN"

            let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 5, repeats: false)
            let request = UNNotificationRequest(identifier: "ttl-warning-\(vaultID)", content: content, trigger: trigger)
            center.add(request)
            NotificationDeliveryLog.shared.record(kind: .scheduled, source: .local, eventType: "ttl_warning", vaultID: vaultID)
            return
        }

        var pending = Set(UserDefaults.standard.stringArray(forKey: Self.ttlWarningBatchDefaultsKey) ?? [])
        pending.insert(vaultID)
        UserDefaults.standard.set(Array(pending), forKey: Self.ttlWarningBatchDefaultsKey)

        let content = UNMutableNotificationContent()
        content.title = LocalizedStrings.ttlWarningTitle
        content.body = pending.count == 1
            ? LocalizedStrings.ttlWarningBody(vaultID: truncatedVaultID(vaultID), timeRemaining: formatTTLRemaining(Int(ttlRemaining)))
            : "\(pending.count) vaults have TTL warnings waiting."
        content.sound = .default
        content.categoryIdentifier = "CHECK_IN"

        center.removePendingNotificationRequests(withIdentifiers: [Self.ttlWarningBatchIdentifier])
        let delay = deferredDelay(minimum: 5)
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: delay, repeats: false)
        let request = UNNotificationRequest(identifier: Self.ttlWarningBatchIdentifier, content: content, trigger: trigger)
        center.add(request) { [weak self] error in
            guard error == nil else { return }
            // Clear the pending set once the batch has actually been scheduled for
            // delivery, so the next call starts a fresh batch instead of growing
            // this one forever.
            self?.clearPendingTTLWarningBatchAfter(delay: delay)
        }
        NotificationDeliveryLog.shared.record(kind: .scheduled, source: .local, eventType: "ttl_warning_batched", vaultID: vaultID)
    }

    private func clearPendingTTLWarningBatchAfter(delay: TimeInterval) {
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
            UserDefaults.standard.removeObject(forKey: Self.ttlWarningBatchDefaultsKey)
        }
    }

    func registerNotificationCategories() {
        // .authenticationRequired ensures iOS forces the device to be unlocked before this
        // action fires — otherwise anyone with the phone in hand could trigger a check-in
        // (resetting the vault's TTL) straight from the lock screen banner, bypassing the
        // BiometricService confirmation that guards the equivalent in-app action.
        let checkInAction = UNNotificationAction(identifier: "CHECK_IN_ACTION", title: LocalizedStrings.checkInAction, options: [.foreground, .authenticationRequired])
        // Issue #473: .customDismissAction is required for a plain swipe-to-dismiss
        // to actually invoke didReceive(response:) at all — without it, iOS doesn't
        // report dismissals to the delegate, so dismissal tracking would silently
        // never fire no matter what the delegate method itself did.
        let category = UNNotificationCategory(identifier: "CHECK_IN", actions: [checkInAction],
                                               intentIdentifiers: [], options: [.customDismissAction])
        UNUserNotificationCenter.current().setNotificationCategories([category])
    }
}
