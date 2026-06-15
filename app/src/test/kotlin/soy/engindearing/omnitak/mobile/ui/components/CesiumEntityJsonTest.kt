package soy.engindearing.omnitak.mobile.ui.components

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.CoTAffiliation
import soy.engindearing.omnitak.mobile.data.CoTEvent

class CesiumEntityJsonTest {

    private fun parse(json: String): JSONArray = JSONArray(json)

    @Test
    fun `self entity has kind self and no sidc`() {
        val json = parse(buildCesiumEntitiesJson(
            contacts = emptyList(),
            selfLat = 47.65,
            selfLon = -117.42,
            selfCallsign = "OMNI-1",
        ))
        assertEquals(1, json.length())
        val self = json.getJSONObject(0)
        assertEquals("__self__", self.getString("uid"))
        assertEquals("self", self.getString("kind"))
        assertFalse("self should NOT carry a sidc (uses affiliation frame)", self.has("sidc"))
    }

    @Test
    fun `self entity is not a triangle by default`() {
        // #83 — without the triangle pref the self entity keeps the friendly
        // affiliation frame (no triangle flag / heading), so existing globe
        // users see no behaviour change.
        val json = parse(buildCesiumEntitiesJson(
            contacts = emptyList(),
            selfLat = 47.65, selfLon = -117.42, selfCallsign = "OMNI-1",
        ))
        val self = json.getJSONObject(0)
        assertFalse("no triangle flag when pref off", self.has("triangle"))
        assertFalse("no heading when pref off", self.has("heading"))
    }

    @Test
    fun `self entity carries triangle flag and heading when triangle style on`() {
        // #83 — triangle self-marker parity with the 2D puck: the JS side draws
        // a heading-rotated triangle when `triangle` is set, rotating by `heading`.
        val json = parse(buildCesiumEntitiesJson(
            contacts = emptyList(),
            selfLat = 47.65, selfLon = -117.42, selfCallsign = "OMNI-1",
            selfTriangle = true,
            selfHeadingDeg = 137.5f,
        ))
        val self = json.getJSONObject(0)
        assertEquals("self", self.getString("kind"))
        assertTrue("triangle flag set", self.getBoolean("triangle"))
        assertEquals(137.5, self.getDouble("heading"), 0.001)
    }

    @Test
    fun `triangle self defaults heading to zero when device heading unknown`() {
        // null device heading (no magnetometer / first frame) → triangle points
        // north, matching a 2D puck with no compass fix yet.
        val json = parse(buildCesiumEntitiesJson(
            contacts = emptyList(),
            selfLat = 47.65, selfLon = -117.42, selfCallsign = "OMNI-1",
            selfTriangle = true,
            selfHeadingDeg = null,
        ))
        val self = json.getJSONObject(0)
        assertTrue("triangle flag set", self.getBoolean("triangle"))
        assertEquals(0.0, self.getDouble("heading"), 0.001)
    }

    @Test
    fun `RID multirotor contact gets SUAPMHQ multirotor SIDC`() {
        // RemoteIdToCoTConverter emits a-u-A-M-H-Q for multirotor drones.
        // The catalogue maps it to SUAPMHQ---- which milsymbol can render
        // as the proper MIL-STD-2525 multirotor UAS glyph in Cesium.
        val drone = CoTEvent(
            uid = "RID-MAVIC3-ABC",
            type = "a-u-A-M-H-Q",
            lat = 47.65,
            lon = -117.42,
            hae = 120.0,
            callsign = "DRONE-MAVIC3-ABC",
        )
        val json = parse(buildCesiumEntitiesJson(
            contacts = listOf(drone),
            selfLat = null,
            selfLon = null,
            selfCallsign = "OMNI-1",
        ))
        assertEquals(1, json.length())
        val e = json.getJSONObject(0)
        assertEquals("RID-MAVIC3-ABC", e.getString("uid"))
        assertEquals("contact", e.getString("kind"))
        assertEquals("u", e.getString("affiliation"))
        assertEquals("SUAPMHQ----", e.getString("sidc"))
        assertEquals("DRONE-MAVIC3-ABC", e.getString("callsign"))
    }

    @Test
    fun `RID fixed-wing contact gets SUAPMFQ fixed-wing SIDC`() {
        val drone = CoTEvent(
            uid = "RID-FW-1",
            type = "a-u-A-M-F-Q",
            lat = 47.65,
            lon = -117.42,
            hae = 500.0,
            callsign = "DRONE-FW-1",
        )
        val json = parse(buildCesiumEntitiesJson(
            contacts = listOf(drone),
            selfLat = null, selfLon = null, selfCallsign = "OMNI-1",
        ))
        val e = json.getJSONObject(0)
        assertEquals("SUAPMFQ----", e.getString("sidc"))
    }

    @Test
    fun `friendly infantry contact gets SFGPUCI SIDC`() {
        val troop = CoTEvent(
            uid = "TROOP-1",
            type = "a-f-G-U-C-I",
            lat = 47.65,
            lon = -117.42,
            callsign = "ALPHA-1",
        )
        val json = parse(buildCesiumEntitiesJson(
            contacts = listOf(troop),
            selfLat = null, selfLon = null, selfCallsign = "OMNI-1",
        ))
        val e = json.getJSONObject(0)
        assertEquals("f", e.getString("affiliation"))
        assertEquals("SFGPUCI----", e.getString("sidc"))
    }

    @Test
    fun `contacts with NaN lat lon are skipped`() {
        val bad = CoTEvent(
            uid = "BAD",
            type = "a-u-A",
            lat = Double.NaN,
            lon = Double.NaN,
            callsign = "BAD",
        )
        val good = CoTEvent(
            uid = "GOOD",
            type = "a-u-A",
            lat = 47.65,
            lon = -117.42,
            callsign = "GOOD",
        )
        val json = parse(buildCesiumEntitiesJson(
            contacts = listOf(bad, good),
            selfLat = null, selfLon = null, selfCallsign = "OMNI-1",
        ))
        assertEquals(1, json.length())
        assertEquals("GOOD", json.getJSONObject(0).getString("uid"))
    }

    @Test
    fun `hae of zero is omitted (legacy CoT renders on-ground)`() {
        val ground = CoTEvent(
            uid = "G",
            type = "a-f-G-U",
            lat = 47.65,
            lon = -117.42,
            hae = 0.0,
            callsign = "G",
        )
        val json = parse(buildCesiumEntitiesJson(
            contacts = listOf(ground),
            selfLat = null, selfLon = null, selfCallsign = "OMNI-1",
        ))
        val e = json.getJSONObject(0)
        assertFalse("hae==0 should be omitted so heightReference CLAMP_TO_GROUND fires", e.has("hae"))
    }
}
