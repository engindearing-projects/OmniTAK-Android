package soy.engindearing.omnitak.mobile.domain.tracking

import kotlinx.serialization.Serializable
import soy.engindearing.omnitak.mobile.data.GeoMath
import java.util.UUID

/**
 * A completed (or in-progress) breadcrumb track. The KML/GPX
 * exporters are pure formatters over this shape; everything a SAR
 * handler needs to recreate a search leg in CalTopo lives here.
 *
 * [totalDistanceM] is denormalised — recomputing on demand each time
 * the operator opens the Tracks list would scan every point, which
 * starts to bite once a 4-hour search has 5k+ points. The recorder
 * keeps it in sync by appending to it as it samples new points.
 */
@Serializable
data class Track(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val startedMs: Long,
    val endedMs: Long? = null,
    val points: List<TrackPoint> = emptyList(),
    val totalDistanceM: Double = 0.0,
    val notes: String? = null,
    /** Hex like #80de4a so the KML style line color survives. */
    val color: String = "#80de4a",
) {
    val durationMs: Long
        get() = (endedMs ?: System.currentTimeMillis()) - startedMs

    val isRecording: Boolean get() = endedMs == null

    /** Recompute total distance from points — cheap fallback when we
     *  don't trust the cached value (e.g. on load from disk). */
    fun recomputeDistanceM(): Double {
        if (points.size < 2) return 0.0
        var sum = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            sum += GeoMath.haversineMeters(a.lat, a.lon, b.lat, b.lon)
        }
        return sum
    }
}
