package com.ethosprotocol

import com.ethosprotocol.services.SensitiveClipboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #270 — Clipboard auto-clear for sensitive values.
 *
 * [SensitiveClipboard] centralizes every "copy sensitive value" action so the
 * auto-clear policy is applied consistently rather than per-screen.
 *
 * Because this is a pure-JVM unit test (no Android Context or real ClipboardManager),
 * these tests verify the *policy constants* and logic that can be exercised without
 * an instrumented environment:
 *   - [SensitiveClipboard.CLEAR_DELAY_SECONDS] is a sane positive value.
 *   - The delay is not excessively long (> 5 min would provide no real protection).
 *
 * The actual copy-and-clear round-trip is covered by a manual QA step in
 * docs/manual-qa-checklist.md, since ClipboardManager requires a real Android
 * Context that is not available in JVM unit tests.
 */
class SensitiveClipboardTest {

    @Test
    fun `CLEAR_DELAY_SECONDS is positive`() {
        assertTrue(
            "Auto-clear delay must be > 0 seconds",
            SensitiveClipboard.CLEAR_DELAY_SECONDS > 0L
        )
    }

    @Test
    fun `CLEAR_DELAY_SECONDS does not exceed 5 minutes`() {
        // 300 s is the maximum for a useful "short-lived" clipboard window.
        assertTrue(
            "Auto-clear delay must not exceed 300 s (5 min) — longer delays provide no clipboard protection",
            SensitiveClipboard.CLEAR_DELAY_SECONDS <= 300L
        )
    }

    @Test
    fun `CLEAR_DELAY_SECONDS matches expected 60 seconds`() {
        assertEquals(
            "Default auto-clear delay should be 60 s, matching iOS SensitiveClipboard.clearDelaySeconds",
            60L,
            SensitiveClipboard.CLEAR_DELAY_SECONDS
        )
    }

    @Test
    fun `shouldClear preserves a clip copied after the sensitive value`() {
        assertFalse(
            "Auto-clear must not delete a clip the user copied during the window",
            SensitiveClipboard.shouldClear(currentClip = "other text", copiedValue = "secret")
        )
    }

    @Test
    fun `shouldClear clears when the clipboard still holds the sensitive value`() {
        assertTrue(
            SensitiveClipboard.shouldClear(currentClip = "secret", copiedValue = "secret")
        )
    }

    @Test
    fun `shouldClear preserves an empty clipboard`() {
        assertFalse(SensitiveClipboard.shouldClear(currentClip = null, copiedValue = "secret"))
    }

    @Test
    fun `shouldMarkSensitive is false below Android 13`() {
        assertFalse(SensitiveClipboard.shouldMarkSensitive(32))
    }

    @Test
    fun `shouldMarkSensitive is true from Android 13 (API 33)`() {
        assertTrue(SensitiveClipboard.shouldMarkSensitive(33))
        assertTrue(SensitiveClipboard.shouldMarkSensitive(35))
    }
}
