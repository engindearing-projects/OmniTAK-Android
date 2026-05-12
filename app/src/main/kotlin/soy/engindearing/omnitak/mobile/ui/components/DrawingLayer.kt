package soy.engindearing.omnitak.mobile.ui.components

import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.sources.GeoJsonSource
import soy.engindearing.omnitak.mobile.data.Drawing
import soy.engindearing.omnitak.mobile.data.DrawingCoT
import soy.engindearing.omnitak.mobile.data.DrawingKind
import kotlin.math.cos
import kotlin.math.sin

/**
 * Updates the persistent drawings source (`drawings-src`) defined
 * in the style JSON. Line / polygon / circle shapes are all emitted
 * into the same FeatureCollection; the layer stack applies per-kind
 * filters so each renders with its own paint.
 */
object DrawingLayer {
    const val SOURCE_ID = "drawings-src"

    fun update(map: MapLibreMap, drawings: List<Drawing>) {
        val style = map.style ?: return
        val src = style.getSourceAs<GeoJsonSource>(SOURCE_ID) ?: return
        src.setGeoJson(
            org.maplibre.geojson.FeatureCollection.fromJson(toFeatureCollection(drawings).toString())
        )
    }

    private fun toFeatureCollection(drawings: List<Drawing>): JSONObject {
        val features = JSONArray()
        drawings.forEach { d ->
            when (d.kind) {
                DrawingKind.LINE -> features.put(lineFeature(d))
                DrawingKind.POLYGON -> features.put(polygonFeature(d))
                DrawingKind.CIRCLE -> features.put(circleFeature(d))
                // == S3:bullseye BEGIN ==
                DrawingKind.BULLSEYE -> bullseyeFeatures(d).forEach { features.put(it) }
                // == S3:bullseye END ==
            }
        }
        return JSONObject()
            .put("type", "FeatureCollection")
            .put("features", features)
    }

    private fun lineFeature(d: Drawing): JSONObject {
        val coords = JSONArray()
        d.points.forEach { (lat, lon) -> coords.put(JSONArray().put(lon).put(lat)) }
        return baseFeature(d, "line")
            .put(
                "geometry",
                JSONObject().put("type", "LineString").put("coordinates", coords)
            )
    }

    private fun polygonFeature(d: Drawing): JSONObject {
        val coords = JSONArray()
        d.points.forEach { (lat, lon) -> coords.put(JSONArray().put(lon).put(lat)) }
        if (d.points.isNotEmpty()) {
            val (lat0, lon0) = d.points.first()
            coords.put(JSONArray().put(lon0).put(lat0))
        }
        val ring = JSONArray().put(coords)
        return baseFeature(d, "polygon")
            .put(
                "geometry",
                JSONObject().put("type", "Polygon").put("coordinates", ring)
            )
    }

    /**
     * CoT circles are stored as [center, edge]. We approximate the
     * drawn circle as a 64-sided polygon so MapLibre can render it
     * with the standard fill layer.
     */
    private fun circleFeature(d: Drawing): JSONObject {
        val (lat0, lon0) = d.points.first()
        val (lat1, lon1) = d.points.getOrElse(1) { lat0 to lon0 }
        val radiusDeg = kotlin.math.hypot(lat1 - lat0, lon1 - lon0)
        val coords = JSONArray()
        val steps = 64
        for (i in 0..steps) {
            val t = 2.0 * Math.PI * i / steps
            val lon = lon0 + radiusDeg * cos(t)
            val lat = lat0 + radiusDeg * sin(t)
            coords.put(JSONArray().put(lon).put(lat))
        }
        val ring = JSONArray().put(coords)
        return baseFeature(d, "polygon")
            .put(
                "geometry",
                JSONObject().put("type", "Polygon").put("coordinates", ring)
            )
    }

    // == S3:bullseye BEGIN ==
    /**
     * Render a bullseye as one polygon-feature per concentric ring.
     * `points` holds only the centre; radii come from `radiiM` (defaults
     * to the SAR-standard 100 m / 500 m / 1 km set). Metres → degrees
     * uses the same coarse `1° lat ≈ 111 km` approximation [circleFeature]
     * already relies on — accurate enough at the zoom levels SAR
     * operators draw at.
     */
    private fun bullseyeFeatures(d: Drawing): List<JSONObject> {
        val (lat0, lon0) = d.points.firstOrNull() ?: return emptyList()
        val radii = (d.radiiM ?: DrawingCoT.DEFAULT_BULLSEYE_RADII_M)
            .filter { it > 0.0 }
            .sorted()
        if (radii.isEmpty()) return emptyList()
        val metersPerDegLat = 111_000.0
        val cosLat = cos(Math.toRadians(lat0))
        return radii.map { rM ->
            val rLatDeg = rM / metersPerDegLat
            val rLonDeg = rM / (metersPerDegLat * if (cosLat == 0.0) 1.0 else cosLat)
            val coords = JSONArray()
            val steps = 64
            for (i in 0..steps) {
                val t = 2.0 * Math.PI * i / steps
                val lon = lon0 + rLonDeg * cos(t)
                val lat = lat0 + rLatDeg * sin(t)
                coords.put(JSONArray().put(lon).put(lat))
            }
            val ring = JSONArray().put(coords)
            baseFeature(d, "polygon").put(
                "geometry",
                JSONObject().put("type", "Polygon").put("coordinates", ring),
            )
        }
    }
    // == S3:bullseye END ==

    private fun baseFeature(d: Drawing, kind: String): JSONObject = JSONObject()
        .put("type", "Feature")
        .put(
            "properties",
            JSONObject()
                .put("id", d.id)
                .put("kind", kind)
                .put("color", d.colorHex)
                .put("name", d.name)
                .put("width", d.widthPx)
        )
}
