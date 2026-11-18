package soy.engindearing.omnitak.mobile.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import soy.engindearing.omnitak.mobile.data.Drawing
import kotlin.math.max
import kotlin.math.min

/**
 * A name to write on the 2D map: [text] above the point ([lat], [lon]), in [colorHex]. [outline] is a few
 * ([lat], [lon]) points round the zone it names, to tell which part of the zone is on the screen.
 */
internal data class GeofenceLabel(
    val text: String,
    val lat: Double,
    val lon: Double,
    val colorHex: String,
    val outline: List<Pair<Double, Double>> = emptyList(),
)

/** The part of the screen a zone covers, in px: the box round its outline. */
internal data class ScreenBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Where a name goes on the screen, in px, and the part of the screen its zone covers when that is known.
 * [halfWidthPx] is half the width of the name's text: how far it reaches to either side of its point. NaN when it is not known.
 */
internal data class ScreenLabel(
    val text: String,
    val x: Float,
    val y: Float,
    val colorHex: String,
    val zone: ScreenBox? = null,
    val halfWidthPx: Float = Float.NaN,
)

/**
 * The rules for the names of the geofences on the 2D map, free of Android types so they are tested
 * on the JVM.
 */
internal object GeofenceLabels {

    /** A very long name is cut: one label must not cover the map. */
    const val MAX_CHARS = 28

    /** How many points round a zone are kept to tell which part of it is on the screen. */
    const val OUTLINE_POINTS = 16

    /** The name's text size, in dp, before the phone's font size and the Label size setting. */
    const val TEXT_DP = 11.5f

    /**
     * How far over the point the bottom of the name sits, in dp: the name is written on top of its point, the
     * way the iOS app anchors it. The point is the north edge of a circle, so the operator's own marker, on the
     * middle of the zone they are in, is not under the name.
     */
    const val GAP_DP = 2f

    /**
     * The y, in px, text is drawn from (its baseline) so that the bottom of its letters is [gapPx] over the
     * point at [pointY]. [descentPx] is how far letters like g and y reach under the baseline.
     */
    fun baselineY(pointY: Float, descentPx: Float, gapPx: Float): Float = pointY - gapPx - descentPx

    /** The names the shapes in [drawings] carry, and the colour of each, in order. */
    fun of(drawings: List<Drawing>): List<GeofenceLabel> = drawings.mapNotNull { d ->
        val label = d.label ?: return@mapNotNull null
        if (label.text.isBlank() || !label.lat.isFinite() || !label.lon.isFinite()) {
            null
        } else {
            GeofenceLabel(label.text, label.lat, label.lon, d.colorHex, thin(d.points))
        }
    }

    /** [points] with at most [most] of them, evenly spread, the first and the last kept. */
    fun thin(points: List<Pair<Double, Double>>, most: Int = OUTLINE_POINTS): List<Pair<Double, Double>> {
        if (points.size <= most || most < 2) return points
        return List(most) { i -> points[(i.toLong() * (points.size - 1) / (most - 1)).toInt()] }
    }

    /** The box round the finite points ([x], [y]) in px, or null when there are none. */
    fun boxOf(points: List<Pair<Float, Float>>): ScreenBox? {
        val finite = points.filter { it.first.isFinite() && it.second.isFinite() }
        if (finite.isEmpty()) return null
        return ScreenBox(finite.minOf { it.first }, finite.minOf { it.second }, finite.maxOf { it.first }, finite.maxOf { it.second })
    }

    /**
     * The names to draw, and where, for a view of [widthPx] x [heightPx]. A name belongs to its zone, so it is
     * drawn for as long as any of the zone is on the screen, even when the point it is written on (the north
     * edge of a circle, the middle of a polygon) is not:
     *  - above the top it is kept at the top, under the first [topPx] (the status bar and the status strip), the
     *    whole name clear of them: [nameHeightPx] tall, [gapPx] over its point;
     *  - below the bottom it is kept at the bottom, above the [bottomPx] along it (the gesture bar or the navigation
     *    buttons, which the map is drawn under and a fade covers);
     *  - past a side it is moved to the middle of the part of the zone that is on the screen.
     * A name whose point is on the screen (or off a side by no more than half of the name's own width,
     * [ScreenLabel.halfWidthPx]: the name is centred on its point, so up to there some of its text is still on the
     * screen) stays where it is, but is moved down off the strip. A name that is wholly off the screen is moved onto
     * its zone like one whose point is far off. [marginPx] stands in for the half width of a name that has none.
     * When nothing is known about the zone ([ScreenLabel.zone] is null) the name stays with its point and is dropped
     * when that is off the screen. Drawn in order, so a later one is over an earlier one.
     */
    fun placed(
        labels: List<ScreenLabel>,
        widthPx: Float,
        heightPx: Float,
        marginPx: Float = 0f,
        topPx: Float = 0f,
        nameHeightPx: Float = 0f,
        gapPx: Float = 0f,
        bottomPx: Float = 0f,
    ): List<ScreenLabel> = labels.mapNotNull { label ->
        if (!label.x.isFinite() || !label.y.isFinite()) return@mapNotNull null
        // The name is centred on its point: it reaches half of its own width to either side of it.
        val margin = if (label.halfWidthPx.isFinite()) label.halfWidthPx else marginPx
        val zone = label.zone
        if (zone == null || !(zone.left.isFinite() && zone.top.isFinite() && zone.right.isFinite() && zone.bottom.isFinite())) {
            val kept = label.x >= -margin && label.x <= widthPx + margin && label.y >= -margin && label.y <= heightPx + margin
            return@mapNotNull if (kept) label else null
        }
        // What part of the zone is where a name can be: on the screen and under the strip.
        val left = max(zone.left, 0f)
        val right = min(zone.right, widthPx)
        val top = max(zone.top, topPx)
        val bottom = min(zone.bottom, heightPx)
        if (left > right || top > bottom) return@mapNotNull null
        val lowest = topPx + gapPx + nameHeightPx
        // The room a name has runs down to the bar along the bottom (none when it is not a size), and never less than the strip leaves.
        val bar = if (bottomPx.isFinite()) bottomPx.coerceAtLeast(0f) else 0f
        val x = if (label.x >= -margin && label.x <= widthPx + margin) label.x else (left + right) / 2f
        val y = label.y.coerceIn(lowest, max(lowest, heightPx - bar))
        if (x == label.x && y == label.y) label else label.copy(x = x, y = y)
    }

    /** [text] cut to [MAX_CHARS] with an ellipsis. */
    fun shown(text: String): String = if (text.length > MAX_CHARS) text.take(MAX_CHARS - 1) + "…" else text
}

/**
 * Writes the names of the geofences on the 2D map: each on top of its point (the north edge of a circle,
 * the middle of a polygon's corners), in the zone's colour on a dark outline.
 *
 * A native polygon has no text, and a marker with the name in its bitmap takes taps (a measure
 * point or a drawing point placed near a zone's edge would land on the name instead). So the names
 * are drawn here, over the map and under every control, and take no touches. They are painted by
 * Compose, not by the map's GL layers, so they show on the Adreno and Mali drivers where a GeoJSON
 * symbol layer does not, and a style reload does not touch them. Like the other overlays that float
 * above the map (the drone, the mission, the UAS ring) they follow the camera through
 * [rememberScreenProjection]; an unchanged answer does not redraw.
 */
@Composable
internal fun GeofenceLabelOverlay(
    map: MapLibreMap?,
    labels: List<GeofenceLabel>,
    labelFactor: Float,
    modifier: Modifier = Modifier,
) {
    val live = map ?: return
    if (labels.isEmpty()) return

    val projected = rememberScreenProjection(live, labels, periodMs = 50) { projection ->
        labels.map { label ->
            val at = projection.toScreenLocation(LatLng(label.lat, label.lon))
            // Where the zone is on the screen: its outline, and the point its name is on, so the box always holds that.
            val outline = label.outline.map { (lat, lon) -> projection.toScreenLocation(LatLng(lat, lon)).let { it.x to it.y } }
            ScreenLabel(GeofenceLabels.shown(label.text), at.x, at.y, label.colorHex, GeofenceLabels.boxOf(outline + (at.x to at.y)))
        }
    } ?: return

    val density = LocalDensity.current.density
    val safeArea = mapSafeAreaPx()
    val fill = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD } }
    val halo = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            style = Paint.Style.STROKE
            color = android.graphics.Color.parseColor("#E6000000")
        }
    }

    Canvas(modifier.fillMaxSize()) {
        val size = GeofenceLabels.TEXT_DP * density * labelFactor
        fill.textSize = size
        halo.textSize = size
        halo.strokeWidth = size * 0.2f
        val gap = GeofenceLabels.GAP_DP * density
        val metrics = fill.fontMetrics
        // Each name is as wide as its own text: measured with the paint it is drawn with, at the size it is drawn at.
        val measured = projected.map { it.copy(halfWidthPx = fill.measureText(it.text) / 2f) }
        val visible = GeofenceLabels.placed(
            measured,
            this.size.width,
            this.size.height,
            // A name stays under the status bar and the status strip, whatever the zone's edge does.
            topPx = (MapOrnamentLayout.belowStripDp(safeArea, density) * density).toFloat(),
            nameHeightPx = metrics.descent - metrics.ascent,
            gapPx = gap,
            // And above the gesture bar or the navigation buttons: the fade behind them (SystemBarScrims) is drawn over the names.
            bottomPx = safeArea.bottom.toFloat(),
        )
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            for (label in visible) {
                fill.color = runCatching { android.graphics.Color.parseColor(label.colorHex) }.getOrDefault(android.graphics.Color.WHITE)
                // The point is on the zone's edge; the bottom of the text is [gap] over it.
                val baseline = GeofenceLabels.baselineY(label.y, metrics.descent, gap)
                native.drawText(label.text, label.x, baseline, halo)
                native.drawText(label.text, label.x, baseline, fill)
            }
        }
    }
}
