package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import soy.engindearing.omnitak.mobile.domain.DittoWire

/**
 * Locks the cross-platform marker contract: the EXACT XML iOS's
 * MarkerCoTGenerator + CoTXMLBuilder emit for a quick-dropped hostile must
 * survive both halves of the Android receive path — DittoWire admission and
 * CoTParser ingestion. Found live 2026-08-31: PPLI and chat crossed the peer
 * mesh, iOS markers never appeared, and the mesh Received counter increments
 * even when CoTParser returns null, which makes a receiver-side parse
 * failure invisible in the UI.
 */
class CoTParserIosMarkerTest {

    private val iosMarkerXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <event version="2.0" uid="MARKER-3F2A9B71-8C44-4E1B-9DDA-51C0FFEE0001" type="a-h-G-U-C" time="2026-09-01T04:31:20Z" start="2026-09-01T04:31:20Z" stale="2026-09-01T05:31:20Z" how="h-g-i-g-o">
            <point lat="47.658781" lon="-117.426048" hae="0.0" ce="10.0" le="10.0"/>
            <detail>
                <contact callsign="HOS-5-2136"/>
                <usericon iconsetpath="COT_MAPPING_SPOTMAP/hostile_point"/>
                <color argb="-65536"/>
                <affiliation value="Hostile"/>
                <precisionlocation altsrc="GPS" geopointsrc="User"/>
                <status readiness="true"/>
                <_marker_>HOS</_marker_>
                <takv device="iPhone" platform="OmniTAK" os="iOS" version="2.41.0"/>
            </detail>
        </event>
    """.trimIndent()

    @Test
    fun dittoWireAdmitsIosMarker() {
        val meta = DittoWire.meta(iosMarkerXml, nowMs = 1_756_700_000_000L)
        assertNotNull("DittoWire.meta must accept the iOS marker envelope", meta)
        assertEquals("MARKER-3F2A9B71-8C44-4E1B-9DDA-51C0FFEE0001", meta!!.uid)
        assertEquals("a-h-G-U-C", meta.type)
    }

    @Test
    fun cotParserIngestsIosMarker() {
        val event = CoTParser.parse(iosMarkerXml)
        assertNotNull("CoTParser must parse the iOS marker XML", event)
        assertEquals("a-h-G-U-C", event!!.type)
        assertEquals("HOS-5-2136", event.callsign)
        assertEquals(47.658781, event.lat, 1e-6)
    }
}
