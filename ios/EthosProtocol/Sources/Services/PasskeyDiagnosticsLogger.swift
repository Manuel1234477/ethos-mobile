import Foundation

/// Logs client-side diagnostic signal for passkey registration failures, for support
/// triage of "passkey sign-in doesn't work" reports — mirrors DecodingFailureLogger's
/// thread-safe, in-memory event log pattern.
///
/// Only the authenticator attachment type and attestation format are recorded — see
/// SECURITY.md for the full scope of what is/isn't logged. No public key material,
/// signatures, challenge bytes, or credential IDs are ever logged here.
final class PasskeyDiagnosticsLogger {
    static let shared = PasskeyDiagnosticsLogger()
    private init() {}

    private var eventLog: [PasskeyDiagnosticsEntry] = []
    private let queue = DispatchQueue(label: "com.ethos-protocol.passkey-diagnostics-logger", attributes: .concurrent)

    /// Maximum number of entries retained in memory, mirroring NotificationDeliveryLog's
    /// bounded-log pattern on Android.
    static let maxEntries = 100

    /// Logs a registration failure. `attestationFormat` is the WebAuthn `fmt` value
    /// (e.g. "packed", "none") when it could be parsed from the attestation object, or
    /// `nil` when the ceremony failed before one was produced.
    ///
    /// Diagnostic logging is restricted to debug builds; in release builds this is a no-op.
    func logRegistrationFailure(authenticatorAttachment: String, attestationFormat: String?, reason: String) {
        #if DEBUG
        let entry = PasskeyDiagnosticsEntry(
            authenticatorAttachment: authenticatorAttachment,
            attestationFormat: attestationFormat,
            reason: reason,
            timestamp: Date()
        )
        queue.async(flags: .barrier) {
            self.eventLog.append(entry)
            if self.eventLog.count > Self.maxEntries {
                self.eventLog.removeFirst(self.eventLog.count - Self.maxEntries)
            }
        }
        #endif
    }

    /// Retrieves all logged events
    func getLoggedEvents() -> [PasskeyDiagnosticsEntry] {
        queue.sync { self.eventLog }
    }

    /// Clears all logged events
    func clearLog() {
        queue.async(flags: .barrier) {
            self.eventLog.removeAll()
        }
    }
}

/// A single entry in the passkey registration diagnostics log.
struct PasskeyDiagnosticsEntry: Codable {
    let authenticatorAttachment: String
    let attestationFormat: String?
    let reason: String
    let timestamp: Date
}
