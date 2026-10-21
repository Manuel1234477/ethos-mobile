package com.ethosprotocol.utils

import android.util.Log
import com.ethosprotocol.BuildConfig
import com.google.firebase.perf.FirebasePerformance
import com.google.firebase.perf.metrics.Trace
import java.util.concurrent.CopyOnWriteArrayList

// ── Data models ───────────────────────────────────────────────────────────────

data class ApiMetric(
    val path: String,
    val method: String,
    val durationMs: Double,
    val statusCode: Int,
    val timestamp: Long = System.currentTimeMillis()
)

data class ScreenMetric(
    val screenName: String,
    val loadTimeMs: Long,
    val timestamp: Long = System.currentTimeMillis()
)

data class PerformanceSummary(
    val apiP50Ms: Double,
    val apiP95Ms: Double,
    val apiP99Ms: Double,
    val slowAPICallCount: Int,
    val slowScreenCount: Int,
    val totalAPICalls: Int,
    val totalScreenLoads: Int
)

object PerformanceThresholds {
    const val API_SLOW_MS = 2000L
    const val SCREEN_SLOW_MS = 500L
    const val MAX_API_METRICS = 100
    const val MAX_SCREEN_METRICS = 50
}

// ── Singleton monitor ─────────────────────────────────────────────────────────

/**
 * Central APM utility for Android. Wraps Firebase Performance for manual traces
 * and maintains a bounded in-memory log of recent API and screen metrics so
 * callers can compute live percentiles or surface a debug summary overlay.
 *
 * Usage:
 *   // Screen tracing (via TrackScreen composable):
 *   val trace = PerformanceMonitor.startScreenTrace("VaultList")
 *   // ... screen renders ...
 *   PerformanceMonitor.stopScreenTrace(trace, "VaultList", durationMs)
 *
 *   // API tracing (called from ApiClient):
 *   PerformanceMonitor.recordApiCall("/vaults", "GET", 123.0, 200)
 */
object PerformanceMonitor {
    private const val TAG = "PerformanceMonitor"

    private val apiMetrics = CopyOnWriteArrayList<ApiMetric>()
    private val screenMetrics = CopyOnWriteArrayList<ScreenMetric>()

    // ── Screen tracing ────────────────────────────────────────────────────────

    /**
     * Starts a Firebase Performance custom trace named "screen_[screenName]" and
     * returns it so the caller can stop it later via [stopScreenTrace].
     */
    fun startScreenTrace(screenName: String): Trace {
        val trace = FirebasePerformance.getInstance().newTrace("screen_$screenName")
        trace.start()
        return trace
    }

    /**
     * Adds the load-time metric to [trace], stops it, records the result in the
     * in-memory [screenMetrics] ring buffer, and logs a warning for slow screens.
     */
    fun stopScreenTrace(trace: Trace, screenName: String, durationMs: Long) {
        trace.putMetric("screen_load_ms", durationMs)
        trace.stop()

        val metric = ScreenMetric(screenName = screenName, loadTimeMs = durationMs)
        screenMetrics.add(metric)
        // Trim to bounded size (drop oldest entries)
        while (screenMetrics.size > PerformanceThresholds.MAX_SCREEN_METRICS) {
            screenMetrics.removeAt(0)
        }

        if (durationMs > PerformanceThresholds.SCREEN_SLOW_MS) {
            Log.w(TAG, "Slow screen load: $screenName took ${durationMs}ms " +
                "(threshold=${PerformanceThresholds.SCREEN_SLOW_MS}ms)")
        } else {
            Log.d(TAG, "Screen load: $screenName = ${durationMs}ms")
        }
    }

    // ── API call tracing ──────────────────────────────────────────────────────

    /**
     * Records an API call using a Firebase Performance manual trace named "api_call"
     * with custom attributes for path/method and a custom metric for duration.
     * Also appends to the in-memory [apiMetrics] ring buffer for local percentile
     * computation.
     */
    fun recordApiCall(path: String, method: String, durationMs: Double, statusCode: Int) {
        // Firebase Performance trace
        val trace = FirebasePerformance.getInstance().newTrace("api_call")
        trace.start()
        trace.putAttribute("path", path.take(100)) // Firebase attribute max length = 100
        trace.putAttribute("method", method)
        trace.putMetric("duration_ms", durationMs.toLong())
        trace.stop()

        // In-memory record
        val metric = ApiMetric(
            path = path,
            method = method,
            durationMs = durationMs,
            statusCode = statusCode
        )
        apiMetrics.add(metric)
        // Trim to bounded size (drop oldest entries)
        while (apiMetrics.size > PerformanceThresholds.MAX_API_METRICS) {
            apiMetrics.removeAt(0)
        }

        if (durationMs > PerformanceThresholds.API_SLOW_MS) {
            val label = if (BuildConfig.DEBUG) "$method $path" else "$method ${toPathTemplate(path)}"
            Log.w(TAG, "Slow API call: $label took ${durationMs}ms " +
                "(threshold=${PerformanceThresholds.API_SLOW_MS}ms) status=$statusCode")
        } else if (BuildConfig.DEBUG) {
            Log.d(TAG, "API call: $method $path = ${durationMs}ms status=$statusCode")
        }
    }

    // ── Summary & percentiles ─────────────────────────────────────────────────

    /**
     * Returns a [PerformanceSummary] computed from the current in-memory ring
     * buffers. Returns zero values when no metrics have been collected yet.
     */
    fun summary(): PerformanceSummary {
        val durations = apiMetrics.map { it.durationMs }
        val slowApiCount = apiMetrics.count { it.durationMs > PerformanceThresholds.API_SLOW_MS }
        val slowScreenCount = screenMetrics.count { it.loadTimeMs > PerformanceThresholds.SCREEN_SLOW_MS }

        return PerformanceSummary(
            apiP50Ms = durations.percentile(50.0),
            apiP95Ms = durations.percentile(95.0),
            apiP99Ms = durations.percentile(99.0),
            slowAPICallCount = slowApiCount,
            slowScreenCount = slowScreenCount,
            totalAPICalls = apiMetrics.size,
            totalScreenLoads = screenMetrics.size
        )
    }

    /**
     * Clears all in-memory metrics. Intended for use in tests only.
     */
    fun reset() {
        apiMetrics.clear()
        screenMetrics.clear()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Replaces concrete path segments (UUIDs, opaque IDs) and query strings with
     * placeholder tokens so the result is safe to log in release builds.
     *
     * Rules applied in order:
     *  1. Strip everything from '?' onward (query params may carry cursors / PII).
     *  2. Replace any path segment that is ≥ 8 chars **and contains at least one digit**
     *     with `{id}`. Requiring a digit preserves known API sub-resource words
     *     (`challenge`, `sessions`, `initiate`, `beneficiary`, `trust-device`, …) which
     *     are all purely alphabetic, while still catching UUIDs, numeric IDs, and the
     *     mixed alphanumeric tokens the server issues.
     *
     * Examples:
     *   "/vaults/abc12345/checkin"                         → "/vaults/{id}/checkin"
     *   "/auth/sessions/550e8400-e29b-41d4-a716-…"        → "/auth/sessions/{id}"
     *   "/vaults/abc12345/2fa/backup-codes/generate"      → "/vaults/{id}/2fa/backup-codes/generate"
     *   "/vaults?limit=20&after=opaqueCursor9999"          → "/vaults"
     *   "/auth/challenge"                                  → "/auth/challenge"  (unchanged)
     */
    internal fun toPathTemplate(path: String): String {
        // 1. Drop query string
        val noQuery = path.substringBefore('?')
        // 2. Replace segments that look like opaque IDs (≥ 8 chars, contains a digit)
        return noQuery.replace(Regex("/([A-Za-z0-9_-]{8,})(?=/|$)")) { match ->
            val segment = match.groupValues[1]
            if (segment.any { it.isDigit() }) "/{id}" else match.value
        }
    }

    /**
     * Returns the [p]-th percentile value (0–100) of a sorted copy of this list.
     * Returns 0.0 for an empty list.
     */
    private fun List<Double>.percentile(p: Double): Double {
        if (isEmpty()) return 0.0
        val sorted = sorted()
        val index = ((p / 100.0) * (sorted.size - 1)).coerceIn(0.0, (sorted.size - 1).toDouble())
        val lower = sorted[index.toInt()]
        val upper = sorted[(index.toInt() + 1).coerceAtMost(sorted.size - 1)]
        val fraction = index - index.toInt()
        return lower + fraction * (upper - lower)
    }
}
