package com.ethosprotocol.security

/**
 * Utility for scrubbing sensitive values from strings and header maps before
 * they are written to any diagnostic channel (Logcat, crash reporters, Ktor
 * [io.ktor.client.plugins.logging.Logging], etc.).
 *
 * Covers all six categories listed in the API contract §Logging Redaction
 * Policy (#111):
 *   1. Bearer JWT tokens / `Authorization` header values
 *   2. 2FA secrets, OTPs, provisioning URIs, TOTP seeds
 *   3. Vault balances and deposit / withdrawal amounts
 *   4. Beneficiary or owner wallet addresses (Stellar `G…` public keys)
 *   5. Acceptance tokens (one-time token from `/accept?token=…`)
 *   6. Full request / response bodies for authenticated endpoints
 *
 * All matching is case-insensitive so callers don't need to normalise header
 * names or field casing before passing them in.
 */
object LogRedactor {

    // -------------------------------------------------------------------------
    // Sensitive header names (lowercase for case-insensitive comparison)
    // -------------------------------------------------------------------------

    /**
     * HTTP header names whose values must always be replaced with `[REDACTED]`
     * before logging. Matching is case-insensitive.
     */
    val SENSITIVE_HEADERS: Set<String> = setOf(
        "authorization",    // category 1 – Bearer JWT
        "x-nonce",
        "x-otp",            // category 2 – OTP
        "x-2fa-token"       // category 2 – 2FA token
    )

    // -------------------------------------------------------------------------
    // Header redaction
    // -------------------------------------------------------------------------

    /**
     * Returns a copy of [headers] where every entry whose key (case-insensitively)
     * is in [SENSITIVE_HEADERS] has its value replaced with `"[REDACTED]"`. All
     * other entries are left unchanged.
     */
    fun redactHeaders(headers: Map<String, String>): Map<String, String> =
        headers.mapValues { (key, value) ->
            if (SENSITIVE_HEADERS.contains(key.lowercase())) "[REDACTED]" else value
        }

    // -------------------------------------------------------------------------
    // Pre-compiled regexes for redactString
    // -------------------------------------------------------------------------

    // Category 1: Bearer JWT
    private val BEARER_REGEX = Regex(
        pattern = """Bearer\s+[\w.\-~+/=]+""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    // Anti-replay nonce (not in the six categories but retained for backward compat)
    private val NONCE_REGEX = Regex(
        pattern = """(x-nonce\s*[=:]\s*)[\w.\-]+""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    // Category 2: 2FA secrets, OTPs, provisioning URIs, TOTP seeds
    private val OTP_FIELD_REGEX = Regex(
        pattern = """"(otp|totp_secret|provisioning_uri|2fa_secret|otp_seed|secret)"\s*:\s*"[^"]*"""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    // Category 3: Vault balances and amounts (numeric JSON fields)
    private val AMOUNT_FIELD_REGEX = Regex(
        pattern = """"(balance|amount|deposit_amount|withdraw_amount)"\s*:\s*[0-9]+(?:\.[0-9]+)?""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    // Category 4: Stellar wallet addresses (G + 55 uppercase base32 chars = 56 total)
    private val STELLAR_ADDRESS_REGEX = Regex(
        pattern = """G[A-Z2-7]{55}""",
        options = emptySet()
    )

    // Category 5a: acceptance token in URL query param  (?token=… or &token=…)
    private val TOKEN_QUERYPARAM_REGEX = Regex(
        pattern = """((?:^|[?&])token=)[^&\s"]*""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    // Category 5b: acceptance token as JSON field  "token":"…"
    private val TOKEN_JSON_FIELD_REGEX = Regex(
        pattern = """"token"\s*:\s*"[^"]+"""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    // Category 6: full request / response body in a log line
    private val BODY_LOG_REGEX = Regex(
        pattern = """((?:Request|Response)\s+body\s*:\s*)\{[^}]*\}""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    // -------------------------------------------------------------------------
    // String redaction
    // -------------------------------------------------------------------------

    /**
     * Query parameter names whose values must be redacted when they appear in a
     * URL or query string (#638/#639). Matching is case-insensitive.
     */
    private val SENSITIVE_QUERY_PARAMS: Set<String> = setOf(
        "token",
        "code",
        "otp",
        "secret",
        "key"
    )

    /**
     * Matches `name=value` pairs for the sensitive query parameters above. The
     * value is captured up to the next `&`, `#`, whitespace, or end of string.
     */
    private val QUERY_PARAM_REGEX = Regex(
        pattern = """(?i)\b(token|code|otp|secret|key)=([^&#\s]*)"""
    )

    /**
     * Replaces all six contract-mandated sensitive patterns in [input] with
     * safe placeholders.
     *
     * | # | Pattern | Replacement |
     * |---|---------|-------------|
     * | 1 | `Bearer <token>` | `Bearer [REDACTED]` |
     * | 2 | `"otp":"…"`, `"totp_secret":"…"`, etc. | value → `[REDACTED]` |
     * | 3 | `"balance":…`, `"amount":…` | value → `[REDACTED]` |
     * | 4 | Stellar G… public-key (56-char base32) | `[REDACTED_ADDRESS]` |
     * | 5 | `token=…` (URL) or `"token":"…"` (JSON) | value → `[REDACTED]` |
     * | 6 | `Request body: {…}` / `Response body: {…}` | `[REDACTED_BODY]` |
     *
     * @param input The raw string to sanitise.
     * @return A copy of [input] with sensitive patterns replaced.
     */
    fun redactString(input: String): String {
        var result = input

        // Category 1 – Bearer JWT
        result = BEARER_REGEX.replace(result, "Bearer [REDACTED]")

        // Nonce (backward-compat, not in the six categories)
        result = NONCE_REGEX.replace(result) { match ->
            "${match.groupValues[1]}[REDACTED]"
        }

        // Category 2 – 2FA / OTP fields
        result = OTP_FIELD_REGEX.replace(result) { match ->
            val fieldName = match.groupValues[1]
            """"$fieldName": "[REDACTED]""""
        }

        // Category 3 – balance / amount fields
        result = AMOUNT_FIELD_REGEX.replace(result) { match ->
            val fieldName = match.groupValues[1]
            """"$fieldName": [REDACTED]"""
        }

        // Category 4 – Stellar wallet addresses
        result = STELLAR_ADDRESS_REGEX.replace(result, "[REDACTED_ADDRESS]")

        // Category 5a – token query param
        result = TOKEN_QUERYPARAM_REGEX.replace(result) { match ->
            "${match.groupValues[1]}[REDACTED]"
        }

        // Category 5b – token JSON field
        result = TOKEN_JSON_FIELD_REGEX.replace(result, """"token": "[REDACTED]"""")

        // #638/#639: other sensitive query parameters (code, otp, secret, key) in URLs.
        result = QUERY_PARAM_REGEX.replace(result) { match ->
            val name = match.groupValues[1]
            if (SENSITIVE_QUERY_PARAMS.contains(name.lowercase())) "$name=[REDACTED]" else match.value
        }

        // Category 6 – request / response body
        result = BODY_LOG_REGEX.replace(result) { match ->
            "${match.groupValues[1]}[REDACTED_BODY]"
        }

        return result
    }
}
