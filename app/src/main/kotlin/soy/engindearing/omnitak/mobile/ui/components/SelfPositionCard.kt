package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent

/**
 * PPLI-style readout card showing the operator's callsign and current
 * self-position telemetry. Mirrors the iOS card so both clients render
 * the same layout in the bottom-right corner of the map.
 *
 * Values are caller-provided so we don't lock in a particular location
 * source; today the caller stubs sensible defaults, GAP-030b wires
 * `FusedLocationProviderClient` updates through.
 */
@Composable
fun SelfPositionCard(
    callsign: String,
    coordinateLabel: String,
    altitudeMetersMSL: Double,
    speedKmh: Double,
    accuracyMeters: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xCC000000))
            .border(1.dp, TacticalAccent.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "Callsign: $callsign",
            color = TacticalAccent,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            coordinateLabel,
            color = Color.White,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            "%.1f m MSL".format(altitudeMetersMSL),
            color = Color.White,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            "%.1f km/h    +/- ${accuracyMeters}m".format(speedKmh),
            color = Color.White,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}
