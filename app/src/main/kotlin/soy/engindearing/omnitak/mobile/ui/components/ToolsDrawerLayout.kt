package soy.engindearing.omnitak.mobile.ui.components

/**
 * Pure layout logic for the map [ToolsDrawer], split out so it can be
 * unit-tested without a Compose harness (UI layout itself isn't unit
 * testable; this captures the orientation/size decisions that are).
 *
 * #182 — on a plate carrier (Pixel 9/10 Pro) landscape is the most likely
 * orientation, and the vertical tool rail used to grow tall enough to eat
 * most of the (short) landscape map. In landscape we keep the drawer
 * collapsed behind its FAB and, when expanded, flow the tools into a
 * horizontal rail (multiple short rows) instead of one tall column — so
 * the map keeps its vertical space and every tool stays one tap away.
 */
object ToolsDrawerLayout {

    /** Compact pip + FAB sizing for landscape; the roomier portrait sizing otherwise. */
    const val FAB_SIZE_PORTRAIT_DP = 56
    const val FAB_SIZE_LANDSCAPE_DP = 48
    const val TOOL_SIZE_PORTRAIT_DP = 48
    const val TOOL_SIZE_LANDSCAPE_DP = 40
    const val SPACING_PORTRAIT_DP = 12
    const val SPACING_LANDSCAPE_DP = 8

    /**
     * True when the expanded tool stack should flow horizontally (a rail
     * that grows sideways, wrapping into extra rows) rather than stacking
     * straight up. Landscape only — portrait keeps the familiar vertical
     * stack that's never been a height problem.
     */
    fun useHorizontalRail(isLandscape: Boolean): Boolean = isLandscape

    fun fabSizeDp(isLandscape: Boolean): Int =
        if (isLandscape) FAB_SIZE_LANDSCAPE_DP else FAB_SIZE_PORTRAIT_DP

    fun toolSizeDp(isLandscape: Boolean): Int =
        if (isLandscape) TOOL_SIZE_LANDSCAPE_DP else TOOL_SIZE_PORTRAIT_DP

    fun spacingDp(isLandscape: Boolean): Int =
        if (isLandscape) SPACING_LANDSCAPE_DP else SPACING_PORTRAIT_DP

    /**
     * Max tools per row of the horizontal landscape rail. Caps the rail's
     * width to a fraction of the screen so it can't span edge-to-edge and
     * re-eat the map; tools beyond the cap wrap onto the next (upward) row.
     *
     * [availableWidthDp] is the usable width the rail may occupy (already
     * net of side padding); [toolSizeDp]/[spacingDp] are the pip metrics.
     * Always returns at least 1 so a tool is never orphaned off-screen.
     */
    fun toolsPerRow(availableWidthDp: Int, toolSizeDp: Int, spacingDp: Int): Int {
        if (availableWidthDp <= 0 || toolSizeDp <= 0) return 1
        val per = (availableWidthDp + spacingDp) / (toolSizeDp + spacingDp)
        return per.coerceAtLeast(1)
    }

    // MARK: labels
    //
    // Each expanded tool shows its label: to the left of its icon in the portrait dial (the
    // Material "speed dial"), under its icon in the landscape rail when they fit. The rules
    // below decide the sizes and whether they fit; the Compose code only draws them.

    /** Air between a label chip and its icon. */
    const val LABEL_GAP_DP = 8

    /** The status strip and a little air at the top of the safe area: the dial never reaches into it. */
    const val TOP_CLEARANCE_DP = MapOrnamentLayout.STATUS_STRIP_DP + 8

    const val PORTRAIT_LABEL_SP = 14
    const val LANDSCAPE_LABEL_SP = 11
    const val CHIP_PADDING_H_DP = 10
    const val CHIP_PADDING_V_DP = 5

    /**
     * The label's line height as a multiple of its size. The chip pins it: left to the Material
     * default (24 sp) a one-line chip was 10 dp taller than it needs to be at the standard font
     * size and the rows were taller than [landscapeLabelHeightDp] counts.
     */
    const val LABEL_LINE_HEIGHT_EM = 1.2f

    /** A labelled landscape rail stays a compact band: two rows at most (#182 keeps the map its height). */
    const val MAX_LABELLED_RAIL_ROWS = 2

    /** Average width of a character in a sans-serif face, as a fraction of its size. */
    private const val CHAR_WIDTH_EM = 0.56f

    /**
     * How far in from the right edge the icons sit, so they line up over the main button: the
     * button is a little bigger than an icon and the column is aligned to the right.
     */
    fun dialEndInsetDp(isLandscape: Boolean): Int = (fabSizeDp(isLandscape) - toolSizeDp(isLandscape)) / 2

    /** Height of the portrait dial's rows, with the gaps between them. */
    fun dialHeightDp(toolCount: Int, toolSizeDp: Int, spacingDp: Int): Int =
        if (toolCount <= 0) 0 else toolCount * toolSizeDp + (toolCount - 1) * spacingDp

    /**
     * The tallest the portrait dial may be: what the safe area leaves under the status strip,
     * less the main button and the gap above it. Never negative.
     */
    fun dialMaxHeightDp(availableHeightDp: Int, fabSizeDp: Int, spacingDp: Int): Int =
        (availableHeightDp - TOP_CLEARANCE_DP - fabSizeDp - spacingDp).coerceAtLeast(0)

    /** True when the rows are taller than the dial may be, so the dial scrolls and nothing is pushed off the top. */
    fun dialScrolls(toolCount: Int, toolSizeDp: Int, spacingDp: Int, availableHeightDp: Int, fabSizeDp: Int): Boolean =
        dialHeightDp(toolCount, toolSizeDp, spacingDp) > dialMaxHeightDp(availableHeightDp, fabSizeDp, spacingDp)

    /** An estimate of the width in dp of [chars] characters at [textSp], at the phone's [fontScale]. */
    fun labelWidthDp(chars: Int, textSp: Int, fontScale: Float): Int =
        kotlin.math.ceil(chars.coerceAtLeast(0) * textSp * fontScale * CHAR_WIDTH_EM).toInt()

    /** Width of one landscape cell: its icon, or its longest label if that is wider. */
    fun landscapeCellWidthDp(toolSizeDp: Int, longestLabelChars: Int, fontScale: Float): Int =
        maxOf(toolSizeDp, labelWidthDp(longestLabelChars, LANDSCAPE_LABEL_SP, fontScale) + 2 * CHIP_PADDING_H_DP)

    /** Height of a one-line landscape label chip at the phone's [fontScale]. */
    fun landscapeLabelHeightDp(fontScale: Float): Int =
        kotlin.math.ceil(LANDSCAPE_LABEL_SP * fontScale * LABEL_LINE_HEIGHT_EM).toInt() + 2 * CHIP_PADDING_V_DP

    /**
     * Whether the landscape rail can carry a label under each icon: with the wider cells fewer
     * fit in a row, so there are more rows. The rail stays a compact band, so it takes labels only
     * while it is at most [MAX_LABELLED_RAIL_ROWS] rows, and the rows plus the main button must
     * fit the height the safe area leaves under the status strip. If not, it keeps icons only.
     *
     * [railWidthDp] and [availableHeightDp] are as in [toolsPerRow] and [dialMaxHeightDp].
     */
    fun landscapeShowsLabels(
        toolCount: Int,
        toolSizeDp: Int,
        spacingDp: Int,
        fabSizeDp: Int,
        railWidthDp: Int,
        availableHeightDp: Int,
        longestLabelChars: Int,
        fontScale: Float,
    ): Boolean {
        if (toolCount <= 0) return true
        val cell = landscapeCellWidthDp(toolSizeDp, longestLabelChars, fontScale)
        val perRow = toolsPerRow(railWidthDp, cell, spacingDp)
        val rows = (toolCount + perRow - 1) / perRow
        if (rows > MAX_LABELLED_RAIL_ROWS) return false
        val rowHeight = toolSizeDp + LABEL_GAP_DP / 2 + landscapeLabelHeightDp(fontScale)
        val needed = rows * rowHeight + (rows - 1) * spacingDp + spacingDp + fabSizeDp
        return needed <= availableHeightDp - TOP_CLEARANCE_DP
    }
}
