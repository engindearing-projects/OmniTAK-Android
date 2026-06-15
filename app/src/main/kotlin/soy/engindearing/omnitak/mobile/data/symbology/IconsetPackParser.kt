package soy.engindearing.omnitak.mobile.data.symbology

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Issue #98 Phase 2 — pure-JVM/Kotlin parser for ATAK's `iconset.xml` format.
 *
 * ATAK iconset zips contain an `iconset.xml` at the root (or sometimes nested
 * under a directory) alongside the image files the XML references. The XML
 * follows this structure:
 *
 * ```xml
 * <?xml version="1.0" encoding="UTF-8"?>
 * <iconset uid="<uuid>" name="Pack Name" version="1"
 *          defaultFriendlyGroup="…" defaultHostileGroup="…" …>
 *   <icon name="Ambulance" filename="Ground/Ambulance.png"/>
 *   <!-- or grouped: -->
 *   <group name="Ground">
 *     <icon name="Tank" filename="Ground/Tank.png"/>
 *   </group>
 * </iconset>
 * ```
 *
 * This class has no Android-framework dependencies so it is fully unit-testable
 * on the JVM. Bitmap loading and storage are handled by [IconPackRegistry].
 *
 * Resolution follows ATAK's `iconsetpath` wire format:
 *   `"<uid>/<filename-without-extension>"`
 * e.g. `"f47ac10b-…/Ground/Ambulance"` matches the icon with
 * `filename="Ground/Ambulance.png"`.
 */
object IconsetPackParser {

    /**
     * A single icon entry from `iconset.xml`.
     *
     * @param name display/lookup name (the `name` attribute).
     * @param filename relative path inside the zip (the `filename` attribute),
     *   e.g. `"Ground/Ambulance.png"`.
     */
    data class IconEntry(val name: String, val filename: String)

    /**
     * A fully-parsed ATAK iconset pack.
     *
     * @param uid the pack's UUID (`uid` attribute on `<iconset>`). Used as the
     *   first path component in `iconsetpath` wire format.
     * @param name human-readable pack name (`name` attribute, or [uid] when absent).
     * @param version pack version (defaults to 1).
     * @param icons all icon entries, flattened from any `<group>` nesting.
     */
    data class ParsedIconset(
        val uid: String,
        val name: String,
        val version: Int,
        val icons: List<IconEntry>,
    ) {
        /** Resolve an icon by its exact `name` attribute. */
        fun iconByName(name: String): IconEntry? = icons.firstOrNull { it.name == name }

        /** Resolve an icon by name, case-insensitively. */
        fun iconByNameInsensitive(name: String): IconEntry? =
            icons.firstOrNull { it.name.equals(name, ignoreCase = true) }

        /**
         * Resolve an icon from an ATAK `usericon iconsetpath` value.
         *
         * Accepted forms (all equivalent for `uid="abc"`, `filename="Ground/Ambulance.png"`):
         * - `"abc/Ground/Ambulance"`       — full path, no extension
         * - `"abc/Ground/Ambulance.png"`   — full path, with extension
         * - `"abc/Ambulance"`              — basename only, no extension
         * - `"abc/Ambulance.png"`          — basename only, with extension
         *
         * Returns null when the uid prefix doesn't match or the token is unknown.
         */
        fun iconByIconsetPath(iconsetPath: String): IconEntry? {
            // Strip the uid prefix — must match exactly (case-sensitive, ATAK convention).
            val prefix = "$uid/"
            if (!iconsetPath.startsWith(prefix)) return null
            val token = iconsetPath.removePrefix(prefix)
                .substringBeforeLast('.')  // strip ".png" if present
                .trimEnd('/')

            // 1. Try exact filename match (without extension).
            icons.firstOrNull { it.filename.substringBeforeLast('.') == token }
                ?.let { return it }

            // 2. Try basename match (in case the path omits the subdirectory).
            val basename = token.substringAfterLast('/')
            return icons.firstOrNull {
                it.filename.substringAfterLast('/').substringBeforeLast('.') == basename
            }
        }

        /**
         * ATAK canonical `iconsetpath` for an [IconEntry]:
         * `"<uid>/<filename-without-extension>"`.
         *
         * This is what OmniTAK emits in `<usericon iconsetpath="…"/>` when the
         * operator places a marker using an icon from this pack, so ATAK / iTAK
         * peers resolve it to the correct glyph.
         */
        fun iconsetPathFor(icon: IconEntry): String =
            "$uid/${icon.filename.substringBeforeLast('.')}"
    }

    /**
     * Parse an ATAK `iconset.xml` string into a [ParsedIconset].
     *
     * Returns null when:
     * - the string is blank or not valid XML,
     * - the root element is not `<iconset>`,
     * - the `uid` attribute is missing or blank.
     *
     * Malformed `<icon>` entries (missing `name` or `filename`) are silently
     * skipped — a partial pack is better than a total failure.
     */
    fun parse(xml: String): ParsedIconset? {
        if (xml.isBlank()) return null
        return runCatching { doParse(xml) }.getOrNull()
    }

    private fun doParse(xml: String): ParsedIconset? {
        val factory = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = false
        }
        val parser = factory.newPullParser()
        parser.setInput(xml.reader())

        var uid: String? = null
        var name: String? = null
        var version = 1
        val icons = mutableListOf<IconEntry>()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "iconset" -> {
                        uid = parser.getAttributeValue(null, "uid")?.takeIf { it.isNotBlank() }
                            ?: return null          // uid is mandatory
                        name = parser.getAttributeValue(null, "name")?.takeIf { it.isNotBlank() }
                        version = parser.getAttributeValue(null, "version")?.toIntOrNull() ?: 1
                    }
                    "icon" -> {
                        val iName = parser.getAttributeValue(null, "name")?.takeIf { it.isNotBlank() }
                        val iFile = parser.getAttributeValue(null, "filename")?.takeIf { it.isNotBlank() }
                        if (iName != null && iFile != null) {
                            icons += IconEntry(name = iName, filename = iFile)
                        }
                        // else: skip malformed entry silently
                    }
                    // `<group>` elements are structural only — icons inside them
                    // are handled when the parser reaches the nested `<icon>` tags.
                }
            }
            event = parser.next()
        }

        // Root <iconset> element was never found.
        if (uid == null) return null

        return ParsedIconset(
            uid = uid,
            name = name ?: uid,   // fall back to uid when name is absent
            version = version,
            icons = icons,
        )
    }
}
