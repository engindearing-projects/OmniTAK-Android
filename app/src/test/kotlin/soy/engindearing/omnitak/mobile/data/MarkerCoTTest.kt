package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkerCoTTest {

    @Test fun timestamped_callsign_is_my_pos_with_HHmm() {
        // 2026-05-11T14:32:00 UTC
        val ts = 1778516000000L
        val cs = MarkerCoT.timestampedCallsign(ts)
        assertTrue("expected MyPos-XXXX, got $cs", cs.startsWith("MyPos-"))
        assertEquals(10, cs.length)
    }

    @Test fun build_emits_required_event_attributes() {
        val xml = MarkerCoT.build(
            uid = "local-1",
            callsign = "MyPos-1432",
            type = "a-f-G-U-C",
            lat = 47.6062,
            lon = -122.3321,
        )
        assertTrue("event tag", xml.contains("<event "))
        assertTrue("uid", xml.contains("uid=\"local-1\""))
        assertTrue("type", xml.contains("type=\"a-f-G-U-C\""))
        assertTrue("lat", xml.contains("lat=\"47.6062\""))
        assertTrue("lon", xml.contains("lon=\"-122.3321\""))
        assertTrue("callsign", xml.contains("callsign=\"MyPos-1432\""))
    }

    @Test fun escapes_xml_metacharacters_in_callsign() {
        val xml = MarkerCoT.build(
            uid = "u",
            callsign = "<test&>",
            type = "a-n-G",
            lat = 0.0, lon = 0.0,
        )
        assertTrue(xml.contains("&lt;test&amp;&gt;"))
    }
}
