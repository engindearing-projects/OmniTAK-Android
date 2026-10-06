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
import soy.engindearing.omnitak.mobile.data.LabelSize
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent

// #213 - the card's design sizes in sp. Compose's sp already carries the phone's
// font size setting, so the Label size is the only multiplier applied on top.
private const val CALLSIGN_DESIGN_SP = 12f
private const val DETAIL_DESIGN_SP = 11f

/** #213 - the callsign line's size in sp at a Label size of [labelScalePercent]. */
internal fun positionCardCallsignSp(labelScalePercent: Int): Float =
    LabelSize.size(CALLSIGN_DESIGN_SP, 1f, labelScalePercent)

/** #213 - the coordinate, altitude and speed lines' size in sp at a Label size of [labelScalePercent]. */
internal fun positionCardDetailSp(labelScalePercent: Int): Float =
    LabelSize.size(DETAIL_DESIGN_SP, 1f, labelScalePercent)

// #213 - the zoom / centre buttons run down the left edge of the map in portrait: they
// start 16 dp in and are 48 dp wide, so their right edge is at 64 dp. The card sits
// 12 dp in from the right edge. At a large label size on a large phone font its text
// is wider than the gap between the two, so it wraps instead of running under them.
private const val MAP_BUTTONS_RIGHT_EDGE_DP = 64
private const val CARD_GAP_TO_BUTTONS_DP = 8
private const val CARD_END_PADDING_DP = 12
private const val CARD_MIN_MAX_WIDTH_DP = 160

/** #213 - the widest the position box may be, in dp, on a screen [screenWidthDp] wide:
 *  it stops short of the map buttons on the left. */
internal fun positionCardMaxWidthDp(screenWidthDp: Int): Int =
    (screenWidthDp - CARD_END_PADDING_DP - MAP_BUTTONS_RIGHT_EDGE_DP - CARD_GAP_TO_BUTTONS_DP)
        .coerceAtLeast(CARD_MIN_MAX_WIDTH_DP)

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
    altitudeLabel: String,
    speedLabel: String,
    accuracyLabel: String,
    modifier: Modifier = Modifier,
    /** #213 - the Label size setting, as a whole percent. */
    labelScalePercent: Int = LabelSize.DEFAULT_PERCENT,
) {
    val callsignSp = positionCardCallsignSp(labelScalePercent).sp
    val detailSp = positionCardDetailSp(labelScalePercent).sp
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xCC000000))
            // The operator's own PPLI readout is friendly — frame it in the
            // brand accent (matching the callsign text), not hostile red
            // (red = hostile in TAK affiliation semantics).
            .border(2.dp, TacticalAccent, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "Callsign: $callsign",
            color = TacticalAccent,
            fontSize = callsignSp,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            coordinateLabel,
            color = Color.White,
            fontSize = detailSp,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            altitudeLabel,
            color = Color.White,
            fontSize = detailSp,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            "$speedLabel    $accuracyLabel",
            color = Color.White,
            fontSize = detailSp,
            fontFamily = FontFamily.Monospace,
        )
    }
}
