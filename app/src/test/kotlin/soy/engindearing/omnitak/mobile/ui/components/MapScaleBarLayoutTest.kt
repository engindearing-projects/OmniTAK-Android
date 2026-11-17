package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.DistanceUnit
import soy.engindearing.omnitak.mobile.data.MapScale

/**
 * The scale bar is drawn exactly as long as the distance it says: the numbers its drawing is made
 * from, and that the row the ground scale is measured on is the row the line is drawn on.
 */
class MapScaleBarLayoutTest {

    @Test fun the_line_runs_from_one_tick_centre_to_the_other_exactly_the_bar_width() {
        for (width in listOf(44f, 50f, 83.7f, 100f, 110f)) {
            assertEquals(width, MapScaleBarLayout.lineEndDp(width) - MapScaleBarLayout.LINE_START_DP, 1e-4f)
        }
    }

    @Test fun every_bar_the_rule_can_make_fits_the_slot_with_its_ticks() {
        var mpd = 0.01
        while (mpd <= 20_000.0) {
            for (unit in DistanceUnit.values()) {
                val bar = MapScale.bar(mpd, MapScale.MAX_WIDTH_DP, unit) ?: continue
                assertTrue(
                    "$mpd $unit",
                    MapScaleBarLayout.lineEndDp(bar.widthDp) + MapScaleBarLayout.STROKE_DP / 2 <= MapScaleBarLayout.SLOT_WIDTH_DP + 1e-3f,
                )
            }
            mpd *= 1.1
        }
    }

    @Test fun the_slot_is_as_wide_as_the_longest_bar_and_its_end_ticks() {
        assertEquals(MapScale.MAX_WIDTH_DP + MapScaleBarLayout.STROKE_DP, MapScaleBarLayout.SLOT_WIDTH_DP, 0f)
    }

    @Test fun the_ground_scale_is_measured_on_the_row_the_line_is_drawn_on() {
        // The line is drawn at the middle of the tick, 5 dp above the slot's bottom edge.
        assertEquals(1000f - 5f * 2.625f, MapScaleBarLayout.rowYPx(1000f, 2.625f), 1e-3f)
        assertEquals(MapScaleBarLayout.TICK_HEIGHT_DP / 2, MapScaleBarLayout.ROW_ABOVE_BOTTOM_DP, 0f)
    }

    @Test fun the_slot_has_room_for_the_ticks_and_a_label_at_a_large_font() {
        // 12 sp text on a 14 sp line, at the largest font scale the other map controls are tested at.
        assertTrue(MapScaleBarLayout.SLOT_HEIGHT_DP >= MapScaleBarLayout.TICK_HEIGHT_DP + 14f * 1.3f)
    }

    @Test fun the_sample_points_lie_inside_the_slot_either_side_of_its_middle() {
        // 50 dp either side of the middle of the slot: the ground the longest bar can stand over.
        val middle = MapScaleBarLayout.SLOT_WIDTH_DP / 2
        assertTrue(middle - MapScale.SAMPLE_HALF_SPAN_DP >= 0f)
        assertTrue(middle + MapScale.SAMPLE_HALF_SPAN_DP <= MapScaleBarLayout.SLOT_WIDTH_DP)
    }
}
