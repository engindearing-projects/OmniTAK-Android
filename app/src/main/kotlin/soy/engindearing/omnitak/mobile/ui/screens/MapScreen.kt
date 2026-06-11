package soy.engindearing.omnitak.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.AddLocation
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent
import soy.engindearing.omnitak.mobile.ui.theme.TacticalBackground
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import soy.engindearing.omnitak.mobile.OmniTAKApp
import soy.engindearing.omnitak.mobile.data.CoTAffiliation
import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.Drawing
import soy.engindearing.omnitak.mobile.data.DrawingKind
import soy.engindearing.omnitak.mobile.data.GeoMath
import soy.engindearing.omnitak.mobile.domain.ConnectionState
import soy.engindearing.omnitak.mobile.ui.components.ATAKStatusBar
import soy.engindearing.omnitak.mobile.ui.components.ContactsPanel
import soy.engindearing.omnitak.mobile.ui.components.LayersDialog
import soy.engindearing.omnitak.mobile.ui.components.MarkerEditSheet
import soy.engindearing.omnitak.mobile.ui.components.RadialAction
import soy.engindearing.omnitak.mobile.ui.components.RadialMenu
import soy.engindearing.omnitak.mobile.ui.components.SelfPositionCard
import soy.engindearing.omnitak.mobile.ui.components.TacticalMap
import soy.engindearing.omnitak.mobile.ui.components.styleJsonForProvider
import soy.engindearing.omnitak.mobile.ui.components.ToolEntry
import soy.engindearing.omnitak.mobile.ui.components.ToolsDrawer
import soy.engindearing.omnitak.mobile.ui.components.rememberLocationPermission
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Cold-start fallback when there is no persisted camera and no GPS fix yet.
// World-level zoom centered on 0°N 0°E — neutral for every operator globally.
private val FALLBACK_GLOBAL_VIEW = LatLng(0.0, 0.0)
private const val FALLBACK_GLOBAL_ZOOM = 2.0

@Composable
fun MapScreen(onOpenTab: (String) -> Unit = {}) {
    val app = LocalContext.current.applicationContext as OmniTAKApp
    val active by app.serverManager.activeServer.collectAsState()
    val connState by app.serverManager.connectionState.collectAsState()
    val allServers by app.serverManager.servers.collectAsState()
    val connectedIds by app.serverManager.connectedServerIds.collectAsState()
    val msgReceived by app.serverManager.messagesReceived.collectAsState()
    val msgSent by app.serverManager.messagesSent.collectAsState()
    val contacts by app.contactStore.contacts.collectAsState()
    // Layers toggle: mesh-origin contacts are persisted because the
    // operator's last choice should survive a process restart. Default
    // visible — matches iOS.
    val userPrefs by app.userPrefsStore.prefs.collectAsState(initial = soy.engindearing.omnitak.mobile.data.UserPrefs())
    val meshNodesVisible = userPrefs.meshNodesLayerVisible
    // GAP-110 — persisted UI toggles. These survive relaunch instead of
    // resetting to defaults each time the user opens the app. Aliases so
    // existing read sites stay terse.
    val gridEnabled = userPrefs.gridEnabled
    val drawingsVisible = userPrefs.drawingsVisible
    val aircraftVisible = userPrefs.aircraftVisible
    val contactsVisible = userPrefs.contactsVisible
    val callsignCardVisible = userPrefs.callsignCardVisible
    val followMeActive = userPrefs.followMeActive
    val map3dEnabled = userPrefs.map3dEnabled
    val prefScope = rememberCoroutineScope()
    fun mutatePref(block: (soy.engindearing.omnitak.mobile.data.UserPrefs) -> soy.engindearing.omnitak.mobile.data.UserPrefs) {
        prefScope.launch { app.userPrefsStore.update(block) }
    }

    val headerLabel = when (val s = connState) {
        is ConnectionState.Connected -> s.serverName
        is ConnectionState.Connecting -> "Connecting…"
        is ConnectionState.Failed -> "Failed"
        ConnectionState.Disconnected -> active?.name ?: "Offline"
    }

    var nowLabel by remember { mutableStateOf(timeLabel()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowLabel = timeLabel()
            delay(15_000L)
        }
    }

    val locationGranted by rememberLocationPermission()
    // GAP-030b — start the FusedLocationProviderClient as soon as we have
    // permission. Idempotent, safe to re-invoke on every recomposition.
    val selfFix by app.locationProvider.fix.collectAsState()
    LaunchedEffect(locationGranted) {
        if (locationGranted) app.locationProvider.start()
    }
    var radialAnchor by remember { mutableStateOf<Offset?>(null) }
    var radialLatLng by remember { mutableStateOf<LatLng?>(null) }
    var markerSheetLatLng by remember { mutableStateOf<LatLng?>(null) }
    // #29 — FEMA / IC marker palette. Tap a FEMA action → camera-center is
    // captured here → palette sheet picks an icon → drop emits the CoT
    // event with the FEMA cotType + iconsetpath. Independent of the
    // generic markerSheetLatLng pipeline so the affiliation-based UI
    // doesn't have to know about FEMA-specific fields.
    var femaPaletteLatLng by remember { mutableStateOf<LatLng?>(null) }
    var editingMarker by remember { mutableStateOf<CoTEvent?>(null) }
    var recenterTick by remember { mutableStateOf(0) }
    var zoomInTick by remember { mutableStateOf(0) }
    var zoomOutTick by remember { mutableStateOf(0) }
    var measurementActive by remember { mutableStateOf(false) }
    // UAS waypoint-add mode — when true, taps on the map drop mission
    // waypoints instead of any other action. Toggled from the mission
    // banner's Done button and entered via Tools → "Add UAS Waypoints".
    var missionMode by remember { mutableStateOf(false) }
    // Multi-drone: bind HUD/commands to the active drone. When operator
    // switches active via the Drones picker, this re-emits and all the
    // inner collectAsState subscriptions tear down + re-bind to the
    // new manager. Inactive drones stay running in the registry.
    val uas by app.uasRegistry.active.collectAsState()
    val mission by uas.mission.collectAsState()
    // Index of the waypoint currently being edited (tap a pin to open
    // the WaypointEditSheet). null = no sheet. Lives at function scope
    // because both onMapSingleTap (hit-test) and the sheet itself need
    // to see/mutate it.
    var editingWaypointIndex by remember { mutableStateOf<Int?>(null) }
    // Mission rehearsal sheet state — held here so both the Rehearse
    // button (MissionBanner) and the sheet itself can read/mutate.
    var rehearsalResult by remember {
        mutableStateOf<soy.engindearing.omnitak.mobile.domain.MissionRehearsal.Result?>(null)
    }
    var rehearsalRunning by remember { mutableStateOf(false) }
    var measurementPoints by remember { mutableStateOf<List<LatLng>>(emptyList()) }
    var drawingKind by remember { mutableStateOf<DrawingKind?>(null) }
    var drawingPoints by remember { mutableStateOf<List<LatLng>>(emptyList()) }
    var drawingPickerOpen by remember { mutableStateOf(false) }
    // GAP-110 — gridEnabled, drawingsVisible, aircraftVisible, contactsVisible,
    // callsignCardVisible, followMeActive are now read from userPrefs (above)
    // so they survive relaunch.
    var layersSheetOpen by remember { mutableStateOf(false) }
    var teamsPanelOpen by remember { mutableStateOf(false) }
    var panTarget by remember { mutableStateOf<LatLng?>(null) }
    var panTargetTick by remember { mutableStateOf(0) }
    // "Go to Coordinate" sheet (TWD97 / MGRS / Lat-Lon). Opened from the
    // Tools popup via CoordinateEntryEvents; reference coord = map centre
    // so 5+5 TWD97 grid input recovers the right 100 km cell.
    var coordEntryOpen by remember { mutableStateOf(false) }
    val coordEntryGen by soy.engindearing.omnitak.mobile.ui.components.CoordinateEntryEvents.openGeneration.collectAsState()
    LaunchedEffect(coordEntryGen) {
        if (coordEntryGen > 0L) coordEntryOpen = true
    }
    val adsbService = remember { soy.engindearing.omnitak.mobile.data.AdsbService() }
    val rawAircraft by adsbService.aircraft.collectAsState()
    val adsbActive by adsbService.active.collectAsState()
    DisposableEffect(adsbService) { onDispose { adsbService.stop() } }

    // Drone telemetry from UASManager — the connected UAS gets its own
    // DroneLayer (programmatic source/layer added at runtime), which
    // pops visually above ADS-B traffic and self so the drone isn't
    // lost in the generic aircraft circle. We still expose the drone
    // through the regular aircraft list for the historical hooks
    // (lasso, etc.) but the dedicated layer is what the operator sees.
    val droneState by uas.state.collectAsState()
    val aircraft = rawAircraft
    // Pass the drone separately to TacticalMap → DroneLayer renders it
    // as a distinct cyan ring + callsign label, above the aircraft circle.
    val droneForMap = if (droneState.hasFix()) droneState else null
    val drawings by app.drawingStore.drawings.collectAsState()
    // KML vector overlays (imported KML/KMZ rendered as GeoJSON sources).
    val kmlOverlays by app.kmlOverlayStore.overlays.collectAsState()
    // MBTiles raster tile overlays (served by the in-app tile server).
    val mbtilesOverlays by app.mbtilesOverlayStore.overlays.collectAsState()
    // Single-image raster overlays (GroundOverlay etc.) via ImageSource.
    val rasterImagery by app.rasterOverlayStore.overlays.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Issue #16 — Lasso freehand multi-select.
    // The MapLibreMap reference is captured via TacticalMap.onMapReady
    // so the lasso overlay can project screen pixels to LatLng on
    // drag end. lassoMode is flipped on by the Tools popup → service
    // event; it flips back automatically when LassoOverlay reports
    // the gesture completed (commits selection on the service).
    var lassoMode by remember { mutableStateOf(false) }
    var mapboxMap by remember { mutableStateOf<org.maplibre.android.maps.MapLibreMap?>(null) }
    val lassoService = remember { soy.engindearing.omnitak.mobile.domain.LassoSelectionService.shared }
    val lassoSelection by lassoService.current.collectAsState()
    var lassoActionsOpen by remember { mutableStateOf(false) }
    var contactPickerOpen by remember { mutableStateOf(false) }
    val appContext = LocalContext.current.applicationContext
    // Observe activation requests via a generation counter — every
    // increment means "user picked Lasso Select again." We track the
    // last value we honored so we only flip lassoMode on a fresh
    // edge. StateFlow keeps the latest value alive across MapScreen
    // recompositions, so an emission that lands before the collector
    // subscribed still gets applied as soon as we observe it.
    val activationGen by lassoService.activationGeneration.collectAsState()
    var lastHonoredGen by remember { mutableStateOf(activationGen) }
    LaunchedEffect(activationGen) {
        if (activationGen != lastHonoredGen) {
            lastHonoredGen = activationGen
            // Skip the initial "0" baseline — only react to real increments.
            if (activationGen > 0L) lassoMode = true
        }
    }

    fun toast(msg: String) {
        scope.launch { snackbar.showSnackbar(msg, withDismissAction = true) }
    }

    // Re-apply KML overlays to the live style whenever the set changes.
    // (Re-application after a style RELOAD is handled by TacticalMap.onStyleReady.)
    LaunchedEffect(kmlOverlays) {
        mapboxMap?.getStyle { style ->
            soy.engindearing.omnitak.mobile.ui.components.KmlOverlayRenderer
                .apply(style, kmlOverlays, app.kmlOverlayStore)
        }
    }
    // Re-apply MBTiles raster overlays when the set changes.
    LaunchedEffect(mbtilesOverlays) {
        mapboxMap?.getStyle { style ->
            soy.engindearing.omnitak.mobile.ui.components.KmlOverlayRenderer
                .applyMBTiles(style, mbtilesOverlays, app.mbtilesOverlayStore)
        }
    }
    // Re-apply single-image raster overlays when the set changes.
    LaunchedEffect(rasterImagery) {
        mapboxMap?.getStyle { style ->
            soy.engindearing.omnitak.mobile.ui.components.KmlOverlayRenderer
                .applyRaster(style, rasterImagery, app.rasterOverlayStore)
        }
    }
    // Frame raster/MBTiles bounds on request.
    val mbZoomBounds by soy.engindearing.omnitak.mobile.ui.components.KmlOverlayEvents.zoomBounds.collectAsState()
    LaunchedEffect(mbZoomBounds) {
        val b = mbZoomBounds ?: return@LaunchedEffect
        if (userPrefs.cesiumGlobeEnabled) {
            app.userPrefsStore.update { it.copy(cesiumGlobeEnabled = false) }
        }
        mapboxMap?.let { m ->
            val bounds = org.maplibre.android.geometry.LatLngBounds.from(b[0], b[2], b[1], b[3])
            runCatching {
                m.easeCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds(bounds, 100), 800)
            }
        }
        soy.engindearing.omnitak.mobile.ui.components.KmlOverlayEvents.boundsConsumed()
    }
    // Frame an overlay's bounds when the Map Overlays sheet requests it.
    val kmlZoom by soy.engindearing.omnitak.mobile.ui.components.KmlOverlayEvents.zoomTo.collectAsState()
    LaunchedEffect(kmlZoom) {
        val o = kmlZoom ?: return@LaunchedEffect
        if (userPrefs.cesiumGlobeEnabled) {
            app.userPrefsStore.update { it.copy(cesiumGlobeEnabled = false) }
        }
        mapboxMap?.let { m ->
            val bounds = org.maplibre.android.geometry.LatLngBounds.from(o.maxLat, o.maxLon, o.minLat, o.minLon)
            runCatching {
                m.easeCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds(bounds, 100), 800)
            }
        }
        soy.engindearing.omnitak.mobile.ui.components.KmlOverlayEvents.consumed()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (userPrefs.cesiumGlobeEnabled) {
        // 3D Globe — photoreal Cesium WebView engine. Contacts (incl. dropped
        // pins) + self render as entities; long-press surfaces the same radial
        // menu the 2D/terrain engines use, tapping a contact opens its sheet.
        soy.engindearing.omnitak.mobile.ui.components.CesiumMapView(
            modifier = Modifier.fillMaxSize(),
            contacts = if (contactsVisible) {
                if (meshNodesVisible) contacts.values.toList()
                else contacts.values.filterNot { it.uid.startsWith("MESHTASTIC-") }
            } else {
                emptyList()
            },
            selfLat = selfFix?.lat,
            selfLon = selfFix?.lon,
            selfCallsign = userPrefs.callsign,
            onLongPress = { latLng, offset ->
                if (!measurementActive) {
                    radialLatLng = latLng
                    radialAnchor = offset
                }
            },
            onContactTap = { event ->
                if (!measurementActive) {
                    editingMarker = event
                    markerSheetLatLng = LatLng(event.lat, event.lon)
                }
            },
            onCameraChanged = { target, zoom ->
                app.mapCameraStore.update(target.latitude, target.longitude, zoom)
            },
            // Same control ticks the 2D map uses — make +/- and "center on
            // me" drive the globe (VC 77: buttons did nothing on 3D).
            zoomInTrigger = zoomInTick,
            zoomOutTrigger = zoomOutTick,
            recenterTrigger = recenterTick,
        )
        } else {
        TacticalMap(
            modifier = Modifier.fillMaxSize(),
            // Cold-start camera priority:
            //   1. Persisted view (DataStore via MapCameraStore) — operator's last pan/zoom.
            //   2. Self-fix — center on GPS position if available and no saved view.
            //   3. Neutral global fallback — world-level zoom, no developer home-town.
            // Spokane hardcode removed (P-E TAK Discord report 2026-05-27).
            initialCenter = run {
                val camLat = app.mapCameraStore.lastTargetLat
                val camLon = app.mapCameraStore.lastTargetLon
                when {
                    camLat != null && camLon != null -> LatLng(camLat, camLon)
                    selfFix != null -> LatLng(selfFix!!.lat, selfFix!!.lon)
                    else -> FALLBACK_GLOBAL_VIEW
                }
            },
            initialZoom = app.mapCameraStore.lastZoom
                ?: if (selfFix != null) 12.0 else FALLBACK_GLOBAL_ZOOM,
            onCameraIdle = { target, zoom ->
                app.mapCameraStore.update(target.latitude, target.longitude, zoom)
            },
            onMapReady = { map -> mapboxMap = map },
            // GAP-101 / GAP-107 — react to the basemap selection from Settings.
            // WMTS_CUSTOM uses the operator-pasted XYZ tile URL.
            styleJson = styleJsonForProvider(userPrefs.mapProvider, userPrefs.customTileUrl, terrain3d = map3dEnabled),
            terrain3d = map3dEnabled,
            onMapLongPress = { latLng, offset ->
                if (measurementActive) return@TacticalMap
                radialLatLng = latLng
                radialAnchor = offset
            },
            onContactTap = { event ->
                if (measurementActive) return@TacticalMap
                editingMarker = event
                markerSheetLatLng = LatLng(event.lat, event.lon)
            },
            onMapSingleTap = onMapSingleTap@ { latLng ->
                // Hit-test existing mission waypoints first so a tap on
                // a pin opens its edit sheet instead of adding a new
                // waypoint on top of it. ~80 m radius covers both
                // missionMode (adding) and view-only modes — pins are
                // 28dp visual, but operator GPS-tap accuracy isn't
                // pixel-perfect on a moving thumb.
                val waypoints = uas.mission.value.waypoints
                val hitIdx = waypoints.indexOfFirst { wp ->
                    val dLat = wp.latDeg - latLng.latitude
                    val dLon = wp.lonDeg - latLng.longitude
                    val metersPerDegLat = 111_320.0
                    val metersPerDegLon = 111_320.0 * kotlin.math.cos(Math.toRadians(latLng.latitude))
                    kotlin.math.hypot(dLat * metersPerDegLat, dLon * metersPerDegLon) < 80.0
                }
                if (hitIdx >= 0) {
                    editingWaypointIndex = hitIdx
                    return@onMapSingleTap true
                }
                when {
                    missionMode -> {
                        uas.missionStore.addWaypoint(latLng.latitude, latLng.longitude)
                        true
                    }
                    measurementActive -> {
                        measurementPoints = measurementPoints + latLng
                        true
                    }
                    drawingKind != null -> {
                        drawingPoints = drawingPoints + latLng
                        true
                    }
                    else -> false
                }
            },
            locationEnabled = locationGranted,
            recenterTrigger = recenterTick,
            zoomInTrigger = zoomInTick,
            zoomOutTrigger = zoomOutTick,
            contacts = if (contactsVisible) {
                if (meshNodesVisible) contacts.values
                // Hide mesh-origin contacts — they all share the
                // `MESHTASTIC-` UID prefix produced by
                // `MeshtasticCoTConverter.takUid`.
                else contacts.values.filterNot { it.uid.startsWith("MESHTASTIC-") }
            } else {
                emptyList()
            },
            measurementPoints = measurementPoints,
            drawings = if (drawingsVisible) {
                drawings + buildInProgressDrawing(drawingKind, drawingPoints)
            } else {
                emptyList()
            },
            gridCenter = if (gridEnabled) LatLng(37.42, -122.08) else null,
            aircraft = if (aircraftVisible) aircraft else emptyList(),
            panTarget = panTarget,
            panTargetTick = panTargetTick,
            followMeActive = followMeActive,
            useMilStdSelfSymbol = userPrefs.useMilStdSelfSymbol,
            selfTeamColor = userPrefs.team,
            onStyleReady = { _, style ->
                soy.engindearing.omnitak.mobile.ui.components.KmlOverlayRenderer
                    .apply(style, kmlOverlays, app.kmlOverlayStore)
                soy.engindearing.omnitak.mobile.ui.components.KmlOverlayRenderer
                    .applyMBTiles(style, mbtilesOverlays, app.mbtilesOverlayStore)
                soy.engindearing.omnitak.mobile.ui.components.KmlOverlayRenderer
                    .applyRaster(style, rasterImagery, app.rasterOverlayStore)
            },
        )
        }

        // Issue #16 — lasso freehand multi-select overlay. Renders
        // ABOVE the map so the dashed orange path stays on top of
        // marker layers (the Android equivalent of the iOS
        // CAShapeLayer fix). Inert when `active = false` — pan/zoom
        // continue to land on TacticalMap.
        // Drawing → LassoDrawing adapter. Drawing.id is a String (TAK
        // UID style); LassoDrawing.id is UUID for parity with the iOS
        // service shape. We project the string onto a deterministic
        // UUID via nameUUIDFromBytes so the same drawing always maps
        // to the same UUID across recompositions and we can reverse
        // the mapping during bulk delete.
        val lassoDrawingIdMap = remember(drawings) {
            drawings.associate {
                java.util.UUID.nameUUIDFromBytes(it.id.toByteArray()) to it.id
            }
        }
        val lassoDrawings = remember(drawings) {
            drawings.map { d ->
                soy.engindearing.omnitak.mobile.domain.LassoDrawing(
                    id = java.util.UUID.nameUUIDFromBytes(d.id.toByteArray()),
                    coordinates = d.points.map { (lat, lon) ->
                        soy.engindearing.omnitak.mobile.domain.LassoLatLng(latitude = lat, longitude = lon)
                    },
                )
            }
        }

        // Link line operator → drone + drone trail. Rendered BEFORE the
        // drone overlay so the cyan marker lands on top of its own tail.
        val opFix by app.locationProvider.fix.collectAsState()
        soy.engindearing.omnitak.mobile.ui.components.UasLinkAndTrail(
            drone = droneState,
            operator = opFix,
            mapboxMap = mapboxMap,
        )

        // FAA UAS Facility Map overlay — rendered BELOW the drone /
        // mission overlays so the polygons sit behind operator pins.
        // Visible only when a UAS is connected (cuts the noise for
        // non-drone operators).
        soy.engindearing.omnitak.mobile.ui.components.FaaNfzOverlay(
            visible = droneState.isConnected(),
            centerLatDeg = (droneState.homeLatDeg ?: selfFix?.lat),
            centerLonDeg = (droneState.homeLonDeg ?: selfFix?.lon),
            mapboxMap = mapboxMap,
        )

        soy.engindearing.omnitak.mobile.ui.components.HomePositionOverlay(
            homeLatDeg = droneState.homeLatDeg,
            homeLonDeg = droneState.homeLonDeg,
            mapboxMap = mapboxMap,
        )

        // Soft geofence ring around HOME (drone-set takeoff point).
        val geofenceM by uas.geofenceMeters.collectAsState()
        soy.engindearing.omnitak.mobile.ui.components.GeofenceOverlay(
            centerLatDeg = droneState.homeLatDeg,
            centerLonDeg = droneState.homeLonDeg,
            radiusMeters = geofenceM,
            mapboxMap = mapboxMap,
        )

        soy.engindearing.omnitak.mobile.ui.components.DroneOverlay(
            drone = droneForMap,
            mapboxMap = mapboxMap,
        )

        // Multi-drone: render markers for every other connected drone
        // (smaller, dimmer than the active marker so operator can't
        // confuse them with the command target).
        val allDrones by app.uasRegistry.drones.collectAsState()
        soy.engindearing.omnitak.mobile.ui.components.InactiveDronesOverlay(
            drones = allDrones,
            activeManager = uas,
            mapboxMap = mapboxMap,
        )

        soy.engindearing.omnitak.mobile.ui.components.MissionOverlay(
            mission = mission,
            mapboxMap = mapboxMap,
            onWaypointClick = { idx -> editingWaypointIndex = idx },
        )
        editingWaypointIndex?.let { idx ->
            mission.waypoints.getOrNull(idx)?.let { wp ->
                val homeMsl = droneState.altMslMeters ?: 0.0
                val cruise = uas.cruiseAlt.value
                val cruiseMsl = cruise.toMsl(homeMsl)
                soy.engindearing.omnitak.mobile.ui.components.WaypointEditSheet(
                    index = idx,
                    waypoint = wp,
                    cruiseHintMsl = cruiseMsl,
                    onApply = { updated -> uas.missionStore.updateWaypoint(idx, updated) },
                    onDelete = { uas.missionStore.removeWaypoint(idx) },
                    onDismiss = { editingWaypointIndex = null },
                )
            }
        }

        // -------- UAS HUD: cruise altitude pill + Follow-Me toggle --------
        val cruiseAlt by uas.cruiseAlt.collectAsState()
        val followActive by uas.followMeActive.collectAsState()
        var altSheetOpen by remember { mutableStateOf(false) }
        if (droneState.isConnected()) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 90.dp, end = 12.dp),
                contentAlignment = Alignment.TopEnd,
            ) {
                val operatorFix by app.locationProvider.fix.collectAsState()
                val terrainBelowDrone by uas.terrainBelowDroneMsl.collectAsState()
                androidx.compose.foundation.layout.Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                ) {
                    // Drones picker — visible whenever ≥1 drone is in
                    // the registry, regardless of which is active.
                    var dronesSheetOpen by remember { mutableStateOf(false) }
                    val activeEntry = allDrones.firstOrNull { it.manager === uas }
                    soy.engindearing.omnitak.mobile.ui.components.UasDronesPill(
                        droneCount = allDrones.size,
                        activeCallsign = activeEntry?.callsign,
                        onClick = { dronesSheetOpen = true },
                    )
                    if (dronesSheetOpen) {
                        soy.engindearing.omnitak.mobile.ui.components.UasDronesPickerSheet(
                            drones = allDrones,
                            activeManager = uas,
                            onPick = { id -> app.uasRegistry.setActive(id) },
                            onDisconnect = { id -> app.uasRegistry.disconnect(id) },
                            onDismiss = { dronesSheetOpen = false },
                        )
                    }
                    // Pre-flight readiness — only shown when disarmed.
                    // Component handles the auto-hide on armed=true.
                    soy.engindearing.omnitak.mobile.ui.components.UasPreflightCard(
                        drone = droneState,
                    )
                    soy.engindearing.omnitak.mobile.ui.components.UasAltitudePill(
                        cruise = cruiseAlt,
                        onClick = { altSheetOpen = true },
                    )
                    soy.engindearing.omnitak.mobile.ui.components.UasSituationCard(
                        drone = droneState,
                        operator = operatorFix,
                        terrainBelowDroneMsl = terrainBelowDrone,
                    )
                    var autoFollow by remember { mutableStateOf(false) }
                    // Auto-follow loop — re-pans camera to drone every 2s
                    // while toggle is on. 2s is the sweet spot: long enough
                    // that operator-initiated map drags aren't instantly
                    // yanked back; short enough that drone stays visible.
                    androidx.compose.runtime.LaunchedEffect(autoFollow, droneState.latDeg, droneState.lonDeg) {
                        if (!autoFollow) return@LaunchedEffect
                        while (autoFollow) {
                            val lat = droneState.latDeg
                            val lon = droneState.lonDeg
                            val m = mapboxMap
                            if (lat != null && lon != null && m != null) {
                                val pos = m.cameraPosition
                                m.cameraPosition = org.maplibre.android.camera.CameraPosition.Builder()
                                    .target(LatLng(lat, lon))
                                    .zoom(pos.zoom)
                                    .build()
                            }
                            kotlinx.coroutines.delay(2_000)
                        }
                    }
                    soy.engindearing.omnitak.mobile.ui.components.UasAutoFollowPill(
                        active = autoFollow,
                        onToggle = { autoFollow = !autoFollow },
                    )
                    soy.engindearing.omnitak.mobile.ui.components.UasFollowMePill(
                        active = followActive,
                        onToggle = {
                            scope.launch {
                                if (followActive) {
                                    uas.stopFollowMe()
                                    toast("Follow-Me OFF — drone parked in LOITER")
                                } else {
                                    val r = uas.startFollowMe()
                                    val msg = when (r) {
                                        soy.engindearing.omnitak.mobile.domain.UASManager.FollowMeResult.Started ->
                                            "Following you at ${cruiseAlt.meters.toInt()} m above"
                                        soy.engindearing.omnitak.mobile.domain.UASManager.FollowMeResult.NoGpsFix ->
                                            "Need your GPS fix first — open the map a moment"
                                        soy.engindearing.omnitak.mobile.domain.UASManager.FollowMeResult.NotConnected ->
                                            "UAS link down"
                                        is soy.engindearing.omnitak.mobile.domain.UASManager.FollowMeResult.AutopilotNotSupported ->
                                            "Follow-Me requires PX4 (autopilot=${r.autopilot ?: "unknown"})"
                                    }
                                    toast(msg)
                                }
                            }
                        },
                    )
                }
            }
        }
        if (altSheetOpen) {
            soy.engindearing.omnitak.mobile.ui.components.UasAltitudeSheet(
                current = cruiseAlt,
                onApply = { newCruise ->
                    uas.setCruiseAltitude(newCruise.meters, newCruise.frame)
                },
                onDismiss = { altSheetOpen = false },
            )
        }

        // STATUSTEXT alert banner — top-center, auto-dismisses after 6s.
        // Placed above the mission banner so a CRITICAL alert always
        // wins the operator's attention even when a mission is queued.
        if (droneState.latestAlert != null) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 64.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                soy.engindearing.omnitak.mobile.ui.components.UasAlertBanner(
                    alert = droneState.latestAlert,
                )
            }
        }

        if (missionMode || mission.waypoints.isNotEmpty()) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 90.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                soy.engindearing.omnitak.mobile.ui.components.MissionBanner(
                    missionMode = missionMode,
                    mission = mission,
                    onUploadAndStart = {
                        scope.launch { uas.uploadAndStartMission() }
                    },
                    onUndo = { uas.missionStore.undoWaypoint() },
                    onCancel = { uas.cancelMission() },
                    onExitMissionMode = { missionMode = false },
                    onRehearse = {
                        rehearsalRunning = true
                        scope.launch {
                            rehearsalResult = uas.rehearseCurrentMission()
                            rehearsalRunning = false
                        }
                    },
                )
            }
        }

        // Mission rehearsal sheet — opens on "Rehearse" button.
        if (rehearsalResult != null || rehearsalRunning) {
            soy.engindearing.omnitak.mobile.ui.components.MissionRehearsalSheet(
                result = rehearsalResult,
                running = rehearsalRunning,
                onUploadAnyway = {
                    rehearsalResult = null
                    scope.launch { uas.uploadAndStartMission() }
                    toast("Mission uploading (rehearsal warning overridden)")
                },
                onUploadAndStart = {
                    rehearsalResult = null
                    scope.launch { uas.uploadAndStartMission() }
                    toast("Mission uploading")
                },
                onDismiss = { rehearsalResult = null },
            )
        }

        // -------- Live video PIP — bottom-right when a video source is set + connected --------
        val videoSource by uas.videoSource.collectAsState()
        var videoVisible by remember { mutableStateOf(true) }
        if (droneState.isConnected() && videoSource.isActive && videoVisible) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = 12.dp, bottom = 90.dp),
                contentAlignment = Alignment.BottomEnd,
            ) {
                soy.engindearing.omnitak.mobile.ui.components.UasVideoPip(
                    source = videoSource,
                    onDismiss = { videoVisible = false },
                    onPhoto = {
                        scope.launch { uas.takePhoto() }
                        toast("📷 Photo captured")
                    },
                    onToggleRecord = { rec ->
                        scope.launch {
                            if (rec) uas.startVideoRecording()
                            else uas.stopVideoRecording()
                        }
                        toast(if (rec) "🔴 Video REC" else "⏹ Video stopped")
                    },
                    onGimbalForward = {
                        scope.launch { uas.setGimbalPitch(0f) }
                    },
                    onGimbalNadir = {
                        scope.launch { uas.setGimbalPitch(-90f) }
                    },
                )
            }
        }

        // -------- In-flight control bar (armed only) — bottom-center --------
        if (droneState.isConnected() && droneState.armed == true) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 24.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                soy.engindearing.omnitak.mobile.ui.components.UasControlBar(
                    drone = droneState,
                    mission = mission,
                    onLand = {
                        scope.launch { uas.landHere() }
                        toast("LAND HERE — drone descending at current position")
                    },
                    onPause = {
                        scope.launch { uas.pauseMission() }
                        toast("Mission PAUSED — hovering in place")
                    },
                    onResume = {
                        scope.launch { uas.resumeMission() }
                        toast("Mission RESUMED")
                    },
                    onRtl = {
                        scope.launch { uas.returnToLaunch() }
                        toast("RTL — returning to launch")
                    },
                    onEmergencyStop = {
                        scope.launch { uas.emergencyStop() }
                        toast("EMERGENCY STOP — motors cut")
                    },
                )
            }
        }

        soy.engindearing.omnitak.mobile.ui.components.LassoOverlay(
            active = lassoMode,
            mapboxMap = mapboxMap,
            markers = (contacts.values).map { c ->
                soy.engindearing.omnitak.mobile.domain.LassoMarker(
                    id = c.uid,
                    coordinate = soy.engindearing.omnitak.mobile.domain.LassoLatLng(
                        latitude = c.lat,
                        longitude = c.lon,
                    ),
                )
            },
            drawings = lassoDrawings,
            onCompleted = { lassoMode = false },
            onCancelled = { lassoMode = false },
        )

        // Issue #16 — selection pill. Surfaces "N selected" whenever
        // there's an active lasso selection. Tap → action sheet.
        if (lassoSelection.totalCount > 0) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 90.dp, end = 16.dp),
                contentAlignment = Alignment.TopEnd,
            ) {
                soy.engindearing.omnitak.mobile.ui.components.LassoSelectionPill(
                    count = lassoSelection.totalCount,
                    onShowActions = { lassoActionsOpen = true },
                )
            }
        }
        if (lassoActionsOpen) {
            // Resolve the selection's UIDs back to the live CoTEvent
            // objects in the contact store. Filters out anything
            // that's already been removed since the lasso closed.
            val selectedEvents = lassoSelection.markerIDs.mapNotNull { contacts[it] }

            soy.engindearing.omnitak.mobile.ui.components.LassoActionsSheet(
                selectionCount = lassoSelection.totalCount,
                onDismiss = { lassoActionsOpen = false },
                onAddToDataPackage = {
                    lassoActionsOpen = false
                    if (selectedEvents.isEmpty()) {
                        toast("Selection is empty")
                    } else {
                        val ctx = appContext
                        scope.launch {
                            val file = withContext(Dispatchers.IO) {
                                soy.engindearing.omnitak.mobile.domain.LassoExporters.writeMissionPackage(
                                    context = ctx,
                                    name = "Lasso selection (${selectedEvents.size})",
                                    events = selectedEvents,
                                )
                            }
                            val shared = soy.engindearing.omnitak.mobile.domain.LassoExporters.shareFile(
                                context = ctx,
                                file = file,
                                mimeType = "application/zip",
                                title = "Lasso data package",
                            )
                            toast(
                                if (shared) "Mission package built · share sheet open"
                                else "Saved package to ${file.parentFile?.name}/${file.name}"
                            )
                        }
                    }
                },
                onUploadToServer = run {
                    val anyEnabledTls = app.serverManager.servers.collectAsState().value
                        .any { it.enabled && it.useTLS && it.certificateName != null }
                    if (!anyEnabledTls || selectedEvents.isEmpty()) {
                        null
                    } else {
                        {
                            lassoActionsOpen = false
                            val ctx = appContext
                            val ts = System.currentTimeMillis()
                            val pkgName = "lasso-${selectedEvents.size}-$ts"
                            scope.launch {
                                val file = withContext(Dispatchers.IO) {
                                    soy.engindearing.omnitak.mobile.domain.LassoExporters
                                        .writeMissionPackage(
                                            context = ctx,
                                            name = pkgName,
                                            events = selectedEvents,
                                        )
                                }
                                val creator = app.userPrefsStore.ensureSelfUid()
                                val outcome = app.missionSyncManager.uploadDataPackage(
                                    zipBytes = file.readBytes(),
                                    filename = file.name,
                                    creatorUid = creator,
                                )
                                val msg = when (outcome) {
                                    is soy.engindearing.omnitak.mobile.domain.UploadOutcome.Hash ->
                                        "Uploaded → ${outcome.serverName} · hash ${outcome.hash.take(10)}…"
                                    soy.engindearing.omnitak.mobile.domain.UploadOutcome.NoServer ->
                                        "No enrolled TAK server to upload to"
                                    is soy.engindearing.omnitak.mobile.domain.UploadOutcome.Failed ->
                                        "Upload failed: ${outcome.reason}"
                                }
                                toast(msg)
                            }
                        }
                    }
                },
                onExportKML = {
                    lassoActionsOpen = false
                    if (selectedEvents.isEmpty()) {
                        toast("Selection is empty")
                    } else {
                        val ctx = appContext
                        scope.launch {
                            val file = withContext(Dispatchers.IO) {
                                soy.engindearing.omnitak.mobile.domain.LassoExporters.writeKml(
                                    context = ctx,
                                    name = "Lasso selection (${selectedEvents.size})",
                                    events = selectedEvents,
                                )
                            }
                            val shared = soy.engindearing.omnitak.mobile.domain.LassoExporters.shareFile(
                                context = ctx,
                                file = file,
                                mimeType = "application/vnd.google-earth.kml+xml",
                                title = "Lasso KML",
                            )
                            toast(
                                if (shared) "KML built · share sheet open"
                                else "Saved KML to ${file.parentFile?.name}/${file.name}"
                            )
                        }
                    }
                },
                onSendToContacts = {
                    lassoActionsOpen = false
                    if (selectedEvents.isEmpty()) {
                        toast("Selection is empty")
                    } else {
                        contactPickerOpen = true
                    }
                },
                onBulkDelete = {
                    val n = lassoSelection.totalCount
                    val mySelfUid = userPrefs.callsign.takeIf { it.isNotBlank() }
                    val selfFixUid = selfFix?.let { "OMNI-${userPrefs.callsign}" }
                    val targetEvents = selectedEvents
                    // Self-marker guard: never accidentally delete our
                    // own CoT — that would propagate "delete me" to
                    // every peer.
                    val protected = setOfNotNull(mySelfUid, selfFixUid)
                    val toRemove = targetEvents.filterNot { it.uid in protected }

                    // 1) Local removal — ContactStore is the source of
                    //    truth for the map renderer; drop them
                    //    immediately so the operator sees the result.
                    toRemove.forEach { app.contactStore.remove(it.uid) }
                    // 2) Drawings: project selection UUIDs back to the
                    //    drawing.id String via the side-map built when
                    //    we adapted Drawings → LassoDrawings.
                    val deletedDrawings = lassoSelection.drawingIDs
                        .mapNotNull { lassoDrawingIdMap[it] }
                    deletedDrawings.forEach { app.drawingStore.remove(it) }
                    // 3) Broadcast — for each deleted marker, fire a
                    //    t-x-d-d tombstone on the active server so
                    //    other EUDs propagate the removal. Best-effort:
                    //    if the connection is down the local removal
                    //    still stands.
                    scope.launch {
                        val senderUid = mySelfUid ?: "OMNI-${java.util.UUID.randomUUID()}"
                        toRemove.forEach { e ->
                            val xml = soy.engindearing.omnitak.mobile.domain.CotBuilders
                                .buildDeleteEvent(targetUid = e.uid, senderUid = senderUid)
                            runCatching { app.serverManager.sendCoT(xml) }
                        }
                    }
                    lassoService.clear()
                    lassoActionsOpen = false
                    val drawingNote = if (deletedDrawings.isNotEmpty())
                        " + ${deletedDrawings.size} drawing(s)" else ""
                    toast(
                        if (toRemove.size == targetEvents.size)
                            "Deleted ${toRemove.size} marker(s)$drawingNote + broadcast"
                        else
                            "Deleted ${toRemove.size}/$n (self skipped)$drawingNote"
                    )
                },
                onClear = {
                    lassoService.clear()
                    lassoActionsOpen = false
                },
            )
        }
        if (contactPickerOpen) {
            // Snapshot the events the lasso captured AND the rest of
            // ContactStore so the picker has someone to send TO. We
            // exclude the selection itself — sending markers to
            // themselves makes no sense.
            val selected = lassoSelection.markerIDs
            soy.engindearing.omnitak.mobile.ui.components.ContactPickerDialog(
                title = "Send ${lassoSelection.totalCount} item(s) to…",
                candidates = contacts.values.toList(),
                excludeUids = selected,
                onDismiss = { contactPickerOpen = false },
                onConfirm = { destUids ->
                    contactPickerOpen = false
                    if (destUids.isEmpty()) {
                        toast("No recipients selected")
                        return@ContactPickerDialog
                    }
                    val selectedEvents = selected.mapNotNull { contacts[it] }
                    scope.launch {
                        var sent = 0
                        selectedEvents.forEach { e ->
                            val xml = soy.engindearing.omnitak.mobile.domain.CotBuilders
                                .rebuildEvent(e, destUids.toList())
                            if (runCatching { app.serverManager.sendCoT(xml) }
                                    .getOrDefault(false)
                            ) sent++
                        }
                        toast("Sent $sent/${selectedEvents.size} → ${destUids.size} recipient(s)")
                    }
                },
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.TopCenter),
        ) {
            ATAKStatusBar(
                serverName = headerLabel,
                isConnected = connState is ConnectionState.Connected,
                messagesReceived = msgReceived,
                messagesSent = msgSent,
                gpsAccuracyMeters = selfFix?.accuracyM?.toInt() ?: 0,
                timeLabel = nowLabel,
                // GAP-102 — wire the previously dead status-bar taps to
                // their natural destinations. Server icon goes to the
                // Servers tab (also helps GAP-105 — server auth lives
                // there). Hamburger goes to Settings.
                onServerTap = { onOpenTab("servers") },
                onMenuTap = { onOpenTab("settings") },
                // One flag per enabled server → "N/M ●●●" multi-server badge.
                serverConnectedFlags = allServers
                    .filter { it.enabled }
                    .map { connectedIds.contains(it.id) },
            )
        }

        // PPLI self-position card — bottom-right, mirrors iOS layout.
        // GAP-030b — coordinates pull from FusedLocationProviderClient
        // (LocationProvider). Until a fix arrives we show "Acquiring
        // fix…" so users in Germany don't see San Francisco (issue #10).
        if (callsignCardVisible) {
            val fix = selfFix
            SelfPositionCard(
                callsign = userPrefs.callsign,
                coordinateLabel = if (fix != null) {
                    soy.engindearing.omnitak.mobile.data.CoordFormatter.position(
                        fix.lat, fix.lon, userPrefs.coordFormat,
                    )
                } else {
                    "Acquiring fix…"
                },
                altitudeLabel = soy.engindearing.omnitak.mobile.data.CoordFormatter.altitude(
                    fix?.altitudeM ?: 0.0, userPrefs.distanceUnit,
                ),
                speedLabel = soy.engindearing.omnitak.mobile.data.CoordFormatter.speed(
                    fix?.speedKmh ?: 0.0, userPrefs.distanceUnit,
                ),
                accuracyLabel = soy.engindearing.omnitak.mobile.data.CoordFormatter.accuracy(
                    fix?.accuracyM?.toInt() ?: 0, userPrefs.distanceUnit,
                ),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 96.dp),
            )
        }

        ToolsDrawer(
            tools = listOf(
                ToolEntry("draw", Icons.Filled.Brush, "Drawing"),
                ToolEntry("measure", Icons.Filled.Straighten, "Measure"),
                ToolEntry("layers", Icons.Filled.Layers, "Layers"),
                ToolEntry("adsb", Icons.Filled.Flight, if (adsbActive) "ADSB on" else "ADSB"),
                ToolEntry("chat", Icons.Filled.Chat, "Chat"),
                ToolEntry("missionsync", Icons.Filled.Sync, "Mission Sync"),
                ToolEntry("fema", Icons.Filled.LocalFireDepartment, "FEMA / IC"),
                ToolEntry("teams", Icons.Filled.Groups, "Teams"),
                ToolEntry(
                    "nav",
                    Icons.Filled.Navigation,
                    if (followMeActive) "Follow on" else "Follow",
                ),
            ),
            onSelect = { tool ->
                when (tool.id) {
                    "measure" -> {
                        measurementActive = true
                        measurementPoints = emptyList()
                        toast("Measure mode — tap map to add points")
                    }
                    "draw" -> drawingPickerOpen = true
                    "layers" -> layersSheetOpen = true
                    "adsb" -> {
                        if (adsbActive) {
                            adsbService.stop()
                            toast("ADSB off")
                        } else {
                            // Bay Area box until we plumb the live camera
                            // center through — matches the emulator's
                            // default Mountain View GPS so aircraft stay
                            // on-screen during dev.
                            adsbService.start(
                                centerLat = 37.42,
                                centerLon = -122.08,
                                halfWidthDegrees = 2.5,
                            )
                            toast("ADSB on — polling OpenSky every 15s")
                        }
                    }
                    "chat" -> onOpenTab("chat")
                    "missionsync" -> onOpenTab("missionsync")
                    "fema" -> {
                        val lat = app.mapCameraStore.lastTargetLat
                        val lon = app.mapCameraStore.lastTargetLon
                        femaPaletteLatLng = if (lat != null && lon != null) LatLng(lat, lon)
                        else selfFix?.let { LatLng(it.lat, it.lon) } ?: FALLBACK_GLOBAL_VIEW
                    }
                    "teams" -> teamsPanelOpen = true
                    "nav" -> {
                        if (!locationGranted) {
                            toast("Follow-me needs location permission")
                        } else {
                            val next = !followMeActive
                            mutatePref { it.copy(followMeActive = next) }
                            toast(if (next) "Follow me ON" else "Follow me OFF")
                        }
                    }
                }
            },
            modifier = Modifier.align(Alignment.BottomEnd),
        )

        // Map control stack — zoom in / zoom out / center-on-me — at the
        // BottomStart corner so it stays reachable one-handed without
        // opening the tools drawer. Mirrors the iOS map controls layout.
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 110.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            MapControlFab(
                icon = Icons.Filled.Add,
                contentDescription = "Zoom in",
                tint = TacticalAccent,
                onClick = { zoomInTick++ },
            )
            MapControlFab(
                icon = Icons.Filled.Remove,
                contentDescription = "Zoom out",
                tint = TacticalAccent,
                onClick = { zoomOutTick++ },
            )
            MapControlFab(
                icon = Icons.Filled.MyLocation,
                contentDescription = "Center on me",
                tint = if (locationGranted) TacticalAccent else androidx.compose.ui.graphics.Color.Gray,
                enabled = locationGranted,
                onClick = { recenterTick++ },
            )
            // Recenter on the drone — only when UAS is connected with a
            // fix. Tap to jump camera to drone's current position at the
            // operator's current zoom (don't change zoom — pilot may have
            // intentionally zoomed for context).
            if (droneState.isConnected() && droneState.latDeg != null && droneState.lonDeg != null) {
                MapControlFab(
                    icon = Icons.Filled.FlightTakeoff,
                    contentDescription = "Center on drone",
                    tint = androidx.compose.ui.graphics.Color(0xFF00E5FF),
                    onClick = {
                        mapboxMap?.let { m ->
                            val pos = m.cameraPosition
                            m.cameraPosition = org.maplibre.android.camera.CameraPosition.Builder()
                                .target(LatLng(droneState.latDeg!!, droneState.lonDeg!!))
                                .zoom(pos.zoom.coerceAtLeast(15.0))
                                .build()
                        }
                    },
                )
            }
        }

        // Surface a "Fly UAS here" action in the radial menu only when a
        // drone is actually connected — keeps the menu clean for users
        // who never touch a UAS.
        val uasConnected = droneState.isConnected()
        RadialMenu(
            visible = radialAnchor != null,
            anchor = radialAnchor ?: Offset.Zero,
            actions = buildList {
                add(RadialAction("drop", Icons.Filled.Place, "Drop Marker"))
                add(RadialAction("measure", Icons.Filled.Straighten, "Measure"))
                add(RadialAction("nav", Icons.Filled.Navigation, "Navigate"))
                add(RadialAction("layers", Icons.Filled.Layers, "Layers"))
                add(RadialAction("copy", Icons.Filled.LocationOn, "Copy Coords"))
                if (uasConnected) {
                    // Ground/surface vehicles drive rather than fly — swap
                    // the verb so the menu reads naturally for a UGV/USV.
                    val isGround = droneState.vehicleClass ==
                        soy.engindearing.omnitak.mobile.data.uas.VehicleClass.GROUND ||
                        droneState.vehicleClass ==
                        soy.engindearing.omnitak.mobile.data.uas.VehicleClass.SURFACE
                    val goVerb = if (isGround) "Drive Here" else "Fly UAS Here"
                    add(RadialAction("uas_fly_here", Icons.Filled.FlightTakeoff, goVerb))
                    add(RadialAction("uas_waypoint", Icons.Filled.AddLocation, "Add Waypoint"))
                    add(RadialAction("uas_orbit", Icons.Filled.Refresh, "Orbit Here"))
                    add(RadialAction("uas_circle", Icons.Filled.RadioButtonUnchecked, "Circle Mission"))
                    add(RadialAction("uas_survey", Icons.Filled.GridOn, "Survey Area"))
                }
                add(RadialAction("center", Icons.Filled.Explore, "Center"))
                add(RadialAction("add", Icons.Filled.Add, "Add"))
            },
            onSelect = { action ->
                val ll = radialLatLng
                radialAnchor = null
                radialLatLng = null
                when (action.id) {
                    "drop" -> if (ll != null) markerSheetLatLng = ll
                    "layers" -> layersSheetOpen = true
                    "uas_fly_here" -> if (ll != null) {
                        // MAV_CMD_DO_REPOSITION at the operator's cruise
                        // altitude, after a TAK Terrain safety check.
                        // Result is surfaced as a toast — blocked
                        // commands include the exact clearance number
                        // so the operator knows what to change.
                        scope.launch {
                            val result = uas.flyTo(ll.latitude, ll.longitude)
                            val msg = when (result) {
                                is soy.engindearing.omnitak.mobile.domain.UASManager.FlyHereResult.Sent ->
                                    if (result.clearance != null)
                                        "UAS → ${"%.4f, %.4f".format(ll.latitude, ll.longitude)} " +
                                            "(${result.targetMsl.toInt()}m MSL, ${result.clearance.toInt()}m AGL)"
                                    else
                                        "UAS → ${"%.4f, %.4f".format(ll.latitude, ll.longitude)} (${result.targetMsl.toInt()}m MSL)"
                                is soy.engindearing.omnitak.mobile.domain.UASManager.FlyHereResult.WouldHitTerrain ->
                                    "BLOCKED: target ${result.targetMsl.toInt()}m would clip terrain at " +
                                        "${result.terrainMsl.toInt()}m (clearance ${result.clearance.toInt()}m). Raise cruise alt."
                                is soy.engindearing.omnitak.mobile.domain.UASManager.FlyHereResult.OutsideGeofence ->
                                    "BLOCKED: ${result.distanceFromHome.toInt()} m from home — exceeds ${result.maxAllowed} m geofence"
                                soy.engindearing.omnitak.mobile.domain.UASManager.FlyHereResult.NoGpsFix ->
                                    "UAS has no GPS fix yet — wait for telemetry"
                                soy.engindearing.omnitak.mobile.domain.UASManager.FlyHereResult.NotConnected ->
                                    "UAS link down — reconnect first"
                            }
                            toast(msg)
                        }
                    }
                    "uas_waypoint" -> if (ll != null) {
                        // Seed the mission with this waypoint AND enter
                        // missionMode so further taps keep dropping pins.
                        uas.missionStore.addWaypoint(ll.latitude, ll.longitude)
                        missionMode = true
                        toast("WP${mission.waypoints.size + 1} dropped — tap more or hit Upload")
                    }
                    "uas_orbit" -> if (ll != null) {
                        // MAV_CMD_DO_ORBIT at cruise altitude, default 50 m radius.
                        // Fast-follow: radius slider on the orbit action.
                        scope.launch { uas.orbitPoint(ll.latitude, ll.longitude) }
                        toast("Orbiting ${"%.4f, %.4f".format(ll.latitude, ll.longitude)} @ 50 m radius")
                    }
                    "uas_circle" -> if (ll != null) {
                        // Persistent circle as a real mission upload —
                        // survives mode changes / link blips.
                        scope.launch {
                            uas.uploadCircleMission(ll.latitude, ll.longitude)
                        }
                        toast("Circle mission uploading — 12 pts @ 50 m radius")
                    }
                    "uas_survey" -> if (ll != null) {
                        // Lawnmower over a 200 m × 200 m box centred on the
                        // tapped point. Fast-follow: box-size slider.
                        scope.launch {
                            uas.uploadSurveyMission(ll.latitude, ll.longitude)
                        }
                        toast("Survey mission uploading — 200 m box, 30 m spacing")
                    }
                    else -> {
                        // Respect the operator's coordinate-format pref (Lat/Lon,
                        // DMS, MGRS, UTM) so the "Add @ …" toast matches the
                        // SelfPositionCard readout instead of always lat/lon.
                        val coord = ll?.let {
                            soy.engindearing.omnitak.mobile.data.CoordFormatter.position(
                                it.latitude, it.longitude, userPrefs.coordFormat,
                            )
                        } ?: ""
                        toast("${action.label}${if (coord.isNotEmpty()) " @ $coord" else ""}")
                    }
                }
            },
            onDismiss = {
                radialAnchor = null
                radialLatLng = null
            },
        )

        MarkerEditSheet(
            visible = markerSheetLatLng != null,
            latLng = markerSheetLatLng,
            initialCallsign = editingMarker?.callsign ?: "Marker ${contacts.size + 1}",
            initialAffiliation = editingMarker?.affiliation ?: CoTAffiliation.FRIEND,
            initialAltitude = editingMarker?.hae?.takeIf { it != 0.0 },
            initialRemarks = editingMarker?.remarks ?: "",
            editing = editingMarker != null,
            onSave = { result ->
                val ll = markerSheetLatLng
                if (ll != null) {
                    val uid = editingMarker?.uid ?: "local-${System.currentTimeMillis()}"
                    app.contactStore.ingest(
                        CoTEvent(
                            uid = uid,
                            type = "a-${result.affiliation.code}-G-U-C",
                            lat = ll.latitude,
                            lon = ll.longitude,
                            hae = result.altitudeMeters ?: 0.0,
                            callsign = result.callsign,
                            remarks = result.remarks,
                        )
                    )
                    val verb = if (editingMarker != null) "Updated" else "Saved"
                    toast("$verb ${result.affiliation.name.lowercase()} marker “${result.callsign}”")
                }
                markerSheetLatLng = null
                editingMarker = null
            },
            onDelete = editingMarker?.let {
                {
                    app.contactStore.remove(it.uid)
                    toast("Deleted marker “${it.callsign ?: it.uid}”")
                    markerSheetLatLng = null
                    editingMarker = null
                }
            },
            onPursueWithUas = editingMarker?.takeIf { droneState.isConnected() }?.let { mk ->
                {
                    val uid = mk.uid
                    val callsign = mk.callsign ?: uid.takeLast(6)
                    scope.launch {
                        val r = uas.startPursueContact(uid, callsign) {
                            // Re-read each tick so we track the contact as it moves.
                            val live = app.contactStore.contacts.value[uid] ?: return@startPursueContact null
                            live.lat to live.lon
                        }
                        val msg = when (r) {
                            soy.engindearing.omnitak.mobile.domain.UASManager.FollowMeResult.Started ->
                                "Pursuing “$callsign” at ${uas.cruiseAlt.value.meters.toInt()} m above terrain"
                            soy.engindearing.omnitak.mobile.domain.UASManager.FollowMeResult.NoGpsFix ->
                                "Contact has no position fix"
                            soy.engindearing.omnitak.mobile.domain.UASManager.FollowMeResult.NotConnected ->
                                "UAS link down"
                            is soy.engindearing.omnitak.mobile.domain.UASManager.FollowMeResult.AutopilotNotSupported ->
                                "Pursue requires PX4 (autopilot=${r.autopilot ?: "unknown"})"
                        }
                        toast(msg)
                    }
                    markerSheetLatLng = null
                    editingMarker = null
                }
            },
            onDismiss = {
                markerSheetLatLng = null
                editingMarker = null
            },
        )

        // "Go to Coordinate" — TWD97 (7+7 / 5+5) / MGRS / Lat-Lon entry.
        if (coordEntryOpen) {
            soy.engindearing.omnitak.mobile.ui.components.CoordinateEntrySheet(
                // Reference for 5+5 TWD97: last camera centre, else self-fix.
                refLat = app.mapCameraStore.lastTargetLat ?: selfFix?.lat,
                refLon = app.mapCameraStore.lastTargetLon ?: selfFix?.lon,
                onGo = { lat, lon, drop ->
                    coordEntryOpen = false
                    panTarget = LatLng(lat, lon)
                    panTargetTick += 1
                    if (followMeActive) mutatePref { it.copy(followMeActive = false) }
                    if (drop) {
                        val uid = "local-${System.currentTimeMillis()}"
                        app.contactStore.ingest(
                            CoTEvent(
                                uid = uid,
                                type = "a-f-G-U-C",
                                lat = lat,
                                lon = lon,
                                hae = 0.0,
                                callsign = "Marker ${contacts.size + 1}",
                                remarks = "",
                            )
                        )
                    }
                    toast(
                        if (drop) "Dropped marker at %.5f, %.5f".format(lat, lon)
                        else "Panning to %.5f, %.5f".format(lat, lon)
                    )
                },
                onDismiss = { coordEntryOpen = false },
            )
        }

        soy.engindearing.omnitak.mobile.ui.components.FemaMarkerPaletteSheet(
            visible = femaPaletteLatLng != null,
            latLng = femaPaletteLatLng,
            onConfirm = { picked ->
                val ll = femaPaletteLatLng
                if (ll != null) {
                    val uid = "local-fema-${System.currentTimeMillis()}"
                    app.contactStore.ingest(
                        soy.engindearing.omnitak.mobile.data.CoTEvent(
                            uid = uid,
                            type = picked.icon.cotType,
                            lat = ll.latitude,
                            lon = ll.longitude,
                            hae = 0.0,
                            callsign = picked.name,
                            remarks = picked.remarks,
                            iconsetPath = picked.icon.iconsetPath,
                        )
                    )
                    // Broadcast to every enabled server so peers with the
                    // FEMA catalog see the right glyph; receivers without
                    // it fall back to the friendly-installation render
                    // ATAK/iTAK does for `a-f-G-I-*`.
                    scope.launch {
                        val xml = soy.engindearing.omnitak.mobile.domain.CotBuilders
                            .rebuildEvent(
                                soy.engindearing.omnitak.mobile.data.CoTEvent(
                                    uid = uid,
                                    type = picked.icon.cotType,
                                    lat = ll.latitude,
                                    lon = ll.longitude,
                                    callsign = picked.name,
                                    remarks = picked.remarks,
                                    iconsetPath = picked.icon.iconsetPath,
                                ),
                                destUids = emptyList(),
                            )
                        runCatching { app.serverManager.sendCoT(xml) }
                    }
                    toast("Dropped ${picked.icon.label} “${picked.name}”")
                }
                femaPaletteLatLng = null
            },
            onDismiss = { femaPaletteLatLng = null },
        )

        if (drawingKind != null) {
            DrawingOverlay(
                kind = drawingKind!!,
                pointCount = drawingPoints.size,
                onUndo = {
                    if (drawingPoints.isNotEmpty()) {
                        drawingPoints = drawingPoints.dropLast(1)
                    }
                },
                onCancel = {
                    drawingKind = null
                    drawingPoints = emptyList()
                },
                onFinish = {
                    val minPts = when (drawingKind!!) {
                        DrawingKind.LINE -> 2
                        DrawingKind.POLYGON -> 3
                        DrawingKind.CIRCLE -> 2
                    }
                    if (drawingPoints.size >= minPts) {
                        app.drawingStore.add(
                            Drawing(
                                id = "draw-${System.currentTimeMillis()}",
                                kind = drawingKind!!,
                                points = drawingPoints.map { it.latitude to it.longitude },
                            )
                        )
                        toast("Saved ${drawingKind!!.name.lowercase()}")
                    } else {
                        toast("Need at least $minPts points")
                    }
                    drawingKind = null
                    drawingPoints = emptyList()
                },
                modifier = Modifier.align(Alignment.TopStart),
            )
        }

        if (drawingPickerOpen) {
            DrawingKindPicker(
                onPick = { kind ->
                    drawingPickerOpen = false
                    drawingKind = kind
                    drawingPoints = emptyList()
                    toast("Drawing ${kind.name.lowercase()} — tap to add points")
                },
                onDismiss = { drawingPickerOpen = false },
            )
        }

        if (measurementActive) {
            MeasurementOverlay(
                points = measurementPoints,
                onUndo = {
                    if (measurementPoints.isNotEmpty()) {
                        measurementPoints = measurementPoints.dropLast(1)
                    }
                },
                onClose = {
                    measurementActive = false
                    measurementPoints = emptyList()
                },
                modifier = Modifier.align(Alignment.TopStart),
            )
        }

        if (layersSheetOpen) {
            LayersDialog(
                gridEnabled = gridEnabled,
                drawingsVisible = drawingsVisible,
                aircraftVisible = aircraftVisible,
                contactsVisible = contactsVisible,
                callsignCardVisible = callsignCardVisible,
                meshNodesVisible = meshNodesVisible,
                map3dEnabled = map3dEnabled,
                onToggleGrid = { v -> mutatePref { it.copy(gridEnabled = v) } },
                onToggleDrawings = { v -> mutatePref { it.copy(drawingsVisible = v) } },
                onToggleAircraft = { v -> mutatePref { it.copy(aircraftVisible = v) } },
                onToggleContacts = { v -> mutatePref { it.copy(contactsVisible = v) } },
                onToggleCallsignCard = { v -> mutatePref { it.copy(callsignCardVisible = v) } },
                onToggleMeshNodes = { v ->
                    scope.launch { app.userPrefsStore.setMeshNodesLayerVisible(v) }
                },
                onToggle3d = { v -> mutatePref { it.copy(map3dEnabled = v) } },
                onDismiss = { layersSheetOpen = false },
            )
        }

        if (teamsPanelOpen) {
            ContactsPanel(
                contacts = contacts.values.toList(),
                onSelect = { c ->
                    panTarget = LatLng(c.lat, c.lon)
                    panTargetTick += 1
                    if (followMeActive) mutatePref { it.copy(followMeActive = false) }
                    teamsPanelOpen = false
                    toast("Panning to ${c.callsign ?: c.uid}")
                },
                onDismiss = { teamsPanelOpen = false },
            )
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

private fun buildInProgressDrawing(kind: DrawingKind?, points: List<LatLng>): List<Drawing> {
    if (kind == null || points.isEmpty()) return emptyList()
    return listOf(
        Drawing(
            id = "__in_progress__",
            kind = kind,
            points = points.map { it.latitude to it.longitude },
            colorHex = "#FFC107",  // amber while drafting
        )
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DrawingKindPicker(
    onPick: (DrawingKind) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = soy.engindearing.omnitak.mobile.ui.theme.TacticalSurface,
        title = { androidx.compose.material3.Text("Drawing tool", color = MaterialTheme.colorScheme.onBackground) },
        text = {
            androidx.compose.foundation.layout.Column {
                listOf(
                    DrawingKind.LINE to "Line — connected segments",
                    DrawingKind.POLYGON to "Polygon — closed shape",
                    DrawingKind.CIRCLE to "Circle — center + edge",
                ).forEach { (kind, label) ->
                    androidx.compose.material3.TextButton(
                        onClick = { onPick(kind) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        androidx.compose.material3.Text(
                            label,
                            color = TacticalAccent,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                androidx.compose.material3.Text("Cancel", color = TacticalAccent)
            }
        },
    )
}

@Composable
private fun DrawingOverlay(
    kind: DrawingKind,
    pointCount: Int,
    onUndo: () -> Unit,
    onCancel: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier
            .padding(top = 76.dp, start = 12.dp, end = 12.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(TacticalBackground.copy(alpha = 0.9f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Text(
            "${kind.name.lowercase().replaceFirstChar { it.uppercase() }} · $pointCount pt",
            color = TacticalAccent,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
        )
        androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
        androidx.compose.material3.TextButton(onClick = onUndo, enabled = pointCount > 0) {
            androidx.compose.material3.Text("Undo", color = TacticalAccent)
        }
        androidx.compose.material3.TextButton(onClick = onCancel) {
            androidx.compose.material3.Text("Cancel", color = TacticalAccent)
        }
        androidx.compose.material3.TextButton(onClick = onFinish) {
            androidx.compose.material3.Text("Save", color = TacticalAccent)
        }
    }
}

@Composable
private fun MeasurementOverlay(
    points: List<LatLng>,
    onUndo: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var totalMeters = 0.0
    for (i in 1 until points.size) {
        totalMeters += GeoMath.haversineMeters(
            points[i - 1].latitude, points[i - 1].longitude,
            points[i].latitude, points[i].longitude,
        )
    }
    val bearing = if (points.size >= 2) {
        val a = points[points.size - 2]
        val b = points[points.size - 1]
        GeoMath.bearingDegrees(a.latitude, a.longitude, b.latitude, b.longitude)
    } else null

    androidx.compose.foundation.layout.Row(
        modifier = modifier
            .padding(top = 76.dp, start = 12.dp, end = 12.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(TacticalBackground.copy(alpha = 0.9f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Text(
            buildString {
                append("${points.size} pt · ")
                append(GeoMath.formatDistance(totalMeters))
                if (bearing != null) append(" · ${GeoMath.formatBearing(bearing)}")
            },
            color = TacticalAccent,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
        )
        androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
        androidx.compose.material3.TextButton(onClick = onUndo, enabled = points.isNotEmpty()) {
            androidx.compose.material3.Text("Undo", color = TacticalAccent)
        }
        androidx.compose.material3.TextButton(onClick = onClose) {
            androidx.compose.material3.Text("Done", color = TacticalAccent)
        }
    }
}

private fun timeLabel(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

/**
 * Standard tactical map control button — translucent dark disc with a
 * tactical-accent glyph. Used for zoom +/− and center-on-me; sized to
 * the same 48dp pip so the column reads as a single control stack.
 */
@Composable
private fun MapControlFab(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(TacticalBackground.copy(alpha = 0.9f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint)
    }
}
