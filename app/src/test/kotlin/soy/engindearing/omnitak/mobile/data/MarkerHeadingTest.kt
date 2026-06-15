package soy.engindearing.omnitak.mobile.data

import org.junit.Test
import org.junit.Assert.*
import soy.engindearing.omnitak.mobile.ui.components.MarkerEditResult
import soy.engindearing.omnitak.mobile.data.CoTAffiliation

class MarkerHeadingTest {

    @Test
    fun `CoTEvent has null courseHeading by default`() {
        val event = CoTEvent(
            uid = "test-uid",
            type = "a-f-G",
            lat = 47.0,
            lon = -122.0,
            hae = 0.0,
            ce = 9999.0,
            le = 9999.0,
            timeIso = "2026-01-01T00:00:00Z",
            staleIso = "2026-01-01T00:05:00Z",
            callsign = "TEST"
        )
        assertNull(event.courseHeading)
    }

    @Test
    fun `CoTEvent stores courseHeading correctly`() {
        val event = CoTEvent(
            uid = "test-uid",
            type = "a-f-G",
            lat = 47.0,
            lon = -122.0,
            hae = 0.0,
            ce = 9999.0,
            le = 9999.0,
            timeIso = "2026-01-01T00:00:00Z",
            staleIso = "2026-01-01T00:05:00Z",
            callsign = "TEST",
            courseHeading = 270.0
        )
        assertEquals(270.0, event.courseHeading!!, 0.0)
    }

    @Test
    fun `MarkerEditResult has null courseHeading by default`() {
        val result = MarkerEditResult(
            callsign = "TEST",
            affiliation = CoTAffiliation.FRIEND,
            altitudeMeters = null,
            remarks = "",
            cotType = "a-f-G",
            iconsetPath = null,
            argbHex = null
        )
        assertNull(result.courseHeading)
    }

    @Test
    fun `CoTParser parses track course from XML`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<event version="2.0" uid="ANDROID-test" type="a-f-G" time="2026-01-01T00:00:00Z" start="2026-01-01T00:00:00Z" stale="2026-01-01T00:05:00Z" how="h-e">
  <point lat="47.0" lon="-122.0" hae="0.0" ce="9999.0" le="9999.0"/>
  <detail>
    <contact callsign="TEST"/>
    <track course="270.0" speed="0.0"/>
  </detail>
</event>"""
        val event = CoTParser.parse(xml)
        assertNotNull(event)
        assertEquals(270.0, event!!.courseHeading!!, 0.0)
    }

    @Test
    fun `CoTParser returns null courseHeading when track element absent`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<event version="2.0" uid="ANDROID-test" type="a-f-G" time="2026-01-01T00:00:00Z" start="2026-01-01T00:00:00Z" stale="2026-01-01T00:05:00Z" how="h-e">
  <point lat="47.0" lon="-122.0" hae="0.0" ce="9999.0" le="9999.0"/>
  <detail>
    <contact callsign="TEST"/>
  </detail>
</event>"""
        val event = CoTParser.parse(xml)
        assertNotNull(event)
        assertNull(event!!.courseHeading)
    }
}
