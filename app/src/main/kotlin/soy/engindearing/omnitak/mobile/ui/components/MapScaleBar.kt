package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import soy.engindearing.omnitak.mobile.data.DistanceUnit
import soy.engindearing.omnitak.mobile.data.MapScale

/**
 * The numbers behind the scale bar's drawing, so that what is drawn is exactly what [MapScale]
 * says. The bar is a horizontal line with a short tick at each end; its length is the distance
 * between the CENTRES of the two ticks, which is [MapScale.Bar.widthDp].
 */
internal object MapScaleBarLayout {

    /** The line and the ticks are this thick. */
    const val STROKE_DP = 2f

    /** The ticks at the ends are this tall. */
    const val TICK_HEIGHT_DP = 10f

    /** The room for the longest bar and half a tick sticking out at each end. */
    const val SLOT_WIDTH_DP = MapScale.MAX_WIDTH_DP + STROKE_DP

    /** The label above the line and the line: a large phone font still fits. */
    const val SLOT_HEIGHT_DP = 32f

    /** Where the left tick's centre is in the slot. */
    const val LINE_START_DP = STROKE_DP / 2

    /** Where the right tick's centre is in the slot for a bar [widthDp] long. */
    fun lineEndDp(widthDp: Float): Float = LINE_START_DP + widthDp

    /** The line's own row, how far above the slot's bottom edge: half the tick's height. */
    const val ROW_ABOVE_BOTTOM_DP = TICK_HEIGHT_DP / 2

    /** The row, in px, of a slot whose bottom edge is [slotBottomPx]: the row the ground scale is measured on. */
    fun rowYPx(slotBottomPx: Float, density: Float): Float = slotBottomPx - ROW_ABOVE_BOTTOM_DP * density
}

/**
 * A scale bar for the map: a line exactly as long as the distance written above it, white with a
 * dark outline so it reads on imagery and on a pale street map. No box behind it, and it takes no
 * touches. It always holds its place (an empty slot while no scale is known) because the engines
 * measure the ground scale along its row: it tells [source] where that is.
 *
 * Only this composable reads the scale, so a pan or a pinch recomposes the bar and nothing else.
 */
@Composable
internal fun MapScaleBar(source: MapScaleSource, unit: DistanceUnit, modifier: Modifier = Modifier) {
    val bar = MapScale.bar(source.metersPerDp.value, MapScale.MAX_WIDTH_DP, unit)
    val density = LocalDensity.current.density
    Box(
        modifier
            .size(MapScaleBarLayout.SLOT_WIDTH_DP.dp, MapScaleBarLayout.SLOT_HEIGHT_DP.dp)
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInRoot()
                source.setAnchor(bounds.center.x, MapScaleBarLayout.rowYPx(bounds.bottom, density), density)
            }
            .then(
                if (bar != null) {
                    Modifier.semantics { contentDescription = "Scale bar, ${bar.label}" }
                } else {
                    Modifier.clearAndSetSemantics { }
                },
            ),
    ) {
        if (bar != null) ScaleBarDrawing(bar)
    }
}

private val Outline = Color(0xD9000000)

@Composable
private fun ScaleBarDrawing(bar: MapScale.Bar) {
    Column(Modifier.size(MapScaleBarLayout.SLOT_WIDTH_DP.dp, MapScaleBarLayout.SLOT_HEIGHT_DP.dp)) {
        Box(Modifier.weight(1f).padding(start = 1.dp), contentAlignment = Alignment.BottomStart) {
            // The label twice: a dark outline underneath, the white text on top.
            val base = TextStyle(fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                bar.label,
                style = base.copy(color = Outline, drawStyle = Stroke(width = 5f, join = StrokeJoin.Round)),
                maxLines = 1,
                softWrap = false,
            )
            Text(bar.label, style = base.copy(color = Color.White), maxLines = 1, softWrap = false)
        }
        Canvas(Modifier.fillMaxWidth().height(MapScaleBarLayout.TICK_HEIGHT_DP.dp)) {
            val stroke = MapScaleBarLayout.STROKE_DP.dp.toPx()
            val halo = stroke + 2.dp.toPx()
            val start = MapScaleBarLayout.LINE_START_DP.dp.toPx()
            val end = MapScaleBarLayout.lineEndDp(bar.widthDp).dp.toPx()
            val midY = size.height / 2
            // The dark halo first, wider than the white line it frames, then the white.
            drawBar(Outline, halo, start, end, midY, size.height, extra = 1.dp.toPx())
            drawBar(Color.White, stroke, start, end, midY, size.height, extra = 0f)
        }
    }
}

private fun DrawScope.drawBar(color: Color, stroke: Float, start: Float, end: Float, midY: Float, height: Float, extra: Float) {
    // Butt ends: a line's length is not rounded up by a cap.
    drawLine(color, Offset(start - extra, midY), Offset(end + extra, midY), stroke, StrokeCap.Butt)
    drawLine(color, Offset(start, -extra), Offset(start, height + extra), stroke, StrokeCap.Butt)
    drawLine(color, Offset(end, -extra), Offset(end, height + extra), stroke, StrokeCap.Butt)
}
