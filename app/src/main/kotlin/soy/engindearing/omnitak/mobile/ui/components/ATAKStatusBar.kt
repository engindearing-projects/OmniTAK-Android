package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import soy.engindearing.omnitak.mobile.i18n.Loc
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent
import soy.engindearing.omnitak.mobile.ui.theme.TacticalBackground

/**
 * ATAK-style status bar that sits above the map. Mirrors the iOS layout:
 *   [•] [Server] [↓counter] [↑counter] .................... [GPS±Nm] [time] [menu]
 * Uses a semi-transparent tactical-navy background so it stays readable
 * over any basemap.
 */
@Composable
fun ATAKStatusBar(
    serverName: String,
    isConnected: Boolean,
    messagesReceived: Int,
    messagesSent: Int,
    gpsAccuracyMeters: Int?,
    timeLabel: String,
    onServerTap: () -> Unit,
    onMenuTap: () -> Unit,
    modifier: Modifier = Modifier,
    // Multi-server indicator: one flag per enabled server (true = connected).
    // When >1 server is enabled this replaces the single dot with a
    // "N/M ●●●" cluster so the operator can see at a glance how many of
    // their servers are live. Empty/size-1 falls back to the single dot.
    serverConnectedFlags: List<Boolean> = emptyList(),
    // "Top info bar" settings toggle. False collapses this down to just the
    // connection dot — the dot itself is never hidden by the toggle, only
    // the server name / counters / GPS / clock / menu around it.
    showDetails: Boolean = true,
    // #211 - true while "Report my position" is switched off. Shown in BOTH
    // layouts (the full bar and the collapsed dot-only one): it is a state the
    // operator should not be able to miss, whatever the top-bar toggle says.
    positionReportingOff: Boolean = false,
) {
    if (!showDetails) {
        Row(
            modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (serverConnectedFlags.size > 1) {
                MultiServerIndicator(flags = serverConnectedFlags)
            } else {
                ConnectionDot(isConnected = isConnected)
            }
            if (positionReportingOff) {
                Spacer(Modifier.width(6.dp))
                PpliOffChip()
            }
        }
        return
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(TacticalBackground.copy(alpha = 0.85f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (serverConnectedFlags.size > 1) {
            MultiServerIndicator(flags = serverConnectedFlags)
        } else {
            ConnectionDot(isConnected = isConnected)
        }
        Spacer(Modifier.width(8.dp))

        Row(
            modifier = Modifier.clickable(onClick = onServerTap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Storage,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                serverName,
                color = Color.White,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
            )
        }

        Spacer(Modifier.width(12.dp))
        CounterChip(label = "↓", count = messagesReceived, tint = Color(0xFF2196F3))
        Spacer(Modifier.width(6.dp))
        CounterChip(label = "↑", count = messagesSent, tint = Color(0xFFFFA000))

        Spacer(Modifier.width(12.dp))
        // The flexible gap. The "PPLI OFF" chip (#211) lives in it, so on a
        // narrow phone it can only squeeze itself and never pushes the
        // GPS / clock / menu off the end of the bar.
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (positionReportingOff) PpliOffChip()
        }

        if (gpsAccuracyMeters != null) {
            Text(
                "±${gpsAccuracyMeters}m",
                color = TacticalAccent,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            timeLabel,
            color = Color.White.copy(alpha = 0.9f),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Filled.Menu,
            contentDescription = "Open tools menu",
            tint = Color.White,
            modifier = Modifier
                .size(24.dp)
                .clickable(onClick = onMenuTap),
        )
    }
}

/**
 * #211 - amber "PPLI OFF" chip: the operator has switched "Report my position"
 * off, so nothing is being sent to servers or the mesh. Single line, clipped
 * (never wrapped) when the gap it sits in is too narrow.
 */
@Composable
private fun PpliOffChip() {
    Text(
        Loc.t("map.pplioff"),
        color = Color.Black,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFFFFA000))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun ConnectionDot(isConnected: Boolean) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(if (isConnected) TacticalAccent else Color(0xFFE53935)),
    )
}

/**
 * Multi-server status cluster: "N/M" connected count followed by one small
 * dot per enabled server (green = connected, red = down). Shown on the map
 * status bar when the operator has more than one TAK server enabled.
 */
@Composable
private fun MultiServerIndicator(flags: List<Boolean>) {
    val connected = flags.count { it }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$connected/${flags.size}",
            color = if (connected > 0) TacticalAccent else Color(0xFFE53935),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
        )
        Spacer(Modifier.width(5.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            flags.forEach { up ->
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (up) TacticalAccent else Color(0xFFE53935)),
                )
            }
        }
    }
}

@Composable
private fun CounterChip(label: String, count: Int, tint: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = tint,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(2.dp))
        Text(
            count.toString(),
            color = tint,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
    }
}
