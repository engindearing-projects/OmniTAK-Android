package soy.engindearing.omnitak.mobile.ui.components

import android.graphics.Color
import org.maplibre.android.annotations.Polygon
import org.maplibre.android.annotations.PolygonOptions
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import soy.engindearing.omnitak.mobile.data.KmlVectorOverlay
import soy.engindearing.omnitak.mobile.data.KmlVectorOverlayStore

/**
 * Renders KML LineString / Polygon geometry as native MapLibre annotations
 * (addPolyline / addPolygon) instead of GeoJsonSource Line/Fill layers.
 *
 * Same root cause as the KML point + contact marker fixes: the GeoJsonSource →
 * Line/Fill GL pipeline silently fails to paint on Adreno/Mali/the emulator GL
 * translator, so imported KML routes and areas were invisible on the 2D map
 * (confirmed on-device) even though the source carried the features. The
 * Annotation API uses the same native renderer as LocationComponent and paints
 * across those drivers (see project_omnitak_android_marker_gpu_bug;
 * KmlMarkerRenderer does the point half).
 *
 * Polygons get a translucent fill plus a crisp full-opacity outline ring (one
 * polyline) so the boundary reads clearly, mirroring the old fill+line styling.
 * Shapes are recomputed whenever the overlay set changes; annotations survive
 * style reloads, so no per-reload re-add is needed.
 */
object KmlShapeRenderer {
    private var polylines: List<Polyline> = emptyList()
    private var polygons: List<Polygon> = emptyList()
    private var boundMap: MapLibreMap? = null

    fun apply(map: MapLibreMap, overlays: List<KmlVectorOverlay>, store: KmlVectorOverlayStore) {
        boundMap = map
        clearAnnotations(map)
        val newLines = ArrayList<Polyline>()
        val newPolys = ArrayList<Polygon>()
        for (overlay in overlays) {
            if (!overlay.visible) continue
            val color = runCatching { Color.parseColor(overlay.colorHex) }.getOrDefault(Color.MAGENTA)
            val width = (overlay.lineWidth * 3f).coerceIn(2f, 14f)
            val shapes = parseShapes(runCatching { store.fileFor(overlay).readText() }.getOrDefault(""))
            for (line in shapes.lines) {
                if (line.size < 2) continue
                runCatching {
                    map.addPolyline(
                        PolylineOptions().addAll(line.map { LatLng(it.first, it.second) })
                            .color(color).width(width).alpha(overlay.opacity),
                    )
                }.getOrNull()?.let { newLines.add(it) }
            }
            for (ring in shapes.polygons) {
                if (ring.size < 3) continue
                val pts = ring.map { LatLng(it.first, it.second) }
                runCatching {
                    map.addPolygon(
                        PolygonOptions().addAll(pts).fillColor(color).alpha(overlay.opacity * 0.3f),
                    )
                }.getOrNull()?.let { newPolys.add(it) }
                // Crisp outline ring — fill alone reads weakly at a distance.
                runCatching {
                    map.addPolyline(
                        PolylineOptions().addAll(pts).color(color).width(width).alpha(overlay.opacity),
                    )
                }.getOrNull()?.let { newLines.add(it) }
            }
        }
        polylines = newLines
        polygons = newPolys
    }

    fun clear(map: MapLibreMap) {
        clearAnnotations(map)
        boundMap = null
    }

    private fun clearAnnotations(map: MapLibreMap) {
        polylines.forEach { runCatching { map.removePolyline(it) } }
        polygons.forEach { runCatching { map.removePolygon(it) } }
        polylines = emptyList()
        polygons = emptyList()
    }

    /** Parsed line + polygon-ring coordinate lists, as (lat, lon) pairs. */
    internal data class Shapes(
        val lines: List<List<Pair<Double, Double>>>,
        val polygons: List<List<Pair<Double, Double>>>,
    )

    /**
     * Pull LineString + Polygon (outer ring) geometry out of a GeoJSON
     * FeatureCollection string. Pure (org.json only) so it unit-tests on the
     * JVM. Polygon holes are ignored — outer ring is enough for KML areas.
     */
    internal fun parseShapes(geoJson: String): Shapes {
        val lines = ArrayList<List<Pair<Double, Double>>>()
        val polys = ArrayList<List<Pair<Double, Double>>>()
        runCatching {
            val feats = org.json.JSONObject(geoJson).optJSONArray("features") ?: return Shapes(lines, polys)
            for (i in 0 until feats.length()) {
                val geom = feats.optJSONObject(i)?.optJSONObject("geometry") ?: continue
                when (geom.optString("type")) {
                    "LineString" -> coordsOf(geom.optJSONArray("coordinates"))?.let { lines.add(it) }
                    "Polygon" -> {
                        val rings = geom.optJSONArray("coordinates") ?: continue
                        coordsOf(rings.optJSONArray(0))?.let { polys.add(it) }
                    }
                }
            }
        }
        return Shapes(lines, polys)
    }

    /** Convert a GeoJSON [[lon,lat],…] array into (lat, lon) pairs. */
    private fun coordsOf(arr: org.json.JSONArray?): List<Pair<Double, Double>>? {
        if (arr == null) return null
        val out = ArrayList<Pair<Double, Double>>(arr.length())
        for (j in 0 until arr.length()) {
            val c = arr.optJSONArray(j) ?: continue
            if (c.length() < 2) continue
            val lon = c.optDouble(0, Double.NaN)
            val lat = c.optDouble(1, Double.NaN)
            if (!lat.isNaN() && !lon.isNaN()) out.add(lat to lon)
        }
        return if (out.isEmpty()) null else out
    }
}
