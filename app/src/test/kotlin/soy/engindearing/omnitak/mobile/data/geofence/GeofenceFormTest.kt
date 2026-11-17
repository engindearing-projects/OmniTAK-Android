package soy.engindearing.omnitak.mobile.data.geofence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.DistanceUnit
import soy.engindearing.omnitak.mobile.data.Drawing
import soy.engindearing.omnitak.mobile.data.DrawingKind
import soy.engindearing.omnitak.mobile.data.GeoMath

/** #155 - the small rules of the new-geofence form: the radius choices, the offered name, a geofence from a drawing. */
class GeofenceFormTest {

    private val centerLat = 47.6588
    private val centerLon = -117.4260

    @Test fun the_offered_name_is_the_lowest_free_number() {
        fun name(existing: List<String>) = GeofenceForm.defaultName(existing, "Geofence %d")
        assertEquals("Geofence 1", name(emptyList()))
        assertEquals("Geofence 2", name(listOf("Geofence 1")))
        assertEquals("Geofence 2", name(listOf("Geofence 1", "Geofence 3")))
        assertEquals("Geofence 1", name(listOf("North gate", "Yard")))
    }

    @Test fun an_empty_name_never_blocks_save_it_is_replaced_by_the_offered_one() {
        val existing = listOf("Geofence 1")
        assertEquals("North gate", GeofenceForm.finalName("  North gate ", existing, "Geofence %d"))
        assertEquals("Geofence 2", GeofenceForm.finalName("", existing, "Geofence %d"))
        assertEquals("Geofence 2", GeofenceForm.finalName("   ", existing, "Geofence %d"))
    }

    @Test fun the_radius_runs_from_fifty_metres_to_five_kilometres() {
        assertEquals(50.0, GeofenceForm.RADIUS_CHOICES.first(), 0.0)
        assertEquals(5_000.0, GeofenceForm.RADIUS_CHOICES.last(), 0.0)
        assertEquals(GeofenceForm.RADIUS_CHOICES, GeofenceForm.RADIUS_CHOICES.sorted())
        assertTrue(GeofenceForm.RADIUS_CHOICES.contains(GeofenceForm.DEFAULT_RADIUS_M))
        assertEquals(200.0, GeofenceForm.DEFAULT_RADIUS_M, 0.0)
    }

    @Test fun the_nearest_choice() {
        assertEquals(50.0, GeofenceForm.nearestRadius(10.0), 0.0)
        assertEquals(200.0, GeofenceForm.nearestRadius(210.0), 0.0)
        assertEquals(5_000.0, GeofenceForm.nearestRadius(99_999.0), 0.0)
    }

    @Test fun the_slider_walks_the_list_and_stays_inside_it() {
        assertEquals(0, GeofenceForm.radiusIndex(50.0))
        assertEquals(GeofenceForm.RADIUS_CHOICES.lastIndex, GeofenceForm.radiusIndex(5_000.0))
        assertEquals(50.0, GeofenceForm.radiusAt(-3), 0.0)
        assertEquals(5_000.0, GeofenceForm.radiusAt(999), 0.0)
        assertEquals(200.0, GeofenceForm.radiusAt(GeofenceForm.radiusIndex(200.0)), 0.0)
    }

    @Test fun the_dwell_choices() {
        assertEquals(listOf(1, 5, 10, 30, 60), GeofenceForm.DWELL_CHOICES_MINUTES)
        assertTrue(GeofenceForm.DEFAULT_DWELL_MINUTES in GeofenceForm.DWELL_CHOICES_MINUTES)
    }

    @Test fun the_radius_in_the_operators_units() {
        fun m(meters: Double) = GeofenceForm.radiusLabel(meters, DistanceUnit.METRIC)
        fun i(meters: Double) = GeofenceForm.radiusLabel(meters, DistanceUnit.IMPERIAL)
        assertEquals("50 m", m(50.0))
        assertEquals("200 m", m(200.0))
        assertEquals("750 m", m(750.0))
        assertEquals("1 km", m(1_000.0))
        assertEquals("1.5 km", m(1_500.0))
        assertEquals("5 km", m(5_000.0))
        assertEquals("660 ft", i(200.0))
        assertEquals("160 ft", i(50.0))
        assertEquals("0.62 mi", i(1_000.0))
        assertEquals("3.11 mi", i(5_000.0))
    }

    // ------------------------------------------------------------------ a geofence from a drawing

    private val settings = GeofenceForm.Settings(
        color = GeofenceColor.GREEN, watchSelf = false, watchContacts = true,
        alertOnEntry = true, alertOnExit = false, dwellMinutes = 10,
    )

    private fun polygonDrawing() = Drawing(
        id = "draw-1", kind = DrawingKind.POLYGON, name = "",
        points = listOf(47.65 to -117.43, 47.65 to -117.42, 47.66 to -117.42, 47.66 to -117.43),
    )

    @Test fun a_geofence_from_a_polygon_copies_its_corners() {
        val g = GeofenceForm.fromDrawing("id1", 5L, "Yard", polygonDrawing(), settings)!!
        assertEquals(GeofenceType.POLYGON, g.type)
        assertEquals(4, g.points!!.size)
        assertEquals(LatLon(47.65, -117.43), g.points!!.first())
        assertTrue(g.hasUsableShape)
        assertNull(g.centerLat)
        assertNull(g.radiusMeters)
    }

    @Test fun a_geofence_from_a_circle_has_the_radius_of_the_drawn_circle_in_metres() {
        // A circle drawing is its centre and a point on its edge.
        val edgeLat = centerLat + 0.003
        val drawing = Drawing(id = "draw-2", kind = DrawingKind.CIRCLE, points = listOf(centerLat to centerLon, edgeLat to centerLon))
        val g = GeofenceForm.fromDrawing("id2", 5L, "Ring", drawing, settings)!!
        assertEquals(GeofenceType.CIRCLE, g.type)
        assertEquals(centerLat, g.centerLat!!, 0.0)
        assertEquals(centerLon, g.centerLon!!, 0.0)
        assertEquals(GeoMath.haversineMeters(centerLat, centerLon, edgeLat, centerLon), g.radiusMeters!!, 1e-9)
        assertEquals(333.6, g.radiusMeters!!, 0.5)
        assertTrue(g.hasUsableShape)
    }

    @Test fun the_settings_of_the_form_go_with_the_shape() {
        val g = GeofenceForm.fromDrawing("id1", 7L, "Yard", polygonDrawing(), settings)!!
        assertEquals("id1", g.id)
        assertEquals("Yard", g.name)
        assertEquals(GeofenceColor.GREEN, g.color)
        assertFalse(g.watchSelf)
        assertTrue(g.watchContacts)
        assertTrue(g.alertOnEntry)
        assertFalse(g.alertOnExit)
        assertEquals(10, g.dwellMinutes)
        assertTrue(g.isActive)
        assertEquals(7L, g.createdAtMs)
    }

    @Test fun editing_the_drawing_afterwards_does_not_move_the_geofence() {
        val drawing = polygonDrawing()
        val g = GeofenceForm.fromDrawing("id1", 5L, "Yard", drawing, settings)!!
        val moved = drawing.copy(points = drawing.points.map { (lat, lon) -> lat + 1.0 to lon })
        assertEquals(47.65, g.points!!.first().lat, 0.0)
        assertEquals(48.65, moved.points.first().first, 0.0)
    }

    @Test fun a_line_has_no_inside_and_a_drawing_with_no_size_makes_nothing() {
        val line = Drawing(id = "draw-3", kind = DrawingKind.LINE, points = listOf(0.0 to 0.0, 1.0 to 1.0))
        assertNull(GeofenceForm.fromDrawing("x", 0L, "Line", line, settings))
        val dot = Drawing(id = "draw-4", kind = DrawingKind.CIRCLE, points = listOf(1.0 to 1.0, 1.0 to 1.0))
        assertNull(GeofenceForm.fromDrawing("x", 0L, "Dot", dot, settings))
        val twoCorners = Drawing(id = "draw-5", kind = DrawingKind.POLYGON, points = listOf(1.0 to 1.0, 2.0 to 2.0))
        assertNull(GeofenceForm.fromDrawing("x", 0L, "Two", twoCorners, settings))
    }

    @Test fun the_form_offers_circles_and_polygons_of_the_operators_and_nothing_else() {
        val circle = Drawing(id = "draw-1", kind = DrawingKind.CIRCLE, points = listOf(1.0 to 1.0, 1.0 to 1.001))
        val line = Drawing(id = "draw-2", kind = DrawingKind.LINE, points = listOf(1.0 to 1.0, 2.0 to 2.0))
        val polygon = polygonDrawing()
        val ring = Drawing(id = "__range_ring_0__", kind = DrawingKind.POLYGON, points = polygon.points)
        val fence = Drawing(id = GeofenceDrawings.idFor("g"), kind = DrawingKind.POLYGON, points = polygon.points)
        val dot = Drawing(id = "draw-9", kind = DrawingKind.CIRCLE, points = listOf(1.0 to 1.0, 1.0 to 1.0))
        assertEquals(
            listOf("draw-1", "draw-1"),
            GeofenceForm.drawingSources(listOf(circle, line, polygon, ring, fence, dot)).map { it.id },
        )
    }

    @Test fun a_circle_at_the_map_centre() {
        val g = GeofenceForm.circleAt("id", 9L, "North gate", centerLat, centerLon, 200.0, GeofenceForm.Settings())
        assertEquals(GeofenceType.CIRCLE, g.type)
        assertEquals(centerLat, g.centerLat!!, 0.0)
        assertEquals(200.0, g.radiusMeters!!, 0.0)
        assertTrue(g.watchSelf && g.watchContacts && g.alertOnEntry && g.alertOnExit)
        assertEquals(0, g.dwellMinutes)
        assertEquals(GeofenceColor.YELLOW, g.color)
        assertNotNull(g.zone)
    }

    @Test fun a_geofence_that_watches_nobody_is_not_saved() {
        assertTrue(GeofenceForm.Settings().watchesSomeone)
        assertTrue(GeofenceForm.Settings(watchSelf = false).watchesSomeone)
        assertTrue(GeofenceForm.Settings(watchContacts = false).watchesSomeone)
        assertFalse(GeofenceForm.Settings(watchSelf = false, watchContacts = false).watchesSomeone)
    }
}
