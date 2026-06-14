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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import soy.engindearing.omnitak.mobile.BuildConfig
import soy.engindearing.omnitak.mobile.data.CoTEvent

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
    /** Issue #95 — when true, globe rotates back to heading 0 (north-up)
     *  and the scene's rotation gestures are suppressed via the JS bridge.
     *  The Cesium HTML side must implement OmniBridge.setNorthUpLocked(bool). */
    northUpLocked: Boolean = false,
    /** Issue #95/#96 — tap the compass → snap globe heading to north. */
    snapNorthTrigger: Int = 0,
) {
    val density = LocalDensity.current.density
    // #98 — app context to rasterise the Markers / Google badge glyphs into the
    // `icon` data-URLs the globe billboards (held across recompositions so the
    // pushEntities closure can read it).
    val appContext = LocalContext.current.applicationContext
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val ready = remember { mutableStateOf(false) }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    val contactsState = rememberUpdatedState(contacts)
    val selfLatState = rememberUpdatedState(selfLat)
    val selfLonState = rememberUpdatedState(selfLon)
    val selfCallsignState = rememberUpdatedState(selfCallsign)
    val onLongPressState = rememberUpdatedState(onLongPress)
    val onContactTapState = rememberUpdatedState(onContactTap)
    val onCameraState = rememberUpdatedState(onCameraChanged)
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
        )
        wv.evaluateJavascript("window.OmniBridge.setEntities($json);", null)
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
                            val event = o.optString("event")
                            val lat = o.optDouble("lat")
                            val lon = o.optDouble("lon")
                            if (lat.isNaN() || lon.isNaN()) return
                            mainHandler.post {
                                when (event) {
                                    "tap" -> {
                                        val uid = if (o.isNull("uid")) null else o.optString("uid")
                                        if (!uid.isNullOrEmpty() && uid != "__self__") {
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
                                        onCameraState.value(
                                            LatLng(lat, lon),
                                            o.optDouble("zoom", 11.0),
                                            o.optDouble("heading", 0.0),
                                        )
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
        update = { pushEntities() },
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
                webViewRef.value?.evaluateJavascript(
                    "window.OmniBridge.centerOnSelf({lat:$lat,lon:$lon});", null,
                )
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

