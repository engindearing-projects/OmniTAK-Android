package soy.engindearing.omnitak.mobile.domain.tracking

import org.junit.Assert.assertTrue
import org.junit.Test

class TrackGpxExporterTest {

    private val t0 = 1_700_000_000_000L

    @Test fun emits_gpx_envelope_with_trkseg() {
        val track = Track(
            name = "Search leg 4",
            startedMs = t0,
            endedMs = t0 + 60_000L,
            points = listOf(
                TrackPoint(47.0, -122.0, 12.0, t0),
                TrackPoint(47.0001, -122.0001, 12.5, t0 + 10_000L),
            ),
            totalDistanceM = 15.0,
        )
        val gpx = TrackGpxExporter.export(track)
        assertTrue(gpx.startsWith("<?xml"))
        assertTrue(gpx.contains("<gpx version=\"1.1\""))
        assertTrue(gpx.contains("<trk>"))
        assertTrue(gpx.contains("<name>Search leg 4</name>"))
        assertTrue(gpx.contains("<trkseg>"))
        assertTrue(gpx.contains("lat=\"47.0\""))
        assertTrue(gpx.contains("lon=\"-122.0\""))
        assertTrue(gpx.contains("</gpx>"))
    }

    @Test fun emits_notes_as_desc_when_present() {
        val track = Track(
            name = "leg",
            startedMs = t0,
            points = emptyList(),
            notes = "wind shift mid-search",
        )
        val gpx = TrackGpxExporter.export(track)
        assertTrue(gpx.contains("<desc>wind shift mid-search</desc>"))
    }
}
