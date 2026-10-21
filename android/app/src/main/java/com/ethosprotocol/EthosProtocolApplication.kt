package com.ethosprotocol

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Handler
import android.os.Looper
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.ethosprotocol.analytics.SessionAnalytics
import com.ethosprotocol.crash.CrashReporter
import com.ethosprotocol.pendingactions.PendingActionsDatabaseMigrator
import com.ethosprotocol.utils.AppVersionUpdateChecker
import com.ethosprotocol.utils.StartupPerformance
import com.ethosprotocol.widget.VaultWidgetUpdateWorker
import dagger.hilt.android.HiltAndroidApp
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.protocol.Request
import javax.inject.Inject

// Multidex (#430): minSdk = 28 (API 28+) means the Android platform handles multiple dex
// files natively. No MultiDexApplication subclass or MultiDex.install() call is needed —
// the legacy androidx.multidex support library is only required for minSdk < 21. Enabling
// multiDexEnabled = true in build.gradle.kts is sufficient; the runtime loader takes care
// of the rest automatically before Application.onCreate() runs.
@HiltAndroidApp
class EthosProtocolApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var appVersionUpdateChecker: AppVersionUpdateChecker

    @Inject lateinit var crashReporter: CrashReporter

    @Inject lateinit var sessionAnalytics: SessionAnalytics

    @Inject lateinit var pendingActionsDatabaseMigrator: PendingActionsDatabaseMigrator

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        StartupPerformance.markAppStart()

        // Migrate the legacy plaintext pending_actions.db to a SQLCipher-encrypted
        // database (#612) before any component (WorkManager, repositories) opens it.
        // The migration is a no-op once the encrypted database exists, and preserves
        // any queued pending actions from the plaintext store.
        pendingActionsDatabaseMigrator.migrateIfNeeded()

        // Configure Sentry event scrubbing and PII controls (#640) before crash
        // reporting is initialized so the hooks apply to every captured event.
        configureSentryScrubbing()

        // Initialize crash reporting as early as possible so that any crash
        // during startup is captured with stack traces, device context and
        // release/version tracking (#425).
        crashReporter.initialize(this)

        // Begin privacy-respecting session analytics tracking (#426). The
        // session start timestamp is recorded here and the session is closed
        // (with a flush of any batched events) when the app is terminated.
        sessionAnalytics.startSession()

        // Defer non-critical initialization (widget updates) to after first frame
        // to reduce cold-start time (#317). Schedule after ~2 seconds to ensure
        // the app is fully rendered and responsive.
        Handler(Looper.getMainLooper()).postDelayed({
            VaultWidgetUpdateWorker.schedule(this)
        }, 2000)

        // Check for new app versions on startup (#424). Deferred so the version
        // check never blocks cold start; the checker compares the installed
        // version against the latest store version and surfaces an update prompt
        // (with a store link, and a forced update for critical releases).
        Handler(Looper.getMainLooper()).postDelayed({
            appVersionUpdateChecker.checkForUpdates()
        }, 3000)
    }

    /**
     * Applies Sentry scrubbing and PII controls (#640).
     *
     * - `sendDefaultPii = false` is set explicitly so Sentry never attaches
     *   default personally identifiable information (IP address, user data).
     * - `beforeSend` strips query strings from request URLs, removes
     *   `Authorization` headers, and drops request bodies before an event is
     *   sent. Breadcrumbs can carry request URLs with query strings (see #89
     *   and #84), so the same scrubbing is applied to breadcrumb data.
     */
    private fun configureSentryScrubbing() {
        SentryOptions().apply {
            isSendDefaultPii = false
            beforeSend = SentryOptions.BeforeSendCallback { event, _ ->
                scrubEvent(event)
                event
            }
            beforeBreadcrumb = SentryOptions.BeforeBreadcrumbCallback { breadcrumb, _ ->
                scrubBreadcrumb(breadcrumb)
                breadcrumb
            }
        }
    }

    private fun scrubEvent(event: SentryEvent) {
        event.request?.let { request ->
            request.url = stripQueryString(request.url)
            request.queryString = null
            request.data = null
            request.cookies = null
            request.headers?.let { headers ->
                headers.keys
                    .filter { it.equals("Authorization", ignoreCase = true) }
                    .forEach { headers.remove(it) }
            }
        }
    }

    private fun scrubBreadcrumb(breadcrumb: io.sentry.Breadcrumb) {
        val data = breadcrumb.data ?: return
        data.keys.toList().forEach { key ->
            when {
                key.equals("url", ignoreCase = true) || key.equals("http.query", ignoreCase = true) ->
                    data[key] = stripQueryString(data[key] as? String)
                key.equals("Authorization", ignoreCase = true) ->
                    data.remove(key)
            }
        }
    }

    private fun stripQueryString(url: String?): String? {
        if (url == null) return null
        val queryIndex = url.indexOf('?')
        return if (queryIndex >= 0) url.substring(0, queryIndex) else url
    }

    override fun onTerminate() {
        // Close the current analytics session and flush any pending batched
        // events before the process is torn down (#426).
        sessionAnalytics.endSession()
        super.onTerminate()
    }

    // #486: React to system memory-pressure signals by dropping caches that are cheap
    // to rebuild (icon/color lookups) rather than letting them hold memory the OS is
    // explicitly asking back for the app not to be killed outright.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            com.ethosprotocol.utils.AssetCache.clearCache()
        }
    }
}
