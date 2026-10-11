package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import soy.engindearing.omnitak.mobile.domain.MultiUasRegistry
import soy.engindearing.omnitak.mobile.domain.UASManager

/**
 * Compact "Drones (N)" pill — visible when 1+ drones are connected.
 * Tap → bottom sheet listing each drone with its callsign, host, link
 * status, armed state, and an "Active" badge on the current command
 * target.
 *
 * Multi-drone ops: from this sheet the operator picks which drone the
 * HUD / commands bind to. Each row also has a Disconnect button so
 * inactive drones can be torn down without flipping active first.
 */
@Composable
fun UasDronesPill(
    droneCount: Int,
    activeCallsign: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (droneCount == 0) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.78f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Icon(Icons.Filled.Flight, contentDescription = "Drones", tint = Color(0xFF00E5FF))
        Text(
            text = "Drones $droneCount",
            color = Color(0xFF00E5FF),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge,
        )
        if (activeCallsign != null) {
            Text(
                text = "· $activeCallsign",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UasDronesPickerSheet(
    drones: List<MultiUasRegistry.Entry>,
    activeManager: UASManager,
    onPick: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1A1F2E),
    ) {
        // #222: one card per connected drone; from the third drone on, the list
        // outgrew a landscape phone and the extra drones could not be picked.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Connected drones",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Text(
                "Tap to make active — HUD, commands, and video bind to the active drone.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
            )
            drones.forEach { entry ->
                DroneRow(
                    entry = entry,
                    isActive = entry.manager === activeManager,
                    onPick = { onPick(entry.droneId); onDismiss() },
                    onDisconnect = { onDisconnect(entry.droneId) },
                )
            }
        }
    }
}

@Composable
private fun DroneRow(
    entry: MultiUasRegistry.Entry,
    isActive: Boolean,
    onPick: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val state by entry.manager.state.collectAsState()
    val connected = state.isConnected()
    val armed = state.armed == true
    val borderColor = when {
        isActive -> Color(0xFF00E5FF)
        connected -> Color.White.copy(alpha = 0.25f)
        else -> Color(0xFFFF3B30).copy(alpha = 0.4f)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(enabled = !isActive, onClick = onPick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                entry.callsign,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (isActive) {
                Text(
                    "ACTIVE",
                    color = Color(0xFF00E5FF),
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (armed) {
                Text(
                    "ARMED",
                    color = Color(0xFFFF3B30),
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Text(
            "${entry.transport} ${entry.host}:${entry.port} · ${if (connected) "LINK OK" else "no link"}",
            color = borderColor,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDisconnect) {
                Text("Disconnect", color = Color(0xFFFF3B30))
            }
        }
    }
}

