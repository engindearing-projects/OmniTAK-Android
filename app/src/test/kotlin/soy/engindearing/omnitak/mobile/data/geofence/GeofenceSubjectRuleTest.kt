package soy.engindearing.omnitak.mobile.data.geofence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #155 - who counts as a teammate for a geofence: the iOS rule, and what this app's own ids add to it. */
class GeofenceSubjectRuleTest {

    private fun rule(uid: String, type: String, how: String? = "m-g", me: String? = "me-uid") =
        GeofenceSubjectRule.isTeammate(uid, type, how, me)

    @Test fun a_teammates_phone_counts() {
        assertTrue(rule("ANDROID-1a2b", "a-f-G-U-C"))
    }

    @Test fun a_node_heard_over_a_mesh_radio_counts() {
        assertTrue(rule("MESHTASTIC-7F3A", "a-n-G-U-C"))
        assertTrue(rule("MESHCORE-ab12", "a-f-G-U-C", how = null))
    }

    @Test fun a_report_with_no_how_at_all_is_left_in() {
        assertTrue(rule("abc", "a-u-G", how = null))
    }

    @Test fun the_operators_own_echo_does_not_count() {
        assertFalse(rule("me-uid", "a-f-G-U-C"))
        assertTrue("with no uid of ours known yet nothing is taken for us", GeofenceSubjectRule.isTeammate("me-uid", "a-f-G-U-C", "m-g", null))
        assertTrue(GeofenceSubjectRule.isTeammate("me-uid", "a-f-G-U-C", "m-g", ""))
    }

    @Test fun a_dropped_marker_does_not_count() {
        assertFalse(rule("marker-1234", "a-f-G"))
        assertFalse(rule("Marker-1234", "a-f-G"))
    }

    @Test fun a_marker_this_app_dropped_does_not_count() {
        // The app names the points the operator drops "local-<time>" (and "local-fema-<time>").
        assertFalse(rule("local-1763337600000", "a-f-G-U-C", how = null))
        assertFalse(rule("local-fema-1763337600000", "a-f-G-U-C", how = null))
    }

    @Test fun a_drone_detection_does_not_count() {
        assertFalse(rule("RID-9f", "a-u-A"))
        assertFalse(rule("RID-OP-9f", "a-f-G"))
        assertFalse(rule("rid-9f", "a-u-A"))
    }

    @Test fun the_operators_own_radio_does_not_count() {
        assertFalse(rule("mesh-self-1a2b3c4d", "a-f-G-U-C"))
    }

    @Test fun hostile_suspect_and_joker_do_not_count() {
        assertFalse(rule("x", "a-h-G"))
        assertFalse(rule("x", "a-s-G"))
        assertFalse(rule("x", "a-j-G"))
        assertFalse(rule("x", "A-H-G"))
    }

    @Test fun unknown_neutral_assumed_and_pending_do_count() {
        assertTrue(rule("x", "a-u-G"))
        assertTrue(rule("x", "a-n-G"))
        assertTrue(rule("x", "a-a-G"))
        assertTrue(rule("x", "a-p-G"))
    }

    @Test fun a_point_that_is_not_a_unit_does_not_count() {
        assertFalse("a waypoint", rule("x", "b-m-p-w"))
        assertFalse("a chat message", rule("x", "b-t-f"))
        assertFalse("nothing at all", rule("x", ""))
        assertFalse("too short to say who", rule("x", "a"))
    }

    @Test fun a_position_placed_by_hand_does_not_count() {
        assertFalse(rule("x", "a-f-G", how = "h-g-i-g-o"))
        assertFalse(rule("x", "a-f-G", how = "h-e"))
    }

    @Test fun a_machine_reported_position_does_count_whatever_the_machine() {
        assertTrue(rule("x", "a-f-G", how = "m-g"))
        assertTrue(rule("x", "a-f-G", how = "m-r"))
        assertTrue(rule("x", "a-f-G", how = "M-G"))
    }
}
