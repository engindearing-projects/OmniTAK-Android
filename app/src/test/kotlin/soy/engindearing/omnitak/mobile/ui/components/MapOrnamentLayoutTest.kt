package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.ui.components.MapOrnamentLayout.EdgePx

/**
 * Where MapLibre draws its own compass and attribution button.
 *
 * The map fills the whole window, under the status bar and the navigation bar, so a margin is
 * measured from the edge of the window: the safe area plus what the app's own controls take.
 * Before, the map stopped at the safe area, the compass margin added the status bar a second
 * time on top of a constant that already allowed for it, and the attribution button had only
 * the default 4 dp from the bottom of the map, which is under the floating toolbar.
 */
class MapOrnamentLayoutTest {

    // The test device: 1080 x 2400 at 420 dpi, gesture navigation.
    private val density = 2.625f
    private val gestureSafeArea = EdgePx(left = 0, top = 114, right = 0, bottom = 63)

    private fun dp(value: Int, d: Float = density) = (value * d + 0.5f).toInt()

    // MARK: compass

    @Test fun the_compass_sits_below_the_status_strip_in_the_safe_area() {
        val m = MapOrnamentLayout.compassMarginsPx(gestureSafeArea, density)
        assertEquals(
            "status bar + the 40 dp strip + an 8 dp gap",
            gestureSafeArea.top + dp(MapOrnamentLayout.STATUS_STRIP_DP + MapOrnamentLayout.COMPASS_GAP_DP),
            m.top,
        )
    }

    @Test fun the_status_bar_is_counted_once_in_the_compass_margin() {
        // The fault: the constant was 64 dp (which already allowed 24 dp for the status bar) and
        // the status bar was added on top of it as well.
        val none = MapOrnamentLayout.compassMarginsPx(EdgePx(), density)
        val tall = MapOrnamentLayout.compassMarginsPx(EdgePx(top = 114), density)
        assertEquals("one more px of status bar is one more px of margin", 114, tall.top - none.top)
    }

    @Test fun the_compass_clears_a_navigation_bar_on_the_side_and_a_cutout() {
        // Landscape with three-button navigation: the bar is on the right edge. A cutout is on
        // the left or right edge.
        val m = MapOrnamentLayout.compassMarginsPx(EdgePx(left = 96, top = 63, right = 126, bottom = 0), density)
        assertEquals(96 + dp(MapOrnamentLayout.SIDE_MARGIN_DP), m.left)
        assertEquals(126 + dp(MapOrnamentLayout.SIDE_MARGIN_DP), m.right)
    }

    @Test fun the_compass_keeps_its_default_side_margin_when_there_is_nothing_to_clear() {
        val m = MapOrnamentLayout.compassMarginsPx(EdgePx(), 1f)
        assertEquals(MapOrnamentLayout.SIDE_MARGIN_DP, m.left)
        assertEquals(MapOrnamentLayout.SIDE_MARGIN_DP, m.right)
    }

    // MARK: attribution

    @Test fun the_attribution_button_is_lifted_over_the_navigation_bar() {
        val gesture = MapOrnamentLayout.attributionMarginsPx(gestureSafeArea, density)
        val none = MapOrnamentLayout.attributionMarginsPx(gestureSafeArea.copy(bottom = 0), density)
        assertEquals("the bar's height is added once", 63, gesture.bottom - none.bottom)
    }

    @Test fun the_attribution_button_is_clear_of_the_floating_toolbar() {
        // The toolbar overlay stands on the navigation bar: 86 dp of pill and its padding, so 96
        // with room for a larger font. It is wider than the screen minus 28 dp, so the only way
        // out from under it is up.
        val portrait = MapOrnamentLayout.attributionMarginsPx(EdgePx(), 1f, landscape = false)
        val landscape = MapOrnamentLayout.attributionMarginsPx(EdgePx(), 1f, landscape = true)
        assertTrue("above the pill in portrait", portrait.bottom >= MapOrnamentLayout.FLOATING_TOOLBAR_PORTRAIT_DP)
        assertTrue("above the compact pill in landscape", landscape.bottom >= MapOrnamentLayout.FLOATING_TOOLBAR_LANDSCAPE_DP)
    }

    @Test fun the_attribution_button_is_clear_of_the_landscape_control_row() {
        // The zoom and centre buttons lie along the bottom edge in landscape: 12 dp up, 48 dp tall.
        val m = MapOrnamentLayout.attributionMarginsPx(EdgePx(), 1f, landscape = true)
        assertTrue(m.bottom >= 12 + 48)
    }

    @Test fun landscape_needs_less_lift_than_portrait() {
        // The landscape toolbar is a compact row, so the button does not float as high.
        assertTrue(MapOrnamentLayout.attributionClearanceDp(landscape = true) < MapOrnamentLayout.attributionClearanceDp(landscape = false))
    }

    @Test fun the_attribution_button_stays_to_the_right_of_the_zoom_buttons() {
        // The zoom buttons run down the left edge: 16 dp in, 48 dp wide.
        val m = MapOrnamentLayout.attributionMarginsPx(EdgePx(), 1f)
        assertTrue(m.left >= 16 + 48)
    }

    @Test fun the_attribution_button_clears_a_cutout_on_the_left() {
        val m = MapOrnamentLayout.attributionMarginsPx(EdgePx(left = 120), density)
        assertEquals(120 + dp(MapOrnamentLayout.ATTRIBUTION_LEFT_DP), m.left)
    }

    @Test fun margins_scale_with_the_screen_density() {
        val a = MapOrnamentLayout.attributionMarginsPx(EdgePx(), 2f)
        val b = MapOrnamentLayout.attributionMarginsPx(EdgePx(), 3f)
        assertEquals(MapOrnamentLayout.ATTRIBUTION_LEFT_DP * 2, a.left)
        assertEquals(MapOrnamentLayout.ATTRIBUTION_LEFT_DP * 3, b.left)
        assertEquals(MapOrnamentLayout.SIDE_MARGIN_DP * 3, b.right)
    }
}
