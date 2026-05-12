package soy.engindearing.omnitak.mobile.domain.offline

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Snapshot of an in-flight tile download. Posted on
 * [TileDownloader.progress] so the Settings UI can render a progress
 * bar without re-querying the disk.
 */
data class TileDownloadProgress(
    val regionId: String,
    val totalTiles: Int,
    val completedTiles: Int,
    val failedTiles: Int = 0,
    val isComplete: Boolean = false,
    val error: String? = null,
) {
    val fraction: Float get() = if (totalTiles == 0) 0f else completedTiles.toFloat() / totalTiles
}

/**
 * Concurrent fetcher that downloads every tile in a [TileRegion] and
 * writes the bytes to disk via [OfflineTileStore]. Rate-limited by a
 * semaphore to be polite to free OSM tile servers (4 parallel
 * requests, matching the iOS DownloadConfiguration default).
 *
 * The downloader does NOT manage its own lifecycle — call from a
 * supervised CoroutineScope and cancel that scope to abort.
 */
class TileDownloader(
    private val store: OfflineTileStore,
    private val client: OkHttpClient = defaultClient(),
    private val maxParallel: Int = 4,
    private val userAgent: String = "OmniTAK-Android/SAR (Offline Maps; tak@engindearing.soy)",
) {

    private val _progress = MutableStateFlow<TileDownloadProgress?>(null)
    val progress: StateFlow<TileDownloadProgress?> = _progress.asStateFlow()

    /** Download every tile inside [region] using its [TileRegion.urlTemplate].
     *  Skips tiles that already exist on disk (resume-on-restart). */
    suspend fun download(region: TileRegion) {
        val tiles = enumerateTiles(region)
        val total = tiles.size
        var completed = 0
        var failed = 0
        emit(region.id, total, completed, failed)

        val sem = Semaphore(maxParallel)
        coroutineScope {
            for ((x, y, z) in tiles) {
                launch(Dispatchers.IO + SupervisorJob()) {
                    sem.withPermit {
                        val existing = store.tileFile(region.id, z, x, y)
                        val ok = if (existing.exists() && existing.length() > 0) {
                            true
                        } else {
                            fetchAndStore(region, x, y, z)
                        }
                        synchronized(this@TileDownloader) {
                            if (ok) completed++ else failed++
                            emit(region.id, total, completed, failed)
                        }
                    }
                }
            }
        }
        val regionDirSize = sumDir(store.regionDir(region.id))
        store.upsert(
            region.copy(
                downloadedTiles = completed,
                sizeBytes = regionDirSize,
            ),
        )
        _progress.value = TileDownloadProgress(
            regionId = region.id,
            totalTiles = total,
            completedTiles = completed,
            failedTiles = failed,
            isComplete = true,
        )
    }

    private fun fetchAndStore(region: TileRegion, x: Int, y: Int, z: Int): Boolean {
        val url = TileMath.expandTemplate(region.urlTemplate, x, y, z) ?: return false
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use false
                val bytes = resp.body?.bytes() ?: return@use false
                store.writeTile(region.id, z, x, y, bytes)
                true
            }
        }.getOrDefault(false)
    }

    private fun emit(regionId: String, total: Int, completed: Int, failed: Int) {
        _progress.value = TileDownloadProgress(
            regionId = regionId,
            totalTiles = total,
            completedTiles = completed,
            failedTiles = failed,
            isComplete = false,
        )
    }

    private fun sumDir(dir: java.io.File): Long {
        if (!dir.exists()) return 0L
        var sum = 0L
        dir.walkBottomUp().filter { it.isFile }.forEach { sum += it.length() }
        return sum
    }

    companion object {
        fun enumerateTiles(region: TileRegion): List<Triple<Int, Int, Int>> {
            val all = ArrayList<Triple<Int, Int, Int>>(region.tileCount)
            for (z in region.zoomMin..region.zoomMax) {
                all.addAll(
                    TileMath.tilesInBbox(
                        north = region.north,
                        south = region.south,
                        east = region.east,
                        west = region.west,
                        zoom = z,
                    ),
                )
            }
            return all
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
