package soy.engindearing.omnitak.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.userPrefsDataStore by preferencesDataStore(name = "user_prefs")

enum class DistanceUnit { METRIC, IMPERIAL }
enum class CoordFormat { LATLON_DECIMAL, LATLON_DMS, MGRS, UTM, TWD97, BNG }
enum class MapProvider { OSM_RASTER, SATELLITE_HINT, TOPO_HINT, WMTS_CUSTOM }

/** Mesh framework the operator's radio speaks. Drives which manager the
 *  mesh screen + CoT bridge + broadcaster route through. */
enum class MeshFramework { MESHTASTIC, MESHCORE }

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
    val team: String = "Cyan",
    // #159 — operator's echelon / org size (Echelon.code, or "" = none). The
    // APP-6 amplifier draws above the self symbol on the map.
    val echelon: String = "",
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
    // Issue #75 — the rest of the persisted self-fix. Together with
    // selfLat/selfLon this lets the self-marker render immediately on
    // cold start (stale-marked when the fix is old) instead of
    // disappearing until GPS reacquires. Written (throttled) by the
    // OmniTAKApp fix collector; restored via SelfFixPersistence.
    val selfHae: Double = Double.NaN,
    val selfFixTimeMs: Long = 0L,
    val distanceUnit: DistanceUnit = DistanceUnit.METRIC,
    val coordFormat: CoordFormat = CoordFormat.LATLON_DECIMAL,
    val mapProvider: MapProvider = MapProvider.TOPO_HINT,
    // GAP-107 — XYZ tile URL template the operator pasted in. Used when
    // mapProvider == WMTS_CUSTOM. Format: https://host/{z}/{x}/{y}.png
    val customTileUrl: String = "",
    val autoPublishMeshToTak: Boolean = true,
    val meshNodesLayerVisible: Boolean = true,
    // Field feedback (PatoG, 2026-08) — a radio in Meshtastic role TAK is
    // paired to a phone that broadcasts its own PLI, so its node marker is a
    // second, slightly-off dot for the same person. Hidden by default;
    // standalone trackers (TAK_TRACKER / sensors / vehicles) stay visible
    // under the main mesh toggle.
    val meshPairedNodesVisible: Boolean = false,
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
    /** Render self-position as a MIL-STD-2525 friendly-combat frame.
     *  When false, falls back to the legacy `ic_self_marker` tinted disc.
     *  Default true so the operator's own pip reads as part of the same
     *  tactical iconography as friendly contact markers. */
    val useMilStdSelfSymbol: Boolean = true,
    /** Run the FAA Remote ID BLE scanner. When on, drones broadcasting
     *  OpenDroneID (DJI Mavic family, Skydio, Autel, etc.) within range
     *  appear on the map as unknown-air UAS contacts. Default off — opt-in
     *  because BLE scanning has a battery cost. */
    val remoteIdScanEnabled: Boolean = false,
    /** External gyb_detect sensor over BLE GATT. Catches WiFi-beacon
     *  Remote ID the phone can't see on its own and streams it over
     *  Bluetooth; detections merge with on-device Remote ID into one
     *  `RID-` marker. Default off (opt-in, pairs with a sensor). */
    val gybDetectorEnabled: Boolean = false,
    /** MAC address of the last gyb sensor the operator connected to.
     *  Empty until the first successful connect. While [gybDetectorEnabled]
     *  is on, GybManager auto-reconnects to this address on app start and
     *  after an unexpected BLE drop (with backoff) — mirrors the iOS
     *  last-device UUID, but gated on the toggle. */
    val gybLastDeviceAddress: String = "",
    /** When true, self-PPLI and GeoChat are also sent over the connected
     *  Meshtastic radio (portnum-72 TAKMessage). Allows two OmniTAK
     *  operators with radios to see each other and chat with NO server. */
    val broadcastOverMesh: Boolean = true,
    /** How often (seconds) PPLI is sent over the mesh. Coerced to 30..60
     *  so LoRa bandwidth is respected. */
    val meshBroadcastIntervalSecs: Int = 30,
    /** Which mesh framework the connected radio speaks. Defaults to
     *  Meshtastic for backwards compatibility — every existing install
     *  runs Meshtastic. Selecting MeshCore switches the mesh screen, CoT
     *  bridge, and broadcaster onto the MeshCore companion BLE path. */
    val selectedMeshFramework: MeshFramework = MeshFramework.MESHTASTIC,
    /** 3D terrain map mode — tilts the camera and renders DEM relief
     *  (AWS Terrarium tiles). Parity with the iOS Cesium 3D globe
     *  toggle. Default off — 2D top-down is the tactical default. */
    val map3dEnabled: Boolean = false,
    /** Photoreal Cesium 3D globe map engine (WebView). Distinct from
     *  [map3dEnabled] (MapLibre terrain tilt): when this is on the map
     *  switches to the Cesium globe, matching the iOS 3D experience.
     *  Map mode is effectively: GLOBE if this is on, else TERRAIN if
     *  [map3dEnabled], else flat 2D. Default off. */
    val cesiumGlobeEnabled: Boolean = false,
    /** Customizable bottom-bar layout — ordered list of ToolbarCatalog
     *  item ids. Empty means "use the default layout". Mirrors the iOS
     *  ToolbarConfigStore. */
    val toolbarItemIds: List<String> = emptyList(),
    /** One-time flag for the "press & hold to customize" coachmark. */
    val toolbarCoachmarkSeen: Boolean = false,
    // Camera persistence — last map view the operator panned/zoomed to.
    // All three must be non-null together to constitute a valid saved view.
    // Null on first install (fresh device → self-fix or global fallback).
    val lastCameraLat: Double? = null,
    val lastCameraLon: Double? = null,
    val lastCameraZoom: Double? = null,
    /** Issue #95 — lock map bearing to 0° (north-up). While on, rotation
     *  gestures are suppressed and the camera snaps back to north. Persisted
     *  so field operators who always run north-up don't have to re-enable. */
    val northUpLocked: Boolean = false,
    /** Issue #97 — prevent the screen from sleeping while OmniTAK is
     *  foreground. Fixes the bug where the setting was present but
     *  FLAG_KEEP_SCREEN_ON was never actually applied. Default off. */
    val keepScreenOn: Boolean = false,
    /** When true, render the self-marker as a triangle pointing in heading
     *  direction instead of the MIL-STD disc / puck. Rotates with compass. */
    val selfMarkerTriangle: Boolean = false,
    /** #178 — when true, contact pins on the map carry an age label and fade as
     *  the point goes stale (fresh <1m full opacity, aging 1–5m, stale >5m). Off
     *  by default — opt-in so the default map look is unchanged. */
    val stalenessOverlayEnabled: Boolean = false,
    /** #179 — relay/gateway mode. When on AND the device is connected to BOTH a
     *  TAK server and a mesh, CoT is bridged both ways: mesh-only nodes appear
     *  server-side and server contacts reach the mesh (ATAK Meshtastic-gateway
     *  parity). DANGEROUS on a busy server (LoRa airtime), so OFF by default and
     *  hard-throttled server→mesh. See [MeshServerRelay]. */
    val relayGatewayEnabled: Boolean = false,
)

class UserPrefsStore(private val context: Context) {
    private val KEY_CALLSIGN = stringPreferencesKey("callsign")
    private val KEY_TEAM = stringPreferencesKey("team")
    private val KEY_ECHELON = stringPreferencesKey("echelon")
    private val KEY_SELF_UID = stringPreferencesKey("self_uid")
    private val KEY_SELF_LAT = stringPreferencesKey("self_lat")
    private val KEY_SELF_LON = stringPreferencesKey("self_lon")
    // Issue #75 — persisted self-fix altitude + fix wall-clock time.
    private val KEY_SELF_HAE = stringPreferencesKey("self_hae")
    private val KEY_SELF_FIX_TIME = longPreferencesKey("self_fix_time_ms")
    private val KEY_DIST = stringPreferencesKey("distance_unit")
    private val KEY_COORD = stringPreferencesKey("coord_format")
    private val KEY_MAP = stringPreferencesKey("map_provider")
    private val KEY_CUSTOM_TILE_URL = stringPreferencesKey("custom_tile_url")
    private val KEY_AUTO_PUBLISH_MESH = booleanPreferencesKey("auto_publish_mesh_to_tak")
    private val KEY_MESH_LAYER_VISIBLE = booleanPreferencesKey("mesh_nodes_layer_visible")
    private val KEY_MESH_PAIRED_VISIBLE = booleanPreferencesKey("mesh_paired_nodes_visible")
    // GAP-110 keys
    private val KEY_CALLSIGN_CARD = booleanPreferencesKey("callsign_card_visible")
    private val KEY_GRID = booleanPreferencesKey("grid_enabled")
    private val KEY_DRAWINGS_VIS = booleanPreferencesKey("drawings_visible")
    private val KEY_AIRCRAFT_VIS = booleanPreferencesKey("aircraft_visible")
    private val KEY_CONTACTS_VIS = booleanPreferencesKey("contacts_visible")
    private val KEY_FOLLOW_ME = booleanPreferencesKey("follow_me_active")
    private val KEY_MIL_STD_SELF = booleanPreferencesKey("use_milstd_self_symbol")
    private val KEY_REMOTE_ID_SCAN = booleanPreferencesKey("remote_id_scan_enabled")
    private val KEY_GYB_DETECTOR = booleanPreferencesKey("gyb_detector_enabled")
    private val KEY_GYB_LAST_DEVICE = stringPreferencesKey("gyb_last_device_address")
    private val KEY_BROADCAST_OVER_MESH = booleanPreferencesKey("broadcast_over_mesh")
    private val KEY_MESH_BROADCAST_INTERVAL = intPreferencesKey("mesh_broadcast_interval_secs")
    private val KEY_MESH_FRAMEWORK = stringPreferencesKey("selected_mesh_framework")
    private val KEY_MAP_3D = booleanPreferencesKey("map_3d_enabled")
    private val KEY_CESIUM_GLOBE = booleanPreferencesKey("cesium_globe_enabled")
    private val KEY_TOOLBAR_ITEMS = stringPreferencesKey("toolbar_item_ids")
    private val KEY_TOOLBAR_COACH = booleanPreferencesKey("toolbar_coachmark_seen")
    // Camera persistence keys (issue: map resets to Spokane on cold start)
    private val KEY_CAMERA_LAT  = stringPreferencesKey("last_camera_lat")
    private val KEY_CAMERA_LON  = stringPreferencesKey("last_camera_lon")
    private val KEY_CAMERA_ZOOM = stringPreferencesKey("last_camera_zoom")
    // Issue #95 — north-up lock; #97 — keep-screen-on
    private val KEY_NORTH_UP_LOCKED = booleanPreferencesKey("north_up_locked")
    private val KEY_KEEP_SCREEN_ON  = booleanPreferencesKey("keep_screen_on")
    private val KEY_SELF_MARKER_TRIANGLE = booleanPreferencesKey("selfMarkerTriangle")
    private val KEY_STALENESS_OVERLAY = booleanPreferencesKey("staleness_overlay_enabled")
    private val KEY_RELAY_GATEWAY = booleanPreferencesKey("relay_gateway_enabled")

    val prefs: Flow<UserPrefs> = context.userPrefsDataStore.data.map { p -> readFrom(p) }

    suspend fun update(block: (UserPrefs) -> UserPrefs) {
        context.userPrefsDataStore.edit { p ->
            val next = block(readFrom(p))
            p[KEY_CALLSIGN] = next.callsign
            p[KEY_TEAM] = next.team
            p[KEY_ECHELON] = next.echelon
            p[KEY_SELF_UID] = next.selfUid
            p[KEY_SELF_LAT] = next.selfLat.toString()
            p[KEY_SELF_LON] = next.selfLon.toString()
            p[KEY_SELF_HAE] = next.selfHae.toString()
            p[KEY_SELF_FIX_TIME] = next.selfFixTimeMs
            p[KEY_DIST] = next.distanceUnit.name
            p[KEY_COORD] = next.coordFormat.name
            p[KEY_MAP] = next.mapProvider.name
            p[KEY_CUSTOM_TILE_URL] = next.customTileUrl
            p[KEY_AUTO_PUBLISH_MESH] = next.autoPublishMeshToTak
            p[KEY_MESH_LAYER_VISIBLE] = next.meshNodesLayerVisible
            p[KEY_MESH_PAIRED_VISIBLE] = next.meshPairedNodesVisible
            p[KEY_CALLSIGN_CARD] = next.callsignCardVisible
            p[KEY_GRID] = next.gridEnabled
            p[KEY_DRAWINGS_VIS] = next.drawingsVisible
            p[KEY_AIRCRAFT_VIS] = next.aircraftVisible
            p[KEY_CONTACTS_VIS] = next.contactsVisible
            p[KEY_FOLLOW_ME] = next.followMeActive
            p[KEY_MIL_STD_SELF] = next.useMilStdSelfSymbol
            p[KEY_REMOTE_ID_SCAN] = next.remoteIdScanEnabled
            p[KEY_GYB_DETECTOR] = next.gybDetectorEnabled
            p[KEY_GYB_LAST_DEVICE] = next.gybLastDeviceAddress
            p[KEY_BROADCAST_OVER_MESH] = next.broadcastOverMesh
            p[KEY_MESH_BROADCAST_INTERVAL] = next.meshBroadcastIntervalSecs.coerceIn(30, 60)
            p[KEY_MESH_FRAMEWORK] = next.selectedMeshFramework.name
            p[KEY_MAP_3D] = next.map3dEnabled
            p[KEY_CESIUM_GLOBE] = next.cesiumGlobeEnabled
            p[KEY_TOOLBAR_ITEMS] = next.toolbarItemIds.joinToString(",")
            p[KEY_TOOLBAR_COACH] = next.toolbarCoachmarkSeen
            // Camera persistence — write only when the value is present so a
            // null (first install) doesn't clobber a previously saved view.
            if (next.lastCameraLat != null)  p[KEY_CAMERA_LAT]  = next.lastCameraLat.toString()
            if (next.lastCameraLon != null)  p[KEY_CAMERA_LON]  = next.lastCameraLon.toString()
            if (next.lastCameraZoom != null) p[KEY_CAMERA_ZOOM] = next.lastCameraZoom.toString()
            p[KEY_NORTH_UP_LOCKED] = next.northUpLocked
            p[KEY_KEEP_SCREEN_ON]  = next.keepScreenOn
            p[KEY_SELF_MARKER_TRIANGLE] = next.selfMarkerTriangle
            p[KEY_STALENESS_OVERLAY] = next.stalenessOverlayEnabled
            p[KEY_RELAY_GATEWAY] = next.relayGatewayEnabled
        }
    }

    /** Persist the customizable toolbar layout. */
    suspend fun setToolbarItemIds(ids: List<String>) {
        update { it.copy(toolbarItemIds = ids) }
    }

    /** Mark the customize-toolbar coachmark as seen. */
    suspend fun setToolbarCoachmarkSeen(value: Boolean) {
        update { it.copy(toolbarCoachmarkSeen = value) }
    }

    /** Persist the last camera view so it survives cold starts. */
    suspend fun setLastCamera(lat: Double, lon: Double, zoom: Double) {
        update { it.copy(lastCameraLat = lat, lastCameraLon = lon, lastCameraZoom = zoom) }
    }

    /** Issue #75 — persist the last real GPS fix so the self-marker can
     *  render immediately on the next cold start (stale-marked when old)
     *  instead of disappearing until GPS reacquires. Speed/accuracy are
     *  deliberately NOT stored: a restored fix must not masquerade as a
     *  live one (see [SelfFixPersistence.restoredFixOrNull]). */
    suspend fun setLastSelfFix(fix: SelfFix) {
        update {
            it.copy(
                selfLat = fix.lat,
                selfLon = fix.lon,
                selfHae = fix.altitudeM,
                selfFixTimeMs = fix.timeMs,
            )
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

    /** Persist the off-grid mesh broadcast toggle. */
    suspend fun setBroadcastOverMesh(value: Boolean) {
        update { it.copy(broadcastOverMesh = value) }
    }

    /** Persist the mesh PPLI broadcast interval (coerced to 30..60 seconds). */
    suspend fun setMeshBroadcastIntervalSecs(value: Int) {
        update { it.copy(meshBroadcastIntervalSecs = value.coerceIn(30, 60)) }
    }

    /** Persist the operator's chosen mesh framework (Meshtastic | MeshCore). */
    suspend fun setSelectedMeshFramework(value: MeshFramework) {
        update { it.copy(selectedMeshFramework = value) }
    }

    /** #179 — persist the mesh↔server relay/gateway toggle (default off). */
    suspend fun setRelayGatewayEnabled(value: Boolean) {
        update { it.copy(relayGatewayEnabled = value) }
    }

    /**
     * Read the stable EUD UID, generating + persisting one on first
     * call. Every wire CoT event tied to this device — PPLI, GeoChat,
     * markers — has to share this UID; sending two different ones makes
     * receiving ATAK render the operator as two separate contacts
     * (#9).
     *
     * Format `ANDROID-<uuid>` matches what SelfPositionBroadcaster has
     * been minting since 0.1; existing installs keep their value.
     */
    suspend fun ensureSelfUid(): String {
        val current = prefs.first()
        if (current.selfUid.isNotBlank()) return current.selfUid
        val generated = "ANDROID-${java.util.UUID.randomUUID()}"
        update { it.copy(selfUid = generated) }
        return generated
    }

    private fun readFrom(p: androidx.datastore.preferences.core.Preferences): UserPrefs = UserPrefs(
        callsign = p[KEY_CALLSIGN] ?: "OMNI-1",
        echelon = p[KEY_ECHELON] ?: "",
        team = normalizeTeam(p[KEY_TEAM]),
        selfUid = p[KEY_SELF_UID] ?: "",
        selfLat = p[KEY_SELF_LAT]?.toDoubleOrNull() ?: Double.NaN,
        selfLon = p[KEY_SELF_LON]?.toDoubleOrNull() ?: Double.NaN,
        selfHae = p[KEY_SELF_HAE]?.toDoubleOrNull() ?: Double.NaN,
        selfFixTimeMs = p[KEY_SELF_FIX_TIME] ?: 0L,
        distanceUnit = p[KEY_DIST]?.let { runCatching { DistanceUnit.valueOf(it) }.getOrNull() }
            ?: DistanceUnit.METRIC,
        coordFormat = p[KEY_COORD]?.let { runCatching { CoordFormat.valueOf(it) }.getOrNull() }
            ?: CoordFormat.LATLON_DECIMAL,
        mapProvider = p[KEY_MAP]?.let { runCatching { MapProvider.valueOf(it) }.getOrNull() }
            ?: MapProvider.TOPO_HINT,
        customTileUrl = p[KEY_CUSTOM_TILE_URL] ?: "",
        autoPublishMeshToTak = p[KEY_AUTO_PUBLISH_MESH] ?: true,
        meshNodesLayerVisible = p[KEY_MESH_LAYER_VISIBLE] ?: true,
        meshPairedNodesVisible = p[KEY_MESH_PAIRED_VISIBLE] ?: false,
        callsignCardVisible = p[KEY_CALLSIGN_CARD] ?: true,
        gridEnabled = p[KEY_GRID] ?: false,
        drawingsVisible = p[KEY_DRAWINGS_VIS] ?: true,
        aircraftVisible = p[KEY_AIRCRAFT_VIS] ?: true,
        contactsVisible = p[KEY_CONTACTS_VIS] ?: true,
        followMeActive = p[KEY_FOLLOW_ME] ?: false,
        useMilStdSelfSymbol = p[KEY_MIL_STD_SELF] ?: true,
        remoteIdScanEnabled = p[KEY_REMOTE_ID_SCAN] ?: false,
        gybDetectorEnabled = p[KEY_GYB_DETECTOR] ?: false,
        gybLastDeviceAddress = p[KEY_GYB_LAST_DEVICE] ?: "",
        broadcastOverMesh = p[KEY_BROADCAST_OVER_MESH] ?: true,
        meshBroadcastIntervalSecs = p[KEY_MESH_BROADCAST_INTERVAL]?.coerceIn(30, 60) ?: 30,
        selectedMeshFramework = p[KEY_MESH_FRAMEWORK]
            ?.let { runCatching { MeshFramework.valueOf(it) }.getOrNull() }
            ?: MeshFramework.MESHTASTIC,
        map3dEnabled = p[KEY_MAP_3D] ?: false,
        cesiumGlobeEnabled = p[KEY_CESIUM_GLOBE] ?: false,
        toolbarItemIds = p[KEY_TOOLBAR_ITEMS]?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
        toolbarCoachmarkSeen = p[KEY_TOOLBAR_COACH] ?: false,
        lastCameraLat  = p[KEY_CAMERA_LAT]?.toDoubleOrNull(),
        lastCameraLon  = p[KEY_CAMERA_LON]?.toDoubleOrNull(),
        lastCameraZoom = p[KEY_CAMERA_ZOOM]?.toDoubleOrNull(),
        northUpLocked  = p[KEY_NORTH_UP_LOCKED] ?: false,
        keepScreenOn   = p[KEY_KEEP_SCREEN_ON]  ?: false,
        selfMarkerTriangle = p[KEY_SELF_MARKER_TRIANGLE] ?: false,
        stalenessOverlayEnabled = p[KEY_STALENESS_OVERLAY] ?: false,
        relayGatewayEnabled = p[KEY_RELAY_GATEWAY] ?: false,
    )

    // ATAK / OpenTakServer canonical team names are Title Case ("Cyan",
    // "Dark Blue"). OmniTAK shipped an ALLCAPS picker through 0.32.0, so
    // legacy installs still have ALLCAPS in DataStore — normalize on read
    // so wire emission (`<__group name="...">`) matches the server's DB
    // keying and OTS's web UI stops rejecting our PPLI.
    private fun normalizeTeam(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return "Cyan"
        return trimmed.split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word ->
                word.lowercase().replaceFirstChar { it.titlecase() }
            }
    }
}
