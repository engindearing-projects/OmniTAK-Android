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
        assertFalse(RetainedMapRules.recenterRequested(trigger = 0, triggerAtComposition = 0, builtViewHere = false))
        assertFalse(RetainedMapRules.recenterRequested(trigger = 7, triggerAtComposition = 7, builtViewHere = false))
        assertFalse(
            "no trigger wired up",
            RetainedMapRules.recenterRequested(trigger = null, triggerAtComposition = null, builtViewHere = false),
        )
    }

    @Test fun the_composition_that_builds_the_view_opens_on_the_operator() {
        // A cold start has always gone to the operator's position.
        assertTrue(RetainedMapRules.recenterRequested(trigger = 0, triggerAtComposition = 0, builtViewHere = true))
        assertFalse(
            "but not when no trigger is wired up",
            RetainedMapRules.recenterRequested(trigger = null, triggerAtComposition = null, builtViewHere = true),
        )
    }

    @Test fun a_press_after_the_map_was_composed_is_a_request() {
        for (built in listOf(true, false)) {
            assertTrue(RetainedMapRules.recenterRequested(trigger = 1, triggerAtComposition = 0, builtViewHere = built))
            assertTrue(RetainedMapRules.recenterRequested(trigger = 8, triggerAtComposition = 7, builtViewHere = built))
            assertTrue(
                "a trigger that appears later",
                RetainedMapRules.recenterRequested(trigger = 1, triggerAtComposition = null, builtViewHere = built),
            )
        }
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
}
