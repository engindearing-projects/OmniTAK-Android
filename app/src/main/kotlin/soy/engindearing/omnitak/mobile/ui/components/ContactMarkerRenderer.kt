package soy.engindearing.omnitak.mobile.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import soy.engindearing.omnitak.mobile.data.CoTEvent

/**
 * Renders CoT contacts (locally-dropped markers + received tracks) as native
 * MapLibre annotations (`addMarker`) instead of a GeoJsonSource circle/symbol
 * layer.
 *
 * Why: on Adreno / Mali / the emulator GL translator, the GeoJsonSource
 * circle-color + symbol pipeline silently fails to rasterize, so contacts that
 * are pushed to `contacts-src` never appear on the 2D map even though they show
 * on the Cesium globe (#77, reported by u/mozios). The data feed is fine —
 * `ContactLayer: pushed N contacts` logs every update — the GL driver just will
 * not paint that layer. The Annotation API uses the same native renderer as
 * LocationComponent, which paints across those drivers (see
 * project_omnitak_android_marker_gpu_bug and the KML fix, KmlMarkerRenderer).
 *
 * Each contact becomes a teardrop pin tinted with [CoTEvent.displayColor] (team
 * color when present, else MIL-STD affiliation color) with the callsign baked in
 * as an always-on label — so this keeps the team-color + callsign that the
 * original `addMarker` contact path lost (the "all blue, no callsign" regression
 * that drove the move to a GeoJsonSource, commit 0813351). The pin tip is the
 * bottom-center anchor, so the label sits ABOVE the head and the tip stays on the
 * exact coordinate.
 *
 * Viewport-cull + a marker budget keep the annotation count bounded; markers are
 * recomputed on every contacts update and on every camera-idle (pan/zoom).
 */
object ContactMarkerRenderer {
    private var markers: List<Marker> = emptyList()
    // Pin+label bitmaps are expensive to draw; reuse by (color, callsign).
    private val iconCache = HashMap<String, Icon>()

    private var boundMap: MapLibreMap? = null
    private var ctx: Context? = null
    private var contacts: List<CoTEvent> = emptyList()
    private var idleListener: MapLibreMap.OnCameraIdleListener? = null

    // Cap live annotations so a flood of CoT contacts can't jank the main thread.
    private const val MAX_MARKERS = 500

    /** Replace the rendered contacts; (re)registers the camera-idle re-render. */
    fun update(map: MapLibreMap, context: Context, contacts: Collection<CoTEvent>) {
        this.ctx = context
        this.contacts = contacts.toList()
        if (boundMap !== map) {
            idleListener?.let { l -> runCatching { boundMap?.removeOnCameraIdleListener(l) } }
            boundMap = map
            val l = MapLibreMap.OnCameraIdleListener { render() }
            map.addOnCameraIdleListener(l)
            idleListener = l
        }
        render()
    }

    /** Viewport-cull the current contacts and (re)place native markers. */
    private fun render() {
        val map = boundMap ?: return
        val context = ctx ?: return
        markers.forEach { runCatching { map.removeMarker(it) } }
        val proj = map.projection
        val bounds = runCatching { proj.visibleRegion.latLngBounds }.getOrNull()
        val factory = IconFactory.getInstance(context)
        val added = ArrayList<Marker>()
        var budget = MAX_MARKERS
        for (c in contacts) {
            if (budget <= 0) break
            if (c.lat.isNaN() || c.lon.isNaN()) continue
            val ll = LatLng(c.lat, c.lon)
            if (bounds != null && !bounds.contains(ll)) continue
            val label = c.callsign?.takeIf { it.isNotBlank() } ?: c.uid
            val icon = iconCache.getOrPut(cacheKey(c.displayColor, label)) {
                factory.fromBitmap(buildContactPin(c.displayColor, label))
            }
            runCatching {
                map.addMarker(MarkerOptions().position(ll).title(label).icon(icon))
            }.getOrNull()?.let { added.add(it); budget-- }
        }
        markers = added
    }

    /** Drop all contact markers + the idle listener. */
    fun clear(map: MapLibreMap) {
        markers.forEach { runCatching { map.removeMarker(it) } }
        markers = emptyList()
        idleListener?.let { runCatching { map.removeOnCameraIdleListener(it) } }
        idleListener = null
        boundMap = null
    }

    /** Stable cache key: one bitmap per (color, callsign) pair. */
    internal fun cacheKey(colorArgb: Int, label: String): String =
        "${colorArgb.toUInt().toString(16)}|$label"

    /**
     * Pure viewport test mirroring [render]'s cull, exposed for unit tests
     * (MapLibre's LatLngBounds is not constructible on the JVM).
     */
    internal fun inBounds(
        lat: Double, lon: Double,
        minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
    ): Boolean = lat in minLat..maxLat && lon in minLon..maxLon

    /**
     * Teardrop pin tinted [colorArgb] with the callsign baked in above the head.
     * The pin tip is at the bitmap's bottom-center (the annotation anchor), so
     * the tip lands on the exact coordinate and the label floats above.
     */
    private fun buildContactPin(colorArgb: Int, callsign: String): Bitmap {
        val pinW = 64; val pinH = 96
        val text = if (callsign.length > 24) callsign.take(23) + "…" else callsign
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 30f; textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CC000000"); textSize = 30f; textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD; style = Paint.Style.STROKE; strokeWidth = 6f
        }
        val labelH = if (text.isBlank()) 0 else 44
        val pad = 12f
        val textW = if (text.isBlank()) 0f else fill.measureText(text)
        val w = maxOf(pinW.toFloat(), textW + pad * 2).toInt()
        val h = labelH + pinH
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // Label above (so the pin tip stays the anchor).
        if (text.isNotBlank()) {
            val ty = 32f
            canvas.drawText(text, w / 2f, ty, halo)
            canvas.drawText(text, w / 2f, ty, fill)
        }

        // Teardrop pin, tip at the very bottom of the bitmap.
        val cx = w / 2f
        val circleR = pinW / 2f - 4f
        val circleTop = labelH + 4f
        val circleCy = circleTop + circleR
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorArgb; style = Paint.Style.FILL }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A1A1A"); style = Paint.Style.STROKE; strokeWidth = 3f
        }
        val path = Path()
        val tipY = (h - 4).toFloat()
        val oval = android.graphics.RectF(cx - circleR, circleTop, cx + circleR, circleTop + 2 * circleR)
        path.arcTo(oval, 120f, 300f, true)
        path.lineTo(cx, tipY)
        path.close()
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)

        // White center dot for contrast against the team color.
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        canvas.drawCircle(cx, circleCy, circleR * 0.35f, dot)
        return bmp
    }
}
