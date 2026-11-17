package soy.engindearing.omnitak.mobile.ui.components

import android.content.Context
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.Lifecycle
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.Drawing
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
 * That includes outliving the Activity. The Activity is destroyed and rebuilt
 * while the process lives far more often than it looks: the system theme
 * changing (dark mode on a schedule, Battery Saver switching it on), a font or
 * display size change, a language change, a keyboard being attached, Back on
 * Android 11 and older, the app being swiped out of Recents while the
 * connection service keeps the process alive. Nothing may call `onDestroy()`
 * on the view for any of those: the next Activity is handed this same view,
 * and a destroyed MapView has no style and no native map behind it (through
 * 0.45.0 it was destroyed and handed out again, and the app died on the first
 * call that reached the location layer).
 *
 * Two things follow from keeping it that long. The location component is
 * activated with the Activity that first shows the map and keeps that
 * Activity reachable: one Activity for the life of the process, not one per
 * rebuild. And MapLibre reads the display density once, when the view is
 * built: after a display size change the map keeps its old scale for symbols
 * and the marker until the process restarts.
 *
 * Because the view outlives whoever shows it, two compositions can briefly
 * both believe it is theirs: when an Activity is finished and opened again,
 * the new one is created before the old one has been stopped and destroyed.
 * [acquire] records who took the view last, and only that composition may
 * drive it ([isHeldBy]). Anything a replaced composition still does
 * (forwarding its late onStop, detaching the view when it is disposed) would
 * pull the map out from under the one on screen.
 *
 * Because the retained MapView's one-time gesture/idle listeners are registered
 * once (at first creation) but the map composable is recreated on every return,
 * those listeners must NOT capture composition-scoped `rememberUpdatedState`
 * holders — those stop updating once their composition is disposed, which would
 * leave map taps wired to a dead composition. Instead the listeners read live
 * callbacks/state from [bindings], a stable holder that the composable showing
 * the view keeps current.
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
     * composable that shows the view keeps these fields current (the callbacks
     * after every recomposition, the state before the other effects of a pass)
     * so a retained MapView always invokes the current composition's callbacks
     * with the current state, never a stale snapshot from the composition that
     * first created the view.
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
        // What the style-reload listener puts back on the map. It is registered
        // once and outlives the composition that registered it, so it reads
        // these and not that composition's own state: after the first trip
        // away from the map tab that state is frozen, and every later style
        // reload (basemap change, 3D terrain) redrew the drawings, measurement
        // and grid as they were at that moment.
        var measurementPoints: List<LatLng> = emptyList()
        var drawings: List<Drawing> = emptyList()
        var gridCenter: LatLng? = null
        var useMilStdSelfSymbol: Boolean = true
        var selfMarkerTriangle: Boolean = false
        var followMeActive: Boolean = false

        /**
         * Drop the callbacks into a composition that is gone. The view stays
         * alive with nobody showing it (another tab, or no Activity at all),
         * and these would keep that whole composition reachable. The state
         * above stays: it is what the style listener redraws from.
         */
        fun clearCallbacks() {
            onMapReady = null
            onStyleReady = null
            onCameraIdle = null
            onLongPress = null
            onMapSingleTap = null
            onContactTap = null
            onSelfMarkerTap = null
        }
    }

    val bindings = Bindings()

    /**
     * What decides whether the first fix of a first run moves the camera. It has to outlive
     * every composition of the map (a tab switch composes it again, an Activity rebuild
     * composes it in a new Activity), so it is kept here with the view and never in a
     * composition's own state. See [FirstFixState].
     */
    val firstFix = FirstFixState()

    private val slot = RetainedSlot<MapView>()

    /** #214: has the view come back at a different size than it left with? See [ReturnSizeWatch]. */
    val returnSize = ReturnSizeWatch()

    /**
     * Return the retained [MapView], creating it via [factory] on first use, and
     * record [holder] (any object unique to the calling composition) as the one
     * now showing it. [appContext] should be the application context so the
     * view itself is not tied to an Activity.
     */
    fun acquire(appContext: Context, holder: Any, factory: (Context) -> MapView): MapView =
        slot.acquire(holder) { factory(appContext) }.also {
            detach(it)
            returnSize.returned()
        }

    /** True while [holder] is the composition that took the view last. */
    fun isHeldBy(holder: Any): Boolean = slot.isHeldBy(holder)

    /**
     * [holder]'s composition is going away: take the view out of it so the next
     * one can attach it. Does nothing, and returns false, when a newer
     * composition has taken the view already; it is on screen there.
     */
    fun release(holder: Any, view: MapView): Boolean {
        if (!slot.release(holder)) return false
        bindings.clearCallbacks()
        detach(view)
        return true
    }

    /** Remove the view from its current parent so it can be re-attached. */
    fun detach(view: MapView) {
        (view.parent as? ViewGroup)?.removeView(view)
    }
}

/**
 * The bookkeeping behind [RetainedMapView], free of Android types so it can be
 * tested on the JVM: one value built on first use and never dropped, and a
 * record of who took it last.
 */
internal class RetainedSlot<V : Any> {
    private var value: V? = null
    private var holder: Any? = null

    /** The value, built by [create] the first time. [holder] becomes the one it belongs to. */
    fun acquire(holder: Any, create: () -> V): V {
        val existing = value ?: create().also { value = it }
        this.holder = holder
        return existing
    }

    fun isHeldBy(holder: Any): Boolean = this.holder === holder

    /** Give the value up. False, and nothing changes, when [holder] no longer has it. */
    fun release(holder: Any): Boolean {
        if (this.holder !== holder) return false
        this.holder = null
        return true
    }
}

/**
 * #214: MapLibre's surface view stops its render thread when the view leaves the
 * window and starts a new one when it comes back. When the screen has turned in
 * between (choosing Landscape or Portrait in Settings turns it while the map is on
 * another tab) the new thread can keep drawing into a buffer the size of the one the
 * surface had when the view left, while the surface has the new size. The log says
 * "BLASTBufferQueue: rejecting buffer", and the map fills only part of the screen with
 * black beside or below it. A resize delivered to the live surface repairs it, which is
 * why turning the phone again with the map open always did; so when the view comes back
 * at a different size than it left with, the map composable gives it one.
 *
 * This only decides whether that is needed, without Android types so it can be tested.
 */
internal class ReturnSizeWatch {
    // The last size the view was laid out at while it was shown, in px.
    private var last: Pair<Int, Int>? = null

    // A composition took the view and has not reported a size yet.
    private var waitingForFirstSize = false

    /** A composition took the view; its first size is the one that matters. */
    fun returned() {
        waitingForFirstSize = true
    }

    /**
     * The view was laid out at [width] x [height]. True when this is the first size
     * since the view came back and it differs from the size it had when it left:
     * the only case that needs the nudge. A later size (the phone turning with the
     * map open) is never one, and neither is the first time the view is shown.
     */
    fun sized(width: Int, height: Int): Boolean {
        val now = width to height
        val before = last
        last = now
        if (!waitingForFirstSize) return false
        waitingForFirstSize = false
        return before != null && before != now
    }
}

/**
 * What the map composable may do to the retained view, as plain functions so
 * the rules are testable without a MapView.
 */
internal object RetainedMapRules {

    /** The MapView lifecycle call a host lifecycle event turns into. */
    enum class ViewCall { START, RESUME, PAUSE, STOP, NOTHING }

    /**
     * [holdsView] is whether the composition seeing [event] is still the one
     * showing the view. A composition that has been replaced forwards nothing:
     * its host stops after the new one has started, and that late stop would
     * freeze the map on screen.
     *
     * There is no call for ON_DESTROY, for anyone. The host being destroyed is
     * not the view being destroyed: the view is kept and handed to the next
     * host, and a destroyed MapView cannot be shown again.
     */
    fun viewCallFor(event: Lifecycle.Event, holdsView: Boolean): ViewCall = when {
        !holdsView -> ViewCall.NOTHING
        event == Lifecycle.Event.ON_START -> ViewCall.START
        event == Lifecycle.Event.ON_RESUME -> ViewCall.RESUME
        event == Lifecycle.Event.ON_PAUSE -> ViewCall.PAUSE
        event == Lifecycle.Event.ON_STOP -> ViewCall.STOP
        else -> ViewCall.NOTHING
    }

    /** Why the map should go to the operator's position now, if it should. */
    enum class Recenter {
        /** It should not. */
        NO,

        /** "Center on me" was pressed. */
        PRESSED,

        /** The map is being opened by the composition that builds the view. */
        OPENING,
    }

    /**
     * [trigger] changes on every press of "Center on me". [triggerAtComposition]
     * is its value when the map was composed, and that value is not a press.
     *
     * It still counts once, for the composition that builds the view
     * ([builtViewHere]): with location on and a fix known, the first map of a
     * process has always opened on the operator's position, and still does.
     * On a view that already exists it does not count. Acting on it there
     * moved the camera back to the operator every time the map tab was opened,
     * because the retained view is already running when it is composed.
     */
    fun recenterReason(trigger: Any?, triggerAtComposition: Any?, builtViewHere: Boolean): Recenter = when {
        trigger == null -> Recenter.NO
        trigger != triggerAtComposition -> Recenter.PRESSED
        builtViewHere -> Recenter.OPENING
        else -> Recenter.NO
    }

    /** From further out than this, going to the operator also zooms in on them. */
    const val OPERATOR_FAR_OUT_ZOOM = 12.0

    /** Street level: the zoom a camera that was far out goes to. */
    const val OPERATOR_ZOOM = 15.0

    /**
     * The zoom the camera goes to when it is sent to the operator ("Center on me", opening the
     * map on them, the first fix of a first run), or null to keep the zoom it has. From far out
     * (below [OPERATOR_FAR_OUT_ZOOM]) it is [OPERATOR_ZOOM]. At [OPERATOR_FAR_OUT_ZOOM] or closer
     * the operator chose that zoom and keeps it: this never answers with a smaller one.
     *
     * The caller puts it in the same camera move as the pan. Asked for after the pan has started,
     * MapLibre's LocationComponent ignores a zoom while the camera is still turning to the
     * location, which is what left the map at the world zoom.
     */
    fun zoomForOperator(currentZoom: Double): Double? =
        if (currentZoom < OPERATOR_FAR_OUT_ZOOM) OPERATOR_ZOOM else null

    /**
     * Whether a fix is the operator's position now, as far as the camera is concerned: not one
     * kept from the last session ([restored]) and not older than
     * [soy.engindearing.omnitak.mobile.data.SelfFixPersistence.STALE_AFTER_MS]. A fix kept from
     * the last session is where the operator was; it is drawn, but it is not the first fix of
     * this one.
     */
    fun isLiveFix(restored: Boolean, fixTimeMs: Long, nowMs: Long): Boolean =
        !restored && !soy.engindearing.omnitak.mobile.data.SelfFixPersistence.isStale(fixTimeMs, nowMs)

    /**
     * Whether the first fix that arrives after the map was opened should move the camera to the
     * operator. On a first run the map is composed before the permission is answered and before
     * the first fix, so it opens on the world and would stay there. The move happens once and
     * only for a map nobody has taken over:
     *
     *  - [wentToOperatorAtOpen]: the map already opened on the operator.
     *  - [userMovedCamera]: the operator has moved it by hand in this session (a drag, a pinch,
     *    the zoom buttons, the compass, a pan to a contact or coordinate).
     *  - [toolActive]: a drawing, measure, range ring or lasso tool is active, or Follow is on.
     *    A tool needs the map to stay where the operator put it, and Follow already owns the
     *    camera. ([FirstFixState.onFreshFix] makes this final: a map in use when the first fix
     *    arrives is left alone for the rest of the session.)
     *  - [alreadyDone]: it has happened once, or "Center on me" has already been pressed.
     *    Never more than once per process.
     */
    fun firstFixMove(
        wentToOperatorAtOpen: Boolean,
        userMovedCamera: Boolean,
        toolActive: Boolean,
        alreadyDone: Boolean,
    ): Boolean = !wentToOperatorAtOpen && !userMovedCamera && !toolActive && !alreadyDone
}

/**
 * The state behind [RetainedMapRules.firstFixMove]: what has happened to the camera in this
 * process. Plain Kotlin so the rules can be tested on the JVM; kept on [RetainedMapView]
 * because it must survive every composition of the map.
 *
 * Main thread only, like the rest of the retained view.
 */
internal class FirstFixState {
    private var wentToOperatorAtOpen = false
    private var userMovedCamera = false
    private var done = false

    /** The map opened on the operator's position. */
    fun openedOnOperator() {
        wentToOperatorAtOpen = true
    }

    /** The operator moved the camera by hand, or asked for a move of their own. */
    fun operatorMovedCamera() {
        userMovedCamera = true
    }

    /** The camera was sent to the operator: by the first fix, or by "Center on me". */
    fun movedToOperator() {
        done = true
    }

    /** True when a fresh fix arriving now should move the camera. [toolActive] as in [RetainedMapRules.firstFixMove]. */
    fun shouldMoveOnFix(toolActive: Boolean): Boolean =
        RetainedMapRules.firstFixMove(wentToOperatorAtOpen, userMovedCamera, toolActive, done)

    /** What a fresh fix arriving now asks of the camera. */
    enum class Action {
        /** Send the camera to the operator, then call [movedToOperator]. */
        MOVE,

        /** The map is in use (a tool is active, or Follow is on): leave it alone, now and later. */
        LEAVE,

        /** Nothing to do: the map already opened on the operator, was moved by hand, or this has happened. */
        NOTHING,
    }

    /**
     * The decision for a fresh fix, with its consequence. If a tool is active or Follow is on at
     * the moment the first fix arrives, the camera is not moved, and the wait is over for good
     * ([operatorMovedCamera]): the map is in use, and moving it afterwards (when the tool is
     * closed, or the map tab is opened again) would be the map moving by itself, which is
     * what this exists to stop. The operator presses "Center on me" when they want it.
     */
    fun onFreshFix(toolActive: Boolean): Action = when {
        shouldMoveOnFix(toolActive = false).not() -> Action.NOTHING
        shouldMoveOnFix(toolActive) -> Action.MOVE
        else -> {
            operatorMovedCamera()
            Action.LEAVE
        }
    }
}
