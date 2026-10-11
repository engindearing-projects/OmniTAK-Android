package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Lasso (freehand multi-select) selection logic. Mirrors the iOS
 * `LassoSelectionService` byte-for-byte API where it makes sense in
 * Kotlin idioms (StateFlow instead of Combine `@Published`, `LassoLatLng`
 * instead of `CLLocationCoordinate2D` so unit tests can run on the JVM
 * without the Android platform).
 *
 * Pure logic; no Compose dependency. The map screen owns instances of
 * this service via remember/CompositionLocal and collects [current] for
 * UI updates.
 *
 * iOS reference: `OmniTAKMobile/Features/Drawing/Services/LassoSelectionService.swift`
 * and `OmniTAKMobileSpecs/Sources/LassoCore/LassoSelectionService.swift`.
 */

// MARK: - Pure-data lat/lon
// A platform-free coordinate so `pointInPolygon` / `centroid` are
// unit-testable without instantiating `org.maplibre.android.geometry.LatLng`
// (which is Parcelable + tied to the Android framework). Production
// call sites convert MapLibre LatLng → LassoLatLng at the boundary.
data class LassoLatLng(val latitude: Double, val longitude: Double)

// MARK: - Selection participants

/** DTO for a marker the lasso can select. */
data class LassoMarker(
    val id: String,
    val coordinate: LassoLatLng,
)

/**
 * DTO for a drawing (line / circle / polygon / route).
 * `coordinates` is the full vertex list; the lasso uses its centroid
 * for inclusion testing per issue #16.
 */
data class LassoDrawing(
    val id: UUID,
    val coordinates: List<LassoLatLng>,
)

// MARK: - Selection result

/**
 * Identity-only selection set. Downstream features (bulk delete, data
 * package builder, export) consume this and do NOT depend on the
 * concrete marker/drawing types.
 */
data class SelectionContext(
    val markerIDs: Set<String> = emptySet(),
    val drawingIDs: Set<UUID> = emptySet(),
) {
    val totalCount: Int get() = markerIDs.size + drawingIDs.size
    val isEmpty: Boolean get() = markerIDs.isEmpty() && drawingIDs.isEmpty()
}

// MARK: - Service

class LassoSelectionService {

    private val _current = MutableStateFlow(SelectionContext())
    /** Observable selection. Compose binds to this via `collectAsState`. */
    val current: StateFlow<SelectionContext> = _current.asStateFlow()

    // Issue #16, activation request. The Tools popup ("Lasso Select")
    // asks; MapScreen acts on [activation] through OnRequest and flips its
    // local lassoMode. A request waits for the map when the Tools launcher
    // navigates to it and asks in the same frame (MapScreen is composed
    // after the ask), and is gone once acted on, so a map built again does
    // not replay it.
    val activation = OneShotRequest()

    /** Fired by the Tools popup. MapScreen acts on it via [activation]. */
    fun requestActivate() = activation.request()

    /**
     * Pure, no-side-effects: given a lasso polygon and a population of
     * map features, return everything inside. Does NOT mutate
     * [current] — call [applySelection] to commit.
     */
    fun performLasso(
        polygon: List<LassoLatLng>,
        markers: List<LassoMarker>,
        drawings: List<LassoDrawing>,
    ): SelectionContext {
        if (polygon.size < 3) return SelectionContext()

        val markerIDs = markers
            .filter { pointInPolygon(it.coordinate, polygon) }
            .map { it.id }
            .toSet()

        val drawingIDs = drawings
            .mapNotNull { d ->
                val c = centroid(d.coordinates) ?: return@mapNotNull null
                if (pointInPolygon(c, polygon)) d.id else null
            }
            .toSet()

        return SelectionContext(markerIDs = markerIDs, drawingIDs = drawingIDs)
    }

    fun applySelection(context: SelectionContext) {
        _current.value = context
    }

    fun clear() {
        _current.value = SelectionContext()
    }

    companion object {

        /** Singleton — UI surfaces (rings, pill, bulk actions) bind to one instance. */
        val shared: LassoSelectionService by lazy { LassoSelectionService() }

        /**
         * Standard ray-casting point-in-polygon (Franklin's PNPOLY).
         * Treats latitude as Y, longitude as X — planar math, accurate
         * for lasso-scale regions (a 10 km lasso is well under the
         * Earth-curvature threshold where great-circle math would
         * matter for inclusion).
         *
         * Cast a horizontal ray east from `point` and count edge
         * crossings. Odd = inside, even = outside.
         */
        fun pointInPolygon(point: LassoLatLng, polygon: List<LassoLatLng>): Boolean {
            if (polygon.size < 3) return false

            val x = point.longitude
            val y = point.latitude

            var inside = false
            var j = polygon.size - 1
            for (i in polygon.indices) {
                val xi = polygon[i].longitude
                val yi = polygon[i].latitude
                val xj = polygon[j].longitude
                val yj = polygon[j].latitude

                val intersect = ((yi > y) != (yj > y)) &&
                    (x < (xj - xi) * (y - yi) / (yj - yi) + xi)
                if (intersect) inside = !inside

                j = i
            }
            return inside
        }

        /**
         * Arithmetic-mean centroid. Good enough for lasso inclusion
         * testing per issue #16 ("drawing centroid inside" heuristic);
         * not area-weighted, but cheap and matches iOS.
         */
        fun centroid(coordinates: List<LassoLatLng>): LassoLatLng? {
            if (coordinates.isEmpty()) return null
            var sumLat = 0.0
            var sumLon = 0.0
            for (c in coordinates) {
                sumLat += c.latitude
                sumLon += c.longitude
            }
            val n = coordinates.size.toDouble()
            return LassoLatLng(sumLat / n, sumLon / n)
        }
    }
}
