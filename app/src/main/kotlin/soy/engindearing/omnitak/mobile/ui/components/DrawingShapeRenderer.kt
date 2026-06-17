package soy.engindearing.omnitak.mobile.ui.components

import android.graphics.Color
import org.maplibre.android.annotations.Polygon
import org.maplibre.android.annotations.PolygonOptions
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import soy.engindearing.omnitak.mobile.data.Drawing
import soy.engindearing.omnitak.mobile.data.DrawingKind
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Renders operator drawings (lines / polygons / circles) as native MapLibre
 * annotations (addPolyline / addPolygon) instead of the `drawings-src`
 * GeoJsonSource Fill/Line layers.
 *
 * #80: drawings were invisible on the 2D map on Adreno/Mali/the emulator — the
 * same GeoJsonSource GL paint bug that hid contacts (#77) and KML lines/polygons
 * (#136). DrawingLayer pushes the FeatureCollection into the source fine, but the
 * driver won't rasterize the Fill/Line layers; toggling the 3D globe (a different
 * renderer) is what made them "reappear". The Annotation API uses the native
 * renderer (same as LocationComponent) and paints on every GPU — and, unlike the
 * style-layer path, doesn't depend on layer-insertion order across style reloads,
 * so it also covers the repaint-on-reload reading of the bug. See
 * project_omnitak_android_marker_gpu_bug; mirrors KmlShapeRenderer.
 *
 * Polygons/circles get a translucent fill plus a full-opacity outline ring.
 */
object DrawingShapeRenderer {
    private var polylines: List<Polyline> = emptyList()
    private var polygons: List<Polygon> = emptyList()

    private const val CIRCLE_STEPS = 64

    fun apply(map: MapLibreMap, drawings: List<Drawing>) {
        clearAnnotations(map)
        val newLines = ArrayList<Polyline>()
        val newPolys = ArrayList<Polygon>()
        for (d in drawings) {
            val color = runCatching { Color.parseColor(d.colorHex) }.getOrDefault(Color.parseColor("#4ADE80"))
            val width = d.widthPx.coerceIn(2f, 14f)
            when (d.kind) {
                DrawingKind.LINE -> {
                    if (d.points.size < 2) continue
                    addLine(map, d.points, color, width)?.let { newLines.add(it) }
                }
                DrawingKind.POLYGON -> {
                    val ring = closedRing(d.points)
                    if (ring.size < 3) continue
                    addPolygon(map, ring, color, width, newLines)?.let { newPolys.add(it) }
                }
                DrawingKind.CIRCLE -> {
                    val ring = circlePolygon(d.points)
                    if (ring.size < 3) continue
                    addPolygon(map, ring, color, width, newLines)?.let { newPolys.add(it) }
                }
            }
        }
        polylines = newLines
        polygons = newPolys
    }

    fun clear(map: MapLibreMap) = clearAnnotations(map)

    private fun clearAnnotations(map: MapLibreMap) {
        polylines.forEach { runCatching { map.removePolyline(it) } }
        polygons.forEach { runCatching { map.removePolygon(it) } }
        polylines = emptyList()
        polygons = emptyList()
    }

    private fun addLine(map: MapLibreMap, pts: List<Pair<Double, Double>>, color: Int, width: Float): Polyline? =
        runCatching {
            map.addPolyline(
                PolylineOptions().addAll(pts.map { LatLng(it.first, it.second) }).color(color).width(width),
            )
        }.getOrNull()

    private fun addPolygon(
        map: MapLibreMap, ring: List<Pair<Double, Double>>, color: Int, width: Float,
        outlineSink: MutableList<Polyline>,
    ): Polygon? {
        val pts = ring.map { LatLng(it.first, it.second) }
        val poly = runCatching {
            map.addPolygon(PolygonOptions().addAll(pts).fillColor(color).alpha(0.3f))
        }.getOrNull()
        // Crisp outline — fill alone reads weakly against the basemap.
        runCatching {
            map.addPolyline(PolylineOptions().addAll(pts).color(color).width(width))
        }.getOrNull()?.let { outlineSink.add(it) }
        return poly
    }

    /** Close a polygon ring (repeat the first vertex) if it isn't already closed. */
    internal fun closedRing(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        if (points.isEmpty()) return points
        return if (points.first() == points.last()) points else points + points.first()
    }

    /**
     * A drawn circle is stored as [center, edge]; approximate it as a
     * [CIRCLE_STEPS]-gon (the same approach DrawingLayer used for its fill).
     * Pure (no MapLibre types) so it unit-tests on the JVM.
     */
    internal fun circlePolygon(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        if (points.isEmpty()) return emptyList()
        val (lat0, lon0) = points.first()
        val (lat1, lon1) = points.getOrElse(1) { lat0 to lon0 }
        val radiusDeg = hypot(lat1 - lat0, lon1 - lon0)
        if (radiusDeg <= 0.0) return emptyList()
        val out = ArrayList<Pair<Double, Double>>(CIRCLE_STEPS + 1)
        for (i in 0..CIRCLE_STEPS) {
            val t = 2.0 * Math.PI * i / CIRCLE_STEPS
            out.add((lat0 + radiusDeg * sin(t)) to (lon0 + radiusDeg * cos(t)))
        }
        return out
    }
}
