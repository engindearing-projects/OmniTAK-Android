package soy.engindearing.omnitak.mobile.data.geofence

import soy.engindearing.omnitak.mobile.data.GeoMath
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * A box on the ground, in degrees. A box across the antimeridian is unwrapped: its [west] can be under
 * -180 or its [east] over 180, so [east] is always the larger.
 */
data class GeoBounds(val south: Double, val west: Double, val north: Double, val east: Double)

/**
 * Where the camera goes to show a box: the middle of it, a web-mercator [zoom] (the zoom the 2D map
 * uses, a world 512 dp across at zoom 0), and the ground the width of the view then spans, for the
 * globe, which is told how much ground to show rather than a zoom.
 */
data class FramedCamera(val lat: Double, val lon: Double, val zoom: Double, val groundWidthMeters: Double)

/**
 * How "Show on map" frames a geofence on both map engines (#155): the whole zone, with room around it,
 * seen from straight above. It used to centre on the zone at a fixed zoom 14 (about 3 km across a phone),
 * so a zone larger than that did not fit and its edge was never seen.
 *
 * Pure, with no map or screen in it, so it is tested on the JVM: the engines give it the size of their view
 * in dp and get a camera back.
 */
object GeofenceFraming {

    /** The 2D map's world is this many dp across at zoom 0. */
    const val WORLD_DP_AT_ZOOM_0 = 512.0

    /** The equator's length in metres on the WGS84 sphere the map's mercator uses. */
    const val EARTH_CIRCUMFERENCE_M = 40_075_016.686

    /** The sphere the zone's edge is measured on: the one [soy.engindearing.omnitak.mobile.data.rangebearing.RangeBearing] and the monitor use. */
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Room kept clear on each side of the zone, in dp: under the status strip and clear of the controls. */
    const val PADDING_DP = 64.0

    /** Never closer than this many metres across the width of the view, so a small zone still shows the streets around it. */
    const val MIN_VIEW_METERS = 600.0

    /** The closest zoom, whatever the zone. The map clamps it again to what its tiles allow. */
    const val MAX_ZOOM = 18.0

    /** A view that has not been laid out yet (no size) is framed as a small phone. */
    private const val NOMINAL_VIEW_WIDTH_DP = 360.0
    private const val NOMINAL_VIEW_HEIGHT_DP = 640.0

    /** Mercator cannot show the poles; this is where the web map ends. */
    private const val MAX_MERCATOR_LAT = 85.0511287798

    /** `cos(lat)` is kept off zero near the poles. */
    private const val MIN_COS_LAT = 0.01

    /** The smallest a view can be after its padding is taken off. */
    private const val MIN_FIT_DP = 1.0

    /** The smallest fraction of the world a zone is taken to span, so a zone of one point has a zoom. */
    private const val TINY_FRACTION = 1e-12

    /**
     * The bounds of [geofence]'s shape: a circle's four extremes on the ground (not a box of equal
     * degrees: a degree of longitude is shorter the further from the equator), a polygon's corners. Null
     * when there is no shape to show. A switched-off geofence has bounds too: its place is still worth showing.
     */
    fun boundsOf(geofence: Geofence): GeoBounds? {
        if (!geofence.hasUsableShape) return null
        return when (geofence.type) {
            GeofenceType.CIRCLE -> circleBounds(geofence.centerLat!!, geofence.centerLon!!, geofence.radiusMeters!!)
            GeofenceType.POLYGON -> geofence.points!!.let { corners ->
                // Corners either side of the antimeridian get longitudes that are close, so the box over them is a
                // small one there and not one that spans the world.
                val lons = corners.map { GeoMath.unwrapLongitude(it.lon, near = corners.first().lon) }
                GeoBounds(
                    south = corners.minOf { it.lat },
                    west = lons.min(),
                    north = corners.maxOf { it.lat },
                    east = lons.max(),
                )
            }
        }
    }

    private fun circleBounds(lat: Double, lon: Double, radiusMeters: Double): GeoBounds {
        val angular = (radiusMeters / EARTH_RADIUS_M).coerceAtMost(PI)
        val angularDeg = Math.toDegrees(angular)
        val phi = Math.toRadians(lat)
        // A circle that holds a pole has no east or west: it spans every longitude.
        if (phi + angular >= PI / 2 || phi - angular <= -PI / 2) {
            return GeoBounds(max(lat - angularDeg, -90.0), -180.0, min(lat + angularDeg, 90.0), 180.0)
        }
        // The furthest east and west a circle of angular radius d around latitude phi reaches: asin(sin d / cos phi).
        val reach = Math.toDegrees(asin(sin(angular) / cos(phi)))
        return GeoBounds(lat - angularDeg, lon - reach, lat + angularDeg, lon + reach)
    }

    /**
     * The camera that shows all of [bounds] in a view [viewWidthDp] x [viewHeightDp] dp, with [paddingDp]
     * of room on every side (less on a very small view: never more than a quarter of its shorter side).
     * The zoom is the closest at which the bounds fit across and down, and no closer than [minViewMeters]
     * across the view or [maxZoom]. It is centred on the middle of the bounds, so a zone with its corners
     * off to one side is still all in view.
     */
    fun cameraFor(
        bounds: GeoBounds,
        viewWidthDp: Double,
        viewHeightDp: Double,
        paddingDp: Double = PADDING_DP,
        minViewMeters: Double = MIN_VIEW_METERS,
        maxZoom: Double = MAX_ZOOM,
    ): FramedCamera {
        val width = if (viewWidthDp.isFinite() && viewWidthDp > 0.0) viewWidthDp else NOMINAL_VIEW_WIDTH_DP
        val height = if (viewHeightDp.isFinite() && viewHeightDp > 0.0) viewHeightDp else NOMINAL_VIEW_HEIGHT_DP
        val padding = min(paddingDp.coerceAtLeast(0.0), min(width, height) / 4.0)

        val northY = mercatorY(bounds.north)
        val southY = mercatorY(bounds.south)
        val centerLat = inverseMercatorY((northY + southY) / 2)
        val centerLon = wrapLongitude((bounds.west + bounds.east) / 2)

        // What fraction of the world's width and height the bounds take, and so what zoom makes that
        // fraction as large as the room there is.
        val lonFraction = ((bounds.east - bounds.west) / 360.0).coerceAtLeast(TINY_FRACTION)
        val latFraction = ((northY - southY) / (2 * PI)).coerceAtLeast(TINY_FRACTION)
        val fitsAcross = log2((width - 2 * padding).coerceAtLeast(MIN_FIT_DP) / (WORLD_DP_AT_ZOOM_0 * lonFraction))
        val fitsDown = log2((height - 2 * padding).coerceAtLeast(MIN_FIT_DP) / (WORLD_DP_AT_ZOOM_0 * latFraction))

        val metersPerDpAtZoom0 = EARTH_CIRCUMFERENCE_M * max(cos(Math.toRadians(centerLat)), MIN_COS_LAT) / WORLD_DP_AT_ZOOM_0
        val closestForStreets = if (minViewMeters > 0.0) log2(width * metersPerDpAtZoom0 / minViewMeters) else Double.POSITIVE_INFINITY

        val zoom = minOf(fitsAcross, fitsDown, closestForStreets, maxZoom).coerceAtLeast(0.0)
        return FramedCamera(centerLat, centerLon, zoom, width * metersPerDpAtZoom0 / 2.0.pow(zoom))
    }

    private fun mercatorY(latDeg: Double): Double =
        ln(tan(PI / 4 + Math.toRadians(latDeg.coerceIn(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT)) / 2))

    private fun inverseMercatorY(y: Double): Double = Math.toDegrees(2 * atan(exp(y)) - PI / 2)

    /** Folds a longitude back into [-180, 180). */
    private fun wrapLongitude(lon: Double): Double = ((lon + 540.0) % 360.0) - 180.0
}
