package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertTrue
import org.junit.Test

class MissionKmlExporterTest {

    @Test fun emits_kml_envelope_with_mission_name() {
        val xml = MissionKmlExporter.export(
            markers = emptyMap(),
            drawings = emptyList(),
            missionName = "Hasty 1",
            nowMs = 1_700_000_000_000L,
        )
        assertTrue(xml.startsWith("<?xml"))
        assertTrue(xml.contains("<kml"))
        assertTrue(xml.contains("<name>Hasty 1</name>"))
        assertTrue(xml.contains("</kml>"))
    }

    @Test fun marker_becomes_placemark_with_point() {
        val xml = MissionKmlExporter.export(
            markers = mapOf(
                "u1" to CoTEvent(
                    uid = "u1", type = "a-f-G-U-C",
                    lat = 47.0, lon = -122.0, hae = 12.0,
                    callsign = "Searcher-3", remarks = "fresh boot prints",
                ),
            ),
            drawings = emptyList(),
        )
        assertTrue(xml.contains("<Placemark>"))
        assertTrue(xml.contains("<name>Searcher-3</name>"))
        assertTrue(xml.contains("<description>fresh boot prints</description>"))
        assertTrue(xml.contains("-122.0,47.0,12.0"))
        assertTrue(xml.contains("#friend"))
    }

    @Test fun circle_drawing_becomes_polygon_approximation() {
        val xml = MissionKmlExporter.export(
            markers = emptyMap(),
            drawings = listOf(
                Drawing(
                    id = "d1",
                    kind = DrawingKind.CIRCLE,
                    name = "IC ring",
                    points = listOf(47.0 to -122.0, 47.001 to -122.0),
                ),
            ),
        )
        assertTrue(xml.contains("<Polygon>"))
        assertTrue(xml.contains("IC ring"))
        // 65 vertices (0..64 inclusive) → 65 comma-separated lon,lat,0 triples
        val polyContents = xml.substringAfter("<coordinates>").substringBefore("</coordinates>")
        val triples = polyContents.trim().split(Regex("\\s+"))
        assertTrue("expected ≥65 vertices, got ${triples.size}", triples.size >= 65)
    }

    @Test fun line_drawing_becomes_linestring() {
        val xml = MissionKmlExporter.export(
            markers = emptyMap(),
            drawings = listOf(
                Drawing(
                    id = "d2", kind = DrawingKind.LINE, name = "Search leg",
                    points = listOf(47.0 to -122.0, 47.0 to -122.01, 47.001 to -122.01),
                ),
            ),
        )
        assertTrue(xml.contains("<LineString>"))
        assertTrue(xml.contains("Search leg"))
    }
}
