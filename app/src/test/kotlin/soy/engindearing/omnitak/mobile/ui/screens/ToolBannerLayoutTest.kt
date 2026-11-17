package soy.engindearing.omnitak.mobile.ui.screens

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The tool banners (Drawing, Measure, Range rings) are a row: a readout, then the buttons that end
 * or undo the tool. A Row measures its children in order, so a readout with no weight takes all the
 * width it asks for and the buttons get what is left. On a 360 dp phone with the font at 1.3 the
 * Drawing banner's Save button was pushed off the end of the banner, and a drawing could not be saved.
 *
 * The rule is `weight(1f, fill = false)` on the readout: the buttons are measured first, the readout
 * gets the rest and wraps. There is no way to run a Compose layout in a JVM test, so this reads the
 * three banners' source, the same way CesiumScaleEventTest reads the globe page.
 */
class ToolBannerLayoutTest {

    private val source: String by lazy {
        listOf(
            "src/main/kotlin/soy/engindearing/omnitak/mobile/ui/screens/MapScreen.kt",
            "app/src/main/kotlin/soy/engindearing/omnitak/mobile/ui/screens/MapScreen.kt",
        ).map(::File).first { it.exists() }.readText()
    }

    /** The body of a banner composable: from its declaration to the next top-level declaration. */
    private fun banner(name: String): String {
        val start = source.indexOf("private fun $name(")
        assertTrue("$name is in MapScreen.kt", start >= 0)
        val rest = source.substring(start + 1)
        val next = Regex("\n(@Composable|private fun |/\\*\\*)").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, next)
    }

    private val readoutWeight = "modifier = Modifier.weight(1f, fill = false)"

    @Test fun the_drawing_banner_readout_leaves_the_buttons_their_width() {
        assertTrue(banner("DrawingOverlay").contains(readoutWeight))
    }

    @Test fun the_measure_banner_readout_leaves_the_buttons_their_width() {
        assertTrue(banner("MeasurementOverlay").contains(readoutWeight))
    }

    @Test fun the_range_rings_banner_readout_leaves_the_buttons_their_width() {
        assertTrue(banner("RangeRingsOverlay").contains(readoutWeight))
    }

    @Test fun the_drawing_banner_still_has_undo_cancel_and_save() {
        val drawing = banner("DrawingOverlay")
        for (button in listOf("\"Undo\"", "\"Cancel\"", "\"Save\"")) {
            assertTrue("$button is on the Drawing banner", drawing.contains(button))
        }
    }
}
