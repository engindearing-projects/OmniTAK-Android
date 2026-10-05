package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #210 - hiding the operator's own marker on the 2D map. The MapLibre
 * LocationComponent is both the puck and the camera-tracking engine, so hiding
 * the marker means keeping the component off and moving the camera from the
 * raw fix instead. These pin the two rules that decide which of the two paths
 * is live, over every combination of "location available" x "marker visible".
 */
class SelfMarkerVisibilityTest {

    @Test fun puck_runs_only_when_location_is_available_and_marker_is_visible() {
        assertTrue(SelfMarkerVisibility.puckActive(locationEnabled = true, selfMarkerVisible = true))
        assertFalse("hidden marker: no puck", SelfMarkerVisibility.puckActive(true, false))
        assertFalse("no location: no puck", SelfMarkerVisibility.puckActive(false, true))
        assertFalse(SelfMarkerVisibility.puckActive(false, false))
    }

    @Test fun camera_follows_the_raw_fix_only_when_location_is_available_and_marker_is_hidden() {
        assertTrue(SelfMarkerVisibility.cameraFollowsFixDirectly(locationEnabled = true, selfMarkerVisible = false))
        assertFalse("visible marker: the puck tracks", SelfMarkerVisibility.cameraFollowsFixDirectly(true, true))
        assertFalse("no location: nothing to follow", SelfMarkerVisibility.cameraFollowsFixDirectly(false, false))
        assertFalse(SelfMarkerVisibility.cameraFollowsFixDirectly(false, true))
    }

    @Test fun with_location_available_exactly_one_path_drives_the_camera() {
        // Follow-me and Center on me must always have a driver while location
        // is available, and never two (the puck tracking AND a manual pan).
        for (visible in listOf(true, false)) {
            assertNotEquals(
                "visible=$visible: puck and direct follow must be mutually exclusive",
                SelfMarkerVisibility.puckActive(true, visible),
                SelfMarkerVisibility.cameraFollowsFixDirectly(true, visible),
            )
        }
    }

    @Test fun without_location_neither_path_runs() {
        for (visible in listOf(true, false)) {
            assertEquals(false, SelfMarkerVisibility.puckActive(false, visible))
            assertEquals(false, SelfMarkerVisibility.cameraFollowsFixDirectly(false, visible))
        }
    }
}
