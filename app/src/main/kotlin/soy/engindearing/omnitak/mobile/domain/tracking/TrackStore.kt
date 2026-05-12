package soy.engindearing.omnitak.mobile.domain.tracking

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * On-disk catalogue of recorded breadcrumb tracks. Each track is a
 * standalone JSON file under `<filesDir>/tracks/<id>.json`, mirroring
 * the iOS TrackRecordingService.tracksDirectoryURL pattern. Keeping
 * them as separate files (rather than a single index file) means a
 * corrupted entry doesn't poison the whole list — load just skips it.
 *
 * The [tracks] StateFlow is sorted newest-first so the Settings UI
 * doesn't have to re-sort on every render.
 */
class TrackStore(context: Context) {

    private val dir: File = File(context.filesDir, "tracks").apply { mkdirs() }

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    init { reload() }

    fun reload() {
        val files = dir.listFiles { f -> f.isFile && f.extension == "json" }
            ?: emptyArray()
        val loaded = files.mapNotNull { f ->
            runCatching { json.decodeFromString<Track>(f.readText()) }.getOrNull()
        }.sortedByDescending { it.startedMs }
        _tracks.value = loaded
    }

    fun save(track: Track) {
        val target = File(dir, "${track.id}.json")
        runCatching { target.writeText(json.encodeToString(track)) }
        upsertInMemory(track)
    }

    fun rename(id: String, newName: String) {
        val current = _tracks.value.firstOrNull { it.id == id } ?: return
        save(current.copy(name = newName))
    }

    fun updateNotes(id: String, notes: String?) {
        val current = _tracks.value.firstOrNull { it.id == id } ?: return
        save(current.copy(notes = notes))
    }

    fun delete(id: String) {
        val target = File(dir, "$id.json")
        runCatching { target.delete() }
        _tracks.value = _tracks.value.filterNot { it.id == id }
    }

    private fun upsertInMemory(track: Track) {
        val without = _tracks.value.filterNot { it.id == track.id }
        _tracks.value = (without + track).sortedByDescending { it.startedMs }
    }
}
