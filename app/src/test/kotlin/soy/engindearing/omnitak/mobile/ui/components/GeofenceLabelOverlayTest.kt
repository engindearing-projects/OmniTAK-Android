package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.Drawing
import soy.engindearing.omnitak.mobile.data.DrawingKind
import soy.engindearing.omnitak.mobile.data.DrawingLabel
import soy.engindearing.omnitak.mobile.data.geofence.Geofence
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceColor
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceDrawings
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceType

/** #155 - which names the 2D map writes, and where on the screen: the rules behind the overlay that draws them. */
class GeofenceLabelOverlayTest {

    private fun shape(id: String, label: DrawingLabel?, color: String = "#FF3B30") = Drawing(
        id = id, kind = DrawingKind.POLYGON, points = listOf(0.0 to 0.0, 0.0 to 1.0, 1.0 to 1.0), colorHex = color, label = label,
    )

    @Test fun only_the_shapes_that_carry_a_name_have_one_written() {
        val named = shape("a", DrawingLabel("North gate", 47.0, -117.0), "#34C759")
        val plain = shape("b", null)
        val labels = GeofenceLabels.of(listOf(named, plain))
        assertEquals(listOf(GeofenceLabel("North gate", 47.0, -117.0, "#34C759")), labels)
    }

    @Test fun an_empty_name_or_a_name_with_no_place_is_not_written() {
        val labels = GeofenceLabels.of(
            listOf(
                shape("a", DrawingLabel("", 1.0, 1.0)),
                shape("b", DrawingLabel("   ", 1.0, 1.0)),
                shape("c", DrawingLabel("Nowhere", Double.NaN, 1.0)),
                shape("d", DrawingLabel("Somewhere", 1.0, Double.POSITIVE_INFINITY)),
            ),
        )
        assertTrue(labels.isEmpty())
    }

    @Test fun the_geofence_mapping_hands_the_overlay_one_name_per_geofence_in_the_geofences_colour() {
        val fence = Geofence(
            id = "g", name = "Yard", type = GeofenceType.CIRCLE, color = GeofenceColor.ORANGE,
            centerLat = 47.0, centerLon = -117.0, radiusMeters = 100.0,
        )
        val labels = GeofenceLabels.of(GeofenceDrawings.from(listOf(fence)))
        assertEquals(listOf(GeofenceLabel("Yard", 47.0, -117.0, GeofenceColor.ORANGE.hex)), labels)
    }

    @Test fun a_very_long_name_is_cut() {
        val long = "A".repeat(60)
        assertEquals(GeofenceLabels.MAX_CHARS, GeofenceLabels.shown(long).length)
        assertTrue(GeofenceLabels.shown(long).endsWith("…"))
        assertEquals("North gate", GeofenceLabels.shown("North gate"))
        val exact = "B".repeat(GeofenceLabels.MAX_CHARS)
        assertEquals(exact, GeofenceLabels.shown(exact))
    }

    @Test fun only_the_names_on_the_screen_are_drawn_with_room_for_a_wide_name_just_off_the_edge() {
        fun at(x: Float, y: Float) = ScreenLabel("n", x, y, "#FFFFFF")
        val inView = at(500f, 900f)
        val justOffTheLeft = at(-100f, 900f)
        val farOff = at(-5_000f, 900f)
        val notAPoint = at(Float.NaN, 900f)
        val kept = GeofenceLabels.onScreen(listOf(inView, justOffTheLeft, farOff, notAPoint), 1080f, 2400f, marginPx = 200f)
        assertEquals(listOf(inView, justOffTheLeft), kept)
    }

    @Test fun the_name_starts_clear_of_the_operators_marker_under_the_middle_of_the_zone() {
        // The operator's own marker is about 54 px (20 dp) tall and sits on the middle of the zone it is in.
        assertTrue(GeofenceLabels.BELOW_DP > 54f / 2f / 2.625f)
    }
}
