package soy.engindearing.omnitak.mobile.data.geofence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.GeoMath

/**
 * #155 - circle and polygon containment, the geometry under the geofences. Pure, so it unit-tests
 * directly. What a zone is called, who it watches and what it alerts on is [Geofence]
 * ([GeofenceModelTest]); what happens when someone crosses its edge is [GeofenceMonitorTest].
 */
class GeofencingTest {

    private val circle = CircleZone("c", centerLat = 47.0, centerLon = -122.0, radiusMeters = 1000.0)
    private val square = PolygonZone(
        "sq",
        listOf(LatLon(-1.0, -1.0), LatLon(-1.0, 1.0), LatLon(1.0, 1.0), LatLon(1.0, -1.0)),
    )

    @Test fun circle_contains_center_excludes_far() {
        assertTrue(circle.contains(47.0, -122.0))
        assertFalse(circle.contains(48.0, -122.0)) // ~111 km away
    }

    @Test fun circle_radius_boundary() {
        assertTrue(circle.contains(47.0 + 0.008, -122.0))  // ~890 m: inside
        assertFalse(circle.contains(47.0 + 0.010, -122.0)) // ~1113 m: outside
    }

    @Test fun a_point_exactly_on_the_radius_is_inside() {
        // The radius is the distance to that very point, measured the way the zone measures: on the edge.
        val edgeLat = 47.0 + 0.006
        val onTheEdge = GeoMath.haversineMeters(47.0, -122.0, edgeLat, -122.0)
        assertTrue(CircleZone("edge", 47.0, -122.0, onTheEdge).contains(edgeLat, -122.0))
        // A hair inside the radius is not enough to hold it.
        assertFalse(CircleZone("edge", 47.0, -122.0, onTheEdge - 0.01).contains(edgeLat, -122.0))
    }

    @Test fun polygon_contains_via_ray_casting() {
        assertTrue(square.contains(0.0, 0.0))    // center
        assertFalse(square.contains(2.0, 2.0))   // outside (NE)
        assertFalse(square.contains(0.0, 5.0))   // outside (E)
        assertTrue(square.contains(0.9, 0.9))    // inside near corner
    }

    @Test fun polygon_edges_just_inside_and_just_outside() {
        val e = 1e-7
        assertTrue(square.contains(1.0 - e, 0.0))
        assertFalse(square.contains(1.0 + e, 0.0))
        assertTrue(square.contains(0.0, -1.0 + e))
        assertFalse(square.contains(0.0, -1.0 - e))
    }

    @Test fun a_polygon_with_fewer_than_three_corners_contains_nothing() {
        assertFalse(PolygonZone("two", listOf(LatLon(0.0, 0.0), LatLon(1.0, 1.0))).contains(0.5, 0.5))
        assertFalse(PolygonZone("none", emptyList()).contains(0.0, 0.0))
    }

    @Test fun a_geofence_hands_its_shape_to_the_zone_it_tests_with() {
        val round = Geofence(
            id = "g", name = "North gate", type = GeofenceType.CIRCLE,
            centerLat = 47.0, centerLon = -122.0, radiusMeters = 1000.0,
        )
        assertEquals(CircleZone("g", 47.0, -122.0, 1000.0), round.zone)
        assertTrue(round.contains(47.0, -122.0))
        assertFalse(round.contains(48.0, -122.0))

        val yard = Geofence(
            id = "y", name = "Yard", type = GeofenceType.POLYGON,
            points = listOf(LatLon(-1.0, -1.0), LatLon(-1.0, 1.0), LatLon(1.0, 1.0)),
        )
        assertNotNull(yard.zone)
        assertTrue(yard.contains(-0.5, 0.5))
    }

    @Test fun a_geofence_without_a_usable_shape_has_no_zone_and_contains_nothing() {
        val noRadius = Geofence(id = "a", name = "A", type = GeofenceType.CIRCLE, centerLat = 1.0, centerLon = 1.0, radiusMeters = 0.0)
        val noCenter = Geofence(id = "b", name = "B", type = GeofenceType.CIRCLE, radiusMeters = 100.0)
        val twoCorners = Geofence(id = "c", name = "C", type = GeofenceType.POLYGON, points = listOf(LatLon(0.0, 0.0), LatLon(1.0, 1.0)))
        val noShape = Geofence(id = "d", name = "D", type = GeofenceType.POLYGON)
        for (g in listOf(noRadius, noCenter, twoCorners, noShape)) {
            assertFalse("${g.name} has a usable shape", g.hasUsableShape)
            assertNull("${g.name} has a zone", g.zone)
            assertFalse(g.contains(1.0, 1.0))
            assertFalse(g.contains(0.5, 0.5))
        }
    }

    @Test fun a_position_that_is_not_a_coordinate_is_never_inside() {
        val round = Geofence(id = "g", name = "G", type = GeofenceType.CIRCLE, centerLat = 0.0, centerLon = 0.0, radiusMeters = 1e9)
        assertFalse(round.contains(Double.NaN, 0.0))
        assertFalse(round.contains(0.0, Double.POSITIVE_INFINITY))
        assertFalse(round.contains(91.0, 0.0))
    }
}
