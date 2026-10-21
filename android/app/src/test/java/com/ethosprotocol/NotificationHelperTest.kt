package com.ethosprotocol

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import com.ethosprotocol.services.NotificationHelper
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NotificationHelperTest {

    private lateinit var helper: NotificationHelper

    @Before
    fun setup() {
        val context: Context = mockk(relaxed = true)
        every { context.getSharedPreferences(any<String>(), any()) } returns fakeSharedPreferences()
        // NotificationHelper's init block calls createChannel(), which calls
        // context.getSystemService(NotificationManager::class.java) — a relaxed Context mock
        // returns a generic relaxed Any for that without this stub, which fails to cast.
        every { context.getSystemService(NotificationManager::class.java) } returns mockk(relaxed = true)
        helper = NotificationHelper(context)
    }

    @Test
    fun `same vault id always maps to the same notification id`() {
        val first = helper.notificationIdFor("vault-1")
        val second = helper.notificationIdFor("vault-1")

        assertEquals(first, second)
    }

    @Test
    fun `two different vault ids never collide across a large sample`() {
        val ids = (0 until 5_000).map { helper.notificationIdFor("vault-$it") }

        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `two vaults produce distinct PendingIntent request codes`() {
        val first = helper.pendingIntentRequestCodeFor("vault-1")
        val second = helper.pendingIntentRequestCodeFor("vault-2")

        assertNotEquals(first, second)
    }

    @Test
    fun `vault notification ids never collide with the queued check-in id`() {
        val ids = (0 until 100).map { helper.notificationIdFor("vault-$it") }

        assertFalse(ids.contains(NotificationHelper.QUEUED_NOTIFICATION_ID))
    }

    @Test
    fun `null vault id does not collide with a real vault id`() {
        val nullId = helper.notificationIdFor(null)
        val realId = helper.notificationIdFor("vault-1")

        assertNotEquals(nullId, realId)
    }

    // ---------------------------------------------------------------------------
    // #197 — reminder lead time scales with the vault's check-in interval
    // ---------------------------------------------------------------------------

    @Test
    fun `short ttl window uses one tenth of the check-in interval as lead time`() {
        // 6h interval -> 36m lead time, so the reminder fires 36m before expiry.
        assertEquals(2_160L, NotificationHelper.primaryLeadTimeSeconds(21_600L))
        assertEquals(19_440L, NotificationHelper.primaryReminderDelaySeconds(21_600L, 21_600L))
    }

    @Test
    fun `medium ttl window scales lead time with the interval`() {
        // 3d interval -> 7.2h lead time, still under the 24h cap.
        assertEquals(25_920L, NotificationHelper.primaryLeadTimeSeconds(259_200L))
        assertEquals(233_280L, NotificationHelper.primaryReminderDelaySeconds(259_200L, 259_200L))
    }

    @Test
    fun `long ttl window caps the lead time at 24 hours`() {
        // 30d interval -> 3d uncapped, capped to 24h.
        assertEquals(86_400L, NotificationHelper.primaryLeadTimeSeconds(2_592_000L))
        assertEquals(2_505_600L, NotificationHelper.primaryReminderDelaySeconds(2_592_000L, 2_592_000L))
    }

    @Test
    fun `reminder delay never falls below the one minute floor`() {
        assertEquals(60L, NotificationHelper.primaryReminderDelaySeconds(30L, 21_600L))
        assertEquals(60L, NotificationHelper.secondaryReminderDelaySeconds(30L))
    }

    @Test
    fun `short check-in intervals get a second urgent reminder only when it lands last`() {
        // 22h interval -> 2h12m lead time, so the 2h-before-expiry reminder still lands after
        // the primary one and adds a genuinely more urgent nudge.
        assertTrue(NotificationHelper.hasSecondaryReminder(100_000L, 79_200L))
        // 6h interval -> 36m lead time: the primary reminder is already the later of the two,
        // so a "2 hours left" reminder would only fire earlier and is skipped.
        assertFalse(NotificationHelper.hasSecondaryReminder(21_600L, 21_600L))
    }

    @Test
    fun `long check-in intervals get no secondary reminder`() {
        assertFalse(NotificationHelper.hasSecondaryReminder(2_592_000L, 2_592_000L))
    }

    private fun fakeSharedPreferences(): SharedPreferences {
        val backing = mutableMapOf<String, Int>()
        val editor: SharedPreferences.Editor = mockk(relaxed = true)
        val prefs: SharedPreferences = mockk()

        every { prefs.getInt(any(), any()) } answers { backing[firstArg()] ?: secondArg() }
        every { prefs.all } answers { backing.toMutableMap() }
        every { prefs.edit() } returns editor
        every { editor.putInt(any(), any()) } answers {
            backing[firstArg()] = secondArg()
            editor
        }
        every { editor.apply() } just Runs

        return prefs
    }
}
