package com.ethosprotocol.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactorTest {

    // -------------------------------------------------------------------------
    // redactString — Bearer token
    // -------------------------------------------------------------------------

    /**
     * [LogRedactor.redactString] must replace the token portion of a Bearer
     * credential so the raw token value does not appear in logs.
     */
    @Test
    fun testBearerTokenRedacted() {
        val input = "Authorization: Bearer abc123.def456.ghi789"
        val output = LogRedactor.redactString(input)

        assertFalse(
            "Raw Bearer token 'abc123' must not appear in redacted output",
            output.contains("abc123")
        )
        assertFalse(
            "Raw Bearer token segment 'def456' must not appear in redacted output",
            output.contains("def456")
        )
        assertTrue(
            "Output should contain the 'Bearer [REDACTED]' placeholder",
            output.contains("Bearer [REDACTED]")
        )
    }

    /** Bearer redaction must be case-insensitive. */
    @Test
    fun testBearerTokenRedactedCaseInsensitive() {
        val input = "authorization: bearer MYSECRETTOKEN"
        val output = LogRedactor.redactString(input)

        assertFalse(
            "Token must be redacted regardless of 'bearer' keyword casing",
            output.contains("MYSECRETTOKEN")
        )
    }

    /** Non-sensitive log strings must pass through unchanged. */
    @Test
    fun testNonSensitiveStringUnchanged() {
        val input = "GET /vaults -> 200 OK (took 123 ms)"
        val output = LogRedactor.redactString(input)

        assertEquals("Non-sensitive strings must not be modified", input, output)
    }

    // -------------------------------------------------------------------------
    // redactString — token-bearing query parameters
    // -------------------------------------------------------------------------

    /**
     * Each sensitive query parameter value must be replaced with `[REDACTED]`
     * so tokens embedded in URLs never reach the logs.
     */
    @Test
    fun testQueryParameterTokenRedacted() {
        assertQueryParamRedacted("token", "/accept?token=abc123")
    }

    @Test
    fun testQueryParameterCodeRedacted() {
        assertQueryParamRedacted("code", "/callback?code=abc123")
    }

    @Test
    fun testQueryParameterOtpRedacted() {
        assertQueryParamRedacted("otp", "/verify?otp=abc123")
    }

    @Test
    fun testQueryParameterSecretRedacted() {
        assertQueryParamRedacted("secret", "/reset?secret=abc123")
    }

    @Test
    fun testQueryParameterKeyRedacted() {
        assertQueryParamRedacted("key", "/unlock?key=abc123")
    }

    /**
     * A URL carrying several sensitive parameters at once must have every
     * sensitive value redacted while non-sensitive parameters are preserved.
     */
    @Test
    fun testMultipleSensitiveQueryParametersRedacted() {
        val input = "/accept?token=abc123&code=def456&otp=ghi789&secret=jkl012&key=mno345&state=xyz"
        val output = LogRedactor.redactString(input)

        for (value in listOf("abc123", "def456", "ghi789", "jkl012", "mno345")) {
            assertFalse(
                "Sensitive query value '$value' must not appear in redacted output",
                output.contains(value)
            )
        }
        assertTrue(
            "Non-sensitive 'state' parameter must be preserved",
            output.contains("state=xyz")
        )
    }

    private fun assertQueryParamRedacted(name: String, input: String) {
        val output = LogRedactor.redactString(input)

        assertFalse(
            "Sensitive query value for '$name' must not appear in redacted output",
            output.contains("abc123")
        )
        assertTrue(
            "Output should contain '$name=[REDACTED]'",
            output.contains("$name=[REDACTED]")
        )
    }

    // -------------------------------------------------------------------------
    // redactHeaders — sensitive headers
    // -------------------------------------------------------------------------

    /**
     * [LogRedactor.redactHeaders] must replace the value of any header whose
     * (lowercased) name is in [LogRedactor.SENSITIVE_HEADERS] with `[REDACTED]`.
     */
    @Test
    fun testSensitiveHeaderRedacted() {
        val headers = mapOf(
            "Authorization" to "Bearer super-secret-token",
            "X-Nonce" to "deadbeefdeadbeef",
            "x-otp" to "123456",
            "X-2FA-Token" to "totp-payload"
        )
        val redacted = LogRedactor.redactHeaders(headers)

        for ((key, _) in headers) {
            assertEquals(
                "Header '$key' must be replaced with [REDACTED]",
                "[REDACTED]",
                redacted[key]
            )
        }
    }

    /** Header name matching must be case-insensitive. */
    @Test
    fun testSensitiveHeaderRedactedCaseInsensitive() {
        val headers = mapOf("AUTHORIZATION" to "Bearer token123")
        val redacted = LogRedactor.redactHeaders(headers)

        assertEquals(
            "AUTHORIZATION (all-caps) must be treated as sensitive",
            "[REDACTED]",
            redacted["AUTHORIZATION"]
        )
    }

    // -------------------------------------------------------------------------
    // redactHeaders — non-sensitive headers
    // -------------------------------------------------------------------------

    /**
     * Non-sensitive headers (e.g. `Content-Type`, `Accept`) must pass through
     * [LogRedactor.redactHeaders] completely unchanged.
     */
    @Test
    fun testNonSensitiveHeaderUnchanged() {
        val headers = mapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "X-Timestamp" to "1700000000"
        )
        val redacted = LogRedactor.redactHeaders(headers)

        assertEquals("Content-Type must not be redacted", "application/json", redacted["Content-Type"])
        assertEquals("Accept must not be redacted", "application/json", redacted["Accept"])
        assertEquals("X-Timestamp must not be redacted", "1700000000", redacted["X-Timestamp"])
    }

    /** A mix of sensitive and non-sensitive headers — only sensitive ones change. */
    @Test
    fun testMixedHeaders() {
        val headers = mapOf(
            "Content-Type" to "application/json",
            "Authorization" to "Bearer abc.def.ghi",
            "X-Nonce" to "0011223344556677",
            "Accept" to "application/json"
        )
        val redacted = LogRedactor.redactHeaders(headers)

        assertEquals("Content-Type should be unchanged", "application/json", redacted["Content-Type"])
        assertEquals("Accept should be unchanged", "application/json", redacted["Accept"])
        assertEquals("Authorization must be redacted", "[REDACTED]", redacted["Authorization"])
        assertEquals("X-Nonce must be redacted", "[REDACTED]", redacted["X-Nonce"])
    }
}
