package soy.engindearing.omnitak.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import soy.engindearing.omnitak.mobile.OmniTAKApp
import soy.engindearing.omnitak.mobile.data.MeshChannelPreset
import soy.engindearing.omnitak.mobile.data.MeshDeviceConfig
import soy.engindearing.omnitak.mobile.data.MeshRole
import soy.engindearing.omnitak.mobile.domain.ConnectionState
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent
import soy.engindearing.omnitak.mobile.ui.theme.TacticalBackground
import soy.engindearing.omnitak.mobile.ui.theme.TacticalSurface

/**
 * GAP-109 — Meshtastic device settings UI.
 *
 * MVP slice. Surfaces the four config fields the practitioner asked
 * for (long/short name, role, PLI cadence, channel-0 name + preset)
 * as a draft buffer persisted to DataStore.
 *
 * **Write-to-device is gated.** The Meshtastic admin protocol round
 * trip needs the protobuf set in the Gradle build before we can push
 * config to a connected radio. The screen tells the operator that
 * honestly. Reading back current device config is gated on the same
 * dependency.
 *
 * For this iteration: edit, save the draft, see it persisted across
 * app launches. When the protobuf path lands, the same DataStore
 * config becomes the seed payload for the AdminMessage write.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshDeviceSettingsScreen(onDone: () -> Unit) {
    val app = LocalContext.current.applicationContext as OmniTAKApp
    val store = app.meshDeviceConfigStore
    val mesh = app.meshtastic
    val saved by store.config.collectAsState(initial = MeshDeviceConfig())
    val connection by mesh.state.collectAsState()
    val scope = rememberCoroutineScope()

    // Local draft buffer — text fields edit this, "Save draft" commits
    // it back to DataStore. Mirrors the local-draft pattern other
    // settings screens use so the keyboard stays responsive without a
    // DataStore round-trip per keystroke.
    var draft by remember(saved) { mutableStateOf(saved) }
    var dirty by remember(saved) { mutableStateOf(false) }
    var savedToast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(savedToast) {
        if (savedToast != null) {
            kotlinx.coroutines.delay(1800)
            savedToast = null
        }
    }

    val deviceConnected = connection is ConnectionState.Connected
    val connectionLabel = when (val s = connection) {
        ConnectionState.Disconnected -> "No device connected"
        is ConnectionState.Connecting -> "Connecting to ${s.serverName}…"
        is ConnectionState.Connected -> "Connected to ${s.serverName}"
        is ConnectionState.Failed -> "Connection failed: ${s.reason}"
    }

    Scaffold(
        containerColor = TacticalBackground,
        topBar = {
            TopAppBar(
                title = { Text("Device settings", color = MaterialTheme.colorScheme.onBackground) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TacticalBackground,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { inner: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            DeviceSyncBanner(connectionLabel = connectionLabel, deviceConnected = deviceConnected)

            MeshSection("Identity")
            OutlinedTextField(
                value = draft.longName,
                onValueChange = { v ->
                    draft = draft.copy(longName = v.take(40))
                    dirty = true
                },
                label = { Text("Long name") },
                singleLine = true,
                colors = meshSettingFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.shortName,
                onValueChange = { v ->
                    // Meshtastic short_name caps at 4 visible chars on the
                    // tiny OLEDs; uppercase ASCII is the convention.
                    draft = draft.copy(shortName = v.uppercase().take(4))
                    dirty = true
                },
                label = { Text("Short name (max 4)") },
                singleLine = true,
                colors = meshSettingFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            MeshSection("Role")
            EnumDropdown(
                selected = draft.role,
                options = MeshRole.values().toList(),
                labelOf = { it.label },
                descriptionOf = { it.description },
                onSelect = { v ->
                    draft = draft.copy(role = v)
                    dirty = true
                },
            )

            MeshSection("Position broadcast (PLI)")
            Text(
                "How often this radio broadcasts its position to the mesh. " +
                    "Lower = fresher icons on every operator's map but more airtime. " +
                    "ATAK convention is 30 s; 80-node events often back this off to 60-120 s.",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = draft.positionBroadcastSecs.toString(),
                onValueChange = { v ->
                    val cleaned = v.filter { c -> c.isDigit() }.take(5)
                    val parsed = cleaned.toIntOrNull() ?: 0
                    draft = draft.copy(positionBroadcastSecs = parsed.coerceIn(0, 24 * 60 * 60))
                    dirty = true
                },
                label = { Text("Interval (seconds, 0 disables)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = meshSettingFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            QuickPickRow(
                options = listOf(15, 30, 60, 120, 300),
                selected = draft.positionBroadcastSecs,
                labelOf = { secs -> if (secs >= 60) "${secs / 60}m" else "${secs}s" },
                onSelect = { v ->
                    draft = draft.copy(positionBroadcastSecs = v)
                    dirty = true
                },
            )

            MeshSection("Primary channel")
            OutlinedTextField(
                value = draft.channelName,
                onValueChange = { v ->
                    draft = draft.copy(channelName = v.take(11))
                    dirty = true
                },
                label = { Text("Channel name (max 11)") },
                singleLine = true,
                colors = meshSettingFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            EnumDropdown(
                selected = draft.channelPreset,
                options = MeshChannelPreset.values().toList(),
                labelOf = { it.label },
                descriptionOf = { it.blurb },
                onSelect = { v ->
                    draft = draft.copy(channelPreset = v)
                    dirty = true
                },
            )

            Spacer(Modifier.height(4.dp))

            SaveDraftRow(
                dirty = dirty,
                onSave = {
                    val toCommit = draft
                    scope.launch {
                        store.update { toCommit }
                        dirty = false
                        savedToast = "Saved draft locally"
                    }
                },
                onRevert = {
                    draft = saved
                    dirty = false
                },
            )

            ComingSoonNote()

            savedToast?.let { msg ->
                Text(
                    msg,
                    color = TacticalAccent,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DeviceSyncBanner(connectionLabel: String, deviceConnected: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(TacticalSurface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(50))
                .background(if (deviceConnected) TacticalAccent else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                connectionLabel,
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Edits below save to a local draft. Push-to-device lands when admin protobuf ships.",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun MeshSection(text: String) {
    Text(
        text.uppercase(),
        color = TacticalAccent,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun ComingSoonNote() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(TacticalSurface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column {
            Text(
                "Push to device — coming soon",
                color = TacticalAccent,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Writing config back to a connected radio uses the Meshtastic admin protobuf (AdminMessage / set_owner / set_config). " +
                    "Wiring those into the OmniTAK build is the next slice — until then, this screen is a draft buffer that syncs once the path opens up.",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun SaveDraftRow(dirty: Boolean, onSave: () -> Unit, onRevert: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(46.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (dirty) TacticalAccent else TacticalSurface)
                .clickable(enabled = dirty) { onSave() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Save draft",
                color = if (dirty) TacticalBackground else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                fontWeight = FontWeight.SemiBold,
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(46.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(TacticalSurface)
                .clickable(enabled = dirty) { onRevert() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Revert",
                color = if (dirty) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
            )
        }
    }
}

@Composable
private fun <T> QuickPickRow(
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(TacticalSurface),
    ) {
        options.forEachIndexed { idx, value ->
            val isSelected = value == selected
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .background(if (isSelected) TacticalAccent.copy(alpha = 0.2f) else Color.Transparent)
                    .clickable { onSelect(value) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    labelOf(value),
                    color = if (isSelected) TacticalAccent else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (idx < options.size - 1) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(40.dp)
                        .background(TacticalBackground),
                )
            }
        }
    }
}

@Composable
private fun <T> EnumDropdown(
    selected: T,
    options: List<T>,
    labelOf: (T) -> String,
    descriptionOf: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(TacticalSurface)
                .clickable { expanded = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    labelOf(selected),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    descriptionOf(selected),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = "Open",
                tint = TacticalAccent,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(TacticalSurface),
        ) {
            options.forEach { v ->
                val isSelected = v == selected
                DropdownMenuItem(
                    onClick = {
                        onSelect(v)
                        expanded = false
                    },
                    text = {
                        Column {
                            Text(
                                labelOf(v),
                                color = if (isSelected) TacticalAccent else MaterialTheme.colorScheme.onBackground,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Text(
                                descriptionOf(v),
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun meshSettingFieldColors() = TextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onBackground,
    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
    focusedContainerColor = TacticalSurface,
    unfocusedContainerColor = TacticalSurface,
    focusedIndicatorColor = TacticalAccent,
    unfocusedIndicatorColor = TacticalAccent.copy(alpha = 0.4f),
    focusedLabelColor = TacticalAccent,
    unfocusedLabelColor = TacticalAccent.copy(alpha = 0.6f),
    cursorColor = TacticalAccent,
)

