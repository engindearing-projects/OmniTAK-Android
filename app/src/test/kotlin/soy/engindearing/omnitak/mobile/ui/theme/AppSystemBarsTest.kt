package soy.engindearing.omnitak.mobile.ui.theme

import androidx.activity.SystemBarStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The status bar and the navigation bar are transparent on every Android version the app runs
 * on, so the map reaches the edges of the screen and the app draws behind the bars itself.
 *
 * Before, both bars were `SystemBarStyle.dark(navy)`. In androidx.activity 1.9.3 that makes the
 * system paint the bars navy and opaque on API 26 to 34 (the platform only ignores the colours
 * from API 35), so on most phones the map still stopped at the bars. What the library hands the
 * window is read from the style itself, with the same calls EdgeToEdge makes, so the tests
 * cover the colour each API band really gets:
 *
 *  - API 26 to 28 (EdgeToEdgeApi26/28): `getScrim(isDark)` goes to setStatusBarColor and
 *    setNavigationBarColor.
 *  - API 29 to 34 (EdgeToEdgeApi29/30): `getScrimWithEnforcedContrast(isDark)` goes to the same
 *    two calls, and the navigation bar's contrast is enforced only when `nightMode` is 0.
 *  - API 35 and later: the platform ignores both colours.
 */
class AppSystemBarsTest {

    private val style: SystemBarStyle = AppSystemBars.style

    private fun call(name: String, vararg args: Any): Any {
        val types = args.map { if (it is Boolean) java.lang.Boolean.TYPE else it.javaClass }.toTypedArray()
        return SystemBarStyle::class.java.getMethod("$name\$activity_release", *types).invoke(style, *args)!!
    }

    private fun alpha(argb: Int) = argb ushr 24

    @Test fun bars_are_transparent_on_api_26_to_28() {
        // EdgeToEdgeApi26.setUp: window.statusBarColor = style.getScrim(isDark), same for the navigation bar.
        assertEquals(0, alpha(call("getScrim", true) as Int))
        assertEquals(0, alpha(call("getScrim", false) as Int))
    }

    @Test fun bars_are_transparent_on_api_29_to_34() {
        // EdgeToEdgeApi29.setUp: the colours come from getScrimWithEnforcedContrast(isDark).
        assertEquals(0, alpha(call("getScrimWithEnforcedContrast", true) as Int))
        assertEquals(0, alpha(call("getScrimWithEnforcedContrast", false) as Int))
    }

    @Test fun no_scrim_is_a_colour_of_the_app() {
        // Fully transparent means no colour at all, not a navy with alpha 0 that a later change could make visible.
        assertEquals(0, call("getScrim", true) as Int)
        assertEquals(0, call("getScrimWithEnforcedContrast", true) as Int)
        assertEquals(0, AppSystemBars.SCRIM_ARGB)
    }

    @Test fun the_system_adds_no_contrast_scrim_of_its_own() {
        // EdgeToEdgeApi29 enforces navigation bar contrast when nightMode is MODE_NIGHT_AUTO (0), and
        // the system then lays its own translucent scrim over a three-button navigation bar.
        assertNotEquals(0, call("getNightMode") as Int)
    }

    @Test fun both_bars_keep_light_icons() {
        // The icons are light when the style says the bar is dark; the map can be bright, and
        // SystemBarScrims puts a navy fade behind them. The style is dark whatever the theme.
        @Suppress("UNCHECKED_CAST")
        val detectDark = call("getDetectDarkMode") as (android.content.res.Resources) -> Boolean
        @Suppress("DEPRECATION")
        val anyResources = android.content.res.Resources(null, null, null)
        assertTrue(detectDark(anyResources))
    }
}
