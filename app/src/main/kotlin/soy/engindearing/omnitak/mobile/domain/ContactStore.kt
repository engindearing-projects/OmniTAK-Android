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

    /** Insert or update a contact. Stale logic arrives with Slice 15. */
    fun ingest(event: CoTEvent) {
        _contacts.update { it + (event.uid to event) }
    }

    /** Remove a contact by UID. */
    fun remove(uid: String) {
        _contacts.update { if (uid in it) it - uid else it }
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
