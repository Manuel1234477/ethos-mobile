package com.ethosprotocol.services

import androidx.annotation.Keep
import androidx.room.*
import kotlinx.coroutines.flow.Flow

enum class PendingActionType { CHECK_IN, CREATE_VAULT, DEPOSIT, WITHDRAW }

@Keep
@kotlinx.serialization.Serializable
data class DepositPayload(val vaultId: String, val amount: Long)

@Keep
@kotlinx.serialization.Serializable
data class WithdrawPayload(val vaultId: String, val amount: Long)

@Keep
@Entity(
    tableName = "pending_actions",
    // #483: queuedAt is indexed because getAll() orders by it — without this,
    // every call sorts the full table at query time instead of using the index.
    // synced is indexed because the sync worker's cleanup path filters on it.
    indices = [
        Index(value = ["dedupeKey"], unique = true),
        Index(value = ["queuedAt"]),
        Index(value = ["synced"]),
    ]
)
data class PendingAction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: PendingActionType,
    val vaultId: String? = null,
    val payloadJson: String? = null,
    val queuedAt: Long,
    // Re-queuing the same logical action (e.g. check-in for a given vault) should
    // replace the earlier queued item rather than pile up duplicates. SQLite treats
    // each NULL in a unique index as distinct, so action types with no natural key
    // (e.g. create-vault) can simply leave this null and queue freely.
    val dedupeKey: String? = null,
    // Stable identifier set once when the action is first attempted (not re-generated on
    // retry), sent as X-Idempotency-Key so a resubmission after the process dies between
    // the server accepting the request and this row being deleted is identifiable by the
    // server as a duplicate of a specific prior attempt, not a brand-new request.
    val idempotencyKey: String = "",
    // Set durably once the server has confirmed success, before the row is physically
    // deleted. If the process dies in that window, the next run sees synced == true and
    // knows this row already succeeded — it just finishes the delete without resubmitting
    // the request or double-counting it in sync diagnostics.
    val synced: Boolean = false,
    // Set when a row is found to be malformed (e.g. a required field is null after a
    // migration or partial write). Dead-lettered rows are skipped by the sync worker so a
    // single bad row cannot block the rest of the queue on every run.
    val deadLetter: Boolean = false
)

@Dao
interface PendingActionDao {
    @Query("SELECT * FROM pending_actions ORDER BY queuedAt ASC")
    suspend fun getAll(): List<PendingAction>

    @Query("SELECT COUNT(*) FROM pending_actions")
    fun observeCount(): Flow<Int>

    // #483: cheap existence/count check that doesn't pull every row's columns
    // across the binder just to test emptiness (unlike getAll().isEmpty()).
    @Query("SELECT COUNT(*) FROM pending_actions")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: PendingAction)

    @Delete
    suspend fun delete(item: PendingAction)

    @Query("UPDATE pending_actions SET synced = 1 WHERE id = :id")
    suspend fun markSynced(id: Long)

    @Query("UPDATE pending_actions SET deadLetter = 1 WHERE id = :id")
    suspend fun markDeadLetter(id: Long)

    @Query("DELETE FROM pending_actions")
    suspend fun deleteAll()
}
