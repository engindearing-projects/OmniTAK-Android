package soy.engindearing.omnitak.mobile.ui.components

import kotlin.math.ceil
import kotlin.math.max

/**
 * Where MapLibre draws its own compass and attribution button, and how the map's own controls
 * make room for the floating toolbar and for a tool banner, as plain arithmetic.
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

    /** Air between the attribution button and whatever is under it. */
    const val ATTRIBUTION_GAP_DP = 8

    private fun px(dp: Int, density: Float): Int = (dp * density + 0.5f).toInt()

    private fun px(dp: Float, density: Float): Int = (dp * density + 0.5f).toInt()

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

    // MARK: the top left: the compass and the tool banners

    /**
     * The app's compass and the Drawing, Measure and Range rings banners start this far below the
     * safe area's top edge: under the status strip, with air. A banner takes the top of the
     * corner and the compass sits under it, so one is never drawn on the other.
     */
    const val TOP_CHROME_DP = 72

    /** Air between a tool banner and the compass under it. */
    const val COMPASS_BELOW_BANNER_GAP_DP = 8

    /** How far the compass sits below the top of its corner: nothing, unless a banner is above it. */
    fun compassTopGapDp(bannerShowing: Boolean): Int = if (bannerShowing) COMPASS_BELOW_BANNER_GAP_DP else 0

    // MARK: the bottom: where the controls rest

    /** The zoom and centre buttons are 48 dp across. */
    const val ZOOM_BUTTON_DP = 48

    /** Portrait: the column of zoom and centre buttons rests this far above the safe area's bottom edge. */
    const val ZOOM_STACK_BOTTOM_DP = 110

    /** Landscape: the row of zoom and centre buttons rests this far above the bottom edge (12 dp up). */
    const val ZOOM_ROW_BOTTOM_DP = 12

    /** The row of zoom and centre buttons along the bottom edge in landscape: 12 dp up, 48 dp tall. */
    const val LANDSCAPE_CONTROL_ROW_DP = ZOOM_ROW_BOTTOM_DP + ZOOM_BUTTON_DP

    /** The position card rests this far above the bottom edge: clear of the tools button under it. */
    const val POSITION_CARD_BOTTOM_DP = 96

    /**
     * The tools button's own distance from the bottom edge (the padding [ToolsDrawer] gives its main
     * button), and so the lowest edge of the bottom-right corner: the position card is above it.
     */
    fun toolsButtonBottomDp(landscape: Boolean): Int = if (landscape) 12 else 16

    /** Where the zoom controls rest: the lowest edge of the bottom-left corner. */
    fun zoomControlsBottomDp(landscape: Boolean): Int = if (landscape) ZOOM_ROW_BOTTOM_DP else ZOOM_STACK_BOTTOM_DP

    /** Portrait: the attribution button rests this far above the bottom edge, in the corner, to the right of the zoom column. */
    const val ATTRIBUTION_BOTTOM_DP = 12

    // MARK: the floating toolbar

    /** Air between the top of the floating toolbar (or of its tip) and a control lifted above it. */
    const val TOOLBAR_GAP_DP = 8

    /**
     * How many dp the floating toolbar reaches above the safe area's bottom edge, whole dp, rounded
     * up. [toolbarHeightPx] is its measured height, which includes the padding it gives the
     * navigation bar and its first-run tip when that is showing (so a larger font makes it taller),
     * and [safeBottomPx] is the safe area's bottom inset: that part of the toolbar is under the
     * edge the controls are laid out from. 0 when there is no toolbar.
     */
    fun toolbarOccupiedDp(toolbarHeightPx: Int, safeBottomPx: Int, density: Float): Int {
        if (toolbarHeightPx <= 0 || density <= 0f) return 0
        return ceil((toolbarHeightPx - safeBottomPx).coerceAtLeast(0) / density).toInt()
    }

    /**
     * How many dp above the safe area's bottom edge must be kept free for the toolbar: what it
     * occupies and a gap, or nothing while it is not showing. Animated from 0 as the toolbar comes
     * and goes, so a control moves smoothly instead of jumping.
     */
    fun clearanceDp(toolbarOccupiedDp: Float): Float =
        if (toolbarOccupiedDp <= 0f) 0f else toolbarOccupiedDp + TOOLBAR_GAP_DP

    /**
     * How far a control whose lowest edge rests [restingBottomDp] above the bottom edge is lifted to
     * clear [clearanceDp]: nothing if it already is clear, and exactly as far as it must be
     * otherwise. A corner (the tools button with the position card above it; the zoom buttons
     * with the scale bar above them) lifts as one, by the lift of its lowest edge.
     */
    fun liftDp(restingBottomDp: Float, clearanceDp: Float): Float = (clearanceDp - restingBottomDp).coerceAtLeast(0f)

    /**
     * The attribution button sits at the bottom left, lifted over the navigation bar, and over the
     * floating toolbar when it shows ([clearanceDp], see [clearanceDp]) so nothing is drawn on it.
     */
    fun attributionMarginsPx(
        safe: EdgePx,
        density: Float,
        landscape: Boolean = false,
        clearanceDp: Float = 0f,
    ): EdgePx = EdgePx(
        left = safe.left + px(ATTRIBUTION_LEFT_DP, density),
        top = px(SIDE_MARGIN_DP, density),
        right = px(SIDE_MARGIN_DP, density),
        bottom = safe.bottom + px(attributionBottomDp(landscape, clearanceDp), density),
    )

    /**
     * dp above the safe area's bottom edge the attribution button sits. Portrait: in the corner,
     * to the right of the zoom column, and above the toolbar when that shows. Landscape: the
     * zoom buttons are a row along the bottom edge and the button is inside its span, so it is
     * above the row, wherever the toolbar has lifted the row to.
     */
    fun attributionBottomDp(landscape: Boolean, clearanceDp: Float): Float =
        if (landscape) {
            LANDSCAPE_CONTROL_ROW_DP + ATTRIBUTION_GAP_DP + liftDp(ZOOM_ROW_BOTTOM_DP.toFloat(), clearanceDp)
        } else {
            max(ATTRIBUTION_BOTTOM_DP.toFloat(), clearanceDp)
        }
}
