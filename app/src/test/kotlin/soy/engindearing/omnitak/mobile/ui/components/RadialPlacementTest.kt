package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where the radial menu's ring goes for a long press. The ring reaches 90 dp from its centre to the
 * middle of each item and each item is 56 dp, so the ring is 118 dp from its centre at its widest:
 * a press within 118 dp of an edge opened a ring half off the screen, its items out of reach.
 *
 * The test device: 1080 x 2400 px at 2.625 px per dp (411 x 914 dp), gesture navigation.
 */
class RadialPlacementTest {

    private val density = 2.625f
    private val reach = RadialPlacement.reachPx(radiusDp = 90, itemSizeDp = 56, density = density)

    private val width = 1080f
    private val height = 2400f
    private val statusBar = 63f
    private val gestureBar = 63f

    private fun place(x: Float, y: Float, left: Float = 0f, top: Float = statusBar, right: Float = 0f, bottom: Float = gestureBar) =
        RadialPlacement.centre(Offset(x, y), reach, left, top, width - right, height - bottom)

    private fun dp(v: Float) = v * density

    @Test fun the_ring_reaches_118_dp_from_its_centre() {
        assertEquals(118f * density, reach, 1e-3f)
    }

    // MARK: a press in the open stays exactly under the finger

    @Test fun a_press_in_the_open_is_not_moved() {
        for ((x, y) in listOf(540f to 1200f, 400f to 800f, 700f to 1600f, 540f to 700f)) {
            val c = place(x, y)
            assertEquals("x for ($x, $y)", x, c.x, 0f)
            assertEquals("y for ($x, $y)", y, c.y, 0f)
        }
    }

    @Test fun a_press_whose_ring_just_fits_is_not_moved() {
        val c = place(reach, 1200f)
        assertEquals(reach, c.x, 0f)
        val r = place(width - reach, 1200f)
        assertEquals(width - reach, r.x, 0f)
        val t = place(540f, statusBar + reach)
        assertEquals(statusBar + reach, t.y, 0f)
        val b = place(540f, height - gestureBar - reach)
        assertEquals(height - gestureBar - reach, b.y, 0f)
    }

    // MARK: near an edge the ring moves in just enough

    @Test fun a_press_100_dp_from_the_left_edge_moves_the_ring_right_so_it_is_on_screen() {
        val c = place(dp(100f), 1200f)
        assertEquals("the ring's left edge is at the screen edge", 0f, c.x - reach, 1e-3f)
        assertEquals("up and down is not touched", 1200f, c.y, 0f)
        assertEquals("by 18 dp, the part of the ring that was off the screen", dp(18f), c.x - dp(100f), 1e-3f)
    }

    @Test fun a_press_near_the_right_edge_moves_the_ring_left() {
        val c = place(width - dp(60f), 1200f)
        assertEquals(width, c.x + reach, 1e-3f)
        assertEquals(1200f, c.y, 0f)
    }

    @Test fun a_press_near_the_top_moves_the_ring_down_to_below_the_status_bar() {
        val c = place(540f, statusBar + dp(40f))
        assertEquals(statusBar, c.y - reach, 1e-3f)
        assertEquals(540f, c.x, 0f)
    }

    @Test fun a_press_near_the_bottom_moves_the_ring_up_to_above_the_gesture_bar() {
        val c = place(540f, height - dp(30f))
        assertEquals(height - gestureBar, c.y + reach, 1e-3f)
        assertEquals(540f, c.x, 0f)
    }

    @Test fun a_press_in_a_corner_moves_the_ring_on_both_axes() {
        val c = place(dp(20f), statusBar + dp(20f))
        assertEquals(reach, c.x, 1e-3f)
        assertEquals(statusBar + reach, c.y, 1e-3f)
    }

    @Test fun the_ring_stays_inside_a_display_cutout_in_landscape() {
        // Landscape, a 68 px camera cutout on the left edge: 2400 x 1080, the status bar on top, the gesture bar at the bottom.
        val c = RadialPlacement.centre(Offset(100f, 540f), reach, 68f, 24f, 2400f, 1080f - 16f)
        assertEquals("the ring's left edge is at the cutout's edge", 68f, c.x - reach, 1e-3f)
    }

    @Test fun a_three_button_bar_on_the_right_in_landscape_is_kept_clear_of() {
        val c = RadialPlacement.centre(Offset(2380f, 540f), reach, 68f, 24f, 2400f - 144f, 1080f)
        assertEquals(2400f - 144f, c.x + reach, 1e-3f)
    }

    // MARK: the whole ring is always on screen when there is room for it

    @Test fun wherever_the_press_is_the_whole_ring_is_inside_the_safe_area() {
        var x = 0f
        while (x <= width) {
            var y = 0f
            while (y <= height) {
                val c = place(x, y)
                assertTrue("left of ($x, $y)", c.x - reach >= 0f - 1e-3f)
                assertTrue("right of ($x, $y)", c.x + reach <= width + 1e-3f)
                assertTrue("top of ($x, $y)", c.y - reach >= statusBar - 1e-3f)
                assertTrue("bottom of ($x, $y)", c.y + reach <= height - gestureBar + 1e-3f)
                y += 97f
            }
            x += 53f
        }
    }

    @Test fun the_ring_moves_no_further_than_it_has_to() {
        var x = 0f
        while (x <= width) {
            var y = 0f
            while (y <= height) {
                val c = place(x, y)
                val fits = x - reach >= 0f && x + reach <= width && y - reach >= statusBar && y + reach <= height - gestureBar
                if (fits) {
                    assertEquals("a ring that fits stays at ($x, $y)", x, c.x, 0f)
                    assertEquals(y, c.y, 0f)
                }
                // On each axis, it ends up at the nearest place that fits: the press itself, or the nearest edge's limit.
                assertEquals(x.coerceIn(reach, width - reach), c.x, 1e-3f)
                assertEquals(y.coerceIn(statusBar + reach, height - gestureBar - reach), c.y, 1e-3f)
                y += 97f
            }
            x += 53f
        }
    }

    // MARK: no room

    @Test fun a_window_too_small_for_the_ring_centres_it_in_the_room_there_is() {
        // 200 dp wide (a narrow split-screen window): the ring is 236 dp, so it cannot fit; it sits in the middle.
        val c = RadialPlacement.centre(Offset(30f, 500f), reach, 0f, 0f, dp(200f), 2000f)
        assertEquals(dp(100f), c.x, 1e-3f)
        assertEquals(500f, c.y, 0f)
    }

    // MARK: RadialMenu uses it

    private val menu: String by lazy {
        listOf(
            "src/main/kotlin/soy/engindearing/omnitak/mobile/ui/components/RadialMenu.kt",
            "app/src/main/kotlin/soy/engindearing/omnitak/mobile/ui/components/RadialMenu.kt",
        ).map(::File).first { it.exists() }.readText()
    }

    @Test fun the_menu_places_its_ring_and_its_cancel_button_with_the_rule() {
        assertTrue(menu.contains("RadialPlacement.centre("))
        // Items and cancel button are laid out round the placed centre, never the raw press.
        assertTrue(!menu.contains("anchor.x"))
        assertTrue(!menu.contains("anchor.y"))
    }
}
