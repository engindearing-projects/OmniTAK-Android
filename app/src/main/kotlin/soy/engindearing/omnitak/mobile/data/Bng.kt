package soy.engindearing.omnitak.mobile.data

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * British National Grid / OSGB36 conversion (#157, ported from iOS
 * `BNGConverter`): WGS84 -> OSGB36 (Helmert 7-parameter datum shift) -> easting
 * /northing (Transverse Mercator on the Airy 1830 ellipsoid) -> grid reference.
 *
 * The Helmert shift is ~5 m accurate (good enough for tactical readouts; OSTN15
 * would be needed for survey-grade). Pure math, so it unit-tests against the
 * Ordnance Survey worked example.
 */
object Bng {

    // Airy 1830 (OSGB36)
    private const val A_OSGB = 6377563.396
    private const val B_OSGB = 6356256.909
    private const val E2_OSGB = 0.00667054015

    // WGS84
    private const val A_WGS = 6378137.0
    private const val E2_WGS = 0.00669437999014

    // BNG Transverse Mercator parameters
    private val LAT0 = Math.toRadians(49.0)
    private val LON0 = Math.toRadians(-2.0)
    private const val N0 = -100000.0
    private const val E0 = 400000.0
    private const val F0 = 0.9996012717

    // Helmert WGS84 -> OSGB36
    private const val TX = -446.448
    private const val TY = 125.157
    private const val TZ = -542.060
    private val RX = Math.toRadians(-0.1502 / 3600.0)
    private val RY = Math.toRadians(-0.2470 / 3600.0)
    private val RZ = Math.toRadians(-0.8421 / 3600.0)
    private const val S = 20.4894 / 1_000_000.0

    // 100 km grid squares indexed [n100k][e100k] over the GB grid.
    private val GRID = arrayOf(
        arrayOf("SV", "SW", "SX", "SY", "SZ", "TV", "TW"),
        arrayOf("SQ", "SR", "SS", "ST", "SU", "TQ", "TR"),
        arrayOf("SL", "SM", "SN", "SO", "SP", "TL", "TM"),
        arrayOf("SF", "SG", "SH", "SJ", "SK", "TF", "TG"),
        arrayOf("SA", "SB", "SC", "SD", "SE", "TA", "TB"),
        arrayOf("NV", "NW", "NX", "NY", "NZ", "OV", "OW"),
        arrayOf("NQ", "NR", "NS", "NT", "NU", "OQ", "OR"),
        arrayOf("NL", "NM", "NN", "NO", "NP", "OL", "OM"),
        arrayOf("NF", "NG", "NH", "NJ", "NK", "OF", "OG"),
        arrayOf("NA", "NB", "NC", "ND", "NE", "OA", "OB"),
        arrayOf("HV", "HW", "HX", "HY", "HZ", "JV", "JW"),
        arrayOf("HQ", "HR", "HS", "HT", "HU", "JQ", "JR"),
        arrayOf("HL", "HM", "HN", "HO", "HP", "JL", "JM"),
    )

    /** WGS84 lat/lon -> OSGB36 lat/lon (degrees) via the Helmert transform. */
    fun wgs84ToOsgb36(latDeg: Double, lonDeg: Double): DoubleArray {
        val lat = Math.toRadians(latDeg)
        val lon = Math.toRadians(lonDeg)
        val sinLat = sin(lat)
        val cosLat = cos(lat)
        val nu = A_WGS / sqrt(1 - E2_WGS * sinLat * sinLat)
        val x1 = nu * cosLat * cos(lon)
        val y1 = nu * cosLat * sin(lon)
        val z1 = nu * (1 - E2_WGS) * sinLat
        val x2 = TX + (1 + S) * x1 + (-RZ) * y1 + RY * z1
        val y2 = TY + RZ * x1 + (1 + S) * y1 + (-RX) * z1
        val z2 = TZ + (-RY) * x1 + RX * y1 + (1 + S) * z1
        val p = sqrt(x2 * x2 + y2 * y2)
        var lat2 = atan2(z2, p * (1 - E2_OSGB))
        repeat(10) {
            val sinLat2 = sin(lat2)
            val nu2 = A_OSGB / sqrt(1 - E2_OSGB * sinLat2 * sinLat2)
            lat2 = atan2(z2 + E2_OSGB * nu2 * sinLat2, p)
        }
        return doubleArrayOf(Math.toDegrees(lat2), Math.toDegrees(atan2(y2, x2)))
    }

    /** OSGB36 lat/lon (degrees) -> BNG easting/northing (meters). */
    fun osgb36ToEastingNorthing(latDeg: Double, lonDeg: Double): DoubleArray {
        val lat = Math.toRadians(latDeg)
        val lon = Math.toRadians(lonDeg)
        val sinLat = sin(lat)
        val cosLat = cos(lat)
        val tanLat = tan(lat)
        val n = (A_OSGB - B_OSGB) / (A_OSGB + B_OSGB)
        val n2 = n * n
        val n3 = n * n * n
        val nu = A_OSGB * F0 / sqrt(1 - E2_OSGB * sinLat * sinLat)
        val rho = A_OSGB * F0 * (1 - E2_OSGB) / (1 - E2_OSGB * sinLat * sinLat).pow(1.5)
        val eta2 = nu / rho - 1
        val ma = (1 + n + 5.0 / 4.0 * n2 + 5.0 / 4.0 * n3) * (lat - LAT0)
        val mb = (3 * n + 3 * n2 + 21.0 / 8.0 * n3) * sin(lat - LAT0) * cos(lat + LAT0)
        val mc = (15.0 / 8.0 * n2 + 15.0 / 8.0 * n3) * sin(2 * (lat - LAT0)) * cos(2 * (lat + LAT0))
        val md = 35.0 / 24.0 * n3 * sin(3 * (lat - LAT0)) * cos(3 * (lat + LAT0))
        val m = B_OSGB * F0 * (ma - mb + mc - md)
        val i = m + N0
        val ii = nu / 2 * sinLat * cosLat
        val iii = nu / 24 * sinLat * cosLat.pow(3) * (5 - tanLat * tanLat + 9 * eta2)
        val iiia = nu / 720 * sinLat * cosLat.pow(5) * (61 - 58 * tanLat * tanLat + tanLat.pow(4))
        val iv = nu * cosLat
        val v = nu / 6 * cosLat.pow(3) * (nu / rho - tanLat * tanLat)
        val vi = nu / 120 * cosLat.pow(5) *
            (5 - 18 * tanLat * tanLat + tanLat.pow(4) + 14 * eta2 - 58 * tanLat * tanLat * eta2)
        val dLon = lon - LON0
        val northing = i + ii * dLon * dLon + iii * dLon.pow(4) + iiia * dLon.pow(6)
        val easting = E0 + iv * dLon + v * dLon.pow(3) + vi * dLon.pow(5)
        return doubleArrayOf(easting, northing)
    }

    /** 2-letter 100 km grid square for an easting/northing, or null if off-grid. */
    fun gridSquare(easting: Double, northing: Double): String? {
        val e100k = floor(easting / 100000.0).toInt()
        val n100k = floor(northing / 100000.0).toInt()
        if (n100k < 0 || n100k >= GRID.size || e100k < 0 || e100k >= GRID[0].size) return null
        return GRID[n100k][e100k]
    }

    /**
     * WGS84 lat/lon -> a BNG grid reference, e.g. `TG 51409 13177`. [digits] is
     * the digit count per axis (1..5; 5 = 1 m). Returns null outside Great
     * Britain's grid.
     */
    fun wgs84ToGridRef(
        latDeg: Double,
        lonDeg: Double,
        digits: Int = 5,
        withSpaces: Boolean = true,
    ): String? {
        if (latDeg < 49.0 || latDeg > 61.0 || lonDeg < -9.0 || lonDeg > 2.0) return null
        val osgb = wgs84ToOsgb36(latDeg, lonDeg)
        val en = osgb36ToEastingNorthing(osgb[0], osgb[1])
        val e = en[0]
        val n = en[1]
        if (e < 0 || e > 700000 || n < 0 || n > 1300000) return null
        val square = gridSquare(e, n) ?: return null
        val d = digits.coerceIn(1, 5)
        val divisor = 10.0.pow(5 - d)
        val eStr = ((e % 100000.0) / divisor).toInt().toString().padStart(d, '0')
        val nStr = ((n % 100000.0) / divisor).toInt().toString().padStart(d, '0')
        return if (withSpaces) "$square $eStr $nStr" else "$square$eStr$nStr"
    }
}
