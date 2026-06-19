package soy.engindearing.omnitak.mobile.data.geofence

import soy.engindearing.omnitak.mobile.data.GeoMath

/**
 * General geofencing (#155, ported from iOS GeofenceService): circle + polygon
 * zones with entry/exit/dwell transitions on tracked contacts.
 *
 * Pure — no Android dependency — so it unit-tests directly. The Android layer
 * feeds each contact's position into [GeofenceMonitor.update] (from the CoT
 * stream / self GPS) and turns the returned events into notifications + a map
 * highlight; CoT for the zone definitions/events layers on top.
 */

/** A WGS84 point. */
data class LatLon(val lat: Double, val lon: Double)

enum class GeofenceEventType { ENTRY, EXIT, DWELL }

/** A monitored area. [contains] is the only thing the monitor needs. */
sealed interface GeofenceZone {
    val id: String
    fun contains(lat: Double, lon: Double): Boolean
}

/** Circular zone — inside when within [radiusMeters] great-circle distance. */
data class CircleZone(
    override val id: String,
    val centerLat: Double,
    val centerLon: Double,
    val radiusMeters: Double,
) : GeofenceZone {
    override fun contains(lat: Double, lon: Double): Boolean =
        GeoMath.haversineMeters(centerLat, centerLon, lat, lon) <= radiusMeters
}

/** Polygon zone — inside per ray-casting over the ring of [vertices]. */
data class PolygonZone(
    override val id: String,
    val vertices: List<LatLon>,
) : GeofenceZone {
    override fun contains(lat: Double, lon: Double): Boolean = Geofencing.pointInPolygon(lat, lon, vertices)
}

/** One transition for [contactUid] against [zoneId] at [atMs]. */
data class GeofenceEvent(
    val zoneId: String,
    val contactUid: String,
    val type: GeofenceEventType,
    val atMs: Long,
)

object Geofencing {
    /**
     * Ray-casting point-in-polygon on (x = lon, y = lat). Planar approximation —
     * correct for tactical-scale zones; doesn't handle the antimeridian.
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

/**
 * Tracks per-(zone, contact) inside state and emits entry/exit/dwell events,
 * mirroring the iOS transition logic. [dwellThresholdMs] of 0 disables dwell;
 * otherwise a DWELL fires once after a contact has been continuously inside a
 * zone for that long (re-armed on the next entry). Not thread-safe — call from
 * a single coroutine/looper.
 */
class GeofenceMonitor(
    private val zones: List<GeofenceZone>,
    private val dwellThresholdMs: Long = 0L,
) {
    private class State(var inside: Boolean, var enteredMs: Long, var dwellFired: Boolean)

    private val state = HashMap<Pair<String, String>, State>()

    fun update(contactUid: String, lat: Double, lon: Double, nowMs: Long): List<GeofenceEvent> {
        val events = ArrayList<GeofenceEvent>()
        for (zone in zones) {
            val key = zone.id to contactUid
            val now = zone.contains(lat, lon)
            val s = state[key]
            when {
                now && (s == null || !s.inside) -> {
                    state[key] = State(inside = true, enteredMs = nowMs, dwellFired = false)
                    events += GeofenceEvent(zone.id, contactUid, GeofenceEventType.ENTRY, nowMs)
                }
                !now && s != null && s.inside -> {
                    s.inside = false
                    events += GeofenceEvent(zone.id, contactUid, GeofenceEventType.EXIT, nowMs)
                }
                now && s != null && s.inside && !s.dwellFired &&
                    dwellThresholdMs > 0L && nowMs - s.enteredMs >= dwellThresholdMs -> {
                    s.dwellFired = true
                    events += GeofenceEvent(zone.id, contactUid, GeofenceEventType.DWELL, nowMs)
                }
            }
        }
        return events
    }
}
