package soy.engindearing.omnitak.mobile.data.symbology

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import androidx.collection.LruCache
import com.caverock.androidsvg.SVG
import soy.engindearing.omnitak.mobile.data.FemaIconCatalog
import java.io.IOException

/**
 * Process-wide cache of rasterised FEMA / ICS-237 glyph icons for the
 * [soy.engindearing.omnitak.mobile.ui.components.ContactSymbolLayer] and
 * the legacy [soy.engindearing.omnitak.mobile.ui.components.ContactLayer].
 *
 * SVGs live under `assets/fema/<category>/<kind>.svg` — exactly the path
 * structure the [FemaIconCatalog.iconsetPath] emits via
 * `COT_MAPPING_FEMA/<category>/<kind>`.  That makes the asset address a
 * direct transform of the iconsetPath:
 *
 *   "COT_MAPPING_FEMA/incidentCommand/command_post"
 *   → "fema/incidentCommand/command_post.svg"
 *
 * Receivers without the FEMA asset bundle (third-party ATAK, iTAK) are
 * unaffected — they never call into this cache; their fallback is the
 * MIL-STD friendly-installation rendering driven by the CoT type.
 *
 * Closes #46.
 */
object FemaIconCache {

    private const val TAG = "FemaIconCache"
    private const val DEFAULT_SIZE_PX = 64

    /**
     * Stable image-name prefix used when registering FEMA bitmaps in a
     * MapLibre [org.maplibre.android.maps.Style] via `addImage`.  Callers
     * must use this prefix so the symbol-layer `icon-image` expression can
     * match against it (e.g. `"fema-command_post"`).
     */
    const val ICON_IMAGE_PREFIX = "fema-"

    private data class Key(val assetPath: String, val sizePx: Int)

    private val cache = LruCache<Key, Bitmap>(64)

    /**
     * Resolve the [Bitmap] for the given FEMA [kind].
     *
     * Returns null only when the underlying asset is missing or unparseable,
     * which should never happen in a correctly packaged build.
     */
    fun bitmapFor(
        context: Context,
        kind: FemaIconCatalog.Kind,
        sizePx: Int = DEFAULT_SIZE_PX,
    ): Bitmap? {
        val icon = FemaIconCatalog.iconFor(kind) ?: return null
        return bitmapForPath(context, assetPathFor(icon), sizePx)
    }

    /**
     * Resolve by [iconsetPath] string — the value parsed from an inbound
     * CoT `<usericon iconsetpath="COT_MAPPING_FEMA/...">` element.  Returns
     * null when [iconsetPath] doesn't map to a known FEMA kind.
     */
    fun bitmapForIconsetPath(
        context: Context,
        iconsetPath: String,
        sizePx: Int = DEFAULT_SIZE_PX,
    ): Bitmap? {
        val icon = FemaIconCatalog.iconByIconsetPath(iconsetPath) ?: return null
        return bitmapForPath(context, assetPathFor(icon), sizePx)
    }

    /**
     * Stable image key for [Style.addImage] registration.  Callers that
     * register a bitmap in the MapLibre style should use this key so the
     * symbol layer can reference it via [ICON_IMAGE_PREFIX] expressions.
     */
    fun imageKeyFor(kind: FemaIconCatalog.Kind): String = ICON_IMAGE_PREFIX + kind.raw

    /** Clear the cache — e.g. on `onTrimMemory(TRIM_MEMORY_RUNNING_LOW)`. */
    fun clear() {
        cache.evictAll()
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    private fun assetPathFor(icon: FemaIconCatalog.FemaIcon): String {
        // iconsetPath is "COT_MAPPING_FEMA/<category>/<kind>"
        // asset path is  "fema/<category>/<kind>.svg"
        val segments = icon.iconsetPath.split('/')
        // segments[0] = "COT_MAPPING_FEMA", [1] = category, [2] = kind
        return "fema/${segments[1]}/${segments[2]}.svg"
    }

    private fun bitmapForPath(context: Context, assetPath: String, sizePx: Int): Bitmap? {
        val key = Key(assetPath, sizePx)
        cache.get(key)?.let { return it }

        return try {
            context.assets.open(assetPath).use { stream ->
                val svg = SVG.getFromInputStream(stream)
                svg.setDocumentWidth(sizePx.toFloat())
                svg.setDocumentHeight(sizePx.toFloat())
                val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
                svg.renderToCanvas(Canvas(bitmap))
                cache.put(key, bitmap)
                bitmap
            }
        } catch (e: IOException) {
            Log.w(TAG, "FEMA asset missing: $assetPath")
            null
        } catch (e: com.caverock.androidsvg.SVGParseException) {
            Log.w(TAG, "SVG parse failed for $assetPath", e)
            null
        }
    }
}
