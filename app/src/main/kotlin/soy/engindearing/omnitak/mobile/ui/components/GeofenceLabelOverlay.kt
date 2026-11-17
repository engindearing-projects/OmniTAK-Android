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

/** A name to write on the 2D map: [text] above the point ([lat], [lon]), in [colorHex]. */
internal data class GeofenceLabel(val text: String, val lat: Double, val lon: Double, val colorHex: String)

/** Where a name goes on the screen, in px. */
internal data class ScreenLabel(val text: String, val x: Float, val y: Float, val colorHex: String)

/**
 * The rules for the names of the geofences on the 2D map, free of Android types so they are tested
 * on the JVM.
 */
internal object GeofenceLabels {

    /** A very long name is cut: one label must not cover the map. */
    const val MAX_CHARS = 28

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
            GeofenceLabel(label.text, label.lat, label.lon, d.colorHex)
        }
    }

    /** [text] cut to [MAX_CHARS] with an ellipsis. */
    fun shown(text: String): String = if (text.length > MAX_CHARS) text.take(MAX_CHARS - 1) + "…" else text

    /**
     * The names that are on the screen: those whose point is inside a view of [widthPx] x [heightPx]
     * with [marginPx] to spare (a name is wide, so one whose point is just off the edge can still
     * reach into the view). Drawn in order, so a later one is over an earlier one.
     */
    fun onScreen(points: List<ScreenLabel>, widthPx: Float, heightPx: Float, marginPx: Float): List<ScreenLabel> =
        points.filter {
            it.x.isFinite() && it.y.isFinite() &&
                it.x >= -marginPx && it.x <= widthPx + marginPx &&
                it.y >= -marginPx && it.y <= heightPx + marginPx
        }
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
            ScreenLabel(GeofenceLabels.shown(label.text), at.x, at.y, label.colorHex)
        }
    } ?: return

    val density = LocalDensity.current.density
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
        val visible = GeofenceLabels.onScreen(projected, this.size.width, this.size.height, marginPx = size * GeofenceLabels.MAX_CHARS / 2)
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            for (label in visible) {
                fill.color = runCatching { android.graphics.Color.parseColor(label.colorHex) }.getOrDefault(android.graphics.Color.WHITE)
                // The point is on the zone's edge; the bottom of the text is [gap] over it.
                val baseline = GeofenceLabels.baselineY(label.y, fill.fontMetrics.descent, gap)
                native.drawText(label.text, label.x, baseline, halo)
                native.drawText(label.text, label.x, baseline, fill)
            }
        }
    }
}
