package soy.engindearing.omnitak.mobile.ui.components

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textAnchor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.symbology.IconPackRegistry
import soy.engindearing.omnitak.mobile.data.symbology.MilStdIconCache
import soy.engindearing.omnitak.mobile.data.symbology.MilStdIconService
import soy.engindearing.omnitak.mobile.data.symbology.TakIconRegistry

/**
 * Experimental GeoJSON-source-driven contacts layer. Holds a single
 * [GeoJsonSource] of feature points + one [SymbolLayer] that draws
 * the MIL-STD-2525 icon for each contact via a data-driven
 * `icon-image` expression. One GL upload per [update] call instead
 * of N marker mutations — the path TAKAware, ATAK, and most
 * production map clients use to handle hundreds of CoT contacts.
 *
 * History to be aware of: an earlier GeoJsonSource + CircleLayer
 * attempt was reverted in commit 0813351 because the Adreno 610
 * fragment pipeline silently failed to paint registered layers
 * (the layer was queryable but never drew pixels). LocationComponent
 * — which uses [Style.addImage] with a bitmap — has always rendered
 * correctly on the same driver, so we exercise the same image
 * registration path here. If this layer also fails to paint on
 * Adreno 610, the user-prefs toggle [UserPrefs.experimentalSymbolLayer]
 * lets the operator fall back to the working Marker-annotation
 * [ContactLayer] without a rebuild.
 *
 * Lifecycle contract:
 *  1. Caller invokes [installInto] once after the [Style] is loaded,
 *     passing the [Context] used for SVG rasterisation. Registers
 *     the source + layer + a pre-warmed set of base SIDC images.
 *  2. Caller invokes [update] with the current contacts collection
 *     whenever the contact store changes. Each unique SIDC seen for
 *     the first time is rasterised and registered via [Style.addImage]
 *     on the fly; subsequent updates reuse the registered image.
 *  3. The instance is single-style-scoped. If the map style is
 *     re-loaded the caller must construct a fresh instance and
 *     re-install.
 */
class ContactSymbolLayer {

    companion object {
        private const val TAG = "ContactSymbolLayer"

        // Source + layer ids — exposed for tests and for callers
        // that need to query the style by id (e.g. visibility
        // toggling without recreating the layer).
        const val SOURCE_ID = "omnitak-contacts-source"
        const val SYMBOL_LAYER_ID = "omnitak-contacts-symbols"
        const val LABEL_LAYER_ID = "omnitak-contacts-labels"

        // Feature property keys.
        const val PROP_UID = "uid"
        const val PROP_SIDC = "sidc"
        const val PROP_CALLSIGN = "callsign"
        const val PROP_AFFILIATION = "affiliation"
        const val PROP_TYPE = "cotType"
        // Fully-resolved MapLibre image name for this feature. Either
        // `milstd-<sidc>` (MIL-STD-2525 path) or a TAK-icon-suite id
        // (`takicon-spot-…`) when the contact resolves to a bundled iconset
        // — issue #98. The symbol layer reads icon-image straight off this so
        // the two symbol families coexist without colliding.
        const val PROP_IMAGE = "iconImage"

        // Pixel size the cache rasterises at. 64 matches the legacy
        // Marker path and is the canonical milsymbol size.
        private const val ICON_PIXEL_SIZE = 64

        // Image name prefix in [Style.addImage]. Keyed by SIDC so
        // each distinct symbol is uploaded exactly once per style.
        const val ICON_IMAGE_PREFIX = "milstd-"
    }

    private var installed: Boolean = false
    private val registeredSidcs: MutableSet<String> = mutableSetOf()

    /**
     * Wire the source + layer into [style] and pre-warm the cache
     * with a small set of common SIDCs so the first contact sighting
     * doesn't pay the SVG rasterisation cost on the rendering frame.
     * Idempotent — calling on the same style twice is a no-op.
     */
    fun installInto(style: Style, context: Context) {
        if (installed) return
        installed = true

        // Empty FeatureCollection — populated by the first [update] call.
        val source = GeoJsonSource(SOURCE_ID, emptyFeatureCollectionJson())
        style.addSource(source)

        val symbolLayer = SymbolLayer(SYMBOL_LAYER_ID, SOURCE_ID).withProperties(
            // Per-feature resolved image name (MIL-STD `milstd-<sidc>` or a TAK
            // icon-suite id). Computed in [featureJson] so the iconset-path
            // branch (#98) and the SIDC branch share one rendering pass.
            iconImage(Expression.get(PROP_IMAGE)),
            // Slight visual scale-down so the 64 px raster reads as a
            // map marker, not a sticker. Matches the legacy MarkerOptions
            // sizing on the Annotation path.
            iconSize(0.6f),
            iconAllowOverlap(true),
            iconIgnorePlacement(true),
        )
        style.addLayer(symbolLayer)

        val labelLayer = SymbolLayer(LABEL_LAYER_ID, SOURCE_ID).withProperties(
            textField(Expression.get(PROP_CALLSIGN)),
            textSize(10f),
            textColor("#FFFFFF"),
            textHaloColor("#000000"),
            textHaloWidth(1.2f),
            textOffset(arrayOf(0f, 1.4f)),
            textAnchor("top"),
            textAllowOverlap(false),
            textIgnorePlacement(false),
        )
        style.addLayer(labelLayer)

        // Pre-warm common SIDCs so the first sighting of a friend
        // / hostile / unknown / multirotor doesn't rasterize on the
        // render frame. Adding these to the style up front also
        // exercises the same code path on the Adreno 610 driver
        // that LocationComponent's foregroundName uses — if any of
        // these fail to register, we know the GL bug applies and
        // can fall back to the Marker path before any contacts
        // even arrive.
        for (cotType in PRE_WARM_COT_TYPES) {
            registerSidcImage(style, context, MilStdIconService.getSidc(cotType))
        }
    }

    /**
     * Push the current contacts list to the GeoJsonSource. Any SIDC
     * not yet registered in [Style] is rasterised + added before the
     * source is updated. Contacts with NaN lat/lon are skipped.
     *
     * @return number of distinct SIDCs newly registered on this call
     *   (useful for tests + diagnostics).
     */
    fun update(map: MapLibreMap, context: Context, contacts: Collection<CoTEvent>): Int {
        val style = map.style ?: return 0
        if (!installed) {
            Log.w(TAG, "update() before installInto() — no-op")
            return 0
        }

        // First pass: make sure every image referenced by the
        // FeatureCollection has been registered. Skip rows with bad
        // coordinates. Issue #98 — a contact carrying a TAK-suite iconset
        // (Spot Map today; Markers/Google when a clean pack lands) resolves to
        // a bundled icon FIRST; everything else falls back to MIL-STD-2525.
        var newlyRegistered = 0
        val features = JSONArray()
        for (c in contacts) {
            if (c.lat.isNaN() || c.lon.isNaN()) continue
            val sidc = MilStdIconService.getSidc(c.type)
            val takImageId = registerTakIconImage(style, context, c)
            if (takImageId == null && registerSidcImage(style, context, sidc)) {
                newlyRegistered++
            }
            val imageName = takImageId ?: (ICON_IMAGE_PREFIX + sidc)
            features.put(featureJson(c, sidc, imageName))
        }

        GeoJsonLayerFeeder.push(style, SOURCE_ID, features)
        return newlyRegistered
    }

    /**
     * Issue #98 — resolve and register an icon for [c], trying in resolution order:
     * 1. Imported custom iconset pack ([IconPackRegistry]) — Phase 2.
     * 2. Bundled TAK icon-suite (Spot Map / Markers / Google) — Phase 1.
     * 3. Returns null → caller falls through to the MIL-STD-2525 path.
     *
     * The `iconsetPath` is consulted FIRST, before any SIDC lookup — exactly the
     * resolution order ATAK / iTAK use.
     */
    private fun registerTakIconImage(style: Style, context: Context, c: CoTEvent): String? {
        // 1. Imported custom iconset pack (Phase 2).
        val registry = IconPackRegistry.get(context)
        val imported = registry.resolve(c.iconsetPath)
        if (imported != null) {
            val (pack, icon) = imported
            val imageId = registry.styleImageId(c.iconsetPath!!)
            if (imageId !in registeredSidcs) {
                val bitmap = registry.loadBitmap(pack, icon)
                if (bitmap != null) {
                    style.addImage(imageId, bitmap)
                    registeredSidcs.add(imageId)
                }
            }
            // Return the id even if the bitmap was missing — avoids repeated
            // load attempts; the symbol will silently hide (no crash).
            if (imageId in registeredSidcs) return imageId
        }

        // 2. Bundled TAK icon-suite (Phase 1 — Spot Map / Markers / Google / FEMA).
        if (!TakIconRegistry.handles(c.type, c.iconsetPath)) return null
        val argb = c.colorArgb?.let { TakIconRegistry.normalizeArgb(it) }
        val imageId = TakIconRegistry.styleImageId(c.type, c.iconsetPath, argb) ?: return null
        if (imageId in registeredSidcs) return imageId
        val bitmap = TakIconRegistry.resolveBitmap(
            cotType = c.type,
            iconsetPath = c.iconsetPath,
            argb = argb,
            sizePx = ICON_PIXEL_SIZE,
            context = context,
        ) ?: return null
        style.addImage(imageId, bitmap)
        registeredSidcs.add(imageId)
        return imageId
    }

    /**
     * Register the icon for [sidc] in [style] if it isn't already.
     * Returns true if this call added a new image; false if it was
     * already present.
     */
    private fun registerSidcImage(style: Style, context: Context, sidc: String): Boolean {
        if (sidc in registeredSidcs) return false
        val cotType = cotTypeForSidc(sidc)
        val bitmap = MilStdIconCache.bitmapFor(context, cotType, ICON_PIXEL_SIZE)
            ?: MilStdIconCache.bitmapFor(context, "a-u-A", ICON_PIXEL_SIZE)
            ?: run {
                Log.w(TAG, "no bitmap available for sidc=$sidc; skipping addImage")
                return false
            }
        style.addImage(ICON_IMAGE_PREFIX + sidc, bitmap)
        registeredSidcs.add(sidc)
        return true
    }

    /**
     * Reverse-resolve a SIDC back to a plausible CoT type so we can
     * call [MilStdIconCache.bitmapFor] (which takes cotType, not
     * SIDC). Uses the service's existing reverse map when present;
     * falls back to plain "a-u-A" so we still register something.
     */
    private fun cotTypeForSidc(sidc: String): String {
        return MilStdIconService.getDefinitionBySidc(sidc)?.value ?: "a-u-A"
    }

    private fun featureJson(c: CoTEvent, sidc: String, imageName: String): JSONObject {
        return JSONObject().apply {
            put("type", "Feature")
            put("geometry", JSONObject().apply {
                put("type", "Point")
                put("coordinates", JSONArray().apply {
                    put(c.lon)
                    put(c.lat)
                })
            })
            put("properties", JSONObject().apply {
                put(PROP_UID, c.uid)
                put(PROP_SIDC, sidc)
                put(PROP_IMAGE, imageName)
                put(PROP_CALLSIGN, c.callsign ?: c.uid)
                put(PROP_AFFILIATION, c.affiliation.code.toString())
                put(PROP_TYPE, c.type)
            })
        }
    }

    private fun emptyFeatureCollectionJson(): String =
        """{"type":"FeatureCollection","features":[]}"""
}

/** CoT types whose icons we register at install time so the first
 *  sighting doesn't rasterise on the render frame. Chosen to cover
 *  the common contact mix: friend/hostile/neutral/unknown ground +
 *  the RID UAS multirotor + fixed-wing default. */
private val PRE_WARM_COT_TYPES: List<String> = listOf(
    "a-f-G-U",
    "a-h-G-U",
    "a-n-G-U",
    "a-u-G-U",
    "a-f-G-U-C-I",
    "a-u-A",
    "a-u-A-M-H-Q",
    "a-u-A-M-F-Q",
    "a-u-G", // RID operator / pilot marker
)
