package com.ethosprotocol

import com.ethosprotocol.utils.PerformanceMonitor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [PerformanceMonitor.toPathTemplate].
 *
 * Issue #633: Stop Logging API Method and Path in Android Release Builds.
 *
 * toPathTemplate() sanitises a concrete API path for release-safe logging. It:
 *  - Strips the query string entirely (cursors, filters, etc. must not appear in logs).
 *  - Replaces path segments that are ≥ 8 chars AND contain at least one digit with {id}.
 *    Requiring a digit preserves known sub-resource words (challenge, sessions, initiate,
 *    beneficiary, trust-device, backup-codes, …) which are all purely alphabetic, while
 *    still catching vault IDs, session IDs, UUIDs, and opaque tokens.
 */
class PerformanceMonitorTest {

    // ── Static paths (no digits in any segment) — must pass through unchanged ─

    @Test
    fun `auth challenge path is returned unchanged`() {
        assertEquals("/auth/challenge", PerformanceMonitor.toPathTemplate("/auth/challenge"))
    }

    @Test
    fun `auth verify path is returned unchanged`() {
        assertEquals("/auth/verify", PerformanceMonitor.toPathTemplate("/auth/verify"))
    }

    @Test
    fun `auth register path is returned unchanged`() {
        assertEquals("/auth/register", PerformanceMonitor.toPathTemplate("/auth/register"))
    }

    @Test
    fun `auth refresh path is returned unchanged`() {
        assertEquals("/auth/refresh", PerformanceMonitor.toPathTemplate("/auth/refresh"))
    }

    @Test
    fun `auth sessions path is returned unchanged`() {
        assertEquals("/auth/sessions", PerformanceMonitor.toPathTemplate("/auth/sessions"))
    }

    @Test
    fun `auth recovery initiate path is returned unchanged`() {
        assertEquals("/auth/recovery/initiate", PerformanceMonitor.toPathTemplate("/auth/recovery/initiate"))
    }

    @Test
    fun `auth recovery complete path is returned unchanged`() {
        assertEquals("/auth/recovery/complete", PerformanceMonitor.toPathTemplate("/auth/recovery/complete"))
    }

    @Test
    fun `vault list path is returned unchanged`() {
        assertEquals("/vaults", PerformanceMonitor.toPathTemplate("/vaults"))
    }

    // ── Paths with a single vault/session ID segment ──────────────────────────

    @Test
    fun `vault ID segment is replaced with placeholder`() {
        assertEquals("/vaults/{id}", PerformanceMonitor.toPathTemplate("/vaults/abc12345"))
    }

    @Test
    fun `vault ID with hyphens is replaced with placeholder`() {
        assertEquals("/vaults/{id}", PerformanceMonitor.toPathTemplate("/vaults/vault-id-12345678"))
    }

    @Test
    fun `session ID segment is replaced with placeholder`() {
        assertEquals("/auth/sessions/{id}", PerformanceMonitor.toPathTemplate("/auth/sessions/sess12345"))
    }

    // ── Paths with ID segment followed by a sub-resource ─────────────────────

    @Test
    fun `vault checkin path has ID replaced and sub-resource preserved`() {
        assertEquals("/vaults/{id}/checkin", PerformanceMonitor.toPathTemplate("/vaults/abc12345/checkin"))
    }

    @Test
    fun `vault deposit path has ID replaced and sub-resource preserved`() {
        assertEquals("/vaults/{id}/deposit", PerformanceMonitor.toPathTemplate("/vaults/abc12345/deposit"))
    }

    @Test
    fun `vault withdraw path has ID replaced and sub-resource preserved`() {
        assertEquals("/vaults/{id}/withdraw", PerformanceMonitor.toPathTemplate("/vaults/abc12345/withdraw"))
    }

    @Test
    fun `vault accept path has ID replaced and sub-resource preserved`() {
        assertEquals("/vaults/{id}/accept", PerformanceMonitor.toPathTemplate("/vaults/abc12345/accept"))
    }

    @Test
    fun `vault beneficiary path has ID replaced and sub-resource preserved`() {
        assertEquals("/vaults/{id}/beneficiary", PerformanceMonitor.toPathTemplate("/vaults/abc12345/beneficiary"))
    }

    // ── 2FA sub-paths (ID + multi-segment suffix) ─────────────────────────────

    @Test
    fun `2fa status path has ID replaced`() {
        assertEquals("/vaults/{id}/2fa/status", PerformanceMonitor.toPathTemplate("/vaults/abc12345/2fa/status"))
    }

    @Test
    fun `2fa enable path has ID replaced`() {
        assertEquals("/vaults/{id}/2fa/enable", PerformanceMonitor.toPathTemplate("/vaults/abc12345/2fa/enable"))
    }

    @Test
    fun `2fa verify path has ID replaced`() {
        assertEquals("/vaults/{id}/2fa/verify", PerformanceMonitor.toPathTemplate("/vaults/abc12345/2fa/verify"))
    }

    @Test
    fun `2fa disable path has ID replaced`() {
        assertEquals("/vaults/{id}/2fa/disable", PerformanceMonitor.toPathTemplate("/vaults/abc12345/2fa/disable"))
    }

    @Test
    fun `2fa trust-device path has ID replaced and hyphenated word preserved`() {
        assertEquals("/vaults/{id}/2fa/trust-device", PerformanceMonitor.toPathTemplate("/vaults/abc12345/2fa/trust-device"))
    }

    @Test
    fun `backup codes generate path has ID replaced and sub-resources preserved`() {
        assertEquals(
            "/vaults/{id}/2fa/backup-codes/generate",
            PerformanceMonitor.toPathTemplate("/vaults/abc12345/2fa/backup-codes/generate")
        )
    }

    @Test
    fun `backup codes status path has ID replaced and sub-resources preserved`() {
        assertEquals(
            "/vaults/{id}/2fa/backup-codes/status",
            PerformanceMonitor.toPathTemplate("/vaults/abc12345/2fa/backup-codes/status")
        )
    }

    // ── Query strings stripped ────────────────────────────────────────────────

    @Test
    fun `query string is stripped from static path`() {
        assertEquals("/vaults", PerformanceMonitor.toPathTemplate("/vaults?limit=20"))
    }

    @Test
    fun `multiple query params are all stripped`() {
        assertEquals("/vaults", PerformanceMonitor.toPathTemplate("/vaults?limit=20&after=someCursor"))
    }

    @Test
    fun `pagination cursor in query string does not appear in output`() {
        val result = PerformanceMonitor.toPathTemplate("/vaults?limit=20&after=opaqueCursor9999")
        assertEquals("/vaults", result)
    }

    @Test
    fun `query string stripped before ID replacement`() {
        assertEquals(
            "/vaults/{id}/checkin",
            PerformanceMonitor.toPathTemplate("/vaults/abc12345/checkin?foo=bar")
        )
    }

    // ── Segment length boundary ───────────────────────────────────────────────

    @Test
    fun `segment with digit but under 8 chars is not replaced`() {
        // "abc1234" = 7 chars, has a digit — still too short to be treated as an ID
        assertEquals("/vaults/abc1234", PerformanceMonitor.toPathTemplate("/vaults/abc1234"))
    }

    @Test
    fun `segment with digit and exactly 8 chars is replaced`() {
        assertEquals("/vaults/{id}", PerformanceMonitor.toPathTemplate("/vaults/abc12345"))
    }

    @Test
    fun `purely alpha segment of 8+ chars is NOT replaced`() {
        // No digits → treated as a sub-resource word, not an ID
        assertEquals("/vaults/abcdefgh", PerformanceMonitor.toPathTemplate("/vaults/abcdefgh"))
    }

    // ── UUID-format IDs ───────────────────────────────────────────────────────

    @Test
    fun `UUID-format vault ID is replaced`() {
        assertEquals(
            "/vaults/{id}/checkin",
            PerformanceMonitor.toPathTemplate("/vaults/550e8400-e29b-41d4-a716-446655440000/checkin")
        )
    }

    @Test
    fun `UUID-format session ID is replaced`() {
        assertEquals(
            "/auth/sessions/{id}",
            PerformanceMonitor.toPathTemplate("/auth/sessions/550e8400-e29b-41d4-a716-446655440000")
        )
    }
}
