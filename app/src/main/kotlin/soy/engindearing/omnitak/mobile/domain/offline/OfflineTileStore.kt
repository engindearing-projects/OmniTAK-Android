package soy.engindearing.omnitak.mobile.domain.offline

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * On-disk catalogue of downloaded map tile regions, plus the actual
 * tile file blobs.
 *
 * Layout:
 *   <filesDir>/tiles/regions.json
 *   <filesDir>/tiles/<region-id>/<z>/<x>/<y>.png
 *
 * Mirrors the iOS OfflineMapManager directory structure so a SAR
 * handler running a mixed Android/iOS team gets the same offline
 * coverage with the same operator gestures. The store is the one
 * source of truth — the downloader and the OkHttp interceptor both
 * read from / write to it through this façade.
 */
class OfflineTileStore(context: Context) {

    private val baseDir: File = File(context.filesDir, "tiles").apply { mkdirs() }
    private val regionsFile: File = File(baseDir, "regions.json")

    private val _regions = MutableStateFlow<List<TileRegion>>(emptyList())
    val regions: StateFlow<List<TileRegion>> = _regions.asStateFlow()

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    init { reload() }

    fun reload() {
        if (!regionsFile.exists()) {
            _regions.value = emptyList()
            return
        }
        val loaded = runCatching {
            json.decodeFromString<List<TileRegion>>(regionsFile.readText())
        }.getOrDefault(emptyList())
        _regions.value = loaded.sortedByDescending { it.createdMs }
    }

    fun upsert(region: TileRegion) {
        val without = _regions.value.filterNot { it.id == region.id }
        _regions.value = (without + region).sortedByDescending { it.createdMs }
        persist()
        File(baseDir, region.id).mkdirs()
    }

    fun delete(id: String) {
        _regions.value = _regions.value.filterNot { it.id == id }
        persist()
        val regionDir = File(baseDir, id)
        if (regionDir.exists()) regionDir.deleteRecursively()
    }

    /** Returns the byte content of a cached tile for any region that
     *  covers (z, x, y), or null if nothing matches. Used by both
     *  [OfflineTileCache] and the downloader's resume check. */
    fun readTile(z: Int, x: Int, y: Int): ByteArray? {
        for (region in _regions.value) {
            if (!region.contains(z, x, y)) continue
            val f = tileFile(region.id, z, x, y)
            if (f.exists() && f.length() > 0) return f.readBytes()
        }
        return null
    }

    /** Persist a downloaded tile under the given region's directory.
     *  Caller is responsible for incrementing the region's
     *  downloadedTiles counter via [upsert]. */
    fun writeTile(regionId: String, z: Int, x: Int, y: Int, bytes: ByteArray): File {
        val target = tileFile(regionId, z, x, y).also { it.parentFile?.mkdirs() }
        target.writeBytes(bytes)
        return target
    }

    fun tileFile(regionId: String, z: Int, x: Int, y: Int): File =
        File(baseDir, "$regionId/$z/$x/$y.png")

    fun regionDir(regionId: String): File = File(baseDir, regionId)

    fun totalDiskUsageBytes(): Long {
        var sum = 0L
        for (region in _regions.value) {
            val dir = regionDir(region.id)
            if (!dir.exists()) continue
            dir.walkBottomUp().filter { it.isFile }.forEach { sum += it.length() }
        }
        return sum
    }

    private fun persist() {
        runCatching {
            regionsFile.writeText(json.encodeToString(_regions.value))
        }
    }
}
