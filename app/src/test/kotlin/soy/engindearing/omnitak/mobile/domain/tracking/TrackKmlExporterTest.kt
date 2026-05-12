package soy.engindearing.omnitak.mobile.domain.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackKmlExporterTest {

    private val t0 = 1_700_000_000_000L

    @Test fun emits_kml_envelope_with_track_name() {
        val track = Track(
            name = "Hasty 1",
            startedMs = t0,
            endedMs = t0 + 60_000L,
            points = listOf(
                TrackPoint(47.0, -122.0, 12.0, t0),
                TrackPoint(47.0001, -122.0001, 12.5, t0 + 10_000L),
            ),
            totalDistanceM = 15.0,
        )
        val xml = TrackKmlExporter.export(track)
        assertTrue(xml.startsWith("<?xml"))
        assertTrue(xml.contains("<kml"))
        assertTrue(xml.contains("<name>Hasty 1</name>"))
        assertTrue(xml.contains("<LineString>"))
        assertTrue(xml.contains("-122.0,47.0,12.0"))
        assertTrue(xml.contains("</kml>"))
    }

    @Test fun escapes_xml_unsafe_characters_in_name() {
        val track = Track(
            name = "Search & rescue <go>",
            startedMs = t0,
            points = emptyList(),
        )
        val xml = TrackKmlExporter.export(track)
        assertTrue(xml.contains("Search &amp; rescue &lt;go&gt;"))
    }

    @Test fun hex_color_converts_to_kml_byte_order() {
        // #80de4a → ff4ade80 (aabbggrr; alpha=ff, blue=4a, green=de, red=80)
        assertEquals("ff4ade80", TrackKmlExporter.hexToKmlColor("#80de4a"))
    }

    @Test fun invalid_hex_falls_back_to_default_green() {
        assertEquals("ff4ade80", TrackKmlExporter.hexToKmlColor("bogus"))
    }
}
