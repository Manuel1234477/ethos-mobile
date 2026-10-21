package com.ethosprotocol.logging

/**
 * Redacts sensitive values from log messages before they are emitted.
 *
 * Covers Bearer tokens, x-nonce values, 6-digit OTPs in otp=/code= fields,
 * otpauth:// provisioning URIs, token= query parameters (acceptance tokens),
 * and Stellar G-/M-addresses (beneficiary and owner wallets).
 */
object LogRedactor {

    private const val REDACTED = "[REDACTED]"

    private val patterns: List<Regex> = listOf(
        // Bearer tokens
        Regex("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+"),
        // x-nonce values
        Regex("(?i)(x-nonce\\s*[:=]\\s*)[A-Za-z0-9._~+/=-]+"),
        // 6-digit OTPs in otp= or code= fields
        Regex("(?i)(\\b(?:otp|code)\\s*[=:]\\s*)\\d{6}\\b"),
        // otpauth:// provisioning URIs
        Regex("(?i)otpauth://[^\\s\"']+"),
        // token= query parameters (acceptance tokens from /accept?token=)
        Regex("(?i)([?&]token=)[^&\\s\"']+"),
        // Stellar M-addresses (69 chars, muxed accounts)
        Regex("\\bM[A-Z2-7]{68}\\b"),
        // Stellar G-addresses (56 chars, account IDs)
        Regex("\\bG[A-Z2-7]{55}\\b")
    )

    fun redact(message: String): String {
        var result = message
        for (pattern in patterns) {
            result = pattern.replace(result) { match ->
                val groupCount = match.groupValues.size
                if (groupCount > 1 && match.groupValues[1].isNotEmpty()) {
                    match.groupValues[1] + REDACTED
                } else {
                    REDACTED
                }
            }
        }
        return result
    }
}
