package soy.engindearing.omnitak.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drawing, Measure and Range rings all read the operator's taps on the map. With two of them on, the
 * first in line took the taps (Measure before Drawing) and the other banner sat there doing nothing,
 * stacked under the first. Starting a tool now ends the others.
 */
class MapToolRulesTest {

    private val all = MapTool.values().toSet()

    // Every subset of the three tools, as "what is on now".
    private val everyCombination: List<Set<MapTool>> = (0 until 8).map { bits ->
        MapTool.values().filterIndexed { i, _ -> bits and (1 shl i) != 0 }.toSet()
    }

    @Test fun starting_a_tool_ends_every_other_tool_that_is_on() {
        for (starting in MapTool.values()) {
            for (active in everyCombination) {
                assertEquals(
                    "starting $starting with $active on",
                    active - starting,
                    MapToolRules.toEnd(starting, active),
                )
            }
        }
    }

    @Test fun the_tool_being_started_is_never_ended_by_itself() {
        for (starting in MapTool.values()) {
            for (active in everyCombination) {
                assertTrue(starting !in MapToolRules.toEnd(starting, active))
            }
        }
    }

    @Test fun after_starting_one_tool_it_is_the_only_one_on() {
        for (starting in MapTool.values()) {
            for (active in everyCombination) {
                val after = (active - MapToolRules.toEnd(starting, active)) + starting
                assertEquals("starting $starting with $active on", setOf(starting), after)
            }
        }
    }

    @Test fun with_nothing_on_nothing_is_ended() {
        for (starting in MapTool.values()) {
            assertEquals(emptySet<MapTool>(), MapToolRules.toEnd(starting, emptySet()))
        }
    }

    @Test fun measure_over_a_drawing_in_progress_ends_the_drawing() {
        // The case the brief singles out: points are placed, then Measure starts. The drawing ends the
        // way its own Cancel ends it: the kind is cleared, the placed points are dropped, nothing is saved.
        assertEquals(setOf(MapTool.DRAWING), MapToolRules.toEnd(MapTool.MEASURE, setOf(MapTool.DRAWING)))
        assertEquals(setOf(MapTool.DRAWING), MapToolRules.toEnd(MapTool.RANGE_RINGS, setOf(MapTool.DRAWING)))
    }

    @Test fun all_three_at_once_leaves_only_the_one_started() {
        assertEquals(setOf(MapTool.DRAWING, MapTool.RANGE_RINGS), MapToolRules.toEnd(MapTool.MEASURE, all))
    }

    // MARK: every place a tool starts goes through the rule

    private val source: String by lazy {
        listOf(
            "src/main/kotlin/soy/engindearing/omnitak/mobile/ui/screens/MapScreen.kt",
            "app/src/main/kotlin/soy/engindearing/omnitak/mobile/ui/screens/MapScreen.kt",
        ).map(::File).first { it.exists() }.readText()
    }

    private fun count(text: String) = Regex(Regex.escape(text)).findAll(source).count()

    @Test fun every_place_that_turns_measure_on_ends_the_others_first() {
        // The Tools dial and the radial menu's Measure.
        assertTrue(count("measurementActive = true") >= 2)
        assertEquals(count("measurementActive = true"), count("startTool(MapTool.MEASURE)"))
    }

    @Test fun every_place_that_turns_range_rings_on_ends_the_others_first() {
        assertTrue(count("rangeRingsActive = true") >= 1)
        assertEquals(count("rangeRingsActive = true"), count("startTool(MapTool.RANGE_RINGS)"))
    }

    @Test fun every_place_that_starts_a_drawing_ends_the_others_first() {
        assertTrue(count("drawingKind = kind") >= 1)
        assertEquals(count("drawingKind = kind"), count("startTool(MapTool.DRAWING)"))
    }
}
