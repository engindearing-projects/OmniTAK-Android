package soy.engindearing.omnitak.mobile.data

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
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
}
