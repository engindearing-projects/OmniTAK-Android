package soy.engindearing.omnitak.mobile.data.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import soy.engindearing.omnitak.mobile.data.MBTilesDb
import soy.engindearing.omnitak.mobile.data.MBTilesServer
import java.io.File
import java.util.UUID

/** Live progress for the in-flight region download (UI binds to this). */
data class RegionDownloadProgress(
    val regionId: String,
    val processed: Int,
    val total: Long,
    val downloaded: Int,
    val failed: Int,
) {
    val fraction: Float get() = if (total <= 0) 0f else (processed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

/**
 * Owns the set of downloaded offline regions (#120): runs downloads, persists
 * region metadata, registers each finished region's MBTiles file with the
 * shared [MBTilesServer] (so it serves over the same in-app tile HTTP path),
 * and exposes list/delete.
 *
 * A downloaded region is a standard MBTiles file, so once registered the map
 * renders it exactly like an imported MBTiles overlay (#33) — no new render
 * path. [OfflineTilePolicy] decides ordering vs the live basemap; the map
 * layer consumes that decision.
 */
class OfflineRegionStore(private val context: Context) {

    private val dir = File(context.filesDir, "offline-tiles").apply { mkdirs() }
    private val metaFile = File(dir, "regions.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private val _regions = MutableStateFlow(load())
    val regions: StateFlow<List<OfflineRegion>> = _regions.asStateFlow()

    private val _progress = MutableStateFlow<RegionDownloadProgress?>(null)
    val progress: StateFlow<RegionDownloadProgress?> = _progress.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    @Volatile private var activeDownloader: RegionDownloader? = null

    init {
        // Re-register persisted regions with the tile server on launch so
        // they serve immediately (mirrors MBTilesOverlayStore.init).
        _regions.value.forEach { r ->
            MBTilesDb.open(fileFor(r).absolutePath)?.let { MBTilesServer.register(serverId(r.id), it) }
        }
    }

    fun fileFor(region: OfflineRegion): File = File(dir, region.fileName)
    /** Server-registration id — distinct namespace from imported MBTiles. */
    private fun serverId(regionId: String) = "offline-$regionId"
    fun tileUrlTemplate(region: OfflineRegion): String? =
        MBTilesServer.tileUrlTemplate(serverId(region.id))

    /** Estimate before committing (drives the UI's count + size readout). */
    fun estimate(bbox: BoundingBox, minZoom: Int, maxZoom: Int): Pair<Long, Long> {
        val count = TileMath.tileCount(bbox, minZoom, maxZoom)
        return count to (count * TileMath.AVG_TILE_BYTES)
    }

    /**
     * Download [bbox] over [minZoom]..[maxZoom] from [template], writing into
     * a fresh MBTiles file, then persist + register the region. Returns the
     * created [OfflineRegion] on success, null if nothing was cached (all
     * fetches failed) or it was cancelled before any tile landed.
     *
     * Device-pending end-to-end: drives real HTTP + SQLite. The orchestration
     * + tile math + cache-key logic are unit-verified
     * ([RegionDownloaderTest], [TileMathTest], [TileStoreRoundTripTest]).
     */
    suspend fun download(
        name: String,
        bbox: BoundingBox,
        minZoom: Int,
        maxZoom: Int,
        template: String,
        sourceLabel: String = "",
        fetch: suspend (String) -> ByteArray? = HttpTileFetcher::fetch,
    ): OfflineRegion? {
        _lastError.value = null
        val id = UUID.randomUUID().toString()
        val file = File(dir, "$id.mbtiles")
        val total = TileMath.tileCount(bbox, minZoom, maxZoom)
        _progress.value = RegionDownloadProgress(id, 0, total, 0, 0)

        return try {
            val writer = OfflineMbtilesWriter.create(file.absolutePath)
            val downloader = RegionDownloader(sink = writer, fetch = fetch)
            activeDownloader = downloader
            val result = downloader.download(bbox, minZoom, maxZoom, template) { done, t ->
                val cur = _progress.value
                _progress.value = RegionDownloadProgress(
                    id, done, t,
                    downloaded = cur?.downloaded ?: 0,
                    failed = cur?.failed ?: 0,
                )
            }
            val region = OfflineRegion(
                id = id, name = name.ifBlank { "Region" }, fileName = file.name,
                north = bbox.normN, south = bbox.normS, east = bbox.normE, west = bbox.normW,
                minZoom = minOf(minZoom, maxZoom).coerceIn(0, TileMath.MAX_ZOOM),
                maxZoom = maxOf(minZoom, maxZoom).coerceIn(0, TileMath.MAX_ZOOM),
                tileCount = result.downloaded + result.skipped,
                sizeBytes = file.length(),
                createdAt = System.currentTimeMillis(),
                sourceLabel = sourceLabel,
            )
            writer.writeMetadata(region)
            writer.close()
            activeDownloader = null

            // Nothing cached (offline / all failed) → don't keep an empty file.
            if (region.tileCount == 0) {
                file.delete()
                _progress.value = null
                if (result.failed > 0) _lastError.value = "Download failed — no tiles cached (network?)."
                return null
            }

            // Register so it serves immediately, persist metadata.
            MBTilesDb.open(file.absolutePath)?.let { MBTilesServer.register(serverId(id), it) }
            _regions.value = _regions.value + region
            persist()
            _progress.value = null
            region
        } catch (e: Exception) {
            file.delete()
            activeDownloader = null
            _progress.value = null
            _lastError.value = "Offline download failed: ${e.message}"
            null
        }
    }

    /** Cancel the in-flight download, if any. */
    fun cancel() {
        activeDownloader?.cancel()
    }

    fun setVisible(id: String, visible: Boolean) {
        _regions.value = _regions.value.map { if (it.id == id) it.copy(visible = visible) else it }
        persist()
    }

    fun rename(id: String, name: String) {
        val t = name.trim(); if (t.isEmpty()) return
        _regions.value = _regions.value.map { if (it.id == id) it.copy(name = t) else it }
        persist()
    }

    fun remove(id: String) {
        MBTilesServer.unregister(serverId(id))
        _regions.value.firstOrNull { it.id == id }?.let { fileFor(it).delete() }
        _regions.value = _regions.value.filterNot { it.id == id }
        persist()
    }

    fun removeAll() {
        _regions.value.forEach { MBTilesServer.unregister(serverId(it.id)); fileFor(it).delete() }
        _regions.value = emptyList()
        persist()
    }

    /** Current network state — feeds [OfflineTilePolicy.decide]. */
    fun networkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun persist() {
        runCatching { metaFile.writeText(json.encodeToString(_regions.value)) }
    }

    private fun load(): List<OfflineRegion> {
        if (!metaFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<OfflineRegion>>(metaFile.readText())
                .filter { File(dir, it.fileName).exists() }
        }.getOrDefault(emptyList())
    }
}
