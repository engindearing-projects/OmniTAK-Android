package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure layout decisions for the map tools drawer (#182). The Compose UI
 * itself isn't unit-testable, but the orientation-driven sizing and the
 * horizontal-rail wrapping math are, so they're pinned here.
 */
class ToolsDrawerLayoutTest {

    @Test fun portrait_keeps_vertical_stack() {
        assertFalse(ToolsDrawerLayout.useHorizontalRail(isLandscape = false))
    }

    @Test fun landscape_uses_horizontal_rail() {
        assertTrue(ToolsDrawerLayout.useHorizontalRail(isLandscape = true))
    }

    @Test fun landscape_pips_are_smaller_than_portrait() {
        assertTrue(
            "landscape FAB must be no larger than portrait",
            ToolsDrawerLayout.fabSizeDp(true) <= ToolsDrawerLayout.fabSizeDp(false),
        )
        assertTrue(
            "landscape tool pip must be no larger than portrait",
            ToolsDrawerLayout.toolSizeDp(true) <= ToolsDrawerLayout.toolSizeDp(false),
        )
        assertTrue(
            "landscape spacing must be no larger than portrait",
            ToolsDrawerLayout.spacingDp(true) <= ToolsDrawerLayout.spacingDp(false),
        )
    }

    @Test fun toolsPerRow_fits_as_many_as_the_width_allows() {
        // 40dp pips + 8dp gaps: n pips + (n-1) gaps must fit in the width.
        // 5 pips = 200 + 4*8 = 232; 6 pips = 240 + 5*8 = 280.
        assertEquals(5, ToolsDrawerLayout.toolsPerRow(232, 40, 8))
        assertEquals(5, ToolsDrawerLayout.toolsPerRow(279, 40, 8))
        assertEquals(6, ToolsDrawerLayout.toolsPerRow(280, 40, 8))
    }

    @Test fun toolsPerRow_never_returns_less_than_one() {
        // A tool must never be orphaned off-screen even with a tiny width.
        assertEquals(1, ToolsDrawerLayout.toolsPerRow(0, 40, 8))
        assertEquals(1, ToolsDrawerLayout.toolsPerRow(10, 40, 8))
        assertEquals(1, ToolsDrawerLayout.toolsPerRow(-5, 40, 8))
    }

    @Test fun toolsPerRow_guards_against_zero_pip_size() {
        assertEquals(1, ToolsDrawerLayout.toolsPerRow(200, 0, 8))
    }

    @Test fun narrow_landscape_rail_wraps_a_full_tool_set_into_rows() {
        // On a narrow landscape rail (2/3 of a ~640dp-wide compact device),
        // a 10-tool set must wrap into more than one short row rather than
        // span one edge-to-edge row that re-eats the map.
        val railWidth = (640 * 2) / 3 // ≈ 426dp
        val perRow = ToolsDrawerLayout.toolsPerRow(
            availableWidthDp = railWidth,
            toolSizeDp = ToolsDrawerLayout.toolSizeDp(true),
            spacingDp = ToolsDrawerLayout.spacingDp(true),
        )
        assertTrue("rail should fit several tools per row", perRow >= 3)
        assertTrue("a 10-tool set should wrap into multiple rows here", perRow < 10)
    }

    // MARK: labels (speed dial)
    //
    // The expanded tools used to be ten icon-only circles stacked over the position card. Each now
    // carries its label, and the layout has to hold at the largest font size.

    @Test fun the_icons_line_up_over_the_main_button() {
        // 48 dp icons over a 56 dp button: 4 dp in from the right edge, so both are centred
        // on the same line, as they were when the stack was centred.
        assertEquals(4, ToolsDrawerLayout.dialEndInsetDp(isLandscape = false))
        // 40 dp icons over a 48 dp button.
        assertEquals(4, ToolsDrawerLayout.dialEndInsetDp(isLandscape = true))
    }

    @Test fun the_portrait_dial_is_as_tall_as_its_rows() {
        // ten 48 dp rows with nine 12 dp gaps
        assertEquals(10 * 48 + 9 * 12, ToolsDrawerLayout.dialHeightDp(toolCount = 10, toolSizeDp = 48, spacingDp = 12))
        assertEquals(0, ToolsDrawerLayout.dialHeightDp(toolCount = 0, toolSizeDp = 48, spacingDp = 12))
        assertEquals(48, ToolsDrawerLayout.dialHeightDp(toolCount = 1, toolSizeDp = 48, spacingDp = 12))
    }

    @Test fun a_normal_phone_shows_the_whole_dial_without_scrolling() {
        // 914 dp tall, less the status bar, strip and navigation bar the safe area already took: ~850.
        val max = ToolsDrawerLayout.dialMaxHeightDp(availableHeightDp = 850, fabSizeDp = 56, spacingDp = 12)
        assertTrue("room for ten rows", max >= ToolsDrawerLayout.dialHeightDp(10, 48, 12))
        assertFalse(ToolsDrawerLayout.dialScrolls(10, 48, 12, availableHeightDp = 850, fabSizeDp = 56))
    }

    @Test fun a_short_screen_scrolls_the_dial_instead_of_pushing_it_off_the_top() {
        assertTrue(ToolsDrawerLayout.dialScrolls(10, 48, 12, availableHeightDp = 560, fabSizeDp = 56))
        val max = ToolsDrawerLayout.dialMaxHeightDp(availableHeightDp = 560, fabSizeDp = 56, spacingDp = 12)
        assertTrue("the dial plus the button fit the screen", max + 12 + 56 + ToolsDrawerLayout.TOP_CLEARANCE_DP <= 560)
    }

    @Test fun the_dial_height_is_never_negative() {
        assertEquals(0, ToolsDrawerLayout.dialMaxHeightDp(availableHeightDp = 0, fabSizeDp = 56, spacingDp = 12))
        assertEquals(0, ToolsDrawerLayout.dialMaxHeightDp(availableHeightDp = 100, fabSizeDp = 56, spacingDp = 12))
    }

    @Test fun a_bigger_font_makes_a_wider_label() {
        val normal = ToolsDrawerLayout.labelWidthDp(chars = 12, textSp = 11, fontScale = 1.0f)
        val large = ToolsDrawerLayout.labelWidthDp(chars = 12, textSp = 11, fontScale = 1.3f)
        assertTrue(large > normal)
        assertTrue("a longer label is wider", ToolsDrawerLayout.labelWidthDp(20, 11, 1.0f) > normal)
    }

    @Test fun a_landscape_cell_is_never_narrower_than_its_icon() {
        assertEquals(40, ToolsDrawerLayout.landscapeCellWidthDp(toolSizeDp = 40, longestLabelChars = 0, fontScale = 1.0f))
        assertTrue(ToolsDrawerLayout.landscapeCellWidthDp(40, 12, 1.0f) > 40)
    }

    @Test fun landscape_labels_fit_on_a_typical_phone_at_the_standard_font_size() {
        // 914 x 411 dp: the rail may use 2/3 of the width; about 300 dp of height is left under the strip.
        assertTrue(
            ToolsDrawerLayout.landscapeShowsLabels(
                toolCount = 10, toolSizeDp = 40, spacingDp = 8, fabSizeDp = 48,
                railWidthDp = 609, availableHeightDp = 340, longestLabelChars = 12, fontScale = 1.0f,
            ),
        )
    }

    @Test fun landscape_keeps_icons_only_at_the_largest_font_size_when_labels_would_need_three_rows() {
        // At 1.3 the cells are wide enough that only four fit a row, so ten tools make three
        // rows of icon and label: most of the height the screen has under the strip. The rail
        // exists to leave the map its height (#182), so it keeps icons only instead.
        assertFalse(
            ToolsDrawerLayout.landscapeShowsLabels(
                toolCount = 10, toolSizeDp = 40, spacingDp = 8, fabSizeDp = 48,
                railWidthDp = 609, availableHeightDp = 340, longestLabelChars = 12, fontScale = 1.3f,
            ),
        )
    }

    @Test fun landscape_labels_stay_when_a_wider_screen_keeps_them_to_two_rows() {
        // A tablet: 1280 dp wide, so the rail may use 853 dp and seven large cells fit a row.
        assertTrue(
            ToolsDrawerLayout.landscapeShowsLabels(
                toolCount = 10, toolSizeDp = 40, spacingDp = 8, fabSizeDp = 48,
                railWidthDp = 853, availableHeightDp = 700, longestLabelChars = 12, fontScale = 1.3f,
            ),
        )
    }

    @Test fun a_labelled_landscape_rail_is_never_more_than_two_rows() {
        // Plenty of height, but the cells are wide and the rail narrow: three rows would be needed.
        assertFalse(
            ToolsDrawerLayout.landscapeShowsLabels(
                toolCount = 10, toolSizeDp = 40, spacingDp = 8, fabSizeDp = 48,
                railWidthDp = 300, availableHeightDp = 900, longestLabelChars = 12, fontScale = 1.0f,
            ),
        )
        assertEquals(2, ToolsDrawerLayout.MAX_LABELLED_RAIL_ROWS)
    }

    @Test fun the_label_chip_height_matches_the_chip_that_is_drawn() {
        // The chip's text has its line height pinned to 1.2 x its size, so this is the height
        // the screen shows (the Material default of 24 sp made the real rows 12 dp taller than
        // the rows counted here, and the rail reached into the status strip at the largest font).
        assertEquals(14 + 2 * ToolsDrawerLayout.CHIP_PADDING_V_DP, ToolsDrawerLayout.landscapeLabelHeightDp(1.0f))
        assertEquals(18 + 2 * ToolsDrawerLayout.CHIP_PADDING_V_DP, ToolsDrawerLayout.landscapeLabelHeightDp(1.3f))
        assertEquals(1.2f, ToolsDrawerLayout.LABEL_LINE_HEIGHT_EM, 0f)
    }

    @Test fun landscape_keeps_icons_only_when_labels_would_not_fit_the_height() {
        // A very short landscape screen: the rows with labels are taller than what is left.
        assertFalse(
            ToolsDrawerLayout.landscapeShowsLabels(
                toolCount = 10, toolSizeDp = 40, spacingDp = 8, fabSizeDp = 48,
                railWidthDp = 426, availableHeightDp = 150, longestLabelChars = 12, fontScale = 1.3f,
            ),
        )
    }

    @Test fun landscape_with_no_tools_needs_no_labels_room() {
        assertTrue(
            ToolsDrawerLayout.landscapeShowsLabels(
                toolCount = 0, toolSizeDp = 40, spacingDp = 8, fabSizeDp = 48,
                railWidthDp = 426, availableHeightDp = 100, longestLabelChars = 0, fontScale = 1.0f,
            ),
        )
    }

    // ---- #155: the dial has an eleventh tool, Geofences

    @Test fun eleven_tools_still_get_labels_on_a_normal_landscape_phone() {
        // 914 x 411 dp (the emulator turned sideways): the rail is two thirds of the width, and eleven
        // labelled cells make two rows, the most a labelled rail may have.
        assertTrue(
            ToolsDrawerLayout.landscapeShowsLabels(
                toolCount = 11, toolSizeDp = 40, spacingDp = 8, fabSizeDp = 48,
                railWidthDp = 914 * 2 / 3, availableHeightDp = 395, longestLabelChars = 12, fontScale = 1.0f,
            ),
        )
    }

    @Test fun eleven_tools_in_portrait_scroll_on_a_short_phone_and_not_on_a_tall_one() {
        // 11 x 48 + 10 x 12 = 648 dp of rows.
        assertEquals(648, ToolsDrawerLayout.dialHeightDp(toolCount = 11, toolSizeDp = 48, spacingDp = 12))
        assertTrue(ToolsDrawerLayout.dialScrolls(11, 48, 12, availableHeightDp = 640, fabSizeDp = 56))
        assertFalse(ToolsDrawerLayout.dialScrolls(11, 48, 12, availableHeightDp = 856, fabSizeDp = 56))
    }
}
