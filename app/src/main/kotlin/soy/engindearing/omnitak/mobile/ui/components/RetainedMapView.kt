package soy.engindearing.omnitak.mobile.ui.components

import android.content.Context
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.SelfFix

/**
 * #177 — process-lifetime holder for the single MapLibre [MapView].
 *
 * The map composable used to create its [MapView] in a plain `remember {}`, so
 * any time the map destination left composition (notably navigating to a
 * full-screen destination like Settings) the view was disposed and
 * `onDestroy()`-ed, forcing a cold MapLibre re-initialisation — a multi-second
 * reload — on return. (Map↔Chat felt instant because those toggles kept the GL
 * context / tile cache warm; a heavier Settings detour evicted it.)
 *
 * Retaining the one native MapView across compositions keeps the engine, style,
 * tiles and camera alive so returning to the map is instant. The view is only
 * truly torn down when the process goes away.
 *
 * Because the retained MapView's one-time gesture/idle listeners are registered
 * once (at first creation) but the map composable is recreated on every return,
 * those listeners must NOT capture composition-scoped `rememberUpdatedState`
 * holders — those stop updating once their composition is disposed, which would
 * leave map taps wired to a dead composition. Instead the listeners read live
 * callbacks/state from [bindings], a stable holder the composable refreshes on
 * every recomposition.
 *
 * IMPORTANT: a single Android View can have at most one parent. The map
 * composable must [detach] the view from its old parent before the `AndroidView`
 * re-attaches it, otherwise Compose throws "The specified child already has a
 * parent."
 *
 * Not thread-safe by design — only touched from the main thread (composition).
 */
internal object RetainedMapView {

    /**
     * Live wiring the retained MapView's one-time listeners read through. The
     * composable overwrites these fields every recomposition so a retained
     * MapView always invokes the current composition's callbacks with the
     * current state, never a stale snapshot from the composition that first
     * created the view.
     */
    class Bindings {
        var onMapReady: ((MapLibreMap) -> Unit)? = null
        var onStyleReady: ((MapLibreMap, Style) -> Unit)? = null
        var onCameraIdle: ((LatLng, Double, Double) -> Unit)? = null
        var onLongPress: ((LatLng, Offset) -> Unit)? = null
        var onMapSingleTap: ((LatLng) -> Boolean)? = null
        var onContactTap: ((CoTEvent) -> Unit)? = null
        var onSelfMarkerTap: (() -> Unit)? = null
        var northUpLocked: Boolean = false
        var selfFix: SelfFix? = null
        // #210 - whether the puck (LocationComponent) may run right now: location
        // available AND the operator has not hidden their marker. Read by the
        // one-time style-load closures, which must not capture a composition-
        // scoped holder (stale after a Settings detour: a hidden marker would
        // come back on the next style reload).
        var puckActive: Boolean = false
        var contacts: Collection<CoTEvent> = emptyList()
    }

    val bindings = Bindings()

    private var instance: MapView? = null

    /**
     * Return the retained [MapView], creating it via [factory] on first use.
     * [appContext] should be the application context so the long-lived view does
     * not leak an Activity.
     */
    fun acquire(appContext: Context, factory: (Context) -> MapView): MapView {
        val existing = instance
        if (existing != null) {
            detach(existing)
            return existing
        }
        return factory(appContext).also { instance = it }
    }

    /** Remove the view from its current parent so it can be re-attached. */
    fun detach(view: MapView) {
        (view.parent as? ViewGroup)?.removeView(view)
    }
}
