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
import kotlinx.coroutines.flow.MutableStateFlow
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
import soy.engindearing.omnitak.mobile.domain.SelfPositionBroadcaster
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

    /**
     * A DataStore over a real file that behaves the way the real one does: the transform runs on what is stored,
     * and when it changes nothing there is no write (so nothing to fail). A write that does happen fails with the
     * error a full disk gives while [full] is true, or with [failure] when that is set.
     */
    private class Disk(private val real: DataStore<Preferences>) : DataStore<Preferences> {
        @Volatile var full = false
        @Volatile var failure: IOException? = null
        var attempts = 0
        override val data = real.data
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            attempts++
            val current = real.data.first()
            val next = transform(current)
            if (next == current) return current
            failure?.let { throw it }
            if (full) throw IOException("write failed: ENOSPC (No space left on device)")
            return real.updateData { next }
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

    // ---- the same identity everywhere while the disk is full

    @Test fun the_prefs_show_the_uid_that_was_handed_out_on_a_full_disk() = runBlocking {
        disk.full = true
        val handedOut = store.ensureSelfUid()
        // Everything that reads the identity from the prefs (the position broadcaster, the chat screen, the self-echo
        // check) must see the one the chat tab was given, not a blank one.
        assertEquals(handedOut, store.prefs.first().selfUid)
    }

    @Test fun a_collector_of_the_prefs_sees_the_uid_as_soon_as_it_is_handed_out() = runBlocking {
        disk.full = true
        val seen = async(Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(5_000) { store.prefs.first { it.selfUid.isNotBlank() }.selfUid }
        }
        kotlinx.coroutines.delay(100)
        val handedOut = store.ensureSelfUid()
        assertEquals(handedOut, seen.await())
    }

    @Test fun the_position_broadcaster_and_the_chat_agree_on_one_uid_while_the_disk_is_full() = runBlocking {
        disk.full = true
        val sent = java.util.Collections.synchronizedList(mutableListOf<String>())
        val fix = MutableStateFlow<SelfFix?>(SelfFix(lat = 47.66, lon = -117.43, altitudeM = 550.0, speedKmh = 0.0, accuracyM = 3f, timeMs = 1L))
        val broadcaster = SelfPositionBroadcaster(
            scope = scope, prefsStore = store, sendCoT = { xml -> sent += xml; true }, locationFix = fix, intervalMs = 20L,
        )
        broadcaster.start()
        kotlinx.coroutines.withTimeout(10_000) { while (sent.size < 4) kotlinx.coroutines.delay(10) }
        broadcaster.stop()
        val chat = store.ensureSelfUid()
        val uids = sent.toList().map { Regex("uid=\"([^\"]+)\"").find(it)!!.groupValues[1] }
        assertEquals("every position report carries the uid the chat uses: $uids", setOf(chat), uids.toSet())
        assertFalse("and none falls back to the one every device would share", sent.any { it.contains("ANDROID-fallback") })
    }

    @Test fun a_write_that_fails_after_the_transform_ran_still_leaves_one_uid_and_it_is_the_one_saved_later() = runBlocking {
        // The disk fake runs the transform (which mints a uid inside the edit) and then fails the write, as the real one does.
        disk.full = true
        val first = store.ensureSelfUid()
        val second = store.ensureSelfUid()
        assertEquals(first, second)
        assertTrue(disk.attempts >= 2)
        disk.full = false
        assertEquals("the disk has room: the same uid is what gets saved", first, store.ensureSelfUid())
        assertEquals(first, store.prefs.first().selfUid)
    }

    // ---- the log, once per outage

    @Test fun a_save_that_changes_nothing_does_not_re_arm_the_log() = runBlocking {
        store.update { it.copy(callsign = "ALPHA-1") } // the file has its keys now: an update that changes nothing is a no-op
        disk.full = true
        store.update { it.copy(gridEnabled = true) } // a real change: fails, reported
        store.update { it } // changes nothing: DataStore skips the write, so nothing was proved about the disk
        store.update { it.copy(gridEnabled = true) } // the outage goes on
        assertEquals("one line for the outage, not one per interleaving: $warnings", 1, warnings.size)
    }

    @Test fun a_save_that_changes_something_and_works_does_re_arm_it() = runBlocking {
        disk.full = true
        store.update { it.copy(gridEnabled = true) }
        disk.full = false
        store.update { it.copy(gridEnabled = true) }
        disk.full = true
        store.update { it.copy(gridEnabled = false) }
        assertEquals(2, warnings.size)
    }

    // ---- what is not swallowed

    @Test fun a_corrupt_preferences_file_is_not_swallowed_as_a_full_disk() = runBlocking {
        // CorruptionException is an IOException too, but nothing here is full: a corrupt file must still fail loudly.
        disk.failure = androidx.datastore.core.CorruptionException("the file is not a preferences file")
        try {
            store.update { it.copy(gridEnabled = true) }
            fail("a corrupt file must reach the caller")
        } catch (e: androidx.datastore.core.CorruptionException) {
            assertEquals("the file is not a preferences file", e.message)
        }
        assertTrue("and it is not reported as a failed write", warnings.isEmpty())
    }

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
