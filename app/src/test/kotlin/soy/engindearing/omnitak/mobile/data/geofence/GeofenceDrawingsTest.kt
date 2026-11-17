package soy.engindearing.omnitak.mobile.data.geofence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.Drawing
import soy.engindearing.omnitak.mobile.data.DrawingKind
import soy.engindearing.omnitak.mobile.data.GeoMath

/**
 * #155 - what both map engines draw for a geofence: the mapping from a geofence to its transient
 * drawings. They ride the same renderer as the range rings, under reserved ids, and are never in
 * the drawing store.
 */
class GeofenceDrawingsTest {

    private val centerLat = 47.6588
    private val centerLon = -117.4260

    private fun circle(id: String, radius: Double = 500.0, name: String = "Gate $id") = Geofence(
        id = id, name = name, type = GeofenceType.CIRCLE, color = GeofenceColor.RED,
        centerLat = centerLat, centerLon = centerLon, radiusMeters = radius,
    )

    private fun yard(id: String = "y") = Geofence(
        id = id, name = "Yard", type = GeofenceType.POLYGON, color = GeofenceColor.BLUE,
        points = listOf(
            LatLon(centerLat - 0.001, centerLon - 0.001), LatLon(centerLat - 0.001, centerLon + 0.001),
            LatLon(centerLat + 0.001, centerLon + 0.001), LatLon(centerLat + 0.001, centerLon - 0.001),
        ),
    )

    private fun shapesOf(drawings: List<Drawing>) = drawings.filter { it.kind == DrawingKind.POLYGON }

    @Test fun only_geofences_that_are_on_and_have_a_shape_are_drawn() {
        val on = circle("on")
        val off = circle("off").copy(isActive = false)
        val flat = circle("flat", radius = 0.0)
        val drawn = shapesOf(GeofenceDrawings.from(listOf(on, off, flat, yard())))
        assertEquals(listOf("Gate on", "Yard"), drawn.map { it.name })
    }

    @Test fun nothing_to_draw_for_no_geofences() {
        assertTrue(GeofenceDrawings.from(emptyList()).isEmpty())
    }

    @Test fun the_ids_are_reserved_so_nothing_can_take_one_for_an_operators_drawing() {
        val drawn = GeofenceDrawings.from(listOf(circle("abc")))
        assertTrue(drawn.isNotEmpty())
        for (d in drawn) {
            assertTrue(d.id, d.id.startsWith("__geofence_"))
            assertTrue(GeofenceDrawings.isGeofenceDrawing(d.id))
            assertTrue("a reserved id starts with two underscores, as the range rings' do", d.id.startsWith("__"))
        }
        assertEquals("__geofence_abc__", GeofenceDrawings.idFor("abc"))
        // An operator's drawing is "draw-<time>", the one being made "__in_progress__", a ring "__range_ring_<n>__".
        assertFalse(GeofenceDrawings.isGeofenceDrawing("draw-1763337600000"))
        assertFalse(GeofenceDrawings.isGeofenceDrawing("__in_progress__"))
        assertFalse(GeofenceDrawings.isGeofenceDrawing("__range_ring_0__"))
    }

    @Test fun every_id_is_different() {
        val drawn = GeofenceDrawings.from(listOf(circle("a"), circle("b"), yard("c")))
        assertEquals(drawn.size, drawn.map { it.id }.toSet().size)
    }

    @Test fun the_shape_is_a_polygon_in_the_geofences_colour_with_a_light_fill() {
        val shape = shapesOf(GeofenceDrawings.from(listOf(circle("a")))).single()
        assertEquals("#FF3B30", shape.colorHex)
        assertEquals(0.12f, shape.fillAlpha, 0f)
        assertEquals(GeofenceDrawings.FILL_ALPHA, shape.fillAlpha, 0f)
        assertTrue("lighter than the 30 percent an operator's own drawing has", shape.fillAlpha < Drawing.DEFAULT_FILL_ALPHA)
    }

    @Test fun the_outline_has_a_dark_casing_under_it() {
        val drawn = GeofenceDrawings.from(listOf(circle("a")))
        assertEquals(2, drawn.size)
        val (casing, shape) = drawn
        assertEquals(DrawingKind.LINE, casing.kind)
        assertEquals(GeofenceDrawings.CASING_COLOR_HEX, casing.colorHex)
        assertTrue("wider than the line over it", casing.widthPx > shape.widthPx)
        assertEquals("the casing is a closed loop on the same ring", casing.points.first(), casing.points.last())
        assertEquals(shape.points, casing.points.dropLast(1))
    }

    @Test fun a_circles_ring_is_the_circle_the_monitor_measures_on_the_ground() {
        val shape = shapesOf(GeofenceDrawings.from(listOf(circle("a", radius = 500.0)))).single()
        assertEquals(GeofenceDrawings.CIRCLE_SEGMENTS, shape.points.size)
        for ((lat, lon) in shape.points) {
            val distance = GeoMath.haversineMeters(centerLat, centerLon, lat, lon)
            assertEquals("a point of the ring is ${distance} m out", 500.0, distance, 500.0 * 0.01)
        }
    }

    @Test fun a_circle_is_round_on_the_ground_at_any_latitude() {
        // Planar degrees would stretch it north-south by 1 / cos(latitude): a third as long again at 70 degrees.
        for (lat in listOf(0.0, 47.6588, 70.0)) {
            val ring = GeofenceDrawings.ringOf(circle("p").copy(centerLat = lat, radiusMeters = 1_000.0))!!
            for ((pointLat, pointLon) in ring) {
                val distance = GeoMath.haversineMeters(lat, centerLon, pointLat, pointLon)
                assertEquals("at $lat the ring is $distance m out", 1_000.0, distance, 10.0)
            }
        }
    }

    @Test fun a_polygons_ring_is_its_own_corners_open() {
        val g = yard()
        val shape = shapesOf(GeofenceDrawings.from(listOf(g))).single()
        assertEquals(g.points!!.map { it.lat to it.lon }, shape.points)
    }

    @Test fun the_name_is_written_on_the_north_edge_of_a_circle_and_at_the_mean_of_a_polygon() {
        val round = shapesOf(GeofenceDrawings.from(listOf(circle("a", name = "North gate")))).single()
        assertEquals("North gate", round.label!!.text)
        assertEquals("due north of the centre", centerLon, round.label!!.lon, 1e-9)
        assertEquals("one radius out", 500.0, GeoMath.haversineMeters(centerLat, centerLon, round.label!!.lat, round.label!!.lon), 0.01)
        val firstCorner = round.points.first()
        assertEquals("it is on the zone's own outline, where the ring starts", firstCorner.first, round.label!!.lat, 1e-9)
        assertEquals(firstCorner.second, round.label!!.lon, 1e-9)

        val square = shapesOf(GeofenceDrawings.from(listOf(yard()))).single()
        assertEquals(centerLat, square.label!!.lat, 1e-9)
        assertEquals(centerLon, square.label!!.lon, 1e-9)
    }

    @Test fun the_casing_carries_no_name_so_a_geofence_is_named_once() {
        val (casing, _) = GeofenceDrawings.from(listOf(circle("a")))
        assertEquals(null, casing.label)
    }

    @Test fun an_operators_own_drawing_is_unchanged_by_all_this() {
        val mine = Drawing(id = "draw-1", kind = DrawingKind.POLYGON, points = listOf(0.0 to 0.0, 0.0 to 1.0, 1.0 to 1.0))
        assertEquals(0.3f, mine.fillAlpha, 0f)
        assertEquals(null, mine.label)
    }
}
