package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
