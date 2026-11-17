package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #215: the CoT `how` attribute reaches [CoTEvent.how], because the max-age rule tells a
 * teammate's own position report (`m-…`) from a marker a person placed (`h-…`) by it.
 * Raw XML goes through the production [CoTParser], the same path a TAK server's frames take.
 */
class CoTParserHowTest {

    private fun xml(uid: String, type: String, how: String?, detail: String = "") =
        """<event version="2.0" uid="$uid" type="$type" """ +
            (if (how != null) """how="$how" """ else "") +
            """time="2026-10-06T20:00:00.000Z" start="2026-10-06T20:00:00.000Z" stale="2026-10-06T20:02:00.000Z">""" +
            """<point lat="38.8899" lon="-77.0340" hae="10.0" ce="10.0" le="10.0"/>""" +
            """<detail>$detail</detail></event>"""

    @Test fun a_position_report_reads_how_m_g() {
        val event = CoTParser.parse(xml("ANDROID-x", "a-f-G-U-C", "m-g"))
        assertNotNull(event)
        assertEquals("m-g", event!!.how)
    }

    @Test fun a_placed_marker_reads_how_h_g_i_g_o() {
        val event = CoTParser.parse(xml("marker-1", "a-h-G", "h-g-i-g-o"))
        assertNotNull(event)
        assertEquals("h-g-i-g-o", event!!.how)
    }

    @Test fun an_event_without_how_has_none() {
        val event = CoTParser.parse(xml("ANDROID-x", "a-f-G-U-C", how = null))
        assertNotNull(event)
        assertNull(event!!.how)
    }

    @Test fun a_teammates_report_from_a_server_is_affected_and_a_placed_marker_is_not() {
        // The frame the stand-in TAK server sends, and one a teammate dropped on the map.
        val pli = CoTParser.parse(
            xml(
                "ANDROID-standin-bravo2", "a-f-G-U-C", "m-g",
                detail = """<contact callsign="BRAVO-2" endpoint="*:-1:stcp"/><__group name="Cyan" role="Team Member"/>""",
            ),
        )!!
        val hostile = CoTParser.parse(
            xml("0b6f2c1e-uuid", "a-h-G", "h-g-i-g-o", detail = """<contact callsign="Hostile 1"/>"""),
        )!!
        val friendlyMarker = CoTParser.parse(
            xml("5d2a9e44-uuid", "a-f-G-U-C", "h-g-i-g-o", detail = """<contact callsign="Rally"/>"""),
        )!!

        assertTrue(ContactMaxAge.appliesTo(pli, selfUid = "ANDROID-me"))
        assertFalse(ContactMaxAge.appliesTo(hostile, selfUid = "ANDROID-me"))
        assertFalse(ContactMaxAge.appliesTo(friendlyMarker, selfUid = "ANDROID-me"))
    }
}
