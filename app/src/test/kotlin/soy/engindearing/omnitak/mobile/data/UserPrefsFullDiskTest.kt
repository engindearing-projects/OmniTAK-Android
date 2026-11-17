package soy.engindearing.omnitak.mobile.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * A write to the preferences file on a full disk threw an IOException ("write failed: ENOSPC") out of
 * DataStore, from a coroutine nobody awaits, and the whole app died with it (seen on the emulator with /data
 * full: any move of the map saves the camera, and the first save killed the process). Every write the app
 * makes to the user preferences goes through [UserPrefsStore.update] or [UserPrefsStore.ensureSelfUid], the chat
 * tab's and the geofence screen's included, so the guard is at those two.
 *
 * The real store runs over a DataStore whose disk can be filled and emptied.
 */
class UserPrefsFullDiskTest {

    @get:Rule val tmp = TemporaryFolder()

    /** A DataStore over a real file whose writes fail with the error a full disk gives, while [full] is true. */
    private class Disk(private val real: DataStore<Preferences>) : DataStore<Preferences> {
        @Volatile var full = false
        var attempts = 0
        override val data = real.data
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            attempts++
            if (full) throw IOException("write failed: ENOSPC (No space left on device)")
            return real.updateData(transform)
        }
    }

    private lateinit var scope: CoroutineScope
    private lateinit var disk: Disk
    private lateinit var warnings: MutableList<String>
    private lateinit var store: UserPrefsStore

    @Before fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        disk = Disk(PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(tmp.root, "user_prefs.preferences_pb") }))
        warnings = mutableListOf()
        store = UserPrefsStore(disk) { warnings += it }
    }

    @After fun tearDown() {
        scope.cancel()
    }

    @Test fun a_write_on_a_full_disk_does_not_throw_and_the_old_value_stays() = runBlocking {
        store.update { it.copy(callsign = "ALPHA-1") }
        disk.full = true
        store.update { it.copy(callsign = "BRAVO-2", gridEnabled = true) } // before the fix: IOException out of here
        val now = store.prefs.first()
        assertEquals("ALPHA-1", now.callsign)
        assertFalse(now.gridEnabled)
    }

    @Test fun the_camera_that_is_saved_on_every_move_of_the_map_does_not_crash_a_full_disk_either() = runBlocking {
        disk.full = true
        store.setLastCamera(47.66, -117.43, 14.0)
        store.setLastSelfFix(SelfFix(lat = 47.66, lon = -117.43, altitudeM = 550.0, speedKmh = 0.0, accuracyM = 3f, timeMs = 1L))
    }

    @Test fun it_is_reported_once_however_many_writes_fail() = runBlocking {
        disk.full = true
        repeat(25) { store.update { it.copy(gridEnabled = true) } }
        assertEquals(25, disk.attempts)
        assertEquals("one line, not 25: $warnings", 1, warnings.size)
        assertTrue(warnings.single().contains("ENOSPC"))
    }

    @Test fun a_disk_that_fills_again_after_working_is_reported_again() = runBlocking {
        disk.full = true
        store.update { it.copy(gridEnabled = true) }
        disk.full = false
        store.update { it.copy(gridEnabled = true) }
        assertTrue("it worked this time", store.prefs.first().gridEnabled)
        disk.full = true
        store.update { it.copy(gridEnabled = false) }
        assertEquals(2, warnings.size)
    }

    @Test fun writes_work_again_when_the_disk_has_room() = runBlocking {
        disk.full = true
        store.update { it.copy(callsign = "LOST") }
        disk.full = false
        store.update { it.copy(callsign = "KEPT") }
        assertEquals("KEPT", store.prefs.first().callsign)
    }

    @Test fun tryUpdate_says_whether_it_was_saved() = runBlocking {
        assertTrue(store.tryUpdate { it.copy(gridEnabled = true) })
        disk.full = true
        assertFalse(store.tryUpdate { it.copy(gridEnabled = false) })
    }

    @Test fun the_rotation_shortcut_does_not_claim_a_mode_that_was_not_saved() = runBlocking {
        val first = store.cycleScreenRotation()
        disk.full = true
        val second = store.cycleScreenRotation()
        assertEquals("the disk would not take the next mode, so the mode in force is the one before", first, second)
        assertEquals(first, store.prefs.first().screenRotation)
    }

    // ---- the chat tab's identity

    @Test fun the_chat_uid_is_the_same_every_time_while_the_disk_is_full() = runBlocking {
        disk.full = true
        val first = store.ensureSelfUid()
        val second = store.ensureSelfUid()
        assertTrue(first.startsWith("ANDROID-"))
        assertEquals("two messages must not go out under two uids (#205)", first, second)
    }

    @Test fun the_uid_handed_out_on_a_full_disk_is_the_one_saved_when_the_disk_has_room() = runBlocking {
        disk.full = true
        val handedOut = store.ensureSelfUid()
        disk.full = false
        assertEquals(handedOut, store.ensureSelfUid())
        assertEquals("and it is on disk now", handedOut, store.prefs.first().selfUid)
    }

    @Test fun a_uid_that_was_saved_is_not_replaced_by_a_full_disk() = runBlocking {
        val saved = store.ensureSelfUid()
        disk.full = true
        assertEquals(saved, store.ensureSelfUid())
    }

    @Test fun several_callers_at_once_on_a_full_disk_still_agree_on_one_uid() = runBlocking {
        disk.full = true
        val uids = (1..8).map { async(Dispatchers.Default) { store.ensureSelfUid() } }.awaitAll()
        assertEquals(1, uids.toSet().size)
    }

    // ---- what is not swallowed

    @Test fun an_error_that_is_not_the_disk_still_reaches_the_caller() = runBlocking {
        try {
            store.update { error("a bug in a caller's block") }
            fail("a bug must not be hidden as a full disk")
        } catch (e: IllegalStateException) {
            assertEquals("a bug in a caller's block", e.message)
        }
        assertTrue("and it is not reported as a failed write", warnings.isEmpty())
    }

    @Test fun a_cancelled_write_is_not_swallowed() = runBlocking {
        val cancelling = object : DataStore<Preferences> {
            override val data = disk.data
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw CancellationException("the screen went away")
        }
        try {
            UserPrefsStore(cancelling) { warnings += it }.update { it.copy(gridEnabled = true) }
            fail("cancellation must reach the coroutine that was cancelled")
        } catch (e: CancellationException) {
            assertNotEquals(0, e.message?.length ?: 0)
        }
        assertTrue(warnings.isEmpty())
    }
}
