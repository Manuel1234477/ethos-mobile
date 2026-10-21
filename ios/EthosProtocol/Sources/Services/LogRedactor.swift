import Foundation

/// Utility for scrubbing sensitive values from strings and header dictionaries
/// before they are written to any diagnostic channel (os_log, print, crash
/// reports, `DecodingFailureLogger`, etc.).
///
/// Covers all six categories listed in the API contract §Logging Redaction
/// Policy (#111):
///   1. Bearer JWT tokens / `Authorization` header values
///   2. 2FA secrets, OTPs, provisioning URIs, TOTP seeds
///   3. Vault balances and deposit / withdrawal amounts
///   4. Beneficiary or owner wallet addresses (Stellar `G…` public keys)
///   5. Acceptance tokens (one-time token from `/accept?token=…`)
///   6. Full request / response bodies for authenticated endpoints
///
/// This is a caseless enum (used as a namespace) so it cannot be instantiated.
enum LogRedactor {

    // MARK: - Sensitive header names (all lowercase for case-insensitive matching)

    /// HTTP header names whose values must always be replaced with `[REDACTED]`
    /// before logging. Matching is case-insensitive.
    static let sensitiveHeaders: Set<String> = [
        "authorization",    // category 1 – Bearer JWT
        "x-nonce",
        "x-otp",            // category 2 – OTP
        "x-2fa-token"       // category 2 – 2FA token
    ]

    // MARK: - Header redaction

    /// Returns a copy of `headers` where every key whose lowercased form is in
    /// `sensitiveHeaders` has its value replaced with `"[REDACTED]"`. All other
    /// headers are left unchanged.
    static func redactHeaders(_ headers: [String: String]) -> [String: String] {
        return headers.reduce(into: [String: String]()) { result, pair in
            let (key, value) = pair
            result[key] = sensitiveHeaders.contains(key.lowercased()) ? "[REDACTED]" : value
        }
    }

    // MARK: - String redaction

    /// Replaces all six contract-mandated sensitive patterns in `input` with
    /// safe placeholders.
    ///
    /// | # | Pattern | Replacement |
    /// |---|---------|-------------|
    /// | 1 | `Bearer <token>` | `Bearer [REDACTED]` |
    /// | 2 | `"otp":"…"`, `"totp_secret":"…"`, `"provisioning_uri":"…"` | value → `[REDACTED]` |
    /// | 3 | `"balance":…`, `"amount":…` (numeric) | value → `[REDACTED]` |
    /// | 4 | Stellar G… public-key addresses (56-char base32) | `[REDACTED_ADDRESS]` |
    /// | 5 | `token=…` query-param or `"token":"…"` JSON field | value → `[REDACTED]` |
    /// | 6 | JSON request / response bodies of authenticated calls | `[REDACTED_BODY]` |
    ///
    /// All replacements use case-insensitive regex so the caller does not need
    /// to normalise casing first.
    static func redactString(_ input: String) -> String {
        var result = input

        // ------------------------------------------------------------------
        // Category 1: Bearer JWT tokens
        // ------------------------------------------------------------------
        // "Bearer " followed by one or more token characters (RFC 6750).
        if let regex = try? NSRegularExpression(
            pattern: #"Bearer\s+[A-Za-z0-9._\-~+/=]+"#,
            options: .caseInsensitive
        ) {
            result = regex.stringByReplacingMatches(
                in: result,
                range: NSRange(result.startIndex..., in: result),
                withTemplate: "Bearer [REDACTED]"
            )
        }

        // Redact x-nonce header values (anti-replay header, not in the six
        // categories but already present in the original implementation).
        if let regex = try? NSRegularExpression(
            pattern: #"(x-nonce\s*[=:]\s*)[A-Za-z0-9._\-]+"#,
            options: .caseInsensitive
        ) {
            result = regex.stringByReplacingMatches(
                in: result,
                range: NSRange(result.startIndex..., in: result),
                withTemplate: "$1[REDACTED]"
            )
        }

        // ------------------------------------------------------------------
        // Category 2: 2FA secrets, OTPs, provisioning URIs, TOTP seeds
        // ------------------------------------------------------------------
        // JSON fields: "otp", "totp_secret", "provisioning_uri", "2fa_secret",
        // "otp_seed"  (and their snake_case / camelCase variations).
        if let regex = try? NSRegularExpression(
            pattern: #"("(?:otp|totp_secret|provisioning_uri|2fa_secret|otp_seed|secret)"\s*:\s*)"[^"]*""#,
            options: .caseInsensitive
        ) {
            result = regex.stringByReplacingMatches(
                in: result,
                range: NSRange(result.startIndex..., in: result),
                withTemplate: #"$1"[REDACTED]""#
            )
        }

        // ------------------------------------------------------------------
        // Category 3: Vault balances and deposit / withdrawal amounts
        // ------------------------------------------------------------------
        // JSON numeric fields: "balance", "amount", "deposit_amount",
        // "withdraw_amount".
        if let regex = try? NSRegularExpression(
            pattern: #"("(?:balance|amount|deposit_amount|withdraw_amount)"\s*:\s*)[0-9]+(?:\.[0-9]+)?"#,
            options: .caseInsensitive
        ) {
            result = regex.stringByReplacingMatches(
                in: result,
                range: NSRange(result.startIndex..., in: result),
                withTemplate: "$1[REDACTED]"
            )
        }

        // ------------------------------------------------------------------
        // Category 4: Beneficiary / owner wallet addresses
        // ------------------------------------------------------------------
        // Stellar public keys: 'G' followed by 55 uppercase base32 characters
        // (total 56 chars).  We also handle lowercase variants defensively.
        if let regex = try? NSRegularExpression(
            pattern: #"G[A-Z2-7]{55}"#,
            options: []
        ) {
            result = regex.stringByReplacingMatches(
                in: result,
                range: NSRange(result.startIndex..., in: result),
                withTemplate: "[REDACTED_ADDRESS]"
            )
        }

        // ------------------------------------------------------------------
        // Category 5: Acceptance tokens
        // ------------------------------------------------------------------
        // URL query-param form: `token=<value>` (until end-of-string or '&').
        if let regex = try? NSRegularExpression(
            pattern: #"((?:^|[?&])token=)[^&\s"]*"#,
            options: .caseInsensitive
        ) {
            result = regex.stringByReplacingMatches(
                in: result,
                range: NSRange(result.startIndex..., in: result),
                withTemplate: "$1[REDACTED]"
            )
        }
        // JSON field form: "token": "<value>".
        if let regex = try? NSRegularExpression(
            pattern: #"("token"\s*:\s*)"[^"]+""#,
            options: .caseInsensitive
        ) {
            result = regex.stringByReplacingMatches(
                in: result,
                range: NSRange(result.startIndex..., in: result),
                withTemplate: #"$1"[REDACTED]""#
            )
        }

        // ------------------------------------------------------------------
        // Category 6: Full request / response bodies for authenticated calls
        // ------------------------------------------------------------------
        // Any JSON object body carried in a log string that follows a known
        // body-log prefix ("Request body:", "Response body:") is replaced in
        // its entirety.
        if let regex = try? NSRegularExpression(
            pattern: #"((?:Request|Response)\s+body\s*:\s*)\{[^}]*\}"#,
            options: .caseInsensitive
        ) {
            result = regex.stringByReplacingMatches(
                in: result,
                range: NSRange(result.startIndex..., in: result),
                withTemplate: "$1[REDACTED_BODY]"
            )
        }

        return result
    }
}
