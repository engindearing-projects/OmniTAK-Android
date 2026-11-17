package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #155 - how wide the geofence alert may be. Seen on a 640 x 360 dp window turned sideways with the
 * font at 1.3, the banner ran across the top of the position card; held sideways it keeps to the
 * left half now.
 */
class GeofenceBannerLayoutTest {

    @Test fun held_upright_the_banner_has_the_whole_width_up_to_its_maximum() {
        assertEquals(480, GeofenceBannerLayout.maxWidthDp(screenWidthDp = 360, landscape = false))
        assertEquals(480, GeofenceBannerLayout.maxWidthDp(screenWidthDp = 411, landscape = false))
        assertEquals(480, GeofenceBannerLayout.maxWidthDp(screenWidthDp = 800, landscape = false))
    }

    @Test fun held_sideways_it_keeps_to_the_left_half() {
        assertEquals(320, GeofenceBannerLayout.maxWidthDp(screenWidthDp = 640, landscape = true))
        assertEquals(457, GeofenceBannerLayout.maxWidthDp(screenWidthDp = 914, landscape = true))
    }

    @Test fun on_a_wide_screen_it_is_never_wider_than_its_maximum() {
        assertEquals(480, GeofenceBannerLayout.maxWidthDp(screenWidthDp = 1280, landscape = true))
    }

    @Test fun the_left_half_stays_clear_of_the_position_card_on_the_windows_it_was_seen_on() {
        // The card (and the pills and the button) are in the right half. The banner starts 12 dp in.
        for (width in listOf(640, 731, 800, 914, 960)) {
            val bannerRightEdge = 12 + GeofenceBannerLayout.maxWidthDp(width, landscape = true)
            assertTrue("at $width dp the banner reaches $bannerRightEdge dp", bannerRightEdge <= width / 2 + 12)
        }
    }
}
