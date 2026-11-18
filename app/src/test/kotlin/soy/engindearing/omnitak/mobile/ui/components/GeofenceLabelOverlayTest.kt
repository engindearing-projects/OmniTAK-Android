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
        assertEquals(listOf(GeofenceLabel("North gate", 47.0, -117.0, "#34C759", outline = named.points)), labels)
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
        val label = GeofenceLabels.of(GeofenceDrawings.from(listOf(fence))).single()
        assertEquals("Yard", label.text)
        assertEquals(GeofenceColor.ORANGE.hex, label.colorHex)
        // On the north edge of the circle, as the iOS app writes it.
        assertEquals(47.0 + Math.toDegrees(100.0 / 6_371_008.8), label.lat, 1e-9)
        assertEquals(-117.0, label.lon, 1e-9)
    }

    @Test fun a_very_long_name_is_cut() {
        val long = "A".repeat(60)
        assertEquals(GeofenceLabels.MAX_CHARS, GeofenceLabels.shown(long).length)
        assertTrue(GeofenceLabels.shown(long).endsWith("…"))
        assertEquals("North gate", GeofenceLabels.shown("North gate"))
        val exact = "B".repeat(GeofenceLabels.MAX_CHARS)
        assertEquals(exact, GeofenceLabels.shown(exact))
    }

    @Test fun a_name_with_no_zone_known_is_kept_or_dropped_by_its_point_with_room_for_a_wide_name_just_off_the_edge() {
        fun at(x: Float, y: Float) = ScreenLabel("n", x, y, "#FFFFFF")
        val inView = at(500f, 900f)
        val justOffTheLeft = at(-100f, 900f)
        val farOff = at(-5_000f, 900f)
        val notAPoint = at(Float.NaN, 900f)
        val kept = GeofenceLabels.placed(listOf(inView, justOffTheLeft, farOff, notAPoint), 1080f, 2400f, marginPx = 200f)
        assertEquals(listOf(inView, justOffTheLeft), kept)
    }

    // ---- a name stays with its zone for as long as any of the zone is on the screen

    // A 1080 x 2400 view whose top 300 px are the status bar and the strip; names are 40 px tall, 5 px over their point.
    private val view = Triple(1080f, 2400f, 200f)
    private val lowest = 300f + 5f + 40f

    private fun placed(vararg labels: ScreenLabel) =
        GeofenceLabels.placed(labels.toList(), view.first, view.second, view.third, topPx = 300f, nameHeightPx = 40f, gapPx = 5f)

    /** [placed] with a bar [bottomPx] tall along the bottom of the window (the gesture bar or the navigation buttons). */
    private fun placedAbove(bottomPx: Float, vararg labels: ScreenLabel) =
        GeofenceLabels.placed(labels.toList(), view.first, view.second, view.third, topPx = 300f, nameHeightPx = 40f, gapPx = 5f, bottomPx = bottomPx)

    private fun named(x: Float, y: Float, left: Float, top: Float, right: Float, bottom: Float, halfWidth: Float = Float.NaN) =
        ScreenLabel("n", x, y, "#FFFFFF", ScreenBox(left, top, right, bottom), halfWidth)

    @Test fun a_name_whose_north_edge_is_above_the_screen_stays_at_the_top_while_the_zone_is_on_it() {
        val kept = placed(named(x = 540f, y = -800f, left = 100f, top = -800f, right = 980f, bottom = 1500f)).single()
        assertEquals("right under the strip, with the whole name below it", lowest, kept.y, 0f)
        assertEquals("over the same part of the zone", 540f, kept.x, 0f)
    }

    @Test fun a_name_just_under_the_strip_is_moved_down_so_none_of_it_is_under_the_strip() {
        val kept = placed(named(x = 540f, y = 310f, left = 100f, top = 310f, right = 980f, bottom = 1500f)).single()
        assertEquals(lowest, kept.y, 0f)
    }

    @Test fun a_name_that_is_clear_of_the_strip_stays_where_it_is() {
        val label = named(x = 540f, y = 800f, left = 100f, top = 800f, right = 980f, bottom = 1500f)
        assertEquals(listOf(label), placed(label))
    }

    @Test fun no_name_when_none_of_the_zone_is_where_a_name_can_be() {
        val above = named(x = 540f, y = -900f, left = 100f, top = -900f, right = 980f, bottom = 200f)
        val toTheLeft = named(x = -900f, y = 800f, left = -1500f, top = 600f, right = -50f, bottom = 1500f)
        val toTheRight = named(x = 1900f, y = 800f, left = 1200f, top = 600f, right = 2600f, bottom = 1500f)
        val below = named(x = 540f, y = 2600f, left = 100f, top = 2500f, right = 980f, bottom = 3500f)
        assertTrue(placed(above, toTheLeft, toTheRight, below).isEmpty())
    }

    @Test fun a_zone_that_only_reaches_a_little_into_the_view_still_has_its_name() {
        val kept = placed(named(x = 540f, y = -800f, left = 100f, top = -800f, right = 980f, bottom = 320f)).single()
        assertEquals(lowest, kept.y, 0f)
    }

    @Test fun a_name_whose_point_is_off_to_the_side_goes_to_the_middle_of_the_part_of_its_zone_on_the_screen() {
        val kept = placed(named(x = -1500f, y = 900f, left = -2000f, top = 500f, right = 400f, bottom = 1300f)).single()
        assertEquals("the zone is on the screen from 0 to 400", 200f, kept.x, 0f)
        assertEquals(900f, kept.y, 0f)
        val right = placed(named(x = 2500f, y = 900f, left = 800f, top = 500f, right = 3000f, bottom = 1300f)).single()
        assertEquals("from 800 to the right edge, 1080", 940f, right.x, 0f)
    }

    @Test fun a_polygons_name_whose_middle_is_below_the_screen_stays_at_the_bottom_edge_when_nothing_covers_it() {
        val kept = placed(named(x = 540f, y = 3000f, left = 100f, top = 1000f, right = 980f, bottom = 3500f)).single()
        assertEquals(2400f, kept.y, 0f)
    }

    @Test fun a_polygons_name_whose_middle_is_below_the_screen_stays_above_the_bar_along_the_bottom() {
        // The map is drawn under the gesture bar or the navigation buttons and a fade covers them (SystemBarScrims): a name kept at the
        // edge of the window was written on that fade. It is kept above the bar: 2400 - 126 (48 dp at 2.625x, three-button navigation).
        val kept = placedAbove(126f, named(x = 540f, y = 3000f, left = 100f, top = 1000f, right = 980f, bottom = 3500f)).single()
        assertEquals(2400f - 126f, kept.y, 0f)
        assertEquals("over the same part of the zone", 540f, kept.x, 0f)
    }

    @Test fun a_name_that_is_clear_of_the_bar_along_the_bottom_stays_where_it_is() {
        val label = named(x = 540f, y = 2200f, left = 100f, top = 1500f, right = 980f, bottom = 3000f)
        assertEquals(listOf(label), placedAbove(126f, label))
    }

    @Test fun a_name_just_over_the_edge_of_the_bar_is_moved_up_to_the_top_of_it() {
        val kept = placedAbove(126f, named(x = 540f, y = 2300f, left = 100f, top = 1500f, right = 980f, bottom = 3000f)).single()
        assertEquals(2400f - 126f, kept.y, 0f)
    }

    @Test fun a_bar_taller_than_the_room_under_the_strip_does_not_push_a_name_up_into_the_strip() {
        // 300 px of strip, 200 px of window, a 126 px bar: no room at all. The name stays at the first y that clears the strip.
        val kept = GeofenceLabels.placed(
            listOf(named(500f, 900f, 0f, 250f, 900f, 1500f)), 1000f, 600f, 100f, topPx = 300f, nameHeightPx = 40f, gapPx = 5f, bottomPx = 400f,
        ).singleOrNull()
        assertTrue(kept == null || kept.y >= 300f + 5f + 40f)
    }

    @Test fun a_bottom_bar_that_is_not_a_size_is_no_bar() {
        val label = named(x = 540f, y = 3000f, left = 100f, top = 1000f, right = 980f, bottom = 3500f)
        for (bad in listOf(Float.NaN, -50f, Float.NEGATIVE_INFINITY)) {
            assertEquals("a bar of $bad", 2400f, placedAbove(bad, label).single().y, 0f)
        }
    }

    // ---- a name is wide: it reaches in from past the side by half its own width, no more

    @Test fun a_name_whose_text_does_not_reach_the_screen_is_moved_onto_the_part_of_its_zone_that_is_on_it() {
        // "North gate" is about 180 px wide and centred on its point: 90 px to each side. The point is 150 px off the left edge, so all of the
        // text is off the screen, while the zone is on it from 0 to 300 (a 5 km circle whose north point is just off the left edge).
        val kept = placed(named(x = -150f, y = 900f, left = -800f, top = 500f, right = 300f, bottom = 1300f, halfWidth = 90f)).single()
        assertEquals("the middle of the zone on the screen, 0 to 300", 150f, kept.x, 0f)
        assertEquals(900f, kept.y, 0f)
    }

    @Test fun a_wide_name_whose_text_reaches_into_the_screen_stays_where_it_is() {
        val kept = placed(named(x = -150f, y = 900f, left = -800f, top = 500f, right = 300f, bottom = 1300f, halfWidth = 300f)).single()
        assertEquals("300 px of text each side: it reaches 150 px in", -150f, kept.x, 0f)
    }

    @Test fun a_name_keeps_its_place_up_to_half_its_own_width_past_a_side_and_moves_beyond_that() {
        val half = 90f
        fun x(at: Float) = placed(named(x = at, y = 900f, left = -2000f, top = 500f, right = 3000f, bottom = 1300f, halfWidth = half)).single().x
        assertEquals("on the edge of reach, left", -half, x(-half), 0f)
        assertTrue("a pixel short of reaching in, left: moved to the middle of the zone on the screen", x(-half - 1f) > 0f)
        assertEquals("on the edge of reach, right", 1080f + half, x(1080f + half), 0f)
        assertTrue("a pixel short of reaching in, right", x(1080f + half + 1f) < 1080f)
    }

    @Test fun the_width_of_each_name_is_its_own() {
        val short = named(x = -150f, y = 900f, left = -800f, top = 500f, right = 300f, bottom = 1300f, halfWidth = 40f)
        val long = named(x = -150f, y = 1000f, left = -800f, top = 500f, right = 300f, bottom = 1300f, halfWidth = 200f)
        val (a, b) = placed(short, long)
        assertEquals("the short one is moved", 150f, a.x, 0f)
        assertEquals("the long one is not", -150f, b.x, 0f)
    }

    @Test fun a_name_with_no_zone_known_is_kept_by_its_own_width() {
        fun label(half: Float) = ScreenLabel("n", -100f, 900f, "#FFFFFF", halfWidthPx = half)
        assertTrue("90 px of text each side does not reach in from 100 px off", GeofenceLabels.placed(listOf(label(90f)), 1080f, 2400f, marginPx = 400f).isEmpty())
        assertEquals("150 px does", 1, GeofenceLabels.placed(listOf(label(150f)), 1080f, 2400f, marginPx = 0f).size)
    }

    @Test fun a_name_is_never_moved_sideways_when_its_point_is_on_the_screen() {
        val kept = placed(named(x = 20f, y = -800f, left = -300f, top = -800f, right = 700f, bottom = 1500f)).single()
        assertEquals(20f, kept.x, 0f)
    }

    @Test fun a_view_too_small_for_the_strip_does_not_crash_the_placement() {
        val kept = GeofenceLabels.placed(
            listOf(named(500f, -100f, 0f, -100f, 900f, 300f)), 1000f, 200f, 100f, topPx = 300f, nameHeightPx = 40f, gapPx = 5f,
        )
        assertTrue(kept.isEmpty() || kept.single().y.isFinite())
    }

    @Test fun the_box_of_a_zone_on_the_screen_is_the_box_round_its_points_and_ignores_points_that_are_not() {
        val box = GeofenceLabels.boxOf(listOf(10f to 20f, 300f to -40f, Float.NaN to 5f, 120f to 900f, 50f to Float.POSITIVE_INFINITY))
        assertEquals(ScreenBox(left = 10f, top = -40f, right = 300f, bottom = 900f), box)
        assertEquals(null, GeofenceLabels.boxOf(emptyList()))
        assertEquals(null, GeofenceLabels.boxOf(listOf(Float.NaN to Float.NaN)))
    }

    @Test fun a_zones_outline_is_thinned_to_a_few_points_spread_all_round_it() {
        val ring = (0..64).map { it.toDouble() to (it * 2).toDouble() }
        val thin = GeofenceLabels.thin(ring)
        assertEquals(GeofenceLabels.OUTLINE_POINTS, thin.size)
        assertEquals(ring.first(), thin.first())
        assertEquals(ring.last(), thin.last())
        assertEquals("evenly: each step is about the same", thin.zipWithNext { a, b -> b.first - a.first }.toSet().size <= 2, true)
        val few = listOf(1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0)
        assertEquals("a few corners are all kept", few, GeofenceLabels.thin(few))
        assertEquals(emptyList<Pair<Double, Double>>(), GeofenceLabels.thin(emptyList()))
    }

    @Test fun the_overlay_gets_a_thinned_outline_of_each_zone() {
        val ring = (0..64).map { 47.0 + it * 0.001 to -117.0 + it * 0.001 }
        val circle = Drawing(id = "c", kind = DrawingKind.POLYGON, points = ring, label = DrawingLabel("Yard", 47.0, -117.0))
        val label = GeofenceLabels.of(listOf(circle)).single()
        assertEquals(GeofenceLabels.thin(ring), label.outline)
        assertTrue(label.outline.size <= GeofenceLabels.OUTLINE_POINTS)
    }

    @Test fun the_name_sits_above_its_point_with_the_bottom_of_its_letters_a_little_over_it() {
        // Text is drawn from its baseline; the descent is how far letters like g and y reach below it.
        val baseline = GeofenceLabels.baselineY(pointY = 1_000f, descentPx = 7f, gapPx = 5f)
        assertEquals("the bottom of the descenders is the gap above the point", 1_000f - 5f, baseline + 7f, 0f)
        assertTrue("so the whole name is above the point", baseline < 1_000f)
    }

    @Test fun the_name_is_clear_of_the_operators_marker_on_the_middle_of_the_zone_once_the_zone_is_bigger_than_the_marker() {
        // The operator's own marker is about 54 px (20 dp) tall, centred on the middle of the zone they are in. The
        // name's point is a radius north of the middle: from a radius of 20 dp on screen it is clear of the marker.
        val markerHalfHeightPx = 54f / 2f
        val radiusPx = 20f * 2.625f
        val baseline = GeofenceLabels.baselineY(pointY = -radiusPx, descentPx = 7f, gapPx = GeofenceLabels.GAP_DP * 2.625f)
        assertTrue(baseline + 7f < -markerHalfHeightPx)
    }
}
