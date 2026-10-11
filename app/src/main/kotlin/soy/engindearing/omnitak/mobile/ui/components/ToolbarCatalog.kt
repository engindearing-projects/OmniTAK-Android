package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import soy.engindearing.omnitak.mobile.domain.OneShotRequest

/**
 * User-customizable bottom toolbar — catalog + rules. Mirrors the iOS
 * `ToolbarCustomization.swift`. Operators long-press the bar to enter
 * edit mode and pick which shortcuts appear and in what order. Every
 * catalog entry routes to a REAL feature (a navigable destination or a
 * working command), so nothing here is a placeholder.
 */

/** What a non-destination shortcut does. */
enum class BarCommand {
    /** Open the Tools popup (ToolsLauncherSheet). */
    TOOLS,

    /** Start freehand lasso multi-select on the map. */
    LASSO,

    /** Toggle the 3D terrain map mode (map3dEnabled pref). */
    ENGINE_TOGGLE,
}

/** A bar entry is either a NavHost destination or a command. */
sealed interface BarKind {
    data class Destination(val route: String) : BarKind
    data class Command(val command: BarCommand) : BarKind
}

/**
 * One selectable shortcut. [id] is the stable key persisted in the
 * operator's config; [ToolbarCatalog.item] reconstructs the full entry.
 */
data class BarItem(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val tint: Color,
    val kind: BarKind,
)

object ToolbarCatalog {
    /** Visual cap for the floating pill — beyond this glyphs get too
     *  cramped to tap. The rest live in the add palette / Tools popup. */
    const val MAX_ITEMS = 6

    /** Never drop below this; there must always be a way around the app. */
    const val MIN_ITEMS = 2

    // Brand tints mirror the iOS bar + the original NavTabs.
    private val cMap = Color(0xFF4FA8FF)
    private val cChat = Color(0xFF34C759)
    private val cServers = Color(0xFF5AC8FA)
    private val cMesh = Color(0xFFFF9F0A)
    private val cTools = Color(0xFFFFCC00)
    private val cSettings = Color(0xFF8E8E93)
    private val cCamera = Color(0xFF00E5FF)
    private val cLasso = Color(0xFFFF9500)

    /** NavHost destinations — switch the visible screen. */
    val destinations: List<BarItem> = listOf(
        BarItem("map", "Map", Icons.Filled.Map, cMap, BarKind.Destination("map")),
        BarItem("chat", "Chat", Icons.AutoMirrored.Filled.Chat, cChat, BarKind.Destination("chat")),
        BarItem("servers", "Servers", Icons.Filled.Storage, cServers, BarKind.Destination("servers")),
        BarItem("mesh", "Mesh", Icons.Filled.Router, cMesh, BarKind.Destination("mesh")),
        BarItem("settings", "Settings", Icons.Filled.Settings, cSettings, BarKind.Destination("settings")),
        BarItem("uas", "Vehicles", Icons.Filled.FlightTakeoff, cChat, BarKind.Destination("uas")),
        BarItem("onvif", "Camera", Icons.Filled.Videocam, cCamera, BarKind.Destination("onvif")),
    )

    /** Commands — open the Tools popup, start lasso, or flip 2D/3D. */
    val commands: List<BarItem> = listOf(
        BarItem("tools", "Tools", Icons.Filled.Handyman, cTools, BarKind.Command(BarCommand.TOOLS)),
        BarItem("lasso", "Select", Icons.Filled.Gesture, cLasso, BarKind.Command(BarCommand.LASSO)),
        BarItem("engine", "2D / 3D", Icons.Filled.Public, cMap, BarKind.Command(BarCommand.ENGINE_TOGGLE)),
    )

    val all: List<BarItem> = destinations + commands

    /** Default layout — matches the original hardcoded NavTabs so existing
     *  users see no change until they customize. */
    val defaultIds: List<String> = listOf("map", "chat", "servers", "mesh", "tools", "settings")

    fun item(id: String): BarItem? = all.firstOrNull { it.id == id }

    /** Resolve a stored id list to render-ready items, dropping any ids that
     *  no longer exist in the catalog (e.g. removed in an update). Falls back
     *  to the default layout when the result is empty. */
    fun resolve(ids: List<String>): List<BarItem> {
        val resolved = ids.mapNotNull { item(it) }
        return resolved.ifEmpty { defaultIds.mapNotNull { item(it) } }
    }

    fun hasDestination(ids: List<String>): Boolean =
        ids.any { item(it)?.kind is BarKind.Destination }
}

/**
 * Lets the Settings screen / Tools popup ask the bar (hosted in AppNav) to
 * enter edit mode. AppNav acts on [edit] through `OnRequest`. A request waits
 * if AppNav is not composed yet, and is gone once acted on, so an Activity
 * rebuilt later (dark mode switched, font size changed) does not put the bar
 * back into edit mode by itself.
 */
object ToolbarEditBus {
    val edit = OneShotRequest()

    fun requestEdit() = edit.request()
}

/**
 * Lets the Tools popup (hosted in AppNav) ask the map (hosted in MapScreen)
 * to open the "Go to Coordinate" sheet. MapScreen acts on [open] through
 * `OnRequest`. A request waits when AppNav navigates to the map and asks in
 * the same frame (the map is composed after the ask), and is gone once acted
 * on, so coming back to the Map tab later does not open the sheet again (#286).
 */
object CoordinateEntryEvents {
    val open = OneShotRequest()

    fun requestOpen() = open.request()
}
