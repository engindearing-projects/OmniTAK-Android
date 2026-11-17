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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import soy.engindearing.omnitak.mobile.data.uas.AltFrame
import soy.engindearing.omnitak.mobile.data.uas.CruiseAltitude

/**
 * Bottom sheet that lets the operator edit the cruise altitude — the
 * one the UAS uses for "fly here" / new mission waypoints. AGL is the
 * default frame (most pilots think above-takeoff); MSL is offered for
 * SAR / mountainous-terrain ops where the absolute altitude matters
 * more than home position.
 *
 * Range 0–500 m AGL covers >99% of UAS ops. Above 500 m exceeds Part 107
 * + most COA caps anyway — operator can type higher manually if needed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UasAltitudeSheet(
    current: CruiseAltitude,
    onApply: (CruiseAltitude) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var meters by remember { mutableStateOf(current.meters) }
    var frame by remember { mutableStateOf(current.frame) }
    var typed by remember { mutableStateOf(current.meters.toInt().toString()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1A1F2E),
    ) {
        // #222: the Cancel / Set cruise row was cut off on a landscape phone, and
        // with the keyboard up the meters field was squeezed to nothing.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Cruise altitude",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Text(
                "Used by Fly UAS Here and new mission waypoints. AGL = above takeoff. " +
                    "MSL = above mean sea level (use this in mountainous terrain).",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AltFrame.entries.forEach { f ->
                    FilterChip(
                        selected = frame == f,
                        onClick = { frame = f },
                        label = { Text(f.name) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF00E5FF),
                            selectedLabelColor = Color.Black,
                        ),
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextField(
                    value = typed,
                    onValueChange = { s ->
                        typed = s.filter { it.isDigit() }
                        typed.toIntOrNull()?.let { meters = it.toDouble() }
                    },
                    label = { Text("meters") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Slider(
                value = meters.toFloat().coerceIn(0f, 500f),
                onValueChange = {
                    meters = it.toDouble()
                    typed = it.toInt().toString()
                },
                valueRange = 0f..500f,
                steps = 49, // 10m steps
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF00E5FF),
                    activeTrackColor = Color(0xFF00E5FF),
                ),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(0.4f),
                ) { Text("Cancel") }
                Button(
                    onClick = {
                        onApply(CruiseAltitude(meters, frame))
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF34C759),
                        contentColor = Color.Black,
                    ),
                ) { Text("Set cruise to ${meters.toInt()} m ${frame.name}") }
            }
        }
    }
}
