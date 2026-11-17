package soy.engindearing.omnitak.mobile.ui.components

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.Drawing
import soy.engindearing.omnitak.mobile.data.DrawingKind
import soy.engindearing.omnitak.mobile.data.geofence.Geofence
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceDrawings
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceType

/**
 * Issue #79 — the Kotlin → Cesium drawings bridge payload. cesium_scene.html's
 * `upsertDrawing` consumes `{uid, kind, coords:[[lon,lat]...], color, width}`
 * with coords in [lon, lat] order (it calls Cesium.Cartesian3.fromDegrees(
 * c[0], c[1], 0)).
 */
class CesiumDrawingsJsonTest {

    private fun parse(json: String): JSONArray = JSONArray(json)

    @Test fun `line emits lon-lat coords in order`() {
        val line = Drawing(
            id = "L1",
            kind = DrawingKind.LINE,
            points = listOf(47.65 to -117.42, 47.66 to -117.40),
            colorHex = "#FFC107",
            widthPx = 4f,
        )
        val arr = parse(buildCesiumDrawingsJson(listOf(line)))
        assertEquals(1, arr.length())
        val o = arr.getJSONObject(0)
        assertEquals("L1", o.getString("uid"))
        assertEquals("line", o.getString("kind"))
        assertEquals("#FFC107", o.getString("color"))
        assertEquals(4.0, o.getDouble("width"), 1e-9)
        val coords = o.getJSONArray("coords")
        assertEquals(2, coords.length())
        // [lon, lat] order.
        assertEquals(-117.42, coords.getJSONArray(0).getDouble(0), 1e-9)
        assertEquals(47.65, coords.getJSONArray(0).getDouble(1), 1e-9)
    }

    @Test fun `polygon kind maps to polygon`() {
        val poly = Drawing(
            id = "P1",
            kind = DrawingKind.POLYGON,
            points = listOf(0.0 to 0.0, 0.0 to 1.0, 1.0 to 1.0),
        )
        val o = parse(buildCesiumDrawingsJson(listOf(poly))).getJSONObject(0)
        assertEquals("polygon", o.getString("kind"))
        assertEquals(3, o.getJSONArray("coords").length())
    }

    @Test fun `circle kind maps to circle with center and edge`() {
        val circle = Drawing(
            id = "C1",
            kind = DrawingKind.CIRCLE,
            points = listOf(47.65 to -117.42, 47.70 to -117.42),
        )
        val o = parse(buildCesiumDrawingsJson(listOf(circle))).getJSONObject(0)
        assertEquals("circle", o.getString("kind"))
        assertEquals(2, o.getJSONArray("coords").length())
    }

    @Test fun `empty-point drawings are skipped`() {
        val empty = Drawing(id = "E", kind = DrawingKind.LINE, points = emptyList())
        val good = Drawing(id = "G", kind = DrawingKind.LINE, points = listOf(1.0 to 2.0, 3.0 to 4.0))
        val arr = parse(buildCesiumDrawingsJson(listOf(empty, good)))
        assertEquals(1, arr.length())
        assertEquals("G", arr.getJSONObject(0).getString("uid"))
    }

    @Test fun `empty list yields empty json array`() {
        assertEquals("[]", buildCesiumDrawingsJson(emptyList()))
    }

    @Test fun `default color is carried through`() {
        val d = Drawing(id = "D", kind = DrawingKind.LINE, points = listOf(0.0 to 0.0, 1.0 to 1.0))
        val o = parse(buildCesiumDrawingsJson(listOf(d))).getJSONObject(0)
        // Drawing's default colorHex.
        assertTrue(o.getString("color").startsWith("#"))
    }

    // ---- #155: the geofences ride the same payload

    @Test fun `an operators own drawing carries no fill or name over the bridge`() {
        val d = Drawing(id = "draw-1", kind = DrawingKind.POLYGON, points = listOf(0.0 to 0.0, 0.0 to 1.0, 1.0 to 1.0))
        val o = parse(buildCesiumDrawingsJson(listOf(d))).getJSONObject(0)
        assertTrue("the page's own 25 percent stays as it was", !o.has("fillAlpha"))
        assertTrue(!o.has("label"))
        assertTrue(!o.has("labelAt"))
    }

    @Test fun `a geofence goes over the bridge as a polygon with its light fill and its name at lon-lat`() {
        val fence = Geofence(
            id = "g1", name = "North gate", type = GeofenceType.CIRCLE,
            centerLat = 47.6588, centerLon = -117.4260, radiusMeters = 200.0,
        )
        val arr = parse(buildCesiumDrawingsJson(GeofenceDrawings.from(listOf(fence))))
        assertEquals("the casing line and the shape", 2, arr.length())
        val casing = arr.getJSONObject(0)
        val shape = arr.getJSONObject(1)
        assertEquals("line", casing.getString("kind"))
        assertTrue(!casing.has("label"))
        assertEquals("__geofence_g1__", shape.getString("uid"))
        assertEquals("polygon", shape.getString("kind"))
        assertEquals(0.12, shape.getDouble("fillAlpha"), 1e-6)
        assertEquals("North gate", shape.getString("label"))
        val at = shape.getJSONArray("labelAt")
        assertEquals(-117.4260, at.getDouble(0), 1e-9)
        assertEquals(47.6588, at.getDouble(1), 1e-9)
        assertEquals(64, shape.getJSONArray("coords").length())
    }
}
