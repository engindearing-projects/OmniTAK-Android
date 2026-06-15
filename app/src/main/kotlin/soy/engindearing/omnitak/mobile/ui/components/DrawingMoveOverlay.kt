package soy.engindearing.omnitak.mobile.ui.components

import android.graphics.PointF
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.maplibre.android.maps.MapLibreMap
import soy.engindearing.omnitak.mobile.data.Drawing
import soy.engindearing.omnitak.mobile.domain.DrawingHitTest
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent
import soy.engindearing.omnitak.mobile.ui.theme.TacticalBackground

/**
 * Issue #76 — drag-to-reposition for a selected drawing. Rendered as a
 * Compose sibling above [TacticalMap] (same pattern as [LassoOverlay]). While
 * [drawing] is non-null the full-screen layer captures drag gestures and
 * translates the whole shape rigidly by the geographic delta under the
 * finger, so the operator sees it track in real time. Done / Cancel chips
 * exit the mode.
 *
 * Translation is computed from the on-screen drag delta projected to a
 * geographic delta at the drawing's anchor (first vertex) — robust across
 * zoom levels because we re-project the anchor each frame rather than
 * accumulating degrees from a fixed pixel scale.
 */
@Composable
fun DrawingMoveOverlay(
    drawing: Drawing?,
    mapboxMap: MapLibreMap?,
    onMoved: (Drawing) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (drawing == null) return
    // Working copy mutated as the operator drags; committed to the store via
    // [onMoved] on every drag delta so the rendered drawing follows the finger.
    var working by remember(drawing.id) { mutableStateOf(drawing) }

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(drawing.id, mapboxMap) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val map = mapboxMap ?: return@detectDragGestures
                            val moved = translateByScreenDelta(map, working, dragAmount)
                            if (moved != null) {
                                working = moved
                                onMoved(moved)
                            }
                        },
                    )
                },
        )
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 76.dp, start = 12.dp, end = 12.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(TacticalBackground.copy(alpha = 0.9f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Drag to move",
                color = TacticalAccent,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onDone) {
                Text("Done", color = TacticalAccent)
            }
        }
    }
}

/**
 * Translate [drawing] by an on-screen pixel delta, converting to a geographic
 * delta at the drawing's anchor vertex (so the shift matches the finger at any
 * zoom). Returns null if the projection can't resolve.
 */
private fun translateByScreenDelta(
    map: MapLibreMap,
    drawing: Drawing,
    dragAmount: Offset,
): Drawing? {
    val anchor = drawing.points.firstOrNull() ?: return null
    val proj = map.projection
    val anchorScreen = proj.toScreenLocation(
        org.maplibre.android.geometry.LatLng(anchor.first, anchor.second),
    )
    val newAnchorLatLng = proj.fromScreenLocation(
        PointF(anchorScreen.x + dragAmount.x, anchorScreen.y + dragAmount.y),
    )
    val dLat = newAnchorLatLng.latitude - anchor.first
    val dLon = newAnchorLatLng.longitude - anchor.second
    if (dLat == 0.0 && dLon == 0.0) return null
    return DrawingHitTest.translate(drawing, dLat, dLon)
}
