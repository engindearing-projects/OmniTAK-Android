package soy.engindearing.omnitak.mobile.ui.components

/**
 * Where MapLibre draws its own compass and attribution button, as plain arithmetic.
 *
 * The map fills the whole window, under the status bar and the navigation bar. MapLibre measures
 * an ornament's margins from the edge of the map view, so every margin here is the safe area
 * (system bars and display cutout, in px) plus the room the app's own controls take at that edge.
 *
 * Nothing here touches Android, so the numbers can be tested on the JVM.
 */
internal object MapOrnamentLayout {

    /** Four edges in px: an inset of the safe area on the way in, a margin on the way out. */
    data class EdgePx(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0)

    /** MapLibre's own default side margin for its ornaments. */
    const val SIDE_MARGIN_DP = 4

    /** Height of the status strip ([ATAKStatusBar]) at the top of the safe area: 8 + 24 + 8. */
    const val STATUS_STRIP_DP = 40

    /** Air between the status strip and the compass under it. */
    const val COMPASS_GAP_DP = 8

    /**
     * MapLibre's default left margin for the attribution button, kept: it leaves the strip at the
     * left edge to the zoom and centre buttons (16 dp in, 48 dp wide).
     */
    const val ATTRIBUTION_LEFT_DP = 92

    /**
     * How far the floating toolbar reaches up from the navigation bar: its 8 dp of padding, the
     * pill itself (86 dp at the standard font size) and a little extra for a larger font. It
     * shows for 3 s after the map tab is opened and stays when auto-hide is off.
     */
    const val FLOATING_TOOLBAR_PORTRAIT_DP = 96

    /** The same in landscape, where the pill is compact (icons only). */
    const val FLOATING_TOOLBAR_LANDSCAPE_DP = 64

    /** The row of zoom and centre buttons along the bottom edge in landscape: 12 dp up, 48 dp tall. */
    const val LANDSCAPE_CONTROL_ROW_DP = 12 + 48

    /** Air between the attribution button and whatever is under it. */
    const val ATTRIBUTION_GAP_DP = 8

    private fun px(dp: Int, density: Float): Int = (dp * density + 0.5f).toInt()

    /**
     * The built-in compass sits at the top right, under the status strip, and only appears when
     * the map is turned. [safe] is the safe area in px.
     */
    fun compassMarginsPx(safe: EdgePx, density: Float): EdgePx = EdgePx(
        left = safe.left + px(SIDE_MARGIN_DP, density),
        top = safe.top + px(STATUS_STRIP_DP + COMPASS_GAP_DP, density),
        right = safe.right + px(SIDE_MARGIN_DP, density),
        bottom = px(SIDE_MARGIN_DP, density),
    )

    /**
     * The attribution button sits at the bottom left, lifted over the navigation bar and over the
     * floating toolbar (and, in landscape, the row of map buttons), so nothing is drawn on it.
     */
    fun attributionMarginsPx(safe: EdgePx, density: Float, landscape: Boolean = false): EdgePx = EdgePx(
        left = safe.left + px(ATTRIBUTION_LEFT_DP, density),
        top = px(SIDE_MARGIN_DP, density),
        right = px(SIDE_MARGIN_DP, density),
        bottom = safe.bottom + px(attributionClearanceDp(landscape), density),
    )

    /** dp above the safe area's bottom edge that the attribution button needs to clear. */
    fun attributionClearanceDp(landscape: Boolean): Int =
        maxOf(
            if (landscape) FLOATING_TOOLBAR_LANDSCAPE_DP else FLOATING_TOOLBAR_PORTRAIT_DP,
            if (landscape) LANDSCAPE_CONTROL_ROW_DP else 0,
        ) + ATTRIBUTION_GAP_DP
}
