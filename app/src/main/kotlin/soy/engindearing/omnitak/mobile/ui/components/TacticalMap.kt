package soy.engindearing.omnitak.mobile.ui.components

import android.annotation.SuppressLint
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleEventObserver
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.CameraMode
import soy.engindearing.omnitak.mobile.R
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.Drawing
import soy.engindearing.omnitak.mobile.data.MapProvider
import soy.engindearing.omnitak.mobile.data.SelfFix
import soy.engindearing.omnitak.mobile.data.SelfFixPersistence
import soy.engindearing.omnitak.mobile.data.TakTeamColor

/**
 * MapLibre-backed map surface. Forwards Android lifecycle events to the
 * native MapView — skipping those leaks native GL resources or crashes
 * on rotation.
 *
 * [onMapLongPress] emits the geographic LatLng of the long-press, along
 * with the on-screen pixel offset so overlays (e.g. radial menu) can
 * anchor to the touch point.
 *
 * [locationEnabled] activates MapLibre's built-in LocationComponent —
 * a blue dot for the user's position and a compass arrow for heading.
 * The caller is responsible for ensuring runtime location permission
 * is granted before flipping this to true.
 *
 * #210 - [selfMarkerVisible] = false keeps the component off even when
 * location is available (the operator hid their own marker; UI only,
 * reporting is unaffected). Follow-me and "Center on me" then move the camera
 * straight from [selfFix] instead of through the component's tracking modes.
 */
@Composable
fun TacticalMap(
    initialCenter: LatLng = LatLng(0.0, 0.0),  // neutral world default — callers should pass a real center
    initialZoom: Double = 2.0,
    /** Initial camera rotation (degrees clockwise from north). #78 — the
     *  3D→2D engine switch hands the globe's heading back through this so
     *  the rotation survives the swap; cold start keeps the north-up
     *  default. */
    initialBearing: Double = 0.0,
    styleJson: String = TACTICAL_STYLE_DARK_MATTER,
    onMapLongPress: ((LatLng, Offset) -> Unit)? = null,
    onContactTap: ((CoTEvent) -> Unit)? = null,
    onMapSingleTap: ((LatLng) -> Boolean)? = null,
    locationEnabled: Boolean = false,
    recenterTrigger: Any? = null,
    zoomInTrigger: Int = 0,
    zoomOutTrigger: Int = 0,
    contacts: Collection<CoTEvent> = emptyList(),
    measurementPoints: List<LatLng> = emptyList(),
    drawings: List<Drawing> = emptyList(),
    gridCenter: LatLng? = null,
    // NOTE: ADS-B aircraft are no longer plumbed through TacticalMap. They are
    // rendered by the ADS-B plugin's map overlay (:plugins:example-adsb), which
    // feeds the `aircraft-src` GeoJSON source baked into the style JSON below
    // via the live MapLibreMap handle. The source + `aircraft-circle` /
    // `aircraft-label` layers stay in this module's style as MapLibre *style
    // infrastructure* — see buildTacticalStyle and AircraftLayer's contract.
    panTarget: LatLng? = null,
    panTargetTick: Int = 0,
    followMeActive: Boolean = false,
    /** 3D terrain mode — tilts the camera so the DEM relief baked into
     *  the style JSON renders dimensionally. The style itself carries
     *  the terrain source; this flag drives the camera pitch. */
    terrain3d: Boolean = false,
    /** Render self-position with the MIL-STD-2525 friendly-combat
     *  frame. When false, falls back to the legacy `ic_self_marker`
     *  tinted disc. Sourced from [soy.engindearing.omnitak.mobile.data.UserPrefs.useMilStdSelfSymbol]. */
    useMilStdSelfSymbol: Boolean = true,
    /** #83 — render the self-marker as a triangle pointing in heading direction. */
    selfMarkerTriangle: Boolean = false,
    /** TAK team name used to tint the self-marker — e.g. "Cyan", "Red",
     *  "Orange". Resolved via [TakTeamColor.forName]; unrecognised names
     *  fall back to cyan (0xFF00FFFF) to match CivTAK's default for
     *  unaffiliated friendlies. Sourced from [soy.engindearing.omnitak.mobile.data.UserPrefs.team]. */
    selfTeamColor: String = "Cyan",
    /** #159 — operator echelon code ([soy.engindearing.omnitak.mobile.data.Echelon.code],
     *  or "" = none). Its APP-6 amplifier (•, ••, I, II, XX, …) draws above the
     *  self symbol. Sourced from [soy.engindearing.omnitak.mobile.data.UserPrefs.echelon]. */
    selfEchelon: String = "",
    /** Issue #75 — the operator's current/last-known fix from
     *  [soy.engindearing.omnitak.mobile.data.LocationProvider]. Forwarded
     *  into the LocationComponent so the self-marker renders immediately
     *  from a restored fix on cold start (dimmed when stale) and snaps to
     *  the forced foreground-resume fix without waiting on the
     *  component's internal engine interval. */
    selfFix: SelfFix? = null,
    /** #210 - draw the operator's own marker (the LocationComponent puck).
     *  False hides it and keeps the component off, UI only. Sourced from
     *  [soy.engindearing.omnitak.mobile.data.UserPrefs.selfMarkerVisible]. */
    selfMarkerVisible: Boolean = true,
    /** Camera idle: target, zoom, bearing (degrees clockwise from north).
     *  Bearing feeds the #78 engine-switch viewport handoff. */
    onCameraIdle: ((LatLng, Double, Double) -> Unit)? = null,
    /** Issue #95 — when true, disable rotate gestures and snap/hold the
     *  camera at bearing 0° (north-up). Animated on toggle so it doesn't
     *  feel like a jump. */
    northUpLocked: Boolean = false,
    /** Issue #95/#96 — incremented by the compass tap or north-lock FAB
     *  to animate the camera back to bearing 0° without toggling the lock
     *  setting (useful when lock is off and the operator just wants to
     *  quickly reset orientation). */
    snapNorthTrigger: Int = 0,
    /** Fired once per composition of this map, as soon as the MapLibre map
     *  is ready: at once when the retained view already has one. Issue #16 —
     *  lasso uses this to grab the [MapLibreMap] reference for screen↔geo
     *  projection during freehand selection. */
    onMapReady: ((org.maplibre.android.maps.MapLibreMap) -> Unit)? = null,
    /** Fired after every style (re)load with the live map + style, so the
     *  caller can re-apply style-level content (e.g. KML vector overlays)
     *  that a setStyle wipes. */
    onStyleReady: ((org.maplibre.android.maps.MapLibreMap, Style) -> Unit)? = null,
    /** #82 — called when the operator taps the self-marker. Opens reposition sheet. */
    onSelfMarkerTap: (() -> Unit)? = null,
    /** Issue #89 — system status-bar inset height in dp. Under
     *  enableEdgeToEdge the map draws under the status bar, so the built-in
     *  MapLibre compass margin adds this on top of [COMPASS_TOP_MARGIN_DP]
     *  to clear the (inset-shifted) ATAKStatusBar on tall-notch devices. */
    topInsetDp: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // #177 — the one-time map listeners (tap, long-press, camera-idle, marker,
    // map-ready/style-ready) now read live callbacks from RetainedMapView.bindings
    // (refreshed in the SideEffect below) instead of these composition-scoped
    // holders, so the retained MapView always fires the current composition's
    // callbacks. The current* holders that remain are the ones still consumed by
    // keyed DisposableEffects / AndroidView.update further down.
    //
    // #210 - the puck (LocationComponent) runs only when location is available
    // AND the operator has not hidden their marker. While it is hidden the
    // component is never activated, so follow-me / "Center on me" move the
    // camera from the raw fix instead (see the effects below).
    val puckActive = SelfMarkerVisibility.puckActive(locationEnabled, selfMarkerVisible)
    val cameraFollowsFix = SelfMarkerVisibility.cameraFollowsFixDirectly(locationEnabled, selfMarkerVisible)
    val currentPuckActive by rememberUpdatedState(puckActive)
    val currentContacts by rememberUpdatedState(contacts)
    val currentMeasurementPoints by rememberUpdatedState(measurementPoints)
    val currentDrawings by rememberUpdatedState(drawings)
    val currentGridCenter by rememberUpdatedState(gridCenter)
    val currentStyleReady by rememberUpdatedState(onStyleReady)
    val currentTerrain3d by rememberUpdatedState(terrain3d)
    val currentSelfFix by rememberUpdatedState(selfFix)
    val currentFollowMe by rememberUpdatedState(followMeActive)
    val currentUseMilStd by rememberUpdatedState(useMilStdSelfSymbol)
    val currentTeamColor by rememberUpdatedState(selfTeamColor)
    val currentSelfEchelon by rememberUpdatedState(selfEchelon)
    val currentOnSelfMarkerTap by rememberUpdatedState(onSelfMarkerTap)
    val currentSelfMarkerTriangle by rememberUpdatedState(selfMarkerTriangle)
    // Issue #75 — whether the puck is currently rendered dimmed (stale
    // restored fix). Plain holder, not MutableState: nothing recomposes
    // off it; the effects below read/write it imperatively.
    val puckAppearance = remember { PuckAppearance() }
    // #210 - the recenter trigger's value at first composition, so the hidden-
    // marker "Center on me" fallback only reacts to real taps.
    val initialRecenterTrigger = remember { recenterTrigger }

    // #177 — keep the live callbacks the retained MapView's one-time listeners
    // call in sync with the current composition. SideEffect runs on every
    // successful recomposition, so a MapView reused after a Settings detour
    // always fires THIS composition's callbacks, never the dead one that first
    // created it. (The state those listeners read is written further down, in
    // an effect that runs before the others.)
    val bindings = RetainedMapView.bindings
    // This composition's claim on the retained view. The view outlives the
    // Activity, and when an Activity is finished and opened again the new one
    // is created before the old one has been stopped and destroyed, so for a
    // moment two compositions hold a reference to the view. Only the one that
    // took it last may drive it.
    val holder = remember { Any() }
    SideEffect {
        if (!RetainedMapView.isHeldBy(holder)) return@SideEffect
        bindings.onMapReady = onMapReady
        bindings.onStyleReady = onStyleReady
        bindings.onCameraIdle = onCameraIdle
        bindings.onLongPress = onMapLongPress
        bindings.onMapSingleTap = onMapSingleTap
        bindings.onContactTap = onContactTap
        bindings.onSelfMarkerTap = onSelfMarkerTap
    }

    // #177 — retain the native MapView across navigation so returning to the map
    // (e.g. from Settings) doesn't tear down and re-initialise the MapLibre
    // engine. acquire() reuses the existing instance (detaching it from any prior
    // parent first) or builds it once. The view is built with the application
    // context so that it is not tied to an Activity.
    val appContext = context.applicationContext
    // Whether this composition is the one that built the view. The view tells
    // its builder when the map is ready; every later composition has to be told
    // separately (see the effect below the factory).
    val builtHere = remember { booleanArrayOf(false) }
    val mapView = remember {
        RetainedMapView.acquire(appContext, holder) { ctx ->
            builtHere[0] = true
            MapLibre.getInstance(ctx)
            MapView(ctx).apply {
                onCreate(null)
                getMapAsync { map ->
                    // Issue #16 — hand the map reference up to the caller
                    // so things like the lasso overlay can call
                    // map.projection.fromScreenLocation during drags.
                    bindings.onMapReady?.invoke(map)
                    map.cameraPosition = CameraPosition.Builder()
                    .target(initialCenter)
                    .zoom(initialZoom)
                    .bearing(initialBearing)
                    .build()
                map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                    // #77: contacts render via native annotations (ContactMarkerRenderer),
                    // not the contacts-src GeoJsonSource circle/symbol layers, which the
                    // GL driver silently fails to paint on Adreno/Mali/emulator (they show
                    // on Cesium but never on 2D). The Annotation path paints everywhere.
                    ContactMarkerRenderer.update(map, context, bindings.contacts)
                    MeasurementLayer.update(map, currentMeasurementPoints)
                    DrawingShapeRenderer.apply(map, currentDrawings)
                    currentGridCenter?.let { GridLayer.update(map, it) }
                    // #206 — clamp the camera to what this basemap actually
                    // serves so pinching in past a provider's real ceiling
                    // can't land on a blank/placeholder tile. MBTiles
                    // overlays aren't known here (TacticalMap doesn't take
                    // them as a parameter) — MapScreen's own
                    // LaunchedEffect(mbtilesOverlays, mapProvider, ...)
                    // re-widens the clamp for those right after cold start.
                    extractBasemapMaxZoom(styleJson)?.let { basemapMax ->
                        map.setMaxZoomPreference(zoomClampFor(basemapMax, emptyList()))
                    }
                    // ADS-B aircraft are fed by the ADS-B plugin's overlay via
                    // the live map handle — not from here. The `aircraft-src`
                    // source stays empty until the plugin pushes into it.
                    if (bindings.puckActive) {
                        activateLocation(
                            map, style, context, currentUseMilStd, currentTeamColor,
                            seedFix = bindings.selfFix, puck = puckAppearance,
                            selfMarkerTriangle = currentSelfMarkerTriangle,
                            echelonAmplifier = echelonAmplifierFor(currentSelfEchelon),
                        )
                    }
                    // Cold-start 3D: if the persisted pref has terrain on,
                    // apply the tilt once the style (+ terrain source) is
                    // loaded. Instant move (no animation) on first paint.
                    if (currentTerrain3d) {
                        map.cameraPosition = CameraPosition.Builder(map.cameraPosition)
                            .tilt(55.0).build()
                    }
                    bindings.onStyleReady?.invoke(map, style)
                }
                map.uiSettings.apply {
                    isCompassEnabled = true
                    isLogoEnabled = false
                    isAttributionEnabled = true
                    // #266 - the view has the application context, and MapLibre's
                    // own attribution dialog cannot be shown from it
                    // (BadTokenException). This one finds the hosting Activity at
                    // the tap.
                    setAttributionDialogManager(HostActivityAttributionDialogManager(ctx, map))
                    // Issue #81 — push the compass below the ATAKStatusBar.
                    // setCompassMargins(left, top, right, bottom) takes raw
                    // pixels; 4 dp matches MapLibre's built-in side defaults
                    // so only the top changes.
                    val density = context.resources.displayMetrics.density
                    // Issue #89 — add the status-bar inset so the compass clears
                    // the ATAKStatusBar after enableEdgeToEdge pushes it down.
                    val compassTopPx =
                        ((COMPASS_TOP_MARGIN_DP + topInsetDp) * density + 0.5f).toInt()
                    val sideMarginPx = (4 * density + 0.5f).toInt()
                    setCompassMargins(sideMarginPx, compassTopPx, sideMarginPx, sideMarginPx)
                }
                // Persist camera state on idle so bottom-nav switches
                // (Map → Settings → Map etc.) don't reset the operator's
                // pan/zoom — see [MapCameraStore].
                map.addOnCameraIdleListener {
                    val pos = map.cameraPosition
                    val target = pos.target ?: return@addOnCameraIdleListener
                    // Issue #95 — if north-up lock is active and the bearing
                    // somehow drifted (edge case: programmatic pan), snap back.
                    if (bindings.northUpLocked && kotlin.math.abs(pos.bearing) > 0.5) {
                        map.animateCamera(
                            CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder(pos).bearing(0.0).build()
                            ),
                            250,
                        )
                        return@addOnCameraIdleListener
                    }
                    bindings.onCameraIdle?.invoke(target, pos.zoom, pos.bearing)
                }
                map.addOnMapLongClickListener { latLng ->
                    val screen = map.projection.toScreenLocation(latLng)
                    bindings.onLongPress?.invoke(latLng, Offset(screen.x, screen.y))
                    true
                }
                map.addOnMapClickListener { latLng ->
                    // Mode-specific tap handler wins when provided
                    // (e.g. measurement mode eats taps to add points).
                    bindings.onMapSingleTap?.let { handler ->
                        if (handler(latLng)) return@addOnMapClickListener true
                    }
                    val cb = bindings.onContactTap ?: return@addOnMapClickListener false
                    val tapPx = map.projection.toScreenLocation(latLng)
                    // #82 — tap on self-marker opens reposition sheet
                    val selfTapCb = bindings.onSelfMarkerTap
                    if (selfTapCb != null) {
                        val fix = bindings.selfFix
                        if (fix != null) {
                            val selfPx = map.projection.toScreenLocation(LatLng(fix.lat, fix.lon))
                            val selfDist = kotlin.math.hypot(
                                (selfPx.x - tapPx.x).toDouble(),
                                (selfPx.y - tapPx.y).toDouble(),
                            )
                            if (selfDist < TAP_HIT_RADIUS_PX) {
                                selfTapCb()
                                return@addOnMapClickListener true
                            }
                        }
                    }
                    var best: CoTEvent? = null
                    var bestDist = Float.MAX_VALUE
                    bindings.contacts.forEach { c ->
                        val px = map.projection.toScreenLocation(LatLng(c.lat, c.lon))
                        val d = kotlin.math.hypot(
                            (px.x - tapPx.x).toDouble(),
                            (px.y - tapPx.y).toDouble(),
                        ).toFloat()
                        if (d < bestDist) {
                            bestDist = d
                            best = c
                        }
                    }
                    if (best != null && bestDist < TAP_HIT_RADIUS_PX) {
                        cb(best!!)
                        true
                    } else {
                        false
                    }
                }
                // #150 — contacts render as native annotation Markers
                // (ContactMarkerRenderer). A Marker's built-in tap handler selects
                // it, shows `.title()` as an InfoWindow, and CONSUMES the touch —
                // so the map-click hit-test above never ran on a DIRECT pin tap:
                // only the label popped up and the edit sheet never opened (a
                // near-miss within TAP_HIT_RADIUS still edited, which is why this
                // read as "editing only works on the 3D globe"). Resolve the tapped
                // pin back to its contact and route it through the SAME onContactTap
                // the globe uses, returning true to suppress the InfoWindow. KML
                // placemarks (also addMarker) resolve to null → return false so
                // MapLibre's default InfoWindow label still works for them.
                map.setOnMarkerClickListener { marker ->
                    val contact = ContactMarkerRenderer.contactForMarker(marker)
                    // Mirror the map-click precedence: an active mode handler
                    // (measurement/drawing/mission) eats the tap before editing.
                    val modeHandled = contact != null &&
                        (bindings.onMapSingleTap?.invoke(marker.position) ?: false)
                    when (decideMarkerTap(contact, modeHandled)) {
                        MarkerTapAction.OPEN_CONTACT_EDIT -> {
                            bindings.onContactTap?.invoke(contact!!)
                            true
                        }
                        MarkerTapAction.CONSUMED_BY_MODE -> true
                        MarkerTapAction.PASS_THROUGH -> false
                    }
                }
            }
            // Issue #80 — re-anchor drawing (and all other overlay) layers on
            // EVERY style reload, regardless of the trigger.
            //
            // Root cause: MapLibre-Android wipes ALL GeoJSON source data when a
            // new style is loaded. The explicit setStyle callbacks above re-push
            // data for Compose-driven reloads (basemap swap, terrain toggle), but
            // those callbacks are one-shot — they fire only for the specific
            // setStyle call they are attached to. Any reload MapLibre triggers
            // internally (tile-source retry, GL context loss, recovery from a
            // failed style parse) is not covered, leaving the sources empty and
            // all overlay layers invisible.
            //
            // addOnDidFinishLoadingStyleListener fires on the MAIN thread after
            // every successful style load — both app-initiated and internal. Re-
            // pushing here is idempotent (setGeoJson replaces source data in-
            // place), so the explicit callbacks above are kept for their side-
            // effects (LocationComponent re-apply, camera tilt, onStyleReady
            // delegation); this listener is a universal safety net that closes the
            // intermittent gap.
            //
            // iOS is immune to this class of bug because Mapbox v11
            // AnnotationManagers survive style swaps natively — Android's hand-
            // inserted style layers do not.
            //
            // This listener lives as long as the view, which is longer than the
            // composition that registers it, so everything it puts back comes
            // from `bindings` (written by whichever composition shows the map,
            // before its other effects run) and the application context.
            // Reading this composition's own state here redrew the drawings,
            // measurement and grid as they were the first time the operator
            // left the map tab, on every later style reload.
                addOnDidFinishLoadingStyleListener {
                    getMapAsync { map ->
                        map.getStyle { style ->
                            ContactMarkerRenderer.update(map, ctx, bindings.contacts)
                            MeasurementLayer.update(map, bindings.measurementPoints)
                            DrawingShapeRenderer.apply(map, bindings.drawings)
                            bindings.gridCenter?.let { GridLayer.update(map, it) }
                            // #197 — this listener fires on EVERY successful style
                            // load, including ones MapLibre triggers internally
                            // (not just the app's own setStyle calls), so it is
                            // the natural place to retry a render-mode apply that
                            // bailed out earlier because the style wasn't ready
                            // yet (map.style == null at the time). Re-applying
                            // when nothing was skipped is a harmless no-op.
                            if (bindings.puckActive && map.locationComponent.isLocationComponentActivated) {
                                val spec = selfPuckSpecFor(
                                    selfMarkerStyleFor(bindings.useMilStdSelfSymbol, bindings.selfMarkerTriangle),
                                )
                                val cameraMode =
                                    if (bindings.followMeActive) CameraMode.TRACKING_COMPASS else CameraMode.NONE
                                applyLocationRenderMode(map, spec, cameraMode)
                            }
                        }
                    }
                }
            } // MapView(ctx).apply
        } // RetainedMapView.acquire factory
    } // remember

    // The state the view's long-lived listeners read. Written here, in the
    // first effect of the composable and keyed on the values, so that it is
    // this pass's state before any later effect can reload the style: a JSON
    // style finishes loading inside setStyle, and the style-reload listener
    // runs there. (A SideEffect would write it after every effect of the pass,
    // one reload too late.)
    DisposableEffect(
        mapView, northUpLocked, selfFix, puckActive, contacts, measurementPoints, drawings,
        gridCenter, useMilStdSelfSymbol, selfMarkerTriangle, followMeActive,
    ) {
        if (RetainedMapView.isHeldBy(holder)) {
            bindings.northUpLocked = northUpLocked
            bindings.selfFix = selfFix
            bindings.puckActive = puckActive
            bindings.contacts = contacts
            bindings.measurementPoints = measurementPoints
            bindings.drawings = drawings
            bindings.gridCenter = gridCenter
            bindings.useMilStdSelfSymbol = useMilStdSelfSymbol
            bindings.selfMarkerTriangle = selfMarkerTriangle
            bindings.followMeActive = followMeActive
        }
        onDispose { }
    }

    // The view reports "map ready" once, when it is built, to the composition
    // that built it. A composition that is handed the existing view (every
    // return to the map tab, every switch back from the 3D globe, every new
    // Activity) was never told, so the screen above it sat on a null map for
    // the rest of the session: its overlay, lasso, plugin-overlay and "frame
    // this overlay" code all quietly did nothing. Tell it here.
    val currentMapReady by rememberUpdatedState(onMapReady)
    DisposableEffect(mapView) {
        var disposed = false
        if (!builtHere[0]) {
            mapView.getMapAsync { map -> if (!disposed) currentMapReady?.invoke(map) }
        }
        onDispose { disposed = true }
    }

    // Flip the location layer on when permission is granted after the
    // map is already alive.
    DisposableEffect(mapView, puckActive) {
        if (puckActive) {
            mapView.getMapAsync { map ->
                val style = map.style
                if (style != null && !map.locationComponent.isLocationComponentActivated) {
                    activateLocation(
                        map, style, context, currentUseMilStd, currentTeamColor,
                        seedFix = currentSelfFix, puck = puckAppearance,
                        selfMarkerTriangle = currentSelfMarkerTriangle,
                        echelonAmplifier = echelonAmplifierFor(currentSelfEchelon),
                    )
                }
                if (map.locationComponent.isLocationComponentActivated) {
                    val spec = selfPuckSpecFor(selfMarkerStyleFor(currentUseMilStd, currentSelfMarkerTriangle))
                    applyLocationRenderMode(map, spec, CameraMode.NONE)
                }
            }
        } else {
            // #210 - marker hidden (or no location): make sure the component is
            // off. A no-op when it was never activated; when the operator hides
            // the marker while it is running this is what takes the puck away.
            mapView.getMapAsync { map -> stopLocationComponent(map) }
        }
        onDispose { }
    }

    // Issue #75 — drive the puck from LocationProvider's fix alongside
    // the component's internal engine. This renders the restored fix the
    // instant the map is up (cold start) and snaps the marker to the
    // forced foreground-resume fix; the provider's newer-wins gate
    // guarantees this flow never regresses the position. Also keeps the
    // dimmed/stale appearance in sync: dim when all we have is an old
    // restored fix, restore full opacity once a fresh fix lands.
    DisposableEffect(mapView, selfFix, puckActive) {
        val fix = selfFix
        if (fix != null && puckActive) {
            mapView.getMapAsync { map ->
                val style = map.style
                if (style != null) {
                    map.withReadyLocationComponent("self fix") { component ->
                        component.forceLocationUpdate(fix.toLocation())
                        val staleNow =
                            SelfFixPersistence.isStale(fix.timeMs, System.currentTimeMillis())
                        if (puckAppearance.dimmed != staleNow) {
                            component.applyStyle(
                                buildPuckOptions(
                                    context, style, currentUseMilStd, currentTeamColor,
                                    dimmed = staleNow,
                                    selfMarkerTriangle = currentSelfMarkerTriangle,
                                    echelonAmplifier = echelonAmplifierFor(currentSelfEchelon),
                                ),
                            )
                            puckAppearance.dimmed = staleNow
                        }
                    }
                }
            }
        }
        onDispose { }
    }

    // #159 — re-apply the puck when the operator changes their echelon so the
    // amplifier appears/updates live (Settings → Map), without waiting on a
    // style reload or re-activation.
    DisposableEffect(mapView, selfEchelon) {
        if (puckActive) {
            mapView.getMapAsync { map ->
                val style = map.style
                if (style != null) {
                    map.withReadyLocationComponent("echelon") { component ->
                        component.applyStyle(
                            buildPuckOptions(
                                context, style, currentUseMilStd, currentTeamColor,
                                dimmed = puckAppearance.dimmed,
                                selfMarkerTriangle = currentSelfMarkerTriangle,
                                echelonAmplifier = echelonAmplifierFor(currentSelfEchelon),
                            ),
                        )
                    }
                }
            }
        }
        onDispose { }
    }

    // Each time [recenterTrigger] changes, briefly flip camera to
    // TRACKING to pan to the user, then restore NONE so the user can
    // still pan freely.
    //
    // Two things send the map to the operator's position: a press on "Center
    // on me", and the composition that builds the view opening the map (with
    // location on and a fix known, the first map of a process has always
    // opened there, marker shown or hidden). Composing the map onto the view
    // that already exists is not one of them (#7). The marker branch used to
    // act on the value it was composed with there too, so every return to the
    // map tab threw the operator's view away for their own position at zoom 15.
    val currentCameraFollowsFix by rememberUpdatedState(cameraFollowsFix)
    DisposableEffect(mapView, recenterTrigger) {
        val reason = RetainedMapRules.recenterReason(recenterTrigger, initialRecenterTrigger, builtHere[0])
        val pressed = reason == RetainedMapRules.Recenter.PRESSED
        // False once this request has been superseded or the map has left the
        // screen: a wait for the map or the style must not act after that.
        var wanted = true
        if (reason == RetainedMapRules.Recenter.OPENING) {
            // Decided when the map is ready, from the state at that moment and
            // not from this pass: the first pass of a cold start can still hold
            // the default preferences (marker shown), and the fix kept from the
            // last session arrives after it.
            mapView.getMapAsync { map ->
                map.getStyle {
                    if (wanted) {
                        val tracking = currentPuckActive && map.withReadyLocationComponent("open on own position") { component ->
                            component.cameraMode = CameraMode.TRACKING
                            component.zoomWhileTracking(15.0)
                        }
                        // Marker hidden: no location component to track with.
                        // Go to the fix at the zoom the map already has, which
                        // is what the tracking transition does.
                        if (!tracking && currentCameraFollowsFix) {
                            bindings.selfFix?.let { fix ->
                                map.animateCamera(CameraUpdateFactory.newLatLng(LatLng(fix.lat, fix.lon)), 750)
                            }
                        }
                    }
                }
            }
        } else if (pressed && puckActive) {
            mapView.getMapAsync { map ->
                // getStyle runs this now when a style is loaded, which is the
                // normal case. With no style (none set yet, or the last one
                // failed to parse) it runs after the next one has loaded and
                // the location component has been moved onto it, so the press
                // is not lost and cannot reach a component with no style.
                map.getStyle {
                    if (wanted) {
                        map.withReadyLocationComponent("recenter") { component ->
                            component.cameraMode = CameraMode.TRACKING
                            component.zoomWhileTracking(15.0)
                        }
                    }
                }
            }
        } else if (pressed) {
            // #210 - marker hidden, so there is no LocationComponent to track
            // with: pan and zoom straight to the raw fix.
            val fix = selfFix
            if (cameraFollowsFix && fix != null) {
                mapView.getMapAsync { map ->
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(LatLng(fix.lat, fix.lon), 15.0),
                        600,
                    )
                }
            }
        }
        onDispose { wanted = false }
    }

    // Programmatic zoom — the +/− FABs in MapScreen tick these counters
    // on tap, and we animate one zoom step per tick.
    DisposableEffect(mapView, zoomInTrigger) {
        if (zoomInTrigger > 0) {
            mapView.getMapAsync { map ->
                map.animateCamera(CameraUpdateFactory.zoomIn(), 250)
            }
        }
        onDispose { }
    }
    DisposableEffect(mapView, zoomOutTrigger) {
        if (zoomOutTrigger > 0) {
            mapView.getMapAsync { map ->
                map.animateCamera(CameraUpdateFactory.zoomOut(), 250)
            }
        }
        onDispose { }
    }

    // Issue #95 — north-up lock: disable/re-enable rotate gestures and snap
    // to bearing 0° when locked.  The MapLibre UiSettings gate is toggled
    // every time the value flips; it persists on the map object until we
    // change it, so no repeated enable is needed while the lock holds.
    DisposableEffect(mapView, northUpLocked) {
        mapView.getMapAsync { map ->
            map.uiSettings.isRotateGesturesEnabled = !northUpLocked
            if (northUpLocked) {
                // Animate to north so the snap doesn't feel jarring.
                val cur = map.cameraPosition
                if (kotlin.math.abs(cur.bearing) > 0.5) {
                    map.animateCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder(cur).bearing(0.0).build()
                        ),
                        350,
                    )
                }
            }
        }
        onDispose { }
    }
    // Issue #95/#96 — manual snap-to-north: compass tap when lock is off.
    DisposableEffect(mapView, snapNorthTrigger) {
        if (snapNorthTrigger > 0) {
            mapView.getMapAsync { map ->
                val cur = map.cameraPosition
                if (kotlin.math.abs(cur.bearing) > 0.5) {
                    map.animateCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder(cur).bearing(0.0).build()
                        ),
                        350,
                    )
                }
            }
        }
        onDispose { }
    }

    // 3D terrain: tilt the camera so the DEM relief (baked into the
    // style JSON via injectTerrain) renders dimensionally. 0° = flat
    // top-down 2D; 55° = the dimensional tactical view. Animated so the
    // toggle feels like a transition, not a jump.
    DisposableEffect(mapView, terrain3d) {
        mapView.getMapAsync { map ->
            val cur = map.cameraPosition
            val targetTilt = if (terrain3d) 55.0 else 0.0
            if (kotlin.math.abs(cur.tilt - targetTilt) > 1.0) {
                map.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder(cur).tilt(targetTilt).build()
                    ),
                    400,
                )
            }
        }
        onDispose { }
    }

    // GAP-101 — react to basemap selection from Settings. Re-applies the
    // entire style JSON so the operational layers (which live inline in the
    // style JSON to dodge the MapLibre-Android addLayer GL quirk) keep
    // rendering. Layer GeoJSON is re-pushed once the new style finishes loading.
    DisposableEffect(mapView, styleJson) {
        mapView.getMapAsync { map ->
            // Skip the first emission — the initial setStyle is handled by the
            // getMapAsync block during MapView construction (line ~88).
            if (map.style != null && map.style?.json != styleJson) {
                map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                    ContactMarkerRenderer.update(map, context, currentContacts)
                    MeasurementLayer.update(map, currentMeasurementPoints)
                    DrawingShapeRenderer.apply(map, currentDrawings)
                    currentGridCenter?.let { GridLayer.update(map, it) }
                    // #206 — re-clamp for the new basemap. Same caveat as
                    // cold start: MBTiles maxzoom isn't known here, so
                    // MapScreen's LaunchedEffect(mbtilesOverlays,
                    // mapProvider, ...) is the one that re-widens the clamp
                    // for any currently-visible overlay right after this.
                    extractBasemapMaxZoom(styleJson)?.let { basemapMax ->
                        map.setMaxZoomPreference(zoomClampFor(basemapMax, emptyList()))
                    }
                    // ADS-B re-push after a style reload is handled by the
                    // plugin overlay's LaunchedEffect(map, …), which re-runs
                    // when the new MapLibreMap/style lands. Nothing to do here.
                    // Issue #75 — the self-marker bitmaps live on the style,
                    // so a basemap swap wipes them. Re-register + re-apply so
                    // the puck survives style reloads with its current
                    // (dimmed or live) appearance.
                    if (currentPuckActive) {
                        map.withReadyLocationComponent("style reload") { component ->
                            component.applyStyle(
                                buildPuckOptions(
                                    context, style, currentUseMilStd, currentTeamColor,
                                    dimmed = puckAppearance.dimmed,
                                    selfMarkerTriangle = currentSelfMarkerTriangle,
                                    echelonAmplifier = echelonAmplifierFor(currentSelfEchelon),
                                ),
                            )
                        }
                    }
                    currentStyleReady?.invoke(map, style)
                    // Apply 3D tilt AFTER the style (which carries the
                    // terrain source) finishes loading — deterministic vs
                    // a separate effect that races the style reload.
                    val targetTilt = if (currentTerrain3d) 55.0 else 0.0
                    map.animateCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder(map.cameraPosition).tilt(targetTilt).build()
                        ),
                        500,
                    )
                }
            }
        }
        onDispose { }
    }

    DisposableEffect(mapView, measurementPoints) {
        mapView.getMapAsync { map ->
            if (map.style != null) MeasurementLayer.update(map, measurementPoints)
        }
        onDispose { }
    }

    DisposableEffect(mapView, drawings) {
        mapView.getMapAsync { map ->
            if (map.style != null) DrawingShapeRenderer.apply(map, drawings)
        }
        onDispose { }
    }

    DisposableEffect(mapView, gridCenter) {
        mapView.getMapAsync { map ->
            if (map.style != null) {
                val c = gridCenter
                if (c != null) GridLayer.update(map, c) else GridLayer.clear(map)
            }
        }
        onDispose { }
    }

    // (ADS-B's DisposableEffect(mapView, aircraft) moved into the plugin's
    // AdsbMapOverlay, which owns aircraft painting via the live map handle.)

    // Pan camera to an arbitrary LatLng — used by the Teams panel to
    // jump the map onto a tapped contact. The tick parameter lets the
    // caller re-fire a pan to the same point (tapping the same row twice).
    DisposableEffect(mapView, panTargetTick) {
        val target = panTarget
        if (panTargetTick > 0 && target != null) {
            mapView.getMapAsync { map ->
                map.withReadyLocationComponent("pan") { it.cameraMode = CameraMode.NONE }
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(target, 14.0),
                    600,
                )
            }
        }
        onDispose { }
    }

    // "Follow me" toggle — pins the camera to the user's location and
    // rotates with compass heading. Flipping off returns to free-pan.
    DisposableEffect(mapView, followMeActive, puckActive) {
        // False once the toggle has changed again or the map has left the
        // screen: a wait for the style must not act after that.
        var wanted = true
        if (puckActive) {
            mapView.getMapAsync { map ->
                // This runs when the map is composed too. With no style loaded
                // (none set yet, or the last one failed to parse) wait for the
                // next one before touching the component.
                map.getStyle {
                    if (wanted) {
                        map.withReadyLocationComponent("follow me") { component ->
                            component.cameraMode = if (followMeActive) {
                                CameraMode.TRACKING_COMPASS
                            } else {
                                CameraMode.NONE
                            }
                        }
                    }
                }
            }
        }
        onDispose { wanted = false }
    }

    // #210 - follow-me while the marker is hidden. There is no LocationComponent
    // to track with, so pan to each new fix ourselves. Like MapLibre's own
    // tracking mode, a drag on the map ends the follow until follow-me is
    // switched off and on again; unlike TRACKING_COMPASS it does not rotate
    // the map with the compass.
    val followBroken = remember { mutableStateOf(false) }
    DisposableEffect(mapView, followMeActive, cameraFollowsFix) {
        followBroken.value = false
        var attachedMap: org.maplibre.android.maps.MapLibreMap? = null
        val listener = org.maplibre.android.maps.MapLibreMap.OnCameraMoveStartedListener { reason ->
            if (reason == org.maplibre.android.maps.MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                followBroken.value = true
            }
        }
        if (cameraFollowsFix && followMeActive) {
            mapView.getMapAsync { map ->
                attachedMap = map
                map.addOnCameraMoveStartedListener(listener)
            }
        }
        onDispose { attachedMap?.removeOnCameraMoveStartedListener(listener) }
    }
    DisposableEffect(mapView, cameraFollowsFix, followMeActive, selfFix?.lat, selfFix?.lon, followBroken.value) {
        val fix = selfFix
        if (cameraFollowsFix && followMeActive && fix != null && !followBroken.value) {
            mapView.getMapAsync { map ->
                map.animateCamera(CameraUpdateFactory.newLatLng(LatLng(fix.lat, fix.lon)), 500)
            }
        }
        onDispose { }
    }

    // Leaving the screen. #177 — the MapView is retained (RetainedMapView) so
    // it can be shown again without a cold MapLibre rebuild, so there is no
    // onDestroy() here. We only:
    //   1. silence the LocationComponent (its compass animator keeps firing
    //      across nav transitions and crashes when it touches a detached
    //      style — the original reason for the teardown), and
    //   2. detach the view from its Compose parent so the next AndroidView can
    //      attach it (a View may have only one parent).
    // The map object with its style and camera stays; detaching ends the
    // render thread and resets the renderer, and both start again on the next
    // attach.
    //
    // This is its own effect, keyed on the view alone, so it runs when the map
    // really leaves the composition and at no other time. It used to sit in the
    // lifecycle effect below, which restarts whenever its lifecycle owner
    // changes: were that ever to happen under a composition that stays, the
    // view would be taken out of a screen still showing it. It is declared
    // before that effect so that, on the way out, the observer is removed
    // first.
    //
    // All of it only while this composition still has the view. If a newer one
    // took it, it is attached and running there: silencing the location layer
    // or detaching the view now would blank the map on screen.
    DisposableEffect(mapView) {
        onDispose {
            if (RetainedMapView.isHeldBy(holder)) {
                runCatching {
                    mapView.getMapAsync { map ->
                        if (map.locationComponent.isLocationComponentActivated) {
                            map.locationComponent.isLocationComponentEnabled = false
                        }
                    }
                }
                RetainedMapView.release(holder, mapView)
            }
        }
    }

    // onPause/onStop/onStart/onResume follow the host's lifecycle.
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            // Nothing is forwarded once a newer composition has the view (a new
            // Activity opened before this one finished closing): this one's
            // late onPause/onStop would freeze the map that is on screen there.
            when (RetainedMapRules.viewCallFor(event, RetainedMapView.isHeldBy(holder))) {
                RetainedMapRules.ViewCall.START -> mapView.onStart()
                RetainedMapRules.ViewCall.RESUME -> {
                    mapView.onResume()
                    // Issue #75 (root cause) — ON_PAUSE below silences the
                    // LocationComponent to kill its compass animator, but
                    // nothing ever re-enabled it: after screen-off/on the
                    // self-marker stayed hidden until the composable was
                    // rebuilt. Re-enable on every resume, restoring the
                    // camera mode follow-me expects.
                    if (currentPuckActive) {
                        runCatching {
                            mapView.getMapAsync { map ->
                                if (map.locationComponent.isLocationComponentActivated) {
                                    val spec = selfPuckSpecFor(
                                        selfMarkerStyleFor(currentUseMilStd, currentSelfMarkerTriangle),
                                    )
                                    val cameraMode =
                                        if (currentFollowMe) CameraMode.TRACKING_COMPASS
                                        else CameraMode.NONE
                                    applyLocationRenderMode(map, spec, cameraMode)
                                }
                            }
                        }
                    }
                }
                RetainedMapRules.ViewCall.PAUSE -> {
                    // Silence the LocationComponent on pause — its compass
                    // animator keeps firing across lifecycle transitions
                    // and crashes when it touches a detached style
                    // (seen navigating away via bottom nav on Android 16).
                    runCatching {
                        mapView.getMapAsync { map ->
                            if (map.locationComponent.isLocationComponentActivated) {
                                map.locationComponent.isLocationComponentEnabled = false
                            }
                        }
                    }
                    mapView.onPause()
                }
                RetainedMapRules.ViewCall.STOP -> mapView.onStop()
                // No onDestroy(), ever. ON_DESTROY is the Activity (or its nav
                // entry) being destroyed, not the view: RetainedMapView keeps
                // the view and hands it to the next Activity. Through 0.45.0
                // this destroyed it and the next Activity got the destroyed
                // view back: no style, a location layer still "activated" on
                // the cleared one, and an IllegalStateException ("Calling
                // getSourceAs when a newer style is loading/has loaded") as
                // soon as anything touched it. Every Activity rebuild in a
                // live process did it: the system switching to dark mode, a
                // font size change, the app swiped out of Recents and reopened
                // while its connection service kept the process alive.
                RetainedMapRules.ViewCall.NOTHING -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Issue #77 — push contact updates on every recomposition that delivers a new
    // contacts list, mirroring the Cesium engine's AndroidView.update pattern.
    // Two deliberate changes vs the removed DisposableEffect(mapView, contacts):
    //
    //   1. AndroidView.update fires on every recomposition (not only on key change),
    //      so a marker dropped while the style is loading is not silently lost —
    //      the push retries on the next frame and every subsequent recomposition
    //      until it lands.
    //
    //   2. map.getStyle { } (the queuing variant) instead of `if (map.style != null)`:
    //      when the style is still loading the callback is queued and fires as soon
    //      as the style is ready, closing the race between ingest and style-load that
    //      caused locally-dropped markers to disappear on first open.
    //
    // currentContacts (rememberUpdatedState) always holds the latest list, so the
    // async getStyle callback never captures a stale snapshot even if several
    // recompositions happen between the update call and the callback execution.
    AndroidView(
        factory = { mapView },
        update = {
            mapView.getMapAsync { map ->
                map.getStyle { _ ->
                    ContactMarkerRenderer.update(map, context, currentContacts)
                }
            }
        },
        modifier = modifier,
    )
}

/**
 * Issue #81 — top margin for the MapLibre built-in compass so it clears
 * the ATAKStatusBar that sits at the very top of the map surface.
 *
 * Derivation (all in dp):
 *   ATAKStatusBar.padding(vertical = 8.dp): 8 top + 8 bottom  = 16 dp
 *   ATAKStatusBar row content (13sp text / 14dp icon)          ≈ 20 dp
 *   ATAKStatusBar total height                                  ≈ 36 dp
 *   Typical Android system status bar                          ≈ 24 dp
 *   Total top clearance                                        ≈ 60 dp
 *   + 4 dp breathing room                                       = 64 dp
 *
 * This is a fixed dp constant because ATAKStatusBar has a fixed layout
 * (no dynamic content that changes the bar height). The system status bar
 * typically ranges 20–28 dp; 64 dp clears both extremes comfortably.
 * setCompassMargins accepts raw pixels, so we convert at runtime via
 * context.resources.displayMetrics.density.
 */
internal const val COMPASS_TOP_MARGIN_DP = 64

private const val TAG = "TacticalMap"

/** Issue #75 — imperative holder for the self-marker's current dim state
 *  (stale restored fix vs live GPS). Shared between the activation path,
 *  the selfFix forwarding effect, and the style-reload re-apply. */
internal class PuckAppearance {
    @Volatile var dimmed: Boolean = false
}

/**
 * #210 - take the puck fully off: stop camera tracking first so nothing keeps
 * animating, then disable the component (hides every layer and stops its
 * engine). A no-op when the component was never activated. Guarded like the
 * pause path: disabling touches the style, which can be mid-swap.
 */
private fun stopLocationComponent(map: org.maplibre.android.maps.MapLibreMap) {
    runCatching {
        if (map.locationComponent.isLocationComponentActivated) {
            map.locationComponent.cameraMode = CameraMode.NONE
            map.locationComponent.isLocationComponentEnabled = false
        }
    }.onFailure { Log.w(TAG, "stopLocationComponent: skipped (style not ready)", it) }
}

@SuppressLint("MissingPermission")
private fun activateLocation(
    map: org.maplibre.android.maps.MapLibreMap,
    style: Style,
    context: android.content.Context,
    useMilStdSelfSymbol: Boolean,
    selfTeamColor: String = "Cyan",
    seedFix: SelfFix? = null,
    puck: PuckAppearance = PuckAppearance(),
    selfMarkerTriangle: Boolean = false,
    echelonAmplifier: String = "",
) {
    // Issue #75 — when the best position available at activation is a
    // restored (persisted) fix that is already old, start the puck dimmed
    // so a last-known position can't masquerade as live GPS.
    val dimmed = seedFix != null &&
        SelfFixPersistence.isStale(seedFix.timeMs, System.currentTimeMillis())
    puck.dimmed = dimmed

    val options = LocationComponentActivationOptions.builder(context, style)
        .useDefaultLocationEngine(true)
        .locationComponentOptions(
            buildPuckOptions(context, style, useMilStdSelfSymbol, selfTeamColor, dimmed, selfMarkerTriangle, echelonAmplifier),
        )
        .build()
    map.locationComponent.activateLocationComponent(options)
    val spec = selfPuckSpecFor(selfMarkerStyleFor(useMilStdSelfSymbol, selfMarkerTriangle))
    applyLocationRenderMode(map, spec, CameraMode.NONE)

    // Issue #75 — render the restored fix immediately instead of leaving
    // the marker invisible until the engine's first delivery (the
    // "disappears until GPS reacquires" bug). Live engine updates and the
    // forced foreground-resume fix take over from here via newer-wins.
    if (seedFix != null) {
        map.locationComponent.forceLocationUpdate(seedFix.toLocation())
    }
}

/**
 * Issue #75 — build (and register the marker images for) the puck's
 * styling options. [dimmed] renders the self-marker at reduced opacity —
 * used while the only position available is a restored fix older than
 * [SelfFixPersistence.STALE_AFTER_MS]. The stale variants are always
 * registered/configured so MapLibre's own stale-state machinery (no
 * location update for the same 30 s — e.g. GPS loss indoors) dims the
 * puck identically.
 */
private fun buildPuckOptions(
    context: android.content.Context,
    style: Style,
    useMilStdSelfSymbol: Boolean,
    selfTeamColor: String,
    dimmed: Boolean,
    selfMarkerTriangle: Boolean = false,
    echelonAmplifier: String = "",
): LocationComponentOptions {
    // Resolve the ARGB tint from the operator's configured TAK team name
    // ("Cyan", "Red", "Orange", …). Falls back to cyan — CivTAK default
    // for unaffiliated friendlies — when the name isn't in the palette.
    val teamArgb: Int = TakTeamColor.forName(selfTeamColor) ?: 0xFF00FFFF.toInt()
    val markerStyle = selfMarkerStyleFor(useMilStdSelfSymbol, selfMarkerTriangle)
    val spec = selfPuckSpecFor(markerStyle)

    // #83/#204 — triangle self-marker overrides both MIL-STD and disc
    // modes; it is MEANT to rotate with heading, so it stays on
    // RenderMode.COMPASS (see SelfPuckSpec) — but only the bearing layer
    // carries the triangle bitmap. COMPASS draws the foreground layer
    // upright AND the bearing layer rotated at the SAME screen position;
    // pointing both at the triangle (the pre-fix code) drew it twice
    // whenever heading != 0. The foreground is a fully transparent 1x1
    // bitmap so that upright copy is invisible instead of a ghost triangle.
    if (markerStyle == SELF_MARKER_STYLE_TRIANGLE) {
        val triangleBmp = createTriangleBitmap(64)
        val transparentBmp = createTransparentBitmap(1)
        style.addImage(SELF_TRIANGLE_IMAGE, triangleBmp)
        style.addImage(SELF_TRIANGLE_STALE_IMAGE, fadeBitmap(triangleBmp, STALE_MARKER_ALPHA))
        style.addImage(SELF_TRIANGLE_TRANSPARENT_IMAGE, transparentBmp)
        style.addImage(SELF_TRIANGLE_TRANSPARENT_STALE_IMAGE, transparentBmp)
        // MapLibre's LocationComponentOptions has no bearingStaleName — the
        // bearing layer's dim state has to be picked manually here, at
        // rebuild time, from spec.bearingImage/bearingStaleImage.
        val bearingImg = if (dimmed) spec.bearingStaleImage!! else spec.bearingImage!!
        return LocationComponentOptions.builder(context)
            .pulseEnabled(!dimmed)
            .pulseColor(teamArgb)
            .pulseSingleDuration(2200f)
            .accuracyColor(teamArgb)
            .accuracyAlpha(0.18f)
            .enableStaleState(true)
            .staleStateTimeout(SelfFixPersistence.STALE_AFTER_MS)
            .bearingName(bearingImg)
            .foregroundName(spec.foregroundImage!!)
            .foregroundStaleName(spec.foregroundStaleImage!!)
            .build()
    }

    // Self-position marker. When [useMilStdSelfSymbol] is true (default),
    // render with a MIL-STD-2525 friendly combat ground symbol
    // (a-f-G-U-C → SFGPUC------) so the operator's own pip reads as part
    // of the same tactical iconography as friendly markers. Otherwise
    // — or when the SVG pipeline can't produce a bitmap (missing asset,
    // AndroidSVG parse failure, OEM GL quirk) — fall back to the legacy
    // tinted-disc drawable.
    val selfBitmap: android.graphics.Bitmap? = if (useMilStdSelfSymbol) {
        soy.engindearing.omnitak.mobile.data.symbology.MilStdIconCache
            .bitmapFor(context, cotType = "a-f-G-U-C", sizePx = 96)
            ?.let { raw -> tintBitmap(raw, teamArgb) }
            // #159 — draw the echelon amplifier above the symbol (symbol stays
            // centered on the GPS fix; LocationComponent anchors the bitmap center).
            ?.let { sym -> withEchelonAmplifier(sym, echelonAmplifier) }
    } else {
        null
    }

    val markerOptionsBuilder = LocationComponentOptions.builder(context)
        // No pulse on a dimmed marker — the pulse animation overstates
        // liveness when all we have is a last-known position.
        .pulseEnabled(!dimmed)
        .pulseColor(teamArgb)
        .pulseSingleDuration(2200f)
        .accuracyColor(teamArgb)
        .accuracyAlpha(0.18f)
        // Issue #75 — let MapLibre dim the puck on its own when no update
        // arrives for STALE_AFTER_MS (same threshold as restored-fix
        // staleness, so both paths look identical).
        .enableStaleState(true)
        .staleStateTimeout(SelfFixPersistence.STALE_AFTER_MS)

    if (selfBitmap != null) {
        // #204 — MIL-STD symbol bodies aren't meant to rotate (the #159
        // echelon amplifier assumes an upright symbol). RenderMode.NORMAL
        // (applied by the caller via SelfPuckSpec.renderMode — see
        // applyLocationRenderMode) never renders a bearing layer at all,
        // so there is only ever one copy of the symbol on screen. Operator
        // heading is still surfaced numerically in the SelfPositionCard.
        val foregroundLive = spec.foregroundImage!!
        val foregroundStale = spec.foregroundStaleImage!!
        style.addImage(foregroundLive, selfBitmap)
        style.addImage(foregroundStale, fadeBitmap(selfBitmap, STALE_MARKER_ALPHA))
        markerOptionsBuilder
            .foregroundName(if (dimmed) foregroundStale else foregroundLive)
            .foregroundStaleName(foregroundStale)
        // spec.bearingImage is null here by design — see SelfPuckSpec.
    } else {
        // Legacy tinted-disc fallback — apply the team color as a tint
        // on the vector drawable so the disc still reflects team identity.
        // Unchanged by #204: it already uses two distinct drawables, so it
        // never had the double-draw bug.
        val tint = if (dimmed) fadeArgb(teamArgb, STALE_MARKER_ALPHA) else teamArgb
        markerOptionsBuilder
            .foregroundDrawable(R.drawable.ic_self_marker)
            .bearingDrawable(R.drawable.ic_self_marker_bearing)
            .foregroundTintColor(tint)
            .bearingTintColor(tint)
            .foregroundStaleTintColor(fadeArgb(teamArgb, STALE_MARKER_ALPHA))
    }
    return markerOptionsBuilder.build()
}

/** Tint an ARGB bitmap's opaque pixels via a SRC_IN PorterDuff filter.
 *  The SVG renders as a transparent-background ARGB bitmap so SRC_IN
 *  recolors only the opaque glyph pixels — equivalent to the iOS
 *  UIImage.withTintColor approach. */
private fun tintBitmap(raw: android.graphics.Bitmap, argb: Int): android.graphics.Bitmap {
    val tinted = android.graphics.Bitmap.createBitmap(
        raw.width, raw.height, android.graphics.Bitmap.Config.ARGB_8888
    )
    val canvas = android.graphics.Canvas(tinted)
    val paint = android.graphics.Paint().apply {
        colorFilter = android.graphics.PorterDuffColorFilter(
            argb,
            android.graphics.PorterDuff.Mode.SRC_IN,
        )
    }
    canvas.drawBitmap(raw, 0f, 0f, paint)
    return tinted
}

/** #159 — resolve an echelon code to its APP-6 amplifier glyph; "" when unset
 *  or unknown (no amplifier drawn). */
private fun echelonAmplifierFor(code: String): String =
    soy.engindearing.omnitak.mobile.data.Echelon.fromCode(code)?.amplifier ?: ""

/**
 * #159 — composite the echelon [amplifier] glyph above [symbol], returning a
 * taller bitmap with the SYMBOL still vertically centered (so it stays on the
 * GPS fix — LocationComponent anchors the foreground bitmap at its center).
 * Returns [symbol] unchanged when there is no amplifier.
 */
private fun withEchelonAmplifier(symbol: android.graphics.Bitmap, amplifier: String): android.graphics.Bitmap {
    if (amplifier.isBlank()) return symbol
    val band = symbol.height * 0.42f          // top space for the glyph + matching bottom pad
    val out = android.graphics.Bitmap.createBitmap(
        symbol.width, (symbol.height + band * 2f).toInt(), android.graphics.Bitmap.Config.ARGB_8888,
    )
    val canvas = android.graphics.Canvas(out)
    canvas.drawBitmap(symbol, 0f, band, null)  // symbol centered: band above, band below
    val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = band * 0.82f
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    val halo = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#CC000000")
        textSize = band * 0.82f
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = band * 0.12f
    }
    val cx = symbol.width / 2f
    val baseline = band * 0.86f
    canvas.drawText(amplifier, cx, baseline, halo)
    canvas.drawText(amplifier, cx, baseline, fill)
    return out
}

/** Issue #75 — a translucent copy of [src] for the stale self-marker. */
private fun fadeBitmap(src: android.graphics.Bitmap, alpha: Int): android.graphics.Bitmap {
    val faded = android.graphics.Bitmap.createBitmap(
        src.width, src.height, android.graphics.Bitmap.Config.ARGB_8888
    )
    val canvas = android.graphics.Canvas(faded)
    val paint = android.graphics.Paint().apply { this.alpha = alpha }
    canvas.drawBitmap(src, 0f, 0f, paint)
    return faded
}

/** Issue #75 — [argb] with its alpha channel replaced by [alpha]. */
private fun fadeArgb(argb: Int, alpha: Int): Int =
    (argb and 0x00FFFFFF) or (alpha shl 24)

/** #204 — a fully transparent bitmap, used as the triangle self-marker's
 *  foreground layer so RenderMode.COMPASS's upright (non-rotating) copy is
 *  invisible instead of a ghost duplicate of the rotating bearing triangle
 *  (see SelfPuckSpec). MapLibre still needs a non-null foreground image
 *  registered by name — this is that image, just with alpha 0 everywhere. */
private fun createTransparentBitmap(sizePx: Int = 1): android.graphics.Bitmap =
    android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)

/** #83 — white triangle bitmap for the triangle self-marker mode. */
private fun createTriangleBitmap(sizePx: Int = 64): android.graphics.Bitmap {
    val bmp = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.WHITE
    val path = android.graphics.Path()
    path.moveTo(sizePx / 2f, 0f)
    path.lineTo(sizePx.toFloat(), sizePx.toFloat())
    path.lineTo(0f, sizePx.toFloat())
    path.close()
    canvas.drawPath(path, paint)
    paint.color = android.graphics.Color.DKGRAY
    paint.style = android.graphics.Paint.Style.STROKE
    paint.strokeWidth = 3f
    canvas.drawPath(path, paint)
    return bmp
}

/** Issue #75 — adapt a [SelfFix] for LocationComponent.forceLocationUpdate.
 *  NaN accuracy (restored fixes) is simply omitted. */
private fun SelfFix.toLocation(): android.location.Location =
    android.location.Location("omnitak-selffix").apply {
        latitude = lat
        longitude = lon
        altitude = altitudeM
        time = timeMs
        if (!accuracyM.isNaN()) accuracy = accuracyM
        if (speedKmh > 0.0) speed = (speedKmh / 3.6).toFloat()
    }

/** Stale self-marker opacity (0–255). ~45% — subtle, but unmistakably
 *  dimmer than the live marker (issue #75). */
private const val STALE_MARKER_ALPHA = 115

/**
 * Touch MapLibre's LocationComponent only when it can take it, and never let
 * its style check take the app down.
 *
 * Any call that reaches the component's layers on a style that has been
 * cleared throws `IllegalStateException("Calling getSourceAs when a newer
 * style is loading/has loaded")` on the main thread (#197, #251). "Activated"
 * is not enough to know it is safe: the component stays activated when its
 * style is cleared. `MapLibreMap.getStyle()` is null unless the current style
 * is fully loaded, and MapLibre moves the component onto a newly loaded style
 * before any callback or listener of the app runs, so a non-null style is the
 * gate. The catch is for whatever that reasoning misses.
 *
 * Returns true when [block] ran, false when it was skipped. A caller whose
 * action must not be lost wraps this in `map.getStyle { ... }`, which waits
 * for the style.
 */
private inline fun org.maplibre.android.maps.MapLibreMap.withReadyLocationComponent(
    what: String,
    block: (org.maplibre.android.location.LocationComponent) -> Unit,
): Boolean {
    if (style == null) return false
    val component = locationComponent
    if (!component.isLocationComponentActivated) return false
    return try {
        block(component)
        true
    } catch (e: IllegalStateException) {
        Log.w(TAG, "$what: the map style changed under the location component, skipped", e)
        false
    }
}

/**
 * #197 — applies a [SelfPuckSpec]'s render mode (and the given camera mode)
 * to an already-activated LocationComponent. Replaces the old
 * `safeEnableLocation`, which guarded nothing: three unconditional setters,
 * where `LocationComponent.setRenderMode` throws
 * `IllegalStateException("Calling getSourceAs when a newer style is
 * loading/has loaded")` when the style has been swapped/reloaded between
 * `onMapReady` and the call (stack: `Style.validateState` <-
 * `SymbolLocationLayerRenderer.refreshSource` <-
 * `LocationComponent.setRenderMode`). MapLibre 11.8's
 * `MapLibreMap.getStyle()`/`.style` returns null unless a style is fully
 * loaded, so that is the readiness gate.
 *
 * Returns true when applied, false when it bailed out early (style not
 * ready yet) or the call still raced a style swap despite the guard —
 * callers don't need to branch on this themselves: the
 * `addOnDidFinishLoadingStyleListener` re-push registered in [TacticalMap]
 * unconditionally re-applies on every subsequent style load, which is a
 * harmless no-op when nothing was actually skipped.
 */
@SuppressLint("MissingPermission")
private fun applyLocationRenderMode(
    map: org.maplibre.android.maps.MapLibreMap,
    spec: SelfPuckSpec,
    cameraMode: Int,
): Boolean {
    if (map.style == null) {
        Log.w(TAG, "applyLocationRenderMode: style not loaded yet, skipping (will retry on next style load)")
        return false
    }
    return try {
        map.locationComponent.isLocationComponentEnabled = true
        map.locationComponent.renderMode = spec.renderMode
        map.locationComponent.cameraMode = cameraMode
        true
    } catch (e: IllegalStateException) {
        // #197 — the style was swapped/reloaded between the null-check above
        // and this call (a real, observed race, not just theoretical) — log
        // and move on instead of crashing.
        Log.w(TAG, "applyLocationRenderMode: style changed mid-call, skipping (will retry on next style load)", e)
        false
    }
}

/**
 * Inline raster OSM style. Reliable on emulators where the demotiles
 * vector-tile style had GL issues. Same raster-layer shape swaps in
 * any XYZ tile URL (satellite, topo, custom TAK tile server) later.
 */
/**
 * Screen-space radius (pixels) used to decide whether a map tap lands
 * on a contact marker. ~48dp ≈ finger-tip tolerance on a mid-density
 * device; we stay in px here because MapLibre's projection returns
 * pixels directly.
 */
private const val TAP_HIT_RADIUS_PX = 72f

/**
 * Tactical dark basemap powered by CartoDB Dark Matter raster tiles
 * (https://carto.com/help/building-maps/basemap-list/). Free, no API
 * key, well-attributed, and gives a high-contrast tactical surface
 * that lets operational overlays (contacts, drawings, grid, aircraft)
 * pop without competing with brightly-styled OSM cartography.
 *
 * Operational layers and their GeoJSON sources live inline in the
 * style JSON. On the API 36 emulator, `style.addSource` /
 * `style.addLayer` called from the `setStyle(builder, onStyleLoaded)`
 * callback occasionally renders nothing despite the calls reporting
 * success and the source/layer appearing in the style — a
 * MapLibre-Android GL quirk we haven't root-caused. Declaring
 * everything in the style JSON avoids that path entirely;
 * `ContactLayer.update` pushes fresh feature data to the existing
 * source via `setGeoJson`.
 */
/**
 * Build a tactical-overlay style JSON wrapped around any XYZ raster
 * basemap. The operational layers (contacts, measurements, drawings,
 * grid, aircraft) live inline so MapLibre-Android renders them on the
 * first style load — a workaround for the addLayer GL quirk noted on
 * the original const below.
 *
 * GAP-101 — extracted from the original TACTICAL_DARK_STYLE so the
 * basemap raster source can be swapped per [MapProvider] preference
 * without losing the overlays.
 */
internal fun buildTacticalStyle(
    name: String,
    basemapTiles: String,
    attribution: String,
    maxZoom: Int = BASEMAP_MAXZOOM_CUSTOM_DEFAULT,
): String =
    TACTICAL_STYLE_HEAD
        .replace("@@NAME@@", name)
        .replace("@@TILES@@", basemapTiles)
        .replace("@@ATTRIBUTION@@", attribution)
        .replace("@@MAXZOOM@@", maxZoom.toString()) + TACTICAL_STYLE_OVERLAYS

private const val TACTICAL_STYLE_HEAD = """
{
  "version": 8,
  "name": "@@NAME@@",
  "sources": {
    "basemap": {
      "type": "raster",
      "tiles": [
        "@@TILES@@"
      ],
      "tileSize": 256,
      "maxzoom": @@MAXZOOM@@,
      "attribution": "@@ATTRIBUTION@@"
    }
"""

/**
 * #206 — per-provider raster maxzoom. A style-source `maxzoom` only
 * controls MapLibre's own overzoom (tile stretching) behavior, it does NOT
 * stop the camera from zooming further — the camera-side ceiling is
 * [zoomClampFor] / `MapLibreMap.setMaxZoomPreference`, applied in
 * [TacticalMap]. These numbers were measured empirically (HTTP probe with
 * the app's own tile User-Agent, [soy.engindearing.omnitak.mobile.data.MapTileHttp.buildUserAgent],
 * against lat 47.66 / lon -117.43 — see PR #206 for the full per-zoom probe
 * table) because providers respond very differently once asked for tiles
 * past their real ceiling:
 *
 *  - **OSM standard tile layer** (tile.openstreetmap.org): real, distinct
 *    tiles through z19; HTTP 400 at z20. Ceiling: 19.
 *  - **OpenTopoMap** (a.tile.opentopomap.org): real, distinct tiles through
 *    z17; z18/z19/z20 all return HTTP 200 but the exact same bytes — an
 *    explicit "max zoom layer = 17" placeholder tile, not real data.
 *    Ceiling: 17.
 *  - **Esri World Imagery** (server.arcgisonline.com): real, distinct
 *    imagery through z22 at this (rural) probe location; z23 repeats the
 *    exact z22 bytes (native ceiling reached there). Matches this app's
 *    Cesium 3D clamp ([CesiumCameraMath]'s MAX_ZOOM = 22), so 2D and 3D
 *    read the same at the edge. Ceiling: 22.
 *  - **CARTO Dark Matter** (basemaps.cartocdn.com): returns HTTP 200 at
 *    EVERY zoom tested (0 through 22) but it is the exact same
 *    "API KEY REQUIRED" placeholder image regardless of zoom, location, or
 *    even User-Agent — CARTO's free unauthenticated basemap tiles have been
 *    discontinued. This style is not reachable from the app's basemap
 *    picker ([MapProvider] has no case that selects it — see
 *    [styleJsonForProvider]; [TACTICAL_STYLE_DARK_MATTER] is only the
 *    [TacticalMap] composable's own default parameter, always overridden
 *    by callers), so it does not contribute to #206 in practice. Kept at a
 *    conservative ceiling pending a real decision on whether to key it,
 *    replace it, or drop it — flagged separately, not fixed here.
 */
internal const val BASEMAP_MAXZOOM_OSM = 19
internal const val BASEMAP_MAXZOOM_TOPO = 17
internal const val BASEMAP_MAXZOOM_SATELLITE = 22
internal const val BASEMAP_MAXZOOM_DARK_MATTER = 19
internal const val BASEMAP_MAXZOOM_CUSTOM_DEFAULT = 19

/** #206 — [MapProvider] -> its basemap's real maxzoom (see the constants'
 *  doc above). WMTS_CUSTOM has no per-server stored max-zoom setting today
 *  (only the tile URL template is persisted — see
 *  [soy.engindearing.omnitak.mobile.data.UserPrefs.customTileUrl]), so it
 *  falls back to [BASEMAP_MAXZOOM_CUSTOM_DEFAULT]. */
internal fun basemapMaxZoomFor(provider: MapProvider): Int = when (provider) {
    MapProvider.OSM_RASTER -> BASEMAP_MAXZOOM_OSM
    MapProvider.TOPO_HINT -> BASEMAP_MAXZOOM_TOPO
    MapProvider.SATELLITE_HINT -> BASEMAP_MAXZOOM_SATELLITE
    MapProvider.WMTS_CUSTOM -> BASEMAP_MAXZOOM_CUSTOM_DEFAULT
}

/** Extra zoom levels of MapLibre overzoom (tile stretching) allowed past
 *  the real ceiling before the camera hard-stops — a little pinch headroom
 *  without reaching a provider's true "no more data" wall. */
internal const val OVERZOOM_ALLOWANCE = 2.0

/**
 * #206 — pure camera-zoom ceiling: the highest of the active basemap's real
 * maxzoom and any visible MBTiles overlay's maxzoom, plus a small overzoom
 * allowance. Callers re-derive and re-apply this via
 * `MapLibreMap.setMaxZoomPreference` any time the basemap or the visible
 * MBTiles overlay set changes (see [TacticalMap] and MapScreen's
 * `LaunchedEffect(mbtilesOverlays, ...)`), so the ceiling never gets stuck
 * at a stale value — zooming out is never blocked by a leftover clamp from
 * a since-removed overlay or since-swapped basemap.
 */
internal fun zoomClampFor(basemapMax: Double, mbtilesMaxes: List<Double>): Double =
    (mbtilesMaxes + basemapMax).max() + OVERZOOM_ALLOWANCE

/**
 * #206 — read the basemap raster source's own `maxzoom` back out of a style
 * JSON built by [buildTacticalStyle]. Used by [TacticalMap]'s own
 * style-loaded call sites, which only have the raw JSON string (not a
 * [MapProvider]) to work with — MapScreen, which does have the provider,
 * uses [basemapMaxZoomFor] directly instead.
 *
 * This can't be a simple brace-scoped regex: the `"basemap"` source's own
 * `"tiles"` value is an XYZ URL template containing LITERAL `{z}`/`{x}`/
 * `{y}` braces (and [injectTerrain]'s spliced-in `terrain-dem` source has
 * the same shape, with the same kind of URL, ahead of "basemap" in the
 * text) — a `[^}]*`-style match stops at the first of those, not the
 * source object's real closing brace. Instead this walks the JSON text
 * from `"basemap"`'s own opening brace, tracking string boundaries (so
 * braces inside quoted values never affect nesting depth) until that
 * brace's true match, then searches only within that substring.
 */
internal fun extractBasemapMaxZoom(styleJson: String): Double? {
    val keyIndex = styleJson.indexOf("\"basemap\"")
    if (keyIndex < 0) return null
    val braceStart = styleJson.indexOf('{', keyIndex)
    if (braceStart < 0) return null

    var depth = 0
    var inString = false
    var escapeNext = false
    for (i in braceStart until styleJson.length) {
        val c = styleJson[i]
        if (escapeNext) {
            escapeNext = false
            continue
        }
        when {
            inString && c == '\\' -> escapeNext = true
            c == '"' -> inString = !inString
            !inString && c == '{' -> depth++
            !inString && c == '}' -> {
                depth--
                if (depth == 0) {
                    val basemapBlock = styleJson.substring(braceStart, i + 1)
                    return Regex("\"maxzoom\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)")
                        .find(basemapBlock)?.groupValues?.get(1)?.toDoubleOrNull()
                }
            }
        }
    }
    return null
}

// CONTRACT WITH THE ADS-B PLUGIN: the `aircraft-src` GeoJSON source plus the
// `aircraft-circle` and `aircraft-label` layers below are MapLibre style
// infrastructure shared with :plugins:example-adsb. The plugin's AircraftLayer
// feeds the source via the live map handle. Do NOT remove them thinking they
// belong to ADS-B logic — the plugin's AircraftLayer.update silently no-ops if
// the source is gone, and aircraft stop rendering.
private const val TACTICAL_STYLE_OVERLAYS = """,
    "contacts-src": {
      "type": "geojson",
      "data": {"type": "FeatureCollection", "features": []}
    },
    "measurement-src": {
      "type": "geojson",
      "data": {"type": "FeatureCollection", "features": []}
    },
    "drawings-src": {
      "type": "geojson",
      "data": {"type": "FeatureCollection", "features": []}
    },
    "grid-src": {
      "type": "geojson",
      "data": {"type": "FeatureCollection", "features": []}
    },
    "aircraft-src": {
      "type": "geojson",
      "data": {"type": "FeatureCollection", "features": []}
    }
  },
  "layers": [
    {"id": "basemap-tiles", "type": "raster", "source": "basemap"},
    {
      "id": "grid-line",
      "type": "line",
      "source": "grid-src",
      "paint": {
        "line-color": "#FFC107",
        "line-width": 2,
        "line-opacity": 0.85
      }
    },
    {
      "id": "drawings-fill",
      "type": "fill",
      "source": "drawings-src",
      "filter": ["==", ["get", "kind"], "polygon"],
      "paint": {
        "fill-color": ["coalesce", ["get", "color"], "#4ADE80"],
        "fill-opacity": 0.2
      }
    },
    {
      "id": "drawings-outline",
      "type": "line",
      "source": "drawings-src",
      "paint": {
        "line-color": ["coalesce", ["get", "color"], "#4ADE80"],
        "line-width": 3
      }
    },
    {
      "id": "measurement-line",
      "type": "line",
      "source": "measurement-src",
      "filter": ["==", ["get", "kind"], "line"],
      "paint": {
        "line-color": "#4ADE80",
        "line-width": 3,
        "line-dasharray": [2, 1]
      }
    },
    {
      "id": "measurement-points",
      "type": "circle",
      "source": "measurement-src",
      "filter": ["==", ["get", "kind"], "vertex"],
      "paint": {
        "circle-radius": 6,
        "circle-color": "#4ADE80",
        "circle-stroke-width": 2,
        "circle-stroke-color": "#0A1628"
      }
    },
    {
      "id": "measurement-labels",
      "type": "symbol",
      "source": "measurement-src",
      "filter": ["==", ["get", "kind"], "vertex"],
      "layout": {
        "text-field": ["get", "label"],
        "text-size": 12,
        "text-offset": [0, -1.4],
        "text-allow-overlap": true
      },
      "paint": {
        "text-color": "#FFFFFF",
        "text-halo-color": "#0A1628",
        "text-halo-width": 1.5
      }
    },
    {
      "id": "contacts-circles",
      "type": "circle",
      "source": "contacts-src",
      "paint": {
        "circle-radius": 10,
        "circle-stroke-width": 2,
        "circle-stroke-color": "#0A1628",
        "circle-color": ["to-color", ["coalesce", ["get", "color"], "#B39DDB"]]
      }
    },
    {
      "id": "contacts-labels",
      "type": "symbol",
      "source": "contacts-src",
      "layout": {
        "text-field": ["get", "callsign"],
        "text-size": 11,
        "text-offset": [0, 1.4],
        "text-allow-overlap": false
      },
      "paint": {
        "text-color": "#FFFFFF",
        "text-halo-color": "#0A1628",
        "text-halo-width": 1.5
      }
    },
    {
      "id": "aircraft-circle",
      "type": "circle",
      "source": "aircraft-src",
      "paint": {
        "circle-radius": 7,
        "circle-color": "#60A5FA",
        "circle-stroke-width": 2,
        "circle-stroke-color": "#0A1628"
      }
    },
    {
      "id": "aircraft-label",
      "type": "symbol",
      "source": "aircraft-src",
      "layout": {
        "text-field": ["get", "callsign"],
        "text-size": 10,
        "text-offset": [0, 1.4],
        "text-allow-overlap": false
      },
      "paint": {
        "text-color": "#BFDBFE",
        "text-halo-color": "#0A1628",
        "text-halo-width": 1.5
      }
    }
  ]
}
"""

// Per-provider tactical styles. All wrap the same operational overlays
// around different XYZ raster basemaps. The attribution of each is an HTML
// link (single-quoted, so the style JSON needs no escaping): MapLibre's
// attribution dialog lists only linked credits, and plain text showed none
// (#266). License notes:
//  - OSM: Standard Tile Layer; usage policy applies, fine for low-volume
//  - Topo: OpenTopoMap CC-BY-SA, fine for non-commercial
//  - Satellite: ESRI World Imagery, fine for non-commercial
//  - Dark: CARTO Dark Matter, free with attribution
val TACTICAL_STYLE_OSM = buildTacticalStyle(
    "OmniTAK OSM",
    "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
    "<a href='https://www.openstreetmap.org/copyright'>© OpenStreetMap contributors</a>",
    maxZoom = BASEMAP_MAXZOOM_OSM,
)
val TACTICAL_STYLE_TOPO = buildTacticalStyle(
    "OmniTAK Topo",
    "https://a.tile.opentopomap.org/{z}/{x}/{y}.png",
    "<a href='https://opentopomap.org/about'>© OpenTopoMap (CC-BY-SA)</a> <a href='https://www.openstreetmap.org/copyright'>© OpenStreetMap contributors</a>",
    maxZoom = BASEMAP_MAXZOOM_TOPO,
)
val TACTICAL_STYLE_SATELLITE = buildTacticalStyle(
    "OmniTAK Satellite",
    "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
    "<a href='https://www.esri.com/en-us/legal/copyright-trademarks'>Imagery © Esri, Maxar, Earthstar Geographics, and the GIS User Community</a>",
    maxZoom = BASEMAP_MAXZOOM_SATELLITE,
)
val TACTICAL_STYLE_DARK_MATTER = buildTacticalStyle(
    "OmniTAK Tactical Dark",
    "https://basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png",
    "<a href='https://www.openstreetmap.org/copyright'>© OpenStreetMap contributors</a> <a href='https://carto.com/attributions'>© CARTO</a>",
    maxZoom = BASEMAP_MAXZOOM_DARK_MATTER,
)

/**
 * Normalize tile-URL placeholder syntax to MapLibre/Mapbox convention.
 *
 * CivTAK / ATAK and a handful of WMTS docs use `{$z}/{$y}/{$x}` (dollar
 * inside braces). MapLibre's raster source spec only understands plain
 * `{z}/{y}/{x}` (and `{bbox-epsg-3857}`, `{quadkey}`). Without this
 * rewrite the literal `{$z}` text gets requested → 404 and the tile
 * layer renders empty.
 *
 * Applied once at the boundary, in [styleJsonForProvider], so call
 * sites don't have to remember to normalize. We also strip whitespace
 * — operators paste from chat and frequently get a stray newline.
 */
internal fun normalizeTileUrlPlaceholders(raw: String): String {
    if (raw.isEmpty()) return raw
    var out = raw.trim()
    // Common ATAK / WMTS variants. Order matters only insofar as we
    // want the longest match first; ${z} → {z} happens before {$z} → {z}
    // even though Kotlin's replace is exact-match (no overlap).
    val tokens = listOf("z", "x", "y", "s", "q", "r")
    for (t in tokens) {
        out = out
            .replace("\${$t}", "{$t}")  // ${z}
            .replace("{\$$t}", "{$t}")  // {$z}
    }
    return out
}

/**
 * Map a [MapProvider] preference to its style JSON. For WMTS_CUSTOM,
 * the operator-supplied XYZ URL is wrapped in a fresh tactical style.
 * Falls back to OSM if WMTS_CUSTOM is selected with an empty/invalid URL.
 *
 * Accepts both `{z}/{x}/{y}` (MapLibre / OSM) and `{$z}/{$x}/{$y}`
 * (CivTAK / ATAK) placeholder conventions — the latter is normalized
 * before the URL is handed to MapLibre.
 */
fun styleJsonForProvider(
    provider: MapProvider,
    customTileUrl: String = "",
    terrain3d: Boolean = false,
): String {
    val base = when (provider) {
        MapProvider.OSM_RASTER -> TACTICAL_STYLE_OSM
        MapProvider.TOPO_HINT -> TACTICAL_STYLE_TOPO
        MapProvider.SATELLITE_HINT -> TACTICAL_STYLE_SATELLITE
        MapProvider.WMTS_CUSTOM -> {
            val url = normalizeTileUrlPlaceholders(customTileUrl)
            if (url.startsWith("http") && url.contains("{z}") && url.contains("{x}") && url.contains("{y}")) {
                buildTacticalStyle("OmniTAK Custom WMTS", url, "Custom tile source", maxZoom = BASEMAP_MAXZOOM_CUSTOM_DEFAULT)
            } else {
                TACTICAL_STYLE_OSM
            }
        }
    }
    return if (terrain3d) injectTerrain(base) else base
}

/**
 * Inject a MapLibre 3D terrain layer into an existing style JSON.
 * Adds a `raster-dem` source (AWS Terrarium tiles — free, public,
 * Web-Mercator-aligned with the basemap, so the relief lines up) and a
 * top-level `terrain` property that MapLibre Native v11+ renders as
 * real elevation when the camera is pitched.
 *
 * String-injection rather than full JSON re-serialize keeps the
 * operational layers (which depend on exact source ids) untouched.
 */
internal fun injectTerrain(styleJson: String): String {
    val demSource = """
    "terrain-dem": {
      "type": "raster-dem",
      "tiles": ["https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"],
      "tileSize": 256,
      "encoding": "terrarium",
      "maxzoom": 14,
      "attribution": "<a href='https://registry.opendata.aws/terrain-tiles/'>Terrain: Mapzen, AWS Open Data</a>"
    },"""
    val terrainProp = """  "terrain": {"source": "terrain-dem", "exaggeration": 1.3},
"""
    // 1. Add the DEM source right after the sources block opens.
    var out = styleJson.replaceFirst("\"sources\": {", "\"sources\": {$demSource")
    // 2. Add the top-level terrain property right before the layers array.
    out = out.replaceFirst("  \"layers\": [", "$terrainProp  \"layers\": [")
    return out
}
