package soy.engindearing.omnitak.mobile.data.rangebearing

import soy.engindearing.omnitak.mobile.data.GeoMath
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Range & Bearing computation (#152, ported from iOS `RangeBearingService`).
 *
 * Pure great-circle math with no Android dependency, so it unit-tests directly.
 * The Android layer supplies [declinationDeg] from
 * `android.hardware.GeomagneticField` and [gridConvergenceDeg] from the active
 * map grid; both default to 0.0 (i.e. magnetic/grid == true bearing) so a caller
 * with no magnetic model still gets correct distance + true bearing.
 */
object RangeBearing {

    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Normalize any degree value into [0, 360). */
    fun norm360(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

    /**
     * Distance + true/magnetic/grid bearing + back-azimuth from origin to
     * destination. Declination and grid convergence are east-positive
     * (magnetic = true − declination; grid = true − convergence), matching the
     * iOS service and standard land-nav convention.
     */
    fun compute(
        originLat: Double,
        originLon: Double,
        destLat: Double,
        destLon: Double,
        declinationDeg: Double = 0.0,
        gridConvergenceDeg: Double = 0.0,
    ): RangeBearingResult {
        val tru = GeoMath.bearingDegrees(originLat, originLon, destLat, destLon)
        return RangeBearingResult(
            distanceMeters = GeoMath.haversineMeters(originLat, originLon, destLat, destLon),
            trueBearing = norm360(tru),
            magneticBearing = norm360(tru - declinationDeg),
            backAzimuth = norm360(tru + 180.0),
            gridBearing = norm360(tru - gridConvergenceDeg),
        )
    }

    /**
     * Geodesic points (each `[lat, lon]`) approximating a circle of
     * [radiusMeters] around the center, walking [segments] equal bearings. The
     * ring is closed (first point repeated at the end) so it can be drawn as a
     * single polyline. Used for tactical range rings.
     */
    fun ringPoints(
        centerLat: Double,
        centerLon: Double,
        radiusMeters: Double,
        segments: Int = 64,
    ): List<DoubleArray> {
        val n = segments.coerceAtLeast(3)
        val angDist = radiusMeters / EARTH_RADIUS_M
        val lat1 = Math.toRadians(centerLat)
        val lon1 = Math.toRadians(centerLon)
        val out = ArrayList<DoubleArray>(n + 1)
        for (i in 0..n) {
            val brng = Math.toRadians(360.0 * i / n)
            val lat2 = asin(sin(lat1) * cos(angDist) + cos(lat1) * sin(angDist) * cos(brng))
            val lon2 = lon1 + atan2(
                sin(brng) * sin(angDist) * cos(lat1),
                cos(angDist) - sin(lat1) * sin(lat2),
            )
            out.add(doubleArrayOf(Math.toDegrees(lat2), normLon(Math.toDegrees(lon2))))
        }
        return out
    }

    /** Concentric rings at [spacingMeters] × 1..[count] (inner → outer). */
    fun rings(
        centerLat: Double,
        centerLon: Double,
        spacingMeters: Double,
        count: Int,
        segments: Int = 64,
    ): List<List<DoubleArray>> =
        (1..count.coerceAtLeast(1)).map { ring ->
            ringPoints(centerLat, centerLon, spacingMeters * ring, segments)
        }

    /** Fold longitude back into [-180, 180). */
    private fun normLon(lon: Double): Double = ((lon + 540.0) % 360.0) - 180.0
}

/** Resolved distance + bearings for one Range & Bearing line. Angles in [0,360). */
data class RangeBearingResult(
    val distanceMeters: Double,
    val trueBearing: Double,
    val magneticBearing: Double,
    val backAzimuth: Double,
    val gridBearing: Double,
)
