package soy.engindearing.omnitak.mobile.data.symbology

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import androidx.collection.LruCache
import com.caverock.androidsvg.SVG
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import java.io.IOException

/**
 * Process-wide cache of rendered MIL-STD-2525 symbols, keyed by SIDC
 * code + pixel size. Loads SVGs from `assets/milstd/{sidc}.svg`,
 * rasterises them with AndroidSVG, and wraps the resulting bitmap in
 * a MapLibre [Icon] for use with the Marker annotation API.
 *
 * Sized as 96 entries — well under typical heap pressure even at four
 * size variants per SIDC, and enough to hold the full Phase C catalog
 * resident once it ships.
 *
 * Bitmaps are not recycled by the cache. The LruCache holds strong
 * refs while the entry is live; once evicted, the underlying bitmap
 * remains valid until MapLibre's own marker bookkeeping releases its
 * reference. Calling [Bitmap.recycle] here would risk crashing the
 * native annotation overlay mid-render.
 */
object MilStdIconCache {

    private const val TAG = "MilStdIconCache"
    // 64 px is the canonical milsymbol render size used by ATAK and
    // matches the Marker annotation overlay's natural sizing. Larger
    // values give more legible interior detail (rotor caret on
    // SUAPMHQ multirotor, wing detail on fixed-wing UAS) but
    // exacerbate cold-cache churn on every size change and visually
    // dominate neighbouring contacts. Revisit if high-density CoT
    // rendering is ever re-engineered.
    private const val DEFAULT_SIZE_PX = 64

    private data class Key(val sidc: String, val sizePx: Int)

    private val cache = LruCache<Key, Icon>(96)

    /**
     * Resolve the [Icon] for a CoT type. Always succeeds — falls back
     * to the per-affiliation default SIDC if the asset can't be loaded.
     */
    fun iconFor(
        context: Context,
        cotType: String,
        sizePx: Int = DEFAULT_SIZE_PX,
    ): Icon {
        val sidc = MilStdIconService.getSidc(cotType)
        return iconForSidc(context, sidc, sizePx)
            ?: iconForSidc(context, fallbackSidc(cotType), sizePx)
            ?: error("no usable MIL-STD icon — assets/milstd/ missing or unreadable")
    }

    /** Internal — looks up by raw SIDC, returns null when the asset is missing. */
    private fun iconForSidc(context: Context, sidc: String, sizePx: Int): Icon? {
        val key = Key(sidc, sizePx)
        cache.get(key)?.let { return it }

        val bitmap = renderSvgToBitmap(context, sidc, sizePx) ?: return null
        val icon = IconFactory.getInstance(context).fromBitmap(bitmap)
        cache.put(key, icon)
        return icon
    }

    /**
     * Resolve the raw [Bitmap] for a CoT type. Callers that need to
     * register an image with the MapLibre style directly (e.g.
     * `style.addImage(...)` for LocationComponent's `foregroundName`)
     * use this; everyone else uses [iconFor]. Returns null if every
     * fallback asset is missing — caller decides what to do (e.g.
     * keep the existing drawable resource path).
     */
    fun bitmapFor(context: Context, cotType: String, sizePx: Int = DEFAULT_SIZE_PX): Bitmap? {
        val sidc = MilStdIconService.getSidc(cotType)
        renderSvgToBitmap(context, sidc, sizePx)?.let { return it }
        return renderSvgToBitmap(context, fallbackSidc(cotType), sizePx)
    }

    private fun renderSvgToBitmap(context: Context, sidc: String, sizePx: Int): Bitmap? {
        val assetPath = "milstd/$sidc.svg"
        return try {
            context.assets.open(assetPath).use { stream ->
                val svg = SVG.getFromInputStream(stream)
                svg.setDocumentWidth(sizePx.toFloat())
                svg.setDocumentHeight(sizePx.toFloat())
                val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
                svg.renderToCanvas(Canvas(bitmap))
                bitmap
            }
        } catch (e: IOException) {
            // Missing asset — caller falls back to the per-affiliation default.
            Log.d(TAG, "asset miss: $assetPath")
            null
        } catch (e: com.caverock.androidsvg.SVGParseException) {
            Log.w(TAG, "SVG parse failed for $assetPath", e)
            null
        }
    }

    private fun fallbackSidc(cotType: String): String =
        when (Affiliation.fromCotType(cotType)) {
            Affiliation.FRIENDLY -> "SFGPU------"
            Affiliation.HOSTILE -> "SHGPU------"
            Affiliation.NEUTRAL -> "SNGPU------"
            Affiliation.UNKNOWN -> "SUGPU------"
        }

    /**
     * Clear the cache — call from `onTrimMemory(TRIM_MEMORY_RUNNING_LOW)`
     * or test setup. Safe to call from any thread.
     */
    fun clear() {
        cache.evictAll()
    }
}
