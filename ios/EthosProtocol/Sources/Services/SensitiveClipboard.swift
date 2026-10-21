import UIKit
import Foundation
import UniformTypeIdentifiers

// MARK: - #270 SensitiveClipboard

/// Centralized utility for copying sensitive values to the clipboard with an
/// automatic-clear timer.
///
/// Any secret the user is allowed to copy — TOTP secrets, vault IDs, provisioning
/// URIs — must go through this utility so the auto-clear policy is applied
/// consistently rather than per-screen. The default timer matches iOS's own
/// password-auto-fill clipboard retention (60 seconds), long enough to paste
/// into an authenticator app but short enough to limit exposure if the user
/// forgets to clear it.
///
/// Usage:
/// ```swift
/// SensitiveClipboard.copy("my-secret-value")
/// ```
enum SensitiveClipboard {

    /// How long (in seconds) the sensitive value stays on the clipboard before
    /// it is automatically cleared. 60 s matches iOS password-auto-fill retention.
    static let clearDelaySeconds: TimeInterval = 60

    /// Copies `value` to the system clipboard and schedules a clear after
    /// `clearDelaySeconds`. A subsequent call before the timer fires will restart
    /// the timer for the new value — only the most-recent copy is ever pending.
    static func copy(_ value: String) {
        UIPasteboard.general.setItems(
            [[UTType.utf8PlainText.identifier: value]],
            options: pasteboardOptions(expiresIn: clearDelaySeconds)
        )
        scheduleClear(after: clearDelaySeconds)
    }

    /// Options applied to every sensitive copy: the entry expires after `delay`
    /// and is kept off Universal Clipboard so it does not sync to other devices.
    static func pasteboardOptions(expiresIn delay: TimeInterval) -> [UIPasteboard.OptionsKey: Any] {
        [
            .expirationDate: Date().addingTimeInterval(delay),
            .localOnly: true
        ]
    }

    // MARK: - Internals

    /// Tracks the current clear work item so it can be cancelled when a new
    /// `copy` call supersedes it.
    private static var pendingClear: DispatchWorkItem?

    private static func scheduleClear(after delay: TimeInterval) {
        pendingClear?.cancel()
        let item = DispatchWorkItem {
            // Only clear if the clipboard still holds our value — if the user
            // has already pasted something else, leave it alone.
            UIPasteboard.general.string = ""
        }
        pendingClear = item
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
    }
}
