package soy.engindearing.omnitak.mobile.data.geofence

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.FileOutputStream

/**
 * The geofences and the history of what they reported, as one saved document. Pure Kotlin (no
 * Android imports) so the rules below are unit-testable on the JVM.
 */
@Serializable
data class GeofenceSnapshot(
    val version: Int = CURRENT_VERSION,
    val geofences: List<Geofence> = emptyList(),
    /** What happened, newest first. */
    val events: List<GeofenceEvent> = emptyList(),
) {
    /** This snapshot cut down to what is worth keeping, in a fixed order so two snapshots of the same state compare equal. */
    fun capped(): GeofenceSnapshot = copy(
        version = CURRENT_VERSION,
        geofences = geofences.distinctBy { it.id },
        events = events.distinctBy { it.id }.take(MAX_EVENTS),
    )

    companion object {
        const val CURRENT_VERSION = 1

        /** How many events the history keeps. The same number the iOS app keeps. */
        const val MAX_EVENTS = 100
    }
}

/** Where the geofences are kept between runs. */
interface GeofenceStorage {
    /**
     * The saved geofences as they were written. Never throws: a file that is missing, empty or
     * unreadable comes back as an empty [GeofenceSnapshot].
     */
    fun load(): GeofenceSnapshot

    /** Writes [snapshot]. Returns false when it could not be written. Never throws. */
    fun save(snapshot: GeofenceSnapshot): Boolean
}

/**
 * [GeofenceStorage] in one JSON file, `<filesDir>/geofences/geofences.json` in the app. The same
 * place and encoding as the chat history (a file in the private files directory, kotlinx JSON), so
 * a geofence survives the process ending and an app update.
 *
 * A save writes a temporary file next to the real one, syncs it and renames it over the real one,
 * so a process killed mid-write leaves the previous file intact instead of a half-written one.
 *
 * A file that cannot be read is not lost: it is moved aside as `geofences.json.corrupt` before the
 * next save would replace it, and what could be read from it is kept. One damaged geofence in the
 * file does not take the others with it.
 */
class GeofenceFile(private val file: File) : GeofenceStorage {

    override fun load(): GeofenceSnapshot = try {
        when {
            !file.isFile -> GeofenceSnapshot()
            // Far more than the caps allow; reading it could run the app out of memory at start-up,
            // and that would repeat on every launch.
            file.length() > MAX_FILE_BYTES -> setAside()
            else -> {
                val text = file.readText(Charsets.UTF_8)
                if (text.isBlank()) GeofenceSnapshot() else read(text) ?: setAside()
            }
        }
    } catch (e: Exception) {
        setAside()
    } catch (e: OutOfMemoryError) {
        GeofenceSnapshot()
    }

    /**
     * What can be read of [text]: null when it is not a document of ours at all (truncated, some
     * other JSON). A geofence or an event in it that cannot be read is left out and the rest are
     * kept; the file is then copied to `.corrupt` first, so what was left out is not destroyed by
     * the next save.
     */
    private fun read(text: String): GeofenceSnapshot? {
        val root = runCatching { JSON.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        // A document with neither list is not ours.
        if (!root.containsKey(KEY_GEOFENCES) && !root.containsKey(KEY_EVENTS)) return null
        var dropped = 0
        val geofences = readEach(root, KEY_GEOFENCES, Geofence.serializer()) { dropped++ }
        val events = readEach(root, KEY_EVENTS, GeofenceEvent.serializer()) { dropped++ }
        if (dropped > 0) keepACopy()
        return GeofenceSnapshot(geofences = geofences, events = events).capped()
    }

    private fun <T> readEach(
        root: JsonObject,
        key: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        onUnreadable: () -> Unit,
    ): List<T> {
        val array = root[key] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            runCatching { JSON.decodeFromJsonElement(serializer, element) }
                .onFailure { onUnreadable() }
                .getOrNull()
        }
    }

    /** Keeps the unreadable file as `.corrupt` (one copy, the newest) and starts empty. */
    private fun setAside(): GeofenceSnapshot {
        runCatching {
            val aside = File(file.absoluteFile.parentFile, file.name + CORRUPT_SUFFIX)
            aside.delete()
            file.renameTo(aside)
        }
        return GeofenceSnapshot()
    }

    /** Keeps a copy of a file that was only partly readable as `.corrupt` (one copy, the newest). */
    private fun keepACopy() {
        runCatching { file.copyTo(File(file.absoluteFile.parentFile, file.name + CORRUPT_SUFFIX), overwrite = true) }
    }

    override fun save(snapshot: GeofenceSnapshot): Boolean = try {
        val bytes = JSON.encodeToString(snapshot.capped()).toByteArray(Charsets.UTF_8)
        val dir = file.absoluteFile.parentFile
        if (dir == null || !(dir.isDirectory || dir.mkdirs())) {
            false
        } else {
            val tmp = File(dir, file.name + ".tmp")
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                // Best effort: a filesystem that cannot sync must not stop saving.
                runCatching { out.fd.sync() }
            }
            if (tmp.renameTo(file)) {
                true
            } else {
                tmp.delete()
                false
            }
        }
    } catch (e: Exception) {
        false
    } catch (e: OutOfMemoryError) {
        false
    }

    companion object {
        const val DIR_NAME = "geofences"
        const val FILE_NAME = "geofences.json"
        const val CORRUPT_SUFFIX = ".corrupt"
        private const val KEY_GEOFENCES = "geofences"
        private const val KEY_EVENTS = "events"

        /** A saved file larger than this is not read. */
        const val MAX_FILE_BYTES = 8L * 1024 * 1024

        /** `<filesDir>/geofences/geofences.json` */
        fun inFilesDir(filesDir: File): GeofenceFile =
            GeofenceFile(File(File(filesDir, DIR_NAME), FILE_NAME))

        private val JSON = Json {
            // A file written by a newer build still loads here.
            ignoreUnknownKeys = true
            // ...and an enum value this build does not know falls back to its default instead of failing the geofence.
            coerceInputValues = true
            encodeDefaults = true
        }
    }
}
