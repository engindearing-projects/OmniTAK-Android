package soy.engindearing.omnitak.mobile.domain

import soy.engindearing.omnitak.mobile.data.Drawing
import soy.engindearing.omnitak.mobile.data.DrawingKind
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Issue #76 — screen-space hit-testing for operator drawings so a tap can
 * select a circle / line / polygon and surface edit / move / delete (parity
 * with marker tap interactions).
 *
 * All geometry runs in SCREEN PIXELS: the caller projects each drawing
 * vertex to pixels via the live map projection and hands us flat points.
 * Keeping the math screen-space (and free of any MapLibre/Android type)
 * makes it unit-testable on the JVM and matches how the marker hit-test in
 * [soy.engindearing.omnitak.mobile.ui.components.TacticalMap] already works.
 *
 * A point hits:
 *  - LINE: within [tolerancePx] of any segment.
 *  - POLYGON: inside the ring, OR within [tolerancePx] of any edge (so the
 *    thin outline is still grabbable on a near-degenerate shape).
 *  - CIRCLE: inside the disc, OR within [tolerancePx] of the rim — stored as
 *    [center, edge], radius = |edge - center| in pixels.
 */
object DrawingHitTest {

    /** A drawing's vertices already projected to screen pixels. */
    data class Projected(val id: String, val kind: DrawingKind, val points: List<Pair<Float, Float>>)

    /**
     * Return the id of the topmost drawing hit by [px],[py], or null. "Topmost"
     * = last in [drawings] (later drawings paint over earlier ones), so we scan
     * in reverse and take the first hit.
     */
    fun hitId(
        drawings: List<Projected>,
        px: Float,
        py: Float,
        tolerancePx: Float,
    ): String? {
        for (i in drawings.indices.reversed()) {
            val d = drawings[i]
            if (hits(d, px, py, tolerancePx)) return d.id
        }
        return null
    }

    fun hits(d: Projected, px: Float, py: Float, tolerancePx: Float): Boolean {
        val pts = d.points
        if (pts.isEmpty()) return false
        return when (d.kind) {
            DrawingKind.LINE -> nearAnySegment(pts, px, py, tolerancePx)
            DrawingKind.POLYGON ->
                pointInPolygon(pts, px, py) || nearAnySegment(closeRing(pts), px, py, tolerancePx)
            DrawingKind.CIRCLE -> {
                val (cx, cy) = pts.first()
                val (ex, ey) = pts.getOrElse(1) { pts.first() }
                val radius = hypot((ex - cx).toDouble(), (ey - cy).toDouble()).toFloat()
                val dist = hypot((px - cx).toDouble(), (py - cy).toDouble()).toFloat()
                // Inside the disc OR within tolerance of the rim.
                dist <= radius + tolerancePx
            }
        }
    }

    private fun closeRing(pts: List<Pair<Float, Float>>): List<Pair<Float, Float>> =
        if (pts.size >= 2 && pts.first() != pts.last()) pts + pts.first() else pts

    private fun nearAnySegment(
        pts: List<Pair<Float, Float>>,
        px: Float,
        py: Float,
        tolerancePx: Float,
    ): Boolean {
        if (pts.size == 1) {
            val (x, y) = pts.first()
            return hypot((px - x).toDouble(), (py - y).toDouble()) <= tolerancePx
        }
        for (i in 0 until pts.size - 1) {
            val (x1, y1) = pts[i]
            val (x2, y2) = pts[i + 1]
            if (distToSegment(px, py, x1, y1, x2, y2) <= tolerancePx) return true
        }
        return false
    }

    /** Shortest distance from (px,py) to the segment (x1,y1)-(x2,y2), in px. */
    internal fun distToSegment(
        px: Float, py: Float,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
    ): Float {
        val dx = (x2 - x1).toDouble()
        val dy = (y2 - y1).toDouble()
        val lenSq = dx * dx + dy * dy
        if (lenSq == 0.0) return hypot((px - x1).toDouble(), (py - y1).toDouble()).toFloat()
        var t = ((px - x1) * dx + (py - y1) * dy) / lenSq
        t = max(0.0, min(1.0, t))
        val projX = x1 + t * dx
        val projY = y1 + t * dy
        return hypot(px - projX, py - projY).toFloat()
    }

    /** Ray-casting point-in-polygon in screen space (PNPOLY). */
    internal fun pointInPolygon(pts: List<Pair<Float, Float>>, px: Float, py: Float): Boolean {
        if (pts.size < 3) return false
        var inside = false
        var j = pts.size - 1
        for (i in pts.indices) {
            val xi = pts[i].first
            val yi = pts[i].second
            val xj = pts[j].first
            val yj = pts[j].second
            val intersect = ((yi > py) != (yj > py)) &&
                (px < (xj - xi) * (py - yi) / (yj - yi) + xi)
            if (intersect) inside = !inside
            j = i
        }
        return inside
    }

    /**
     * Issue #76 — translate every vertex of [drawing] by a geographic delta
     * (degrees). Used by the move gesture: the operator drags the selected
     * shape and we shift all points so the whole drawing moves rigidly.
     */
    fun translate(drawing: Drawing, dLatDeg: Double, dLonDeg: Double): Drawing =
        drawing.copy(points = drawing.points.map { (lat, lon) -> (lat + dLatDeg) to (lon + dLonDeg) })
}
