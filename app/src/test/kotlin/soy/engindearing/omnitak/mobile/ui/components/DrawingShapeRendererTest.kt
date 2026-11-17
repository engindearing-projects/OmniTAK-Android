package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.Drawing
import soy.engindearing.omnitak.mobile.data.DrawingKind
import soy.engindearing.omnitak.mobile.data.GeoMath
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceDrawings
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceForm
import soy.engindearing.omnitak.mobile.data.rangebearing.RangeBearing
import kotlin.math.abs

/**
 * Locks the pure geometry helpers behind the #80 native-drawing fix. Operator
 * drawings were invisible on the 2D map (GeoJsonSource Fill/Line GL paint bug,
 * same class as #77/#136); DrawingShapeRenderer draws them as native polyline /
 * polygon annotations, and these prepare the rings it feeds to addPolygon.
 */
class DrawingShapeRendererTest {

    @Test fun closed_ring_appends_first_vertex_when_open() {
        val open = listOf(23.69 to 120.99, 23.69 to 121.01, 23.70 to 121.01)
        val ring = DrawingShapeRenderer.closedRing(open)
        assertEquals(open.size + 1, ring.size)
        assertEquals(ring.first(), ring.last())
    }

    @Test fun closed_ring_leaves_already_closed_ring_untouched() {
        val closed = listOf(23.69 to 120.99, 23.69 to 121.01, 23.70 to 121.01, 23.69 to 120.99)
        assertEquals(closed, DrawingShapeRenderer.closedRing(closed))
    }

    @Test fun circle_polygon_makes_a_closed_64gon_around_the_center() {
        // [center, edge] -> 64 segments, 65 verts, first == last.
        val pts = listOf(23.70 to 121.00, 23.71 to 121.00) // about 1.1 km
        val ring = DrawingShapeRenderer.circlePolygon(pts)
        assertEquals(65, ring.size)
        assertEquals(ring.first(), ring.last())
        // Every vertex sits the same distance on the ground from the center: the distance to the edge point.
        val radius = GeoMath.haversineMeters(23.70, 121.00, 23.71, 121.00)
        ring.forEach { (lat, lon) ->
            val d = GeoMath.haversineMeters(23.70, 121.00, lat, lon)
            assertEquals("vertex off the circle: $d m, not $radius m", radius, d, 0.01)
        }
    }

    // #155 follow-up: a drawn circle used the same number of DEGREES of latitude and of longitude as its radius.
    // A degree of longitude is only cos(latitude) as long as a degree of latitude, so away from the equator the
    // circle was an oval, 1.49 times taller than wide at Spokane, and the geofence made from it (which is round
    // on the ground) did not lie on it.

    private val spokane = 47.66 to -117.43

    /** How far the ring reaches from its centre to the north and to the east, in metres on the ground. */
    private fun reach(ring: List<Pair<Double, Double>>, center: Pair<Double, Double>): Pair<Double, Double> {
        val north = ring.maxOf { it.first }
        val east = ring.maxOf { it.second }
        return GeoMath.haversineMeters(center.first, center.second, north, center.second) to
            GeoMath.haversineMeters(center.first, center.second, center.first, east)
    }

    private fun edgeAt(center: Pair<Double, Double>, meters: Double, bearingSteps: Int, of: Int): Pair<Double, Double> {
        // ringPoints walks equal bearings from north: step i of `of` is bearing 360 * i / of.
        val point = RangeBearing.ringPoints(center.first, center.second, meters, of)[bearingSteps]
        return point[0] to point[1]
    }

    @Test fun a_circle_drawn_at_spokane_is_as_tall_as_it_is_wide_on_the_ground() {
        val edge = edgeAt(spokane, 300.0, bearingSteps = 1, of = 4) // 300 m due east
        val ring = DrawingShapeRenderer.circlePolygon(listOf(spokane, edge))
        val (north, east) = reach(ring, spokane)
        assertEquals("300 m north, the way it was tapped east", 300.0, north, 1.5)
        assertEquals("300 m east", 300.0, east, 1.5)
    }

    @Test fun it_is_round_whichever_way_the_edge_is_tapped() {
        for (steps in 0 until 8) { // north, north-east, east, ... of eight
            val edge = edgeAt(spokane, 450.0, bearingSteps = steps, of = 8)
            val ring = DrawingShapeRenderer.circlePolygon(listOf(spokane, edge))
            val (north, east) = reach(ring, spokane)
            val radius = GeoMath.haversineMeters(spokane.first, spokane.second, edge.first, edge.second)
            assertEquals("tapped at bearing ${steps * 45}: north reach", radius, north, radius * 0.005)
            assertEquals("tapped at bearing ${steps * 45}: east reach", radius, east, radius * 0.005)
        }
    }

    @Test fun it_is_round_at_sixty_north_too() {
        val center = 60.0 to 10.0
        val edge = edgeAt(center, 1_000.0, bearingSteps = 3, of = 8)
        val (north, east) = reach(DrawingShapeRenderer.circlePolygon(listOf(center, edge)), center)
        assertEquals(north, east, 5.0)
    }

    @Test fun at_the_equator_nothing_changes_a_degree_is_a_degree() {
        val center = 0.0 to 100.0
        val edge = 0.0 to 100.01
        val ring = DrawingShapeRenderer.circlePolygon(listOf(center, edge))
        val (north, east) = reach(ring, center)
        assertEquals(north, east, 1.0)
        assertEquals(GeoMath.haversineMeters(0.0, 100.0, 0.0, 100.01), east, 1.0)
    }

    @Test fun a_circle_drawn_across_the_antimeridian_has_continuous_longitudes_not_a_jump_of_360_degrees() {
        // RangeBearing.ringPoints folds every longitude into [-180, 180); a ring that crosses 180 would jump from
        // 180 to -180 between two vertices, and the map draws that as a band across the world.
        val center = 0.0 to 179.99
        val edge = edgeAt(center, 5_000.0, bearingSteps = 2, of = 4)
        val ring = DrawingShapeRenderer.circlePolygon(listOf(center, edge))
        for ((a, b) in ring.zipWithNext()) {
            assertTrue("jump from ${a.second} to ${b.second}", abs(a.second - b.second) < 1.0)
        }
        assertTrue("part of the circle is past 180", ring.any { it.second > 180.0 })
        assertEquals(ring.first(), ring.last())
    }

    @Test fun a_geofence_made_from_a_drawn_circle_lies_exactly_on_it() {
        val edge = edgeAt(spokane, 367.0, bearingSteps = 1, of = 8)
        val drawing = Drawing(id = "d", kind = DrawingKind.CIRCLE, points = listOf(spokane, edge))
        val fence = checkNotNull(
            GeofenceForm.fromDrawing("g", 0L, "Yard", drawing, GeofenceForm.Settings()),
        )
        val geofenceRing = checkNotNull(GeofenceDrawings.ringOf(fence))
        val drawnRing = DrawingShapeRenderer.circlePolygon(drawing.points).dropLast(1)
        assertEquals(drawnRing.size, geofenceRing.size)
        for (i in drawnRing.indices) {
            assertEquals("vertex $i lat", drawnRing[i].first, geofenceRing[i].first, 1e-9)
            assertEquals("vertex $i lon", drawnRing[i].second, geofenceRing[i].second, 1e-9)
        }
    }

    @Test fun circle_polygon_with_zero_radius_is_empty_not_a_dot() {
        // Degenerate (center == edge) must not emit a zero-area polygon.
        assertTrue(DrawingShapeRenderer.circlePolygon(listOf(23.70 to 121.00, 23.70 to 121.00)).isEmpty())
    }
}
