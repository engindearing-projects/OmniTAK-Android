package soy.engindearing.omnitak.mobile.domain

import android.content.Context
import android.util.Log
import soy.engindearing.omnitak.mobile.data.symbology.IconPackRegistry
import soy.engindearing.omnitak.mobile.data.symbology.IconPackRegistry.ImportedIcon
import soy.engindearing.omnitak.mobile.data.symbology.IconPackRegistry.ImportedPack
import soy.engindearing.omnitak.mobile.data.symbology.IconsetPackParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Issue #98 Phase 2 — imports an ATAK iconset pack from a `.zip` stream or
 * file and registers it in [IconPackRegistry].
 *
 * ATAK iconset zip layout:
 * ```
 * <pack-name>.zip
 * ├── iconset.xml          ← pack metadata + icon index
 * ├── Ground/
 * │   ├── Ambulance.png
 * │   └── PoliceCar.png
 * └── Air/
 *     └── Helicopter.png
 * ```
 *
 * The importer:
 *   1. Streams the zip and finds `iconset.xml` (at root or one level deep).
 *   2. Parses it with [IconsetPackParser] — validates uid + icon entries.
 *   3. Extracts each image that `iconset.xml` references into
 *      `<filesDir>/iconpacks/<uid>/<filename>`.
 *   4. Registers the pack in [IconPackRegistry] (persists metadata to
 *      `packs.json` so it survives app restarts).
 *
 * Returns an [ImportResult] describing success or the failure reason. Does
 * NOT throw — the caller (file-picker callback, DataPackage bootstrap) wraps
 * this and shows a user-facing error on failure.
 *
 * Call on a background thread / coroutine; does file I/O throughout.
 */
class IconPackImporter(private val context: Context) {

    sealed class ImportResult {
        data class Success(val pack: ImportedPack) : ImportResult()
        data class Failure(val reason: String) : ImportResult()
    }

    /**
     * Import from a [File] (SAF file-picker path or sideload drop).
     * The file must be a `.zip`; no deletion or rename is performed here —
     * the caller decides lifecycle.
     */
    fun importZip(zipFile: File): ImportResult {
        Log.i(TAG, "Importing icon pack from ${zipFile.name}")
        return zipFile.inputStream().use { importStream(it, zipFile.nameWithoutExtension) }
    }

    /**
     * Import from a raw [InputStream] (e.g. a zip entry inside a data package).
     * [hint] is used only in log messages.
     */
    fun importStream(stream: InputStream, hint: String = "stream"): ImportResult {
        // Pass 1: find iconset.xml and collect all image bytes.
        var iconsetXml: String? = null
        val imageBytes = mutableMapOf<String, ByteArray>()

        runCatching {
            ZipInputStream(stream.buffered()).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    if (entry.isDirectory) { zin.closeEntry(); continue }
                    val name = entry.name.trimStart('/')
                    val bytes = zin.readBytes()
                    zin.closeEntry()
                    when {
                        name.equals("iconset.xml", ignoreCase = true) ||
                            name.endsWith("/iconset.xml", ignoreCase = true) -> {
                            iconsetXml = String(bytes, Charsets.UTF_8)
                        }
                        name.looksLikeImage() -> {
                            // Store under the basename-only path for flexible matching.
                            imageBytes[name] = bytes
                        }
                    }
                }
            }
        }.onFailure {
            return ImportResult.Failure("ZIP read error in $hint: ${it.message}")
        }

        val xml = iconsetXml
            ?: return ImportResult.Failure("No iconset.xml found in $hint")

        val parsed = IconsetPackParser.parse(xml)
            ?: return ImportResult.Failure("iconset.xml parse failed in $hint")

        if (parsed.icons.isEmpty()) {
            return ImportResult.Failure("iconset.xml in $hint declares no icons")
        }

        // Persist images to app-private storage.
        val destDir = IconPackRegistry.get(context).packDir(parsed.uid)
        destDir.mkdirs()

        val registeredIcons = mutableListOf<ImportedIcon>()
        for (iconEntry in parsed.icons) {
            val bytes = imageBytes.findForFilename(iconEntry.filename)
            if (bytes == null) {
                Log.w(TAG, "Image not found in zip for '${iconEntry.filename}' — skipping")
                continue
            }
            val dest = File(destDir, iconEntry.filename)
            dest.parentFile?.mkdirs()
            dest.writeBytes(bytes)
            registeredIcons += ImportedIcon(name = iconEntry.name, filename = iconEntry.filename)
        }

        if (registeredIcons.isEmpty()) {
            return ImportResult.Failure(
                "No images extracted from $hint — zip may not match iconset.xml references",
            )
        }

        val pack = ImportedPack(
            uid = parsed.uid,
            name = parsed.name,
            version = parsed.version,
            dirRelative = "iconpacks/${parsed.uid}",
            icons = registeredIcons,
        )
        IconPackRegistry.get(context).register(pack)
        Log.i(TAG, "Imported pack '${pack.name}' uid=${pack.uid} with ${registeredIcons.size} icon(s)")
        return ImportResult.Success(pack)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private fun String.looksLikeImage(): Boolean {
        val ext = substringAfterLast('.').lowercase()
        return ext in IMAGE_EXTENSIONS
    }

    /**
     * Find image bytes in the zip by the iconset.xml `filename` attribute.
     * Tries exact match first, then basename-only match so packs with
     * path-stripped zip entries still resolve.
     */
    private fun Map<String, ByteArray>.findForFilename(filename: String): ByteArray? {
        // Exact match (zip entry path matches iconset.xml filename exactly).
        get(filename)?.let { return it }
        // Basename match (zip flattened all images into root).
        val basename = filename.substringAfterLast('/')
        return entries.firstOrNull { it.key.substringAfterLast('/') == basename }?.value
    }

    companion object {
        private const val TAG = "IconPackImporter"
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
    }
}
