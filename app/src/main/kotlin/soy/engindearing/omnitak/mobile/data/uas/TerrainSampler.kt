package soy.engindearing.omnitak.mobile.data.uas

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Samples bare-earth elevation from TAK Terrain — the SRTM-backed PNG
 * tile feed published at tak.gov (Public Release).
 *
 * Tile feed reference (captured in
 * ~/Projects/omnitak-uas-research/TAK_MAPS_NOTES.md):
 *  - URL: `…/t3/datasets/terrain/lcll/v1/{z}/{x}/{y}.png`
 *  - Tiling: **WGS84** (NOT Web Mercator) — `2 * 2^z` X tiles by `2^z` Y
 *    tiles, lat 90→-90 north→south.
 *  - Encoding: Mapbox Terrain-RGB —
 *    `elev_m = -10000 + (R*65536 + G*256 + B) * 0.1`
 *  - Heights are ellipsoidal (HAE), same datum as GPS.
 *
 * Zoom strategy: prefer **z=12** (5km tiles, ~19 m/pixel — fine for
 * flight planning). z=13 is the published native zoom but coverage is
 * sparse in many regions (returns 403); we fall back automatically to
 * the next-available zoom on miss, down to [MIN_ZOOM].
 *
 * Cache: in-memory LRU of decoded Bitmaps, sized for ~16 MB
 * (32 × 256 KB). Tiles are immutable so caching is safe forever from
 * a correctness standpoint; we cap on memory not staleness.
 *
 * Threading: all network + bitmap work runs on Dispatchers.IO.
 *
 * Open (with [sample] and [sampleMeters]) so a unit test can stand in for the
 * tile feed without a network or a Bitmap; nothing in the app subclasses it.
 */
open class TerrainSampler(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val preferredZoom: Int = PREFERRED_ZOOM,
    private val httpTimeoutMs: Int = 4_000,
) {
    private val cache = LruCache<String, Bitmap>(CACHE_TILES)
    /** Coverage memo — remember which (z) returned 404/403 for a given
     *  (tx,ty) so we don't re-hit the CDN on the same dead tile twice. */
    private val missCache = LruCache<String, Boolean>(CACHE_TILES * 4)
    private val fetchLock = Mutex()

    data class Sample(val meters: Double, val zoomUsed: Int)

    /**
     * Get bare-earth elevation in meters HAE at [latDeg]/[lonDeg].
     * Tries [preferredZoom] first; if that tile isn't available, falls
     * back down to [MIN_ZOOM] (coverage is dense at low zoom).
     * Returns null on full network failure or transparent pixels.
     */
    open suspend fun sampleMeters(latDeg: Double, lonDeg: Double): Double? =
        sample(latDeg, lonDeg)?.meters

    open suspend fun sample(latDeg: Double, lonDeg: Double): Sample? = withContext(Dispatchers.IO) {
        for (z in preferredZoom downTo MIN_ZOOM) {
            val tx = lonToTileX(lonDeg, z)
            val ty = latToTileY(latDeg, z)
            val bmp = loadTile(z, tx, ty) ?: continue
            val px = lonToPixelX(lonDeg, tx, z, bmp.width)
            val py = latToPixelY(latDeg, ty, z, bmp.height)
            val meters = decodePixelMeters(bmp.getPixel(px, py)) ?: continue
            return@withContext Sample(meters, z)
        }
        null
    }

    private suspend fun loadTile(z: Int, x: Int, y: Int): Bitmap? {
        val key = "$z/$x/$y"
        cache.get(key)?.let { return it }
        if (missCache.get(key) == true) return null
        // Serialise fetches so two parallel callers don't double-fetch.
        return fetchLock.withLock {
            cache.get(key)?.let { return@withLock it }
            if (missCache.get(key) == true) return@withLock null
            val bmp = fetchTile(z, x, y)
            if (bmp != null) cache.put(key, bmp) else missCache.put(key, true)
            bmp
        }
    }

    private fun fetchTile(z: Int, x: Int, y: Int): Bitmap? {
        val url = URL("$baseUrl/$z/$x/$y.png")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = httpTimeoutMs
            readTimeout = httpTimeoutMs
            requestMethod = "GET"
        }
        return try {
            val code = conn.responseCode
            if (code != 200) {
                // 403/404 just mean this (z,x,y) isn't provisioned —
                // routine for sparse high-zoom coverage. Log at debug
                // only; the missCache prevents re-fetch.
                if (code !in setOf(403, 404)) {
                    Log.w(TAG, "tile $z/$x/$y HTTP $code")
                }
                return null
            }
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (e: IOException) {
            Log.w(TAG, "tile $z/$x/$y fetch failed: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Mapbox Terrain-RGB pixel decode. Alpha is mask-only on TAK
     * Terrain (255 = data, 0 = nodata over water — the BB tiles cover
     * that gap).
     */
    private fun decodePixelMeters(argb: Int): Double? {
        val a = (argb ushr 24) and 0xFF
        if (a == 0) return null
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return -10000.0 + (r * 65536 + g * 256 + b) * 0.1
    }

    // --- WGS84 tile math --------------------------------------------------
    // WGS84 grid: zoom 0 = 2x1 tiles. Z extra zoom doubles both axes.
    // numTilesX = 2 * 2^z, numTilesY = 2^z. Y grows south.

    private fun lonToTileX(lonDeg: Double, z: Int): Int {
        val n = 2 shl z // 2 * 2^z
        return ((lonDeg + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    }

    private fun latToTileY(latDeg: Double, z: Int): Int {
        val n = 1 shl z // 2^z
        return ((90.0 - latDeg) / 180.0 * n).toInt().coerceIn(0, n - 1)
    }

    private fun lonToPixelX(lonDeg: Double, tileX: Int, z: Int, tilePx: Int): Int {
        val n = 2 shl z
        val tileLonW = -180.0 + tileX * 360.0 / n
        val frac = (lonDeg - tileLonW) / (360.0 / n)
        return (frac * tilePx).toInt().coerceIn(0, tilePx - 1)
    }

    private fun latToPixelY(latDeg: Double, tileY: Int, z: Int, tilePx: Int): Int {
        val n = 1 shl z
        val tileLatN = 90.0 - tileY * 180.0 / n
        val frac = (tileLatN - latDeg) / (180.0 / n)
        return (frac * tilePx).toInt().coerceIn(0, tilePx - 1)
    }

    companion object {
        private const val TAG = "TerrainSampler"
        const val DEFAULT_BASE_URL =
            "https://d1g8eu4d17b9ii.cloudfront.net/t3/datasets/terrain/lcll/v1"
        /** z=12 is reliably available for the populated world (~5km tiles,
         *  ~19 m/pixel). z=13 published as native but coverage is patchy. */
        const val PREFERRED_ZOOM = 12
        const val MIN_ZOOM = 5
        private const val CACHE_TILES = 32
    }
}
