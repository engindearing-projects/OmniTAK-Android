package soy.engindearing.omnitak.mobile.data

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * TWD97 / TM2 zone 121 (Taiwan) converter — EPSG:3826.
 * Transverse Mercator on the GRS80 ellipsoid.
 *
 * Unlike BNG (which needs a Helmert datum shift to OSGB36), TWD97 is
 * referenced to GRS80/ITRF and is effectively identical to WGS84
 * (towgs84 = 0,0,0,0,0,0,0 — sub-metre difference), so no datum
 * transform is required. We project WGS84 lat/lon straight to/from TM2.
 *
 * Projection parameters (EPSG:3826):
 *   central meridian (lon0) = 121°E
 *   scale factor (k0)       = 0.9999
 *   false easting (E0)      = 250000 m
 *   false northing (N0)     = 0 m
 *   latitude of origin      = 0°
 *
 * Digit input modes (what Gavin asked to compare):
 *   [DigitMode.FULL7] — full absolute TM2 coordinate, 7 digits easting +
 *     7 digits northing at 1 m. Unambiguous, the standard Taiwan survey
 *     ("二度分帶") input, but longer to type.
 *   [DigitMode.GRID5] — MGRS-style truncated reference: the last 5 digits
 *     of easting and northing within the local 100 km cell. Shorter and
 *     matches MGRS muscle memory, but ambiguous outside the local 100 km
 *     cell — parsing requires a reference coordinate (the map centre) to
 *     recover the 100 km prefix.
 *
 * Kotlin port of the verified iOS `TWD97Converter.swift` (Redfearn TM
 * forward/inverse series), minus the datum step.
 */
object Twd97Converter {

    // MARK: - GRS80 Ellipsoid Constants

    private const val a: Double = 6378137.0                               // Semi-major axis
    private const val f: Double = 1.0 / 298.257222101                     // Flattening
    private const val b: Double = 6378137.0 * (1.0 - 1.0 / 298.257222101) // Semi-minor axis
    private const val e2: Double =
        (2.0 / 298.257222101) - (1.0 / 298.257222101) * (1.0 / 298.257222101) // First eccentricity²

    // MARK: - TM2 zone 121 Projection Parameters

    private const val lat0: Double = 0.0                       // True origin latitude (equator)
    private val lon0: Double = 121.0 * Math.PI / 180.0         // Central meridian (121°E)
    private const val N0: Double = 0.0                         // False northing
    private const val E0: Double = 250000.0                    // False easting
    private const val F0: Double = 0.9999                      // Scale factor on central meridian

    // MARK: - Taiwan Bounds (TM2 zone 121: main island + nearshore)
    // Kinmen/Matsu use zone 119 (EPSG:3825) and are intentionally excluded.

    private const val minLat: Double = 21.0
    private const val maxLat: Double = 26.5
    private const val minLon: Double = 119.0
    private const val maxLon: Double = 123.0

    // MARK: - Digit Input Mode

    enum class DigitMode(val digits: Int, val label: String) {
        FULL7(7, "7+7"),   // 7 + 7 absolute digits (1 m)
        GRID5(5, "5+5");   // 5 + 5 truncated digits within local 100 km cell (1 m)
    }

    // MARK: - TWD97 Coordinate Structure

    data class Twd97Coordinate(
        val easting: Double,   // Full absolute easting (metres)
        val northing: Double,  // Full absolute northing (metres)
        val mode: DigitMode,
    ) {
        /** Formatted grid string for the selected digit mode. */
        fun formatted(withSpaces: Boolean = true): String = when (mode) {
            DigitMode.FULL7 -> {
                val e = "%07d".format(Math.round(easting))
                val n = "%07d".format(Math.round(northing))
                if (withSpaces) "$e $n" else "$e$n"
            }
            DigitMode.GRID5 -> {
                val e = (Math.round(easting) % 100000L)
                val n = (Math.round(northing) % 100000L)
                val eStr = "%05d".format(e)
                val nStr = "%05d".format(n)
                if (withSpaces) "$eStr $nStr" else "$eStr$nStr"
            }
        }
    }

    // MARK: - WGS84 Lat/Lon → TWD97 TM2 (forward, Redfearn series)

    /**
     * Project a WGS84 coordinate to absolute TM2 easting/northing.
     * Returns null if outside the Taiwan (zone 121) bounds.
     */
    fun latLonToTwd97(lat: Double, lon: Double, mode: DigitMode = DigitMode.FULL7): Twd97Coordinate? {
        if (!isWithinBounds(lat, lon)) return null
        val (easting, northing) = project(lat, lon)
        return Twd97Coordinate(easting, northing, mode)
    }

    /** Raw forward projection (no bounds check). Exposed for testing/round-trips. */
    fun project(latDeg: Double, lonDeg: Double): Pair<Double, Double> {
        val lat = latDeg * Math.PI / 180.0
        val lon = lonDeg * Math.PI / 180.0

        val sinLat = sin(lat)
        val cosLat = cos(lat)
        val tanLat = tan(lat)

        val n = (a - b) / (a + b)
        val n2 = n * n
        val n3 = n * n * n

        val nu = a * F0 / sqrt(1 - e2 * sinLat * sinLat)
        val rho = a * F0 * (1 - e2) / (1 - e2 * sinLat * sinLat).pow(1.5)
        val eta2 = nu / rho - 1

        val Ma = (1 + n + (5.0 / 4.0) * n2 + (5.0 / 4.0) * n3) * (lat - lat0)
        val Mb = (3 * n + 3 * n2 + (21.0 / 8.0) * n3) * sin(lat - lat0) * cos(lat + lat0)
        val Mc = ((15.0 / 8.0) * n2 + (15.0 / 8.0) * n3) * sin(2 * (lat - lat0)) * cos(2 * (lat + lat0))
        val Md = (35.0 / 24.0) * n3 * sin(3 * (lat - lat0)) * cos(3 * (lat + lat0))
        val M = b * F0 * (Ma - Mb + Mc - Md)

        val I = M + N0
        val II = (nu / 2) * sinLat * cosLat
        val III = (nu / 24) * sinLat * cosLat.pow(3) * (5 - tanLat * tanLat + 9 * eta2)
        val IIIA = (nu / 720) * sinLat * cosLat.pow(5) * (61 - 58 * tanLat * tanLat + tanLat.pow(4))
        val IV = nu * cosLat
        val V = (nu / 6) * cosLat.pow(3) * (nu / rho - tanLat * tanLat)
        val VI = (nu / 120) * cosLat.pow(5) *
            (5 - 18 * tanLat * tanLat + tanLat.pow(4) + 14 * eta2 - 58 * tanLat * tanLat * eta2)

        val dLon = lon - lon0

        val northing = I + II * dLon * dLon + III * dLon.pow(4) + IIIA * dLon.pow(6)
        val easting = E0 + IV * dLon + V * dLon.pow(3) + VI * dLon.pow(5)

        return easting to northing
    }

    // MARK: - TWD97 TM2 → WGS84 Lat/Lon (inverse, Redfearn series)

    /** Convert absolute TM2 easting/northing to WGS84 lat/lon (degrees). */
    fun twd97ToLatLon(easting: Double, northing: Double): Pair<Double, Double> {
        val n = (a - b) / (a + b)
        val n2 = n * n
        val n3 = n * n * n

        var lat = lat0
        var M = 0.0

        // Iterate latitude until the meridional arc matches the northing.
        do {
            lat = ((northing - N0 - M) / (a * F0)) + lat

            val Ma = (1 + n + (5.0 / 4.0) * n2 + (5.0 / 4.0) * n3) * (lat - lat0)
            val Mb = (3 * n + 3 * n2 + (21.0 / 8.0) * n3) * sin(lat - lat0) * cos(lat + lat0)
            val Mc = ((15.0 / 8.0) * n2 + (15.0 / 8.0) * n3) * sin(2 * (lat - lat0)) * cos(2 * (lat + lat0))
            val Md = (35.0 / 24.0) * n3 * sin(3 * (lat - lat0)) * cos(3 * (lat + lat0))
            M = b * F0 * (Ma - Mb + Mc - Md)
        } while (kotlin.math.abs(northing - N0 - M) >= 0.00001)

        val sinLat = sin(lat)
        val cosLat = cos(lat)
        val tanLat = tan(lat)

        val nu = a * F0 / sqrt(1 - e2 * sinLat * sinLat)
        val rho = a * F0 * (1 - e2) / (1 - e2 * sinLat * sinLat).pow(1.5)
        val eta2 = nu / rho - 1

        val tanLat2 = tanLat * tanLat
        val tanLat4 = tanLat2 * tanLat2
        val tanLat6 = tanLat4 * tanLat2

        val VII = tanLat / (2 * rho * nu)
        val VIII = tanLat / (24 * rho * nu.pow(3)) * (5 + 3 * tanLat2 + eta2 - 9 * tanLat2 * eta2)
        val IX = tanLat / (720 * rho * nu.pow(5)) * (61 + 90 * tanLat2 + 45 * tanLat4)
        val X = 1.0 / (cosLat * nu)
        val XI = 1.0 / (cosLat * 6 * nu.pow(3)) * (nu / rho + 2 * tanLat2)
        val XII = 1.0 / (cosLat * 120 * nu.pow(5)) * (5 + 28 * tanLat2 + 24 * tanLat4)
        val XIIA = 1.0 / (cosLat * 5040 * nu.pow(7)) * (61 + 662 * tanLat2 + 1320 * tanLat4 + 720 * tanLat6)

        val dE = easting - E0

        val latRad = lat - VII * dE * dE + VIII * dE.pow(4) - IX * dE.pow(6)
        val lonRad = lon0 + X * dE - XI * dE.pow(3) + XII * dE.pow(5) - XIIA * dE.pow(7)

        return (latRad * 180.0 / Math.PI) to (lonRad * 180.0 / Math.PI)
    }

    // MARK: - Parsing typed input

    /**
     * Parse typed easting/northing strings into a (lat, lon) coordinate.
     * - FULL7: strings are full absolute metres (7 digits each).
     * - GRID5: strings are truncated to the local 100 km cell; [refLat]/[refLon]
     *   (typically the current map centre) recover the 100 km prefix. If no
     *   reference is supplied, the centre of Taiwan is assumed (23.7, 121.0).
     *
     * Returns null on un-parseable input.
     */
    fun parse(
        eastingText: String,
        northingText: String,
        mode: DigitMode,
        refLat: Double? = null,
        refLon: Double? = null,
    ): Pair<Double, Double>? {
        val eVal = eastingText.trim().toDoubleOrNull() ?: return null
        val nVal = northingText.trim().toDoubleOrNull() ?: return null
        if (eVal < 0 || nVal < 0) return null

        val easting: Double
        val northing: Double

        when (mode) {
            DigitMode.FULL7 -> {
                easting = eVal
                northing = nVal
            }
            DigitMode.GRID5 -> {
                // Recover the 100 km prefix from the reference coordinate.
                val rLat = refLat ?: 23.7
                val rLon = refLon ?: 121.0
                val (refE, refN) = project(rLat, rLon)
                val e100 = floor(refE / 100000.0) * 100000.0
                val n100 = floor(refN / 100000.0) * 100000.0
                easting = e100 + eVal.rem(100000.0)
                northing = n100 + nVal.rem(100000.0)
            }
        }

        val (lat, lon) = twd97ToLatLon(easting, northing)
        if (!lat.isFinite() || !lon.isFinite()) return null
        return lat to lon
    }

    // MARK: - Formatting helpers

    fun formatTwd97(
        lat: Double,
        lon: Double,
        mode: DigitMode = DigitMode.FULL7,
        withSpaces: Boolean = true,
    ): String {
        val twd = latLonToTwd97(lat, lon, mode) ?: return "Out of TWD97 bounds"
        return twd.formatted(withSpaces)
    }

    // MARK: - Validation

    fun isWithinBounds(lat: Double, lon: Double): Boolean =
        lat in minLat..maxLat && lon in minLon..maxLon
}
