package soy.engindearing.omnitak.mobile.ui.components

import org.json.JSONArray
import org.json.JSONObject
import soy.engindearing.omnitak.mobile.data.Drawing
import soy.engindearing.omnitak.mobile.data.DrawingKind

/**
 * Issue #79 — build the drawings payload `window.OmniBridge.setDrawings(...)`
 * consumes on the Cesium globe. The JS bridge (cesium_scene.html
 * `upsertDrawing` / `setDrawings`) already renders line / polygon / circle
 * entities; this is the Kotlin side that was missing, so operator drawings
 * made on the 2D engine now also appear on the 3D globe (engine parity —
 * the documented "wired into one engine, missing from the other" bug class).
 *
 * Coordinate order is [lon, lat] to match `Cesium.Cartesian3.fromDegrees`,
 * which the bridge calls as `fromDegrees(c[0], c[1], 0)`.
 */
internal fun buildCesiumDrawingsJson(drawings: List<Drawing>): String {
    val arr = JSONArray()
    for (d in drawings) {
        if (d.points.isEmpty()) continue
        val coords = JSONArray()
        d.points.forEach { (lat, lon) ->
            coords.put(JSONArray().put(lon).put(lat))
        }
        arr.put(
            JSONObject().apply {
                put("uid", d.id)
                put("kind", d.kind.jsKind)
                put("coords", coords)
                put("color", d.colorHex)
                put("width", d.widthPx.toDouble())
            },
        )
    }
    return arr.toString()
}

/** Lower-case kind string the cesium_scene.html bridge switches on. */
private val DrawingKind.jsKind: String
    get() = when (this) {
        DrawingKind.LINE -> "line"
        DrawingKind.POLYGON -> "polygon"
        DrawingKind.CIRCLE -> "circle"
    }
