package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.ui.geometry.Offset

/**
 * Where the radial menu's ring goes for a long press.
 *
 * The ring is a circle of items round a centre, and the centre cancel button sits on that centre. In
 * the open the centre is the press itself. Near an edge the ring would be half off the screen and
 * its items out of reach, so the whole ring (centre, items and cancel button together) moves inward
 * just far enough to be inside the safe area: no further than it has to, and not at all when it
 * already fits.
 */
internal object RadialPlacement {

    /** From the ring's centre to the outside of its items, px: the radius to an item's middle plus half an item. */
    fun reachPx(radiusDp: Int, itemSizeDp: Int, density: Float): Float = (radiusDp + itemSizeDp / 2f) * density

    /**
     * The ring's centre for a press at [anchor]. [reachPx] is [reachPx]; [left], [top], [right] and
     * [bottom] are the edges of the area the ring must stay inside (the window less the system
     * bars and a display cutout), in the same px as [anchor].
     *
     * On an axis where the area is smaller than the ring (a narrow split-screen window) it cannot
     * fit, so the ring is centred in the room there is.
     */
    fun centre(anchor: Offset, reachPx: Float, left: Float, top: Float, right: Float, bottom: Float): Offset =
        Offset(
            x = along(anchor.x, reachPx, left, right),
            y = along(anchor.y, reachPx, top, bottom),
        )

    private fun along(press: Float, reach: Float, low: Float, high: Float): Float {
        val first = low + reach
        val last = high - reach
        return if (first > last) (low + high) / 2f else press.coerceIn(first, last)
    }
}
