package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import soy.engindearing.omnitak.mobile.data.CoTEvent

/**
 * Application-scoped roster of last-known CoT contacts keyed by UID.
 * Updates are diff-friendly — the underlying StateFlow re-emits only
 * when a referentially-new map is assigned, and the GeoJson layer on
 * the map rebuilds features from whatever is latest here.
 *
 * Writers are concurrent (per-server collectors on Dispatchers.Default,
 * the Meshtastic cotSink, gyb + Remote ID pipelines on appScope), so
 * every read-modify-write goes through [MutableStateFlow.update]'s CAS
 * loop — a plain `value = value + x` can silently drop a concurrent
 * ingest under multi-server CoT burst.
 */
class ContactStore {
    private val _contacts = MutableStateFlow<Map<String, CoTEvent>>(emptyMap())
    val contacts: StateFlow<Map<String, CoTEvent>> = _contacts.asStateFlow()

    /**
     * #118 — Filtered view of [contacts] that excludes locally-dropped point
     * markers and bookmark-map-point (b-m-p-*) types. Use this in any UI that
     * presents valid DM endpoints (e.g. ChatScreen's contact stub list).
     */
    val chatCandidates: Flow<Map<String, CoTEvent>> =
        _contacts.map { m -> m.filterValues { isEndpoint(it) } }

    /**
     * Insert or update a contact.
     *
     * #178 — every ingest stamps [CoTEvent.receivedAtMs] with the current
     * wall-clock so the UI can show how old a point is (the CoT `time` attribute
     * is the producer's clock and lags the link, so it isn't a reliable "when did
     * I last hear this" signal). Callers may pre-stamp [event.receivedAtMs] (e.g.
     * tests with a fixed clock); a non-zero value is respected, otherwise `now`.
     *
     * #180 — callers tag [event.source] at the ingest point (server sink vs mesh
     * sink). If a caller didn't set one but the existing contact already carries
     * a source, the existing source is preserved so a same-UID re-ingest from an
     * untagged path doesn't blank it.
     */
    fun ingest(event: CoTEvent, nowMs: Long = System.currentTimeMillis()) {
        _contacts.update { current ->
            val stamped = event.copy(
                receivedAtMs = if (event.receivedAtMs != 0L) event.receivedAtMs else nowMs,
                source = event.source ?: current[event.uid]?.source,
            )
            current + (event.uid to stamped)
        }
    }

    /** Remove a contact by UID. */
    fun remove(uid: String) {
        _contacts.update { if (uid in it) it - uid else it }
    }

    /**
     * #215: Remove every contact [shouldRemove] says yes to, and return how many went.
     *
     * The decision is made inside the update loop, on the map that update is replacing. A report
     * that lands while this runs is judged on its new received-at stamp and survives; deciding
     * from an earlier snapshot and then calling [remove] by uid would drop a contact that had
     * just been refreshed. [shouldRemove] can run more than once per call (the loop retries
     * when another writer got in first), so it must not have side effects.
     */
    fun removeIf(shouldRemove: (CoTEvent) -> Boolean): Int {
        var removed = 0
        _contacts.update { current ->
            val kept = current.filterValues { !shouldRemove(it) }
            removed = current.size - kept.size
            if (removed == 0) current else kept
        }
        return removed
    }

    /** Drop everything — used on connection teardown or manual reset. */
    fun clear() {
        _contacts.value = emptyMap()
    }

    companion object {
        /**
         * #118 — Returns `true` when [event] is a valid GeoChat DM endpoint.
         *
         * A contact is NOT a valid endpoint when:
         * - Its uid starts with `"local-"` — operator-dropped point markers
         *   created in MapScreen get a `local-{timestamp}` uid and are never
         *   network-reachable peers.
         * - Its CoT type starts with `"b-m-p"` — bookmark-map-point sub-schema
         *   events are positional annotations, not communicating endpoints,
         *   regardless of which server forwarded them.
         *
         * Real endpoints — friendly/hostile/neutral EUDs (`a-*`), RID drones
         * (`RID-` uid prefix), and Meshtastic nodes (`MESHTASTIC-` prefix) —
         * all pass through and return `true`.
         */
        fun isEndpoint(event: CoTEvent): Boolean {
            if (event.uid.startsWith("local-")) return false
            if (event.type.startsWith("b-m-p")) return false
            return true
        }
    }
}
