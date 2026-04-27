package soy.engindearing.omnitak.mobile.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import soy.engindearing.omnitak.mobile.ui.components.LiquidGlassNavBar
import soy.engindearing.omnitak.mobile.ui.components.NavTabSpec
import soy.engindearing.omnitak.mobile.ui.screens.AboutScreen
import soy.engindearing.omnitak.mobile.ui.screens.AddServerScreen
import soy.engindearing.omnitak.mobile.ui.screens.ChatScreen
import soy.engindearing.omnitak.mobile.ui.screens.MapScreen
import soy.engindearing.omnitak.mobile.ui.screens.MeshTopologyScreen
import soy.engindearing.omnitak.mobile.ui.screens.MeshtasticScreen
import soy.engindearing.omnitak.mobile.ui.screens.ServersScreen
import soy.engindearing.omnitak.mobile.ui.screens.SettingsScreen

// Per-tab brand colors mirror the iOS bottom bar — each glyph carries
// its own tint instead of all five icons being the same accent yellow.
private val NavTabs = listOf(
    NavTabSpec("map", "Map", Icons.Filled.Map, Color(0xFF4FA8FF)),
    NavTabSpec("chat", "Chat", Icons.AutoMirrored.Filled.Chat, Color(0xFF34C759)),
    NavTabSpec("servers", "Servers", Icons.Filled.Storage, Color(0xFF5AC8FA)),
    NavTabSpec("mesh", "Mesh", Icons.Filled.Router, Color(0xFFFF9F0A)),
    NavTabSpec("settings", "Settings", Icons.Filled.Settings, Color(0xFF8E8E93)),
)

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            LiquidGlassNavBar(
                tabs = NavTabs,
                currentRoute = currentRoute,
                onSelect = { tab ->
                    nav.navigate(tab.route) {
                        popUpTo(nav.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
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
                ServersScreen(onAdd = { nav.navigate("servers/add") })
            }
            composable("servers/add") {
                AddServerScreen(onDone = { nav.popBackStack() })
            }
            composable("chat") { ChatScreen() }
            composable("mesh") {
                MeshtasticScreen(
                    onOpenTopology = { nav.navigate("mesh_topology") },
                )
            }
            composable("mesh_topology") {
                MeshTopologyScreen(onBack = { nav.popBackStack() })
            }
            composable("settings") { SettingsScreen() }
            composable("about") { AboutScreen() }
        }
    }
}
