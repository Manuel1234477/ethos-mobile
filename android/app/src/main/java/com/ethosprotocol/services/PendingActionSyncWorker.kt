package com.ethosprotocol.services

// ADR-0001: Offline-first architecture — see docs/adr/adr-0001-offline-first.md

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.ethosprotocol.BuildConfig
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.api.NetworkMonitor
import com.ethosprotocol.models.CreateVaultRequest
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@HiltWorker
class PendingActionSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val apiClient: ApiClient,
    private val dao: PendingActionDao,
    private val notificationHelper: NotificationHelper,
    private val networkMonitor: NetworkMonitor
) : CoroutineWorker(context, params) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun doWork(): Result {
        // WorkManager's NetworkType.CONNECTED constraint only guarantees an active network,
        // not one with real internet reachability (e.g. a captive-portal Wi-Fi network still
        // satisfies it). Re-check with NetworkMonitor, which validates NET_CAPABILITY_INTERNET,
        // before burning a retry cycle on requests that are certain to fail.
        if (!networkMonitor.isConnected) return Result.retry()

        val startMs = System.currentTimeMillis()
        val runAtIso = isoTimestamp(startMs)

        // #483: query execution time logging.
        val getAllStartMs = System.currentTimeMillis()
        val rawPending = dao.getAll()
        Log.d(TAG, "dao.getAll() took ${System.currentTimeMillis() - getAllStartMs}ms, rows=${rawPending.size}")
        val pending = compactConflicts(rawPending).filterNot { it.deadLetter }
        if (pending.isEmpty()) {
            recordOutcome(runAtIso, succeeded = 0, failed = 0, retrying = false, queued = 0, lastModifiedAt = null)
            Log.i(TAG, "run finished — nothing to sync")
            return Result.success()
        }

        var hasRetryableFailure = false
        var succeeded = 0
        var permanentlyFailed = 0

        val toSync = mutableListOf<PendingAction>()
        for (item in pending) {
            // Vault IDs may only appear in logs in debug builds (see contract); in release
            // builds the segment is omitted entirely.
            val vaultSegment = if (BuildConfig.DEBUG) " vaultId=${item.vaultId}" else ""

            if (item.synced) {
                // The server already confirmed this action on a previous run, but the
                // process died before the row could be deleted. Finish the cleanup without
                // resubmitting the request or counting it toward this run's `succeeded`
                // (it was already counted when it first succeeded).
                dao.delete(item)
                Log.i(TAG, "already-synced action cleaned up type=${item.type}$vaultSegment")
                continue
            }

            // Validate the row before dispatch. A malformed row (null field from a migration
            // or a partial write) must not throw inside the worker, or it would be retried
            // forever and block the rest of the queue. Move it to the dead-letter state and
            // carry on with the next item.
            val invalidReason = validate(item)
            if (invalidReason != null) {
                dao.markDeadLetter(item.id)
                permanentlyFailed++
                Log.e(TAG, "action dead-lettered (invalid row) type=${item.type} id=${item.id} reason=$invalidReason")
                continue
            }

            toSync += item
        }

        // #487: Dispatch all of this run's pending actions concurrently instead of one
        // network round-trip at a time. compactConflicts() already resolved same-key
        // (same vault+type) conflicts above, so the remaining actions have no ordering
        // dependency on each other — only the per-item DAO/notification side effects
        // below need to stay sequential, since they share hasRetryableFailure/counters.
        val outcomes: List<Pair<PendingAction, ApiResult<*>>> = coroutineScope {
            toSync.map { item ->
                async {
                    item to when (item.type) {
                        PendingActionType.CHECK_IN -> apiClient.checkIn(item.vaultId!!, item.idempotencyKey)
                        PendingActionType.CREATE_VAULT ->
                            apiClient.createVault(
                                Json.decodeFromString<CreateVaultRequest>(item.payloadJson!!),
                                item.idempotencyKey
                            )
                    }
                }
            }.awaitAll()
        }

        for ((item, result) in outcomes) {
            val vaultSegment = if (BuildConfig.DEBUG) " vaultId=${item.vaultId}" else ""
            when (result) {
                is ApiResult.Success -> {
                    dao.markSynced(item.id)
                    dao.delete(item)
                    succeeded++
                    Log.i(TAG, "action synced type=${item.type}$vaultSegment")
                }
                ApiResult.NetworkUnavailable -> {
                    hasRetryableFailure = true
                    Log.w(TAG, "action deferred (no network) type=${item.type}$vaultSegment")
                }
                is ApiResult.Error -> {
                    // A queued action represents user intent (a check-in, a vault the user
                    // filled out a form for): silently dropping it on a transient failure
                    // (server error, timeout, expired auth) would lose that intent. Only drop
                    // the item when the server has definitively rejected it as invalid (e.g.
                    // the vault no longer exists) — everything else is retried.
                    if (result.code == VAULT_EXPIRED_CODE) {
                        dao.delete(item)
                        permanentlyFailed++
                        notificationHelper.showVaultExpiredNotification(item.vaultId ?: "", item.type)
                        Log.e(TAG, "action dropped (vault expired) type=${item.type}$vaultSegment")
                    } else if (result.code in NON_RETRYABLE_ERROR_CODES) {
                        dao.delete(item)
                        permanentlyFailed++
                        Log.e(TAG, "action dropped (non-retryable) type=${item.type}$vaultSegment code=${result.code} msg=${result.message}")
                    } else {
                        hasRetryableFailure = true
                        Log.w(TAG, "action deferred (retryable error) type=${item.type}$vaultSegment code=${result.code} msg=${result.message}")
                    }
                }
            }
        }

        // #483: a single getAll() replaces what were two separate full-table queries
        // back to back (one just to check emptiness, one for diagnostics).
        val remaining = dao.getAll().filterNot { it.deadLetter }
        if (remaining.isEmpty()) {
            notificationHelper.cancelQueuedActions()
        }

        val willRetry = hasRetryableFailure
        recordOutcome(
            runAtIso,
            succeeded,
            permanentlyFailed,
            willRetry,
            queued = remaining.size,
            lastModifiedAt = remaining.maxOfOrNull { it.queuedAt }?.let(::isoTimestamp)
        )

        val elapsedMs = System.currentTimeMillis() - startMs
        Log.i(TAG,
            "run finished elapsedMs=$elapsedMs succeeded=$succeeded permanentlyFailed=$permanentlyFailed willRetry=$willRetry"
        )

        return if (willRetry) Result.retry() else Result.success()
    }

    /**
     * Returns a non-sensitive reason string if [item] is malformed and cannot be dispatched,
     * or null if it is valid. Reasons never include payload contents.
     */
    private fun validate(item: PendingAction): String? = when (item.type) {
        PendingActionType.CHECK_IN ->
            if (item.vaultId.isNullOrBlank()) "missing vaultId" else null
        PendingActionType.CREATE_VAULT -> when {
            item.payloadJson.isNullOrBlank() -> "missing payloadJson"
            else -> try {
                Json.decodeFromString<CreateVaultRequest>(item.payloadJson)
                null
            } catch (e: Exception) {
                "unparseable payloadJson"
            }
        }
    }

    /**
     * Persists the last sync attempt timestamp and outcome to SharedPreferences so that
     * support/debug tooling can surface it without needing logcat access.
     */
    private suspend fun compactConflicts(items: List<PendingAction>): List<PendingAction> {
        val deduped = mutableListOf<PendingAction>()
        val grouped = items.groupBy { it.dedupeKey }
        for ((key, group) in grouped) {
            if (key == null || group.size == 1) {
                deduped += group
                continue
            }
            val winner = group.maxBy { it.queuedAt }
            group.filter { it.id != winner.id }.forEach { dao.delete(it) }
            deduped += winner
        }
        return deduped.sortedBy { it.queuedAt }
    }

    private fun recordOutcome(
        timestamp: String,
        succeeded: Int,
        failed: Int,
        retrying: Boolean,
        queued: Int,
        lastModifiedAt: String?
    ) {
        prefs.edit()
            .putString(PREF_LAST_SYNC_AT, timestamp)
            .putInt(PREF_LAST_SYNC_SUCCEEDED, succeeded)
            .putInt(PREF_LAST_SYNC_FAILED, failed)
            .putBoolean(PREF_LAST_SYNC_RETRYING, retrying)
            .putInt(PREF_LAST_SYNC_QUEUED, queued)
            .putString(PREF_LAST_SYNC_LAST_MODIFIED_AT, lastModifiedAt)
            .apply()
    }

    private fun isoTimestamp(epochMs: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date(epochMs))

    companion object {
        const val WORK_NAME = "pending_action_sync"
        const val PREFS_NAME = "pending_action_sync_diagnostics"
        const val PREF_LAST_SYNC_AT = "last_sync_at"
        const val PREF_LAST_SYNC_SUCCEEDED = "last_sync_succeeded"
        const val PREF_LAST_SYNC_FAILED = "last_sync_failed"
        const val PREF_LAST_SYNC_RETRYING = "last_sync_retrying"
        const val PREF_LAST_SYNC_QUEUED = "last_sync_queued"
        const val PREF_LAST_SYNC_LAST_MODIFIED_AT = "last_sync_last_modified_at"
        private const val TAG = "PendingActionSyncWorker"

        // Error codes where the server has told us unambiguously that this action can
        // never succeed (bad request / vault no longer exists), so retrying is pointless.
        // Everything else (5xx, 401, 0/exception) is treated as transient and retried.
        private val NON_RETRYABLE_ERROR_CODES = setOf(400, 404)
        private const val VAULT_EXPIRED_CODE = 410

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<PendingActionSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        /**
         * Returns a snapshot of the last sync run's diagnostics for display in support/debug UI.
         * Returns null if no sync has ever run on this device.
         */
        fun lastSyncDiagnostics(context: Context): SyncDiagnostics? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val timestamp = prefs.getString(PREF_LAST_SYNC_AT, null) ?: return null
            return SyncDiagnostics(
                lastSyncAt = timestamp,
                succeeded = prefs.getInt(PREF_LAST_SYNC_SUCCEEDED, 0),
                failed = prefs.getInt(PREF_LAST_SYNC_FAILED, 0),
                isRetrying = prefs.getBoolean(PREF_LAST_SYNC_RETRYING, false),
                queued = prefs.getInt(PREF_LAST_SYNC_QUEUED, 0),
                lastModifiedAt = prefs.getString(PREF_LAST_SYNC_LAST_MODIFIED_AT, null)
            )
        }
    }
}

/** Snapshot of the last PendingActionSyncWorker run, surfaced for support and debug use. */
data class SyncDiagnostics(
    val lastSyncAt: String,
    val succeeded: Int,
    val failed: Int,
    val isRetrying: Boolean,
    val queued: Int = 0,
    val lastModifiedAt: String? = null
)
