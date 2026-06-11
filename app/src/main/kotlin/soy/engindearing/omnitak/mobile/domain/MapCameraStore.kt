package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import soy.engindearing.omnitak.mobile.data.UserPrefs
import soy.engindearing.omnitak.mobile.data.UserPrefsStore

/**
 * App-scoped persistence for the map camera position.
 *
 * In-memory fields survive bottom-nav tab switches (Issue #7).
 * DataStore writes survive full process death — the operator's last
 * pan/zoom is restored on cold start instead of reverting to a
 * hardcoded developer default (P-E TAK Discord report 2026-05-27).
 *
 * Writes are debounced 500 ms so rapid camera movement during a pan
 * gesture doesn't hammer DataStore on every idle event.
 */
class MapCameraStore(private val userPrefsStore: UserPrefsStore) {

    // In-memory cache. Populated from DataStore at app startup via
    // [seedFromPrefs], then kept current by [update]. MapScreen reads
    // synchronously from here; DataStore writes happen asynchronously.
    var lastTargetLat: Double? = null
        private set
    var lastTargetLon: Double? = null
        private set
    var lastZoom: Double? = null
        private set
    // Bearing (degrees clockwise from north) is session-memory ONLY — it
    // is not persisted with the lat/lon/zoom triple because the 2D map
    // intentionally cold-starts north-up. It exists so the 2D↔3D engine
    // switch can hand the rotation across within a session (#78).
    var lastBearing: Double? = null
        private set

    private val scope = CoroutineScope(Dispatchers.IO)
    private var debounceJob: Job? = null

    /**
     * Seed the in-memory cache from previously persisted prefs.
     * Call once at startup (e.g. in OmniTAKApp.onCreate) before
     * MapScreen first composes. Safe to call multiple times — a
     * populated cache is never overwritten by a later seed call.
     */
    fun seedFromPrefs(prefs: UserPrefs) {
        val (lat, lon, zoom) = extractCamera(prefs) ?: return
        if (lastTargetLat == null) {
            lastTargetLat = lat
            lastTargetLon = lon
            lastZoom      = zoom
        }
    }

    /**
     * Record a new camera position. Updates the in-memory cache
     * immediately and debounce-persists to DataStore after 500 ms so
     * rapid pan/zoom gestures coalesce into a single write.
     */
    fun update(lat: Double, lon: Double, zoom: Double, bearing: Double = 0.0) {
        lastTargetLat = lat
        lastTargetLon = lon
        lastZoom      = zoom
        lastBearing   = bearing

        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(500)
            userPrefsStore.setLastCamera(lat, lon, zoom)
        }
    }

    companion object {
        /**
         * Extract a valid camera triple from [prefs], or null if any
         * component is missing. All three values must be present and
         * finite for the saved view to be usable.
         *
         * Exposed as a companion function so the priority-resolution
         * logic can be tested without an Android context.
         */
        fun extractCamera(prefs: UserPrefs): Triple<Double, Double, Double>? {
            val lat  = prefs.lastCameraLat  ?: return null
            val lon  = prefs.lastCameraLon  ?: return null
            val zoom = prefs.lastCameraZoom ?: return null
            if (!lat.isFinite() || !lon.isFinite() || !zoom.isFinite()) return null
            return Triple(lat, lon, zoom)
        }
    }
}
