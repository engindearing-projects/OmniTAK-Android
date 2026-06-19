package soy.engindearing.omnitak.mobile.data.rangebearing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.GeoMath

/**
 * #152 — Range & Bearing math (ported from iOS RangeBearingService). Pure, so
 * it unit-tests directly against known great-circle values.
 */
class RangeBearingTest {

    @Test fun east_along_equator_is_90deg_backaz_270() {
        val r = RangeBearing.compute(0.0, 0.0, 0.0, 1.0)
        assertEquals(90.0, r.trueBearing, 1e-6)
        assertEquals(270.0, r.backAzimuth, 1e-6)
        assertEquals(111_195.0, r.distanceMeters, 60.0) // ~1° of longitude at equator
    }

    @Test fun north_is_0deg_backaz_180() {
        val r = RangeBearing.compute(0.0, 0.0, 1.0, 0.0)
        assertEquals(0.0, r.trueBearing, 1e-6)
        assertEquals(180.0, r.backAzimuth, 1e-6)
    }

    @Test fun magnetic_subtracts_east_declination() {
        // true 90, east declination +10 -> magnetic 80
        val r = RangeBearing.compute(0.0, 0.0, 0.0, 1.0, declinationDeg = 10.0)
        assertEquals(80.0, r.magneticBearing, 1e-6)
    }

    @Test fun magnetic_with_west_declination() {
        // true 0, west declination -10 -> magnetic 10
        val r = RangeBearing.compute(0.0, 0.0, 1.0, 0.0, declinationDeg = -10.0)
        assertEquals(10.0, r.magneticBearing, 1e-6)
    }

    @Test fun magnetic_wraps_below_zero() {
        // true 0, east declination +10 -> magnetic 350 (wrap)
        val r = RangeBearing.compute(0.0, 0.0, 1.0, 0.0, declinationDeg = 10.0)
        assertEquals(350.0, r.magneticBearing, 1e-6)
    }

    @Test fun grid_subtracts_convergence() {
        // true 90, grid convergence +5 -> grid 85
        val r = RangeBearing.compute(0.0, 0.0, 0.0, 1.0, gridConvergenceDeg = 5.0)
        assertEquals(85.0, r.gridBearing, 1e-6)
    }

    @Test fun norm360_wraps_both_directions() {
        assertEquals(10.0, RangeBearing.norm360(370.0), 1e-9)
        assertEquals(350.0, RangeBearing.norm360(-10.0), 1e-9)
        assertEquals(0.0, RangeBearing.norm360(360.0), 1e-9)
    }

    @Test fun ring_is_closed_and_every_point_is_at_radius() {
        val cLat = 47.6; val cLon = -122.3; val radius = 5_000.0
        val pts = RangeBearing.ringPoints(cLat, cLon, radius, segments = 32)
        assertEquals(33, pts.size) // segments + 1 (closed)
        assertEquals(pts.first()[0], pts.last()[0], 1e-9)
        assertEquals(pts.first()[1], pts.last()[1], 1e-9)
        for (p in pts) {
            val d = GeoMath.haversineMeters(cLat, cLon, p[0], p[1])
            assertEquals("ring point off radius", radius, d, radius * 0.02)
        }
    }

    @Test fun ring_first_point_is_due_north() {
        // segments=4 -> point 0 is bearing 0 (north)
        val pts = RangeBearing.ringPoints(0.0, 0.0, 111_195.0, segments = 4)
        assertTrue("north point should be ~1° north", pts[0][0] > 0.9)
        assertEquals(0.0, pts[0][1], 1e-6)
    }

    @Test fun concentric_rings_count_and_outer_radius() {
        val rings = RangeBearing.rings(0.0, 0.0, spacingMeters = 1_000.0, count = 3, segments = 16)
        assertEquals(3, rings.size)
        val outer = GeoMath.haversineMeters(0.0, 0.0, rings[2][0][0], rings[2][0][1])
        assertEquals(3_000.0, outer, 60.0)
    }
}
