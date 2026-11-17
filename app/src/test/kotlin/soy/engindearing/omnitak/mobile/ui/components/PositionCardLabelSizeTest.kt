package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #213 - the position box on the map follows the Label size setting. Compose's
 * sp already carries the phone's font size, so these are design sizes x the
 * setting only.
 */
class PositionCardLabelSizeTest {

    private val eps = 0.001f

    @Test fun at_100_percent_the_card_keeps_the_sizes_it_always_had() {
        assertEquals(12f, positionCardCallsignSp(100), eps)
        assertEquals(11f, positionCardDetailSp(100), eps)
    }

    @Test fun every_choice_scales_both_lines() {
        assertEquals(9.6f, positionCardCallsignSp(80), eps)
        assertEquals(8.8f, positionCardDetailSp(80), eps)
        assertEquals(14.4f, positionCardCallsignSp(120), eps)
        assertEquals(13.2f, positionCardDetailSp(120), eps)
        assertEquals(16.8f, positionCardCallsignSp(140), eps)
        assertEquals(15.4f, positionCardDetailSp(140), eps)
        assertEquals(19.2f, positionCardCallsignSp(160), eps)
        assertEquals(17.6f, positionCardDetailSp(160), eps)
    }

    @Test fun a_value_that_is_not_a_choice_is_read_as_the_nearest_choice() {
        assertEquals(positionCardCallsignSp(80), positionCardCallsignSp(0), eps)
        assertEquals(positionCardDetailSp(160), positionCardDetailSp(5000), eps)
    }

    // --- the box wraps before it reaches the map buttons --------------------

    @Test fun on_a_411_dp_phone_the_box_may_be_327_dp_wide() {
        // 411 - 12 (gap to the right edge) - 64 (right edge of the buttons) - 8 (gap)
        assertEquals(327, positionCardMaxWidthDp(411))
    }

    @Test fun the_box_stops_short_of_the_map_buttons_on_every_common_phone_width() {
        for (screen in listOf(320, 360, 393, 411, 430, 600)) {
            val max = positionCardMaxWidthDp(screen)
            val leftEdgeOfBox = screen - 12 - max
            assertTrue(
                "box left edge $leftEdgeOfBox dp must clear the buttons (right edge 64 dp) on a $screen dp screen",
                leftEdgeOfBox >= 64 + 8,
            )
        }
    }

    @Test fun a_very_narrow_screen_does_not_squeeze_the_box_to_nothing() {
        assertEquals(160, positionCardMaxWidthDp(100))
        assertEquals(160, positionCardMaxWidthDp(0))
    }
}
