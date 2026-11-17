package soy.engindearing.omnitak.mobile.ui.components

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import soy.engindearing.omnitak.mobile.BuildConfig
import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.Drawing
import soy.engindearing.omnitak.mobile.data.geofence.GeoBounds
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceFraming

/**
 * Photoreal Cesium 3D globe map engine, hosted in a WebView. Mirrors the
 * iOS Cesium scene — same `cesium_scene.html` bridge (OmniBridge.setEntities
 * etc.) and the same tap / long-press / camera events posted back to native
 * via `window.OmniBridgeNative`.
 *
 * Renders contacts (including dropped pins) and the operator's own position
 * as MIL-STD-affiliation billboards. Long-press surfaces the same radial /
 * drop flow the MapLibre map uses (the menu overlay is engine-agnostic);
 * tapping a contact opens its edit sheet. MapLibre-specific overlays
 * (drone, FAA, lasso, drawings) stay on the 2D / terrain engines for now.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CesiumMapView(
    contacts: List<CoTEvent>,
    selfLat: Double?,
    selfLon: Double?,
    selfCallsign: String,
    onLongPress: (LatLng, Offset) -> Unit,
    onContactTap: (CoTEvent) -> Unit,
    // #82 — tap on the self entity opens the reposition sheet, parity with the
    // 2D engine's onSelfMarkerTap. The scene already tags the self billboard
    // `__self__` and posts that uid on tap; this routes it back to native.
    onSelfMarkerTap: (() -> Unit)? = null,
    // #83 — render the self entity as a heading-rotated triangle (vs the
    // friendly circle) when the operator's triangle self-marker pref is on.
    selfMarkerTriangle: Boolean = false,
    // #83 — device compass heading (deg CW from north) driving the triangle's
    // rotation; null leaves it pointing north.
    selfHeadingDeg: Float? = null,
    // #210 - draw the self entity. False hides it on the globe (UI only); the
    // raw selfLat/selfLon still feed "Center on me" below.
    selfMarkerVisible: Boolean = true,
    // Issue #79 — operator drawings (line / polygon / circle) rendered on the
    // globe via the cesium_scene.html setDrawings bridge, so a shape made on
    // the 2D engine doesn't vanish when the operator switches to 3D.
    drawings: List<Drawing> = emptyList(),
    // Camera events from the scene: target, web-mercator zoom, heading
    // (degrees clockwise from north — MapLibre's bearing convention).
    // Heading rides along so the 3D→2D switch keeps the rotation (#78).
    onCameraChanged: (LatLng, Double, Double) -> Unit,
    modifier: Modifier = Modifier,
    // #78 — 2D→3D engine-switch handoff. When non-null (the operator was
    // looking somewhere on the 2D engine), the globe opens at this
    // viewport instead of the scene's legacy (0,0) world intro flight.
    // Captured once per activation by MapScreen; seeded on bridge-ready.
    initialCamera: CesiumCameraSeed? = null,
    // Tick counters from the on-screen map controls. Incrementing one fires
    // the matching camera command on the globe. Mirror the TacticalMap
    // (2D) wiring so the +/- zoom and "center on me" buttons work on 3D too.
    zoomInTrigger: Int = 0,
    zoomOutTrigger: Int = 0,
    recenterTrigger: Int = 0,
    // Pan-to-coordinate parity with TacticalMap — Go-to-Coordinate "Go",
    // ContactsPanel taps and the radial Center action all set a pan
    // target; the globe flies there via the scene bridge instead of
    // silently ignoring it (the "panel never appears on Cesium" class).
    panTarget: LatLng? = null,
    panTargetTick: Int = 0,
    /** A box to show whole ("Show on map" for a geofence, #155): the globe looks straight down on it from
     *  as high as it takes to fit it, whenever [frameBoundsTick] goes up. Same request as the 2D map's. */
    frameBounds: GeoBounds? = null,
    frameBoundsTick: Int = 0,
    /** Issue #95 — when true, globe rotates back to heading 0 (north-up)
     *  and the scene's rotation gestures are suppressed via the JS bridge.
     *  The Cesium HTML side must implement OmniBridge.setNorthUpLocked(bool). */
    northUpLocked: Boolean = false,
    /** Issue #95/#96 — tap the compass → snap globe heading to north. */
    snapNorthTrigger: Int = 0,
    /** The scale bar's holder. The globe is told where the bar's row is (setScaleAnchor) and reports the
     *  ground scale it measured there with every camera event. See [MapScaleSource]. */
    scaleSource: MapScaleSource? = null,
) {
    val density = LocalDensity.current.density
    // #98 — app context to rasterise the Markers / Google badge glyphs into the
    // `icon` data-URLs the globe billboards (held across recompositions so the
    // pushEntities closure can read it).
    val appContext = LocalContext.current.applicationContext
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val ready = remember { mutableStateOf(false) }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    // The zoom of the last camera event: how far out the camera is, for "Center on me" below.
    val cameraZoom = remember { mutableDoubleStateOf(Double.NaN) }

    val contactsState = rememberUpdatedState(contacts)
    val drawingsState = rememberUpdatedState(drawings)
    val selfLatState = rememberUpdatedState(selfLat)
    val selfLonState = rememberUpdatedState(selfLon)
    val selfCallsignState = rememberUpdatedState(selfCallsign)
    val onLongPressState = rememberUpdatedState(onLongPress)
    val onContactTapState = rememberUpdatedState(onContactTap)
    val onSelfMarkerTapState = rememberUpdatedState(onSelfMarkerTap)
    val selfTriangleState = rememberUpdatedState(selfMarkerTriangle)
    val selfHeadingState = rememberUpdatedState(selfHeadingDeg)
    val selfMarkerVisibleState = rememberUpdatedState(selfMarkerVisible)
    val onCameraState = rememberUpdatedState(onCameraChanged)
    val scaleSourceState = rememberUpdatedState(scaleSource)
    // #95 — read the latest lock state inside the JS bridge callbacks so a
    // globe that opens while north-up is already on gets the lock applied on
    // bridge-ready (the change-driven DisposableEffect below only fires on a
    // toggle, never on first composition).
    val northUpLockedState = rememberUpdatedState(northUpLocked)

    fun pushEntities() {
        val wv = webViewRef.value ?: return
        if (!ready.value) return
        val json = buildCesiumEntitiesJson(
            contactsState.value, selfLatState.value, selfLonState.value, selfCallsignState.value,
            context = appContext,
            selfTriangle = selfTriangleState.value,
            selfHeadingDeg = selfHeadingState.value,
            selfVisible = selfMarkerVisibleState.value,
        )
        wv.evaluateJavascript("window.OmniBridge.setEntities($json);", null)
    }

    // Issue #79 — push the current drawing roster to the globe. The JS bridge
    // diffs against its own `_state.drawings` map, so this is idempotent and
    // also removes drawings the operator deleted (#76 parity on the globe).
    fun pushDrawings() {
        val wv = webViewRef.value ?: return
        if (!ready.value) return
        val json = buildCesiumDrawingsJson(drawingsState.value)
        wv.evaluateJavascript("window.OmniBridge.setDrawings($json);", null)
    }

    // #78 — seed the globe camera from the 2D engine's viewport on
    // bridge-ready. Zoom → height via CesiumCameraMath (the exact inverse
    // of the scene's _zoomFromHeight, so toggling engines never
    // zoom-creeps). Fields are validated finite before being
    // interpolated into JS source.
    fun seedInheritedCamera() {
        val wv = webViewRef.value ?: return
        val seed = initialCamera
        if (seed == null || !seed.isValid) return
        val height = CesiumCameraMath.heightForZoom(seed.zoom, seed.lat)
        wv.evaluateJavascript(
            "window.OmniBridge.setCamera(" +
                "{lat:${seed.lat},lon:${seed.lon},height:$height,heading:${seed.bearing}});",
            null,
        )
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                webViewRef.value = this
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                setBackgroundColor(android.graphics.Color.BLACK)
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onReady() {
                            mainHandler.post {
                                ready.value = true
                                // Inherit the 2D viewport before the first
                                // entity push so the scene opens where the
                                // operator was looking (#78).
                                seedInheritedCamera()
                                pushEntities()
                                // #79 — paint operator drawings on first ready
                                // so a shape made on the 2D engine is already
                                // on the globe when it opens.
                                pushDrawings()
                                // #95 — apply the persisted north-up lock on
                                // first ready so the globe respects it without
                                // waiting for a toggle.
                                if (northUpLockedState.value) {
                                    webViewRef.value?.evaluateJavascript(
                                        "if(window.OmniBridge&&window.OmniBridge.setNorthUpLocked)" +
                                            "window.OmniBridge.setNorthUpLocked(true);",
                                        null,
                                    )
                                }
                            }
                        }

                        @JavascriptInterface
                        fun onMapEvent(json: String) {
                            val o = runCatching { JSONObject(json) }.getOrNull() ?: return
                            // The scale changed under the bar without the camera moving (tiles arrived):
                            // it has no position, so it is read before the position is required.
                            scaleChangeFromEvent(o)?.let { change ->
                                mainHandler.post { scaleSourceState.value?.reportGlobe(change.metersPerDp) }
                                return
                            }
                            val event = o.optString("event")
                            val lat = o.optDouble("lat")
                            val lon = o.optDouble("lon")
                            if (lat.isNaN() || lon.isNaN()) return
                            mainHandler.post {
                                when (event) {
                                    "tap" -> {
                                        val uid = if (o.isNull("uid")) null else o.optString("uid")
                                        when {
                                            // #82 — tapping the self entity opens the
                                            // reposition sheet (parity with TacticalMap's
                                            // onSelfMarkerTap on the 2D engine).
                                            uid == "__self__" -> onSelfMarkerTapState.value?.invoke()
                                            !uid.isNullOrEmpty() ->
                                                contactsState.value.firstOrNull { it.uid == uid }
                                                    ?.let { onContactTapState.value(it) }
                                        }
                                    }
                                    "longpress" -> {
                                        val sx = (o.optDouble("screenX", 0.0) * density).toFloat()
                                        val sy = (o.optDouble("screenY", 0.0) * density).toFloat()
                                        onLongPressState.value(LatLng(lat, lon), Offset(sx, sy))
                                    }
                                    "camerachanged" -> {
                                        val zoom = o.optDouble("zoom", 11.0)
                                        cameraZoom.doubleValue = zoom
                                        onCameraState.value(
                                            LatLng(lat, lon),
                                            zoom,
                                            o.optDouble("heading", 0.0),
                                        )
                                        // The ground scale along the scale bar's row, measured by the page.
                                        scaleSourceState.value?.reportGlobe(metersPerDpFromCameraEvent(o))
                                    }
                                }
                            }
                        }
                    },
                    "OmniBridgeNative",
                )
                val html = ctx.assets.open("cesium_scene.html")
                    .bufferedReader().use { it.readText() }
                    // Ion token is injected here from BuildConfig (fed by the
                    // gitignored local.properties) so the committed asset in
                    // this public repo never carries a literal token. Empty
                    // token = tokenless Cesium (degraded Ion imagery), not a
                    // crash.
                    .replace("__CESIUM_ION_TOKEN__", BuildConfig.CESIUM_ION_TOKEN)
                loadDataWithBaseURL("https://cesium.com/", html, "text/html", "UTF-8", null)
            }
        },
        update = {
            pushEntities()
            // #79 — recomposition delivers a fresh drawings list (add / edit /
            // move / delete); mirror the entity push so the globe stays in sync.
            pushDrawings()
        },
    )

    // Map-control buttons → globe camera. The JS guards no-op until the
    // viewer exists, but gate on `ready` too so a stray early tick is dropped.
    // Skip the initial 0 so the first composition doesn't fire a command.
    DisposableEffect(zoomInTrigger) {
        if (zoomInTrigger > 0 && ready.value) {
            webViewRef.value?.evaluateJavascript("window.OmniBridge.zoomIn();", null)
        }
        onDispose { }
    }
    DisposableEffect(zoomOutTrigger) {
        if (zoomOutTrigger > 0 && ready.value) {
            webViewRef.value?.evaluateJavascript("window.OmniBridge.zoomOut();", null)
        }
        onDispose { }
    }
    DisposableEffect(recenterTrigger) {
        if (recenterTrigger > 0 && ready.value) {
            val lat = selfLatState.value
            val lon = selfLonState.value
            if (lat != null && lon != null && !lat.isNaN() && !lon.isNaN()) {
                // From far out the camera also comes in to street level, the same rule as the 2D map;
                // from zoom 12 or closer it keeps its height.
                val height = CesiumCameraMath.heightForOperator(cameraZoom.doubleValue, lat)
                val args = if (height != null) "{lat:$lat,lon:$lon,height:$height}" else "{lat:$lat,lon:$lon}"
                webViewRef.value?.evaluateJavascript("window.OmniBridge.centerOnSelf($args);", null)
            }
        }
        onDispose { }
    }
    DisposableEffect(panTargetTick) {
        val t = panTarget
        if (panTargetTick > 0 && t != null && ready.value) {
            webViewRef.value?.evaluateJavascript(
                "window.OmniBridge.flyTo({lat:${t.latitude},lon:${t.longitude},range:3000});",
                null,
            )
        }
        onDispose { }
    }

    // Show a whole box (a geofence's zone, #155). The framing says how much ground to see across the screen;
    // the page works the camera height out from its own field of view, over the ground there, and flies to it
    // looking straight down with north up.
    DisposableEffect(frameBoundsTick) {
        val bounds = frameBounds
        val wv = webViewRef.value
        if (frameBoundsTick > 0 && bounds != null && ready.value && wv != null) {
            val camera = GeofenceFraming.cameraFor(
                bounds,
                viewWidthDp = wv.width / density.toDouble(),
                viewHeightDp = wv.height / density.toDouble(),
            )
            wv.evaluateJavascript(
                "window.OmniBridge.lookDown({lat:${camera.lat},lon:${camera.lon},width:${camera.groundWidthMeters}});",
                null,
            )
        }
        onDispose { }
    }

    // Issue #95 — north-up lock on the Cesium globe. When ready, push the
    // lock state to the JS bridge.  setNorthUpLocked(true) should disable
    // rotation gestures in the scene and fly to heading 0; false restores.
    DisposableEffect(northUpLocked) {
        if (ready.value) {
            webViewRef.value?.evaluateJavascript(
                "if(window.OmniBridge&&window.OmniBridge.setNorthUpLocked)" +
                    "window.OmniBridge.setNorthUpLocked(${northUpLocked});",
                null,
            )
        }
        onDispose { }
    }
    // The scale bar's row: told to the page once the globe is ready and whenever the bar is laid out
    // or moves (rotation, the toolbar lifting it), so the page measures the scale there.
    LaunchedEffect(scaleSource) {
        val source = scaleSource ?: return@LaunchedEffect
        snapshotFlow { if (ready.value) source.anchorDp.value else null }.collect { at ->
            if (at != null) {
                webViewRef.value?.evaluateJavascript("window.OmniBridge.setScaleAnchor(${at.x},${at.y});", null)
            }
        }
    }
    // Issue #95/#96 — manual snap to north (compass tap when lock is off).
    DisposableEffect(snapNorthTrigger) {
        if (snapNorthTrigger > 0 && ready.value) {
            webViewRef.value?.evaluateJavascript(
                "if(window.OmniBridge&&window.OmniBridge.snapToNorth)" +
                    "window.OmniBridge.snapToNorth();",
                null,
            )
        }
        onDispose { }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef.value?.destroy()
            webViewRef.value = null
        }
    }
}

/**
 * The `mpp` of a `camerachanged` or `scalechanged` event: ground metres per CSS px (= dp) along the
 * scale bar's row, measured by the page. Null when it is absent, JSON null (too few points on the
 * globe), not finite or not positive.
 */
internal fun metersPerDpFromCameraEvent(event: JSONObject): Double? {
    if (!event.has("mpp") || event.isNull("mpp")) return null
    val value = event.optDouble("mpp", Double.NaN)
    return if (value.isFinite() && value > 0.0) value else null
}

/** What a `scalechanged` event tells the scale bar: the new ground scale, or none. */
internal data class ScaleChange(val metersPerDp: Double?)

/**
 * The page posts `{event:"scalechanged", mpp}` when the surface under the bar changed without a
 * camera move (the 3D tiles for the view finished loading) and the scale it measures there moved by
 * more than 1 percent. It carries no position, so it is not a camera event and does not touch the
 * saved camera. Null for any other event.
 */
internal fun scaleChangeFromEvent(event: JSONObject): ScaleChange? =
    if (event.optString("event") == "scalechanged") ScaleChange(metersPerDpFromCameraEvent(event)) else null
