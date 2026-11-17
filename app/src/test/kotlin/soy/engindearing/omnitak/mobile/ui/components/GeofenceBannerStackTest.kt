package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #155 - the geofence alert and the pills under it. Held upright, the alert is as wide as the screen and
 * starts 72 dp under the safe area's top edge, in the same band as the lasso's "N selected" pill and the
 * drone's pills (90 dp down, at the right), so it covered them: a 360 dp phone has no room beside it for
 * a pill. The pills, and the two top-centre banners of the drone, now start under the alert while it
 * shows and move back when it goes.
 *
 * Plain arithmetic: the alert's height is measured and handed in. That the alert reports a height of zero when it
 * leaves is tested on the real banner, in [GeofenceBannerHeightTest].
 */
class GeofenceBannerStackTest {

    private val alertTop = MapOrnamentLayout.TOP_CHROME_DP

    /** The alert as it is drawn: a row 60 dp tall with the 8 dp it leaves under itself. */
    private val alertHeight = 68f

    private val pills = MapOrnamentLayout.TOP_RIGHT_PILLS_DP
    private val banners = MapOrnamentLayout.UAS_ALERT_TOP_DP

    private fun shift(rest: Int, height: Float, landscape: Boolean = false) =
        MapOrnamentLayout.shiftBelowGeofenceBannerDp(rest, height, landscape)

    @Test fun with_no_alert_everything_rests_where_it_always_did() {
        for (rest in listOf(pills, banners)) {
            assertEquals(0f, shift(rest, 0f), 0f)
            assertEquals(0f, shift(rest, 0f, landscape = true), 0f)
        }
    }

    @Test fun held_upright_the_pills_start_under_the_alert() {
        val top = pills + shift(pills, alertHeight)
        assertEquals("the alert's bottom edge, the 8 dp under it included", alertTop + alertHeight, top, 0f)
    }

    @Test fun the_alert_is_never_drawn_over_a_pill_or_a_banner_whatever_its_height() {
        // One line, two lines, and two lines with the font at 1.3 or larger.
        for (height in listOf(40f, 68f, 96f, 124f, 180f, 300f)) {
            for (rest in listOf(pills, banners)) {
                val top = rest + shift(rest, height)
                assertTrue("rest $rest, alert $height dp: first thing at $top", top >= alertTop + height)
                assertTrue("and never above where it rests", top >= rest)
            }
        }
    }

    @Test fun a_thin_alert_that_ends_above_the_pills_does_not_move_them() {
        // They rest at 90 dp; an alert that ends at 72 + 10 = 82 dp is no reason to move them.
        assertEquals(0f, shift(pills, 10f), 0f)
    }

    @Test fun the_drones_two_top_centre_banners_move_together_and_keep_the_gap_between_them() {
        val alertTopNow = MapOrnamentLayout.UAS_ALERT_TOP_DP + shift(banners, alertHeight)
        val missionTopNow = MapOrnamentLayout.MISSION_BANNER_TOP_DP + shift(banners, alertHeight)
        assertEquals(
            "the status-text alert still starts above the mission banner by what it did",
            (MapOrnamentLayout.MISSION_BANNER_TOP_DP - MapOrnamentLayout.UAS_ALERT_TOP_DP).toFloat(),
            missionTopNow - alertTopNow,
            0f,
        )
        assertEquals("and the first of them is under the geofence alert", alertTop + alertHeight, alertTopNow, 0f)
    }

    @Test fun held_sideways_the_pills_at_the_right_stay_put_because_the_alert_keeps_to_the_left_half() {
        assertEquals(0f, shift(pills, alertHeight, landscape = true), 0f)
    }

    @Test fun held_sideways_the_drones_top_centre_banners_still_move_because_the_alert_reaches_into_the_middle() {
        // They are centred on the window, so the alert in the left half runs under their left side.
        val sideways = MapOrnamentLayout.shiftBelowGeofenceBannerDp(banners, alertHeight, landscape = true, reachesTheLeftHalf = true)
        assertEquals(shift(banners, alertHeight, landscape = false), sideways, 0f)
        assertTrue(sideways > 0f)
        assertEquals("and with no alert they stay where they are", 0f, MapOrnamentLayout.shiftBelowGeofenceBannerDp(banners, 0f, landscape = true, reachesTheLeftHalf = true), 0f)
    }

    @Test fun the_resting_places_are_the_ones_the_map_screen_always_used() {
        assertEquals(90, MapOrnamentLayout.TOP_RIGHT_PILLS_DP)
        assertEquals(64, MapOrnamentLayout.UAS_ALERT_TOP_DP)
        assertEquals(90, MapOrnamentLayout.MISSION_BANNER_TOP_DP)
    }
}
