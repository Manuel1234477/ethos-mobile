import Foundation

/// Logs APIClient response-decoding failures for triage — mirrors DeepLinkLogger's
/// thread-safe, in-memory event log pattern. Response bodies are redacted of
/// known-sensitive fields before being logged, since a decode failure means the
/// body is by definition unexpected/unvalidated content.
final class DecodingFailureLogger {
    static let shared = DecodingFailureLogger()
    private init() {}

    private var eventLog: [DecodingFailureEntry] = []
    private let queue = DispatchQueue(label: "com.ethos-protocol.decoding-failure-logger", attributes: .concurrent)

    /// Maximum number of entries retained in `eventLog`; older entries are
    /// dropped so a repeatedly failing endpoint can't grow the log unbounded
    /// (#628, mirroring Android's `NotificationDeliveryLog`).
    static let maxEntries = 100

    /// Substrings (matched case-insensitively against JSON keys) whose values
    /// are replaced with "[REDACTED]" before a response body is logged.
    ///
    /// Substring matching (rather than an exact-key set) ensures compound keys
    /// such as `access_token`, `refresh_token`, `totp_secret`, and
    /// `recovery_token` are redacted too (#625).
    ///
    /// The contract-listed fields — `amount`, `balance`, `beneficiary`, `owner`,
    /// `address`, and `acceptance_token` — are sourced from
    /// `shared/api-contract.md` §Logging Redaction Policy (#626).
    private static let sensitiveKeyPatterns: [String] = [
        "token", "secret", "otp", "password", "key",
        "amount", "balance", "beneficiary", "owner", "address", "acceptance_token"
    ]

    /// Returns `true` when `key` names a sensitive field whose value must be
    /// redacted before logging.
    static func isSensitiveKey(_ key: String) -> Bool {
        let lowered = key.lowercased()
        return sensitiveKeyPatterns.contains { lowered.contains($0) }
    }

    /// Logs a decode failure for `path` where a `expectedType` was expected but
    /// couldn't be decoded from `responseBody`.
    ///
    /// The `path` is passed through `LogRedactor.redactString` (#279) before
    /// being stored, ensuring that any token or nonce embedded in a query string
    /// is stripped even when the URL is used as the log key.
    func log(path: String, expectedType: String, responseBody: Data) {
        #if DEBUG
        let entry = DecodingFailureEntry(
            path: LogRedactor.redactString(path),
            expectedType: expectedType,
            redactedBody: Self.redact(responseBody),
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
    func getLoggedEvents() -> [DecodingFailureEntry] {
        queue.sync { self.eventLog }
    }

    /// Clears all logged events
    func clearLog() {
        queue.async(flags: .barrier) {
            self.eventLog.removeAll()
        }
    }

    /// Redacts values for known-sensitive keys from a JSON response body while
    /// preserving structure, so the shape of an unexpected response stays visible
    /// for triage without leaking tokens/credentials/secrets into logs.
    static func redact(_ data: Data) -> String {
        guard let json = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]) else {
            return "<non-JSON response, \(data.count) bytes>"
        }
        let redacted = redactValue(json)
        // JSONSerialization.data(withJSONObject:) requires a top-level array/dictionary;
        // wrap bare scalars (a response body that's just `"oops"` or `42`) so they can
        // still be serialized back out for logging.
        let wrapped: Any = (redacted is [String: Any] || redacted is [Any]) ? redacted : ["value": redacted]
        guard let redactedData = try? JSONSerialization.data(withJSONObject: wrapped, options: [.sortedKeys]),
              let string = String(data: redactedData, encoding: .utf8) else {
            return "<unrepresentable response>"
        }
        return string
    }

    private static func redactValue(_ value: Any) -> Any {
        if let dict = value as? [String: Any] {
            var result: [String: Any] = [:]
            for (key, v) in dict {
                result[key] = isSensitiveKey(key) ? "[REDACTED]" : redactValue(v)
            }
            return result
        } else if let array = value as? [Any] {
            return array.map(redactValue)
        } else {
            return value
        }
    }
}

/// A single entry in the decode-failure log
struct DecodingFailureEntry: Codable {
    let path: String
    let expectedType: String
    let redactedBody: String
    let timestamp: Date
}
