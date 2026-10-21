package com.ethosprotocol

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ethosprotocol.services.PendingActionDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.api.NetworkMonitor
import com.ethosprotocol.models.CreateVaultRequest
import com.ethosprotocol.models.Vault
import com.ethosprotocol.models.VaultStatus
import com.ethosprotocol.services.NotificationHelper
import com.ethosprotocol.services.PendingAction
import com.ethosprotocol.services.PendingActionDao
import com.ethosprotocol.services.PendingActionSyncWorker
import com.ethosprotocol.services.PendingActionType
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PendingActionSyncWorkerTest {

    private val context: Context = mockk(relaxed = true)
    private val params: WorkerParameters = mockk(relaxed = true)
    private val apiClient: ApiClient = mockk()
    private val dao: PendingActionDao = mockk()
    private val notificationHelper: NotificationHelper = mockk(relaxed = true)
    private val networkMonitor: NetworkMonitor = mockk()
    private lateinit var worker: PendingActionSyncWorker

    @Before
    fun setup() {
        every { networkMonitor.isConnected } returns true
        worker = PendingActionSyncWorker(context, params, apiClient, dao, notificationHelper, networkMonitor)
    }

    @Test
    fun `empty queue succeeds without touching the api`() = runTest {
        coEvery { dao.getAll() } returns emptyList()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { apiClient.checkIn(any(), any()) }
        coVerify(exactly = 0) { apiClient.createVault(any(), any()) }
    }

    @Test
    fun `successful check-in is removed from the queue`() = runTest {
        val item = checkInAction("v1")
        coEvery { dao.getAll() } returnsMany listOf(listOf(item), emptyList())
        coEvery { apiClient.checkIn("v1", item.idempotencyKey) } returns ApiResult.Success(Unit)
        coEvery { dao.markSynced(item.id) } just Runs
        coEvery { dao.delete(item) } just Runs

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify { dao.markSynced(item.id) }
        coVerify { dao.delete(item) }
        verify { notificationHelper.cancelQueuedActions() }
    }

    @Test
    fun `network unavailable retries and keeps the item queued`() = runTest {
        val item = checkInAction("v1")
        coEvery { dao.getAll() } returns listOf(item)
        coEvery { apiClient.checkIn("v1", item.idempotencyKey) } returns ApiResult.NetworkUnavailable

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        coVerify(exactly = 0) { dao.delete(any()) }
    }

    @Test
    fun `non-retryable error drops the item and still succeeds`() = runTest {
        val item = checkInAction("v1")
        coEvery { dao.getAll() } returnsMany listOf(listOf(item), emptyList())
        coEvery { apiClient.checkIn("v1", item.idempotencyKey) } returns ApiResult.Error("Not found", 404)
        coEvery { dao.delete(item) } just Runs

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify { dao.delete(item) }
    }

    @Test
    fun `retryable server error keeps the item queued and retries`() = runTest {
        val item = checkInAction("v1")
        coEvery { dao.getAll() } returns listOf(item)
        coEvery { apiClient.checkIn("v1", item.idempotencyKey) } returns ApiResult.Error("Server error", 500)

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        coVerify(exactly = 0) { dao.delete(any()) }
    }

    @Test
    fun `resubmission after simulated crash sends the same idempotency key`() = runTest {
        val item = checkInAction("v1").copy(idempotencyKey = "stable-key")
        coEvery { dao.getAll() } returns listOf(item)
        coEvery { apiClient.checkIn("v1", "stable-key") } returns ApiResult.Success(Unit)
        // Simulates the process dying between the server accepting the request and
        // dao.markSynced's write committing.
        coEvery { dao.markSynced(item.id) } throws RuntimeException("process died")

        runCatching { worker.doWork() }

        // Fresh run: the row is still present (markSynced never committed), so it's
        // resubmitted — but it must carry the identical idempotency key, not a new one.
        coEvery { dao.markSynced(item.id) } just Runs
        coEvery { dao.delete(item) } just Runs
        worker.doWork()

        coVerify(exactly = 2) { apiClient.checkIn("v1", "stable-key") }
    }

    @Test
    fun `already-synced item is cleaned up without resubmitting or double counting success`() = runTest {
        // Simulates the process dying between dao.markSynced committing and dao.delete
        // running on a prior run: the row survives with synced == true.
        val item = checkInAction("v1").copy(synced = true)
        coEvery { dao.getAll() } returnsMany listOf(listOf(item), emptyList())
        coEvery { dao.delete(item) } just Runs

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify { dao.delete(item) }
        coVerify(exactly = 0) { apiClient.checkIn(any(), any()) }
        coVerify(exactly = 0) { dao.markSynced(any()) }
    }

    @Test
    fun `check-in row with null vaultId is dead-lettered without calling the api`() = runTest {
        val bad = PendingAction(
            id = 1,
            type = PendingActionType.CHECK_IN,
            vaultId = null,
            queuedAt = 1L
        )
        coEvery { dao.getAll() } returnsMany listOf(listOf(bad), emptyList())
        coEvery { dao.markDeadLetter(any(), any()) } just Runs
        coEvery { dao.delete(any()) } just Runs

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { apiClient.checkIn(any(), any()) }
        coVerify { dao.markDeadLetter(bad.id, any()) }
        coVerify(exactly = 0) { dao.markSynced(any()) }
    }

    @Test
    fun `create-vault row with null payloadJson is dead-lettered without calling the api`() = runTest {
        val bad = PendingAction(
            id = 2,
            type = PendingActionType.CREATE_VAULT,
            payloadJson = null,
            queuedAt = 2L
        )
        coEvery { dao.getAll() } returnsMany listOf(listOf(bad), emptyList())
        coEvery { dao.markDeadLetter(any(), any()) } just Runs
        coEvery { dao.delete(any()) } just Runs

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { apiClient.createVault(any(), any()) }
        coVerify { dao.markDeadLetter(bad.id, any()) }
        coVerify(exactly = 0) { dao.markSynced(any()) }
    }

    @Test
    fun `malformed row does not block a subsequent valid row in the same run`() = runTest {
        val bad = PendingAction(
            id = 1,
            type = PendingActionType.CHECK_IN,
            vaultId = null,
            queuedAt = 1L
        )
        val good = checkInAction("v1").copy(id = 2)
        coEvery { dao.getAll() } returnsMany listOf(listOf(bad, good), emptyList())
        coEvery { dao.markDeadLetter(any(), any()) } just Runs
        coEvery { apiClient.checkIn("v1", good.idempotencyKey) } returns ApiResult.Success(Unit)
        coEvery { dao.markSynced(good.id) } just Runs
        coEvery { dao.delete(any()) } just Runs

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify { dao.markDeadLetter(bad.id, any()) }
        coVerify { apiClient.checkIn("v1", good.idempotencyKey) }
        coVerify { dao.markSynced(good.id) }
        coVerify { dao.delete(good) }
    }

    @Test
    fun `mixed check-in and create-vault items are both dispatched and removed on success`() = runTest {
        val checkIn = checkInAction("v1")
        val vaultReq = CreateVaultRequest("GXYZ", 2_592_000L)
        val createVault = PendingAction(
            id = 2,
            type = PendingActionType.CREATE_VAULT,
            payloadJson = Json.encodeToString(kotlinx.serialization.serializer(), vaultReq),
            queuedAt = 2L
        )
        coEvery { dao.getAll() } returnsMany listOf(listOf(checkIn, createVault), emptyList())
        coEvery { apiClient.checkIn("v1", checkIn.idempotencyKey) } returns ApiResult.Success(Unit)
        coEvery { apiClient.createVault(vaultReq, createVault.idempotencyKey) } returns ApiResult.Success(makeVault())
        coEvery { dao.markSynced(any()) } just Runs
        coEvery { dao.delete(any()) } just Runs

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify { apiClient.createVault(vaultReq, createVault.idempotencyKey) }
        coVerify { dao.delete(checkIn) }
        coVerify { dao.delete(createVault) }
    }

    private fun checkInAction(vaultId: String) = PendingAction(
        id = 1,
        type = PendingActionType.CHECK_IN,
        vaultId = vaultId,
        queuedAt = 1L,
        dedupeKey = "check_in:$vaultId"
    )

    private fun makeVault() = Vault(
        id = "v2", owner = "GABC", beneficiary = "GXYZ",
        balance = 0L, checkInInterval = 2_592_000L,
        lastCheckIn = "2026-04-01T00:00:00Z", ttlRemaining = 2_592_000L,
        status = VaultStatus.active
    )
}

@RunWith(AndroidJUnit4::class)
class PendingActionDatabaseMigrationTest {

    private val testDbName = "pending-action-migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PendingActionDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate1To2_preservesQueuedActionsAndPopulatesNewColumns() {
        helper.createDatabase(testDbName, 1).apply {
            execSQL(
                "INSERT INTO pending_actions (id, type, vaultId, payloadJson, queuedAt, dedupeKey) " +
                    "VALUES (1, 'CHECK_IN', 'v1', NULL, 1, 'check_in:v1')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            testDbName,
            2,
            true,
            PendingActionDatabase.MIGRATION_1_2
        )

        db.query(
            "SELECT id, type, vaultId, queuedAt, dedupeKey, idempotencyKey, synced " +
                "FROM pending_actions WHERE id = 1"
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
            assertEquals("CHECK_IN", cursor.getString(1))
            assertEquals("v1", cursor.getString(2))
            assertEquals(1L, cursor.getLong(3))
            assertEquals("check_in:v1", cursor.getString(4))
            assertNotNull(cursor.getString(5))
            assertFalse(cursor.isNull(5))
            assertEquals(0, cursor.getInt(6))
        }

        db.close()
    }
}
