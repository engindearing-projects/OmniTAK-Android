package soy.engindearing.omnitak.mobile.ui.components

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.plugins.annotation.Symbol
import org.maplibre.android.plugins.annotation.SymbolManager
import org.maplibre.android.plugins.annotation.SymbolOptions
import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.symbology.IconPackRegistry
import soy.engindearing.omnitak.mobile.data.symbology.MilStdIconCache
import soy.engindearing.omnitak.mobile.data.symbology.MilStdIconService
import soy.engindearing.omnitak.mobile.data.symbology.TakIconRegistry

/**
 * Issue #77 — renders CoT contacts through MapLibre's [SymbolManager]
 * (the `android-plugin-annotation-v9` annotation pipeline), NOT a GeoJSON
 * [org.maplibre.android.style.layers.SymbolLayer] / CircleLayer.
 *
 * ## Why
 * Contacts previously rendered only through GeoJSON-source layers
 * ([ContactLayer]'s inline `contacts-src` CircleLayer + [ContactSymbolLayer]'s
 * runtime `addImage` SymbolLayer). On Adreno 610 / Mali-G57 / Pixel Immortalis
 * those point layers are *queryable but never draw pixels* — a MapLibre-Android
 * fragment-pipeline failure (the codebase had already reverted a CircleLayer over
 * it; see [ContactSymbolLayer]'s history note). A full style reload does **not**
 * fix it (confirmed on-device, issue #77), ruling out repaint/atlas timing — it
 * is the layer *type*. The self-marker renders fine because it is a
 * [org.maplibre.android.location.LocationComponent] (annotation/bitmap path).
 *
 * The annotation plugin's [SymbolManager] is the same render-path family as
 * LocationComponent, so it shares the pipeline that *does* paint on these
 * drivers. Drawings (Fill/Line layers) paint fine and are untouched — this is
 * only the contact point markers.
 *
 * ## Design
 *  - One [SymbolManager] for all contacts, created against the live [MapView] +
 *    [MapLibreMap] + [Style] once the style is loaded.
 *  - The contact → render mapping ([ContactSymbolSpec]) and the add/update/remove
 *    diff ([ContactSymbolDiff]) are pure, unit-tested classes; this class is the
 *    thin device adapter that applies their output to real [Symbol]s.
 *  - Bitmaps are registered via [Style.addImage] using the *exact* keys + caches
 *    ([MilStdIconCache] / [TakIconRegistry] / [IconPackRegistry]) that
 *    [ContactSymbolLayer] used, so the icon glyphs are identical to before — only
 *    the layer carrying them changed.
 *  - Team color is preserved: MIL-STD frames are tinted via `iconColor`, the
 *    callsign is drawn as `textField` with a halo, and TAK-suite glyphs keep
 *    their baked-in colors (no tint). This guards against the historic
 *    "all blue, no callsign" regression the GeoJSON path was added to fix.
 *  - Single-style-scoped, mirroring [ContactSymbolLayer]: a style reload wipes
 *    annotation layers, so the caller constructs a fresh instance per style.
 */
class ContactSymbolManager(
    private val onContactTap: ((CoTEvent) -> Unit)?,
) {
    companion object {
        private const val TAG = "ContactSymbolManager"

        // Same 64 px raster the legacy Marker path + ContactSymbolLayer use.
        private const val ICON_PIXEL_SIZE = 64

        // Icon scale-down so the 64 px raster reads as a marker, not a sticker —
        // matches ContactSymbolLayer.iconSize(0.6f) and the legacy MarkerOptions.
        private const val ICON_SIZE = 0.6f
        private const val TEXT_SIZE = 10f
        private const val TEXT_HALO_WIDTH = 1.2f

        // gson data key — lets the click listener map a tapped Symbol back to the
        // contact uid (SymbolManager hands back a Symbol, not the CoTEvent).
        private const val DATA_UID = "uid"
    }

    private var manager: SymbolManager? = null
    private var installed = false

    /** Live render state, keyed by contact uid: the spec last applied and the
     *  Symbol handle for it. [ContactSymbolDiff] consumes [specByUid]; [symbolByUid]
     *  is how we update/delete the matching annotation. */
    private val specByUid = LinkedHashMap<String, ContactSymbolSpec>()
    private val symbolByUid = HashMap<String, Symbol>()

    /** uid → contact, so the tap listener resolves the original [CoTEvent] for
     *  [onContactTap] without re-deriving it from the Symbol's geometry. */
    private val contactByUid = HashMap<String, CoTEvent>()

    /** Image ids already registered on the current style — addImage is idempotent
     *  but skipping the re-raster keeps updates cheap. */
    private val registeredImages = HashSet<String>()

    /**
     * Create the [SymbolManager] on [style] and wire the tap listener. Idempotent.
     * Mirrors [ContactSymbolLayer.installInto]'s contract: call once per style.
     */
    fun installInto(mapView: MapView, map: MapLibreMap, style: Style) {
        if (installed) return
        installed = true

        val mgr = SymbolManager(mapView, map, style).apply {
            // Tactical markers must never be decluttered away — every contact is
            // load-bearing. Matches ContactSymbolLayer's icon overlap flags.
            iconAllowOverlap = true
            iconIgnorePlacement = true
            // Labels may collide-hide (matches ContactSymbolLayer's label layer):
            // overlapping callsigns are illegible, the icon still shows.
            textAllowOverlap = false
            textIgnorePlacement = false
        }
        mgr.addClickListener { symbol ->
            val cb = onContactTap
            val uid = symbol.data?.asJsonObject?.get(DATA_UID)?.asString
            val contact = uid?.let { contactByUid[it] }
            if (cb != null && contact != null) {
                cb(contact)
                true // consume — this tap hit a contact
            } else {
                false
            }
        }
        manager = mgr
    }

    /**
     * Diff [contacts] against the current symbols and apply the minimal mutation
     * set. Skips contacts with NaN lat/lon (same guard as the GeoJSON path).
     * Registers any not-yet-seen icon image via [Style.addImage] before its
     * Symbol references it, so SymbolManager never points at a missing image.
     *
     * @return number of symbols created on this call (diagnostics + tests).
     */
    fun update(context: Context, style: Style, contacts: Collection<CoTEvent>): Int {
        val mgr = manager
        if (mgr == null || !installed) {
            Log.w(TAG, "update() before installInto() — no-op")
            return 0
        }

        // Build the desired spec set, registering each contact's icon bitmap.
        // contactByUid is rebuilt so the tap listener always resolves the current
        // CoTEvent (position/callsign may have changed since last tick).
        val target = ArrayList<ContactSymbolSpec>(contacts.size)
        val freshContacts = HashMap<String, CoTEvent>(contacts.size)
        for (c in contacts) {
            if (c.lat.isNaN() || c.lon.isNaN()) continue
            val spec = resolveAndRegister(context, style, c)
            target.add(spec)
            freshContacts[c.uid] = c
        }
        contactByUid.clear()
        contactByUid.putAll(freshContacts)

        val diff = ContactSymbolDiff.compute(current = specByUid, target = target)
        if (diff.isEmpty) return 0

        for (uid in diff.toRemove) {
            symbolByUid.remove(uid)?.let { mgr.delete(it) }
            specByUid.remove(uid)
        }
        for (spec in diff.toUpdate) {
            val symbol = symbolByUid[spec.uid]
            if (symbol != null) {
                applyTo(symbol, spec)
                mgr.update(symbol)
            } else {
                // Defensive: spec said update but we lost the handle — recreate.
                symbolByUid[spec.uid] = mgr.create(optionsFor(spec))
            }
            specByUid[spec.uid] = spec
        }
        var created = 0
        for (spec in diff.toAdd) {
            symbolByUid[spec.uid] = mgr.create(optionsFor(spec))
            specByUid[spec.uid] = spec
            created++
        }
        return created
    }

    /** Tear down all symbols (e.g. before a style reload). */
    fun clear() {
        manager?.deleteAll()
        symbolByUid.clear()
        specByUid.clear()
        contactByUid.clear()
        registeredImages.clear()
    }

    // ── icon resolution + image registration ────────────────────────────────
    // Mirrors ContactSymbolLayer's resolution order exactly so the glyphs are
    // byte-identical — only the layer carrying them changed.

    private fun resolveAndRegister(context: Context, style: Style, c: CoTEvent): ContactSymbolSpec {
        val takImageId = registerTakIconImage(style, context, c)
        if (takImageId != null) {
            return ContactSymbolSpec.from(c, resolvedImageId = takImageId)
        }
        val sidc = MilStdIconService.getSidc(c.type)
        registerSidcImage(style, context, sidc)
        return ContactSymbolSpec.from(c, resolvedImageId = ContactSymbolLayer.ICON_IMAGE_PREFIX + sidc)
    }

    /** Issue #98 resolution order: imported pack → bundled TAK suite → null. */
    private fun registerTakIconImage(style: Style, context: Context, c: CoTEvent): String? {
        val registry = IconPackRegistry.get(context)
        val imported = registry.resolve(c.iconsetPath)
        if (imported != null) {
            val (pack, icon) = imported
            val imageId = registry.styleImageId(c.iconsetPath!!)
            if (imageId !in registeredImages) {
                val bitmap = registry.loadBitmap(pack, icon)
                if (bitmap != null) {
                    style.addImage(imageId, bitmap)
                    registeredImages.add(imageId)
                }
            }
            if (imageId in registeredImages) return imageId
        }

        if (!TakIconRegistry.handles(c.type, c.iconsetPath)) return null
        val argb = c.colorArgb?.let { TakIconRegistry.normalizeArgb(it) }
        val imageId = TakIconRegistry.styleImageId(c.type, c.iconsetPath, argb) ?: return null
        if (imageId in registeredImages) return imageId
        val bitmap = TakIconRegistry.resolveBitmap(
            cotType = c.type,
            iconsetPath = c.iconsetPath,
            argb = argb,
            sizePx = ICON_PIXEL_SIZE,
            context = context,
        ) ?: return null
        style.addImage(imageId, bitmap)
        registeredImages.add(imageId)
        return imageId
    }

    private fun registerSidcImage(style: Style, context: Context, sidc: String) {
        val imageId = ContactSymbolLayer.ICON_IMAGE_PREFIX + sidc
        if (imageId in registeredImages) return
        val cotType = MilStdIconService.getDefinitionBySidc(sidc)?.value ?: "a-u-A"
        val bitmap = MilStdIconCache.bitmapFor(context, cotType, ICON_PIXEL_SIZE)
            ?: MilStdIconCache.bitmapFor(context, "a-u-A", ICON_PIXEL_SIZE)
            ?: run {
                Log.w(TAG, "no bitmap for sidc=$sidc; symbol will hide rather than crash")
                return
            }
        style.addImage(imageId, bitmap)
        registeredImages.add(imageId)
    }

    // ── spec → SymbolOptions / Symbol ───────────────────────────────────────

    private fun optionsFor(spec: ContactSymbolSpec): SymbolOptions =
        SymbolOptions()
            .withLatLng(LatLng(spec.lat, spec.lon))
            .withIconImage(spec.imageId)
            .withIconSize(ICON_SIZE)
            .withTextField(spec.callsign)
            .withTextSize(TEXT_SIZE)
            // Team color rides on the callsign label — icon-color only tints SDF
            // icons, and our MIL-STD / TAK-suite glyphs are full-color rasters
            // (see ContactSymbolSpec). A dark halo keeps any team color legible on
            // light or dark basemaps.
            .withTextColor(argbToCssHex(spec.teamColorArgb))
            .withTextHaloColor("#000000")
            .withTextHaloWidth(TEXT_HALO_WIDTH)
            .withTextOffset(arrayOf(0f, 1.4f))
            .withTextAnchor("top")
            .withData(dataFor(spec))

    private fun applyTo(symbol: Symbol, spec: ContactSymbolSpec) {
        symbol.latLng = LatLng(spec.lat, spec.lon)
        symbol.iconImage = spec.imageId
        symbol.textField = spec.callsign
        symbol.textColor = argbToCssHex(spec.teamColorArgb)
        symbol.data = dataFor(spec)
    }

    private fun dataFor(spec: ContactSymbolSpec): JsonObject =
        JsonObject().apply { addProperty(DATA_UID, spec.uid) }
}

/** ARGB int → `#RRGGBB` CSS hex (alpha dropped) for MapLibre color strings.
 *  Same conversion [ContactLayer.argbToHex] uses; kept here so this file has no
 *  cross-class call into the GeoJSON path we are replacing. */
internal fun argbToCssHex(argb: Int): String = String.format("#%06X", argb and 0x00FFFFFF)
