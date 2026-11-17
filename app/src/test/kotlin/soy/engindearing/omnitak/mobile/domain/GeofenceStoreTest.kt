package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import soy.engindearing.omnitak.mobile.data.geofence.Geofence
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceEvent
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceEventType
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceFile
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceSnapshot
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceStorage
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceType

/**
 * #155 - the geofence store: the list the screens and the service share, kept the way the chat log
 * is (loaded before anything reads it, written back off the calling thread once a burst is over),
 * so a geofence survives the process ending.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GeofenceStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun circle(id: String, name: String = "Gate $id") = Geofence(
        id = id, name = name, type = GeofenceType.CIRCLE,
        centerLat = 47.6588, centerLon = -117.4260, radiusMeters = 200.0,
    )

    private fun event(id: String) = GeofenceEvent(
        id = id, geofenceId = "a", geofenceName = "Gate a", type = GeofenceEventType.ENTRY, atMs = 1L,
        lat = 0.0, lon = 0.0, subjectId = "x",
    )

    /** A saved file in memory that records what the store does to it, in order. */
    private class FakeStorage(private var onDisk: GeofenceSnapshot = GeofenceSnapshot()) : GeofenceStorage {
        val events = mutableListOf<String>()
        val saves = mutableListOf<GeofenceSnapshot>()
        var failNextSaves = 0

        override fun load(): GeofenceSnapshot {
            events += "load"
            return onDisk
        }

        override fun save(snapshot: GeofenceSnapshot): Boolean {
            events += "save"
            if (failNextSaves > 0) {
                failNextSaves--
                return false
            }
            saves += snapshot
            onDisk = snapshot
            return true
        }
    }

    private fun TestScope.storeOver(storage: GeofenceStorage) = GeofenceStore(
        storage = storage,
        scope = backgroundScope,
        writeDelayMs = DELAY,
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun TestScope.settle(ms: Long = DELAY) {
        advanceTimeBy(ms)
        runCurrent()
    }

    // ------------------------------------------------------------------ the list

    @Test fun add_update_switch_and_remove() {
        val store = GeofenceStore()
        store.add(circle("a"))
        store.add(circle("b"))
        assertEquals(listOf("a", "b"), store.geofences.value.map { it.id })

        store.update(circle("a", "Renamed"))
        assertEquals("Renamed", store.geofences.value.first { it.id == "a" }.name)

        store.setActive("a", false)
        assertFalse(store.geofences.value.first { it.id == "a" }.isActive)
        store.setActive("a", true)
        assertTrue(store.geofences.value.first { it.id == "a" }.isActive)

        store.remove("a")
        assertEquals(listOf("b"), store.geofences.value.map { it.id })
    }

    @Test fun changing_what_is_not_there_changes_nothing() {
        val store = GeofenceStore()
        store.add(circle("a"))
        val before = store.geofences.value
        store.update(circle("zzz"))
        store.setActive("zzz", false)
        store.remove("zzz")
        assertTrue("the very same list", store.geofences.value === before)
    }

    @Test fun the_history_is_newest_first_and_capped_at_a_hundred() {
        val store = GeofenceStore()
        store.recordEvents(listOf(event("1"), event("2")))
        store.recordEvents(listOf(event("3")))
        assertEquals(listOf("3", "2", "1"), store.events.value.map { it.id })

        store.recordEvents((4..200).map { event("$it") })
        assertEquals(GeofenceSnapshot.MAX_EVENTS, store.events.value.size)
        assertEquals("200", store.events.value.first().id)
    }

    @Test fun clearing_the_history_empties_it() {
        val store = GeofenceStore()
        store.recordEvents(listOf(event("1")))
        store.clearEvents()
        assertTrue(store.events.value.isEmpty())
    }

    @Test fun deleting_a_geofence_leaves_what_it_reported_in_the_history() {
        val store = GeofenceStore()
        store.add(circle("a"))
        store.recordEvents(listOf(event("1")))
        store.remove("a")
        assertEquals(listOf("1"), store.events.value.map { it.id })
    }

    // ------------------------------------------------------------------ loading

    @Test fun it_holds_what_the_last_run_left_before_anyone_can_read_it() {
        val storage = FakeStorage(GeofenceSnapshot(geofences = listOf(circle("a"), circle("b")), events = listOf(event("e"))))
        val store = GeofenceStore(storage = storage)
        assertEquals(listOf("a", "b"), store.geofences.value.map { it.id })
        assertEquals(listOf("e"), store.events.value.map { it.id })
        assertEquals(listOf("load"), storage.events)
    }

    // ------------------------------------------------------------------ writing

    @Test fun a_change_is_written_once_the_burst_is_over() = runTest {
        val storage = FakeStorage()
        val store = storeOver(storage)
        runCurrent()
        store.add(circle("a"))
        store.add(circle("b"))
        store.setActive("a", false)
        assertTrue("nothing yet: the burst is still open", storage.saves.isEmpty())
        settle()
        assertEquals("one write for the whole burst", 1, storage.saves.size)
        assertEquals(listOf("a", "b"), storage.saves.single().geofences.map { it.id })
        assertFalse(storage.saves.single().geofences.first { it.id == "a" }.isActive)
    }

    @Test fun a_change_that_changes_nothing_is_not_written() = runTest {
        val storage = FakeStorage(GeofenceSnapshot(geofences = listOf(circle("a"))))
        val store = storeOver(storage)
        runCurrent()
        store.setActive("a", true) // it already is
        store.remove("nope")
        settle(DELAY * 3)
        assertTrue(storage.saves.isEmpty())
    }

    @Test fun a_failed_write_is_tried_again_with_the_next_change() = runTest {
        val storage = FakeStorage().also { it.failNextSaves = 1 }
        val store = storeOver(storage)
        runCurrent()
        store.add(circle("a"))
        settle()
        assertTrue(storage.saves.isEmpty())
        store.add(circle("b"))
        settle()
        assertEquals(listOf("a", "b"), storage.saves.single().geofences.map { it.id })
    }

    @Test fun the_events_are_written_with_the_geofences() = runTest {
        val storage = FakeStorage()
        val store = storeOver(storage)
        runCurrent()
        store.add(circle("a"))
        store.recordEvents(listOf(event("e1")))
        settle()
        assertEquals(listOf("e1"), storage.saves.single().events.map { it.id })
    }

    // ------------------------------------------------------------------ the next run

    @Test fun a_geofence_made_in_one_run_is_there_in_the_next() = runTest {
        val first = GeofenceStore(GeofenceFile.inFilesDir(tmp.root), backgroundScope, DELAY, StandardTestDispatcher(testScheduler))
        runCurrent()
        first.add(circle("a", "North gate"))
        first.add(circle("b").copy(isActive = false, dwellMinutes = 10))
        first.recordEvents(listOf(event("e1")))
        settle()

        // The process ends; a new one reads the file.
        val second = GeofenceStore(GeofenceFile.inFilesDir(tmp.root))
        assertEquals(listOf("North gate", "Gate b"), second.geofences.value.map { it.name })
        assertFalse(second.geofences.value.last().isActive)
        assertEquals(10, second.geofences.value.last().dwellMinutes)
        assertEquals(listOf("e1"), second.events.value.map { it.id })
    }

    @Test fun a_deleted_geofence_is_gone_in_the_next_run() = runTest {
        val first = GeofenceStore(GeofenceFile.inFilesDir(tmp.root), backgroundScope, DELAY, StandardTestDispatcher(testScheduler))
        runCurrent()
        first.add(circle("a"))
        first.add(circle("b"))
        settle()
        first.remove("a")
        settle()
        assertEquals(listOf("b"), GeofenceStore(GeofenceFile.inFilesDir(tmp.root)).geofences.value.map { it.id })
    }

    private companion object {
        const val DELAY = 200L
    }
}
