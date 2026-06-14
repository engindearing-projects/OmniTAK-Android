package soy.engindearing.omnitak.mobile.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import soy.engindearing.omnitak.mobile.OmniTAKApp
import soy.engindearing.omnitak.mobile.data.UserPrefs
import soy.engindearing.omnitak.mobile.domain.LassoSelectionService
import soy.engindearing.omnitak.mobile.ui.components.BarCommand
import soy.engindearing.omnitak.mobile.ui.components.BarItem
import soy.engindearing.omnitak.mobile.ui.components.BarKind
import soy.engindearing.omnitak.mobile.ui.components.CustomToolbar
import soy.engindearing.omnitak.mobile.ui.components.ToolbarAddPalette
import soy.engindearing.omnitak.mobile.ui.components.ToolbarCatalog
import soy.engindearing.omnitak.mobile.ui.components.ToolbarEditBus
import soy.engindearing.omnitak.mobile.ui.components.ToolsLauncherSheet
import soy.engindearing.omnitak.mobile.ui.screens.AboutScreen
import soy.engindearing.omnitak.mobile.ui.screens.AddServerScreen
import soy.engindearing.omnitak.mobile.ui.screens.ChatScreen
import soy.engindearing.omnitak.mobile.ui.screens.EnrollServerScreen
import soy.engindearing.omnitak.mobile.ui.screens.ImportPreviewDialog
import soy.engindearing.omnitak.mobile.ui.screens.MapScreen
import soy.engindearing.omnitak.mobile.ui.screens.MeshDeviceSettingsScreen
import soy.engindearing.omnitak.mobile.ui.screens.MeshTopologyScreen
import soy.engindearing.omnitak.mobile.ui.screens.MeshtasticScreen
import soy.engindearing.omnitak.mobile.ui.screens.ProfilesScreen
import soy.engindearing.omnitak.mobile.ui.screens.ServersScreen
import soy.engindearing.omnitak.mobile.ui.screens.SettingsScreen
import soy.engindearing.omnitak.mobile.ui.screens.UASScreen

@Composable
fun AppNav() {
    val app = LocalContext.current.applicationContext as OmniTAKApp
    val prefs by app.userPrefsStore.prefs.collectAsState(initial = UserPrefs())
    val scope = rememberCoroutineScope()

    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    var showToolsLauncher by remember { mutableStateOf(false) }
    var showAddPalette by remember { mutableStateOf(false) }
    var showKmlOverlays by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }

    fun navigateTo(route: String) {
        nav.navigate(route) {
            popUpTo(nav.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // Working copy of the bar layout. Edits mutate this live (smooth, no
    // DataStore churn); we persist on Done / add / reset. While not editing
    // it tracks the saved config.
    val savedIds = prefs.toolbarItemIds.ifEmpty { ToolbarCatalog.defaultIds }
    val workingIds = remember { mutableStateListOf<String>() }
    LaunchedEffect(savedIds, editing) {
        if (!editing) {
            workingIds.clear()
            workingIds.addAll(savedIds)
        }
    }

    fun persistToolbar() {
        scope.launch { app.userPrefsStore.setToolbarItemIds(workingIds.toList()) }
    }

    fun enterEdit() {
        scope.launch { app.userPrefsStore.setToolbarCoachmarkSeen(true) }
        editing = true
    }

    // Settings / Tools popup can request edit mode from another screen.
    val editGen by ToolbarEditBus.editGeneration.collectAsState()
    LaunchedEffect(editGen) {
        if (editGen > 0L) {
            navigateTo("map")
            editing = true
        }
    }

    // DEBUG: auto-import a KML/KMZ staged in filesDir/import/ (mirrors the
    // DataPackageBootstrap sideload) so the real on-device import + render
    // path can be exercised with large files without the document picker.
    LaunchedEffect(Unit) {
        val debuggable = (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable && app.kmlOverlayStore.overlays.value.isEmpty()) {
            val ext = java.io.File(app.getExternalFilesDir(null) ?: app.filesDir, "import")
            val intl = java.io.File(app.filesDir, "import")
            val candidates = (ext.listFiles()?.toList() ?: emptyList()) +
                (intl.listFiles()?.toList() ?: emptyList())
            val kml = candidates.firstOrNull { it.extension.lowercase() in listOf("kml", "kmz") }
            if (kml != null) {
                app.kmlOverlayStore.importKml(kml, kml.name)
                app.kmlOverlayStore.overlays.value.lastOrNull()?.let {
                    soy.engindearing.omnitak.mobile.ui.components.KmlOverlayEvents.requestZoom(it)
                }
            }
        }
    }

    fun dispatch(item: BarItem) {
        when (val kind = item.kind) {
            is BarKind.Destination -> navigateTo(kind.route)
            is BarKind.Command -> when (kind.command) {
                BarCommand.TOOLS -> showToolsLauncher = true
                BarCommand.LASSO -> {
                    navigateTo("map")
                    LassoSelectionService.shared.requestActivate()
                }
                BarCommand.ENGINE_TOGGLE -> {
                    // Cycle 2D Flat → 3D Terrain → 3D Globe → 2D Flat.
                    scope.launch {
                        app.userPrefsStore.update {
                            when {
                                it.cesiumGlobeEnabled -> it.copy(cesiumGlobeEnabled = false, map3dEnabled = false)
                                it.map3dEnabled -> it.copy(map3dEnabled = false, cesiumGlobeEnabled = true)
                                else -> it.copy(map3dEnabled = true, cesiumGlobeEnabled = false)
                            }
                        }
                    }
                }
            }
        }
    }

    val barItems = ToolbarCatalog.resolve(workingIds)
    val coachmarkVisible = !prefs.toolbarCoachmarkSeen && currentRoute == "map" && !editing

    Scaffold(
        bottomBar = {
            CustomToolbar(
                items = barItems,
                currentRoute = currentRoute,
                editing = editing,
                coachmarkVisible = coachmarkVisible,
                canAdd = workingIds.size < ToolbarCatalog.MAX_ITEMS,
                canRemove = workingIds.size > ToolbarCatalog.MIN_ITEMS,
                onSelect = { dispatch(it) },
                onEnterEdit = { enterEdit() },
                onDoneEdit = {
                    editing = false
                    persistToolbar()
                },
                onRemove = { item ->
                    val next = workingIds.toMutableList().apply { remove(item.id) }
                    if (next.size >= ToolbarCatalog.MIN_ITEMS && ToolbarCatalog.hasDestination(next)) {
                        workingIds.clear()
                        workingIds.addAll(next)
                    }
                },
                onReorder = { from, to ->
                    if (from in workingIds.indices && to in workingIds.indices && from != to) {
                        workingIds.add(to, workingIds.removeAt(from))
                    }
                },
                onAddTapped = { showAddPalette = true },
                onDismissCoachmark = {
                    scope.launch { app.userPrefsStore.setToolbarCoachmarkSeen(true) }
                },
            )
        },
    ) { inner: PaddingValues ->
        NavHost(
            navController = nav,
            startDestination = "map",
            modifier = Modifier.padding(inner),
        ) {
            composable("map") {
                MapScreen(onOpenTab = { route -> navigateTo(route) })
            }
            composable("servers") {
                ServersScreen(
                    onAdd = { nav.navigate("servers/add") },
                    onQuickConnect = { nav.navigate("servers/enroll") },
                    onScanQr = { nav.navigate("servers/scan") },
                )
            }
            composable("servers/add") {
                AddServerScreen(onDone = { nav.popBackStack() })
            }
            composable("servers/enroll") {
                EnrollServerScreen(onDone = { nav.popBackStack() })
            }
            composable("servers/scan") {
                soy.engindearing.omnitak.mobile.ui.screens.ServerEnrollScanRoute(
                    onDone = { nav.popBackStack() },
                )
            }
            composable("uas") {
                UASScreen(onDone = { nav.popBackStack() })
            }
            composable("onvif") {
                soy.engindearing.omnitak.mobile.ui.screens.OnvifCameraScreen(onDone = { nav.popBackStack() })
            }
            composable("chat") { ChatScreen() }
            composable(
                route = "chat?convoId={convoId}",
                arguments = listOf(
                    androidx.navigation.navArgument("convoId") {
                        type = androidx.navigation.NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { backStackEntry ->
                ChatScreen(initialConversationId = backStackEntry.arguments?.getString("convoId"))
            }
            composable("mesh") {
                MeshtasticScreen(
                    onOpenTopology = { nav.navigate("mesh_topology") },
                    onOpenDeviceSettings = { nav.navigate("mesh/device-settings") },
                    onOpenChat = { convoId ->
                        nav.navigate("chat?convoId=$convoId") {
                            popUpTo(nav.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable("mesh_topology") {
                MeshTopologyScreen(onBack = { nav.popBackStack() })
            }
            composable("mesh/device-settings") {
                MeshDeviceSettingsScreen(onDone = { nav.popBackStack() })
            }
            composable("missionsync") {
                soy.engindearing.omnitak.mobile.ui.screens.MissionSyncScreen(onBack = { nav.popBackStack() })
            }
            composable("settings") {
                SettingsScreen(
                    onOpenAbout = { nav.navigate("about") },
                    onOpenPlugins = { pluginId -> nav.navigate("settings/plugin/$pluginId") },
                    onOpenPluginsList = { nav.navigate("plugins") },
                    onOpenProfiles = { nav.navigate("settings/profiles") },
                )
            }
            composable("settings/profiles") {
                ProfilesScreen(onBack = { nav.popBackStack() })
            }
            composable("about") { AboutScreen() }
            // Top-level Plugins list (reads from PluginRegistry).
            composable("plugins") {
                soy.engindearing.omnitak.mobile.ui.screens.PluginsListScreen(
                    onBack = { nav.popBackStack() },
                    onOpenPlugin = { pluginId -> nav.navigate("settings/plugin/$pluginId") },
                )
            }
            // Per-plugin detail page (enable toggle + settingsContent), reached
            // from the Settings Plugins row or the Plugins list.
            composable(
                route = "settings/plugin/{pluginId}",
                arguments = listOf(
                    androidx.navigation.navArgument("pluginId") {
                        type = androidx.navigation.NavType.StringType
                    },
                ),
            ) { backStackEntry ->
                soy.engindearing.omnitak.mobile.ui.screens.PluginDetailScreen(
                    pluginId = backStackEntry.arguments?.getString("pluginId").orEmpty(),
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }

    // Tools popup.
    if (showToolsLauncher) {
        ToolsLauncherSheet(
            onDismiss = { showToolsLauncher = false },
            onLasso = {
                showToolsLauncher = false
                navigateTo("map")
                LassoSelectionService.shared.requestActivate()
            },
            onUAS = {
                showToolsLauncher = false
                nav.navigate("uas") {
                    popUpTo(nav.graph.startDestinationId) { saveState = true }
                    launchSingleTop = true
                }
            },
            onOnvifCamera = {
                showToolsLauncher = false
                nav.navigate("onvif") {
                    popUpTo(nav.graph.startDestinationId) { saveState = true }
                    launchSingleTop = true
                }
            },
            onCustomize = {
                showToolsLauncher = false
                navigateTo("map")
                enterEdit()
            },
            onMapOverlays = {
                showToolsLauncher = false
                navigateTo("map")
                showKmlOverlays = true
            },
            onGoToCoordinate = {
                showToolsLauncher = false
                navigateTo("map")
                soy.engindearing.omnitak.mobile.ui.components.CoordinateEntryEvents.requestOpen()
            },
            map3dEnabled = prefs.map3dEnabled,
            cesiumGlobeEnabled = prefs.cesiumGlobeEnabled,
            onToggleTerrain3D = {
                scope.launch {
                    app.userPrefsStore.update {
                        it.copy(map3dEnabled = !it.map3dEnabled, cesiumGlobeEnabled = false)
                    }
                }
            },
            onToggleGlobe = {
                scope.launch {
                    app.userPrefsStore.update { it.copy(cesiumGlobeEnabled = !it.cesiumGlobeEnabled) }
                }
            },
        )
    }

    // Add-a-shortcut palette.
    if (showAddPalette) {
        ToolbarAddPalette(
            available = ToolbarCatalog.all.filter { it.id !in workingIds },
            isFull = workingIds.size >= ToolbarCatalog.MAX_ITEMS,
            onAdd = { item ->
                if (workingIds.size < ToolbarCatalog.MAX_ITEMS && item.id !in workingIds) {
                    workingIds.add(item.id)
                    persistToolbar()
                }
            },
            onReset = {
                workingIds.clear()
                workingIds.addAll(ToolbarCatalog.defaultIds)
                persistToolbar()
                showAddPalette = false
            },
            onDismiss = { showAddPalette = false },
        )
    }

    // Map Overlays (KML/KMZ import + manage).
    if (showKmlOverlays) {
        soy.engindearing.omnitak.mobile.ui.components.KmlOverlaysSheet(
            onDismiss = { showKmlOverlays = false },
        )
    }

    // Deep-link profile import — show the same ImportPreviewDialog the
    // in-app QR scanner uses, so the user can review before applying.
    val pendingImport by app.pendingProfileImport.collectAsState()
    pendingImport?.let { candidate ->
        ImportPreviewDialog(
            profile = candidate,
            onConfirm = {
                app.pendingProfileImport.value = null
                scope.launch {
                    app.configProfileStore.saveProfile(candidate)
                    app.configProfileStore.apply(candidate)
                }
            },
            onDismiss = { app.pendingProfileImport.value = null },
        )
    }
}
