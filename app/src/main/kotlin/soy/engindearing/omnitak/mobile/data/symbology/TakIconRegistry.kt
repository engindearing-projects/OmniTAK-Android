package soy.engindearing.omnitak.mobile.data.symbology

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import androidx.collection.LruCache
import androidx.compose.ui.graphics.Color

/**
 * Issue #98 (Phase 1) — resolution + selection framework for the standard
 * TAK icon suite. Android port of the iOS `TAKIconRegistry`, kept in lockstep
 * so a marker dropped on one platform round-trips to the other through CoT.
 *
 * WHAT THIS SOLVES
 * ----------------
 * Before this, a received or placed marker only ever rendered as one of four
 * MIL-STD-2525 affiliation frames (via [MilStdIconCache]). Markers pushed from
 * scripts or iTAK carrying a `<usericon iconsetpath="…">` — the Spot Map /
 * Markers / Google packs — had nowhere to resolve to, so they fell through to
 * the generic affiliation glyph. That is exactly the gap kymyura reported on
 * Discord (scripts pushing CoT with `<usericon iconsetpath="COT_MAPPING_SPOTMAP/…">`).
 *
 * This registry is the resolution layer: given a CoT type, an optional
 * `usericon` iconset path, and an optional ARGB colour, it returns the right
 * bundled icon bitmap — and it exposes a selectable catalogue so the same icons
 * can be picked when *placing* a marker. Resolution order mirrors ATAK:
 *   1. explicit `usericon iconsetpath` (e.g. `COT_MAPPING_SPOTMAP/red`)
 *   2. CoT type that maps to a known iconset (`b-m-p-s-m` → Spot Map)
 *   3. caller's fallback (the MIL-STD-2525 affiliation frame from
 *      [MilStdIconCache], handled by the caller when this returns null)
 *
 * ICON SOURCING / LICENSING (read before adding packs)
 * ----------------------------------------------------
 * The standard ATAK "Spot Map" set is a coloured dot keyed off the CoT
 * `<color>` element — it carries no creative artwork, so this file renders it
 * cleanly at runtime keyed to ATAK's exact canonical colour palette and
 * `COT_MAPPING_SPOTMAP/{color}` paths. That makes received and placed spot-map
 * markers resolve and round-trip correctly with ATAK / iTAK / TAK Server with
 * zero third-party assets.
 *
 * The "Markers" and "Google" raster packs are bitmap artwork. The only local
 * source (atak-civ-source) is GPLv3 in this checkout, so those bitmaps CANNOT
 * be vendored into a closed Play / App-Store binary without relicensing the
 * app. The framework here is pack-agnostic — [TakIconPack] + [resolveBitmap]
 * already model multi-pack lookup — so when an Apache-2.0 / public-domain
 * bitmap pack (or a user-imported ATAK iconset, Phase 2) is available it slots
 * in behind the same API. See the build report for the explicit asset blocker.
 */
object TakIconRegistry {

    /**
     * Identifies a standard TAK icon pack. The [uid] matches ATAK's iconset
     * UID / `COT_MAPPING_*` path prefix so an `iconsetpath` round-trips
     * byte-for-byte with ATAK / iTAK.
     */
    enum class TakIconPack(val uid: String, val displayName: String, val bundled: Boolean) {
        /** Coloured point markers — ATAK's "Spot Map". Path prefix
         *  `COT_MAPPING_SPOTMAP`, CoT type `b-m-p-s-m`. Bundled
         *  (runtime-rendered, no artwork). */
        SPOT_MAP("COT_MAPPING_SPOTMAP", "Spot Map", bundled = true),

        /** ATAK "Markers" pack (bitmap artwork). Modeled for resolution but
         *  not bundled — see file header on the GPL asset blocker. */
        MARKERS("COT_MAPPING_2525C", "Markers", bundled = false),

        /** ATAK "Google" pack (bitmap artwork). Modeled but not bundled. The
         *  UID is the canonical Google iconset UID from ATAK's iconsets DB. */
        GOOGLE("f7f71666-8b28-4b57-9fbb-e38e61d33b79", "Google", bundled = false),
    }

    /**
     * The canonical ATAK Spot Map colour palette. Values mirror
     * `SpotMapPalletFragment` in ATAK-CIV (and the iOS `TAKSpotIcon`) exactly,
     * so a marker placed here reads identically in ATAK / iTAK and a marker
     * received from them resolves back to the same swatch. Each case is a
     * selectable icon when placing a marker.
     */
    enum class SpotIcon(val key: String, val argb: Int) {
        WHITE("white", 0xFFFFFFFF.toInt()),
        YELLOW("yellow", 0xFFFFCC00.toInt()),
        ORANGE("orange", 0xFFFF7700.toInt()),
        BROWN("brown", 0xFF8B4513.toInt()),
        RED("red", 0xFFFF3B30.toInt()),
        MAGENTA("magenta", 0xFFFF00FF.toInt()),
        BLUE("blue", 0xFF007AFF.toInt()),
        CYAN("cyan", 0xFF00FFFF.toInt()),
        GREEN("green", 0xFF34C759.toInt()),
        GREY("grey", 0xFF777777.toInt()),
        BLACK("black", 0xFF000000.toInt());

        /** `usericon` iconset path: `COT_MAPPING_SPOTMAP/{color}`. ATAK matches
         *  the trailing token case-insensitively, so the lowercased key is exact. */
        val iconsetPath: String get() = "${TakIconPack.SPOT_MAP.uid}/$key"

        /** Display name for the picker / accessibility. */
        val displayName: String get() = key.replaceFirstChar { it.uppercase() }

        /** Compose colour for picker swatches. */
        val color: Color get() = Color(argb)

        /** 8-hex opaque ARGB string for the CoT `<color argb>` element. */
        val argbHex: String get() = "FF%02X%02X%02X".format(
            (argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF,
        )

        companion object {
            /** The CoT type ATAK uses for every spot-map point. Colour rides
             *  the `<color>` element, not the type — same as ATAK. */
            const val COT_TYPE = "b-m-p-s-m"

            /** Resolve a Spot Map colour from a `usericon` iconset path
             *  (`COT_MAPPING_SPOTMAP/red`, case-insensitive trailing token).
             *  Unknown / "label"-only tokens fall back to white so a spot
             *  marker never renders blank. Null when the path is not Spot Map. */
            fun fromIconsetPath(path: String): SpotIcon? {
                if (!path.uppercase().startsWith(TakIconPack.SPOT_MAP.uid)) return null
                val token = path.substringAfterLast('/').lowercase()
                return entries.firstOrNull { it.key == token }
                    ?: if (token.isEmpty()) null else WHITE
            }
        }
    }

    /** Spot Map icons offered in the marker-placement picker, in ATAK order. */
    val selectableSpotIcons: List<SpotIcon> get() = SpotIcon.entries

    /** Packs the suite knows about, flagged by whether their assets are bundled. */
    val availablePacks: List<TakIconPack> get() = TakIconPack.entries

    // Rendered-glyph cache so the map's symbol refresh stays cheap.
    private data class Key(val token: String, val sizePx: Int)
    private val cache = LruCache<Key, Bitmap>(64)

    /**
     * Resolve a renderable bitmap for a marker. Returns null when no TAK-suite
     * icon applies, so the caller falls back to MIL-STD-2525 affiliation art.
     *
     * @param cotType CoT `type` (e.g. `b-m-p-s-m`, `a-f-G-U-C-I`).
     * @param iconsetPath `usericon iconsetpath` if the CoT carried one.
     * @param argb optional override colour (signed ARGB int from `<color>`);
     *   used for Spot Map points whose colour rides the `<color>` element.
     * @param sizePx target pixel size for the rendered glyph.
     */
    fun resolveBitmap(
        cotType: String?,
        iconsetPath: String? = null,
        argb: Int? = null,
        sizePx: Int = 64,
    ): Bitmap? {
        // 1. Explicit Spot Map iconset path wins.
        if (iconsetPath != null) {
            SpotIcon.fromIconsetPath(iconsetPath)?.let { spot ->
                val color = argb ?: spot.argb
                return spotDot(color, sizePx, "spot|${spot.key}|$color")
            }
        }
        // 2. Spot Map CoT type (colour carried by <color>, default white).
        if (cotType == SpotIcon.COT_TYPE) {
            val color = argb ?: SpotIcon.WHITE.argb
            return spotDot(color, sizePx, "spot|type|$color")
        }
        // 3. Unbundled bitmap packs (Markers/Google) would resolve here once a
        //    clean asset source lands — intentionally null for now so the
        //    caller keeps using the MIL-STD-2525 fallback rather than a blank.
        return null
    }

    /**
     * Whether this marker resolves to a TAK-suite (non-MIL-STD) icon. Lets the
     * symbol layer decide which image-registration path to take without
     * rendering twice.
     */
    fun handles(cotType: String?, iconsetPath: String?): Boolean =
        (iconsetPath != null && SpotIcon.fromIconsetPath(iconsetPath) != null) ||
            cotType == SpotIcon.COT_TYPE

    /** Convenience: the rendered glyph for a selectable Spot Map icon (picker
     *  swatches, current-symbol rows). */
    fun bitmapFor(spot: SpotIcon, sizePx: Int = 64): Bitmap =
        spotDot(spot.argb, sizePx, "spot|sel|${spot.key}")

    /**
     * Stable image-registration key for the MapLibre style. Distinct from a
     * MIL-STD SIDC so the two symbol families never collide in
     * [android.graphics.Bitmap] registration.
     */
    fun styleImageId(cotType: String?, iconsetPath: String?, argb: Int?): String? {
        if (iconsetPath != null) {
            SpotIcon.fromIconsetPath(iconsetPath)?.let { spot ->
                return "takicon-spot-${argb ?: spot.argb}"
            }
        }
        if (cotType == SpotIcon.COT_TYPE) return "takicon-spot-${argb ?: SpotIcon.WHITE.argb}"
        return null
    }

    /**
     * ATAK's spot-map point: a filled dot with a thin contrasting outline so it
     * reads on any basemap. White/black get an inverted ring for contrast.
     */
    private fun spotDot(argb: Int, sizePx: Int, cacheKey: String): Bitmap {
        val key = Key(cacheKey, sizePx)
        cache.get(key)?.let { return it }

        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = sizePx / 2f
        val cy = sizePx / 2f
        val r = sizePx * 0.32f

        // Drop shadow for legibility against bright imagery.
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(110, 0, 0, 0)
            setShadowLayer(sizePx * 0.06f, 0f, 0f, AndroidColor.argb(160, 0, 0, 0))
        }
        // setShadowLayer needs a software layer; draw the fill then the ring.
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, r, shadow)
        canvas.drawCircle(cx, cy, r, fill)

        // Contrasting outline — black ring for light dots, white for dark.
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isLight(argb)) AndroidColor.BLACK else AndroidColor.WHITE
            style = Paint.Style.STROKE
            strokeWidth = (sizePx * 0.06f).coerceAtLeast(1f)
        }
        canvas.drawCircle(cx, cy, r, outline)

        cache.put(key, bmp)
        return bmp
    }

    /** Perceived-luminance test used to pick a contrasting outline. */
    private fun isLight(argb: Int): Boolean {
        val rr = ((argb shr 16) and 0xFF) / 255.0
        val gg = ((argb shr 8) and 0xFF) / 255.0
        val bb = (argb and 0xFF) / 255.0
        return (0.299 * rr + 0.587 * gg + 0.114 * bb) > 0.6
    }

    /** Decode a signed ARGB int (as carried in CoT `<color argb>`) to an opaque
     *  ARGB int — a fully-transparent alpha (common when colour omits alpha) is
     *  treated as opaque so the dot is visible. */
    fun normalizeArgb(argb: Int): Int {
        val a = (argb shr 24) and 0xFF
        return if (a == 0) argb or 0xFF000000.toInt() else argb
    }

    /** Clear the cache — call from `onTrimMemory` or test teardown. */
    fun clear() = cache.evictAll()
}
