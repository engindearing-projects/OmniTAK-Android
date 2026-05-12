package soy.engindearing.omnitak.mobile.domain.offline

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * One downloaded (or in-progress) tile cache region. Identified by a
 * stable UUID so the on-disk directory survives renames. Tile counts
 * are cached at creation time — the operator sees an estimated size
 * before committing to a multi-hundred-megabyte download.
 *
 * Mirrors iOS `CachedRegion` field-for-field where possible.
 */
@Serializable
data class TileRegion(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double,
    val zoomMin: Int,
    val zoomMax: Int,
    val tileCount: Int,
    val downloadedTiles: Int = 0,
    val createdMs: Long = System.currentTimeMillis(),
    /** URL template the operator picked at download time, e.g.
     *  "https://tile.openstreetmap.org/{z}/{x}/{y}.png". Saved so
     *  resuming a partial download uses the same provider. */
    val urlTemplate: String,
    /** Approximate bytes on disk; updated post-download. Zero means
     *  unknown / not yet computed. */
    val sizeBytes: Long = 0L,
) {
    val isComplete: Boolean get() = downloadedTiles >= tileCount

    val progress: Float
        get() = if (tileCount == 0) 0f else downloadedTiles.toFloat() / tileCount

    fun bbox(): Bbox = Bbox(north = north, south = south, east = east, west = west)

    /** True if this region's bbox + zoom range covers (z, x, y). */
    fun contains(z: Int, x: Int, y: Int): Boolean {
        if (z < zoomMin || z > zoomMax) return false
        val (lat, lon) = TileMath.tileNorthWest(x, y, z)
        return bbox().contains(lat, lon)
    }
}
