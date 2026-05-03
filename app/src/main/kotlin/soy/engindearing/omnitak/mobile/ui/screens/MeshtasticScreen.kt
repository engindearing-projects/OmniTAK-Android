package soy.engindearing.omnitak.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import soy.engindearing.omnitak.mobile.OmniTAKApp
import soy.engindearing.omnitak.mobile.data.MeshNode
import soy.engindearing.omnitak.mobile.data.MeshtasticBleClient
import soy.engindearing.omnitak.mobile.domain.ConnectionState
import soy.engindearing.omnitak.mobile.domain.MeshtasticManager
import soy.engindearing.omnitak.mobile.ui.components.BleScanList
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent
import soy.engindearing.omnitak.mobile.ui.theme.TacticalBackground
import soy.engindearing.omnitak.mobile.ui.theme.TacticalSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshtasticScreen(
    onOpenTopology: () -> Unit = {},
    onOpenDeviceSettings: () -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as OmniTAKApp
    val mesh = app.meshtastic
    // Touching this lazy property kicks the bridge into active
    // collection so any nodes ingested below light up on the map.
    remember { app.meshtasticCoTBridge }
    val nodes by mesh.nodes.collectAsState()
    val connectionState by mesh.state.collectAsState()
    val bleState = mesh.bleState()?.collectAsState()?.value
    val anyConnected = connectionState is ConnectionState.Connected ||
        bleState is ConnectionState.Connected
    val userPrefs by app.userPrefsStore.prefs.collectAsState(
        initial = soy.engindearing.omnitak.mobile.data.UserPrefs(),
    )
    val coScope = rememberCoroutineScope()

    var selectedTab by remember { mutableStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = TacticalBackground,
        topBar = {
            TopAppBar(
                title = { Text("Meshtastic", color = MaterialTheme.colorScheme.onBackground) },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "Menu",
                            tint = TacticalAccent,
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("View Topology") },
                            onClick = {
                                menuOpen = false
                                onOpenTopology()
                            },
                        )
                        // GAP-109 — Meshtastic device settings entry point.
                        DropdownMenuItem(
                            text = { Text("Device settings") },
                            onClick = {
                                menuOpen = false
                                onOpenDeviceSettings()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "Auto-publish to TAK",
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (userPrefs.autoPublishMeshToTak) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = "Enabled",
                                            tint = TacticalAccent,
                                        )
                                    }
                                }
                            },
                            onClick = {
                                val next = !userPrefs.autoPublishMeshToTak
                                coScope.launch {
                                    app.userPrefsStore.setAutoPublishMeshToTak(next)
                                }
                                // Apply immediately — the prefs flow
                                // collector in OmniTAKApp will catch up
                                // shortly, but a direct write keeps the
                                // UX responsive when the toggle is
                                // tapped repeatedly.
                                app.meshtasticCoTBridge.enabled = next
                                menuOpen = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Disconnect") },
                            enabled = anyConnected,
                            onClick = {
                                menuOpen = false
                                mesh.disconnect()
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TacticalBackground,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { inner: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = TacticalBackground,
                contentColor = TacticalAccent,
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("TCP") },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("BLE") },
                )
            }
            when (selectedTab) {
                0 -> TcpPane(mesh, nodes.values.sortedByDescending { it.lastHeardEpoch }, nodes.size)
                else -> BlePane(mesh, nodes.values.sortedByDescending { it.lastHeardEpoch }, nodes.size)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TcpPane(
    mesh: MeshtasticManager,
    sortedNodes: List<MeshNode>,
    nodeCount: Int,
) {
    val connectionState by mesh.state.collectAsState()
    val bytes by mesh.bytesReceived.collectAsState()

    var host by remember { mutableStateOf("192.168.1.100") }
    var port by remember { mutableStateOf("4403") }

    val stateLabel = when (val s = connectionState) {
        ConnectionState.Disconnected -> "Disconnected"
        is ConnectionState.Connecting -> "Connecting to ${s.serverName}…"
        is ConnectionState.Connected -> "Connected to ${s.serverName}"
        is ConnectionState.Failed -> "Failed: ${s.reason}"
    }
    val connected = connectionState is ConnectionState.Connected

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader("TCP gateway")
        Text(
            "Point this at a Meshtastic device running the WiFi/TCP proxy (default port 4403). Frames decode through the Phase 1 protobuf parser into the node table below.",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Host / IP") },
            singleLine = true,
            enabled = !connected,
            colors = meshFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
            label = { Text("Port") },
            singleLine = true,
            enabled = !connected,
            colors = meshFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!connected) {
                Button(
                    onClick = {
                        val p = port.toIntOrNull() ?: 4403
                        mesh.connectTcp(host.trim(), p)
                    },
                    enabled = host.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TacticalAccent,
                        contentColor = androidx.compose.ui.graphics.Color.Black,
                    ),
                    modifier = Modifier.weight(1f),
                ) { Text("Connect") }
            } else {
                OutlinedButton(
                    onClick = { mesh.disconnect() },
                    modifier = Modifier.weight(1f),
                ) { Text("Disconnect") }
            }
        }

        HorizontalDivider(color = TacticalSurface)

        SectionHeader("Link")
        KeyValueRow(label = "State", value = stateLabel)
        KeyValueRow(label = "Bytes RX", value = "$bytes")
        KeyValueRow(label = "Nodes", value = "$nodeCount")

        HorizontalDivider(color = TacticalSurface)

        SectionHeader("Nodes")
        if (sortedNodes.isEmpty()) {
            Text(
                "No nodes yet. Once a Meshtastic radio connects, NodeInfo frames will populate this list automatically.",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            NodeList(sortedNodes)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun BlePane(
    mesh: MeshtasticManager,
    sortedNodes: List<MeshNode>,
    nodeCount: Int,
) {
    // Make sure the BLE client exists so we can observe its state.
    LaunchedEffect(Unit) { mesh.ensureBleReady() }

    val coScope = rememberCoroutineScope()
    val bleStateFlow = mesh.bleState()
    val bleBytesFlow = mesh.bleBytesReceived()
    val rssiFlow = mesh.bleRssi()

    val bleState = bleStateFlow?.collectAsState()?.value ?: ConnectionState.Disconnected
    val bleBytes = bleBytesFlow?.collectAsState()?.value ?: 0L
    val bleRssi = rssiFlow?.collectAsState()?.value ?: 0

    var isScanning by remember { mutableStateOf(false) }
    val results = remember { mutableStateListOf<MeshtasticBleClient.BleScanResult>() }

    DisposableEffect(Unit) {
        onDispose { mesh.stopBleScan() }
    }

    val stateLabel = when (val s = bleState) {
        ConnectionState.Disconnected -> "Disconnected"
        is ConnectionState.Connecting -> "Connecting to ${s.serverName}…"
        is ConnectionState.Connected -> "Connected to ${s.serverName}"
        is ConnectionState.Failed -> "Failed: ${s.reason}"
    }
    val connected = bleState is ConnectionState.Connected

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader("Bluetooth LE")
        Text(
            "Pair OmniTAK directly with a Meshtastic radio over BLE. Mirrors the iOS BLE transport: same service UUIDs, MTU 512, fromNum-notify with 1s safety polling.",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodySmall,
        )

        BleScanList(
            isScanning = isScanning,
            onStartScan = {
                results.clear()
                isScanning = true
                coScope.launch {
                    val flow = mesh.startBleScan() ?: run {
                        isScanning = false
                        return@launch
                    }
                    flow.collect { hit ->
                        if (results.none { it.address == hit.address }) results.add(hit)
                    }
                }
            },
            onStopScan = {
                isScanning = false
                mesh.stopBleScan()
            },
            results = results.toList(),
            onConnect = { addr ->
                isScanning = false
                mesh.stopBleScan()
                coScope.launch { mesh.connectBle(addr) }
            },
        )

        if (connected) {
            OutlinedButton(
                onClick = { mesh.disconnect() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Disconnect") }
        }

        HorizontalDivider(color = TacticalSurface)

        SectionHeader("Link")
        KeyValueRow(label = "State", value = stateLabel)
        KeyValueRow(label = "Bytes RX", value = "$bleBytes")
        KeyValueRow(label = "RSSI", value = if (bleRssi == 0) "—" else "$bleRssi dBm")
        KeyValueRow(label = "Nodes", value = "$nodeCount")

        HorizontalDivider(color = TacticalSurface)

        SectionHeader("Nodes")
        if (sortedNodes.isEmpty()) {
            Text(
                "No nodes yet. Connect to a Meshtastic radio over BLE to start receiving NodeInfo frames.",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            NodeList(sortedNodes)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        color = TacticalAccent,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun KeyValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            value,
            color = MaterialTheme.colorScheme.onBackground,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun NodeList(nodes: List<MeshNode>) {
    // Plain Column instead of LazyColumn — the parent Mesh screen is
    // already inside `Column(Modifier.verticalScroll(...))` so a nested
    // LazyColumn measures with infinite height and crashes the moment
    // there's at least one item to render. Mesh node lists max out at
    // a few dozen even on huge events, so virtualization isn't needed.
    var detailNode by remember { mutableStateOf<MeshNode?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        nodes.forEach { n ->
            NodeRow(node = n, onClick = { detailNode = n })
        }
    }
    detailNode?.let { selected ->
        // GAP-121 — surface the full record (works for position-less
        // nodes too) on tap.
        soy.engindearing.omnitak.mobile.ui.components.MeshNodeDetailSheet(
            node = selected,
            onDismiss = { detailNode = null },
        )
    }
}

@Composable
private fun NodeRow(node: MeshNode, onClick: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(TacticalSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                node.longName.ifBlank { node.shortName.ifBlank { node.idHex } },
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "id ${node.idHex}" +
                    (node.snr?.let { " · SNR ${"%.1f".format(it)}" } ?: "") +
                    (node.hopDistance?.let { " · hop $it" } ?: ""),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        node.batteryLevel?.let { Text("$it%", color = TacticalAccent) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun meshFieldColors() = TextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onBackground,
    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
    disabledTextColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
    focusedContainerColor = TacticalSurface,
    unfocusedContainerColor = TacticalSurface,
    disabledContainerColor = TacticalSurface,
    focusedIndicatorColor = TacticalAccent,
    unfocusedIndicatorColor = TacticalAccent.copy(alpha = 0.4f),
    focusedLabelColor = TacticalAccent,
    unfocusedLabelColor = TacticalAccent.copy(alpha = 0.6f),
    cursorColor = TacticalAccent,
)
