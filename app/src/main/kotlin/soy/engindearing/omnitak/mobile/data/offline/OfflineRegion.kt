package soy.engindearing.omnitak.mobile.data.offline

import kotlinx.serialization.Serializable

/**
 * Metadata for one downloaded offline region (#120). The tiles themselves
 * live in [fileName] (a standard MBTiles SQLite file under the app's
 * `offline-tiles/` dir), which the existing
 * [soy.engindearing.omnitak.mobile.data.MBTilesDb] reads and
 * [soy.engindearing.omnitak.mobile.data.MBTilesServer] serves — so a region
 * is, on the map, just another raster tile source.
 */
@Serializable
data class OfflineRegion(
    val id: String,
    val name: String,
    val fileName: String,
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double,
    val minZoom: Int,
    val maxZoom: Int,
    /** Tiles successfully written (for the manage/list UI). */
    val tileCount: Int,
    /** On-disk size of the MBTiles file in bytes. */
    val sizeBytes: Long,
    val createdAt: Long,
    /** Which basemap the tiles were pulled from, for display ("OSM", "Topo"…). */
    val sourceLabel: String = "",
    val visible: Boolean = true,
) {
    /** True if [lat]/[lon] falls inside this region's bbox. */
    fun contains(lat: Double, lon: Double): Boolean =
        lat in south..north && lon in west..east

    /** True if [z] is within the downloaded zoom range. */
    fun coversZoom(z: Int): Boolean = z in minZoom..maxZoom

    fun bbox(): BoundingBox = BoundingBox(north, south, east, west)
}

/** The rendering decision for the current online/offline state. */
data class OfflineDecision(
    /** Region ids whose cached layers should be present on the map. */
    val activeRegionIds: List<String>,
    /** True when cached layers should be drawn *above* the live basemap so
     *  they win — i.e. the network is down. When false, cached layers are
     *  still added (free, instant) but the live basemap shows through outside
     *  downloaded areas. */
    val cachePreferred: Boolean,
)

/**
 * Pure policy for "serve from cache when offline". Kept free of Android and
 * MapLibre types so it unit-tests directly; the map layer translates an
 * [OfflineDecision] into raster source/layer ordering on device.
 */
object OfflineTilePolicy {
    fun decide(regions: List<OfflineRegion>, networkAvailable: Boolean): OfflineDecision =
        OfflineDecision(
            activeRegionIds = regions.filter { it.visible }.map { it.id },
            cachePreferred = !networkAvailable && regions.any { it.visible },
        )
}
