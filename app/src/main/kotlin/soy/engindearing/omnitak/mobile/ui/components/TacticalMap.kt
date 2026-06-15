package soy.engindearing.omnitak.mobile.ui.components

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
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
    /** TAK team name used to tint the self-marker — e.g. "Cyan", "Red",
     *  "Orange". Resolved via [TakTeamColor.forName]; unrecognised names
     *  fall back to cyan (0xFF00FFFF) to match CivTAK's default for
     *  unaffiliated friendlies. Sourced from [soy.engindearing.omnitak.mobile.data.UserPrefs.team]. */
    selfTeamColor: String = "Cyan",
    /** Issue #75 — the operator's current/last-known fix from
     *  [soy.engindearing.omnitak.mobile.data.LocationProvider]. Forwarded
     *  into the LocationComponent so the self-marker renders immediately
     *  from a restored fix on cold start (dimmed when stale) and snaps to
     *  the forced foreground-resume fix without waiting on the
     *  component's internal engine interval. */
    selfFix: SelfFix? = null,
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
    /** Fired once the MapLibre map is ready. Issue #16 — lasso uses
     *  this to grab the [MapLibreMap] reference for screen↔geo
     *  projection during freehand selection. */
    onMapReady: ((org.maplibre.android.maps.MapLibreMap) -> Unit)? = null,
    /** Fired after every style (re)load with the live map + style, so the
     *  caller can re-apply style-level content (e.g. KML vector overlays)
     *  that a setStyle wipes. */
    onStyleReady: ((org.maplibre.android.maps.MapLibreMap, Style) -> Unit)? = null,
    /** Issue #89 — system status-bar inset height in dp. Under
     *  enableEdgeToEdge the map draws under the status bar, so the built-in
     *  MapLibre compass margin adds this on top of [COMPASS_TOP_MARGIN_DP]
     *  to clear the (inset-shifted) ATAKStatusBar on tall-notch devices. */
    topInsetDp: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentLongPress by rememberUpdatedState(onMapLongPress)
    val currentContactTap by rememberUpdatedState(onContactTap)
    val currentMapSingleTap by rememberUpdatedState(onMapSingleTap)
    val currentLocationEnabled by rememberUpdatedState(locationEnabled)
    val currentContacts by rememberUpdatedState(contacts)
    val currentMeasurementPoints by rememberUpdatedState(measurementPoints)
    val currentDrawings by rememberUpdatedState(drawings)
    val currentGridCenter by rememberUpdatedState(gridCenter)
    val currentCameraIdle by rememberUpdatedState(onCameraIdle)
    val currentMapReady by rememberUpdatedState(onMapReady)
    val currentStyleReady by rememberUpdatedState(onStyleReady)
    val currentNorthUpLocked by rememberUpdatedState(northUpLocked)
    val currentTerrain3d by rememberUpdatedState(terrain3d)
    val currentSelfFix by rememberUpdatedState(selfFix)
    val currentFollowMe by rememberUpdatedState(followMeActive)
    val currentUseMilStd by rememberUpdatedState(useMilStdSelfSymbol)
    val currentTeamColor by rememberUpdatedState(selfTeamColor)
    // Issue #75 — whether the puck is currently rendered dimmed (stale
    // restored fix). Plain holder, not MutableState: nothing recomposes
    // off it; the effects below read/write it imperatively.
    val puckAppearance = remember { PuckAppearance() }

    // ContactSymbolLayer uses Style.addImage (bitmap) + SymbolLayer — the path
    // LocationComponent uses and that renders on Adreno 610 / SwiftShader when
    // CircleLayer circle-color expressions silently fail (GL fragment pipeline bug).
    // Keyed on styleJson so a basemap swap (style reload) gets a fresh instance
    // with a clean installed=false state.
    val contactSymbolLayer = remember(styleJson) { ContactSymbolLayer() }

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply {
            onCreate(null)
            getMapAsync { map ->
                // Issue #16 — hand the map reference up to the caller
                // so things like the lasso overlay can call
                // map.projection.fromScreenLocation during drags.
                currentMapReady?.invoke(map)
                map.cameraPosition = CameraPosition.Builder()
                    .target(initialCenter)
                    .zoom(initialZoom)
                    .bearing(initialBearing)
                    .build()
                map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                    ContactLayer.update(map, context, currentContacts)
                    // Install bitmap-icon symbol layer alongside the inline circle layer.
                    // The CircleLayer circle-color expression silently fails on Adreno 610
                    // and SwiftShader — ContactSymbolLayer uses Style.addImage (the same
                    // path LocationComponent uses) which renders correctly on both drivers.
                    contactSymbolLayer.installInto(style, context)
                    contactSymbolLayer.update(map, context, currentContacts)
                    MeasurementLayer.update(map, currentMeasurementPoints)
                    DrawingLayer.update(map, currentDrawings)
                    currentGridCenter?.let { GridLayer.update(map, it) }
                    // ADS-B aircraft are fed by the ADS-B plugin's overlay via
                    // the live map handle — not from here. The `aircraft-src`
                    // source stays empty until the plugin pushes into it.
                    if (currentLocationEnabled) {
                        activateLocation(
                            map, style, context, currentUseMilStd, currentTeamColor,
                            seedFix = currentSelfFix, puck = puckAppearance,
                        )
                    }
                    // Cold-start 3D: if the persisted pref has terrain on,
                    // apply the tilt once the style (+ terrain source) is
                    // loaded. Instant move (no animation) on first paint.
                    if (currentTerrain3d) {
                        map.cameraPosition = CameraPosition.Builder(map.cameraPosition)
                            .tilt(55.0).build()
                    }
                    currentStyleReady?.invoke(map, style)
                }
                map.uiSettings.apply {
                    isCompassEnabled = true
                    isLogoEnabled = false
                    isAttributionEnabled = true
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
                    if (currentNorthUpLocked && kotlin.math.abs(pos.bearing) > 0.5) {
                        map.animateCamera(
                            CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder(pos).bearing(0.0).build()
                            ),
                            250,
                        )
                        return@addOnCameraIdleListener
                    }
                    currentCameraIdle?.invoke(target, pos.zoom, pos.bearing)
                }
                map.addOnMapLongClickListener { latLng ->
                    val screen = map.projection.toScreenLocation(latLng)
                    currentLongPress?.invoke(latLng, Offset(screen.x, screen.y))
                    true
                }
                map.addOnMapClickListener { latLng ->
                    // Mode-specific tap handler wins when provided
                    // (e.g. measurement mode eats taps to add points).
                    currentMapSingleTap?.let { handler ->
                        if (handler(latLng)) return@addOnMapClickListener true
                    }
                    val cb = currentContactTap ?: return@addOnMapClickListener false
                    val tapPx = map.projection.toScreenLocation(latLng)
                    var best: CoTEvent? = null
                    var bestDist = Float.MAX_VALUE
                    currentContacts.forEach { c ->
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
            addOnDidFinishLoadingStyleListener {
                getMapAsync { map ->
                    map.getStyle { style ->
                        ContactLayer.update(map, context, currentContacts)
                        contactSymbolLayer.installInto(style, context)
                        contactSymbolLayer.update(map, context, currentContacts)
                        MeasurementLayer.update(map, currentMeasurementPoints)
                        DrawingLayer.update(map, currentDrawings)
                        currentGridCenter?.let { GridLayer.update(map, it) }
                    }
                }
            }
        }
    }

    // Flip the location layer on when permission is granted after the
    // map is already alive.
    DisposableEffect(mapView, locationEnabled) {
        if (locationEnabled) {
            mapView.getMapAsync { map ->
                val style = map.style
                if (style != null && !map.locationComponent.isLocationComponentActivated) {
                    activateLocation(
                        map, style, context, currentUseMilStd, currentTeamColor,
                        seedFix = currentSelfFix, puck = puckAppearance,
                    )
                }
                if (map.locationComponent.isLocationComponentActivated) {
                    safeEnableLocation(map)
                }
            }
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
    DisposableEffect(mapView, selfFix) {
        val fix = selfFix
        if (fix != null && locationEnabled) {
            mapView.getMapAsync { map ->
                val style = map.style
                if (style != null && map.locationComponent.isLocationComponentActivated) {
                    map.locationComponent.forceLocationUpdate(fix.toLocation())
                    val staleNow =
                        SelfFixPersistence.isStale(fix.timeMs, System.currentTimeMillis())
                    if (puckAppearance.dimmed != staleNow) {
                        map.locationComponent.applyStyle(
                            buildPuckOptions(
                                context, style, currentUseMilStd, currentTeamColor,
                                dimmed = staleNow,
                            ),
                        )
                        puckAppearance.dimmed = staleNow
                    }
                }
            }
        }
        onDispose { }
    }

    // Each time [recenterTrigger] changes, briefly flip camera to
    // TRACKING to pan to the user, then restore NONE so the user can
    // still pan freely.
    DisposableEffect(mapView, recenterTrigger) {
        if (recenterTrigger != null && locationEnabled) {
            mapView.getMapAsync { map ->
                if (map.locationComponent.isLocationComponentActivated) {
                    map.locationComponent.cameraMode = CameraMode.TRACKING
                    map.locationComponent.zoomWhileTracking(15.0)
                }
            }
        }
        onDispose { }
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
                    ContactLayer.update(map, context, currentContacts)
                    contactSymbolLayer.installInto(style, context)
                    contactSymbolLayer.update(map, context, currentContacts)
                    MeasurementLayer.update(map, currentMeasurementPoints)
                    DrawingLayer.update(map, currentDrawings)
                    currentGridCenter?.let { GridLayer.update(map, it) }
                    // ADS-B re-push after a style reload is handled by the
                    // plugin overlay's LaunchedEffect(map, …), which re-runs
                    // when the new MapLibreMap/style lands. Nothing to do here.
                    // Issue #75 — the self-marker bitmaps live on the style,
                    // so a basemap swap wipes them. Re-register + re-apply so
                    // the puck survives style reloads with its current
                    // (dimmed or live) appearance.
                    if (currentLocationEnabled && map.locationComponent.isLocationComponentActivated) {
                        map.locationComponent.applyStyle(
                            buildPuckOptions(
                                context, style, currentUseMilStd, currentTeamColor,
                                dimmed = puckAppearance.dimmed,
                            ),
                        )
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
            if (map.style != null) DrawingLayer.update(map, drawings)
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
                if (map.locationComponent.isLocationComponentActivated) {
                    map.locationComponent.cameraMode = CameraMode.NONE
                }
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
    DisposableEffect(mapView, followMeActive, locationEnabled) {
        if (locationEnabled) {
            mapView.getMapAsync { map ->
                if (map.locationComponent.isLocationComponentActivated) {
                    map.locationComponent.cameraMode = if (followMeActive) {
                        CameraMode.TRACKING_COMPASS
                    } else {
                        CameraMode.NONE
                    }
                }
            }
        }
        onDispose { }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> {
                    mapView.onResume()
                    // Issue #75 (root cause) — ON_PAUSE below silences the
                    // LocationComponent to kill its compass animator, but
                    // nothing ever re-enabled it: after screen-off/on the
                    // self-marker stayed hidden until the composable was
                    // rebuilt. Re-enable on every resume, restoring the
                    // camera mode follow-me expects.
                    if (currentLocationEnabled) {
                        runCatching {
                            mapView.getMapAsync { map ->
                                if (map.locationComponent.isLocationComponentActivated) {
                                    map.locationComponent.isLocationComponentEnabled = true
                                    map.locationComponent.renderMode = RenderMode.COMPASS
                                    map.locationComponent.cameraMode =
                                        if (currentFollowMe) CameraMode.TRACKING_COMPASS
                                        else CameraMode.NONE
                                }
                            }
                        }
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
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
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Nav-away via bottom tabs doesn't fire ON_PAUSE (the hosting
            // Activity stays RESUMED), so we have to silence the
            // LocationComponent here too to kill the compass animator
            // before tearing the MapView down.
            runCatching {
                mapView.getMapAsync { map ->
                    if (map.locationComponent.isLocationComponentActivated) {
                        map.locationComponent.isLocationComponentEnabled = false
                    }
                }
            }
            runCatching { mapView.onPause() }
            runCatching { mapView.onStop() }
            runCatching { mapView.onDestroy() }
        }
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
                    ContactLayer.update(map, context, currentContacts)
                    contactSymbolLayer.update(map, context, currentContacts)
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

/** Issue #75 — imperative holder for the self-marker's current dim state
 *  (stale restored fix vs live GPS). Shared between the activation path,
 *  the selfFix forwarding effect, and the style-reload re-apply. */
internal class PuckAppearance {
    @Volatile var dimmed: Boolean = false
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
            buildPuckOptions(context, style, useMilStdSelfSymbol, selfTeamColor, dimmed),
        )
        .build()
    map.locationComponent.activateLocationComponent(options)
    safeEnableLocation(map)

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
): LocationComponentOptions {
    // Resolve the ARGB tint from the operator's configured TAK team name
    // ("Cyan", "Red", "Orange", …). Falls back to cyan — CivTAK default
    // for unaffiliated friendlies — when the name isn't in the palette.
    val teamArgb: Int = TakTeamColor.forName(selfTeamColor) ?: 0xFF00FFFF.toInt()

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
        // Register the bitmaps with the style so LocationComponent can
        // reference them by name — MapLibre's LocationComponentOptions
        // builder accepts resource IDs or names, not direct bitmaps.
        // Using the same bitmap for foreground and bearing keeps the
        // symbol upright regardless of heading; the bearing-arrow
        // chevron is intentionally omitted here because MIL-STD
        // symbol bodies aren't meant to rotate. Operator heading is
        // still surfaced numerically in the SelfPositionCard.
        style.addImage(SELF_FOREGROUND_IMAGE, selfBitmap)
        style.addImage(SELF_FOREGROUND_STALE_IMAGE, fadeBitmap(selfBitmap, STALE_MARKER_ALPHA))
        val foreground = if (dimmed) SELF_FOREGROUND_STALE_IMAGE else SELF_FOREGROUND_IMAGE
        markerOptionsBuilder
            .foregroundName(foreground)
            .bearingName(foreground)
            .foregroundStaleName(SELF_FOREGROUND_STALE_IMAGE)
    } else {
        // Legacy tinted-disc fallback — apply the team color as a tint
        // on the vector drawable so the disc still reflects team identity.
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

private const val SELF_FOREGROUND_IMAGE = "omnitak-self-milstd-foreground"
private const val SELF_FOREGROUND_STALE_IMAGE = "omnitak-self-milstd-foreground-stale"

/** Stale self-marker opacity (0–255). ~45% — subtle, but unmistakably
 *  dimmer than the live marker (issue #75). */
private const val STALE_MARKER_ALPHA = 115

@SuppressLint("MissingPermission")
private fun safeEnableLocation(map: org.maplibre.android.maps.MapLibreMap) {
    map.locationComponent.isLocationComponentEnabled = true
    map.locationComponent.renderMode = RenderMode.COMPASS
    map.locationComponent.cameraMode = CameraMode.NONE
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
internal fun buildTacticalStyle(name: String, basemapTiles: String, attribution: String): String =
    TACTICAL_STYLE_HEAD
        .replace("@@NAME@@", name)
        .replace("@@TILES@@", basemapTiles)
        .replace("@@ATTRIBUTION@@", attribution) + TACTICAL_STYLE_OVERLAYS

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
      "maxzoom": 20,
      "attribution": "@@ATTRIBUTION@@"
    }
"""

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
// around different XYZ raster basemaps. License notes:
//  - OSM: Standard Tile Layer; usage policy applies, fine for low-volume
//  - Topo: OpenTopoMap CC-BY-SA, fine for non-commercial
//  - Satellite: ESRI World Imagery, fine for non-commercial
//  - Dark: CARTO Dark Matter, free with attribution
val TACTICAL_STYLE_OSM = buildTacticalStyle(
    "OmniTAK OSM",
    "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
    "© OpenStreetMap contributors",
)
val TACTICAL_STYLE_TOPO = buildTacticalStyle(
    "OmniTAK Topo",
    "https://a.tile.opentopomap.org/{z}/{x}/{y}.png",
    "© OpenTopoMap (CC-BY-SA), © OpenStreetMap contributors",
)
val TACTICAL_STYLE_SATELLITE = buildTacticalStyle(
    "OmniTAK Satellite",
    "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
    "Imagery © Esri, Maxar, Earthstar Geographics, and the GIS User Community",
)
val TACTICAL_STYLE_DARK_MATTER = buildTacticalStyle(
    "OmniTAK Tactical Dark",
    "https://basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png",
    "© OpenStreetMap contributors © CARTO",
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
                buildTacticalStyle("OmniTAK Custom WMTS", url, "Custom tile source")
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
      "attribution": "Terrain: AWS / Mapzen"
    },"""
    val terrainProp = """  "terrain": {"source": "terrain-dem", "exaggeration": 1.3},
"""
    // 1. Add the DEM source right after the sources block opens.
    var out = styleJson.replaceFirst("\"sources\": {", "\"sources\": {$demSource")
    // 2. Add the top-level terrain property right before the layers array.
    out = out.replaceFirst("  \"layers\": [", "$terrainProp  \"layers\": [")
    return out
}
