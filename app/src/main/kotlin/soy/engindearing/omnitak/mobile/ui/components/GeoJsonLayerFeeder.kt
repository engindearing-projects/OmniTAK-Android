package soy.engindearing.omnitak.mobile.ui.components

import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * The one way OmniTAK pushes a FeatureCollection into a MapLibre
 * GeoJsonSource. Six layer feeders used to hand-build the envelope and
 * call divergent setGeoJson overloads — four of them on the
 * `FeatureCollection.fromJson` overload whose Java-layer null-features
 * guard can silently no-op (the hazard ContactLayer documented but only
 * two copies had applied).
 *
 * Always uses the String overload: it goes straight to
 * nativeSetGeoJsonString, bypassing the FeatureCollection guard.
 */
object GeoJsonLayerFeeder {

    private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""

    /** Wrap [features] in a FeatureCollection envelope and push it.
     *  Returns false when the style/source isn't ready (caller may log). */
    fun push(map: MapLibreMap, sourceId: String, features: JSONArray): Boolean {
        val style = map.style ?: return false
        val ok = push(style, sourceId, features)
        // Issues #77 / #80 — force a GL frame after updating source data.
        // On some Mali/Adreno drivers (reported on Pixel 9 Pro v0.35.5)
        // GeoJsonSource.setGeoJson on a source whose layer is baked into the
        // style JSON does NOT schedule a repaint, so a dropped marker / new
        // drawing stays invisible until a full style reload happens to repaint
        // (e.g. toggling 3D Terrain). triggerRepaint forces the frame so the
        // layer paints immediately, with no reload needed. It's a no-op when
        // the map is already drawing, so it's safe on every push.
        if (ok) map.triggerRepaint()
        return ok
    }

    /** Same as [push] for callers that already hold the [Style]. */
    fun push(style: Style, sourceId: String, features: JSONArray): Boolean {
        val src = style.getSourceAs<GeoJsonSource>(sourceId) ?: return false
        src.setGeoJson(
            JSONObject()
                .put("type", "FeatureCollection")
                .put("features", features)
                .toString()
        )
        return true
    }

    /** Reset the source to an empty FeatureCollection. */
    fun clear(map: MapLibreMap, sourceId: String) {
        val src = map.style?.getSourceAs<GeoJsonSource>(sourceId) ?: return
        src.setGeoJson(EMPTY)
    }
}
