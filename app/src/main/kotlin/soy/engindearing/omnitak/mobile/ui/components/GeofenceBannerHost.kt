package soy.engindearing.omnitak.mobile.ui.components

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fence
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceBanner
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceEventType
import soy.engindearing.omnitak.mobile.domain.GeofenceService
import soy.engindearing.omnitak.mobile.i18n.Loc
import soy.engindearing.omnitak.mobile.ui.theme.TacticalBackground
import kotlin.math.roundToInt

/** How wide the alert banner may be. Plain arithmetic, so it is tested without a screen. */
internal object GeofenceBannerLayout {
    /** The widest the banner is, in dp, on any screen. */
    const val MAX_WIDTH_DP = 480

    /**
     * The widest the banner may be on a screen [screenWidthDp] wide. Held upright, the banner has the
     * whole width: the position card is down in the bottom corner. Held sideways on a phone the card
     * is as tall as the window is short (a large font makes it taller still) and reaches up into the
     * band the banner is in, so the banner keeps to the left half and the right half stays the card's,
     * the tools button's and the drone pills'.
     */
    fun maxWidthDp(screenWidthDp: Int, landscape: Boolean): Int =
        if (landscape) minOf(MAX_WIDTH_DP, screenWidthDp / 2) else MAX_WIDTH_DP
}

/**
 * The alert that crosses the top of the map when someone enters or leaves a geofence, or stays in
 * one: "ALPHA-2 entered North gate". One at a time, five seconds each, then the next; tapping it
 * opens the geofence list, and the cross closes it.
 *
 * Its own composable, reading the service itself, so the map screen is not recomposed for every
 * alert. It takes the room it needs in the column of top-left chrome it is placed in, so it never
 * covers a tool banner or the compass: they move down while it is showing.
 */
@Composable
fun GeofenceBannerHost(
    service: GeofenceService,
    onOpen: () -> Unit,
    /** Told how tall the alert is, in px, as it comes and goes, and 0 once it is gone: see [belowGeofenceBanner]. */
    onHeightPx: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val banner by service.banner.collectAsState()
    GeofenceBannerView(
        banner = banner,
        onOpen = {
            service.dismissBanner()
            onOpen()
        },
        onClose = service::dismissBanner,
        onHeightPx = onHeightPx,
        modifier = modifier,
    )
}

/**
 * Starts the content of a band [restTopDp] under the safe area's top edge lower while the geofence alert is
 * showing, by the amount [MapOrnamentLayout.shiftBelowGeofenceBannerDp] says, so the alert does not cover it.
 * [bannerHeightPx] is the height [GeofenceBannerHost] reports. It is read while the content is laid out, not
 * while the screen is composed, so an alert coming and going does not recompose the map screen, and the
 * content slides down and back with the alert's own animation.
 */
internal fun Modifier.belowGeofenceBanner(
    bannerHeightPx: State<Int>,
    restTopDp: Int,
    landscape: Boolean,
    reachesTheLeftHalf: Boolean = false,
): Modifier =
    offset {
        val shiftDp = MapOrnamentLayout.shiftBelowGeofenceBannerDp(
            restTopDp, bannerHeightPx.value / density, landscape, reachesTheLeftHalf,
        )
        IntOffset(0, (shiftDp * density).roundToInt())
    }

/** The banner itself, drawn from what it is given: [banner] null is no banner. */
@Composable
internal fun GeofenceBannerView(
    banner: GeofenceBanner?,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onHeightPx: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val maxWidth = GeofenceBannerLayout.maxWidthDp(
        configuration.screenWidthDp,
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
    )
    // The last banner stays on screen while it fades out, so it does not go blank before it goes.
    val last = remember { mutableStateOf<GeofenceBanner?>(null) }
    if (banner != null) last.value = banner
    AnimatedVisibility(
        visible = banner != null,
        modifier = modifier.onSizeChanged { onHeightPx(it.height) },
        enter = fadeIn(tween(150)) + expandVertically(tween(150)),
        exit = fadeOut(tween(150)) + shrinkVertically(tween(150)),
    ) {
        // The alert is gone for good when this leaves the composition, after its exit animation: nothing is under it
        // then, whatever size was last reported on the way out (the size node leaves with it, without a last report).
        DisposableEffect(Unit) { onDispose { onHeightPx(0) } }
        last.value?.let { shown ->
            Row(
                modifier = Modifier
                    .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                    .widthIn(max = maxWidth.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(TacticalBackground.copy(alpha = 0.94f))
                    .clickable(role = Role.Button, onClick = onOpen)
                    // Read out when it appears, without taking the focus.
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Filled.Fence,
                    contentDescription = null,
                    tint = bannerTint(shown.type),
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    shown.sentence,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = Loc.t("geofence.banner.close"),
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** Entry yellow, exit red, dwell orange. */
private fun bannerTint(type: GeofenceEventType): Color = when (type) {
    GeofenceEventType.ENTRY -> Color(0xFFFFCC00)
    GeofenceEventType.EXIT -> Color(0xFFFF3B30)
    GeofenceEventType.DWELL -> Color(0xFFFF9500)
}
