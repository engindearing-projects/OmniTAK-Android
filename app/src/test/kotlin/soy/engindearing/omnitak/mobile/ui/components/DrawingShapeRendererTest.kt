package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
        // [center, edge] → 64 segments, 65 verts, first == last.
        val pts = listOf(23.70 to 121.00, 23.71 to 121.00) // ~0.01deg radius
        val ring = DrawingShapeRenderer.circlePolygon(pts)
        assertEquals(65, ring.size)
        assertEquals(ring.first(), ring.last())
        // Every vertex sits ~radius from the center.
        val r = 0.01
        ring.forEach { (lat, lon) ->
            val d = kotlin.math.hypot(lat - 23.70, lon - 121.00)
            assertTrue("vertex off the circle: $d", kotlin.math.abs(d - r) < 1e-6)
        }
    }

    @Test fun circle_polygon_with_zero_radius_is_empty_not_a_dot() {
        // Degenerate (center == edge) must not emit a zero-area polygon.
        assertTrue(DrawingShapeRenderer.circlePolygon(listOf(23.70 to 121.00, 23.70 to 121.00)).isEmpty())
    }
}
