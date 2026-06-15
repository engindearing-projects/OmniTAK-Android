package soy.engindearing.omnitak.mobile.data.symbology

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Issue #98 Phase 2 — runtime registry for imported ATAK iconset packs.
 *
 * Imported packs land in `<filesDir>/iconpacks/<uid>/` after extraction by
 * [IconPackImporter]. This registry:
 *   - persists the pack metadata list across launches (packs.json)
 *   - resolves a `usericon iconsetpath` to the pack that owns it
 *   - loads the matching image file as a [Bitmap] for the ContactSymbolLayer
 *   - exposes the full selectable catalogue for the [MarkerIconPickerSheet]
 *
 * Thread safety: all mutating state is guarded by [lock]. Reads and bitmap
 * loads are called from coroutines/background threads by the map renderer.
 */
class IconPackRegistry(private val context: Context) {

    /** Lightweight, JSON-serializable descriptor persisted across launches. */
    @Serializable
    data class ImportedPack(
        val uid: String,
        val name: String,
        val version: Int,
        /** Relative path inside the app's files dir: `iconpacks/<uid>/`. */
        val dirRelative: String,
        val icons: List<ImportedIcon>,
    )

    @Serializable
    data class ImportedIcon(
        val name: String,
        /** Relative path from the pack dir: e.g. `Ground/Ambulance.png`. */
        val filename: String,
    ) {
        /** Derive the ATAK `iconsetpath` token (filename without extension). */
        fun pathToken(): String = filename.substringBeforeLast('.')
    }

    private val lock = Any()
    private val packs = mutableListOf<ImportedPack>()
    private val packIndex = mutableMapOf<String, ImportedPack>()  // uid → pack
    private var loaded = false

    /** Ensure the pack list is loaded from disk. Idempotent, call on any thread. */
    fun ensureLoaded() {
        synchronized(lock) {
            if (loaded) return
            loadFromDisk()
            loaded = true
        }
    }

    /** All currently imported packs, in import order. */
    fun allPacks(): List<ImportedPack> {
        ensureLoaded()
        return synchronized(lock) { packs.toList() }
    }

    /** Register a newly-imported pack. Persists immediately. Idempotent by uid. */
    fun register(pack: ImportedPack) {
        synchronized(lock) {
            ensureLoaded()
            // Replace if the uid already exists (re-import / update).
            packs.removeAll { it.uid == pack.uid }
            packIndex.remove(pack.uid)
            packs += pack
            packIndex[pack.uid] = pack
            saveToDisk()
        }
        Log.i(TAG, "Registered icon pack '${pack.name}' uid=${pack.uid} (${pack.icons.size} icons)")
    }

    /** Remove an imported pack by uid. Returns true when found and removed. */
    fun remove(uid: String): Boolean {
        synchronized(lock) {
            ensureLoaded()
            val removed = packs.removeAll { it.uid == uid }
            packIndex.remove(uid)
            if (removed) {
                saveToDisk()
                packDir(uid).deleteRecursively()
            }
            return removed
        }
    }

    /**
     * Resolve an `iconsetpath` to its [ImportedPack] and [ImportedIcon].
     *
     * ATAK wire format: `"<uid>/<filename-no-ext>"` e.g.
     * `"f47ac10b-…/Ground/Ambulance"`.
     * Returns null when no imported pack owns this path.
     */
    fun resolve(iconsetPath: String?): Pair<ImportedPack, ImportedIcon>? {
        if (iconsetPath == null) return null
        ensureLoaded()
        val uidEnd = iconsetPath.indexOf('/')
        if (uidEnd < 1) return null
        val uid = iconsetPath.substring(0, uidEnd)
        val token = iconsetPath.substring(uidEnd + 1)

        val pack = synchronized(lock) { packIndex[uid] } ?: return null
        val icon = pack.icons.firstOrNull { icon ->
            val iconToken = icon.filename.substringBeforeLast('.')
            iconToken == token ||
                iconToken.substringAfterLast('/') == token.substringAfterLast('/')
        } ?: return null
        return pack to icon
    }

    /**
     * Load the image [Bitmap] for an icon from a resolved pack.
     * Returns null when the file is missing or fails to decode.
     * Call off the main thread — does file I/O.
     */
    fun loadBitmap(pack: ImportedPack, icon: ImportedIcon): Bitmap? {
        val file = File(packDir(pack.uid), icon.filename)
        if (!file.exists()) {
            Log.w(TAG, "Icon file missing: ${file.absolutePath}")
            return null
        }
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }
            .onFailure { Log.w(TAG, "Failed to decode ${file.absolutePath}: ${it.message}") }
            .getOrNull()
    }

    /** Whether any imported pack owns this iconset path. */
    fun handles(iconsetPath: String?): Boolean = resolve(iconsetPath) != null

    /** Stable MapLibre style-image key for an imported icon, distinct from
     *  bundled TAK-suite keys. Format: `"import-<uid>-<token>"`. */
    fun styleImageId(iconsetPath: String): String {
        val uidEnd = iconsetPath.indexOf('/')
        return if (uidEnd > 0) {
            val uid = iconsetPath.substring(0, uidEnd)
            val token = iconsetPath.substring(uidEnd + 1)
            "import-$uid-$token"
        } else {
            "import-$iconsetPath"
        }
    }

    /** Canonical filesystem directory for a pack's extracted images. */
    fun packDir(uid: String): File =
        File(context.filesDir, "iconpacks/$uid")

    // ─── Persistence ──────────────────────────────────────────────────────

    private val packsJsonFile get() = File(context.filesDir, "iconpacks/packs.json")

    private fun loadFromDisk() {
        val file = packsJsonFile
        if (!file.exists()) return
        runCatching {
            val list: List<ImportedPack> = json.decodeFromString(file.readText())
            packs.clear()
            packIndex.clear()
            packs += list
            list.forEach { packIndex[it.uid] = it }
            Log.i(TAG, "Loaded ${list.size} imported pack(s) from disk")
        }.onFailure {
            Log.w(TAG, "Failed to load packs.json: ${it.message}")
        }
    }

    private fun saveToDisk() {
        runCatching {
            packsJsonFile.parentFile?.mkdirs()
            packsJsonFile.writeText(json.encodeToString(packs.toList()))
        }.onFailure {
            Log.w(TAG, "Failed to save packs.json: ${it.message}")
        }
    }

    companion object {
        private const val TAG = "IconPackRegistry"
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

        /** App-scoped singleton — one registry per process. */
        @Volatile private var instance: IconPackRegistry? = null

        fun get(context: Context): IconPackRegistry =
            instance ?: synchronized(this) {
                instance ?: IconPackRegistry(context.applicationContext).also { instance = it }
            }
    }
}
