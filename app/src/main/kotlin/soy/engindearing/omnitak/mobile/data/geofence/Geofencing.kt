package soy.engindearing.omnitak.mobile.data.geofence

import kotlinx.serialization.Serializable
import soy.engindearing.omnitak.mobile.data.GeoMath

/**
 * General geofencing (#155, ported from iOS GeofenceService): circle + polygon zones.
 *
 * This file is the geometry: where a point is inside a zone. What a zone is called, who it
 * watches and what it alerts on is [Geofence]; what happens when someone crosses its edge is
 * [GeofenceMonitor]. Pure, with no Android dependency, so it unit-tests directly.
 */

/** A WGS84 point. */
@Serializable
data class LatLon(val lat: Double, val lon: Double)

enum class GeofenceEventType { ENTRY, EXIT, DWELL }

/** A monitored area. [contains] is the only thing the monitor needs. */
sealed interface GeofenceZone {
    val id: String
    fun contains(lat: Double, lon: Double): Boolean
}

/**
 * Circular zone: inside when within [radiusMeters] great-circle distance. A point exactly on the
 * radius is inside.
 */
data class CircleZone(
    override val id: String,
    val centerLat: Double,
    val centerLon: Double,
    val radiusMeters: Double,
) : GeofenceZone {
    override fun contains(lat: Double, lon: Double): Boolean =
        GeoMath.haversineMeters(centerLat, centerLon, lat, lon) <= radiusMeters
}

/** Polygon zone: inside per ray-casting over the ring of [vertices]. */
data class PolygonZone(
    override val id: String,
    val vertices: List<LatLon>,
) : GeofenceZone {
    override fun contains(lat: Double, lon: Double): Boolean = Geofencing.pointInPolygon(lat, lon, vertices)
}

object Geofencing {
    /**
     * Ray-casting point-in-polygon on (x = lon, y = lat). Planar approximation, correct for
     * tactical-scale zones; a polygon across the antimeridian is out of scope.
     */
    fun pointInPolygon(lat: Double, lon: Double, vertices: List<LatLon>): Boolean {
        if (vertices.size < 3) return false
        var inside = false
        var j = vertices.size - 1
        for (i in vertices.indices) {
            val yi = vertices[i].lat
            val xi = vertices[i].lon
            val yj = vertices[j].lat
            val xj = vertices[j].lon
            val intersects = (yi > lat) != (yj > lat) &&
                lon < (xj - xi) * (lat - yi) / (yj - yi) + xi
            if (intersects) inside = !inside
            j = i
        }
        return inside
    }
}
