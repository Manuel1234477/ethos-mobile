import Foundation

/// Redacts sensitive values from log messages before they are emitted.
///
/// The redaction policy covers:
/// - `Bearer` tokens (Authorization headers)
/// - `x-nonce` values
/// - 6-digit OTPs in `otp=` / `code=` fields
/// - `otpauth://` provisioning URIs
/// - `token=` query parameters (e.g. acceptance tokens from `/accept?token=`)
enum LogRedactor {

    private static let patterns: [(regex: NSRegularExpression, replacement: String)] = {
        let specs: [(String, String)] = [
            // Bearer tokens: keep the scheme, redact the credential.
            (#"(?i)(Bearer\s+)[A-Za-z0-9\-._~+/]+=*"#, "$1[REDACTED]"),
            // x-nonce values (header or key/value form).
            (#"(?i)(x-nonce\s*[:=]\s*)[^\s,;\"']+"#, "$1[REDACTED]"),
            // 6-digit OTPs in otp= / code= fields.
            (#"(?i)(\b(?:otp|code)\s*=\s*)[^\s&,;\"']+"#, "$1[REDACTED]"),
            // otpauth:// provisioning URIs (redact the whole URI).
            (#"(?i)otpauth://[^\s\"']+"#, "[REDACTED]"),
            // token= query parameters (acceptance tokens from /accept?token=).
            (#"(?i)([?&]token\s*=\s*)[^\s&,;\"']+"#, "$1[REDACTED]")
        ]

        return specs.compactMap { pattern, replacement in
            guard let regex = try? NSRegularExpression(pattern: pattern) else { return nil }
            return (regex, replacement)
        }
    }()

    /// Returns a copy of `message` with all sensitive values redacted.
    static func redact(_ message: String) -> String {
        var result = message
        let range = NSRange(result.startIndex..<result.endIndex, in: result)

        for (regex, replacement) in patterns {
            result = regex.stringByReplacingMatches(
                in: result,
                options: [],
                range: NSRange(result.startIndex..<result.endIndex, in: result),
                withTemplate: replacement
            )
        }

        _ = range
        return result
    }
}
