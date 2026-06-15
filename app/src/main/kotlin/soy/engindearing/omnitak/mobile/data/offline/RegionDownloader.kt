package soy.engindearing.omnitak.mobile.data.offline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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
 * tile bytes on 200, null on any non-200 / IO error (the downloader counts
 * those as failures without aborting the whole region).
 *
 * Device-pending: exercised against a real tile server on device, not in JVM
 * unit tests (which inject a fake fetch into [RegionDownloader]).
 */
object HttpTileFetcher {
    private const val TIMEOUT_MS = 8_000
    private const val USER_AGENT = "OmniTAK-Android offline-tile-cache"

    suspend fun fetch(url: String): ByteArray? = withContext(Dispatchers.IO) {
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            if (conn.responseCode != 200) return@withContext null
            conn.inputStream.use { it.readBytes() }
        } catch (e: java.io.IOException) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
