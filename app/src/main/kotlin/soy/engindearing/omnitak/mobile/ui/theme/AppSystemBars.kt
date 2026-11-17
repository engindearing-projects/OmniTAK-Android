package soy.engindearing.omnitak.mobile.ui.theme

import androidx.activity.SystemBarStyle

/**
 * How the status bar and the navigation bar are drawn: fully transparent, with light icons, on
 * every Android version the app supports. The app draws behind them itself. The map fills the
 * whole window (its own fade, SystemBarScrims, keeps the icons readable over a bright map) and
 * every other screen sits on the navy background inside its own insets.
 *
 * It is `SystemBarStyle.dark` with a transparent scrim, not `dark(navy)` and not `auto`:
 *
 *  - The scrim is the colour the library gives the window for each bar, through
 *    `setStatusBarColor` / `setNavigationBarColor` on API 26 to 34 (EdgeToEdgeApi26 and 28 use
 *    `getScrim`, EdgeToEdgeApi29 and 30 use `getScrimWithEnforcedContrast`, which is the scrim
 *    again for every style but `auto`). With the navy scrim the system painted opaque navy bars
 *    over the edge strips of the map. From API 35 the platform ignores these colours, which is
 *    why only a phone on 34 or older showed it.
 *  - `auto` would be transparent on API 29 and later but sets nightMode to MODE_NIGHT_AUTO, and
 *    EdgeToEdgeApi29 then asks the system to enforce contrast on the navigation bar: it lays a
 *    translucent scrim of its own over a three-button bar. `dark` leaves that off, on every
 *    version, so the only fade behind the bars is the app's own.
 *  - `dark` also says the bars are dark, so their icons are light.
 */
internal object AppSystemBars {

    /** Fully transparent: no colour at all, so nothing the system draws can show through as a band. */
    const val SCRIM_ARGB: Int = android.graphics.Color.TRANSPARENT

    /** Used for both bars. */
    val style: SystemBarStyle = SystemBarStyle.dark(SCRIM_ARGB)
}
