package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
        BarItem("peermesh", "Peer Mesh", Icons.Filled.Hub, cMesh, BarKind.Destination("peermesh")),
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
 * enter edit mode. Mirrors the LassoSelectionService activation-counter
 * pattern so AppNav can collect it via collectAsState without a SharedFlow
 * subscribe-timing race.
 */
object ToolbarEditBus {
    private val _editGeneration = MutableStateFlow(0L)
    val editGeneration: StateFlow<Long> = _editGeneration.asStateFlow()

    fun requestEdit() {
        _editGeneration.value = _editGeneration.value + 1
    }
}

/**
 * Lets the Tools popup (hosted in AppNav) ask the map (hosted in MapScreen)
 * to open the "Go to Coordinate" sheet. Same activation-counter pattern as
 * [ToolbarEditBus] — MapScreen collects via collectAsState, sidestepping the
 * SharedFlow subscribe-timing race when AppNav navigates to map and requests
 * the sheet in the same frame.
 */
object CoordinateEntryEvents {
    private val _openGeneration = MutableStateFlow(0L)
    val openGeneration: StateFlow<Long> = _openGeneration.asStateFlow()

    fun requestOpen() {
        _openGeneration.value = _openGeneration.value + 1
    }
}
