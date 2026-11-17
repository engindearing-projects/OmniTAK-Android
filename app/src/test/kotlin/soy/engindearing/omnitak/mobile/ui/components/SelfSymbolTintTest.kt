package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.TakTeamColor

/**
 * The operator's own marker is the MIL-STD-2525 friendly ground unit frame: a black outline round
 * a light blue fill, with a black label. It is tinted with the team colour. A colour filter that
 * replaces every opaque pixel with the team colour (what it did) erases the outline and the label
 * and leaves a plain rectangle; the rule here keeps them: the team colour fills the symbol and
 * its lines stay visible (dark on a light or mid team colour, light on a dark one, where dark lines
 * would vanish into the fill). The rule is plain arithmetic on ARGB ints, so it is tested on int arrays.
 */
class SelfSymbolTintTest {

    private val fill = 0xFF80E0FF.toInt()      // rgb(128,224,255): the frame's own fill
    private val black = 0xFF000000.toInt()

    private val teams: Map<String, Int> = listOf(
        "White", "Yellow", "Orange", "Magenta", "Red", "Maroon", "Purple",
        "Dark Blue", "Blue", "Cyan", "Teal", "Green", "Dark Green", "Brown",
    ).associateWith { TakTeamColor.forName(it)!! }

    private fun rgb(argb: Int) = argb and 0x00FFFFFF
    private fun alpha(argb: Int) = argb ushr 24
    private fun luma(argb: Int) =
        0.299 * ((argb shr 16) and 0xFF) + 0.587 * ((argb shr 8) and 0xFF) + 0.114 * (argb and 0xFF)

    private fun tint1(pixel: Int, team: Int) = SelfSymbolTint.tint(intArrayOf(pixel), team)[0]

    // MARK: the rule

    @Test fun the_frames_fill_becomes_the_full_team_colour_for_every_team() {
        for ((name, team) in teams) {
            assertEquals(name, rgb(team), rgb(tint1(fill, team)))
            assertEquals(name, 255, alpha(tint1(fill, team)))
        }
    }

    private val darkTeams = listOf("Dark Blue", "Blue", "Maroon", "Purple", "Dark Green", "Brown")
    private val otherTeams = listOf("White", "Yellow", "Orange", "Magenta", "Red", "Cyan", "Teal", "Green")

    @Test fun the_dark_lines_stay_dark_on_a_light_or_mid_team_colour() {
        for (name in otherTeams) {
            assertEquals(name, 0, rgb(tint1(black, teams.getValue(name))))
            assertEquals(name, 255, alpha(tint1(black, teams.getValue(name))))
        }
    }

    @Test fun the_lines_turn_light_on_a_dark_team_colour_where_dark_ones_would_vanish() {
        for (name in darkTeams) {
            assertEquals(name, 0xFFFFFF, rgb(tint1(black, teams.getValue(name))))
            assertEquals(name, 255, alpha(tint1(black, teams.getValue(name))))
        }
    }

    /** WCAG relative luminance of an opaque colour. */
    private fun relative(argb: Int): Double {
        fun lin(c: Int): Double = (c / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * lin((argb shr 16) and 0xFF) + 0.7152 * lin((argb shr 8) and 0xFF) + 0.0722 * lin(argb and 0xFF)
    }

    private fun contrast(a: Int, b: Int): Double {
        val x = relative(a)
        val y = relative(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    @Test fun the_outline_is_visible_against_the_fill_for_every_team_colour() {
        // The WCAG ratio for graphics is 3:1. A black outline on Dark Blue is 1.4:1: the reason the lines turn light there.
        for ((name, team) in teams) {
            val ratio = contrast(tint1(black, team), tint1(fill, team))
            assertTrue("$name: $ratio", ratio >= 3.0)
        }
    }

    @Test fun the_label_is_as_readable_as_the_outline_because_it_is_drawn_the_same_way() {
        // Same source colour (black), same rule.
        for ((_, team) in teams) assertEquals(tint1(black, team), tint1(0xFF000000.toInt(), team))
    }

    @Test fun a_pixel_between_the_outline_and_the_fill_gets_a_shade_between() {
        // Half the fill's brightness: half the team colour, so the edge is smooth.
        val half = 0xFF407080.toInt()                        // (64, 112, 128)
        val cyan = 0xFF00FFFF.toInt()
        val got = tint1(half, cyan)
        assertEquals(0, (got shr 16) and 0xFF)
        assertEquals(128.0, ((got shr 8) and 0xFF).toDouble(), 2.0)
        assertEquals(128.0, (got and 0xFF).toDouble(), 2.0)
    }

    @Test fun on_a_dark_team_colour_the_edge_shade_runs_from_white_to_the_colour() {
        val half = 0xFF407080.toInt()                        // half the fill's brightness
        val darkBlue = 0xFF00008B.toInt()
        val got = tint1(half, darkBlue)
        assertEquals(127.5, ((got shr 16) and 0xFF).toDouble(), 2.0)     // halfway from 255 to 0
        assertEquals(127.5, ((got shr 8) and 0xFF).toDouble(), 2.0)
        assertEquals(197.0, (got and 0xFF).toDouble(), 2.0)              // halfway from 255 to 139
    }

    @Test fun alpha_is_untouched() {
        for (a in listOf(1, 64, 128, 200, 255)) {
            assertEquals(a, alpha(tint1((a shl 24) or 0x80E0FF, 0xFFFF0000.toInt())))
            assertEquals(a, alpha(tint1((a shl 24) or 0x000000, 0xFFFF0000.toInt())))
        }
    }

    @Test fun a_transparent_pixel_stays_transparent() {
        assertEquals(0, alpha(tint1(0x00000000, 0xFFFF0000.toInt())))
        assertEquals(0, alpha(tint1(0x0080E0FF, 0xFFFF0000.toInt())))
    }

    @Test fun nothing_brighter_than_the_fill_goes_past_the_team_colour() {
        val white = 0xFFFFFFFF.toInt()
        for ((name, team) in teams) assertEquals(name, rgb(team), rgb(tint1(white, team)))
    }

    @Test fun the_frame_is_not_a_flat_rectangle_any_more() {
        // A small frame: a black outline round the light blue fill, and one black pixel of label inside.
        val w = 8
        val h = 6
        val frame = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            when {
                x == 0 || y == 0 || x == w - 1 || y == h - 1 -> black
                x == 3 && y == 2 -> black
                else -> fill
            }
        }
        for ((name, team) in teams) {
            val out = SelfSymbolTint.tint(frame, team)
            assertEquals("$name: the lines and the fill are two colours, not one", 2, out.map { rgb(it) }.toSet().size)
            assertEquals("$name outline", rgb(tint1(black, team)), rgb(out[0]))
            assertEquals("$name label", rgb(tint1(black, team)), rgb(out[2 * w + 3]))
            assertEquals("$name fill", rgb(team), rgb(out[2 * w + 5]))
        }
    }

    @Test fun the_old_rule_made_the_frame_one_flat_colour() {
        // The SRC_IN filter: every opaque pixel is the team colour. Documents what the rule replaces.
        val frame = intArrayOf(black, fill, fill, black)
        val team = 0xFF00FFFF.toInt()
        val old = frame.map { (alpha(it) shl 24) or rgb(team) }
        assertEquals(1, old.toSet().size)
        assertTrue(SelfSymbolTint.tint(frame, team).toSet().size > 1)
    }

    // MARK: which team colours get light lines

    @Test fun the_dark_team_colours_get_light_lines_and_the_others_do_not() {
        for (name in darkTeams) assertTrue(name, SelfSymbolTint.usesLightLines(teams.getValue(name)))
        for (name in otherTeams) assertFalse(name, SelfSymbolTint.usesLightLines(teams.getValue(name)))
    }
}
