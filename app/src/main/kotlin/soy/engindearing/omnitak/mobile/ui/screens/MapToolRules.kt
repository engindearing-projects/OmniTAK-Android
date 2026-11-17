package soy.engindearing.omnitak.mobile.ui.screens

/**
 * The map tools that take the operator's taps on the map. Only one of them is on at a time.
 */
internal enum class MapTool { DRAWING, MEASURE, RANGE_RINGS }

internal object MapToolRules {

    /**
     * What must end when [starting] begins, given what is on now in [active]: every other tool.
     * They all read the same taps, so with two on, the first in line (Range rings, then Measure,
     * then Drawing) took them and the other banner sat under it doing nothing.
     *
     * A tool that ends does what its own banner does when the operator closes it: Measure and Range
     * rings drop their points and their centre (Done); a drawing in progress is cancelled (Cancel):
     * its kind is cleared, the points placed so far are dropped and nothing is saved.
     */
    fun toEnd(starting: MapTool, active: Set<MapTool>): Set<MapTool> = active - starting
}
