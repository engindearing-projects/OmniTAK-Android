package soy.engindearing.omnitak.mobile.data.symbology

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.util.Base64
import androidx.annotation.DrawableRes
import androidx.collection.LruCache
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import soy.engindearing.omnitak.mobile.R
import soy.engindearing.omnitak.mobile.data.FemaIconCatalog
import java.io.ByteArrayOutputStream

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
 * `COT_MAPPING_SPOTMAP/{color}` paths.
 *
 * The "Markers" and "Google" packs ship as **vector-drawable glyphs derived
 * from the Material Symbols set (Apache License 2.0)** — `res/drawable/takicon_*`
 * — so they are clean to vendor into the closed Play binary (the GPLv3
 * atak-civ-source pack is NOT used). Each glyph is rasterised at runtime onto a
 * rounded badge so it reads as a map marker. The canonical ATAK icon names are
 * preserved on the [MarkerIcon] / [GoogleIcon] cases and in their
 * [iconsetPath]s, so an inbound `iconsetpath` from ATAK / iTAK resolves to the
 * matching glyph and a marker placed here round-trips back to those clients.
 * Per-marker custom icon-pack import (arbitrary `iconset.xml` + images) remains
 * Phase 2.
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

        /** ATAK "Markers" pack. Path prefix `COT_MAPPING_2525C` (the historical
         *  ATAK iconset UID for the general marker set). Bundled — glyphs are
         *  Material-Symbols (Apache-2.0) vector drawables, see [MarkerIcon]. */
        MARKERS("COT_MAPPING_2525C", "Markers", bundled = true),

        /** ATAK "Google" POI pack. The [uid] is the canonical Google iconset UID
         *  from ATAK's iconsets DB. Bundled — Material-Symbols (Apache-2.0)
         *  vector drawables, see [GoogleIcon]. */
        GOOGLE("f7f71666-8b28-4b57-9fbb-e38e61d33b79", "Google", bundled = true),

        /** FEMA / ICS marker catalog from #29. Path prefix `COT_MAPPING_FEMA`.
         *  Bundled — rendered at runtime as category-accented dots. Proper
         *  per-glyph SVG artwork is tracked in #46; this entry wires up the
         *  resolution path so inbound FEMA markers are handled by the TAK-suite
         *  path rather than falling through to the MIL-STD-2525 default. */
        FEMA("COT_MAPPING_FEMA", "FEMA/ICS", bundled = true),
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

    /**
     * Common interface for the bitmap-glyph packs (Markers / Google) so the
     * picker and resolver treat them uniformly. Each case names a canonical ATAK
     * icon, carries the badge tint ATAK uses for that pack, and points at a
     * Material-Symbols (Apache-2.0) vector drawable.
     */
    interface BadgeIcon {
        val pack: TakIconPack
        val token: String
        @get:DrawableRes val drawableRes: Int
        val badgeArgb: Int
        val displayName: String

        /** `usericon` iconset path, e.g. `COT_MAPPING_2525C/flag`. */
        val iconsetPath: String get() = "${pack.uid}/$token"
    }

    /**
     * ATAK "Markers" pack — general-purpose point markers. The [token] is the
     * canonical ATAK icon name so an inbound `COT_MAPPING_2525C/<token>` path
     * resolves here and a placed marker round-trips to ATAK / iTAK. Glyphs are
     * Material Symbols (Apache-2.0); the dark-slate badge matches ATAK's neutral
     * marker chrome.
     */
    enum class MarkerIcon(
        override val token: String,
        @get:DrawableRes override val drawableRes: Int,
        override val displayName: String,
    ) : BadgeIcon {
        FLAG("flag", R.drawable.takicon_marker_flag, "Flag"),
        WARNING("warning", R.drawable.takicon_marker_warning, "Warning"),
        STAR("star", R.drawable.takicon_marker_star, "Star"),
        PERSON("person", R.drawable.takicon_marker_person, "Person"),
        VEHICLE("vehicle", R.drawable.takicon_marker_vehicle, "Vehicle"),
        AIRCRAFT("aircraft", R.drawable.takicon_marker_aircraft, "Aircraft"),
        BOAT("boat", R.drawable.takicon_marker_boat, "Boat"),
        CIRCLE("circle", R.drawable.takicon_marker_circle, "Circle");

        override val pack get() = TakIconPack.MARKERS
        override val badgeArgb get() = 0xFF2B3440.toInt()

        companion object {
            /** Resolve a Markers icon from a `usericon` iconset path. Null when
             *  the path isn't the Markers pack; an unknown trailing token falls
             *  back to [CIRCLE] so a received marker never renders blank. */
            fun fromIconsetPath(path: String): MarkerIcon? {
                if (!path.uppercase().startsWith(TakIconPack.MARKERS.uid)) return null
                val token = path.substringAfterLast('/').lowercase()
                if (token.isEmpty()) return null
                return entries.firstOrNull { it.token == token } ?: CIRCLE
            }
        }
    }

    /**
     * ATAK "Google" POI pack — Google-Maps-style place markers. The [token] is
     * the canonical Google iconset basename (sans `.png`) so an inbound
     * `f7f71666-…/<token>` path resolves here. Glyphs are Material Symbols
     * (Apache-2.0); the Google-red badge matches the Maps pin convention.
     */
    enum class GoogleIcon(
        override val token: String,
        @get:DrawableRes override val drawableRes: Int,
        override val displayName: String,
    ) : BadgeIcon {
        AIRPORT("airports", R.drawable.takicon_google_airport, "Airport"),
        GAS("gas_stations", R.drawable.takicon_google_gas, "Gas Station"),
        HOSPITAL("hospitals", R.drawable.takicon_google_hospital, "Hospital"),
        RESTAURANT("restaurant", R.drawable.takicon_google_restaurant, "Restaurant"),
        LODGING("lodging", R.drawable.takicon_google_lodging, "Lodging"),
        PARKING("parking_lots", R.drawable.takicon_google_parking, "Parking"),
        POLICE("police", R.drawable.takicon_google_police, "Police"),
        FIRE("fire", R.drawable.takicon_google_fire, "Fire");

        override val pack get() = TakIconPack.GOOGLE
        override val badgeArgb get() = 0xFFEA4335.toInt()

        companion object {
            /** Resolve a Google POI icon from a `usericon` iconset path. The
             *  inbound basename may carry a `.png` suffix and/or a group segment
             *  (`…/Google/airports.png`); both are tolerated. Unknown tokens fall
             *  back to [AIRPORT] so a received marker never renders blank. */
            fun fromIconsetPath(path: String): GoogleIcon? {
                if (!path.uppercase().startsWith(TakIconPack.GOOGLE.uid.uppercase())) return null
                val token = path.substringAfterLast('/').substringBeforeLast('.').lowercase()
                if (token.isEmpty()) return null
                return entries.firstOrNull { it.token == token } ?: AIRPORT
            }
        }
    }

    /** Spot Map icons offered in the marker-placement picker, in ATAK order. */
    val selectableSpotIcons: List<SpotIcon> get() = SpotIcon.entries

    /** Markers-pack icons offered in the marker-placement picker. */
    val selectableMarkerIcons: List<MarkerIcon> get() = MarkerIcon.entries

    /** Google-POI-pack icons offered in the marker-placement picker. */
    val selectableGoogleIcons: List<GoogleIcon> get() = GoogleIcon.entries

    /** Packs the suite knows about, flagged by whether their assets are bundled. */
    val availablePacks: List<TakIconPack> get() = TakIconPack.entries

    // Rendered-glyph cache so the map's symbol refresh stays cheap.
    private data class Key(val token: String, val sizePx: Int)
    private val cache = LruCache<Key, Bitmap>(128)
    // Base64 PNG data-URL cache for the Cesium billboard path.
    private val dataUrlCache = LruCache<String, String>(128)

    /**
     * Resolve the badge icon for a marker (Markers / Google packs), independent
     * of bitmap rasterisation so the colour/path logic is unit-testable without
     * an Android [Context]. Spot Map is handled by [SpotIcon] separately.
     * Resolution order mirrors ATAK: explicit iconset path only (these packs
     * have no dedicated CoT type the way Spot Map's `b-m-p-s-m` does).
     */
    fun resolveBadgeIcon(iconsetPath: String?): BadgeIcon? {
        if (iconsetPath == null) return null
        MarkerIcon.fromIconsetPath(iconsetPath)?.let { return it }
        GoogleIcon.fromIconsetPath(iconsetPath)?.let { return it }
        return null
    }

    /**
     * #46 — Resolve a [FemaIconCatalog.FemaIcon] from a `usericon` iconset path.
     * Returns null when the path is not a `COT_MAPPING_FEMA/…` path.
     *
     * NOTE on artwork: this currently renders FEMA markers as a category-accented
     * colored dot (same runtime approach as Spot Map). Per-glyph SVG artwork for
     * each of the 12 FEMA kinds needs to be sourced and dropped into
     * `app/src/main/assets/fema/COT_MAPPING_FEMA/<category>/<kind>.svg`, then
     * this method updated to rasterise from the asset bundle. See #46.
     */
    fun resolveFemaIcon(iconsetPath: String?): FemaIconCatalog.FemaIcon? =
        if (iconsetPath != null) FemaIconCatalog.iconByIconsetPath(iconsetPath) else null

    /**
     * Resolve a renderable bitmap for a marker. Returns null when no TAK-suite
     * icon applies, so the caller falls back to MIL-STD-2525 affiliation art.
     *
     * @param context needed to decode the Markers / Google vector glyphs; may be
     *   null for callers that only care about Spot Map (which is fully
     *   runtime-rendered and needs no resources).
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
        context: Context? = null,
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
        // 3. FEMA / ICS catalog — rasterised from per-kind SVG glyphs (#46).
        //    Context is required; fall through to the dot only when context
        //    is null (should not happen in production — the symbol layer always
        //    has a context). If the SVG asset is somehow absent (corrupted APK),
        //    the dot ensures the marker still renders rather than disappearing.
        resolveFemaIcon(iconsetPath)?.let { femaIcon ->
            if (context != null) {
                val bmp = FemaIconCache.bitmapFor(context, femaIcon.kind, sizePx)
                if (bmp != null) return bmp
            }
            // Fallback dot — context null or asset missing.
            return spotDot(femaIcon.category.accentArgb, sizePx, "fema-dot|${femaIcon.kind.raw}|$sizePx")
        }
        // 4. Markers / Google bitmap packs — Material-Symbols (Apache-2.0)
        //    vector glyphs on a rounded badge. Needs a Context to decode.
        if (context != null) {
            resolveBadgeIcon(iconsetPath)?.let { return badge(context, it, sizePx) }
        }
        return null
    }

    /**
     * Whether this marker resolves to a TAK-suite (non-MIL-STD) icon. Lets the
     * symbol layer decide which image-registration path to take without
     * rendering twice.
     */
    fun handles(cotType: String?, iconsetPath: String?): Boolean =
        (iconsetPath != null && SpotIcon.fromIconsetPath(iconsetPath) != null) ||
            cotType == SpotIcon.COT_TYPE ||
            resolveBadgeIcon(iconsetPath) != null ||
            resolveFemaIcon(iconsetPath) != null

    /** Convenience: the rendered glyph for a selectable Spot Map icon (picker
     *  swatches, current-symbol rows). */
    fun bitmapFor(spot: SpotIcon, sizePx: Int = 64): Bitmap =
        spotDot(spot.argb, sizePx, "spot|sel|${spot.key}")

    /** Convenience: the rendered badge for a selectable Markers / Google icon. */
    fun bitmapFor(context: Context, icon: BadgeIcon, sizePx: Int = 64): Bitmap =
        badge(context, icon, sizePx)

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
            resolveBadgeIcon(iconsetPath)?.let { return "takicon-${it.pack.name.lowercase()}-${it.token}" }
            // #46/#47: FEMA iconset paths get a stable per-kind key so each of the
            // 12 kinds is registered as a distinct image in the MapLibre style.
            resolveFemaIcon(iconsetPath)?.let { return "takicon-fema-${it.kind.raw}" }
        }
        if (cotType == SpotIcon.COT_TYPE) return "takicon-spot-${argb ?: SpotIcon.WHITE.argb}"
        return null
    }

    /**
     * `data:image/png;base64,…` for a TAK-suite marker so the Cesium globe's
     * `_billboard()` renders the exact same glyph the 2D map + picker show
     * (3D parity, issue #98). Returns null when no TAK-suite icon applies.
     */
    fun dataUrlFor(
        context: Context,
        cotType: String?,
        iconsetPath: String?,
        argb: Int? = null,
        sizePx: Int = 56,
    ): String? {
        val id = styleImageId(cotType, iconsetPath, argb) ?: return null
        dataUrlCache.get(id)?.let { return it }
        val bmp = resolveBitmap(cotType, iconsetPath, argb, sizePx, context) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        val url = "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        dataUrlCache.put(id, url)
        return url
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

    /**
     * Render a Markers / Google badge: a rounded-rect chip in the pack's tint
     * with a white Material-Symbols glyph centred on it. Reads as a distinct map
     * marker family from the Spot Map dots and the MIL-STD frames.
     */
    private fun badge(context: Context, icon: BadgeIcon, sizePx: Int): Bitmap {
        val key = Key("badge|${icon.pack.name}|${icon.token}", sizePx)
        cache.get(key)?.let { return it }

        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val inset = sizePx * 0.10f
        val rect = RectF(inset, inset, sizePx - inset, sizePx - inset)
        val radius = sizePx * 0.26f

        // Drop shadow + filled chip in the pack tint.
        val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = icon.badgeArgb
            style = Paint.Style.FILL
            setShadowLayer(sizePx * 0.06f, 0f, sizePx * 0.02f, AndroidColor.argb(150, 0, 0, 0))
        }
        canvas.drawRoundRect(rect, radius, radius, chip)

        // Thin white outline so dark chips read on dark imagery.
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(220, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = (sizePx * 0.04f).coerceAtLeast(1f)
        }
        canvas.drawRoundRect(rect, radius, radius, outline)

        // Centre the white glyph at ~60% of the badge.
        val glyphSize = (sizePx * 0.60f).toInt().coerceAtLeast(1)
        val drawable = ContextCompat.getDrawable(context, icon.drawableRes)
        if (drawable != null) {
            drawable.colorFilter = PorterDuffColorFilter(AndroidColor.WHITE, PorterDuff.Mode.SRC_IN)
            val glyph = drawable.toBitmap(glyphSize, glyphSize, Bitmap.Config.ARGB_8888)
            val left = (sizePx - glyphSize) / 2f
            val top = (sizePx - glyphSize) / 2f
            canvas.drawBitmap(glyph, left, top, Paint(Paint.ANTI_ALIAS_FLAG))
        }

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

    /** Clear the caches — call from `onTrimMemory` or test teardown. */
    fun clear() {
        cache.evictAll()
        dataUrlCache.evictAll()
    }
}
