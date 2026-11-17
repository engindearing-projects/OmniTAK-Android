package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import soy.engindearing.omnitak.mobile.domain.ConnectionState
import soy.engindearing.omnitak.mobile.domain.GybManager
import soy.engindearing.omnitak.mobile.i18n.Loc
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent
import soy.engindearing.omnitak.mobile.ui.theme.TacticalSurface

/**
 * Scan / connect / status sheet for the external gyb_detect drone sensor.
 * Opened from Settings → Drone Detection → "gyb device". Mirrors the iOS
 * GybDetectorView: scan for advertising sensors (filtered to the gyb GATT
 * service UUID), tap a row to connect, live link status + device info +
 * detection count, and a Disconnect button.
 *
 * The address of a successful connect is persisted by [GybManager.connect]
 * so the manager can auto-reconnect on app start and after BLE drops while
 * the feature toggle stays on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GybDeviceSheet(
    gybManager: GybManager,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val connState by gybManager.connectionState.collectAsState()
    val scanResults by gybManager.scanResults.collectAsState()
    val battery by gybManager.batteryLevel.collectAsState()
    val droneCount by gybManager.droneCount.collectAsState()
    val deviceModel by gybManager.deviceModel.collectAsState()

    var isScanning by remember { mutableStateOf(false) }
    // GybBleClient auto-stops its scan after 12 s — flip the button back
    // in lockstep so "Stop scan" never lies about a dead scan.
    LaunchedEffect(isScanning) {
        if (isScanning) {
            delay(12_000)
            isScanning = false
        }
    }
    DisposableEffect(Unit) {
        onDispose { gybManager.stopScan() }
    }

    val connected = connState is ConnectionState.Connected
    val stateLabel = when (val s = connState) {
        ConnectionState.Disconnected -> Loc.t("gyb.state.disconnected")
        is ConnectionState.Connecting -> Loc.t("gyb.state.connecting", s.serverName)
        is ConnectionState.Connected -> Loc.t("gyb.state.connected", s.serverName)
        is ConnectionState.Failed -> Loc.t("gyb.state.failed", s.reason)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0F1115),
    ) {
        // #222: status rows, a Disconnect button and the 260 dp scan list stack up
        // past a landscape phone once a scan returns results; scroll keeps them reachable.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                Loc.t("gyb.sheet.title"),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                Loc.t("gyb.sheet.desc"),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
            )

            StatusRow(Loc.t("gyb.row.state"), stateLabel)
            if (connected) {
                deviceModel?.let { StatusRow(Loc.t("gyb.row.device"), it) }
                battery?.let { StatusRow(Loc.t("gyb.row.battery"), "${(it * 100).toInt()}%") }
                StatusRow(Loc.t("gyb.row.drones"), "$droneCount")
            }

            if (connected || connState is ConnectionState.Connecting) {
                OutlinedButton(
                    onClick = { gybManager.disconnect() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(Loc.t("gyb.disconnect")) }
            }

            HorizontalDivider(color = TacticalSurface)

            BleScanList(
                isScanning = isScanning,
                onStartScan = {
                    isScanning = true
                    gybManager.scan()
                },
                onStopScan = {
                    isScanning = false
                    gybManager.stopScan()
                },
                results = scanResults.map {
                    BleDeviceItem(name = it.name, address = it.address, rssi = it.rssi)
                },
                onConnect = { addr ->
                    isScanning = false
                    gybManager.stopScan()
                    gybManager.connect(addr)
                },
                deviceNoun = "gyb sensors",
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            value,
            color = TacticalAccent,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}
