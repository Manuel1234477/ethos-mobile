package com.ethosprotocol.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.ethosprotocol.R
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.models.VaultStatus
import com.ethosprotocol.ui.MainActivity
import com.ethosprotocol.utils.DateTimeFormatter
import com.ethosprotocol.utils.WidgetErrorLogger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.math.absoluteValue

class VaultStatusWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        widgetIds.forEach { updateWidget(context, manager, it) }
    }

    /** Re-render when the user resizes the widget so the layout adapts to the new size (#247). */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, manager, widgetId, newOptions)
        updateWidget(context, manager, widgetId)
    }

    companion object {
        // Per-widget shared-prefs key pattern (#246). Each widget instance gets its own
        // preferences file so different widgets can show different vaults simultaneously.
        private fun prefsName(widgetId: Int) = "vault_widget_prefs_$widgetId"

        // Shared prefs key used to store the list of all known vault IDs, for use by
        // VaultWidgetConfigActivity's vault picker (#245).
        const val PREFS_SHARED = "vault_widget_shared"
        const val KEY_VAULT_ID_LIST = "vault_id_list"

        private const val KEY_VAULT_ID = "vault_id"
        private const val KEY_VAULT_NAME = "vault_name"
        private const val KEY_TTL = "ttl_remaining"
        private const val KEY_LAST_CHECK_IN = "last_check_in"
        const val KEY_BALANCE = "balance"
        const val KEY_BENEFICIARY = "beneficiary"
        // #435: persists whether the last refresh attempt failed
        private const val KEY_HAS_ERROR = "has_error"

        // Selected-vault key stored in per-widget prefs; written by VaultWidgetConfigActivity.
        private const val KEY_SELECTED_VAULT_ID = "selected_vault_id"

        // #431: Refresh interval (minutes) and colour scheme preference keys.
        const val KEY_REFRESH_INTERVAL_MINUTES = "refresh_interval_minutes"
        const val KEY_COLOR_SCHEME = "color_scheme"
        const val DEFAULT_REFRESH_INTERVAL = 15

        // #433: Additional vault rows (encoded as "id1|name1|ttl1,id2|name2|ttl2").
        private const val KEY_ADDITIONAL_VAULTS = "additional_vaults"

        // Colour scheme constants (#431).
        const val COLOR_SCHEME_AUTO   = "auto"
        const val COLOR_SCHEME_BLUE   = "blue"
        const val COLOR_SCHEME_GREEN  = "green"
        const val COLOR_SCHEME_ORANGE = "orange"

        /**
         * Saves vault display data to per-widget SharedPreferences (#246).
         * Each widget ID maps to its own prefs file so data is isolated per instance.
         */
        fun saveVaultData(
            context: Context,
            widgetId: Int,
            vaultId: String,
            vaultName: String,
            ttlRemaining: String,
            lastCheckIn: String,
            balance: String,
            beneficiary: String,
            hasError: Boolean = false  // #435
        ) {
            context.getSharedPreferences(prefsName(widgetId), Context.MODE_PRIVATE).edit()
                .putString(KEY_VAULT_ID, vaultId)
                .putString(KEY_VAULT_NAME, vaultName)
                .putString(KEY_TTL, ttlRemaining)
                .putString(KEY_LAST_CHECK_IN, lastCheckIn)
                .putString(KEY_BALANCE, balance)
                .putString(KEY_BENEFICIARY, beneficiary)
                .putBoolean(KEY_HAS_ERROR, hasError)  // #435
                .apply()
        }

        /**
         * Returns the vault ID pinned by the user for this widget instance via
         * VaultWidgetConfigActivity, or null if the user has not made a selection (#245 / #246).
         */
        fun getSelectedVaultId(context: Context, widgetId: Int): String? =
            context.getSharedPreferences(prefsName(widgetId), Context.MODE_PRIVATE)
                .getString(KEY_SELECTED_VAULT_ID, null)
                .takeIf { !it.isNullOrEmpty() }

        /**
         * Persists the user's vault selection for a specific widget instance (#245 / #246).
         * Called by VaultWidgetConfigActivity when the user picks a vault.
         */
        fun saveSelectedVaultId(context: Context, widgetId: Int, vaultId: String) {
            context.getSharedPreferences(prefsName(widgetId), Context.MODE_PRIVATE).edit()
                .putString(KEY_SELECTED_VAULT_ID, vaultId)
                .apply()
        }

        /** Persists the user-configured refresh interval (minutes) for this widget (#431). */
        fun saveRefreshIntervalMinutes(context: Context, widgetId: Int, minutes: Int) {
            context.getSharedPreferences(prefsName(widgetId), Context.MODE_PRIVATE).edit()
                .putInt(KEY_REFRESH_INTERVAL_MINUTES, minutes)
                .apply()
        }

        /** Returns the persisted refresh interval in minutes, defaulting to 15 (#431). */
        fun getRefreshIntervalMinutes(context: Context, widgetId: Int): Int =
            context.getSharedPreferences(prefsName(widgetId), Context.MODE_PRIVATE)
                .getInt(KEY_REFRESH_INTERVAL_MINUTES, DEFAULT_REFRESH_INTERVAL)

        /** Persists the user-configured colour scheme for this widget (#431). */
        fun saveColorScheme(context: Context, widgetId: Int, scheme: String) {
            context.getSharedPreferences(prefsName(widgetId), Context.MODE_PRIVATE).edit()
                .putString(KEY_COLOR_SCHEME, scheme)
                .apply()
        }

        /** Returns the persisted colour scheme string, defaulting to "auto" (#431). */
        fun getColorScheme(context: Context, widgetId: Int): String =
            context.getSharedPreferences(prefsName(widgetId), Context.MODE_PRIVATE)
                .getString(KEY_COLOR_SCHEME, COLOR_SCHEME_AUTO) ?: COLOR_SCHEME_AUTO

        /**
         * Persists up to 2 additional vault rows for the multi-vault large layout (#433).
         * Encoded as a comma-separated list of "id|name|ttl" triples.
         */
        fun saveAdditionalVaults(context: Context, widgetId: Int, vaults: List<Triple<String, String, String>>) {
            val encoded = vaults.take(2).joinToString(",") { (id, name, ttl) -> "$id|$name|$ttl" }
            context.getSharedPreferences(prefsName(widgetId), Context.MODE_PRIVATE).edit()
                .putString(KEY_ADDITIONAL_VAULTS, encoded)
                .apply()
        }

        /**
         * Returns up to 2 additional vault rows as (id, name, ttl) triples (#433).
         */
        fun getAdditionalVaults(context: Context, widgetId: Int): List<Triple<String, String, String>> {
            val raw = context.getSharedPreferences(prefsName(widgetId), Context.MODE_PRIVATE)
                .getString(KEY_ADDITIONAL_VAULTS, null) ?: return emptyList()
            return raw.split(",")
                .mapNotNull { entry ->
                    val parts = entry.split("|")
                    if (parts.size == 3) Triple(parts[0], parts[1], parts[2]) else null
                }
        }

        /**
         * Chooses the correct layout resource based on the widget's current width (#247).
         * Reads OPTION_APPWIDGET_MIN_WIDTH from the options bundle:
         *   width < 180dp  → small  (TTL only)
         *   180 ≤ width < 250dp → medium (TTL + balance)
         *   width ≥ 250dp  → large  (TTL + balance + beneficiary + optional multi-vault rows)
         *
         * #433: When the large size is selected AND there are additional vaults persisted for
         * this widget instance, we use the multi-vault layout instead.
         */
        fun selectLayout(options: Bundle, hasAdditionalVaults: Boolean = false): Int {
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            return when {
                minWidth >= 250 -> if (hasAdditionalVaults) R.layout.vault_widget_large_multi
                                   else R.layout.vault_widget_large
                minWidth >= 180 -> R.layout.vault_widget_medium
                else -> R.layout.vault_widget_small
            }
        }

        /** Builds the deep link used to open a widget tap directly onto the vault it displayed. */
        internal fun deepLinkUri(vaultId: String): String = "ethosprotocol://vault/$vaultId/view-details"

        fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val prefs = context.getSharedPreferences(prefsName(widgetId), Context.MODE_PRIVATE)
            val vaultId = prefs.getString(KEY_VAULT_ID, null)
            val vaultName = prefs.getString(KEY_VAULT_NAME, "—") ?: "—"
            val ttl = prefs.getString(KEY_TTL, context.getString(R.string.widget_ttl_unknown)) ?: context.getString(R.string.widget_ttl_unknown)
            val lastCheckIn = prefs.getString(KEY_LAST_CHECK_IN, context.getString(R.string.widget_last_checkin_never)) ?: context.getString(R.string.widget_last_checkin_never)
            val balance = prefs.getString(KEY_BALANCE, "—") ?: "—"
            val beneficiary = prefs.getString(KEY_BENEFICIARY, "—") ?: "—"
            val hasError = prefs.getBoolean(KEY_HAS_ERROR, false)  // #435

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (!vaultId.isNullOrEmpty()) {
                    data = Uri.parse(deepLinkUri(vaultId))
                }
            }

            val pendingIntent = PendingIntent.getActivity(
                context, widgetId, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // #433: Determine if we have extra vault rows to decide which large layout to use.
            val additionalVaults = getAdditionalVaults(context, widgetId)

            // Pick layout based on current widget size options (#247 / #433).
            val options = manager.getAppWidgetOptions(widgetId)
            val layoutId = selectLayout(options, hasAdditionalVaults = additionalVaults.isNotEmpty())

            val isDarkMode = context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

            val views = RemoteViews(context.packageName, layoutId).apply {
                setTextViewText(R.id.widget_vault_name, vaultName)
                setTextViewText(R.id.widget_ttl, "TTL: $ttl")
                setTextViewText(R.id.widget_balance, balance)
                setTextViewText(R.id.widget_beneficiary, beneficiary)
                setOnClickPendingIntent(R.id.widget_root, pendingIntent)

                // #435: Show/hide the error indicator based on last sync result.
                // widget_error_indicator is declared GONE by default in vault_widget.xml.
                setViewVisibility(
                    R.id.widget_error_indicator,
                    if (hasError) android.view.View.VISIBLE else android.view.View.GONE
                )

                // #439: If vault_widget.xml does NOT yet have a res/layout-night/ variant,
                // apply programmatic dark/light overrides here.  These mirror the colours in
                // WidgetScreenshotTest (VaultWidgetPreview):
                //   Dark  background : 0xFF1C1C1E  (matches existing XML dark bg)
                //   Light background : 0xFFFFFFFF  (standard light surface)
                //   Dark  text       : 0xFFEBEBF5  (iOS-aligned off-white)
                //   Light text       : 0xFF1C1C1E  (dark label)
                //
                // Remove these overrides once res/layout-night/vault_widget.xml is added and
                // the XML handles colour selection automatically via night-mode qualifiers.
                if (isDarkMode) {
                    setInt(R.id.widget_root, "setBackgroundColor", 0xFF1C1C1E.toInt())
                } else {
                    setInt(R.id.widget_root, "setBackgroundColor", android.graphics.Color.WHITE)
                }

                // #433: Populate additional vault rows in the multi-vault large layout.
                // setViewVisibility on IDs that don't exist in the current layout is a no-op
                // for RemoteViews, so these calls are safe across all layout variants.
                if (additionalVaults.isNotEmpty()) {
                    val (id2, name2, ttl2) = additionalVaults[0]
                    setViewVisibility(R.id.widget_vault2_row, android.view.View.VISIBLE)
                    setTextViewText(R.id.widget_vault2_name, name2)
                    setTextViewText(R.id.widget_vault2_ttl, ttl2)
                }
                if (additionalVaults.size >= 2) {
                    val (id3, name3, ttl3) = additionalVaults[1]
                    setViewVisibility(R.id.widget_vault3_row, android.view.View.VISIBLE)
                    setTextViewText(R.id.widget_vault3_name, name3)
                    setTextViewText(R.id.widget_vault3_ttl, ttl3)
                }
            }
            manager.updateAppWidget(widgetId, views)
        }

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, VaultStatusWidget::class.java))
            ids.forEach { updateWidget(context, manager, it) }
        }

        /** Formats an ISO-8601 timestamp as a relative time ("2 hours ago") for widget display. */
        internal fun formatLastCheckIn(isoTimestamp: String, context: Context, now: Instant = Instant.now()): String {
            val checkInInstant = runCatching { Instant.parse(isoTimestamp) }.getOrNull() ?: return isoTimestamp
            val seconds = Duration.between(checkInInstant, now).seconds.coerceAtLeast(0)
            return when {
                seconds < 60 -> context.getString(R.string.widget_last_checkin_just_now)
                seconds < 3_600 -> relative(context, seconds / 60, "minute")
                seconds < 86_400 -> relative(context, seconds / 3_600, "hour")
                else -> relative(context, seconds / 86_400, "day")
            }
        }

        private fun relative(context: Context, value: Long, unit: String): String {
            val plural = if (value == 1L) "" else "s"
            return when (unit) {
                "minute" -> context.getString(R.string.widget_last_checkin_minutes, value, plural)
                "hour" -> context.getString(R.string.widget_last_checkin_hours, value, plural)
                "day" -> context.getString(R.string.widget_last_checkin_days, value, plural)
                else -> "$value $unit$plural ago"
            }
        }
    }
}

@HiltWorker
class VaultWidgetUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val apiClient: ApiClient
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // #484: Power usage metric for this background wake-up, so battery drain from
        // widget refreshes is observable (e.g. via logcat/bug reports) instead of opaque.
        logBatterySnapshot()

        val result = apiClient.listVaults()
        if (result is ApiResult.Success) {
            val vaults = result.data.filter { it.status == VaultStatus.active }

            // #438: Log a data-refresh failure when the API succeeds but returns no
            // active vaults — the call worked but the widget has nothing useful to show.
            if (vaults.isEmpty()) {
                WidgetErrorLogger.logDataRefreshFailure(
                    "Data refresh returned no active vaults (total: ${result.data.size})"
                )
                return Result.success()
            }

            // Pick the active vault with the lowest ttlRemaining as the urgency fallback.
            // Individual widget instances may override this with a pinned vault ID (#245/#246).
            val urgentVault = vaults.minByOrNull { it.ttlRemaining ?: Long.MAX_VALUE }
                ?: return Result.success()

            // Save the full vault ID list so VaultWidgetConfigActivity can populate
            // the picker (#245).
            val vaultIdList = vaults.joinToString(",") { it.id }
            applicationContext.getSharedPreferences(
                VaultStatusWidget.PREFS_SHARED, Context.MODE_PRIVATE
            ).edit().putString(VaultStatusWidget.KEY_VAULT_ID_LIST, vaultIdList).apply()

            // Update each widget instance independently (#246).
            // If the user has pinned a specific vault, use that; otherwise fall back to urgentVault.
            val manager = AppWidgetManager.getInstance(applicationContext)
            val ids = manager.getAppWidgetIds(
                ComponentName(applicationContext, VaultStatusWidget::class.java)
            )
            ids.forEach { widgetId ->
                val pinnedId = VaultStatusWidget.getSelectedVaultId(applicationContext, widgetId)
                val vault = if (pinnedId != null) {
                    vaults.find { it.id == pinnedId } ?: urgentVault
                } else {
                    urgentVault
                }

                // #433: Save up to 2 additional vault rows (all active vaults except primary).
                val additionalVaultData = vaults
                    .filter { it.id != vault.id }
                    .sortedBy { it.ttlRemaining ?: Long.MAX_VALUE }
                    .take(2)
                    .map { v ->
                        Triple(
                            v.id,
                            v.id.take(12) + "…",
                            formatTtl(applicationContext, v.ttlRemaining)
                        )
                    }
                VaultStatusWidget.saveAdditionalVaults(applicationContext, widgetId, additionalVaultData)

                VaultStatusWidget.saveVaultData(
                    applicationContext,
                    widgetId = widgetId,
                    vaultId = vault.id,
                    vaultName = vault.id.take(12) + "…",
                    ttlRemaining = formatTtl(applicationContext, vault.ttlRemaining),
                    lastCheckIn = VaultStatusWidget.formatLastCheckIn(vault.lastCheckIn, applicationContext),
                    balance = vault.formattedBalance,
                    beneficiary = vault.beneficiary.take(12) + "…",
                    hasError = false  // #435: clear error state on successful refresh
                )
                VaultStatusWidget.updateWidget(applicationContext, manager, widgetId)
            }

            schedule(applicationContext, determineUpdateInterval(urgentVault.ttlRemaining))
        } else {
            // #438: Log a load failure for any non-Success result (network unavailable
            // or API error) so it surfaces in Logcat and Firebase Performance.
            val reason = when (result) {
                is ApiResult.NetworkUnavailable -> "Network unavailable"
                is ApiResult.Error -> "API error ${result.code}: ${result.message}"
                else -> result::class.simpleName ?: "Unknown error"
            }
            WidgetErrorLogger.logLoadFailure(reason)

            // #435: Mark all widget instances as having an error so the error indicator
            // is displayed until the next successful refresh.
            val manager = AppWidgetManager.getInstance(applicationContext)
            val ids = manager.getAppWidgetIds(
                ComponentName(applicationContext, VaultStatusWidget::class.java)
            )
            ids.forEach { widgetId ->
                val prefs = applicationContext.getSharedPreferences(
                    "vault_widget_prefs_$widgetId", android.content.Context.MODE_PRIVATE
                )
                prefs.edit().putBoolean("has_error", true).apply()
                VaultStatusWidget.updateWidget(applicationContext, manager, widgetId)
            }
        }
        return Result.success()
    }

    private fun formatTtl(context: Context, seconds: Long?): String {
        if (seconds == null) return context.getString(R.string.widget_ttl_unknown)
        return DateTimeFormatter.formatDurationInSeconds(seconds)
    }

    /** #484: Logs battery level/charging state at the start of a background refresh. */
    private fun logBatterySnapshot() {
        runCatching {
            val batteryManager = applicationContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val isCharging = batteryManager.isCharging
            Log.d(TAG, "widget refresh starting battery=$level% charging=$isCharging")
        }
    }

    companion object {
        const val WORK_NAME = "vault_widget_update"
        private const val TAG = "VaultWidgetUpdateWorker"

        // WorkManager enforces a 15-minute floor on periodic work, so that's the shortest
        // interval available for a vault close to expiring. Once it's not urgent, back off
        // to a much longer interval to conserve battery (coordinated with iOS's #33 gap).
        private const val URGENT_INTERVAL_MINUTES = 15L
        private const val NORMAL_INTERVAL_MINUTES = 60L
        private const val URGENCY_THRESHOLD_SECONDS = 86_400L // 24h, matches Vault.isExpiringSoon

        // #484: Flex window applied to the non-urgent schedule only (15 min is already
        // WorkManager's periodic-interval floor, leaving no room for a flex window there).
        // This lets the OS fire the refresh any time in the last 15 minutes of the 60-minute
        // window, batching it with other scheduled wake-ups instead of forcing an exact tick.
        private const val NORMAL_FLEX_MINUTES = 15L

        /** Picks the widget refresh interval based on how close the most urgent vault is to expiring. */
        internal fun determineUpdateInterval(ttlRemainingSeconds: Long?): Long =
            if ((ttlRemainingSeconds ?: Long.MAX_VALUE) < URGENCY_THRESHOLD_SECONDS) {
                URGENT_INTERVAL_MINUTES
            } else {
                NORMAL_INTERVAL_MINUTES
            }

        fun schedule(context: Context, intervalMinutes: Long = NORMAL_INTERVAL_MINUTES) {
            val isUrgent = intervalMinutes <= URGENT_INTERVAL_MINUTES

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .apply {
                    // #484: A non-urgent refresh can wait for a healthier battery state;
                    // an urgent (vault-expiring-soon) refresh still runs regardless.
                    if (!isUrgent) setRequiresBatteryNotLow(true)
                }
                .build()

            // #484: Only the non-urgent schedule gets a flex window — at the 15-minute
            // floor there's no room for one (flex requires interval - flex >= 15 min).
            val request = if (isUrgent) {
                PeriodicWorkRequestBuilder<VaultWidgetUpdateWorker>(intervalMinutes, TimeUnit.MINUTES)
            } else {
                PeriodicWorkRequestBuilder<VaultWidgetUpdateWorker>(
                    intervalMinutes, TimeUnit.MINUTES,
                    NORMAL_FLEX_MINUTES, TimeUnit.MINUTES
                )
            }.setConstraints(constraints).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
