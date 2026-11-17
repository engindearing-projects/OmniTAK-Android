package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the map's own controls sit relative to the floating toolbar and to the tool banners.
 *
 * Before, the toolbar (and its first-run tip) was drawn over the tools button and the bottom of
 * the position card, and a tool banner was drawn over the compass. The toolbar's height depends
 * on the phone's font size and on whether the tip is showing, so it is measured, and what the
 * controls need from it is one number: how many dp it reaches above the safe area's bottom edge.
 * Everything below is plain arithmetic on that number.
 */
class MapChromeLayoutTest {

    // The test device: 1080 x 2400 at 420 dpi, gesture navigation.
    private val density = 2.625f
    private val gestureBottomPx = 63

    private fun px(dp: Int) = (dp * density).toInt()

    // MARK: how much of the toolbar is above the safe area

    @Test fun no_toolbar_occupies_nothing() {
        assertEquals(0, MapOrnamentLayout.toolbarOccupiedDp(0, gestureBottomPx, density))
    }

    @Test fun only_the_part_above_the_safe_area_counts() {
        // The toolbar stands on the navigation bar: its own height includes the bar's inset.
        val withBar = MapOrnamentLayout.toolbarOccupiedDp(px(94) + gestureBottomPx, gestureBottomPx, density)
        val withoutBar = MapOrnamentLayout.toolbarOccupiedDp(px(94), 0, density)
        assertEquals(withoutBar, withBar)
    }

    @Test fun a_toolbar_shorter_than_the_inset_occupies_nothing() {
        assertEquals(0, MapOrnamentLayout.toolbarOccupiedDp(40, gestureBottomPx, density))
    }

    @Test fun the_height_is_rounded_up_never_short() {
        // 100.2 dp must not become 100.
        val px = (100.2f * density).toInt() + 1
        assertEquals(101, MapOrnamentLayout.toolbarOccupiedDp(px + gestureBottomPx, gestureBottomPx, density))
    }

    @Test fun a_taller_toolbar_occupies_more() {
        // The tip, a larger font and the edit header all make it taller.
        var last = -1
        for (heightDp in 60..320 step 10) {
            val now = MapOrnamentLayout.toolbarOccupiedDp(px(heightDp) + gestureBottomPx, gestureBottomPx, density)
            assertTrue(now > last)
            last = now
        }
    }

    // MARK: what the controls must clear

    @Test fun nothing_has_to_be_cleared_while_the_toolbar_is_hidden() {
        assertEquals(0f, MapOrnamentLayout.clearanceDp(0f), 0f)
    }

    @Test fun the_clearance_is_the_toolbar_and_a_gap() {
        assertEquals(94f + MapOrnamentLayout.TOOLBAR_GAP_DP, MapOrnamentLayout.clearanceDp(94f), 0f)
    }

    // MARK: lifting a control

    @Test fun a_control_already_above_the_toolbar_does_not_move() {
        // The zoom stack rests 110 dp up: clear of a 94 dp toolbar and its gap.
        assertEquals(0f, MapOrnamentLayout.liftDp(110f, MapOrnamentLayout.clearanceDp(94f)), 0f)
    }

    @Test fun a_control_under_the_toolbar_moves_exactly_as_far_as_it_must() {
        // The tools button rests 16 dp up. It ends with its bottom edge 8 dp above the toolbar.
        val clearance = MapOrnamentLayout.clearanceDp(94f)
        assertEquals(clearance - 16f, MapOrnamentLayout.liftDp(16f, clearance), 0f)
    }

    @Test fun while_the_toolbar_is_hidden_no_control_is_moved() {
        for (resting in listOf(0f, 12f, 16f, 24f, 96f, 110f)) {
            assertEquals(0f, MapOrnamentLayout.liftDp(resting, 0f), 0f)
        }
    }

    @Test fun every_control_ends_up_above_the_toolbar_whatever_its_height() {
        val resting = listOf(
            0f, // the message bar
            MapOrnamentLayout.toolsButtonBottomDp(false).toFloat(),
            MapOrnamentLayout.toolsButtonBottomDp(true).toFloat(),
            MapOrnamentLayout.zoomControlsBottomDp(false).toFloat(),
            MapOrnamentLayout.zoomControlsBottomDp(true).toFloat(),
            MapOrnamentLayout.ATTRIBUTION_BOTTOM_DP.toFloat(),
        )
        for (occupied in 40..320 step 4) {
            val clearance = MapOrnamentLayout.clearanceDp(occupied.toFloat())
            for (r in resting) {
                val bottom = r + MapOrnamentLayout.liftDp(r, clearance)
                assertTrue("rest $r, toolbar $occupied dp", bottom >= clearance)
                assertTrue("rest $r, toolbar $occupied dp", bottom >= occupied + MapOrnamentLayout.TOOLBAR_GAP_DP)
            }
        }
    }

    @Test fun a_lift_never_gets_smaller_as_the_toolbar_grows() {
        // The move is animated: it must not step back while the toolbar is still appearing.
        for (resting in listOf(0f, 16f, 110f)) {
            var last = -1f
            for (occupied in 0..320) {
                val lift = MapOrnamentLayout.liftDp(resting, MapOrnamentLayout.clearanceDp(occupied.toFloat()))
                assertTrue(lift >= last)
                last = lift
            }
        }
    }

    // MARK: the bottom-right corner: the tools button and the position card above it

    @Test fun the_card_keeps_its_place_above_the_tools_button() {
        // They lift together: the card is above the button's top edge by the same distance
        // with the toolbar showing as without it, in both orientations.
        for (landscape in listOf(false, true)) {
            val buttonSize = if (landscape) 48 else 56
            val gap = MapOrnamentLayout.POSITION_CARD_BOTTOM_DP -
                (MapOrnamentLayout.toolsButtonBottomDp(landscape) + buttonSize)
            assertTrue("the card rests above the button", gap > 0)
            for (occupied in listOf(0f, 94f, 141f, 260f)) {
                val lift = MapOrnamentLayout.liftDp(
                    MapOrnamentLayout.toolsButtonBottomDp(landscape).toFloat(),
                    MapOrnamentLayout.clearanceDp(occupied),
                )
                val buttonTop = MapOrnamentLayout.toolsButtonBottomDp(landscape) + buttonSize + lift
                val cardBottom = MapOrnamentLayout.POSITION_CARD_BOTTOM_DP + lift
                assertEquals(gap.toFloat(), cardBottom - buttonTop, 0.001f)
            }
        }
    }

    @Test fun the_tools_button_ends_up_above_the_toolbar_with_and_without_the_tip() {
        // Portrait at the standard font size: a 94 dp toolbar, 141 dp with the tip over it.
        for (occupied in listOf(94f, 141f)) {
            val clearance = MapOrnamentLayout.clearanceDp(occupied)
            val lift = MapOrnamentLayout.liftDp(MapOrnamentLayout.toolsButtonBottomDp(false).toFloat(), clearance)
            assertEquals(clearance, MapOrnamentLayout.toolsButtonBottomDp(false) + lift, 0.001f)
        }
    }

    // MARK: the attribution button

    private val safe = MapOrnamentLayout.EdgePx(top = 114, bottom = 63)

    @Test fun the_attribution_button_rests_low_while_the_toolbar_is_hidden() {
        // It used to float 104 dp up all the time to stay clear of a toolbar that is shown 3 s in a session.
        val m = MapOrnamentLayout.attributionMarginsPx(safe, 1f, landscape = false)
        assertEquals(63 + MapOrnamentLayout.ATTRIBUTION_BOTTOM_DP, m.bottom)
    }

    @Test fun the_attribution_button_rises_above_the_toolbar_and_its_tip() {
        for (occupied in listOf(94f, 141f, 200f)) {
            val clearance = MapOrnamentLayout.clearanceDp(occupied)
            val m = MapOrnamentLayout.attributionMarginsPx(MapOrnamentLayout.EdgePx(), 1f, landscape = false, clearanceDp = clearance)
            assertTrue(m.bottom >= clearance)
        }
    }

    @Test fun in_landscape_the_attribution_button_stays_above_the_zoom_row_wherever_the_row_is() {
        // The attribution button is inside the row's horizontal span (92 dp in, the row runs from 16 dp to
        // 176 dp), so the only way to stay clear of it is up, and the row lifts with the toolbar.
        for (occupied in listOf(0f, 62f, 100f, 180f)) {
            val clearance = MapOrnamentLayout.clearanceDp(occupied)
            val rowTop = MapOrnamentLayout.ZOOM_ROW_BOTTOM_DP + MapOrnamentLayout.ZOOM_BUTTON_DP +
                MapOrnamentLayout.liftDp(MapOrnamentLayout.ZOOM_ROW_BOTTOM_DP.toFloat(), clearance)
            val m = MapOrnamentLayout.attributionMarginsPx(MapOrnamentLayout.EdgePx(), 1f, landscape = true, clearanceDp = clearance)
            assertTrue("toolbar $occupied dp", m.bottom >= rowTop + MapOrnamentLayout.ATTRIBUTION_GAP_DP)
        }
    }

    // MARK: the compass and the tool banners

    @Test fun the_compass_sits_where_it_was_when_no_banner_is_showing() {
        assertEquals(0, MapOrnamentLayout.compassTopGapDp(bannerShowing = false))
    }

    @Test fun the_compass_drops_below_a_banner_with_a_gap() {
        assertTrue(MapOrnamentLayout.compassTopGapDp(bannerShowing = true) > 0)
        assertEquals(MapOrnamentLayout.COMPASS_BELOW_BANNER_GAP_DP, MapOrnamentLayout.compassTopGapDp(bannerShowing = true))
    }

    @Test fun the_banners_and_the_compass_start_under_the_status_strip() {
        assertTrue(MapOrnamentLayout.TOP_CHROME_DP >= MapOrnamentLayout.STATUS_STRIP_DP + MapOrnamentLayout.COMPASS_GAP_DP)
        assertFalse("the compass cannot start above its own place", MapOrnamentLayout.TOP_CHROME_DP < MapOrnamentLayout.STATUS_STRIP_DP)
    }
}
