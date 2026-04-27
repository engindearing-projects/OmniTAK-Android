package soy.engindearing.omnitak.mobile.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import soy.engindearing.omnitak.mobile.data.MeshtasticBleClient
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent
import soy.engindearing.omnitak.mobile.ui.theme.TacticalSurface

/**
 * BLE device picker for the Meshtastic screen. Handles the
 * BLUETOOTH_SCAN/CONNECT permission flow on Android 12+, gracefully
 * falls back on older releases (which only need the location perms
 * already declared in the manifest).
 *
 * Behaviour mirrors the iOS picker — start scanning on tap, surface
 * each result (name, MAC, RSSI bars), tap to connect.
 */
@Composable
fun BleScanList(
    isScanning: Boolean,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    results: List<MeshtasticBleClient.BleScanResult>,
    onConnect: (address: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // BLUETOOTH_SCAN/CONNECT only exist on API 31+. Below that, the
    // location perms in the manifest are sufficient and we treat the
    // permission state as already-granted.
    val needsRuntimePerms = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val initiallyGranted = !needsRuntimePerms || MeshtasticBleClient.RUNTIME_PERMISSIONS.all { p ->
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    }
    var permsGranted by remember { mutableStateOf(initiallyGranted) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        permsGranted = granted.values.all { it }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!permsGranted) {
            Text(
                "Bluetooth scan + connect permissions are required to discover Meshtastic radios.",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        launcher.launch(MeshtasticBleClient.RUNTIME_PERMISSIONS)
                    } else {
                        // Older releases — fall through with location
                        // perms already in the manifest.
                        launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = TacticalAccent,
                    contentColor = Color.Black,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Grant Bluetooth permission") }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!isScanning) {
                    Button(
                        onClick = onStartScan,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TacticalAccent,
                            contentColor = Color.Black,
                        ),
                        modifier = Modifier.weight(1f),
                    ) { Text("Start scan") }
                } else {
                    OutlinedButton(
                        onClick = onStopScan,
                        modifier = Modifier.weight(1f),
                    ) { Text("Stop scan") }
                }
            }

            if (results.isEmpty()) {
                Text(
                    if (isScanning) "Scanning…" else "No devices yet — start a scan to discover Meshtastic radios.",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.height(260.dp),
                ) {
                    items(results, key = { it.address }) { d -> ScanRow(d, onConnect) }
                }
            }
        }
    }
}

@Composable
private fun ScanRow(
    device: MeshtasticBleClient.BleScanResult,
    onConnect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(TacticalSurface)
            .pointerInput(device.address) {
                detectTapGestures(onTap = { onConnect(device.address) })
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                device.name?.ifBlank { null } ?: "Unknown device",
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                device.address,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        RssiBars(device.rssi)
        Spacer(Modifier.width(6.dp))
        Text(
            "${device.rssi}",
            color = TacticalAccent,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun RssiBars(rssi: Int) {
    // 5-bar ladder. iOS uses similar thresholds.
    val bars = when {
        rssi >= -50 -> 5
        rssi >= -65 -> 4
        rssi >= -75 -> 3
        rssi >= -85 -> 2
        rssi >= -95 -> 1
        else -> 0
    }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(5) { i ->
            val active = i < bars
            val color = if (active) TacticalAccent else TacticalAccent.copy(alpha = 0.2f)
            // Each bar grows in height — bar 0 = 4dp, bar 4 = 12dp.
            val h = 4 + i * 2
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(h.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(color),
            )
        }
    }
}
