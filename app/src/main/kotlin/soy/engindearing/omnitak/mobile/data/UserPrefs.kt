package soy.engindearing.omnitak.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.userPrefsDataStore by preferencesDataStore(name = "user_prefs")

enum class DistanceUnit { METRIC, IMPERIAL }
enum class CoordFormat { LATLON_DECIMAL, LATLON_DMS, MGRS }
enum class MapProvider { OSM_RASTER, SATELLITE_HINT, TOPO_HINT }

/**
 * Operator preferences — callsign, units, coord format, tile choice.
 * All string-backed in DataStore so the schema stays trivial; enum
 * cases round-trip by name.
 *
 * Phase 3 added two booleans for Meshtastic UX parity:
 *  - [autoPublishMeshToTak] — controls whether the
 *    [soy.engindearing.omnitak.mobile.domain.MeshtasticCoTBridge] pushes
 *    decoded mesh nodes into the active CoT pipeline. Defaults to on so
 *    operators get the same behaviour they had pre-toggle. Mirrors the
 *    iOS "Auto Map Updates" toolbar toggle.
 *  - [meshNodesLayerVisible] — layers-dialog visibility flag for
 *    mesh-origin contacts on the tactical map. When false, the map
 *    filters out any contact whose UID starts with `MESHTASTIC-`.
 */
data class UserPrefs(
    val callsign: String = "OMNI-1",
    val team: String = "CYAN",
    val distanceUnit: DistanceUnit = DistanceUnit.METRIC,
    val coordFormat: CoordFormat = CoordFormat.LATLON_DECIMAL,
    val mapProvider: MapProvider = MapProvider.OSM_RASTER,
    val autoPublishMeshToTak: Boolean = true,
    val meshNodesLayerVisible: Boolean = true,
    // GAP-110 — persisted UI toggles. Each one mirrors a switch the operator
    // hits via the long-press radial menu / Layers sheet / map controls. Used
    // to evaporate on relaunch (`var X by remember { mutableStateOf(...) }`)
    // which made the picks feel meaningless.
    val callsignCardVisible: Boolean = true,
    val gridEnabled: Boolean = false,
    val drawingsVisible: Boolean = true,
    val aircraftVisible: Boolean = true,
    val contactsVisible: Boolean = true,
    val followMeActive: Boolean = false,
)

class UserPrefsStore(private val context: Context) {
    private val KEY_CALLSIGN = stringPreferencesKey("callsign")
    private val KEY_TEAM = stringPreferencesKey("team")
    private val KEY_DIST = stringPreferencesKey("distance_unit")
    private val KEY_COORD = stringPreferencesKey("coord_format")
    private val KEY_MAP = stringPreferencesKey("map_provider")
    private val KEY_AUTO_PUBLISH_MESH = booleanPreferencesKey("auto_publish_mesh_to_tak")
    private val KEY_MESH_LAYER_VISIBLE = booleanPreferencesKey("mesh_nodes_layer_visible")
    // GAP-110 keys
    private val KEY_CALLSIGN_CARD = booleanPreferencesKey("callsign_card_visible")
    private val KEY_GRID = booleanPreferencesKey("grid_enabled")
    private val KEY_DRAWINGS_VIS = booleanPreferencesKey("drawings_visible")
    private val KEY_AIRCRAFT_VIS = booleanPreferencesKey("aircraft_visible")
    private val KEY_CONTACTS_VIS = booleanPreferencesKey("contacts_visible")
    private val KEY_FOLLOW_ME = booleanPreferencesKey("follow_me_active")

    val prefs: Flow<UserPrefs> = context.userPrefsDataStore.data.map { p -> readFrom(p) }

    suspend fun update(block: (UserPrefs) -> UserPrefs) {
        context.userPrefsDataStore.edit { p ->
            val next = block(readFrom(p))
            p[KEY_CALLSIGN] = next.callsign
            p[KEY_TEAM] = next.team
            p[KEY_DIST] = next.distanceUnit.name
            p[KEY_COORD] = next.coordFormat.name
            p[KEY_MAP] = next.mapProvider.name
            p[KEY_AUTO_PUBLISH_MESH] = next.autoPublishMeshToTak
            p[KEY_MESH_LAYER_VISIBLE] = next.meshNodesLayerVisible
            p[KEY_CALLSIGN_CARD] = next.callsignCardVisible
            p[KEY_GRID] = next.gridEnabled
            p[KEY_DRAWINGS_VIS] = next.drawingsVisible
            p[KEY_AIRCRAFT_VIS] = next.aircraftVisible
            p[KEY_CONTACTS_VIS] = next.contactsVisible
            p[KEY_FOLLOW_ME] = next.followMeActive
        }
    }

    /** Convenience writer for the Meshtastic auto-publish toggle so the
     *  overflow menu doesn't have to reach for [update]. */
    suspend fun setAutoPublishMeshToTak(value: Boolean) {
        update { it.copy(autoPublishMeshToTak = value) }
    }

    /** Convenience writer for the layers-dialog mesh visibility toggle. */
    suspend fun setMeshNodesLayerVisible(value: Boolean) {
        update { it.copy(meshNodesLayerVisible = value) }
    }

    private fun readFrom(p: androidx.datastore.preferences.core.Preferences): UserPrefs = UserPrefs(
        callsign = p[KEY_CALLSIGN] ?: "OMNI-1",
        team = p[KEY_TEAM] ?: "CYAN",
        distanceUnit = p[KEY_DIST]?.let { runCatching { DistanceUnit.valueOf(it) }.getOrNull() }
            ?: DistanceUnit.METRIC,
        coordFormat = p[KEY_COORD]?.let { runCatching { CoordFormat.valueOf(it) }.getOrNull() }
            ?: CoordFormat.LATLON_DECIMAL,
        mapProvider = p[KEY_MAP]?.let { runCatching { MapProvider.valueOf(it) }.getOrNull() }
            ?: MapProvider.OSM_RASTER,
        autoPublishMeshToTak = p[KEY_AUTO_PUBLISH_MESH] ?: true,
        meshNodesLayerVisible = p[KEY_MESH_LAYER_VISIBLE] ?: true,
        callsignCardVisible = p[KEY_CALLSIGN_CARD] ?: true,
        gridEnabled = p[KEY_GRID] ?: false,
        drawingsVisible = p[KEY_DRAWINGS_VIS] ?: true,
        aircraftVisible = p[KEY_AIRCRAFT_VIS] ?: true,
        contactsVisible = p[KEY_CONTACTS_VIS] ?: true,
        followMeActive = p[KEY_FOLLOW_ME] ?: false,
    )
}
