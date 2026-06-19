package soy.engindearing.omnitak.mobile.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

    // #150 — id→contact registry for the tapped-pin → edit-sheet route. A native
    // annotation Marker's default tap handler selects the marker, shows `.title()`
    // as an InfoWindow, and swallows the touch the map-click hit-test relies on —
    // so editing never opened on a direct pin tap (only the label popped up).
    // TacticalMap's OnMarkerClickListener resolves the tapped Marker through this
    // map instead. Rebuilt in lockstep with [markers] on every [render]; KML
    // placemarks (also addMarker) are absent here, so they pass through to the
    // default InfoWindow untouched.
    internal val markerContacts = HashMap<Long, CoTEvent>()

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
        // Rebuilt below in lockstep with the markers we re-add (#150).
        markerContacts.clear()
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
            }.getOrNull()?.let { added.add(it); markerContacts[it.id] = c; budget-- }
        }
        markers = added
    }

    /** Drop all contact markers + the idle listener. */
    fun clear(map: MapLibreMap) {
        markers.forEach { runCatching { map.removeMarker(it) } }
        markers = emptyList()
        markerContacts.clear()
        idleListener?.let { runCatching { map.removeOnCameraIdleListener(it) } }
        idleListener = null
        boundMap = null
    }

    /** #150 — resolve a tapped annotation [Marker] back to the contact it
     *  renders, or null when it isn't a contact pin (e.g. a KML placemark). */
    fun contactForMarker(marker: Marker): CoTEvent? = markerContacts[marker.id]

    /** Pure id-keyed resolution, exposed for unit tests — MapLibre's [Marker]
     *  isn't constructible on the JVM. */
    internal fun contactForMarkerId(id: Long): CoTEvent? = markerContacts[id]

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
     * Compact affiliation dot tinted [colorArgb] (team color), with the callsign
     * baked in above it. The symbol sits at the bitmap's bottom-center — the
     * annotation anchor — so its center is one radius above the exact coordinate
     * and the visible dot straddles the point.
     *
     * Why a centered dot, not a tall pin: a 140px tip-anchored pin put the
     * visible head ~60px ABOVE the coordinate, but tap hit-testing (72px radius)
     * and lasso both test the coordinate — so tapping/lassoing the visible pin
     * missed (couldn't edit or lasso-delete a dropped marker, #135 regression).
     * Centering the symbol on the point restores the old ContactLayer behavior
     * where the visual and the hit/lasso target coincide.
     */
    private fun buildContactPin(colorArgb: Int, callsign: String): Bitmap {
        val text = if (callsign.length > 24) callsign.take(23) + "…" else callsign
        val r = 16f          // symbol radius — small, so center↔coordinate offset is minimal
        val ring = 3f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 30f; textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CC000000"); textSize = 30f; textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD; style = Paint.Style.STROKE; strokeWidth = 6f
        }
        val labelH = if (text.isBlank()) 0f else 40f
        val pad = 12f
        val diameter = (r + ring) * 2f
        val textW = if (text.isBlank()) 0f else fill.measureText(text)
        val w = maxOf(diameter, textW + pad * 2).toInt()
        val h = (labelH + diameter).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = w / 2f
        // Symbol center sits one radius above the bottom-center anchor, so the
        // dot's lower edge touches the coordinate — keeps the visual on the point.
        val cy = h - r - ring
        canvas.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorArgb; style = Paint.Style.FILL })
        canvas.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A1A1A"); style = Paint.Style.STROKE; strokeWidth = ring
        })
        canvas.drawCircle(cx, cy, r * 0.34f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL })

        // Callsign label above the dot (not a hit target; the dot is).
        if (text.isNotBlank()) {
            val ty = 30f
            canvas.drawText(text, cx, ty, halo)
            canvas.drawText(text, cx, ty, fill)
        }
        return bmp
    }
}

/** #150 — what a tap on an annotation [Marker] should do, decided without any
 *  MapLibre types so it stays unit-testable. */
internal enum class MarkerTapAction { OPEN_CONTACT_EDIT, CONSUMED_BY_MODE, PASS_THROUGH }

/**
 * Decide the outcome of a marker tap:
 *  - non-contact marker ([contact] is null) → [PASS_THROUGH]: leave MapLibre's
 *    default behaviour (a KML placemark keeps its InfoWindow label).
 *  - an active mode handler already consumed the tap ([modeHandled] is true) →
 *    [CONSUMED_BY_MODE]: measurement/drawing/mission ate it — don't also edit.
 *  - otherwise → [OPEN_CONTACT_EDIT]: route to the contact's edit sheet, the
 *    same outcome a tap gets on the 3D globe.
 */
internal fun decideMarkerTap(contact: CoTEvent?, modeHandled: Boolean): MarkerTapAction =
    when {
        contact == null -> MarkerTapAction.PASS_THROUGH
        modeHandled -> MarkerTapAction.CONSUMED_BY_MODE
        else -> MarkerTapAction.OPEN_CONTACT_EDIT
    }
