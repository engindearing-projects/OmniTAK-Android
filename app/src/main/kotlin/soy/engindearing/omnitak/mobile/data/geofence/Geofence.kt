package soy.engindearing.omnitak.mobile.data.geofence

import kotlinx.serialization.Serializable
import soy.engindearing.omnitak.mobile.data.GeoMath
import soy.engindearing.omnitak.mobile.data.rangebearing.RangeBearing

/** The shapes a geofence can have. */
@Serializable
enum class GeofenceType { CIRCLE, POLYGON }

/**
 * The colours an operator can give a geofence. [hex] is "#RRGGBB" and is what both map engines
 * are given, so a geofence reads the same on the 2D map and on the globe. The values are the
 * ones the iOS app uses for the same names.
 */
@Serializable
enum class GeofenceColor(val hex: String) {
    RED("#FF3B30"),
    BLUE("#007AFF"),
    GREEN("#34C759"),
    YELLOW("#FFCC00"),
    ORANGE("#FF9500"),
    PURPLE("#AF52DE"),
    CYAN("#5AC8FA"),
}

/**
 * A geofence: an area on the map that tells the operator when they, or a teammate, enter it,
 * leave it, or stay in it.
 *
 * Flat on purpose (a circle has a centre and a radius, a polygon has its corners, the other
 * fields stay empty) so the saved file stays simple to read and an older or newer file with
 * fields this build does not know still loads. The same fields, with the same meaning and the
 * same defaults, as the iOS app.
 *
 * Everything that decides what happens is in here as plain data; [GeofenceMonitor] does the
 * deciding.
 */
@Serializable
data class Geofence(
    val id: String,
    val name: String,
    val type: GeofenceType,
    val color: GeofenceColor = GeofenceColor.YELLOW,
    /** Switched on. A switched-off geofence is not drawn and watches nobody. */
    val isActive: Boolean = true,
    /** Watches the operator's own position. */
    val watchSelf: Boolean = true,
    /** Watches the teammates' positions (every contact that reports one, over a server or a mesh radio). */
    val watchContacts: Boolean = true,
    val alertOnEntry: Boolean = true,
    val alertOnExit: Boolean = true,
    /** Alert once when someone has been inside for this many minutes. 0 is off. */
    val dwellMinutes: Int = 0,
    // Circle
    val centerLat: Double? = null,
    val centerLon: Double? = null,
    val radiusMeters: Double? = null,
    // Polygon
    val points: List<LatLon>? = null,
    val createdAtMs: Long = 0L,
) {
    /**
     * Whether there is a shape to be inside of: a circle with a centre and a radius above zero,
     * or a polygon with at least three corners. A geofence without one is never drawn and never
     * contains anything.
     */
    val hasUsableShape: Boolean
        get() = when (type) {
            GeofenceType.CIRCLE -> {
                val lat = centerLat
                val lon = centerLon
                val radius = radiusMeters
                lat != null && lon != null && radius != null &&
                    isValidCoordinate(lat, lon) && radius.isFinite() && radius > 0.0
            }
            GeofenceType.POLYGON -> {
                val corners = points
                corners != null && corners.size >= 3 && corners.all { isValidCoordinate(it.lat, it.lon) }
            }
        }

    /** The zone to test positions against, or null when there is no usable shape. */
    val zone: GeofenceZone?
        get() {
            if (!hasUsableShape) return null
            return when (type) {
                GeofenceType.CIRCLE -> CircleZone(id, centerLat!!, centerLon!!, radiusMeters!!)
                GeofenceType.POLYGON -> PolygonZone(id, points!!)
            }
        }

    /** Whether a position is inside the shape. A position that is not a coordinate is never inside. */
    fun contains(lat: Double, lon: Double): Boolean {
        if (!isValidCoordinate(lat, lon)) return false
        return zone?.contains(lat, lon) ?: false
    }

    /** Whether this geofence watches the operator ([isOperator]) or a teammate. */
    fun watches(isOperator: Boolean): Boolean = if (isOperator) watchSelf else watchContacts

    /** The dwell time in ms, 0 when the dwell alert is off. */
    val dwellMs: Long get() = dwellMinutes.coerceAtLeast(0) * 60_000L

    /**
     * Where its name is written, as the iOS app writes it: on the north edge of a circle (so the operator's
     * own marker, which sits on the middle of the zone they are in, never covers it), at the mean of a
     * polygon's corners. The text sits above the point. Null when there are no corners or no centre. A circle
     * with no size has no edge, so its name goes on its centre. ("Show on map" does not go here: it frames
     * the whole shape, see [GeofenceFraming].)
     */
    val labelPoint: LatLon?
        get() = when (type) {
            GeofenceType.CIRCLE -> {
                val lat = centerLat
                val lon = centerLon
                val radius = radiusMeters
                if (lat == null || lon == null) {
                    null
                } else if (radius != null && radius.isFinite() && radius > 0.0) {
                    // The first point of the ring the zone is drawn with is due north of the centre.
                    val north = RangeBearing.ringPoints(lat, lon, radius, 4).first()
                    LatLon(north[0], north[1])
                } else {
                    LatLon(lat, lon)
                }
            }
            GeofenceType.POLYGON -> {
                val corners = points
                if (corners.isNullOrEmpty()) {
                    null
                } else {
                    // The mean of the longitudes after moving them near the first corner, so corners either side of the
                    // antimeridian (179.9 and -179.9) average to 180, not to 0 on the far side of the planet; then back
                    // onto the map.
                    val near = corners.first().lon
                    val lon = corners.sumOf { GeoMath.unwrapLongitude(it.lon, near) } / corners.size
                    LatLon(corners.sumOf { it.lat } / corners.size, if (lon > 180.0) lon - 360.0 else if (lon < -180.0) lon + 360.0 else lon)
                }
            }
        }

    companion object {
        /** A latitude and a longitude that exist. */
        fun isValidCoordinate(lat: Double, lon: Double): Boolean =
            lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0
    }
}

/**
 * One thing that happened, kept in the history the geofence list shows. Written from what the
 * monitor reported ([GeofenceTransition]); the sentence is built when it is shown, so it reads in
 * the language the operator has then.
 */
@Serializable
data class GeofenceEvent(
    val id: String,
    val geofenceId: String,
    val geofenceName: String,
    val type: GeofenceEventType,
    val atMs: Long,
    val lat: Double,
    val lon: Double,
    val subjectId: String,
    /** The callsign of who it was, null for an event that did not say. */
    val subjectName: String? = null,
    val byOperator: Boolean = false,
    /** How long they had been inside: set for an exit and for a dwell. */
    val stayMs: Long? = null,
)
