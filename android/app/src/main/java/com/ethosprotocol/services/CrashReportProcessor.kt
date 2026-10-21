package com.ethosprotocol.services

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.ethosprotocol.BuildConfig
import com.ethosprotocol.security.LogRedactor
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class CrashSeverity { LOW, MEDIUM, HIGH, CRITICAL }

@Serializable
data class CrashReport(
    val id: String,
    val timestampMs: Long,
    val appVersion: String,
    val osVersion: String,
    val deviceModel: String,
    val stackTrace: String,
    val exceptionType: String,
    val exceptionMessage: String,
    val severity: CrashSeverity = CrashSeverity.MEDIUM,
    val triageNote: String? = null,
    val isAcknowledged: Boolean = false
)

data class CrashDashboardSummary(
    val totalCrashes: Int,
    val criticalCount: Int,
    val highCount: Int,
    val mediumCount: Int,
    val lowCount: Int,
    val unacknowledgedCount: Int,
    val mostFrequentException: String?,
    val lastCrashTimestampMs: Long?
)

/**
 * Processes, triages, and aggregates crash reports for issue #470.
 *
 * Stores up to [MAX_REPORTS] reports in SharedPreferences (newest first).
 * Fires a critical alert log when a [CrashSeverity.CRITICAL] crash is ingested —
 * the [alertCritical] hook is the integration point for a future PagerDuty / push
 * alert pathway.
 *
 * Severity triage rules applied by [triageSeverity]:
 * - CRITICAL : OutOfMemoryError, StackOverflowError, or a "fatal"-keyed message
 * - HIGH     : NullPointerException, IllegalStateException
 * - MEDIUM   : IOException, NetworkException
 * - LOW      : everything else
 */
class CrashReportProcessor(
    private val context: Context,
    /** Overridable in tests; defaults to [BuildConfig.DEBUG] in production. */
    private val isDebug: Boolean = BuildConfig.DEBUG,
    /**
     * Sink for log messages produced by [ingest] and [alertCritical]. Defaults to
     * no-op; override in tests to capture log output without Robolectric or
     * `mockkStatic(Log::class)`. Each call receives the full formatted log line.
     *
     * #634: Having an injectable sink keeps the logging contract testable without
     * any Android runtime dependency.
     */
    internal val logSink: (String) -> Unit = {}
) {

    private val tag = "CrashReportProcessor"
    private val prefsName = "ethos_crash_reports"
    private val reportsKey = "crash_reports_json"
    private val maxReports = 200

    private val json = Json { ignoreUnknownKeys = true }

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    /** Ingest a new crash report. Persists, trims to [maxReports], and alerts if critical. */
    fun ingest(report: CrashReport) {
        val reports = loadReports().toMutableList()
        reports.add(0, report) // newest first
        val trimmed = reports.take(maxReports)
        saveReports(trimmed)
        // #634: Never log raw exceptionMessage in release — it can contain tokens,
        // request bodies, or user data. In debug, redact known-sensitive patterns first.
        if (isDebug) {
            val safeMessage = LogRedactor.redactString(report.exceptionMessage)
            val line = "Crash ingested: [${report.severity}] ${report.exceptionType}: $safeMessage (id=${report.id})"
            Log.e(tag, line)
            logSink(line)
        } else {
            val line = "Crash ingested: [${report.severity}] ${report.exceptionType} (id=${report.id})"
            Log.e(tag, line)
            logSink(line)
        }
        if (report.severity == CrashSeverity.CRITICAL) {
            alertCritical(report)
        }
    }

    /**
     * Build and ingest a crash from a raw [Throwable]. Severity is determined
     * automatically by [triageSeverity]. Device metadata is read from [android.os.Build].
     */
    fun ingestUncaughtException(throwable: Throwable, appVersion: String) {
        val stackTrace = throwable.stackTraceToString()
        val exType = throwable.javaClass.simpleName
        val exMsg = throwable.message ?: "No message"
        val report = CrashReport(
            id = java.util.UUID.randomUUID().toString(),
            timestampMs = System.currentTimeMillis(),
            appVersion = appVersion,
            osVersion = android.os.Build.VERSION.RELEASE,
            deviceModel = android.os.Build.MODEL,
            stackTrace = stackTrace,
            exceptionType = exType,
            exceptionMessage = exMsg,
            severity = triageSeverity(exType, exMsg)
        )
        ingest(report)
    }

    /**
     * Determine [CrashSeverity] from exception type name and message text.
     *
     * Rules (evaluated top-to-bottom; first match wins):
     * 1. CRITICAL — OutOfMemoryError, StackOverflowError, or message contains "fatal"
     * 2. HIGH     — NullPointerException or IllegalStateException
     * 3. MEDIUM   — IOException or NetworkException
     * 4. LOW      — all other exceptions
     */
    fun triageSeverity(exceptionType: String, message: String): CrashSeverity {
        return when {
            exceptionType.contains("OutOfMemoryError", ignoreCase = true) ||
            exceptionType.contains("StackOverflowError", ignoreCase = true) ||
            message.contains("fatal", ignoreCase = true) -> CrashSeverity.CRITICAL

            exceptionType.contains("NullPointerException", ignoreCase = true) ||
            exceptionType.contains("IllegalStateException", ignoreCase = true) -> CrashSeverity.HIGH

            exceptionType.contains("IOException", ignoreCase = true) ||
            exceptionType.contains("NetworkException", ignoreCase = true) -> CrashSeverity.MEDIUM

            else -> CrashSeverity.LOW
        }
    }

    /** Return all stored reports, newest first. */
    fun allReports(): List<CrashReport> = loadReports()

    /**
     * Return reports whose severity is at or above [minimumSeverity].
     *
     * Severity order (ascending): LOW → MEDIUM → HIGH → CRITICAL.
     */
    fun reports(minimumSeverity: CrashSeverity): List<CrashReport> {
        val order = listOf(CrashSeverity.LOW, CrashSeverity.MEDIUM, CrashSeverity.HIGH, CrashSeverity.CRITICAL)
        val minIdx = order.indexOf(minimumSeverity)
        return loadReports().filter { order.indexOf(it.severity) >= minIdx }
    }

    /**
     * Mark a crash report as acknowledged by its [reportId].
     * No-ops silently if the ID is not found.
     */
    fun acknowledge(reportId: String) {
        val updated = loadReports().map { if (it.id == reportId) it.copy(isAcknowledged = true) else it }
        saveReports(updated)
    }

    /** Build a [CrashDashboardSummary] from all currently stored reports. */
    fun dashboardSummary(): CrashDashboardSummary {
        val reports = loadReports()
        val mostFreq = reports
            .groupBy { it.exceptionType }
            .maxByOrNull { it.value.size }
            ?.key
        return CrashDashboardSummary(
            totalCrashes = reports.size,
            criticalCount = reports.count { it.severity == CrashSeverity.CRITICAL },
            highCount = reports.count { it.severity == CrashSeverity.HIGH },
            mediumCount = reports.count { it.severity == CrashSeverity.MEDIUM },
            lowCount = reports.count { it.severity == CrashSeverity.LOW },
            unacknowledgedCount = reports.count { !it.isAcknowledged },
            mostFrequentException = mostFreq,
            lastCrashTimestampMs = reports.maxOfOrNull { it.timestampMs }
        )
    }

    /**
     * Clear all stored reports.
     *
     * Intended for use in tests and debug builds only — production code should
     * not call this.
     */
    fun reset() {
        prefs.edit().remove(reportsKey).apply()
    }

    // ---------- private helpers ----------

    /**
     * Emit a CRITICAL alert. Currently writes an assert-level log entry that is
     * visible in logcat and crash tools. In production this is the hook for
     * integrating PagerDuty / push alerting.
     */
    private fun alertCritical(report: CrashReport) {
        // #634: Same rule as ingest() — raw exceptionMessage is suppressed in release.
        if (isDebug) {
            val safeMessage = LogRedactor.redactString(report.exceptionMessage)
            val line = "CRITICAL CRASH ALERT: ${report.exceptionType} — $safeMessage (id=${report.id})"
            Log.wtf(tag, line)
            logSink(line)
        } else {
            val line = "CRITICAL CRASH ALERT: ${report.exceptionType} (id=${report.id})"
            Log.wtf(tag, line)
            logSink(line)
        }
        // TODO(#470): wire to PagerDuty / on-call push alert pathway.
    }

    private fun loadReports(): List<CrashReport> {
        val raw = prefs.getString(reportsKey, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<CrashReport>>(raw)
        } catch (e: Exception) {
            Log.w(tag, "Failed to decode stored crash reports, resetting store", e)
            emptyList()
        }
    }

    private fun saveReports(reports: List<CrashReport>) {
        prefs.edit().putString(reportsKey, json.encodeToString(reports)).apply()
    }
}
