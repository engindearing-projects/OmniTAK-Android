package soy.engindearing.omnitak.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.userPrefsDataStore by preferencesDataStore(name = "user_prefs")

enum class DistanceUnit { METRIC, IMPERIAL }
enum class CoordFormat { LATLON_DECIMAL, LATLON_DMS, MGRS, UTM }
enum class MapProvider { OSM_RASTER, SATELLITE_HINT, TOPO_HINT, WMTS_CUSTOM }

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
    // Persistent EUD identifier broadcast in every PPLI CoT event. Empty
    // until the first connection generates one (`ANDROID-<uuid>`); after
    // that it never changes so TAK servers see a stable contact across
    // restarts. iOS uses the same convention with an `IOS-` prefix.
    val selfUid: String = "",
    // Self-position used for PPLI broadcast. NaN sentinels mean "no fix
    // yet" — GAP-030b wires real GPS via FusedLocationProviderClient
    // (LocationProvider) and the broadcaster suppresses PPLI until a
    // real fix arrives, fixing issue #10 (HUD showing San Francisco).
    val selfLat: Double = Double.NaN,
    val selfLon: Double = Double.NaN,
    val distanceUnit: DistanceUnit = DistanceUnit.METRIC,
    val coordFormat: CoordFormat = CoordFormat.LATLON_DECIMAL,
    // Issue #17 — first-run users see OpenTopoMap (terrain shading + contour
    // lines). Existing operators keep whatever they last picked because the
    // DataStore read returns their persisted value before this default fires.
    val mapProvider: MapProvider = MapProvider.TOPO_HINT,
    // GAP-107 — XYZ tile URL template the operator pasted in. Used when
    // mapProvider == WMTS_CUSTOM. Format: https://host/{z}/{x}/{y}.png
    val customTileUrl: String = "",
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
    // GAP-108 — server-driven config. When set, the app fetches this URL
    // on every launch (and on demand from Settings → "Refresh config now").
    // Response can be either a TAK data-package .zip (server creds + prefs
    // + certs) or a plain-text body containing one or more
    // `tak://com.atakmap.app/preference?…` URLs separated by newlines.
    // Pattern: operator publishes one team config URL via OTS onboarding
    // portal; every EUD picks up changes without re-scanning.
    val configBundleUrl: String = "",
    // PLI broadcast interval for the app's self-position CoT, in seconds.
    // Critical knob for large-mesh deployments — the operator pushes a
    // longer interval (60–300s) when running 50+ EUDs so the mesh isn't
    // saturated by self-position chatter. Pushable via tak://preference
    // (key `pliIntervalSecs` or the ATAK aliases `dispatchLocationCotInterval`
    // / `locationReportingInterval`) so an OpenTAKserver onboarding
    // portal can set it for the whole team via configBundleUrl.
    val pliIntervalSecs: Int = 30,
    // When true (default), the MeshtasticCoTBridge suppresses any mesh
    // node whose callsign matches our own — solves the "see yourself
    // twice" duplication when running TAK_TRACKER role on the mesh side
    // alongside the OmniTAK client publishing its own PPLI.
    val hideSelfFromMeshContacts: Boolean = true,
    // When true (default), whenever the operator's TAK callsign changes
    // and a Meshtastic transport is connected, push the new callsign to
    // the radio as the owner long/short name (set_owner AdminMessage).
    // This is the "unified identity" half of mata's TAK_TRACKER ask —
    // the mesh node and the TAK client wear the same callsign so the
    // self-dedup is guaranteed to match, no manual sync.
    val autoSyncCallsignToMesh: Boolean = true,
)

class UserPrefsStore(private val context: Context) {
    private val KEY_CALLSIGN = stringPreferencesKey("callsign")
    private val KEY_TEAM = stringPreferencesKey("team")
    private val KEY_SELF_UID = stringPreferencesKey("self_uid")
    private val KEY_SELF_LAT = stringPreferencesKey("self_lat")
    private val KEY_SELF_LON = stringPreferencesKey("self_lon")
    private val KEY_DIST = stringPreferencesKey("distance_unit")
    private val KEY_COORD = stringPreferencesKey("coord_format")
    private val KEY_MAP = stringPreferencesKey("map_provider")
    private val KEY_CUSTOM_TILE_URL = stringPreferencesKey("custom_tile_url")
    private val KEY_AUTO_PUBLISH_MESH = booleanPreferencesKey("auto_publish_mesh_to_tak")
    private val KEY_MESH_LAYER_VISIBLE = booleanPreferencesKey("mesh_nodes_layer_visible")
    // GAP-110 keys
    private val KEY_CALLSIGN_CARD = booleanPreferencesKey("callsign_card_visible")
    private val KEY_GRID = booleanPreferencesKey("grid_enabled")
    private val KEY_DRAWINGS_VIS = booleanPreferencesKey("drawings_visible")
    private val KEY_AIRCRAFT_VIS = booleanPreferencesKey("aircraft_visible")
    private val KEY_CONTACTS_VIS = booleanPreferencesKey("contacts_visible")
    private val KEY_FOLLOW_ME = booleanPreferencesKey("follow_me_active")
    private val KEY_CONFIG_BUNDLE_URL = stringPreferencesKey("config_bundle_url")
    private val KEY_PLI_INTERVAL_SECS = intPreferencesKey("pli_interval_secs")
    private val KEY_HIDE_SELF_FROM_MESH = booleanPreferencesKey("hide_self_from_mesh_contacts")
    private val KEY_AUTO_SYNC_CALLSIGN_TO_MESH = booleanPreferencesKey("auto_sync_callsign_to_mesh")

    val prefs: Flow<UserPrefs> = context.userPrefsDataStore.data.map { p -> readFrom(p) }

    suspend fun update(block: (UserPrefs) -> UserPrefs) {
        context.userPrefsDataStore.edit { p ->
            val next = block(readFrom(p))
            p[KEY_CALLSIGN] = next.callsign
            p[KEY_TEAM] = next.team
            p[KEY_SELF_UID] = next.selfUid
            p[KEY_SELF_LAT] = next.selfLat.toString()
            p[KEY_SELF_LON] = next.selfLon.toString()
            p[KEY_DIST] = next.distanceUnit.name
            p[KEY_COORD] = next.coordFormat.name
            p[KEY_MAP] = next.mapProvider.name
            p[KEY_CUSTOM_TILE_URL] = next.customTileUrl
            p[KEY_AUTO_PUBLISH_MESH] = next.autoPublishMeshToTak
            p[KEY_MESH_LAYER_VISIBLE] = next.meshNodesLayerVisible
            p[KEY_CALLSIGN_CARD] = next.callsignCardVisible
            p[KEY_GRID] = next.gridEnabled
            p[KEY_DRAWINGS_VIS] = next.drawingsVisible
            p[KEY_AIRCRAFT_VIS] = next.aircraftVisible
            p[KEY_CONTACTS_VIS] = next.contactsVisible
            p[KEY_FOLLOW_ME] = next.followMeActive
            p[KEY_CONFIG_BUNDLE_URL] = next.configBundleUrl
            p[KEY_PLI_INTERVAL_SECS] = next.pliIntervalSecs
            p[KEY_HIDE_SELF_FROM_MESH] = next.hideSelfFromMeshContacts
            p[KEY_AUTO_SYNC_CALLSIGN_TO_MESH] = next.autoSyncCallsignToMesh
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
        selfUid = p[KEY_SELF_UID] ?: "",
        selfLat = p[KEY_SELF_LAT]?.toDoubleOrNull() ?: Double.NaN,
        selfLon = p[KEY_SELF_LON]?.toDoubleOrNull() ?: Double.NaN,
        distanceUnit = p[KEY_DIST]?.let { runCatching { DistanceUnit.valueOf(it) }.getOrNull() }
            ?: DistanceUnit.METRIC,
        coordFormat = p[KEY_COORD]?.let { runCatching { CoordFormat.valueOf(it) }.getOrNull() }
            ?: CoordFormat.LATLON_DECIMAL,
        mapProvider = p[KEY_MAP]?.let { runCatching { MapProvider.valueOf(it) }.getOrNull() }
            ?: MapProvider.TOPO_HINT,
        customTileUrl = p[KEY_CUSTOM_TILE_URL] ?: "",
        autoPublishMeshToTak = p[KEY_AUTO_PUBLISH_MESH] ?: true,
        meshNodesLayerVisible = p[KEY_MESH_LAYER_VISIBLE] ?: true,
        callsignCardVisible = p[KEY_CALLSIGN_CARD] ?: true,
        gridEnabled = p[KEY_GRID] ?: false,
        drawingsVisible = p[KEY_DRAWINGS_VIS] ?: true,
        aircraftVisible = p[KEY_AIRCRAFT_VIS] ?: true,
        contactsVisible = p[KEY_CONTACTS_VIS] ?: true,
        followMeActive = p[KEY_FOLLOW_ME] ?: false,
        configBundleUrl = p[KEY_CONFIG_BUNDLE_URL] ?: "",
        pliIntervalSecs = (p[KEY_PLI_INTERVAL_SECS] ?: 30).coerceIn(5, 600),
        hideSelfFromMeshContacts = p[KEY_HIDE_SELF_FROM_MESH] ?: true,
        autoSyncCallsignToMesh = p[KEY_AUTO_SYNC_CALLSIGN_TO_MESH] ?: true,
    )
}
