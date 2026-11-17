package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import soy.engindearing.omnitak.mobile.data.uas.Waypoint

/**
 * Per-waypoint editor. Opens when the operator taps a numbered pin in
 * [MissionOverlay]. Lets them override altitude (MSL) and speed for
 * just that waypoint — leaving the mission-wide cruise as default.
 *
 * Altitude is MSL meters (matches the wire format). 0 means "use
 * cruise" — the uploader fills in the cruise value at upload time.
 *
 * Speed override toggles on/off; off (null) means autopilot default
 * cruise speed.
 *
 * Delete button is destructive — surfaces in red so an accidental tap
 * is visually obvious.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaypointEditSheet(
    index: Int,
    waypoint: Waypoint,
    cruiseHintMsl: Double,
    onApply: (Waypoint) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    // Initialise sliders from the waypoint (0.0 means "use cruise" —
    // show cruise value as the starting position so the operator's
    // first drag goes from a sensible baseline).
    val initialAlt = if (waypoint.altMslMeters > 0.0) waypoint.altMslMeters else cruiseHintMsl
    var altMsl by remember { mutableStateOf(initialAlt) }
    var altTyped by remember { mutableStateOf(altMsl.toInt().toString()) }

    var speedEnabled by remember { mutableStateOf(waypoint.speedMps != null) }
    var speedMps by remember { mutableStateOf((waypoint.speedMps ?: 5f).toFloat()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1A1F2E),
    ) {
        // #222: altitude field, slider, speed override and the Delete / Cancel /
        // Apply row are taller than a landscape phone; the buttons were cut off.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Waypoint ${index + 1}",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Text(
                text = rememberCoordText(waypoint.latDeg, waypoint.lonDeg),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
                fontFamily = FontFamily.Monospace,
            )

            // -------- Altitude --------
            Text(
                "Altitude (MSL)",
                style = MaterialTheme.typography.labelLarge,
                color = Color(0xFF00E5FF),
            )
            TextField(
                value = altTyped,
                onValueChange = { s ->
                    altTyped = s.filter { it.isDigit() }
                    altTyped.toIntOrNull()?.let { altMsl = it.toDouble() }
                },
                label = { Text("meters MSL") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Slider(
                value = altMsl.toFloat().coerceIn(0f, 2000f),
                onValueChange = {
                    altMsl = it.toDouble()
                    altTyped = it.toInt().toString()
                },
                valueRange = 0f..2000f,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF00E5FF),
                    activeTrackColor = Color(0xFF00E5FF),
                ),
            )

            // -------- Speed --------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = speedEnabled,
                    onCheckedChange = { speedEnabled = it },
                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFF00E5FF)),
                )
                Text(
                    if (speedEnabled) "Speed override: ${speedMps.toInt()} m/s"
                    else "Use autopilot default speed",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (speedEnabled) {
                Slider(
                    value = speedMps.coerceIn(1f, 30f),
                    onValueChange = { speedMps = it },
                    valueRange = 1f..30f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF00E5FF),
                        activeTrackColor = Color(0xFF00E5FF),
                    ),
                )
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
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) { Text("Cancel") }
                Button(
                    onClick = {
                        onApply(
                            waypoint.copy(
                                altMslMeters = altMsl,
                                speedMps = if (speedEnabled) speedMps else null,
                            )
                        )
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF34C759),
                        contentColor = Color.Black,
                    ),
                ) { Text("Apply") }
            }
        }
    }
}
