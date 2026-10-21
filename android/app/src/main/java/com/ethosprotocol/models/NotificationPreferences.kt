package com.ethosprotocol.models

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import com.ethosprotocol.security.LogRedactor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Calendar

/**
 * Per-category notification preferences and optional quiet hours.
 * Persisted to SharedPreferences and synced server-side on change so
 * preferences survive reinstall.
 */
@Keep
@Serializable
data class NotificationPreferences(
    /** Whether TTL-expiry warning notifications are enabled. */
    val ttlWarningsEnabled: Boolean = true,
    /** Whether check-in reminder notifications are enabled. */
    val checkInRemindersEnabled: Boolean = true,
    /** Whether quiet hours are active. */
    val quietHoursEnabled: Boolean = false,
    /** Start of quiet hours (hour 0-23, local time). */
    val quietHoursStart: Int = 22,
    /** End of quiet hours (hour 0-23, local time). */
    val quietHoursEnd: Int = 8,
    /** Whether in-app notification badges are shown. */
    val inAppBadgesEnabled: Boolean = true,
    /** Whether the in-app notification banner is shown. */
    val inAppBannerEnabled: Boolean = true,
    /** Where the in-app notification banner is anchored on screen. */
    val inAppBannerPosition: InAppBannerPosition = InAppBannerPosition.TOP
) {
    /**
     * Returns true if a notification should be suppressed right now based on quiet hours.
     * Mirrors iOS NotificationPreferences.isSuppressedByQuietHours.
     */
    fun isSuppressedByQuietHours(hourOfDay: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)): Boolean {
        if (!quietHoursEnabled) return false
        return if (quietHoursStart <= quietHoursEnd) {
            hourOfDay >= quietHoursStart && hourOfDay < quietHoursEnd
        } else {
            // Wraps midnight, e.g. 22:00–08:00
            hourOfDay >= quietHoursStart || hourOfDay < quietHoursEnd
        }
    }

    companion object {
        private const val TAG = "NotificationPreferences"
        private const val PREFS_NAME = "notification_preferences"
        private const val KEY = "prefs_json"
        private const val KEY_LAST_VALID = "prefs_json_last_valid"
        private const val KEY_RESET_NOTICE = "prefs_reset_notice"
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Loads the stored preferences. If the stored value fails to decode, the last value that
         * decoded successfully is returned instead of the defaults, so opted-out categories stay
         * opted out. A reset notice is recorded for [consumeResetNotice] in that case.
         */
        fun load(context: Context): NotificationPreferences {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val stored = prefs.getString(KEY, null) ?: return NotificationPreferences()
            decodeOrNull(stored)?.let { return it }

            Log.w(TAG, LogRedactor.redactString("Stored notification preferences failed to decode; using last valid value"))
            prefs.edit().putBoolean(KEY_RESET_NOTICE, true).apply()
            return prefs.getString(KEY_LAST_VALID, null)?.let { decodeOrNull(it) } ?: NotificationPreferences()
        }

        fun save(context: Context, preferences: NotificationPreferences) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val encoded = json.encodeToString(preferences)
            prefs.edit()
                .putString(KEY, encoded)
                .putString(KEY_LAST_VALID, encoded)
                .apply()
        }

        /** Returns true once if the stored preferences failed to decode since the last call, then clears the flag. */
        fun consumeResetNotice(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val pending = prefs.getBoolean(KEY_RESET_NOTICE, false)
            if (pending) prefs.edit().putBoolean(KEY_RESET_NOTICE, false).apply()
            return pending
        }

        private fun decodeOrNull(raw: String): NotificationPreferences? =
            try { json.decodeFromString<NotificationPreferences>(raw) } catch (_: Exception) { null }
    }
}

/**
 * Anchor position for the in-app notification banner.
 * Customizable so the banner can be placed where it does not obscure
 * primary content on different screens.
 */
@Serializable
enum class InAppBannerPosition {
    TOP,
    BOTTOM
}
