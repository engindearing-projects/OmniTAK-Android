package soy.engindearing.omnitak.mobile.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import soy.engindearing.omnitak.mobile.OmniTAKApp
import soy.engindearing.omnitak.mobile.domain.DittoMeshService
import java.text.DateFormat
import java.util.Date

/**
 * Operator controls for the Ditto peer-to-peer mesh — Android port of iOS
 * `DittoMeshSettingsView`. Intentionally short: the mesh is meant to work with
 * nobody touching it, so this screen exists to explain what is happening and
 * to expose the two decisions that genuinely belong to the operator — which
 * channel to ride, and whether this device backhauls other people's traffic
 * to a TAK server.
 *
 * The enable toggle doubles as the opt-in consent gate: the mesh shares this
 * device's position with nearby installs, so it is OFF until the operator
 * flips it, and flipping it also requests the runtime permissions Ditto's
 * transports need (BLE advertise/scan/connect, Nearby Wi-Fi on 13+).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DittoMeshScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as OmniTAKApp
    val mesh = app.dittoMesh
    val scope = rememberCoroutineScope()

    val prefs by app.userPrefsStore.prefs.collectAsState(
        initial = soy.engindearing.omnitak.mobile.data.UserPrefs(),
    )
    val state by mesh.state.collectAsState()
    val peers by mesh.peerCount.collectAsState()
    val incompatiblePeers by mesh.incompatiblePeerCount.collectAsState()
    val sent by mesh.published.collectAsState()
    val received by mesh.received.collectAsState()
    val relayed by mesh.relayed.collectAsState()
    val lastInbound by mesh.lastInboundAtMs.collectAsState()

    var channelDraft by remember(prefs.dittoMeshChannel) { mutableStateOf(prefs.dittoMeshChannel) }

    // Ditto's transports need runtime grants the Meshtastic BLE path doesn't
    // already hold (ADVERTISE — we become a peripheral; NEARBY_WIFI_DEVICES
    // for Wi-Fi Aware on 13+). Denial isn't fatal: Ditto simply runs on
    // whichever transports remain, so the toggle proceeds either way.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* best-effort — the mesh uses whatever was granted */ }

    fun runtimePermissions(): Array<String> {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            wanted += Manifest.permission.BLUETOOTH_SCAN
            wanted += Manifest.permission.BLUETOOTH_CONNECT
            wanted += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        if (Build.VERSION.SDK_INT >= 33) {
            wanted += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        return wanted.toTypedArray()
    }

    Scaffold(
        containerColor = soy.engindearing.omnitak.mobile.ui.theme.TacticalBackground,
        topBar = {
            TopAppBar(
                title = { Text("Peer Mesh") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = soy.engindearing.omnitak.mobile.ui.theme.TacticalBackground,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!mesh.isConfigured) {
                Section {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFFFA726))
                        Spacer(Modifier.padding(4.dp))
                        Text("Not available in this build", style = MaterialTheme.typography.titleSmall)
                    }
                    Text(
                        "This copy of OmniTAK was built without peer-to-peer mesh credentials. " +
                            "Everything else — TAK servers, Meshtastic, MeshCore — works normally.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
                }
            } else {
                Section {
                    SectionTitle("Status")
                    StatusRow("State", state.label, live = state is DittoMeshService.State.Syncing)
                    PlainRow("Peers in range", "$peers")
                    if (incompatiblePeers > 0) {
                        PlainRow("Incompatible peers", "$incompatiblePeers")
                        Text(
                            "A nearby device runs an incompatible mesh version and will " +
                                "never sync, no matter how close it stands. Update both apps.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFFA726),
                        )
                    }
                    PlainRow("Sent", "$sent")
                    PlainRow("Received", "$received")
                    if (prefs.dittoGatewayEnabled) PlainRow("Relayed to servers", "$relayed")
                    lastInbound?.let {
                        PlainRow("Last contact", DateFormat.getTimeInstance().format(Date(it)))
                    }
                }

                Section {
                    ToggleRow(
                        title = "Peer mesh",
                        subtitle = "Share position, markers and chat with nearby OmniTAK devices",
                        checked = prefs.dittoMeshEnabled,
                        onChange = { on ->
                            if (on) permissionLauncher.launch(runtimePermissions())
                            scope.launch { app.userPrefsStore.setDittoMeshEnabled(on) }
                        },
                    )
                    OutlinedTextField(
                        value = channelDraft,
                        onValueChange = { channelDraft = it },
                        label = { Text("Channel") },
                        placeholder = { Text("omnitak") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (channelDraft.trim() != prefs.dittoMeshChannel) {
                        Text(
                            "Tap the mesh toggle off/on or leave this screen to apply.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                        )
                    }
                    Text(
                        "Only devices on the same channel share tracks. Leave it alone unless " +
                            "you want a private group — everyone must type it identically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
                }

                Section {
                    SectionTitle("Gateway")
                    ToggleRow(
                        title = "Relay mesh to TAK servers",
                        subtitle = null,
                        checked = prefs.dittoGatewayEnabled,
                        onChange = { on -> scope.launch { app.userPrefsStore.setDittoGatewayEnabled(on) } },
                    )
                    Text(
                        "When this device is connected to a TAK server, forward everything it " +
                            "hears on the mesh to that server. One connected phone puts the whole " +
                            "local group on the server's map, even for teammates with no signal.\n\n" +
                            "Off by default: it publishes other people's positions to a server " +
                            "they may not be enrolled on.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
                }
            }

            Section {
                SectionTitle("How it works")
                Text(
                    "OmniTAK devices near each other connect directly over Bluetooth and " +
                        "Wi-Fi and share position, markers and chat — no TAK server, no data " +
                        "package, no internet, nothing to pair.\n\nWhen any device does have " +
                        "a connection, the same picture syncs onward, so people out of radio " +
                        "range still see it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    // Apply a channel edit when the operator leaves the screen — mirrors the
    // iOS focus-loss commit without needing an explicit save button.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            val next = channelDraft.trim()
            if (next.isNotEmpty() && next != prefs.dittoMeshChannel) {
                scope.launch { app.userPrefsStore.setDittoMeshChannel(next) }
            }
        }
    }
}

@Composable
private fun Section(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(soy.engindearing.omnitak.mobile.ui.theme.TacticalSurface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun PlainRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
    }
}

@Composable
private fun StatusRow(label: String, value: String, live: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (live) Color(0xFF66BB6A) else Color.Gray),
        )
        Spacer(Modifier.padding(3.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = if (live) Color.Unspecified else Color.Gray)
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent,
            ),
        )
    }
}
