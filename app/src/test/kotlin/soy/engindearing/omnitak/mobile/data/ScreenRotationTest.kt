package soy.engindearing.omnitak.mobile.data

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #214 - the rules behind the screen rotation setting: the three modes and their stored
 * values, the order the shortcut cycles through them, which Activity orientation each mode
 * asks the system for, how a stored value that is not one of them reads, and the words the
 * operator sees.
 */
class ScreenRotationTest {

    @Test fun the_modes_are_auto_portrait_landscape_in_that_order() {
        assertEquals(
            listOf(ScreenRotation.AUTO, ScreenRotation.PORTRAIT, ScreenRotation.LANDSCAPE),
            ScreenRotation.entries.toList(),
        )
    }

    @Test fun the_stored_values_are_auto_portrait_landscape() {
        assertEquals("auto", ScreenRotation.AUTO.stored)
        assertEquals("portrait", ScreenRotation.PORTRAIT.stored)
        assertEquals("landscape", ScreenRotation.LANDSCAPE.stored)
    }

    @Test fun the_default_is_auto() {
        assertSame(ScreenRotation.AUTO, ScreenRotation.DEFAULT)
        assertSame(ScreenRotation.AUTO, UserPrefs().screenRotation)
    }

    // --- the cycle ---------------------------------------------------------

    @Test fun one_tap_goes_auto_then_portrait_then_landscape_then_back_to_auto() {
        assertSame(ScreenRotation.PORTRAIT, ScreenRotation.AUTO.next())
        assertSame(ScreenRotation.LANDSCAPE, ScreenRotation.PORTRAIT.next())
        assertSame(ScreenRotation.AUTO, ScreenRotation.LANDSCAPE.next())
    }

    @Test fun three_taps_come_back_to_where_you_started() {
        for (start in ScreenRotation.entries) {
            assertSame("from $start", start, start.next().next().next())
        }
    }

    @Test fun a_tap_always_changes_the_mode() {
        for (m in ScreenRotation.entries) assertNotEquals(m, m.next())
    }

    // --- which orientation each mode asks the system for --------------------

    @Test fun auto_asks_for_no_orientation_so_the_app_follows_the_phone() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, ScreenRotation.AUTO.orientation)
    }

    @Test fun portrait_asks_for_upright_portrait() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ScreenRotation.PORTRAIT.orientation)
    }

    @Test fun landscape_asks_for_either_landscape_direction_and_never_portrait() {
        // SENSOR_LANDSCAPE: landscape either way up, following how the phone is turned. Plain
        // LANDSCAPE would pin one direction, and the portrait values would be wrong outright.
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, ScreenRotation.LANDSCAPE.orientation)
        val portraitValues = listOf(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
        )
        for (v in portraitValues) assertNotEquals(v, ScreenRotation.LANDSCAPE.orientation)
    }

    @Test fun the_three_modes_ask_for_three_different_orientations() {
        assertEquals(3, ScreenRotation.entries.map { it.orientation }.toSet().size)
    }

    // --- reading a stored value ---------------------------------------------

    @Test fun each_stored_value_reads_as_its_mode() {
        for (m in ScreenRotation.entries) assertSame(m, ScreenRotation.fromStored(m.stored))
    }

    @Test fun anything_else_reads_as_auto() {
        val notModes = listOf(
            null, "", " ", "sideways", "0", "1", "6", "PORTRAIT", "Portrait", "LANDSCAPE",
            "Auto", " auto", "auto ", "portrait,landscape", "reverse",
        )
        for (v in notModes) assertSame("stored <$v>", ScreenRotation.AUTO, ScreenRotation.fromStored(v))
    }

    // --- the words the operator sees ----------------------------------------

    @Test fun the_messages_are_the_ones_the_spec_gives() {
        assertEquals("Screen follows the phone", ScreenRotation.AUTO.message)
        assertEquals("Screen stays in portrait", ScreenRotation.PORTRAIT.message)
        assertEquals("Screen stays in landscape", ScreenRotation.LANDSCAPE.message)
    }

    @Test fun the_accessibility_label_names_the_mode() {
        assertEquals("Screen rotation: Auto", ScreenRotation.AUTO.contentDescription)
        assertEquals("Screen rotation: Portrait", ScreenRotation.PORTRAIT.contentDescription)
        assertEquals("Screen rotation: Landscape", ScreenRotation.LANDSCAPE.contentDescription)
    }

    @Test fun the_settings_labels_are_auto_portrait_landscape() {
        assertEquals(listOf("Auto", "Portrait", "Landscape"), ScreenRotation.entries.map { it.label })
    }

    // --- large screens: Android 16 ignores the request ----------------------

    private fun ignored(sdk: Int, target: Int, widthDp: Int) = ScreenRotation.requestIgnored(sdk, target, widthDp)

    @Test fun android_16_an_app_targeting_36_and_a_600_dp_screen_is_ignored() {
        assertTrue(ignored(sdk = 36, target = 36, widthDp = 600))
        assertTrue(ignored(sdk = 36, target = 36, widthDp = 720))
        assertTrue(ignored(sdk = 36, target = 36, widthDp = 1280))
    }

    @Test fun a_screen_narrower_than_600_dp_holds_the_rotation() {
        assertFalse(ignored(sdk = 36, target = 36, widthDp = 599))
        assertFalse(ignored(sdk = 36, target = 36, widthDp = 411))
        assertFalse(ignored(sdk = 36, target = 36, widthDp = 0))
    }

    @Test fun the_screen_size_line_is_exactly_600_dp() {
        assertEquals(600, ScreenRotation.LARGE_SCREEN_DP)
        assertFalse(ignored(sdk = 36, target = 36, widthDp = ScreenRotation.LARGE_SCREEN_DP - 1))
        assertTrue(ignored(sdk = 36, target = 36, widthDp = ScreenRotation.LARGE_SCREEN_DP))
    }

    @Test fun an_older_android_holds_the_rotation_on_any_screen() {
        assertFalse(ignored(sdk = 35, target = 36, widthDp = 900))
        assertFalse(ignored(sdk = 34, target = 34, widthDp = 900))
        assertFalse(ignored(sdk = 26, target = 36, widthDp = 900))
    }

    @Test fun android_16_is_the_first_that_ignores() {
        assertEquals(36, ScreenRotation.FIRST_API_THAT_IGNORES)
        assertFalse(ignored(sdk = ScreenRotation.FIRST_API_THAT_IGNORES - 1, target = 36, widthDp = 720))
        assertTrue(ignored(sdk = ScreenRotation.FIRST_API_THAT_IGNORES, target = 36, widthDp = 720))
    }

    @Test fun an_app_that_targets_an_older_api_is_not_ignored_even_on_android_16() {
        assertFalse(ignored(sdk = 36, target = 35, widthDp = 900))
        assertFalse(ignored(sdk = 36, target = 34, widthDp = 900))
    }

    @Test fun later_android_and_later_targets_are_ignored_too() {
        assertTrue(ignored(sdk = 37, target = 36, widthDp = 720))
        assertTrue(ignored(sdk = 36, target = 37, widthDp = 720))
        assertTrue(ignored(sdk = 37, target = 37, widthDp = 720))
    }

    @Test fun portrait_and_landscape_say_what_is_true_when_the_request_is_ignored() {
        val note = "On a screen this size Android does not let apps hold the rotation"
        assertEquals(note, ScreenRotation.HOLD_IGNORED_NOTE)
        assertEquals(note, ScreenRotation.PORTRAIT.messageFor(requestIgnored = true))
        assertEquals(note, ScreenRotation.LANDSCAPE.messageFor(requestIgnored = true))
    }

    @Test fun auto_still_says_it_follows_the_phone_when_the_request_is_ignored() {
        assertEquals("Screen follows the phone", ScreenRotation.AUTO.messageFor(requestIgnored = true))
    }

    @Test fun every_mode_keeps_its_own_message_when_the_request_is_not_ignored() {
        for (m in ScreenRotation.entries) assertEquals(m.message, m.messageFor(requestIgnored = false))
    }
}
