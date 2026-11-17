package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * The map is drawn under the system bars and the display cutout. Two things use the window's insets
 * and they are not the same set:
 *
 *  - the map's own chrome keeps clear of both the bars and a cutout, so nothing is covered or cut off;
 *  - the navy fade behind the system icons is only for the bars, where the icons are. A cutout is a
 *    hole for the camera with no icons in it; in landscape the fade put a dark band down that edge of
 *    the map (the cutout is on the left of a phone turned to landscape).
 */
class MapInsetsTest {

    private val density = Density(1f)

    private fun WindowInsets.edges() = intArrayOf(
        getLeft(density, LayoutDirection.Ltr), getTop(density), getRight(density, LayoutDirection.Ltr), getBottom(density),
    )

    // A phone in landscape with gesture navigation: the status bar on top, the gesture bar at the bottom,
    // and the camera cutout on the left edge (the emulator's: 68 px).
    private val barsLandscapeGestures = WindowInsets(left = 0, top = 24, right = 0, bottom = 16)
    private val cutoutLeft = WindowInsets(left = 68, top = 0, right = 0, bottom = 0)

    @Test fun the_chrome_keeps_clear_of_the_bars_and_the_cutout() {
        val insets = MapInsets(barsLandscapeGestures, cutoutLeft)
        assertArrayEquals(intArrayOf(68, 24, 0, 16), insets.safeArea.edges())
    }

    @Test fun the_fade_covers_the_bars_and_not_the_cutout() {
        val insets = MapInsets(barsLandscapeGestures, cutoutLeft)
        assertArrayEquals("no bar on the left, so no fade there", intArrayOf(0, 24, 0, 16), insets.systemBars.edges())
    }

    @Test fun a_cutout_deeper_than_the_bar_does_not_deepen_the_fade() {
        // Portrait with a tall notch: the cutout reaches 90 px down, the status bar is 63 px.
        val insets = MapInsets(WindowInsets(top = 63, bottom = 63), WindowInsets(top = 90))
        assertArrayEquals(intArrayOf(0, 90, 0, 63), insets.safeArea.edges())
        assertArrayEquals(intArrayOf(0, 63, 0, 63), insets.systemBars.edges())
    }

    @Test fun a_navigation_bar_on_the_side_still_gets_its_fade() {
        // Three-button navigation in landscape puts the bar on the right edge.
        val bars = WindowInsets(left = 0, top = 24, right = 48, bottom = 0)
        val insets = MapInsets(bars, cutoutLeft)
        assertArrayEquals(intArrayOf(68, 24, 48, 0), insets.safeArea.edges())
        assertArrayEquals(intArrayOf(0, 24, 48, 0), insets.systemBars.edges())
    }

    @Test fun with_no_cutout_the_two_are_the_same() {
        val insets = MapInsets(WindowInsets(0, 63, 0, 63), WindowInsets(0, 0, 0, 0))
        assertArrayEquals(insets.safeArea.edges(), insets.systemBars.edges())
    }
}
