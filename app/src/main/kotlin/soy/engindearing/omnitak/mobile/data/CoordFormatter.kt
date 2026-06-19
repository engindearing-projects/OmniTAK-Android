package soy.engindearing.omnitak.mobile.data

import mil.nga.grid.features.Point
import mil.nga.mgrs.MGRS
import mil.nga.mgrs.utm.UTM
import kotlin.math.abs

/**
 * Format a (lat, lon) pair to the operator's chosen [CoordFormat] and
 * format altitude/speed to their chosen [DistanceUnit]. Used by
 * [SelfPositionCard] and any other widget that surfaces a coordinate
 * to the operator. Issue #3 + #4 — settings pickers were no-ops because
 * nothing read the prefs.
 *
 * MGRS / UTM round-trip the NGA `mil.nga.mgrs` library so grid coords
 * stay correct under zone boundaries, polar overrides, and the special
 * cases for Norway / Svalbard.
 */
object CoordFormatter {

    fun position(lat: Double, lon: Double, format: CoordFormat): String = when (format) {
        CoordFormat.LATLON_DECIMAL -> latLonDecimal(lat, lon)
        CoordFormat.LATLON_DMS -> latLonDms(lat, lon)
        CoordFormat.MGRS -> runCatching { MGRS.from(Point.point(lon, lat)).coordinate() }
            .getOrElse { latLonDecimal(lat, lon) }
        CoordFormat.UTM -> runCatching {
            val p = Point.point(lon, lat)
            val u = UTM.from(p)
            // Band letter (C..X, omitting I and O) comes from the MGRS
            // grid-zone designator — UTM proper only carries zone + hemisphere,
            // but the standard "11T 471845mE 5269313mN" readout users expect
            // includes the latitude band. Falls through to hemisphere if the
            // band lookup fails (poles / UPS regions outside MGRS coverage).
            val band: String = runCatching { MGRS.from(p).band.toString() }
                .getOrElse { if (u.hemisphere.name == "NORTH") "N" else "S" }
            "%d%s %.0fmE %.0fmN".format(u.zone, band, u.easting, u.northing)
        }.getOrElse { latLonDecimal(lat, lon) }
        // TWD97 / TM2 zone 121 (Taiwan). Full 7+7 absolute readout matches
        // iOS. Outside the Taiwan zone the converter returns a sentinel, so
        // fall back to plain decimal lat/lon there.
        CoordFormat.TWD97 ->
            if (Twd97Converter.isWithinBounds(lat, lon))
                "TWD97 ${Twd97Converter.formatTwd97(lat, lon, Twd97Converter.DigitMode.FULL7)}"
            else latLonDecimal(lat, lon)

        // British National Grid (OSGB36). Returns null outside Great Britain,
        // so fall back to plain decimal lat/lon there (mirrors TWD97).
        CoordFormat.BNG ->
            Bng.wgs84ToGridRef(lat, lon, digits = 5)?.let { "BNG $it" }
                ?: latLonDecimal(lat, lon)
    }

    fun altitude(meters: Double, unit: DistanceUnit): String = when (unit) {
        DistanceUnit.METRIC -> "%.1f m MSL".format(meters)
        DistanceUnit.IMPERIAL -> "%.0f ft MSL".format(meters * 3.28084)
    }

    fun speed(kmh: Double, unit: DistanceUnit): String = when (unit) {
        DistanceUnit.METRIC -> "%.1f km/h".format(kmh)
        DistanceUnit.IMPERIAL -> "%.1f mph".format(kmh * 0.621371)
    }

    fun accuracy(meters: Int, unit: DistanceUnit): String = when (unit) {
        DistanceUnit.METRIC -> "+/- ${meters}m"
        DistanceUnit.IMPERIAL -> "+/- ${(meters * 3.28084).toInt()}ft"
    }

    private fun latLonDecimal(lat: Double, lon: Double): String =
        "%.5f, %.5f".format(lat, lon)

    private fun latLonDms(lat: Double, lon: Double): String {
        val latDms = toDms(abs(lat))
        val lonDms = toDms(abs(lon))
        val latHemi = if (lat >= 0) "N" else "S"
        val lonHemi = if (lon >= 0) "E" else "W"
        return "${latDms.format()} $latHemi  ${lonDms.format()} $lonHemi"
    }

    private data class Dms(val deg: Int, val min: Int, val sec: Double) {
        fun format(): String = "%d° %02d′ %05.2f″".format(deg, min, sec)
    }

    private fun toDms(decimalDeg: Double): Dms {
        val deg = decimalDeg.toInt()
        val minFull = (decimalDeg - deg) * 60.0
        val min = minFull.toInt()
        val sec = (minFull - min) * 60.0
        return Dms(deg, min, sec)
    }
}
