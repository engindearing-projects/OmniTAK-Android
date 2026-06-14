package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent
import soy.engindearing.omnitak.mobile.ui.theme.TacticalBackground

/**
 * Issue #96 — small floating compass indicator.
 *
 * Shows the map's current bearing as a rotated "N" needle. The widget
 * rotates so the "N" always points toward true north on the map surface —
 * i.e. the widget itself rotates by -bearing so that when the map is
 * rotated clockwise (positive bearing), the needle swings counter-clockwise
 * to indicate where north really is.
 *
 * Tapping resets the map to north (bearing 0°) via [onTapToNorth].
 *
 * Styling matches the dark semi-transparent / cyan-accent overlay language
 * used by [SelfPositionCard] and the UAS HUD pills.
 */
@Composable
fun CompassOverlay(
    /** Current map bearing in degrees clockwise from north (0–360). */
    bearingDeg: Double,
    /** Called when the user taps the compass — should animate map to north. */
    onTapToNorth: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(TacticalBackground.copy(alpha = 0.88f))
            .border(1.dp, TacticalAccent.copy(alpha = 0.55f), CircleShape)
            .clickable { onTapToNorth() },
        contentAlignment = Alignment.Center,
    ) {
        // The "N" rotates opposite to map bearing so it always points north.
        Text(
            text = "N",
            color = TacticalAccent,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.rotate(-bearingDeg.toFloat()),
        )
    }
}
