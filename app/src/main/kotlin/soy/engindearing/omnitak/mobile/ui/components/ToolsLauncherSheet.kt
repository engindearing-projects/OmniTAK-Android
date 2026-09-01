package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import soy.engindearing.omnitak.mobile.i18n.Loc

/**
 * Tools tab popup — mirrors the iOS ToolsLauncherSheet. A short
 * Material 3 ModalBottomSheet anchored at the bottom edge so the
 * underlying map (or whichever tab the user was on) stays visible
 * behind it. Every row navigates somewhere real — placeholder rows
 * advertising unported iOS features (the old "Full Tools…" snackbar
 * dead-end) are not allowed; add the row when the tool exists.
 *
 * The sheet's container color matches the bottom toolbar's tactical
 * dark surface so the popup reads as part of the same chrome family.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsLauncherSheet(
    onLasso: () -> Unit,
    onUAS: () -> Unit = {},
    onOnvifCamera: () -> Unit = {},
    onPeerMesh: () -> Unit = {},
    onGoToCoordinate: () -> Unit = {},
    onCustomize: () -> Unit = {},
    onMapOverlays: () -> Unit = {},
    map3dEnabled: Boolean = false,
    cesiumGlobeEnabled: Boolean = false,
    onToggleTerrain3D: () -> Unit = {},
    onToggleGlobe: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = Color(0xFF0F1115),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            ToolsRow(
                icon = { Icon(Icons.Filled.Build, contentDescription = null, tint = Color(0xFFFF9500)) },
                title = "Lasso Select",
                subtitle = "Long-press + drag on the map to multi-select features",
                onClick = onLasso,
            )

            HorizontalDivider(
                modifier = Modifier.padding(start = 76.dp),
                color = Color.White.copy(alpha = 0.08f),
            )

            ToolsRow(
                icon = { Icon(Icons.Filled.Terrain, contentDescription = null, tint = Color(0xFFFF9F0A)) },
                title = if (map3dEnabled && !cesiumGlobeEnabled) "3D Terrain ✓" else "3D Terrain",
                subtitle = "Tilted DEM relief over the current basemap (MapLibre)",
                onClick = onToggleTerrain3D,
            )

            HorizontalDivider(
                modifier = Modifier.padding(start = 76.dp),
                color = Color.White.copy(alpha = 0.08f),
            )

            ToolsRow(
                icon = { Icon(Icons.Filled.Public, contentDescription = null, tint = Color(0xFF4FA8FF)) },
                title = if (cesiumGlobeEnabled) "3D Globe (Photoreal) ✓" else "3D Globe (Photoreal)",
                subtitle = "Cesium photoreal globe with 3D buildings — like the iOS view",
                onClick = onToggleGlobe,
            )

            HorizontalDivider(
                modifier = Modifier.padding(start = 76.dp),
                color = Color.White.copy(alpha = 0.08f),
            )

            ToolsRow(
                icon = { Icon(Icons.Filled.FlightTakeoff, contentDescription = null, tint = Color(0xFF34C759)) },
                title = "Vehicle Connect (UAS / UGV)",
                subtitle = "MAVLink drones, rovers & boats — PX4 / ArduPilot, telemetry to CoT",
                onClick = onUAS,
            )

            HorizontalDivider(
                modifier = Modifier.padding(start = 76.dp),
                color = Color.White.copy(alpha = 0.08f),
            )

            ToolsRow(
                icon = { Icon(Icons.Filled.Videocam, contentDescription = null, tint = Color(0xFF00E5FF)) },
                title = "ONVIF Camera (PTZ)",
                subtitle = "Connect a PTZ IP camera — live RTSP feed + pan/tilt/zoom control",
                onClick = onOnvifCamera,
            )

            HorizontalDivider(
                modifier = Modifier.padding(start = 76.dp),
                color = Color.White.copy(alpha = 0.08f),
            )

            ToolsRow(
                icon = { Icon(Icons.Filled.Hub, contentDescription = null, tint = Color(0xFFFF9F0A)) },
                title = "Peer Mesh",
                subtitle = "Phone-to-phone CoT sync over Bluetooth and Wi-Fi — no server needed",
                onClick = onPeerMesh,
            )

            HorizontalDivider(
                modifier = Modifier.padding(start = 76.dp),
                color = Color.White.copy(alpha = 0.08f),
            )

            ToolsRow(
                icon = { Icon(Icons.Filled.Tune, contentDescription = null, tint = Color(0xFFFFCC00)) },
                title = "Customize Toolbar",
                subtitle = "Pick and arrange your own bottom-bar shortcuts",
                onClick = onCustomize,
            )

            HorizontalDivider(
                modifier = Modifier.padding(start = 76.dp),
                color = Color.White.copy(alpha = 0.08f),
            )

            ToolsRow(
                icon = { Icon(Icons.Filled.Layers, contentDescription = null, tint = Color(0xFFA78BFA)) },
                title = "Map Overlays",
                subtitle = "Import & toggle KML/KMZ overlays (handles huge files)",
                onClick = onMapOverlays,
            )

            HorizontalDivider(
                modifier = Modifier.padding(start = 76.dp),
                color = Color.White.copy(alpha = 0.08f),
            )

            ToolsRow(
                icon = { Icon(Icons.Filled.MyLocation, contentDescription = null, tint = Color(0xFF66D9A0)) },
                title = Loc.t("tools.gotoCoordinate"),
                subtitle = Loc.t("tools.gotoCoordinate.desc"),
                onClick = onGoToCoordinate,
            )
        }
    }
}

@Composable
private fun ToolsRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = Color.White,
                fontSize = 17.sp,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                subtitle,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.4f),
        )
    }
}
