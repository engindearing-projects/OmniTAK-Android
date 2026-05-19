package soy.engindearing.omnitak.mobile.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import soy.engindearing.omnitak.mobile.domain.LassoSelectionService
import soy.engindearing.omnitak.mobile.ui.components.LiquidGlassNavBar
import soy.engindearing.omnitak.mobile.ui.components.NavTabSpec
import soy.engindearing.omnitak.mobile.ui.components.ToolsLauncherSheet
import soy.engindearing.omnitak.mobile.ui.screens.AboutScreen
import soy.engindearing.omnitak.mobile.ui.screens.AddServerScreen
import soy.engindearing.omnitak.mobile.ui.screens.ChatScreen
import soy.engindearing.omnitak.mobile.ui.screens.EnrollServerScreen
import soy.engindearing.omnitak.mobile.ui.screens.MapScreen
import soy.engindearing.omnitak.mobile.ui.screens.MeshDeviceSettingsScreen
import soy.engindearing.omnitak.mobile.ui.screens.MeshTopologyScreen
import soy.engindearing.omnitak.mobile.ui.screens.MeshtasticScreen
import soy.engindearing.omnitak.mobile.ui.screens.ServersScreen
import soy.engindearing.omnitak.mobile.ui.screens.SettingsScreen

// Per-tab brand colors mirror the iOS bottom bar — each glyph carries
// its own tint instead of all five icons being the same accent yellow.
// "tools" is a command tab — selecting it opens the ToolsLauncherSheet
// popup instead of navigating to its own destination.
private val NavTabs = listOf(
    NavTabSpec("map", "Map", Icons.Filled.Map, Color(0xFF4FA8FF)),
    NavTabSpec("chat", "Chat", Icons.AutoMirrored.Filled.Chat, Color(0xFF34C759)),
    NavTabSpec("servers", "Servers", Icons.Filled.Storage, Color(0xFF5AC8FA)),
    NavTabSpec("mesh", "Mesh", Icons.Filled.Router, Color(0xFFFF9F0A)),
    NavTabSpec("tools", "Tools", Icons.Filled.Handyman, Color(0xFFFFCC00)),
    NavTabSpec("settings", "Settings", Icons.Filled.Settings, Color(0xFF8E8E93)),
)

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    // Tools tab is a command, not a destination — selecting it opens
    // a Material 3 ModalBottomSheet popup instead of navigating. The
    // visible nav highlight stays on whichever real tab the user was
    // on, so the popup feels like a transient overlay.
    var showToolsLauncher by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()

    Scaffold(
        bottomBar = {
            LiquidGlassNavBar(
                tabs = NavTabs,
                currentRoute = currentRoute,
                onSelect = { tab ->
                    if (tab.route == "tools") {
                        showToolsLauncher = true
                    } else {
                        nav.navigate(tab.route) {
                            popUpTo(nav.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner: PaddingValues ->
        NavHost(
            navController = nav,
            startDestination = "map",
            modifier = Modifier.padding(inner),
        ) {
            composable("map") {
                MapScreen(
                    onOpenTab = { route ->
                        nav.navigate(route) {
                            popUpTo(nav.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable("servers") {
                ServersScreen(
                    onAdd = { nav.navigate("servers/add") },
                    onQuickConnect = { nav.navigate("servers/enroll") },
                )
            }
            composable("servers/add") {
                AddServerScreen(onDone = { nav.popBackStack() })
            }
            composable("servers/enroll") {
                EnrollServerScreen(onDone = { nav.popBackStack() })
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
                        // GAP-124 — jump straight into the chat tab with
                        // the requested conversation pre-selected.
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
            composable("settings") { SettingsScreen() }
            composable("about") { AboutScreen() }
        }
    }

    // Tools popup. Lasso routes to Map and toggles the LassoSelectionService
    // (wired in subsequent phases). Full Tools surfaces a snackbar for now
    // until we port the iOS ATAKToolsView grid.
    if (showToolsLauncher) {
        ToolsLauncherSheet(
            onDismiss = { showToolsLauncher = false },
            onLasso = {
                showToolsLauncher = false
                // Make sure the user is on the map before activating
                // lasso — otherwise the gesture has no canvas.
                nav.navigate("map") {
                    popUpTo(nav.graph.startDestinationId) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
                // Fire the activation event — MapScreen's collector
                // flips its lassoMode flag and the freehand overlay
                // takes over the pointer input.
                LassoSelectionService.shared.requestActivate()
            },
            onFullTools = {
                showToolsLauncher = false
                snackbarScope.launch {
                    snackbarHostState.showSnackbar("Full Tools — porting the iOS grid next")
                }
            },
        )
    }
}
