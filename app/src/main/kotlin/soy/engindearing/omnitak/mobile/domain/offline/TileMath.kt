package soy.engindearing.omnitak.mobile.domain.offline

import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Slippy-map math. Pure helpers split out from the store so unit
 * tests run on the JVM without standing up Android. Matches the iOS
 * TileCalculator / TileCalculationHelper functions byte-for-byte so a
 * region drawn on Android and a region drawn on iOS cover the same
 * physical tiles.
 */
object TileMath {

    /** OpenStreetMap "slippy" tile XY for a lat/lon at the given
     *  zoom. Standard XYZ scheme — top-left is (0,0). */
    fun tileXY(lat: Double, lon: Double, zoom: Int): Pair<Int, Int> {
        val n = 2.0.pow(zoom)
        val x = ((lon + 180.0) / 360.0 * n).toInt()
        val latRad = lat * PI / 180.0
        val y = ((1.0 - asinh(tan(latRad)) / PI) / 2.0 * n).toInt()
        return x to y
    }

    /** Inverse of [tileXY] — returns the NW corner of the given
     *  tile. Used by the cache interceptor when a request URL is
     *  inverted back to a lat/lon for region containment checks. */
    fun tileNorthWest(x: Int, y: Int, zoom: Int): Pair<Double, Double> {
        val n = 2.0.pow(zoom)
        val lon = x / n * 360.0 - 180.0
        val latRad = atan(sinh(PI * (1.0 - 2.0 * y / n)))
        val lat = latRad * 180.0 / PI
        return lat to lon
    }

    /** All tiles covering a bbox at one zoom level. Caller-supplied
     *  bbox uses north/south/east/west (degrees). */
    fun tilesInBbox(
        north: Double,
        south: Double,
        east: Double,
        west: Double,
        zoom: Int,
    ): List<Triple<Int, Int, Int>> {
        // Top-left of the bbox is (north, west); bottom-right is (south, east).
        val (xMin, yMin) = tileXY(north, west, zoom)
        val (xMax, yMax) = tileXY(south, east, zoom)
        val xStart = minOf(xMin, xMax)
        val xEnd = maxOf(xMin, xMax)
        val yStart = minOf(yMin, yMax)
        val yEnd = maxOf(yMin, yMax)
        val out = ArrayList<Triple<Int, Int, Int>>((xEnd - xStart + 1) * (yEnd - yStart + 1))
        for (x in xStart..xEnd) {
            for (y in yStart..yEnd) {
                out.add(Triple(x, y, zoom))
            }
        }
        return out
    }

    /** Tile count across a zoom range. Cheaper than enumerating tiles. */
    fun countTiles(
        north: Double,
        south: Double,
        east: Double,
        west: Double,
        zoomMin: Int,
        zoomMax: Int,
    ): Int {
        var total = 0
        for (z in zoomMin..zoomMax) {
            val (xMin, yMin) = tileXY(north, west, z)
            val (xMax, yMax) = tileXY(south, east, z)
            val xCount = (maxOf(xMin, xMax) - minOf(xMin, xMax)) + 1
            val yCount = (maxOf(yMin, yMax) - minOf(yMin, yMax)) + 1
            total += xCount * yCount
        }
        return total
    }

    /** Convert a circular SAR area-of-interest (centre + radius
     *  metres) into a bbox. Approximates as a rectangle inscribing
     *  the disc; loose at high latitudes but the operator's radius
     *  picker tops out at ~50 km so it stays good enough. */
    fun bboxAroundPoint(
        lat: Double,
        lon: Double,
        radiusM: Double,
    ): Bbox {
        val latDelta = radiusM / 111_320.0 // meters per degree latitude (avg)
        val lonDelta = radiusM / (111_320.0 * kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(1e-6))
        return Bbox(
            north = lat + latDelta,
            south = lat - latDelta,
            east = lon + lonDelta,
            west = lon - lonDelta,
        )
    }

    /** Substitute {z}/{x}/{y} (or ATAK-style {\$z}/{\$x}/{\$y}) in an
     *  XYZ URL template. Returns null if the template doesn't have
     *  enough placeholders to be a real tile URL. */
    fun expandTemplate(template: String, x: Int, y: Int, z: Int): String? {
        if (template.isBlank()) return null
        val expanded = template
            .replace("{z}", z.toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())
            .replace("{\$z}", z.toString())
            .replace("{\$x}", x.toString())
            .replace("{\$y}", y.toString())
        // After substitution there should be no remaining {placeholder}
        // pattern that could indicate a malformed template.
        return if (expanded.contains("{")) null else expanded
    }

    /** Parse the (z, x, y) triple back out of an XYZ tile URL. Used
     *  by [OfflineTileCache] to decide whether a given HTTP request
     *  is a tile fetch (and therefore a cache candidate). */
    private val XYZ_PATTERN = Regex("""/(\d{1,2})/(\d+)/(\d+)(?:\.[a-zA-Z0-9]+)?(?:\?.*)?$""")

    fun parseTileFromUrl(url: String): Triple<Int, Int, Int>? {
        val m = XYZ_PATTERN.find(url) ?: return null
        val z = m.groupValues[1].toIntOrNull() ?: return null
        val x = m.groupValues[2].toIntOrNull() ?: return null
        val y = m.groupValues[3].toIntOrNull() ?: return null
        if (z !in 0..24) return null
        return Triple(z, x, y)
    }
}

/** Geographic bounding box in degrees. Held as four scalars rather
 *  than a Pair-of-Pairs so the JSON shape is obvious. */
data class Bbox(
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double,
) {
    fun contains(lat: Double, lon: Double): Boolean =
        lat in south..north && lon in west..east
}
