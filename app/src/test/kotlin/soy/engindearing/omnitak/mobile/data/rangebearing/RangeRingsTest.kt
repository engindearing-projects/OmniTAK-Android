package soy.engindearing.omnitak.mobile.data.rangebearing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.DrawingKind

/**
 * #152 — range-rings tool helper. Locks the parity-relevant defaults + label
 * format and the drawing shape the map tool renders (CI can't drive the map UI).
 */
class RangeRingsTest {

    @Test fun default_distances_match_the_ios_configuration() {
        assertEquals(listOf(100.0, 500.0, 1000.0, 2000.0, 5000.0), RangeRings.DEFAULT_DISTANCES_M)
    }

    @Test fun label_uses_meters_under_1km_and_km_above() {
        assertEquals("100m", RangeRings.label(100.0))
        assertEquals("500m", RangeRings.label(500.0))
        assertEquals("1.0km", RangeRings.label(1000.0))
        assertEquals("2.0km", RangeRings.label(2000.0))
        assertEquals("5.0km", RangeRings.label(5000.0))
    }

    @Test fun builds_one_closed_line_drawing_per_radius() {
        val rings = RangeRings.ringDrawings(25.0330, 121.5654)
        assertEquals(RangeRings.DEFAULT_DISTANCES_M.size, rings.size)
        for (d in rings) {
            assertEquals(DrawingKind.LINE, d.kind)            // un-filled outline, not a polygon
            assertTrue("ring should be a closed loop", d.points.first() == d.points.last())
            assertTrue("ring needs enough points to read as a circle", d.points.size > 60)
        }
    }

    @Test fun ring_radius_grows_with_distance() {
        val rings = RangeRings.ringDrawings(0.0, 0.0, listOf(100.0, 1000.0))
        // East-point longitude offset from center scales ~linearly with radius.
        val inner = rings[0].points.maxOf { it.second }
        val outer = rings[1].points.maxOf { it.second }
        assertTrue("1km ring must be wider than the 100m ring", outer > inner * 5)
    }

    @Test fun rings_are_centered_on_the_requested_point() {
        val rings = RangeRings.ringDrawings(51.5074, -0.1278, listOf(500.0))
        val lats = rings[0].points.map { it.first }
        val lons = rings[0].points.map { it.second }
        // Center is the mean of the symmetric ring vertices.
        assertEquals(51.5074, lats.average(), 0.01)
        assertEquals(-0.1278, lons.average(), 0.01)
    }
}
