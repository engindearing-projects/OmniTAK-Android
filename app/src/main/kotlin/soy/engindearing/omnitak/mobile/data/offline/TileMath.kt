package soy.engindearing.omnitak.mobile.data.offline

import soy.engindearing.omnitak.mobile.data.MapProvider
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.tan

/** A single Web-Mercator slippy-map tile. */
data class Tile(val z: Int, val x: Int, val y: Int)

/**
 * Geographic bounding box in WGS84 degrees. Corners may arrive in any
 * order (a drag rectangle can be top-left→bottom-right or reversed); the
 * tile math normalizes via [normN]/[normS]/[normE]/[normW].
 */
data class BoundingBox(
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double,
) {
    val normN: Double get() = maxOf(north, south)
    val normS: Double get() = minOf(north, south)
    val normE: Double get() = maxOf(east, west)
    val normW: Double get() = minOf(east, west)
}

/**
 * Pure Web-Mercator (EPSG:3857 / XYZ slippy-map) tile math for offline
 * region download (#120). No Android dependencies so it unit-tests as
 * plain JVM code.
 *
 * NOTE: this is the standard Web-Mercator scheme the basemaps use (OSM,
 * OpenTopoMap, ESRI World Imagery, custom WMTS/XYZ). It is deliberately
 * separate from [soy.engindearing.omnitak.mobile.data.uas.TerrainSampler],
 * which speaks the *WGS84* 2·2^z grid TAK Terrain publishes — a different
 * projection that would plot the wrong tiles for these basemaps.
 *
 * Formulae per the OSM slippy-map tilenames spec:
 *   x = floor((lon + 180) / 360 · 2^z)
 *   y = floor((1 − asinh(tan(lat)) / π) / 2 · 2^z)
 */
object TileMath {

    /** Hard cap on download zoom. z19 is the practical max for raster XYZ
     *  sources (OSM/Topo stop there); going deeper explodes tile counts. */
    const val MAX_ZOOM = 19

    /** Web-Mercator latitude clamp — the projection is undefined past this. */
    private const val MAX_LAT = 85.05112878
    private const val MIN_LAT = -85.05112878

    /** Average compressed bytes per 256px raster tile, used for the
     *  pre-download size estimate. ~22 KB is a realistic mean across mixed
     *  PNG/JPEG basemap tiles (empty ocean tiles are tiny, dense urban PNGs
     *  run 50–100 KB). The estimate is advisory, not a guarantee. */
    const val AVG_TILE_BYTES = 22_000L

    // --- lon/lat/z -> x/y -------------------------------------------------

    fun lonToTileX(lonDeg: Double, z: Int): Int {
        val zc = z.coerceIn(0, MAX_ZOOM)
        val n = 1 shl zc
        val x = Math.floor((lonDeg + 180.0) / 360.0 * n).toInt()
        return x.coerceIn(0, n - 1)
    }

    fun latToTileY(latDeg: Double, z: Int): Int {
        val zc = z.coerceIn(0, MAX_ZOOM)
        val n = 1 shl zc
        val lat = latDeg.coerceIn(MIN_LAT, MAX_LAT)
        val latRad = lat * PI / 180.0
        val y = Math.floor((1.0 - asinh(tan(latRad)) / PI) / 2.0 * n).toInt()
        return y.coerceIn(0, n - 1)
    }

    // --- enumeration ------------------------------------------------------

    /**
     * All tiles covering [bbox] across the inclusive zoom range
     * [minZoom]..[maxZoom]. Zoom bounds are order-independent and clamped
     * to 0..[MAX_ZOOM]. North/west map to the min tile indices, south/east
     * to the max (XYZ y grows southward).
     */
    fun enumerateTiles(bbox: BoundingBox, minZoom: Int, maxZoom: Int): List<Tile> {
        val (lo, hi) = zoomRange(minZoom, maxZoom)
        val out = ArrayList<Tile>()
        for (z in lo..hi) {
            val xMin = lonToTileX(bbox.normW, z)
            val xMax = lonToTileX(bbox.normE, z)
            val yMin = latToTileY(bbox.normN, z) // north = smaller y
            val yMax = latToTileY(bbox.normS, z) // south = larger y
            for (x in xMin..xMax) for (y in yMin..yMax) out.add(Tile(z, x, y))
        }
        return out
    }

    /** Tile count for [bbox] over [minZoom]..[maxZoom] without materializing
     *  the list (cheap enough to call on every UI slider move). */
    fun tileCount(bbox: BoundingBox, minZoom: Int, maxZoom: Int): Long {
        val (lo, hi) = zoomRange(minZoom, maxZoom)
        var total = 0L
        for (z in lo..hi) {
            val xMin = lonToTileX(bbox.normW, z)
            val xMax = lonToTileX(bbox.normE, z)
            val yMin = latToTileY(bbox.normN, z)
            val yMax = latToTileY(bbox.normS, z)
            total += (xMax - xMin + 1).toLong() * (yMax - yMin + 1).toLong()
        }
        return total
    }

    /** Rough download size in bytes = tile count × [AVG_TILE_BYTES]. */
    fun estimateBytes(bbox: BoundingBox, minZoom: Int, maxZoom: Int): Long =
        tileCount(bbox, minZoom, maxZoom) * AVG_TILE_BYTES

    private fun zoomRange(a: Int, b: Int): Pair<Int, Int> {
        val lo = minOf(a, b).coerceIn(0, MAX_ZOOM)
        val hi = maxOf(a, b).coerceIn(0, MAX_ZOOM)
        return lo to hi
    }

    // --- URL template fill ------------------------------------------------

    /**
     * Substitute a tile's z/x/y into an XYZ/TMS URL template, respecting the
     * literal token order ({z}/{y}/{x} for ESRI is handled correctly). If a
     * `{s}` subdomain token is present, [subdomains] are rotated
     * deterministically by (x + y) so retries hit a stable host.
     */
    fun fillTemplate(template: String, tile: Tile, subdomains: List<String> = emptyList()): String {
        var url = template
            .replace("{z}", tile.z.toString())
            .replace("{x}", tile.x.toString())
            .replace("{y}", tile.y.toString())
        if (url.contains("{s}") && subdomains.isNotEmpty()) {
            val s = subdomains[Math.floorMod(tile.x + tile.y, subdomains.size)]
            url = url.replace("{s}", s)
        }
        return url
    }

    // --- provider -> template ---------------------------------------------

    /**
     * Resolve the active [MapProvider] to the concrete XYZ tile-URL template
     * to download from. Mirrors the basemap URLs baked into
     * [soy.engindearing.omnitak.mobile.ui.components.styleJsonForProvider] so
     * the cached tiles match what the live map renders.
     *
     * Returns null when no fixed raster template exists — i.e. WMTS_CUSTOM
     * with a blank/invalid URL (the caller blocks the download and tells the
     * operator to set a tile source first).
     */
    fun templateForProvider(provider: MapProvider, customTileUrl: String): String? = when (provider) {
        MapProvider.OSM_RASTER -> "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        MapProvider.TOPO_HINT -> "https://a.tile.opentopomap.org/{z}/{x}/{y}.png"
        MapProvider.SATELLITE_HINT ->
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
        MapProvider.WMTS_CUSTOM -> {
            val url = normalizeTemplate(customTileUrl)
            if (url.startsWith("http") && url.contains("{z}") && url.contains("{x}") && url.contains("{y}")) url
            else null
        }
    }

    /**
     * Normalize ATAK/CivTAK `{$z}` and shell `${z}` placeholder forms to the
     * MapLibre `{z}` convention and trim stray whitespace. Kept in sync with
     * the map's `normalizeTileUrlPlaceholders`; duplicated here so the math
     * layer carries no UI dependency.
     */
    internal fun normalizeTemplate(raw: String): String {
        if (raw.isEmpty()) return raw
        var out = raw.trim()
        for (t in listOf("z", "x", "y", "s", "q", "r")) {
            out = out.replace("\${$t}", "{$t}").replace("{\$$t}", "{$t}")
        }
        return out
    }

    // --- humanize ---------------------------------------------------------

    /** Human-readable byte size, e.g. 1536 -> "1.5 KB". */
    fun humanizeBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024.0
        var idx = 0
        while (value >= 1024.0 && idx < units.size - 1) {
            value /= 1024.0; idx++
        }
        return String.format("%.1f %s", value, units[idx])
    }
}
