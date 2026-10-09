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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import soy.engindearing.omnitak.mobile.OmniTAKApp
import soy.engindearing.omnitak.mobile.domain.AggregatedMission
import soy.engindearing.omnitak.mobile.domain.AggregatedPackage
import soy.engindearing.omnitak.mobile.domain.MissionServerStatus
import soy.engindearing.omnitak.mobile.domain.ServerSyncSession
import soy.engindearing.omnitak.mobile.i18n.Loc
import soy.engindearing.omnitak.mobile.ui.theme.HostileRed
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent
import soy.engindearing.omnitak.mobile.ui.theme.TacticalBackground
import soy.engindearing.omnitak.mobile.ui.theme.TacticalSurface

/**
 * Multi-server Mission Sync UI, bound to [MissionSyncManager] (no stubs).
 * Shows every enabled server's live status and an aggregated list of missions
 * + data packages across all of them. Android counterpart to iOS's
 * MissionSyncView.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MissionSyncScreen(onBack: () -> Unit = {}) {
    val app = LocalContext.current.applicationContext as OmniTAKApp
    val manager = app.missionSyncManager
    val sessions by manager.sessions.collectAsState()
    val isRefreshing by manager.isRefreshing.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    fun toast(msg: String) {
        scope.launch { snackbar.showSnackbar(msg, withDismissAction = true) }
    }
    // "New Mission" sheet (createMission) + package→mission attach
    // picker (attachHashToMission) — the screen used to be a read-only
    // status display while these Marti write APIs sat unwired.
    var newMissionOpen by remember { mutableStateOf(false) }
    var attachTarget by remember { mutableStateOf<AggregatedPackage?>(null) }

    LaunchedEffect(Unit) { manager.refreshAll() }

    val onlineCount = sessions.count { it.status.isOnline }
    val allMissions = sessions.flatMap { s ->
        s.missions.map { AggregatedMission(s.serverId, s.serverName, it) }
    }
    val allPackages = sessions.flatMap { s ->
        s.dataPackages.map { AggregatedPackage(s.serverId, s.serverName, it) }
    }

    Scaffold(
        containerColor = TacticalBackground,
        topBar = {
            TopAppBar(
                title = { Text("Mission Sync", color = MaterialTheme.colorScheme.onBackground) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { newMissionOpen = true },
                        enabled = sessions.any { it.status.isOnline },
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = Loc.t("mission.new.title"),
                            tint = TacticalAccent,
                        )
                    }
                    IconButton(
                        onClick = { scope.launch { manager.refreshAll() } },
                        enabled = !isRefreshing,
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = TacticalAccent,
                            )
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = TacticalAccent)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TacticalBackground,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner: PaddingValues ->
        if (sessions.isEmpty()) {
            EmptyMissionSync(Modifier.padding(inner))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                SectionHeader("SERVERS", "$onlineCount/${sessions.size} online")
            }
            items(sessions, key = { it.serverId }) { s ->
                ServerStatusRow(s, onTap = { scope.launch { manager.refresh(s.serverId) } })
            }

            if (allMissions.isNotEmpty()) {
                item { SectionHeader("MISSIONS", "${allMissions.size}") }
                items(allMissions, key = { it.id }) { m -> MissionRow(m) }
            }

            if (allPackages.isNotEmpty()) {
                item { SectionHeader("DATA PACKAGES", "${allPackages.size}") }
                items(allPackages, key = { it.id }) { p ->
                    PackageRow(p, onTap = { attachTarget = p })
                }
            }
        }
    }

    if (newMissionOpen) {
        NewMissionSheet(
            servers = sessions.filter { it.status.isOnline },
            onCreate = { serverId, name, description ->
                newMissionOpen = false
                val serverName = sessions
                    .firstOrNull { it.serverId == serverId }?.serverName ?: serverId
                scope.launch {
                    val creator = app.userPrefsStore.ensureSelfUid()
                    manager.createMission(serverId, name, description, creator).fold(
                        onSuccess = { toast(Loc.t("mission.new.created", it.name, serverName)) },
                        onFailure = {
                            toast(Loc.t("mission.new.failed", it.message ?: "unknown error"))
                        },
                    )
                }
            },
            onDismiss = { newMissionOpen = false },
        )
    }

    // Tap a data package → attach its hash to a mission on the same
    // server (PUT /Marti/api/missions/{name}/contents).
    attachTarget?.let { pkg ->
        val missionsOnServer = sessions
            .firstOrNull { it.serverId == pkg.serverId }?.missions ?: emptyList()
        if (missionsOnServer.isEmpty()) {
            LaunchedEffect(pkg.id) {
                snackbar.showSnackbar(
                    Loc.t("mission.attach.none", pkg.serverName),
                    withDismissAction = true,
                )
                attachTarget = null
            }
        } else {
            AlertDialog(
                onDismissRequest = { attachTarget = null },
                containerColor = TacticalSurface,
                title = {
                    Text(
                        Loc.t("mission.attach.title", pkg.pkg.name),
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                },
                text = {
                    Column {
                        missionsOnServer.forEach { m ->
                            TextButton(
                                onClick = {
                                    attachTarget = null
                                    scope.launch {
                                        manager.attachPackageToMission(
                                            serverId = pkg.serverId,
                                            missionName = m.name,
                                            hash = pkg.pkg.hash,
                                        ).fold(
                                            onSuccess = {
                                                toast(Loc.t("mission.attach.ok", m.name))
                                            },
                                            onFailure = {
                                                toast(
                                                    Loc.t(
                                                        "mission.attach.failed",
                                                        it.message ?: "unknown error",
                                                    )
                                                )
                                            },
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(m.name, color = TacticalAccent, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { attachTarget = null }) {
                        Text(Loc.t("common.cancel"), color = TacticalAccent)
                    }
                },
            )
        }
    }
}

/**
 * Minimal mission-authoring sheet — name + optional description, plus a
 * server picker when more than one server is online. Mirrors the iOS
 * MissionCreationSheet scope (slice 2 of #30).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewMissionSheet(
    servers: List<ServerSyncSession>,
    onCreate: (serverId: String, name: String, description: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState()
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var serverId by remember { mutableStateOf(servers.firstOrNull()?.serverId) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = Color(0xFF0F1115),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                Loc.t("mission.new.title"),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(Loc.t("mission.new.name")) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(Loc.t("mission.new.desc")) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
            if (servers.size > 1) {
                Text(
                    Loc.t("mission.new.server"),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 12.dp),
                )
                servers.forEach { s ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { serverId = s.serverId }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = serverId == s.serverId,
                            onClick = { serverId = s.serverId },
                        )
                        Text(s.serverName, color = Color.White)
                    }
                }
            }
            Button(
                onClick = {
                    serverId?.let {
                        onCreate(it, name.trim(), description.trim().ifEmpty { null })
                    }
                },
                enabled = name.isNotBlank() && serverId != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            ) {
                Text(Loc.t("mission.new.create"))
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, trailing: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        Text(
            trailing,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ServerStatusRow(s: ServerSyncSession, onTap: () -> Unit) {
    // An offline row opens its diagnosis (#169 parity); online rows refresh.
    var showDetail by remember { mutableStateOf(false) }
    val offline = s.status as? MissionServerStatus.Offline
    if (showDetail && offline != null) {
        val clipboard = LocalClipboardManager.current
        val text = listOfNotNull("${s.serverName} (${s.host}): ${offline.reason}", offline.detail).joinToString("\n\n")
        AlertDialog(
            onDismissRequest = { showDetail = false },
            title = { Text(s.serverName) },
            text = {
                Column {
                    Text(offline.reason, style = MaterialTheme.typography.bodyMedium)
                    offline.detail?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("Copy") }
            },
            dismissButton = {
                TextButton(onClick = { showDetail = false; onTap() }) { Text("Retry") }
            },
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(TacticalSurface)
            .clickable(onClick = { if (offline != null) showDetail = true else onTap() })
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(s.status)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                s.serverName,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                statusLine(s),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (s.status.isOnline) {
            Text(
                "${s.missions.size}m · ${s.dataPackages.size}p",
                color = TacticalAccent,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun StatusDot(status: MissionServerStatus) {
    Box(modifier = Modifier.size(14.dp), contentAlignment = Alignment.Center) {
        when (status) {
            is MissionServerStatus.Checking -> CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
                color = TacticalAccent,
            )
            is MissionServerStatus.Online -> Dot(Color(0xFF4CAF50))
            is MissionServerStatus.Offline -> Dot(HostileRed)
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color),
    )
}

private fun statusLine(s: ServerSyncSession): String = when (val st = s.status) {
    is MissionServerStatus.Checking -> "${s.host} — checking…"
    is MissionServerStatus.Online -> s.authLabel?.let { "${s.host} · $it" } ?: s.host
    is MissionServerStatus.Offline -> st.reason
}

@Composable
private fun MissionRow(item: AggregatedMission) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(TacticalSurface)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.mission.name,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            ServerBadge(item.serverName)
        }
        val desc = item.mission.description
        if (!desc.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                desc,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
            )
        }
        if (item.mission.contentCount > 0) {
            Spacer(Modifier.height(2.dp))
            val n = item.mission.contentCount
            Text(
                "$n item${if (n == 1) "" else "s"}",
                color = TacticalAccent,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun PackageRow(item: AggregatedPackage, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(TacticalSurface)
            .clickable(onClick = onTap)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Inventory2,
            contentDescription = null,
            tint = TacticalAccent,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.pkg.name,
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            if (item.pkg.size > 0) {
                Text(
                    formatBytes(item.pkg.size),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        ServerBadge(item.serverName)
    }
}

@Composable
private fun ServerBadge(name: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(TacticalAccent.copy(alpha = 0.85f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            name,
            color = Color.Black,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun EmptyMissionSync(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Sync,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = TacticalAccent.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "No servers enabled",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Enable a TLS TAK server with a client certificate in Servers, then " +
                "refresh. Every enabled server syncs here at once.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    return String.format("%.1f GB", mb / 1024.0)
}
