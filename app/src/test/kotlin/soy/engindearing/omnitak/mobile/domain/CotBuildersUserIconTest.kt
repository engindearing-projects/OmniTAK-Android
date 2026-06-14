package soy.engindearing.omnitak.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.CoTParser
import soy.engindearing.omnitak.mobile.data.symbology.TakIconRegistry

/**
 * #29 — CotBuilders.rebuildEvent must emit `<usericon iconsetpath="…">`
 * when the source CoTEvent carries one. Without that, receiving ATAK
 * has no way to render the FEMA glyph and falls all the way back to
 * the generic friendly-installation default.
 */
class CotBuildersUserIconTest {

    @Test fun rebuildEvent_emits_usericon_when_iconsetPath_present() {
        val event = CoTEvent(
            uid = "test-fema-cp",
            type = "a-f-G-I-U-T",
            lat = 47.6588,
            lon = -117.4260,
            callsign = "CP-1",
            iconsetPath = "COT_MAPPING_FEMA/incidentCommand/command_post",
        )
        val xml = CotBuilders.rebuildEvent(event, destUids = emptyList())
        assertTrue(
            "missing usericon tag in:\n$xml",
            xml.contains("""<usericon iconsetpath="COT_MAPPING_FEMA/incidentCommand/command_post"/>"""),
        )
    }

    @Test fun rebuildEvent_omits_usericon_when_iconsetPath_null() {
        val event = CoTEvent(
            uid = "test-plain",
            type = "a-f-G-U-C",
            lat = 47.6588,
            lon = -117.4260,
            callsign = "PLAIN",
        )
        val xml = CotBuilders.rebuildEvent(event, destUids = emptyList())
        assertFalse(
            "unexpected usericon tag in plain-marker XML:\n$xml",
            xml.contains("<usericon"),
        )
    }

    @Test fun rebuildEvent_xml_escapes_iconsetPath() {
        // Defensive — iconsetPath values should never contain quotes in
        // practice, but the emitter has to escape just in case so a
        // pathological payload can't break the XML doc.
        val event = CoTEvent(
            uid = "u",
            type = "a-f-G-I-U-T",
            lat = 0.0,
            lon = 0.0,
            iconsetPath = """foo"bar""",
        )
        val xml = CotBuilders.rebuildEvent(event, destUids = emptyList())
        assertTrue(
            "iconsetPath quote not escaped in:\n$xml",
            xml.contains("""foo&quot;bar"""),
        )
    }

    // ---- #98 Spot Map round-trip (placed -> wire -> received) ------------

    @Test fun rebuildEvent_emits_spotmap_usericon_and_color() {
        // A placed Spot Map marker carries the canonical CoT type, the
        // COT_MAPPING_SPOTMAP usericon path, and its swatch colour in <color>.
        val spot = TakIconRegistry.SpotIcon.RED
        val event = CoTEvent(
            uid = "spot-1",
            type = TakIconRegistry.SpotIcon.COT_TYPE,
            lat = 47.6588,
            lon = -117.4260,
            callsign = "SPOT-1",
            iconsetPath = spot.iconsetPath,
            colorArgb = spot.argb,
        )
        val xml = CotBuilders.rebuildEvent(event, destUids = emptyList())
        assertTrue(
            "missing spot usericon in:\n$xml",
            xml.contains("""<usericon iconsetpath="COT_MAPPING_SPOTMAP/red"/>"""),
        )
        assertTrue("missing <color argb> in:\n$xml", xml.contains("""<color argb="${spot.argb}"/>"""))
    }

    @Test fun spotmap_marker_round_trips_through_wire_back_to_the_same_icon() {
        // End-to-end: the exact path kymyura's scripts exercise. A placed spot
        // marker emitted to the wire must re-parse on the receiving side back to
        // the identical iconset path + colour, and the registry must resolve
        // that received path to the same bundled swatch (not a MIL-STD fallback).
        val spot = TakIconRegistry.SpotIcon.GREEN
        val placed = CoTEvent(
            uid = "spot-2",
            type = TakIconRegistry.SpotIcon.COT_TYPE,
            lat = 12.34,
            lon = 56.78,
            callsign = "SPOT-2",
            iconsetPath = spot.iconsetPath,
            colorArgb = spot.argb,
        )
        val wire = CotBuilders.rebuildEvent(placed, destUids = emptyList())

        // Receiving side parses the wire CoT.
        val received = CoTParser.parse(wire)
        assertNotNull("received event failed to parse:\n$wire", received)
        assertEquals(spot.iconsetPath, received!!.iconsetPath)
        assertEquals(spot.argb, received.colorArgb)

        // Received marker resolves to the same bundled Spot Map icon — the
        // gap that previously fell through to a generic MIL-STD symbol.
        assertTrue(TakIconRegistry.handles(received.type, received.iconsetPath))
        assertEquals(spot, TakIconRegistry.SpotIcon.fromIconsetPath(received.iconsetPath!!))
    }

    @Test fun received_scripted_spotmap_cot_resolves_without_a_color_element() {
        // A minimal scripted spot (path only, no <color>) still resolves — to
        // the swatch named in the path. Mirrors a bare iTAK / script push.
        val wire = """
            <event version="2.0" uid="ext-1" type="b-m-p-s-m" time="2026-01-01T00:00:00Z"
                   start="2026-01-01T00:00:00Z" stale="2026-01-01T00:05:00Z" how="h-g-i-g-o">
              <point lat="1.0" lon="2.0" hae="0" ce="9999999" le="9999999"/>
              <detail>
                <contact callsign="EXT"/>
                <usericon iconsetpath="COT_MAPPING_SPOTMAP/orange"/>
              </detail>
            </event>
        """.trimIndent()
        val received = CoTParser.parse(wire)
        assertNotNull(received)
        assertEquals("COT_MAPPING_SPOTMAP/orange", received!!.iconsetPath)
        assertTrue(TakIconRegistry.handles(received.type, received.iconsetPath))
        assertEquals(
            TakIconRegistry.SpotIcon.ORANGE,
            TakIconRegistry.SpotIcon.fromIconsetPath(received.iconsetPath!!),
        )
    }
}
