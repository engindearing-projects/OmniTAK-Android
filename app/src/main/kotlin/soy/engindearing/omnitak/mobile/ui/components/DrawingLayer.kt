package soy.engindearing.omnitak.mobile.ui.components

import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.maps.MapLibreMap
import soy.engindearing.omnitak.mobile.data.Drawing
import soy.engindearing.omnitak.mobile.data.DrawingKind

/**
 * Updates the persistent drawings source (`drawings-src`) defined
 * in the style JSON. Line / polygon / circle shapes are all emitted
 * into the same FeatureCollection; the layer stack applies per-kind
 * filters so each renders with its own paint.
 */
object DrawingLayer {
    const val SOURCE_ID = "drawings-src"

    fun update(map: MapLibreMap, drawings: List<Drawing>) {
        GeoJsonLayerFeeder.push(map, SOURCE_ID, toFeatures(drawings))
    }

    private fun toFeatures(drawings: List<Drawing>): JSONArray {
        val features = JSONArray()
        drawings.forEach { d ->
            when (d.kind) {
                DrawingKind.LINE -> features.put(lineFeature(d))
                DrawingKind.POLYGON -> features.put(polygonFeature(d))
                DrawingKind.CIRCLE -> circleFeature(d)?.let { features.put(it) }
            }
        }
        return features
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
     * CoT circles are stored as [center, edge]. We approximate the drawn circle as a 64-sided polygon so
     * MapLibre can render it with the standard fill layer: the same ring [DrawingShapeRenderer] draws, a
     * circle on the ground. Null for a circle with no size.
     */
    private fun circleFeature(d: Drawing): JSONObject? {
        val coords = JSONArray()
        DrawingShapeRenderer.circlePolygon(d.points).forEach { (lat, lon) -> coords.put(JSONArray().put(lon).put(lat)) }
        if (coords.length() == 0) return null
        val ring = JSONArray().put(coords)
        return baseFeature(d, "polygon")
            .put(
                "geometry",
                JSONObject().put("type", "Polygon").put("coordinates", ring)
            )
    }

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
