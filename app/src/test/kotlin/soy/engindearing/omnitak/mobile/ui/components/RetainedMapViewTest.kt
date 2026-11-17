package soy.engindearing.omnitak.mobile.ui.components

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.geometry.LatLng
import soy.engindearing.omnitak.mobile.data.SelfFix
import soy.engindearing.omnitak.mobile.ui.components.RetainedMapRules.Recenter
import soy.engindearing.omnitak.mobile.ui.components.RetainedMapRules.ViewCall

/**
 * The one MapView is kept for the life of the process and shown by whichever
 * composition of the map is current. These pin the rules that keep that safe:
 * the view is never destroyed with its host, only the composition that took it
 * last may drive it, and composing the map is not a request to move it.
 *
 * What went wrong before them, on every release from 0.40.0 to 0.45.0: the
 * view was destroyed with the Activity and handed, destroyed, to the next one.
 * The app died on reopening after the Activity had been rebuilt in a live
 * process (dark mode switching, a font size change, a swipe out of Recents
 * while connected to a server).
 */
class RetainedMapViewTest {

    // MARK: the slot

    @Test fun the_value_is_built_once_and_every_later_holder_gets_the_same_one() {
        val slot = RetainedSlot<Any>()
        var built = 0
        val first = Any()
        val second = Any()

        val a = slot.acquire(first) { built++; Any() }
        val b = slot.acquire(second) { built++; Any() }

        assertSame("the second holder is handed the view the first one built", a, b)
        assertEquals(1, built)
    }

    @Test fun the_value_survives_being_released() {
        // A release is the map leaving the screen (another tab, the Activity
        // closing). It must not drop the view: that is the whole point of it.
        val slot = RetainedSlot<Any>()
        val first = Any()
        val a = slot.acquire(first) { Any() }
        assertTrue(slot.release(first))

        val b = slot.acquire(Any()) { throw AssertionError("built a second view") }
        assertSame(a, b)
    }

    @Test fun only_the_holder_that_took_it_last_holds_it() {
        val slot = RetainedSlot<Any>()
        val oldActivity = Any()
        val newActivity = Any()

        slot.acquire(oldActivity) { Any() }
        assertTrue(slot.isHeldBy(oldActivity))

        // The new Activity is created before the old one has been stopped.
        slot.acquire(newActivity) { Any() }
        assertFalse("the old composition no longer holds the view", slot.isHeldBy(oldActivity))
        assertTrue(slot.isHeldBy(newActivity))
    }

    @Test fun a_replaced_holder_cannot_release_the_view_from_under_the_new_one() {
        val slot = RetainedSlot<Any>()
        val oldActivity = Any()
        val newActivity = Any()
        slot.acquire(oldActivity) { Any() }
        slot.acquire(newActivity) { Any() }

        // The old composition is disposed a moment later.
        assertFalse("its release is refused", slot.release(oldActivity))
        assertTrue("and the new one still holds the view", slot.isHeldBy(newActivity))
    }

    @Test fun nobody_holds_the_view_after_its_holder_lets_go() {
        val slot = RetainedSlot<Any>()
        val holder = Any()
        slot.acquire(holder) { Any() }

        assertTrue(slot.release(holder))
        assertFalse(slot.isHeldBy(holder))
        assertFalse("a second release changes nothing", slot.release(holder))
    }

    @Test fun holders_are_told_apart_by_identity_not_by_equality() {
        // Two compositions must never be mistaken for one another, whatever they use as a token.
        val slot = RetainedSlot<Any>()
        val first = String(charArrayOf('m', 'a', 'p'))
        val second = String(charArrayOf('m', 'a', 'p'))
        assertEquals(first, second)

        slot.acquire(first) { Any() }
        assertFalse(slot.isHeldBy(second))
        assertFalse(slot.release(second))
        assertTrue(slot.isHeldBy(first))
    }

    @Test fun a_failed_build_is_retried_by_the_next_holder() {
        val slot = RetainedSlot<Any>()
        runCatching { slot.acquire(Any()) { throw IllegalStateException("no GL") } }

        val view = Any()
        assertSame(view, slot.acquire(Any()) { view })
    }

    // MARK: lifecycle

    @Test fun a_host_being_destroyed_forwards_nothing_to_the_view() {
        // The crash: ON_DESTROY used to call MapView.onDestroy(), and the next
        // Activity was handed the destroyed view. There is no call for it at
        // all now; this pins that ON_DESTROY does not turn into one of the
        // others either.
        assertEquals(ViewCall.NOTHING, RetainedMapRules.viewCallFor(Lifecycle.Event.ON_DESTROY, holdsView = true))
        assertEquals(ViewCall.NOTHING, RetainedMapRules.viewCallFor(Lifecycle.Event.ON_DESTROY, holdsView = false))
    }

    @Test fun the_holder_forwards_start_resume_pause_and_stop() {
        assertEquals(ViewCall.START, RetainedMapRules.viewCallFor(Lifecycle.Event.ON_START, holdsView = true))
        assertEquals(ViewCall.RESUME, RetainedMapRules.viewCallFor(Lifecycle.Event.ON_RESUME, holdsView = true))
        assertEquals(ViewCall.PAUSE, RetainedMapRules.viewCallFor(Lifecycle.Event.ON_PAUSE, holdsView = true))
        assertEquals(ViewCall.STOP, RetainedMapRules.viewCallFor(Lifecycle.Event.ON_STOP, holdsView = true))
        // The view was created once, when it was built: a host being created is not that.
        assertEquals(ViewCall.NOTHING, RetainedMapRules.viewCallFor(Lifecycle.Event.ON_CREATE, holdsView = true))
        assertEquals(ViewCall.NOTHING, RetainedMapRules.viewCallFor(Lifecycle.Event.ON_ANY, holdsView = true))
    }

    @Test fun a_replaced_composition_forwards_nothing() {
        // Its Activity stops after the new one has started. Forwarding that
        // stop would freeze the map the operator is looking at.
        for (event in Lifecycle.Event.values()) {
            assertEquals(
                "$event from a composition that no longer holds the view",
                ViewCall.NOTHING,
                RetainedMapRules.viewCallFor(event, holdsView = false),
            )
        }
    }

    // MARK: Center on me

    @Test fun composing_the_map_onto_the_existing_view_is_not_a_request_to_recenter() {
        // MapScreen's counter starts at 0, and a new composition of the map
        // sees whatever it is at that moment. Every return to the map tab is
        // such a composition.
        assertEquals(Recenter.NO, RetainedMapRules.recenterReason(trigger = 0, triggerAtComposition = 0, builtViewHere = false))
        assertEquals(Recenter.NO, RetainedMapRules.recenterReason(trigger = 7, triggerAtComposition = 7, builtViewHere = false))
        assertEquals(
            "no trigger wired up",
            Recenter.NO,
            RetainedMapRules.recenterReason(trigger = null, triggerAtComposition = null, builtViewHere = false),
        )
    }

    @Test fun the_composition_that_builds_the_view_opens_on_the_operator() {
        // With location on and a fix known, the first map of a process has
        // always opened on the operator's position.
        assertEquals(Recenter.OPENING, RetainedMapRules.recenterReason(trigger = 0, triggerAtComposition = 0, builtViewHere = true))
        assertEquals(
            "but not when no trigger is wired up",
            Recenter.NO,
            RetainedMapRules.recenterReason(trigger = null, triggerAtComposition = null, builtViewHere = true),
        )
    }

    @Test fun a_press_after_the_map_was_composed_is_a_press_whoever_built_the_view() {
        // A press zooms in as well, which opening the map does not: the two
        // must not be mistaken for one another.
        for (built in listOf(true, false)) {
            assertEquals(Recenter.PRESSED, RetainedMapRules.recenterReason(trigger = 1, triggerAtComposition = 0, builtViewHere = built))
            assertEquals(Recenter.PRESSED, RetainedMapRules.recenterReason(trigger = 8, triggerAtComposition = 7, builtViewHere = built))
            assertEquals(
                "a trigger that appears later",
                Recenter.PRESSED,
                RetainedMapRules.recenterReason(trigger = 1, triggerAtComposition = null, builtViewHere = built),
            )
        }
    }

    // MARK: how far in "Center on me" goes
    //
    // "Center on me" and opening the map pan to the operator and, from far out, zoom in on them
    // in the same move. Through 0.45.0 the zoom was asked for after the pan had started, and
    // MapLibre ignores a zoom while the camera is still turning to the location: the map
    // arrived at the operator at the world zoom.

    @Test fun from_far_out_the_camera_goes_in_to_street_level() {
        assertEquals("the world view of a first run", 15.0, RetainedMapRules.zoomForOperator(2.0)!!, 0.0)
        assertEquals(15.0, RetainedMapRules.zoomForOperator(0.0)!!, 0.0)
        assertEquals("just short of the limit", 15.0, RetainedMapRules.zoomForOperator(11.99)!!, 0.0)
    }

    @Test fun at_zoom_12_or_closer_the_operators_own_zoom_is_kept() {
        assertNull("exactly 12", RetainedMapRules.zoomForOperator(12.0))
        assertNull(RetainedMapRules.zoomForOperator(15.0))
        assertNull("zoomed in on a building", RetainedMapRules.zoomForOperator(17.0))
        assertNull(RetainedMapRules.zoomForOperator(22.0))
    }

    @Test fun the_camera_never_goes_out_to_find_the_operator() {
        // The hidden-marker branch used to send every press to zoom 15, so a press at zoom 17
        // zoomed out. "Keep the zoom" must never turn into a smaller one.
        for (zoom in listOf(0.0, 2.0, 11.99, 12.0, 14.9, 15.0, 17.0, 20.0)) {
            val target = RetainedMapRules.zoomForOperator(zoom)
            assertTrue("from zoom $zoom", target == null || target > zoom)
        }
    }

    @Test fun an_unknown_zoom_is_left_alone() {
        assertNull(RetainedMapRules.zoomForOperator(Double.NaN))
    }

    // MARK: the first fix of a first run
    //
    // On a first run the map is composed before the location permission is answered and before
    // the first fix, so it opens on the world and stays there while the position card already
    // shows where the operator is. The first fix that arrives moves the camera there once.

    @Test fun the_first_fix_moves_a_map_nobody_has_touched() {
        assertTrue(
            RetainedMapRules.firstFixMove(
                wentToOperatorAtOpen = false, userMovedCamera = false, toolActive = false, alreadyDone = false,
            ),
        )
    }

    @Test fun any_one_reason_is_enough_to_leave_the_camera_alone() {
        // The map already opened on the operator.
        assertFalse(RetainedMapRules.firstFixMove(true, false, false, false))
        // The operator has already moved the map by hand.
        assertFalse(RetainedMapRules.firstFixMove(false, true, false, false))
        // A drawing, measure, range ring or lasso tool is active, or Follow is on.
        assertFalse(RetainedMapRules.firstFixMove(false, false, true, false))
        // It has already happened once.
        assertFalse(RetainedMapRules.firstFixMove(false, false, false, true))
    }

    @Test fun every_combination_of_the_four_inputs() {
        for (bits in 0 until 16) {
            val went = bits and 1 != 0
            val moved = bits and 2 != 0
            val tool = bits and 4 != 0
            val done = bits and 8 != 0
            assertEquals(
                "went=$went moved=$moved tool=$tool done=$done",
                bits == 0,
                RetainedMapRules.firstFixMove(went, moved, tool, done),
            )
        }
    }

    @Test fun a_fresh_process_is_waiting_for_its_first_fix() {
        val state = FirstFixState()
        assertTrue(state.shouldMoveOnFix(toolActive = false))
    }

    @Test fun it_happens_once_per_process() {
        val state = FirstFixState()
        assertTrue(state.shouldMoveOnFix(toolActive = false))
        state.movedToOperator()
        assertFalse("the next fix leaves the camera where it is", state.shouldMoveOnFix(toolActive = false))
        assertFalse(state.shouldMoveOnFix(toolActive = false))
    }

    @Test fun a_map_that_opened_on_the_operator_does_not_move_again() {
        val state = FirstFixState()
        state.openedOnOperator()
        assertFalse(state.shouldMoveOnFix(toolActive = false))
    }

    @Test fun the_operator_moving_the_map_by_hand_ends_it_for_the_session() {
        val state = FirstFixState()
        state.operatorMovedCamera()
        assertFalse(state.shouldMoveOnFix(toolActive = false))
        // And it stays ended: a later fix, a tab switch or an Activity rebuild do not bring it back.
        assertFalse(state.shouldMoveOnFix(toolActive = false))
    }

    @Test fun a_fresh_fix_on_an_untouched_map_means_move() {
        val state = FirstFixState()
        assertEquals(FirstFixState.Action.MOVE, state.onFreshFix(toolActive = false))
    }

    @Test fun a_fix_after_the_work_is_settled_means_nothing() {
        val moved = FirstFixState().apply { movedToOperator() }
        assertEquals(FirstFixState.Action.NOTHING, moved.onFreshFix(toolActive = false))
        val opened = FirstFixState().apply { openedOnOperator() }
        assertEquals(FirstFixState.Action.NOTHING, opened.onFreshFix(toolActive = false))
        val taken = FirstFixState().apply { operatorMovedCamera() }
        assertEquals(FirstFixState.Action.NOTHING, taken.onFreshFix(toolActive = false))
        // An active tool changes nothing when there was nothing to do.
        assertEquals(FirstFixState.Action.NOTHING, moved.onFreshFix(toolActive = true))
    }

    @Test fun a_map_already_in_use_when_the_first_fix_arrives_is_left_alone_for_the_session() {
        // Measuring, drawing, a range ring, the lasso, or Follow, when the fix arrives: the
        // camera does not move under the operator, and it does not move later either, when the
        // tool is closed or the map tab is opened again. Waiting for the tool to end and then
        // moving would be the old fault in a new place: the map moving by itself.
        val state = FirstFixState()
        assertEquals(FirstFixState.Action.LEAVE, state.onFreshFix(toolActive = true))
        assertEquals("the tool is closed", FirstFixState.Action.NOTHING, state.onFreshFix(toolActive = false))
        assertFalse(state.shouldMoveOnFix(toolActive = false))
    }

    @Test fun pressing_center_on_me_uses_the_move_up() {
        val state = FirstFixState()
        state.movedToOperator()
        assertFalse(state.shouldMoveOnFix(toolActive = false))
    }

    @Test fun only_a_live_fix_counts_as_the_operator_having_arrived() {
        val now = 1_000_000L
        assertTrue("taken a second ago", RetainedMapRules.isLiveFix(restored = false, fixTimeMs = now - 1_000, nowMs = now))
        assertTrue("right at the 30 s limit", RetainedMapRules.isLiveFix(restored = false, fixTimeMs = now - 30_000, nowMs = now))
        assertFalse("a minute old: where the operator was", RetainedMapRules.isLiveFix(restored = false, fixTimeMs = now - 60_000, nowMs = now))
        assertFalse("kept from the last session, however recent its stamp", RetainedMapRules.isLiveFix(restored = true, fixTimeMs = now, nowMs = now))
        assertFalse("no time on it", RetainedMapRules.isLiveFix(restored = false, fixTimeMs = 0L, nowMs = now))
    }

    @Test fun the_state_is_one_object_for_the_whole_process() {
        // It lives on the retained view, not in a composition: a tab switch or an Activity
        // rebuild composes the map again and must find what the last one left.
        assertSame(RetainedMapView.firstFix, RetainedMapView.firstFix)
    }

    // MARK: bindings

    @Test fun clearing_the_callbacks_keeps_what_the_map_has_to_redraw() {
        val bindings = RetainedMapView.Bindings()
        val fix = SelfFix(lat = 1.0, lon = 2.0, altitudeM = 0.0, speedKmh = 0.0, accuracyM = 5f, timeMs = 3L)
        val points = listOf(LatLng(1.0, 2.0), LatLng(3.0, 4.0))
        bindings.onMapReady = { }
        bindings.onStyleReady = { _, _ -> }
        bindings.onCameraIdle = { _, _, _ -> }
        bindings.onLongPress = { _, _ -> }
        bindings.onMapSingleTap = { true }
        bindings.onContactTap = { }
        bindings.onSelfMarkerTap = { }
        bindings.selfFix = fix
        bindings.puckActive = true
        bindings.measurementPoints = points
        bindings.gridCenter = LatLng(5.0, 6.0)
        bindings.followMeActive = true
        bindings.selfMarkerTriangle = true
        bindings.useMilStdSelfSymbol = false
        bindings.northUpLocked = true

        bindings.clearCallbacks()

        // Nothing may keep the composition that is gone reachable...
        assertNull(bindings.onMapReady)
        assertNull(bindings.onStyleReady)
        assertNull(bindings.onCameraIdle)
        assertNull(bindings.onLongPress)
        assertNull(bindings.onMapSingleTap)
        assertNull(bindings.onContactTap)
        assertNull(bindings.onSelfMarkerTap)
        // ...and a style reload while nobody is looking still redraws the map as it was.
        assertSame(fix, bindings.selfFix)
        assertTrue(bindings.puckActive)
        assertSame(points, bindings.measurementPoints)
        assertEquals(LatLng(5.0, 6.0), bindings.gridCenter)
        assertTrue(bindings.followMeActive)
        assertTrue(bindings.selfMarkerTriangle)
        assertFalse(bindings.useMilStdSelfSymbol)
        assertTrue(bindings.northUpLocked)
    }

    // MARK: the size the map comes back at (#214)
    //
    // MapLibre's restarted render thread can keep drawing at the size the surface had when
    // the view left. That only happens when the size changed while the map was away, so that
    // is the only time the nudge is wanted.

    @Test fun a_map_that_comes_back_at_a_different_size_needs_the_nudge() {
        val watch = ReturnSizeWatch()
        watch.returned()
        assertFalse("the first time it is shown", watch.sized(1080, 2201))
        // Left for Settings, the screen turned, the map comes back landscape.
        watch.returned()
        assertTrue(watch.sized(2400, 943))
    }

    @Test fun a_map_that_comes_back_at_the_same_size_needs_none() {
        val watch = ReturnSizeWatch()
        watch.returned()
        watch.sized(1080, 2201)
        watch.returned()
        assertFalse(watch.sized(1080, 2201))
    }

    @Test fun the_first_time_the_map_is_shown_needs_none() {
        val watch = ReturnSizeWatch()
        watch.returned()
        assertFalse(watch.sized(2400, 943))
    }

    @Test fun only_the_first_size_after_it_comes_back_counts() {
        val watch = ReturnSizeWatch()
        watch.returned()
        watch.sized(1080, 2201)
        watch.returned()
        assertTrue(watch.sized(2400, 943))
        // Later sizes are the map being resized while it is on screen (insets settling, the
        // phone turning with the map open). That path works and is not nudged.
        assertFalse(watch.sized(2400, 900))
        assertFalse(watch.sized(1080, 2201))
    }

    @Test fun turning_the_phone_with_the_map_open_is_not_a_return() {
        val watch = ReturnSizeWatch()
        watch.returned()
        watch.sized(1080, 2201)
        assertFalse(watch.sized(2400, 943))
        assertFalse(watch.sized(1080, 2201))
    }

    @Test fun the_size_it_left_with_is_the_last_one_it_was_shown_at() {
        val watch = ReturnSizeWatch()
        watch.returned()
        watch.sized(1080, 2201)
        // Turned to landscape with the map open, then left for another tab.
        assertFalse(watch.sized(2400, 943))
        watch.returned()
        assertFalse("back at the size it left with", watch.sized(2400, 943))
        watch.returned()
        assertTrue("back at a different size", watch.sized(1080, 2201))
    }

    @Test fun every_return_is_judged_on_its_own() {
        val watch = ReturnSizeWatch()
        watch.returned(); watch.sized(1080, 2201)
        watch.returned(); assertTrue(watch.sized(2400, 943))
        watch.returned(); assertTrue(watch.sized(1080, 2201))
        watch.returned(); assertFalse(watch.sized(1080, 2201))
    }

    @Test fun a_size_reported_when_nobody_has_taken_the_view_never_counts() {
        val watch = ReturnSizeWatch()
        watch.returned(); watch.sized(1080, 2201)
        assertFalse(watch.sized(2400, 943))
        assertFalse(watch.sized(1080, 2201))
    }
}
