package com.ethosprotocol.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.ethosprotocol.R
import com.ethosprotocol.services.PendingActionType
import com.ethosprotocol.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dndHelper: DoNotDisturbHelper
) {
    private val notificationScope = CoroutineScope(Dispatchers.Default)

    companion object {
        const val CHANNEL_ID = "ttl_reminders"
        const val QUEUED_CHANNEL_ID = "ttl_queued"
        const val QUEUED_NOTIFICATION_ID = 9_001
        const val EXPIRED_CHANNEL_ID = "vault_expired"

        // Reserved range for per-vault notification IDs, kept clear of QUEUED_NOTIFICATION_ID
        // and NO_VAULT_NOTIFICATION_ID below.
        const val VAULT_NOTIFICATION_ID_RANGE_START = 10_000
        // Fallback ID for the (practically unused) case where show() is called without a
        // vaultId — sits below the reserved vault range so it can never collide with it.
        const val NO_VAULT_NOTIFICATION_ID = 1

        private const val VAULT_NOTIFICATION_IDS_PREFS = "vault_notification_ids"

        // In-app notification banner preferences.
        private const val IN_APP_PREFS = "in_app_notifications"
        private const val KEY_BANNER_POSITION = "banner_position"

        // Customizable banner positions for the in-app notification banner.
        const val POSITION_TOP = "top"
        const val POSITION_BOTTOM = "bottom"
    }

    // String.hashCode() collides between distinct vault IDs within the 32-bit hash space, which
    // would make one vault's notification silently replace another's. Instead, persist a stable
    // assignment of each vault ID to the next free slot in a reserved ID range — two distinct
    // vault IDs are then guaranteed distinct notification IDs for as long as the mapping lives,
    // rather than merely "unlikely" to collide.
    private val vaultNotificationIdPrefs =
        context.getSharedPreferences(VAULT_NOTIFICATION_IDS_PREFS, Context.MODE_PRIVATE)

    private val inAppPrefs =
        context.getSharedPreferences(IN_APP_PREFS, Context.MODE_PRIVATE)

    init {
        createChannel(CHANNEL_ID, context.getString(R.string.notification_channel_checkin_reminders), NotificationManager.IMPORTANCE_HIGH)
        createChannel(QUEUED_CHANNEL_ID, context.getString(R.string.notification_channel_queued_requests), NotificationManager.IMPORTANCE_DEFAULT)
        createChannel(EXPIRED_CHANNEL_ID, context.getString(R.string.notification_channel_vault_expiry), NotificationManager.IMPORTANCE_HIGH)
    }

    @Synchronized
    fun notificationIdFor(vaultId: String?): Int {
        if (vaultId == null) return NO_VAULT_NOTIFICATION_ID
        vaultNotificationIdPrefs.getInt(vaultId, -1).takeIf { it != -1 }?.let { return it }
        val id = VAULT_NOTIFICATION_ID_RANGE_START + vaultNotificationIdPrefs.all.size
        vaultNotificationIdPrefs.edit().putInt(vaultId, id).apply()
        return id
    }

    // FLAG_UPDATE_CURRENT reuses a PendingIntent whenever the request code matches, so a shared
    // code would make grouped notifications open whichever vault's extras were written last.
    // Keying the request code on the vault keeps each vault's tap target distinct.
    internal fun pendingIntentRequestCodeFor(vaultId: String?): Int = notificationIdFor(vaultId)

    /**
     * Badge count for pending check-ins. Used by the in-app notification badge so users
     * can see at a glance how many check-ins are still awaiting action.
     */
    fun pendingCheckInBadgeCount(pendingCheckIns: Int): Int = pendingCheckIns.coerceAtLeast(0)

    /**
     * Customizable position for the in-app notification banner. Defaults to the top of the
     * screen; callers may persist a different position via [setBannerPosition].
     */
    fun getBannerPosition(): String =
        inAppPrefs.getString(KEY_BANNER_POSITION, POSITION_TOP) ?: POSITION_TOP

    fun setBannerPosition(position: String) {
        val normalized = if (position == POSITION_BOTTOM) POSITION_BOTTOM else POSITION_TOP
        inAppPrefs.edit().putString(KEY_BANNER_POSITION, normalized).apply()
    }

    fun show(title: String, body: String, vaultId: String?, ttlRemaining: Long? = null, isCritical: Boolean = false) {
        val effectiveIsCritical = isCritical || (ttlRemaining?.let { dndHelper.isExpirationCritical(it) } ?: false)
        val delay = dndHelper.getDelayForNotification(effectiveIsCritical)

        notificationScope.launch {
            if (delay > 0) {
                delay(delay)
            }

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                vaultId?.let { data = android.net.Uri.parse("ethosprotocol://vault/$it/check-in") }
            }
            val pi = PendingIntent.getActivity(context, pendingIntentRequestCodeFor(vaultId), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val groupKey = vaultId ?: "general"
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                // Groups all of a vault's notifications together so they visually cluster even if
                // notificationIdFor() were ever wrong, rather than relying solely on ID uniqueness
                // for replace-vs-append behavior.
                .setGroup(groupKey)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                .build()

            val nm = context.getSystemService(NotificationManager::class.java)
            nm.notify(notificationIdFor(vaultId), notification)

            // Post a summary notification for the group if there are multiple notifications
            if (vaultId != null) {
                showGroupSummary(nm, groupKey)
            }
        }
    }

    private fun showGroupSummary(nm: NotificationManager, groupKey: String) {
        // Notification ID for summary: use a deterministic high number based on group key hash
        val summaryId = 50_000 + (groupKey.hashCode() and 0x7FFFFFFF) % 50_000

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(context, summaryId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val summary = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Vault Reminders")
            .setContentText("Multiple check-in reminders")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setGroup(groupKey)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        nm.notify(summaryId, summary)
    }

    fun showQueuedActions(count: Int) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(context, QUEUED_NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val body = if (count == 1) context.getString(R.string.notification_queued_single)
                   else context.getString(R.string.notification_queued_plural, count)

        val notification = NotificationCompat.Builder(context, QUEUED_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(context.getString(R.string.notification_queued_title))
            .setContentText(body)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(QUEUED_NOTIFICATION_ID, notification)
    }

    fun cancelQueuedActions() {
        context.getSystemService(NotificationManager::class.java).cancel(QUEUED_NOTIFICATION_ID)
    }

    fun showVaultExpiredNotification(vaultId: String, actionType: PendingActionType) {
        val body = if (actionType == PendingActionType.CHECK_IN)
            context.getString(R.string.notification_vault_expired_body_checkin)
        else
            context.getString(R.string.notification_vault_expired_body_request)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (vaultId.isNotEmpty())
                data = android.net.Uri.parse("ethosprotocol://vault/$vaultId/check-in")
        }
        val pi = PendingIntent.getActivity(context, notificationIdFor(vaultId), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(context, EXPIRED_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(context.getString(R.string.notification_vault_expired_title))
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(notificationIdFor(vaultId), notification)
    }

    private fun createChannel(id: String, name: String, importance: Int) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(id) == null) {
            nm.createNotificationChannel(NotificationChannel(id, name, importance))
        }
    }
}
