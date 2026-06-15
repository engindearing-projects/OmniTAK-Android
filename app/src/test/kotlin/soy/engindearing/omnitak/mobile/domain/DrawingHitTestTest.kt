package soy.engindearing.omnitak.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.Drawing
import soy.engindearing.omnitak.mobile.data.DrawingKind

/**
 * Issue #76 — screen-space drawing hit-testing. All coordinates are pixels
 * (the caller projects geo → screen before calling), so these are pure JVM
 * tests with no Android map dependency.
 */
class DrawingHitTestTest {

    private fun projected(id: String, kind: DrawingKind, pts: List<Pair<Float, Float>>) =
        DrawingHitTest.Projected(id, kind, pts)

    // ── LINE ──────────────────────────────────────────────────────────────

    @Test fun tap_on_line_segment_hits() {
        val line = projected("L", DrawingKind.LINE, listOf(0f to 0f, 100f to 0f))
        // 5px off the midpoint of a horizontal line, tolerance 24 → hit.
        assertTrue(DrawingHitTest.hits(line, 50f, 5f, 24f))
    }

    @Test fun tap_far_from_line_misses() {
        val line = projected("L", DrawingKind.LINE, listOf(0f to 0f, 100f to 0f))
        assertFalse(DrawingHitTest.hits(line, 50f, 80f, 24f))
    }

    // ── POLYGON ───────────────────────────────────────────────────────────

    @Test fun tap_inside_polygon_hits() {
        val poly = projected(
            "P", DrawingKind.POLYGON,
            listOf(0f to 0f, 100f to 0f, 100f to 100f, 0f to 100f),
        )
        assertTrue(DrawingHitTest.hits(poly, 50f, 50f, 8f))
    }

    @Test fun tap_outside_polygon_misses() {
        val poly = projected(
            "P", DrawingKind.POLYGON,
            listOf(0f to 0f, 100f to 0f, 100f to 100f, 0f to 100f),
        )
        assertFalse(DrawingHitTest.hits(poly, 200f, 200f, 8f))
    }

    @Test fun tap_on_polygon_edge_hits_via_tolerance() {
        val poly = projected(
            "P", DrawingKind.POLYGON,
            listOf(0f to 0f, 100f to 0f, 100f to 100f, 0f to 100f),
        )
        // Just outside the right edge but within tolerance of it.
        assertTrue(DrawingHitTest.hits(poly, 105f, 50f, 24f))
    }

    // ── CIRCLE ────────────────────────────────────────────────────────────

    @Test fun tap_inside_circle_disc_hits() {
        // center (50,50), edge (90,50) → radius 40.
        val circle = projected("C", DrawingKind.CIRCLE, listOf(50f to 50f, 90f to 50f))
        assertTrue(DrawingHitTest.hits(circle, 60f, 50f, 8f))
    }

    @Test fun tap_near_circle_rim_hits() {
        val circle = projected("C", DrawingKind.CIRCLE, listOf(50f to 50f, 90f to 50f))
        // 5px outside the rim, tolerance 24 → hit.
        assertTrue(DrawingHitTest.hits(circle, 95f, 50f, 24f))
    }

    @Test fun tap_far_outside_circle_misses() {
        val circle = projected("C", DrawingKind.CIRCLE, listOf(50f to 50f, 90f to 50f))
        assertFalse(DrawingHitTest.hits(circle, 200f, 200f, 24f))
    }

    // ── topmost selection ───────────────────────────────────────────────────

    @Test fun hitId_returns_topmost_when_overlapping() {
        // Two overlapping polygons; the later one (B) paints on top, so a tap
        // in the overlap selects B.
        val a = projected("A", DrawingKind.POLYGON, listOf(0f to 0f, 100f to 0f, 100f to 100f, 0f to 100f))
        val b = projected("B", DrawingKind.POLYGON, listOf(0f to 0f, 100f to 0f, 100f to 100f, 0f to 100f))
        assertEquals("B", DrawingHitTest.hitId(listOf(a, b), 50f, 50f, 8f))
    }

    @Test fun hitId_returns_null_when_nothing_hit() {
        val a = projected("A", DrawingKind.LINE, listOf(0f to 0f, 10f to 0f))
        assertNull(DrawingHitTest.hitId(listOf(a), 500f, 500f, 8f))
    }

    @Test fun empty_points_never_hit() {
        val empty = projected("E", DrawingKind.LINE, emptyList())
        assertFalse(DrawingHitTest.hits(empty, 0f, 0f, 100f))
    }

    // ── translate ────────────────────────────────────────────────────────

    @Test fun translate_shifts_all_vertices_rigidly() {
        val d = Drawing(
            id = "d1",
            kind = DrawingKind.POLYGON,
            points = listOf(10.0 to 20.0, 11.0 to 21.0, 12.0 to 22.0),
        )
        val moved = DrawingHitTest.translate(d, dLatDeg = 0.5, dLonDeg = -0.25)
        assertEquals(listOf(10.5 to 19.75, 11.5 to 20.75, 12.5 to 21.75), moved.points)
        // id / kind preserved.
        assertEquals("d1", moved.id)
        assertEquals(DrawingKind.POLYGON, moved.kind)
    }
}
