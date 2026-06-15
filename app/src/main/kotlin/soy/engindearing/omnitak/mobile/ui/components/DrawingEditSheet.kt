package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import soy.engindearing.omnitak.mobile.data.Drawing
import soy.engindearing.omnitak.mobile.data.DrawingKind

/**
 * Issue #76 — edit / move / delete a placed drawing (parity with the marker
 * edit sheet). Opens when the operator taps a circle / line / polygon on the
 * map. A modal bottom sheet (never a side panel — the full-screen map is
 * sacred) lets them rename, recolor, start a move (drag-to-reposition on the
 * map), or delete the shape.
 *
 * "Move" dismisses the sheet and flips the caller into a drag mode; the actual
 * translation happens on the map surface so the operator sees the shape track
 * their finger.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrawingEditSheet(
    drawing: Drawing,
    onApply: (Drawing) -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var name by remember(drawing.id) { mutableStateOf(drawing.name) }
    var colorHex by remember(drawing.id) { mutableStateOf(drawing.colorHex) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1A1F2E),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                kindTitle(drawing.kind),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Text(
                "${drawing.points.size} point${if (drawing.points.size == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
                fontFamily = FontFamily.Monospace,
            )

            // -------- Name --------
            TextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Label (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // -------- Color --------
            Text(
                "Color",
                style = MaterialTheme.typography.labelLarge,
                color = Color(0xFF00E5FF),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DRAWING_PALETTE.forEach { hex ->
                    val selected = hex.equals(colorHex, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(android.graphics.Color.parseColor(hex)))
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) Color.White else Color.White.copy(alpha = 0.4f),
                                shape = CircleShape,
                            )
                            .clickable { colorHex = hex },
                    )
                }
            }

            // -------- Actions --------
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        onDelete()
                        onDismiss()
                    },
                ) { Text("Delete", color = Color(0xFFFF3B30)) }
                OutlinedButton(
                    onClick = {
                        // Persist any pending rename / recolor before handing
                        // off to the on-map move so edits aren't lost.
                        onApply(drawing.copy(name = name, colorHex = colorHex))
                        onMove()
                        onDismiss()
                    },
                ) {
                    Icon(
                        Icons.Filled.OpenWith,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("  Move", color = Color(0xFF00E5FF))
                }
                Button(
                    onClick = {
                        onApply(drawing.copy(name = name, colorHex = colorHex))
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF34C759),
                        contentColor = Color.Black,
                    ),
                ) { Text("Save") }
            }
        }
    }
}

private fun kindTitle(kind: DrawingKind): String = when (kind) {
    DrawingKind.LINE -> "Line"
    DrawingKind.POLYGON -> "Polygon"
    DrawingKind.CIRCLE -> "Circle"
}

/** Common tactical drawing colors. First entry matches the drawing default. */
private val DRAWING_PALETTE = listOf(
    "#4ADE80", // green (default)
    "#FFC107", // amber
    "#F44336", // red
    "#2196F3", // blue
    "#FFFFFF", // white
)
