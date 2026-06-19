package soy.engindearing.omnitak.mobile.data.offline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import soy.engindearing.omnitak.mobile.data.MapTileHttp
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Outcome of a region download run.
 *
 * [downloaded] = tiles freshly fetched and written. [skipped] = tiles already
 * in the cache (resume). [failed] = fetch errors (404 / offline). Progress
 * for the UI is (downloaded + skipped + failed) out of [total].
 */
data class DownloadResult(
    val total: Long,
    val downloaded: Int,
    val skipped: Int,
    val failed: Int,
    val completed: Boolean,
) {
    /** Tiles processed so far — what a progress bar measures against [total]. */
    val processed: Int get() = downloaded + skipped + failed
}

/**
 * Orchestrates an offline region download (#120): enumerate the bbox×zoom
 * tile set, fetch each tile from the active source template with a bounded
 * number of concurrent requests, write hits into the [TileSink], and report
 * progress. Honours cancellation and (optionally) skips tiles already in the
 * cache so an interrupted download resumes cheaply.
 *
 * The HTTP fetch is injected ([fetch]) so this orchestration is pure,
 * deterministic, and unit-testable. The production fetch is
 * [HttpTileFetcher.fetch] (a thin HttpURLConnection call — device-verified).
 *
 * Concurrency is intentionally capped (default [DEFAULT_CONCURRENCY]) to
 * respect public tile-server usage policy; the UI further caps the zoom
 * depth via [TileMath.MAX_ZOOM].
 */
class RegionDownloader(
    private val sink: TileSink,
    private val fetch: suspend (url: String) -> ByteArray?,
    private val concurrency: Int = DEFAULT_CONCURRENCY,
    private val skipExisting: Boolean = true,
    private val subdomains: List<String> = listOf("a", "b", "c"),
) {
    private val writer = TileCacheWriter(sink)
    private val cancelled = AtomicBoolean(false)

    /** Request the in-flight download stop as soon as possible. */
    fun cancel() { cancelled.set(true) }

    /**
     * Run the download. Suspends until every tile has been attempted or the
     * download is cancelled. [onProgress] fires (done, total) as tiles
     * complete — already-cached skips count as done so the bar still fills.
     */
    suspend fun download(
        bbox: BoundingBox,
        minZoom: Int,
        maxZoom: Int,
        template: String,
        onProgress: (done: Int, total: Long) -> Unit = { _, _ -> },
    ): DownloadResult {
        cancelled.set(false)
        val tiles = TileMath.enumerateTiles(bbox, minZoom, maxZoom)
        val total = tiles.size.toLong()
        val done = AtomicInteger(0)       // processed (for progress)
        val downloaded = AtomicInteger(0) // fresh successful writes
        val skipped = AtomicInteger(0)    // already-cached hits
        val failed = AtomicInteger(0)     // fetch errors

        val gate = Semaphore(concurrency.coerceAtLeast(1))
        runCatching {
            coroutineScope {
                for (tile in tiles) {
                    if (cancelled.get()) break
                    launch(Dispatchers.IO) {
                        if (cancelled.get()) return@launch
                        gate.withPermit {
                            if (cancelled.get()) return@withPermit
                            // Resume optimization: a tile already cached is a
                            // hit — count it, don't re-fetch.
                            if (skipExisting && isCached(tile)) {
                                skipped.incrementAndGet()
                                onProgress(done.incrementAndGet(), total)
                                return@withPermit
                            }
                            val url = TileMath.fillTemplate(template, tile, subdomains)
                            val bytes = runCatching { fetch(url) }.getOrNull()
                            if (bytes != null && bytes.isNotEmpty()) {
                                writer.writeXyz(tile, bytes)
                                downloaded.incrementAndGet()
                            } else {
                                failed.incrementAndGet()
                            }
                            onProgress(done.incrementAndGet(), total)
                        }
                    }
                }
            }
        }.onFailure { if (it is CancellationException) cancelled.set(true) else throw it }

        val finished = !cancelled.get()
        return DownloadResult(
            total = total,
            downloaded = downloaded.get(),
            skipped = skipped.get(),
            failed = failed.get(),
            completed = finished,
        )
    }

    private fun isCached(tile: Tile): Boolean =
        sink.get(tile.z, tile.x, MbtilesRow.xyzToTms(tile.z, tile.y)) != null

    companion object {
        /** Polite default — public OSM/Topo servers throttle aggressive
         *  parallel pulls. Operators on a private LAN tile server can run
         *  hotter, but the floor protects the shared sources. */
        const val DEFAULT_CONCURRENCY = 4
    }
}

/**
 * Production tile fetch over HTTP — a thin HttpURLConnection GET, mirroring
 * the proven pattern in
 * [soy.engindearing.omnitak.mobile.data.uas.TerrainSampler]. Returns the raw
 * tile bytes on a real 200, null on any non-200 / IO error / policy block (the
 * downloader counts those as failures without aborting the whole region).
 *
 * User-Agent (#139): OSM/OpenTopoMap's tile usage policy *requires* a UA that
 * identifies the app; a generic one gets the "Access blocked" placeholder. The
 * interactive map fixed this by installing an OkHttp client into MapLibre
 * ([soy.engindearing.omnitak.mobile.data.MapTileHttp]), but the offline region
 * download runs through this fetcher, not MapLibre — so it must send the same
 * identifying UA, otherwise "Download this region" is blocked on OSM while
 * satellite (Esri, no UA policy) works. Reuse the one builder so both paths
 * stay in sync.
 *
 * Blocked-tile guard (#139): OSM serves its "Access blocked" placeholder as
 * HTTP **200** with an `X-Blocked` header — verified against
 * tile.openstreetmap.org, the body is a 6987-byte text PNG, *not* a 4xx — so a
 * status-code check alone caches the placeholder as if it were a real tile. A
 * *bulk* region pull trips this even with a valid UA, because OSM rate-limits
 * bulk downloads with the same blocked response. Drop any block-marked response
 * so the offline cache is never poisoned with "Access blocked" placeholders.
 *
 * Device-pending: the live HTTP round-trip is exercised against a real tile
 * server on device; the pure [isBlocked] decision and [RegionDownloader] (which
 * injects a fake fetch) are covered by JVM unit tests.
 */
object HttpTileFetcher {
    private const val TIMEOUT_MS = 8_000
    private val USER_AGENT = MapTileHttp.buildUserAgent()

    /**
     * True when a 200 response is actually OSM/OpenTopoMap's "Access blocked"
     * placeholder rather than a real tile. OSM marks it with an `X-Blocked`
     * header (its value points at the tile-usage policy). Pure over a header
     * lookup so it unit-tests without a network round-trip; [header] mirrors
     * [java.net.HttpURLConnection.getHeaderField] (case-insensitive name).
     */
    internal fun isBlocked(header: (name: String) -> String?): Boolean =
        header("X-Blocked") != null

    suspend fun fetch(url: String): ByteArray? = withContext(Dispatchers.IO) {
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            if (conn.responseCode != 200) return@withContext null
            if (isBlocked { conn.getHeaderField(it) }) return@withContext null
            conn.inputStream.use { it.readBytes() }
        } catch (e: java.io.IOException) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
