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
    // #178 — when true, a contact pin shows its age and fades as it goes stale.
    private var stalenessOverlay: Boolean = false

    // #213: the phone's font size x the Label size setting: one multiplier for
    // every dimension of the name baked into a pin. It is held here, not passed to
    // update(), because TacticalMap re-renders from several places that know
    // nothing about it; a parameter they left out would put the size back to 100%.
    private var labelFactor: Float = 1f

    // Cap live annotations so a flood of CoT contacts can't jank the main thread.
    private const val MAX_MARKERS = 500

    /**
     * #213: set the label size multiplier ([soy.engindearing.omnitak.mobile.data.LabelSize.factor])
     * and redraw the pins at once if it changed. Safe to call before the map is
     * bound: the next [render] uses it.
     */
    fun setLabelFactor(factor: Float) {
        if (factor == labelFactor) return
        labelFactor = factor
        // Pins drawn at the old size are never used again; free them.
        iconCache.clear()
        render()
    }

    /**
     * Replace the rendered contacts; (re)registers the camera-idle re-render.
     *
     * #178 — [stalenessOverlay] turns on the per-pin age label + staleness fade.
     * Off by default so the existing marker look is unchanged when the operator
     * hasn't opted in.
     */
    fun update(
        map: MapLibreMap,
        context: Context,
        contacts: Collection<CoTEvent>,
        stalenessOverlay: Boolean = false,
    ) {
        this.ctx = context
        this.contacts = contacts.toList()
        this.stalenessOverlay = stalenessOverlay
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
        // #178 — single clock read for this whole render pass so every pin's age
        // bucket is computed against the same "now".
        val now = System.currentTimeMillis()
        // #213: one size for the whole pass, like the clock above.
        val factor = labelFactor
        for (c in contacts) {
            if (budget <= 0) break
            if (c.lat.isNaN() || c.lon.isNaN()) continue
            val ll = LatLng(c.lat, c.lon)
            if (bounds != null && !bounds.contains(ll)) continue
            val label = c.callsign?.takeIf { it.isNotBlank() } ?: c.uid
            // #178 — staleness overlay: fade by age bucket + append an age label
            // (e.g. "ALPHA  3m"). Only when the operator opted in AND we have a
            // received-at stamp; otherwise the pin renders exactly as before.
            val ageLabel = if (stalenessOverlay) {
                soy.engindearing.omnitak.mobile.data.CoTAge.shortLabel(c.receivedAtMs, now)
            } else null
            val alpha = if (stalenessOverlay && c.receivedAtMs > 0L) {
                soy.engindearing.omnitak.mobile.data.CoTAge.alpha(now - c.receivedAtMs)
            } else 1.0f
            val icon = iconCache.getOrPut(cacheKey(c.displayColor, label, ageLabel, alpha, factor)) {
                factory.fromBitmap(buildContactPin(c.displayColor, label, ageLabel, alpha, factor))
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

    /** Stable cache key: one bitmap per (color, callsign[, age, alpha, size]) tuple.
     *  The age label + opacity ride the key so the #178 staleness overlay gets a
     *  fresh bitmap as a point ages, and the plain (color,label) call still works
     *  for callers/tests that don't use the overlay. #213: the label size rides
     *  it too: without it a pin cached at one size would be reused after the
     *  operator picks another, and the change would not show. */
    internal fun cacheKey(
        colorArgb: Int,
        label: String,
        ageLabel: String? = null,
        alpha: Float = 1.0f,
        labelFactor: Float = 1.0f,
    ): String =
        "${colorArgb.toUInt().toString(16)}|$label|${ageLabel ?: ""}|$alpha|$labelFactor"

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
     * baked in above it. MapLibre (11.8.0) anchors the bitmap at its CENTRE (measured:
     * the dot sits 20 px below its coordinate at the default size), not at the bottom
     * edge as this comment used to say. #213 keeps the dot at that same offset at every
     * label size by padding the bitmap on the side away from the label, see
     * [MapLabelGeometry]; the offset itself is unchanged.
     *
     * Why a centered dot, not a tall pin: a 140px tip-anchored pin put the
     * visible head ~60px ABOVE the coordinate, but tap hit-testing (72px radius)
     * and lasso both test the coordinate — so tapping/lassoing the visible pin
     * missed (couldn't edit or lasso-delete a dropped marker, #135 regression).
     * Centering the symbol on the point restores the old ContactLayer behavior
     * where the visual and the hit/lasso target coincide.
     */
    private fun buildContactPin(
        colorArgb: Int,
        callsign: String,
        ageLabel: String? = null,
        alpha: Float = 1.0f,
        labelFactor: Float = 1.0f,
    ): Bitmap {
        // #178 — append the age (e.g. "ALPHA  3m") so the label carries freshness
        // inline; the symbol itself fades via [alpha].
        val base = if (callsign.length > 24) callsign.take(23) + "…" else callsign
        val text = if (ageLabel != null) "$base  $ageLabel".trim() else base
        // Clamp opacity so a faded-but-stale pin never fully disappears.
        val a = (alpha.coerceIn(0.25f, 1.0f) * 255f).toInt()
        val r = 16f          // symbol radius — small, so center↔coordinate offset is minimal
        val ring = 3f
        // #213: the name (text, outline, strip, gap) follows the label size; the
        // dot (r, ring) does not, so a larger name grows upward and stays off it.
        val g = MapLabelGeometry.forContact(labelFactor)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = g.textSize; textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD; this.alpha = a
        }
        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CC000000"); textSize = g.textSize; textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD; style = Paint.Style.STROKE; strokeWidth = g.haloStroke; this.alpha = a
        }
        val hasLabel = text.isNotBlank()
        val labelH = if (hasLabel) g.band else 0f
        // #213: transparent rows that keep the dot where it is at every label size:
        // MapLibre anchors the bitmap at its centre, so a taller strip would pull the
        // dot away from its coordinate (see MapLabelGeometry).
        val top = if (hasLabel) g.topPad else 0
        val bottom = if (hasLabel) g.bottomPad else 0
        val pad = g.padding
        val diameter = (r + ring) * 2f
        val textW = if (hasLabel) fill.measureText(text) else 0f
        val w = maxOf(diameter, textW + pad * 2).toInt()
        val h = (top + labelH + diameter + bottom).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = w / 2f
        // The dot sits at the foot of the strip + dot block, above any bottom padding.
        val cy = h - bottom - r - ring
        canvas.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorArgb; style = Paint.Style.FILL; this.alpha = a })
        canvas.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A1A1A"); style = Paint.Style.STROKE; strokeWidth = ring; this.alpha = a
        })
        canvas.drawCircle(cx, cy, r * 0.34f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL; this.alpha = a })

        // Callsign label above the dot (not a hit target; the dot is).
        if (hasLabel) {
            val ty = top + g.baseline
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
