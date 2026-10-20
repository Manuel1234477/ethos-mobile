package com.ethosprotocol.security

/**
 * Utility for scrubbing sensitive values from strings and header maps before
 * they are written to any diagnostic channel (Logcat, crash reporters, Ktor
 * [io.ktor.client.plugins.logging.Logging], etc.).
 *
 * All matching is case-insensitive so callers don't need to normalise header
 * names before passing them in.
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
        "authorization",
        "x-nonce",
        "x-otp",
        "x-2fa-token"
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
    // String redaction
    // -------------------------------------------------------------------------

    private val BEARER_REGEX = Regex(
        pattern = """Bearer\s+[\w.\-~+/=]+""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val NONCE_REGEX = Regex(
        pattern = """(x-nonce\s*[=:]\s*)[\w.\-]+""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    /**
     * Query parameter names whose values must be redacted when they appear in a
     * URL or query string. Matching is case-insensitive.
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
     * value is captured up to the next `&`, `#`, whitespace, or end of string so
     * that multi-parameter URLs are handled correctly.
     */
    private val QUERY_PARAM_REGEX = Regex(
        pattern = """(?i)\b(token|code|otp|secret|key)=([^&#\s]*)"""
    )

    /**
     * Replaces known-sensitive patterns in [input] with safe placeholders:
     *
     * - `Bearer <token>` → `Bearer [REDACTED]`
     *   Covers `Authorization: Bearer …` lines appearing in logged request dumps.
     *
     * - `x-nonce: <value>` → `x-nonce: [REDACTED]`  (case-insensitive)
     *   Covers the anti-replay nonce header if it appears in a log string.
     *
     * - `token=…`, `code=…`, `otp=…`, `secret=…`, `key=…` → `name=[REDACTED]`
     *   Covers token-bearing query parameters embedded in logged URLs/paths.
     *
     * @param input The raw string to sanitise.
     * @return A copy of [input] with sensitive patterns replaced.
     */
    fun redactString(input: String): String {
        var result = BEARER_REGEX.replace(input, "Bearer [REDACTED]")
        result = NONCE_REGEX.replace(result) { match ->
            "${match.groupValues[1]}[REDACTED]"
        }
        result = QUERY_PARAM_REGEX.replace(result) { match ->
            val name = match.groupValues[1]
            if (SENSITIVE_QUERY_PARAMS.contains(name.lowercase())) {
                "$name=[REDACTED]"
            } else {
                match.value
            }
        }
        return result
    }
}
