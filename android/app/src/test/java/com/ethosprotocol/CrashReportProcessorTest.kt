package com.ethosprotocol

import android.content.Context
import android.content.SharedPreferences
import com.ethosprotocol.services.CrashReport
import com.ethosprotocol.services.CrashReportProcessor
import com.ethosprotocol.services.CrashSeverity
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [CrashReportProcessor] (issue #470).
 *
 * Uses an in-memory fake [SharedPreferences] backed by a [MutableMap] — the
 * same pattern used across this project (e.g. NotificationDeliveryLogTest) —
 * so no Android runtime or Robolectric is required.
 *
 * Coverage:
 * - ingest and retrieve reports
 * - automatic severity triage rules (all four levels)
 * - acknowledge flow
 * - dashboard summary counts and mostFrequentException
 * - severity filter (reports(minimumSeverity))
 * - trim-to-200 cap
 * - reset clears the store
 * - unknown-acknowledge-id is a safe no-op
 * - lastCrashTimestampMs reflects the maximum timestamp across stored reports
 */
class CrashReportProcessorTest {

    private lateinit var processor: CrashReportProcessor

    @Before
    fun setUp() {
        val context: Context = mockk(relaxed = true)
        every { context.getSharedPreferences(any<String>(), any()) } returns fakeSharedPreferences()
        processor = CrashReportProcessor(context)
    }

    // ---------- helpers ----------

    private fun fakeSharedPreferences(): SharedPreferences {
        val backing = mutableMapOf<String, String?>()
        val editor: SharedPreferences.Editor = mockk(relaxed = true)
        val prefs: SharedPreferences = mockk()

        every { prefs.getString(any(), any()) } answers { backing[firstArg()] ?: secondArg() }
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } answers {
            backing[firstArg()] = secondArg()
            editor
        }
        every { editor.remove(any()) } answers {
            backing.remove(firstArg())
            editor
        }
        every { editor.apply() } just Runs

        return prefs
    }

    private fun makeReport(
        severity: CrashSeverity = CrashSeverity.MEDIUM,
        type: String = "RuntimeException",
        message: String = "test error",
        timestampMs: Long = System.currentTimeMillis()
    ): CrashReport = CrashReport(
        id = java.util.UUID.randomUUID().toString(),
        timestampMs = timestampMs,
        appVersion = "1.0.0",
        osVersion = "14",
        deviceModel = "Pixel 8",
        stackTrace = "at com.ethosprotocol.Foo.bar(Foo.kt:42)",
        exceptionType = type,
        exceptionMessage = message,
        severity = severity
    )

    // ---------- ingest / retrieve ----------

    @Test
    fun `ingest stores a report and allReports returns it`() {
        val report = makeReport()
        processor.ingest(report)
        val all = processor.allReports()
        assertEquals(1, all.size)
        assertEquals(report.id, all.first().id)
    }

    @Test
    fun `allReports returns newest first`() {
        val first = makeReport(timestampMs = 1_000L)
        val second = makeReport(timestampMs = 2_000L)
        processor.ingest(first)
        processor.ingest(second)
        val all = processor.allReports()
        assertEquals(second.id, all[0].id)
        assertEquals(first.id, all[1].id)
    }

    @Test
    fun `allReports is empty when no reports have been ingested`() {
        assertTrue(processor.allReports().isEmpty())
    }

    // ---------- trim ----------

    @Test
    fun `ingest trims store to 200 reports`() {
        repeat(250) { processor.ingest(makeReport()) }
        assertEquals(200, processor.allReports().size)
    }

    @Test
    fun `oldest reports are dropped when cap is exceeded`() {
        val old = makeReport(timestampMs = 1L)
        processor.ingest(old)
        // Fill up to and beyond the cap — old should be evicted
        repeat(200) { processor.ingest(makeReport()) }
        val ids = processor.allReports().map { it.id }
        assertFalse("Oldest report should have been evicted", ids.contains(old.id))
    }

    // ---------- triage severity ----------

    @Test
    fun `triageSeverity returns CRITICAL for OutOfMemoryError`() {
        assertEquals(CrashSeverity.CRITICAL, processor.triageSeverity("OutOfMemoryError", ""))
    }

    @Test
    fun `triageSeverity returns CRITICAL for StackOverflowError`() {
        assertEquals(CrashSeverity.CRITICAL, processor.triageSeverity("StackOverflowError", ""))
    }

    @Test
    fun `triageSeverity returns CRITICAL when message contains fatal (case-insensitive)`() {
        assertEquals(CrashSeverity.CRITICAL, processor.triageSeverity("RuntimeException", "Fatal signal 11"))
    }

    @Test
    fun `triageSeverity returns HIGH for NullPointerException`() {
        assertEquals(CrashSeverity.HIGH, processor.triageSeverity("NullPointerException", ""))
    }

    @Test
    fun `triageSeverity returns HIGH for IllegalStateException`() {
        assertEquals(CrashSeverity.HIGH, processor.triageSeverity("IllegalStateException", ""))
    }

    @Test
    fun `triageSeverity returns MEDIUM for IOException`() {
        assertEquals(CrashSeverity.MEDIUM, processor.triageSeverity("IOException", ""))
    }

    @Test
    fun `triageSeverity returns MEDIUM for NetworkException`() {
        assertEquals(CrashSeverity.MEDIUM, processor.triageSeverity("NetworkException", ""))
    }

    @Test
    fun `triageSeverity returns LOW for unrecognised exception type`() {
        assertEquals(CrashSeverity.LOW, processor.triageSeverity("SomeUnknownException", "nothing special"))
    }

    // ---------- acknowledge ----------

    @Test
    fun `acknowledge marks matching report as acknowledged`() {
        val report = makeReport()
        processor.ingest(report)
        processor.acknowledge(report.id)
        assertTrue(processor.allReports().first().isAcknowledged)
    }

    @Test
    fun `acknowledge does not affect other reports`() {
        val a = makeReport()
        val b = makeReport()
        processor.ingest(a)
        processor.ingest(b)
        processor.acknowledge(a.id)
        val byId = processor.allReports().associateBy { it.id }
        assertTrue(byId[a.id]!!.isAcknowledged)
        assertFalse(byId[b.id]!!.isAcknowledged)
    }

    @Test
    fun `acknowledge with unknown id is a no-op`() {
        val report = makeReport()
        processor.ingest(report)
        processor.acknowledge("does-not-exist")
        // Original report should remain unacknowledged
        assertFalse(processor.allReports().first().isAcknowledged)
    }

    // ---------- dashboard summary ----------

    @Test
    fun `dashboardSummary counts by severity correctly`() {
        processor.ingest(makeReport(CrashSeverity.CRITICAL, "OutOfMemoryError"))
        processor.ingest(makeReport(CrashSeverity.CRITICAL, "OutOfMemoryError"))
        processor.ingest(makeReport(CrashSeverity.HIGH, "NullPointerException"))
        processor.ingest(makeReport(CrashSeverity.MEDIUM))
        processor.ingest(makeReport(CrashSeverity.LOW, "SomeException"))

        val summary = processor.dashboardSummary()
        assertEquals(5, summary.totalCrashes)
        assertEquals(2, summary.criticalCount)
        assertEquals(1, summary.highCount)
        assertEquals(1, summary.mediumCount)
        assertEquals(1, summary.lowCount)
    }

    @Test
    fun `dashboardSummary identifies mostFrequentException`() {
        processor.ingest(makeReport(CrashSeverity.CRITICAL, "OutOfMemoryError"))
        processor.ingest(makeReport(CrashSeverity.CRITICAL, "OutOfMemoryError"))
        processor.ingest(makeReport(CrashSeverity.HIGH, "NullPointerException"))

        val summary = processor.dashboardSummary()
        assertEquals("OutOfMemoryError", summary.mostFrequentException)
    }

    @Test
    fun `dashboardSummary unacknowledgedCount reflects only unacknowledged entries`() {
        val a = makeReport()
        val b = makeReport()
        processor.ingest(a)
        processor.ingest(b)
        processor.acknowledge(a.id)

        val summary = processor.dashboardSummary()
        assertEquals(1, summary.unacknowledgedCount)
    }

    @Test
    fun `dashboardSummary lastCrashTimestampMs is the maximum timestamp`() {
        processor.ingest(makeReport(timestampMs = 1_000L))
        processor.ingest(makeReport(timestampMs = 5_000L))
        processor.ingest(makeReport(timestampMs = 3_000L))

        val summary = processor.dashboardSummary()
        assertEquals(5_000L, summary.lastCrashTimestampMs)
    }

    @Test
    fun `dashboardSummary on empty store returns zero counts and null fields`() {
        val summary = processor.dashboardSummary()
        assertEquals(0, summary.totalCrashes)
        assertEquals(0, summary.criticalCount)
        assertNull(summary.mostFrequentException)
        assertNull(summary.lastCrashTimestampMs)
    }

    // ---------- severity filter ----------

    @Test
    fun `reports(HIGH) returns only HIGH and CRITICAL`() {
        processor.ingest(makeReport(CrashSeverity.LOW, "SomeException"))
        processor.ingest(makeReport(CrashSeverity.MEDIUM))
        processor.ingest(makeReport(CrashSeverity.HIGH, "NullPointerException"))
        processor.ingest(makeReport(CrashSeverity.CRITICAL, "OutOfMemoryError"))

        val filtered = processor.reports(CrashSeverity.HIGH)
        assertEquals(2, filtered.size)
        assertTrue(filtered.all { it.severity == CrashSeverity.HIGH || it.severity == CrashSeverity.CRITICAL })
    }

    @Test
    fun `reports(CRITICAL) returns only CRITICAL`() {
        processor.ingest(makeReport(CrashSeverity.HIGH, "NullPointerException"))
        processor.ingest(makeReport(CrashSeverity.CRITICAL, "OutOfMemoryError"))

        val filtered = processor.reports(CrashSeverity.CRITICAL)
        assertEquals(1, filtered.size)
        assertEquals(CrashSeverity.CRITICAL, filtered.first().severity)
    }

    @Test
    fun `reports(LOW) returns all reports`() {
        processor.ingest(makeReport(CrashSeverity.LOW, "SomeException"))
        processor.ingest(makeReport(CrashSeverity.HIGH, "NullPointerException"))
        assertEquals(2, processor.reports(CrashSeverity.LOW).size)
    }

    // ---------- reset ----------

    @Test
    fun `reset clears all stored reports`() {
        processor.ingest(makeReport())
        processor.ingest(makeReport())
        processor.reset()
        assertTrue(processor.allReports().isEmpty())
    }

    @Test
    fun `reset followed by ingest works correctly`() {
        processor.ingest(makeReport())
        processor.reset()
        val fresh = makeReport(CrashSeverity.CRITICAL, "OutOfMemoryError")
        processor.ingest(fresh)
        val all = processor.allReports()
        assertEquals(1, all.size)
        assertEquals(fresh.id, all.first().id)
    }

    // ---------- #634 logging behaviour ----------

    /**
     * Helper that builds a processor wired to an in-memory log sink so tests can
     * inspect log output without Robolectric or mockkStatic(Log::class).
     */
    private fun processorWithLogCapture(
        isDebug: Boolean,
        logLines: MutableList<String>
    ): CrashReportProcessor {
        val context: Context = mockk(relaxed = true)
        every { context.getSharedPreferences(any<String>(), any()) } returns fakeSharedPreferences()
        return CrashReportProcessor(context, isDebug = isDebug, logSink = { logLines += it })
    }

    @Test
    fun `release build logs only exception type and crash ID — no raw message`() {
        val logged = mutableListOf<String>()
        val proc = processorWithLogCapture(isDebug = false, logLines = logged)
        val report = makeReport(message = "Bearer eyJhbGciOiJIUzI1NiJ9.secret")

        proc.ingest(report)

        assertEquals("expected exactly one log line", 1, logged.size)
        val line = logged[0]
        assertFalse("raw exceptionMessage must not appear in release log", line.contains("Bearer"))
        assertFalse("raw exceptionMessage must not appear in release log", line.contains("secret"))
        assertTrue("log must contain exceptionType", line.contains(report.exceptionType))
        assertTrue("log must contain crash ID", line.contains(report.id))
    }

    @Test
    fun `debug build logs exception type, redacted message, and crash ID`() {
        val logged = mutableListOf<String>()
        val proc = processorWithLogCapture(isDebug = true, logLines = logged)
        val sensitiveMessage = "Bearer eyJhbGciOiJIUzI1NiJ9.secret token leaked here"
        val report = makeReport(message = sensitiveMessage)

        proc.ingest(report)

        assertEquals("expected exactly one log line", 1, logged.size)
        val line = logged[0]
        // Token value must be redacted by LogRedactor
        assertFalse("raw Bearer token must be redacted in debug log", line.contains("eyJhbGciOiJIUzI1NiJ9.secret"))
        assertTrue("redacted placeholder must appear", line.contains("[REDACTED]"))
        assertTrue("log must contain exceptionType", line.contains(report.exceptionType))
        assertTrue("log must contain crash ID", line.contains(report.id))
    }

    @Test
    fun `release build critical alert logs only exception type and crash ID — no raw message`() {
        val logged = mutableListOf<String>()
        val proc = processorWithLogCapture(isDebug = false, logLines = logged)
        // CRITICAL severity triggers alertCritical() in addition to the ingest log
        val report = makeReport(
            severity = CrashSeverity.CRITICAL,
            type = "OutOfMemoryError",
            message = "Authorization: Bearer top-secret-token"
        )

        proc.ingest(report)

        // Two lines: one from ingest(), one from alertCritical()
        assertEquals("expected ingest + alertCritical log lines", 2, logged.size)
        for (line in logged) {
            assertFalse("raw token must never appear in release log: $line", line.contains("top-secret-token"))
            assertTrue("crash ID must appear in every line: $line", line.contains(report.id))
        }
    }

    @Test
    fun `debug build critical alert redacts Bearer token in message`() {
        val logged = mutableListOf<String>()
        val proc = processorWithLogCapture(isDebug = true, logLines = logged)
        val report = makeReport(
            severity = CrashSeverity.CRITICAL,
            type = "OutOfMemoryError",
            message = "Bearer abc123.secret"
        )

        proc.ingest(report)

        assertEquals("expected ingest + alertCritical log lines", 2, logged.size)
        for (line in logged) {
            assertFalse("raw token value must be redacted in debug log: $line", line.contains("abc123.secret"))
        }
    }

    @Test
    fun `release build does not log exceptionMessage even when it contains no known patterns`() {
        val logged = mutableListOf<String>()
        val proc = processorWithLogCapture(isDebug = false, logLines = logged)
        val sensitivePlainText = "userId=abc-def-ghi requestBody={token:xyz}"
        val report = makeReport(message = sensitivePlainText)

        proc.ingest(report)

        assertEquals(1, logged.size)
        assertFalse(
            "exceptionMessage must not appear at all in release log regardless of content",
            logged[0].contains(sensitivePlainText)
        )
    }
}
