package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.KmlVectorOverlay

/**
 * Locks the GeoJSON line/polygon parse that feeds the #80-class native KML
 * shape fix. KML routes (LineString) and areas (Polygon) were invisible on the
 * 2D map because the GeoJsonSource Fill/Line layers do not paint on
 * Adreno/Mali/emulator GL; KmlShapeRenderer draws them as native polyline /
 * polygon annotations instead, and this parses the coordinates it renders.
 */
class KmlShapeRendererTest {

    @Test fun parses_linestring_coordinates_as_lat_lon_pairs() {
        val gj = """{"type":"FeatureCollection","features":[
          {"type":"Feature","properties":{},"geometry":{"type":"LineString",
            "coordinates":[[120.985,23.700],[120.995,23.715],[121.020,23.725]]}}]}"""
        val s = KmlShapeRenderer.parseShapes(gj)
        assertEquals(1, s.lines.size)
        assertEquals(0, s.polygons.size)
        assertEquals(3, s.lines[0].size)
        // GeoJSON is [lon,lat]; we render LatLng, so output must be (lat, lon).
        assertEquals(23.700, s.lines[0][0].first, 1e-9)
        assertEquals(120.985, s.lines[0][0].second, 1e-9)
    }

    @Test fun parses_polygon_outer_ring_only() {
        val gj = """{"type":"FeatureCollection","features":[
          {"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":[
            [[120.99,23.69],[121.01,23.69],[121.01,23.70],[120.99,23.70],[120.99,23.69]],
            [[120.995,23.693],[121.0,23.693],[120.995,23.697],[120.995,23.693]]
          ]}}]}"""
        val s = KmlShapeRenderer.parseShapes(gj)
        assertEquals(0, s.lines.size)
        assertEquals(1, s.polygons.size)
        // Outer ring kept (5 verts); the hole (second ring) is ignored.
        assertEquals(5, s.polygons[0].size)
        assertEquals(23.69, s.polygons[0][0].first, 1e-9)
    }

    @Test fun mixed_collection_separates_lines_and_polygons() {
        val gj = """{"type":"FeatureCollection","features":[
          {"type":"Feature","geometry":{"type":"LineString","coordinates":[[120.9,23.7],[121.0,23.8]]}},
          {"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[120.9,23.6],[121.0,23.6],[121.0,23.7],[120.9,23.6]]]}},
          {"type":"Feature","geometry":{"type":"Point","coordinates":[120.95,23.65]}}
        ]}"""
        val s = KmlShapeRenderer.parseShapes(gj)
        assertEquals("points are handled by KmlMarkerRenderer, not here", 1, s.lines.size)
        assertEquals(1, s.polygons.size)
    }

    @Test fun malformed_geojson_yields_empty_not_crash() {
        val s = KmlShapeRenderer.parseShapes("not json")
        assertTrue(s.lines.isEmpty() && s.polygons.isEmpty())
    }

    // The map view is kept across visits to other tabs, and the map screen asks
    // for its KML shapes again every time it is composed. Shapes that are
    // already on that map must not be read from disk and parsed again.

    private fun overlay(id: String, visible: Boolean = true, colorHex: String = "#FF00FF") = KmlVectorOverlay(
        id = id, name = id, fileName = "$id.geojson", colorHex = colorHex, visible = visible,
        featureCount = 1, minLat = 0.0, minLon = 0.0, maxLat = 1.0, maxLon = 1.0,
    )

    @Test fun the_same_overlays_on_the_same_map_are_not_drawn_again() {
        val shown = listOf(overlay("a"), overlay("b"))
        assertFalse(KmlShapeRenderer.needsApply(sameMap = true, applied = shown, wanted = listOf(overlay("a"), overlay("b"))))
        assertFalse("nothing shown, nothing wanted", KmlShapeRenderer.needsApply(true, emptyList(), emptyList()))
    }

    @Test fun anything_that_changes_what_is_drawn_draws_again() {
        val shown = listOf(overlay("a"), overlay("b"))
        assertTrue("an overlay added", KmlShapeRenderer.needsApply(true, shown, shown + overlay("c")))
        assertTrue("an overlay removed", KmlShapeRenderer.needsApply(true, shown, listOf(overlay("a"))))
        assertTrue("an overlay hidden", KmlShapeRenderer.needsApply(true, shown, listOf(overlay("a"), overlay("b", visible = false))))
        assertTrue("a colour changed", KmlShapeRenderer.needsApply(true, shown, listOf(overlay("a"), overlay("b", colorHex = "#00FF00"))))
        assertTrue("a different order is a different draw order", KmlShapeRenderer.needsApply(true, shown, shown.reversed()))
    }

    @Test fun a_different_map_or_a_first_call_always_draws() {
        val shown = listOf(overlay("a"))
        assertTrue("another map instance has none of these shapes", KmlShapeRenderer.needsApply(sameMap = false, applied = shown, wanted = shown))
        assertTrue("nothing applied yet", KmlShapeRenderer.needsApply(sameMap = true, applied = null, wanted = shown))
        assertTrue(KmlShapeRenderer.needsApply(sameMap = true, applied = null, wanted = emptyList()))
    }
}
