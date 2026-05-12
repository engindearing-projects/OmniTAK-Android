package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DrawingCoTTest {

    private val seattle = 47.6062 to -122.3321
    private val edge = 47.6152 to -122.3321  // ~1000 m due north of seattle
    private val now = 1778516000000L         // 2026-05-11T14:32:00Z

    @Test fun line_envelope_includes_required_event_attrs() {
        val xml = DrawingCoT.buildLine(
            Drawing(
                id = "draw-1",
                kind = DrawingKind.LINE,
                name = "Patrol path",
                points = listOf(seattle, 47.61 to -122.30, 47.60 to -122.28),
                colorHex = "#FF8800",
            ),
            callsign = "Engie",
            closed = false,
            nowMs = now,
        )
        assertTrue("event opens", xml.contains("<event "))
        assertTrue("u-d-f type", xml.contains("type=\"u-d-f\""))
        assertTrue("uid", xml.contains("uid=\"draw-1\""))
        assertTrue("callsign", xml.contains("callsign=\"Engie\""))
        // vertex chain via <link>
        assertTrue("v0 link", xml.contains("uid=\"draw-1-v0\""))
        assertTrue("v2 link", xml.contains("uid=\"draw-1-v2\""))
        assertFalse("open line no closed flag", xml.contains("closed_polygon=\"1\""))
    }

    @Test fun polygon_closes_ring_and_sets_closed_flag() {
        val xml = DrawingCoT.buildLine(
            Drawing(
                id = "poly-1",
                kind = DrawingKind.POLYGON,
                points = listOf(seattle, 47.61 to -122.30, 47.60 to -122.28),
            ),
            callsign = "Engie",
            closed = true,
            nowMs = now,
        )
        assertTrue("closed flag", xml.contains("closed_polygon=\"1\""))
        assertTrue("closing vertex", xml.contains("uid=\"poly-1-v3\""))
        // The closing vertex repeats the first point
        assertTrue(
            "closing link carries anchor",
            xml.contains("point=\"47.6062,-122.3321,0.0\""),
        )
    }

    @Test fun circle_emits_u_d_c_c_with_ellipse_and_remarks_radius() {
        val drawing = Drawing(
            id = "circ-1",
            kind = DrawingKind.CIRCLE,
            points = listOf(seattle, edge),
        )
        val xmls = DrawingCoT.buildAll(drawing, callsign = "Engie", nowMs = now)
        assertEquals(1, xmls.size)
        val xml = xmls.first()
        assertTrue("type", xml.contains("type=\"u-d-c-c\""))
        assertTrue("ellipse", xml.contains("<ellipse minor="))
        assertTrue("radius remark", xml.contains("radius="))
    }

    @Test fun bullseye_emits_one_event_per_default_ring() {
        val drawing = Drawing(
            id = "bull-1",
            kind = DrawingKind.BULLSEYE,
            name = "IPP",
            points = listOf(seattle),
        )
        val xmls = DrawingCoT.buildAll(drawing, callsign = "Engie", nowMs = now)
        assertEquals(
            "default bullseye = 3 rings (100/500/1000m)",
            3, xmls.size,
        )
        // All rings share the bullseye group attribute
        xmls.forEach { assertTrue("group attr", it.contains("group=\"bull-1\"")) }
        // Each ring has a stable per-ring UID
        assertTrue("ring 1 uid", xmls[0].contains("uid=\"bull-1-ring1\""))
        assertTrue("ring 3 uid", xmls[2].contains("uid=\"bull-1-ring3\""))
        // Radii written in remarks in metres, ascending
        assertTrue("100m ring", xmls[0].contains("radius=100m") ||
                xmls[0].contains("radius=100.0m"))
        assertTrue("1000m ring", xmls[2].contains("radius=1000m") ||
                xmls[2].contains("radius=1000.0m"))
    }

    @Test fun bullseye_honors_custom_radii() {
        val drawing = Drawing(
            id = "bull-2",
            kind = DrawingKind.BULLSEYE,
            points = listOf(seattle),
            radiiM = listOf(250.0, 2_000.0),
        )
        val xmls = DrawingCoT.buildAll(drawing, callsign = "Engie", nowMs = now)
        assertEquals(2, xmls.size)
        assertTrue(xmls[0].contains("radius=250m") || xmls[0].contains("radius=250.0m"))
        assertTrue(xmls[1].contains("radius=2000m") || xmls[1].contains("radius=2000.0m"))
    }

    @Test fun bullseye_with_no_centre_or_zero_radii_yields_no_events() {
        val noCentre = Drawing(
            id = "x",
            kind = DrawingKind.BULLSEYE,
            points = emptyList(),
        )
        assertTrue(DrawingCoT.buildAll(noCentre, callsign = "Engie").isEmpty())

        val zeroRadii = Drawing(
            id = "y",
            kind = DrawingKind.BULLSEYE,
            points = listOf(seattle),
            radiiM = listOf(0.0, -10.0),
        )
        assertTrue(DrawingCoT.buildAll(zeroRadii, callsign = "Engie").isEmpty())
    }

    @Test fun parse_round_trip_line() {
        val original = Drawing(
            id = "draw-2",
            kind = DrawingKind.LINE,
            name = "Engie",
            points = listOf(
                47.6062 to -122.3321,
                47.6100 to -122.3300,
                47.6200 to -122.3200,
            ),
            colorHex = "#FF8800",
        )
        val xml = DrawingCoT.buildLine(original, callsign = "Engie", closed = false, nowMs = now)
        val parsed = DrawingCoT.parse(xml)
        assertNotNull("parse non-null", parsed)
        assertEquals(DrawingKind.LINE, parsed!!.kind)
        assertEquals(original.points.size, parsed.points.size)
        original.points.zip(parsed.points).forEach { (a, b) ->
            assertTrue("lat close", abs(a.first - b.first) < 1e-6)
            assertTrue("lon close", abs(a.second - b.second) < 1e-6)
        }
    }

    @Test fun parse_round_trip_polygon_drops_closing_duplicate() {
        val original = Drawing(
            id = "poly-2",
            kind = DrawingKind.POLYGON,
            points = listOf(
                47.6062 to -122.3321,
                47.6100 to -122.3300,
                47.6200 to -122.3200,
            ),
        )
        val xml = DrawingCoT.buildLine(original, callsign = "Engie", closed = true, nowMs = now)
        val parsed = DrawingCoT.parse(xml)
        assertNotNull(parsed)
        assertEquals(DrawingKind.POLYGON, parsed!!.kind)
        assertEquals(
            "closing duplicate stripped",
            original.points.size, parsed.points.size,
        )
    }

    @Test fun parse_round_trip_circle_recovers_radius_within_1m() {
        val drawing = Drawing(
            id = "circ-2",
            kind = DrawingKind.CIRCLE,
            points = listOf(seattle, edge),
        )
        val xmls = DrawingCoT.buildAll(drawing, callsign = "Engie", nowMs = now)
        val parsed = DrawingCoT.parse(xmls.first())
        assertNotNull(parsed)
        assertEquals(DrawingKind.CIRCLE, parsed!!.kind)
        val originalRadius = DrawingCoT.circleCentreAndRadius(drawing)!!.second
        val parsedRadius = DrawingCoT.circleCentreAndRadius(parsed)!!.second
        assertTrue(
            "radius round-trips within 1 m (was $originalRadius, parsed $parsedRadius)",
            abs(originalRadius - parsedRadius) < 1.0,
        )
    }

    @Test fun parse_returns_null_for_marker_xml() {
        val markerXml = MarkerCoT.build(
            uid = "m-1",
            callsign = "MyPos-1432",
            type = "a-f-G-U-C",
            lat = 47.6062,
            lon = -122.3321,
        )
        assertNull("marker is not a drawing", DrawingCoT.parse(markerXml))
    }

    @Test fun hex_argb_round_trip() {
        val argb = DrawingCoT.hexToArgbInt("#FF8800")
        val hex = DrawingCoT.argbToHex(argb)
        // hexToArgbInt pads alpha to FF when only RGB is given
        assertEquals("#FFFF8800", hex)
    }

    @Test fun escapes_xml_metacharacters_in_callsign_and_uid() {
        val xml = DrawingCoT.buildLine(
            Drawing(
                id = "draw<&3>",
                kind = DrawingKind.LINE,
                points = listOf(seattle, 47.61 to -122.30),
            ),
            callsign = "<bad&cs>",
            closed = false,
            nowMs = now,
        )
        assertTrue(xml.contains("uid=\"draw&lt;&amp;3&gt;\""))
        assertTrue(xml.contains("callsign=\"&lt;bad&amp;cs&gt;\""))
    }
}
