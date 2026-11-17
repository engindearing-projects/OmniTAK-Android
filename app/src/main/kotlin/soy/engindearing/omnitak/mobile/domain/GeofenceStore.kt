package soy.engindearing.omnitak.mobile.domain

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import soy.engindearing.omnitak.mobile.data.geofence.Geofence
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceEvent
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceSnapshot
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceStorage

/**
 * The operator's geofences, and the history of what they reported. Application-scoped, so screens
 * and the service that watches them read the same lists.
 *
 * Kept the way the chat log is: with a [storage] they survive the process ending (force stop,
 * reboot, an update):
 *  - they are loaded in the constructor, before the flows exist, so a screen only ever sees a store
 *    that already holds them. The app builds the store on a background thread at launch; whoever
 *    needs it sooner waits for the lazy that holds it;
 *  - every change is written again off the calling thread: the first change opens a [writeDelayMs]
 *    window and everything that changes inside it goes out in one write when the window closes.
 *
 * Without a [storage] the store stays in memory, which is what the tests of the classes that use it
 * do. A [storage] without a [scope] is read once and never written.
 */
class GeofenceStore(
    private val storage: GeofenceStorage? = null,
    scope: CoroutineScope? = null,
    private val writeDelayMs: Long = WRITE_DELAY_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    // Read first, before anything below exists.
    private val restored: GeofenceSnapshot = loadRestored(storage)

    private val _geofences = MutableStateFlow(restored.geofences)
    val geofences: StateFlow<List<Geofence>> = _geofences.asStateFlow()

    /** What happened, newest first. */
    private val _events = MutableStateFlow(restored.events)
    val events: StateFlow<List<GeofenceEvent>> = _events.asStateFlow()

    /** Wakes the writer. Conflated: any number of changes while it waits count as one. */
    private val dirty = Channel<Unit>(Channel.CONFLATED)

    /** What the file holds as far as this store knows. Touched only by the writer. */
    private var lastWritten: GeofenceSnapshot = restored

    init {
        if (storage != null && scope != null) {
            scope.launch {
                for (ignored in dirty) {
                    delay(writeDelayMs)
                    // Everything changed up to now goes into the snapshot taken below; a change
                    // after that sends a new signal.
                    dirty.tryReceive()
                    try {
                        writeIfChanged(storage)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The writer must outlive any one failed write.
                        Log.w(TAG, "geofences not saved: ${e.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    /** Adds [geofence] at the end of the list. */
    fun add(geofence: Geofence) {
        edit { list -> list + geofence }
    }

    /** Replaces the geofence with the same id; nothing if there is none. */
    fun update(geofence: Geofence) {
        edit { list -> if (list.any { it.id == geofence.id }) list.map { if (it.id == geofence.id) geofence else it } else list }
    }

    /** Switches one on or off. */
    fun setActive(id: String, active: Boolean) {
        edit { list -> if (list.any { it.id == id && it.isActive != active }) list.map { if (it.id == id) it.copy(isActive = active) else it } else list }
    }

    /** Deletes one. Its events stay in the history: they happened. */
    fun remove(id: String) {
        edit { list -> if (list.any { it.id == id }) list.filterNot { it.id == id } else list }
    }

    /** Puts [added] (oldest first) at the front of the history, newest first, within its cap. */
    fun recordEvents(added: List<GeofenceEvent>) {
        if (added.isEmpty()) return
        val before = _events.value
        _events.update { current -> (added.asReversed() + current).take(GeofenceSnapshot.MAX_EVENTS) }
        if (_events.value !== before) markDirty()
    }

    fun clearEvents() {
        if (_events.value.isEmpty()) return
        _events.value = emptyList()
        markDirty()
    }

    // Every change to the geofences goes through here, so none can skip the save. A transform that
    // returns its argument unchanged is not a change.
    private inline fun edit(transform: (List<Geofence>) -> List<Geofence>) {
        val before = _geofences.value
        _geofences.update(transform)
        if (_geofences.value !== before) markDirty()
    }

    private fun markDirty() {
        if (storage != null) dirty.trySend(Unit)
    }

    private fun currentSnapshot() = GeofenceSnapshot(geofences = _geofences.value, events = _events.value)

    private suspend fun writeIfChanged(storage: GeofenceStorage) {
        val snapshot = currentSnapshot().capped()
        if (snapshot == lastWritten) return
        if (withContext(ioDispatcher) { storage.save(snapshot) }) {
            lastWritten = snapshot
        } else {
            // Stays unwritten; the next change tries again.
            Log.w(TAG, "geofences not saved")
        }
    }

    companion object {
        private const val TAG = "GeofenceStore"

        private fun loadRestored(storage: GeofenceStorage?): GeofenceSnapshot {
            if (storage == null) return GeofenceSnapshot()
            val startNs = System.nanoTime()
            val restored = storage.load().capped()
            Log.i(
                TAG,
                "restored ${restored.geofences.size} geofences and ${restored.events.size} events " +
                    "in ${(System.nanoTime() - startNs) / 1_000_000} ms",
            )
            return restored
        }

        /**
         * How long the first change in a burst waits for company before it is written. Short: a
         * geofence is made by hand and the operator expects it to be there after a force stop.
         */
        const val WRITE_DELAY_MS = 200L
    }
}
