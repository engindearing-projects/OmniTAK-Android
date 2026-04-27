package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * JVM unit tests for the portnum-72 ATAK plugin parser/serializer pair.
 * The XML fallback test deliberately invokes [AtakPluginParser.parse]
 * which routes through `parseCoTXmlFallback` on a plain JVM (no
 * `android.util.Xml`).
 */
class AtakPluginParserTest {

    @Test fun round_trip_serializer_to_parser_preserves_key_fields() {
        val event = CoTEvent(
            uid = "ANDROID-OMNITAK-1",
            type = "a-f-G-U-C",
            lat = 37.7749,
            lon = -122.4194,
            hae = 42.0,
            ce = 5.0,
            le = 5.0,
            callsign = "ENGIE",
            remarks = "round-trip test",
        )

        val payload = AtakPluginSerializer.serialize(event)
        val decoded = AtakPluginParser.parse(payload)
        assertNotNull("expected serializer round-trip to parse back", decoded)
        decoded!!

        assertEquals("ANDROID-OMNITAK-1", decoded.uid)
        assertEquals("a-f-G-U-C", decoded.type)
        assertEquals(37.7749, decoded.lat, 1e-9)
        assertEquals(-122.4194, decoded.lon, 1e-9)
        assertEquals(42.0, decoded.hae, 1e-9)
        assertEquals(5.0, decoded.ce, 1e-9)
        assertEquals(5.0, decoded.le, 1e-9)
        assertEquals("ENGIE", decoded.callsign)
        assertTrue(decoded.remarks.contains("round-trip"))
        // rawXml should reconstruct a CoT event for downstream consumers.
        assertNotNull(decoded.rawXml)
        assertTrue(decoded.rawXml!!.contains("<event"))
        assertTrue(decoded.rawXml!!.contains("uid=\"ANDROID-OMNITAK-1\""))
    }

    @Test fun handcrafted_takmessage_parses_lat_lon_uid_type() {
        // Build a TAKMessage{ cotEvent { type, uid, lat, lon } } by hand.
        val cot = ByteArrayOutputStream().apply {
            // 1: type (string) -> "a-f-G-U-C"
            writeTagString(this, field = 1, value = "a-f-G-U-C")
            // 5: uid
            writeTagString(this, field = 5, value = "TEST-UID-42")
            // 10: lat (double / fixed64)
            writeTagFixed64Double(this, field = 10, value = 47.6062)
            // 11: lon
            writeTagFixed64Double(this, field = 11, value = -122.3321)
        }.toByteArray()

        val takMessage = ByteArrayOutputStream().apply {
            // 2: cotEvent
            writeTagBytes(this, field = 2, value = cot)
        }.toByteArray()

        val event = AtakPluginParser.parse(takMessage)
        assertNotNull(event)
        assertEquals("TEST-UID-42", event!!.uid)
        assertEquals("a-f-G-U-C", event.type)
        assertEquals(47.6062, event.lat, 1e-9)
        assertEquals(-122.3321, event.lon, 1e-9)
    }

    @Test fun handcrafted_takmessage_parses_detail_callsign() {
        // Detail { contact { callsign = "BRAVO-1" } }
        val contact = ByteArrayOutputStream().apply {
            writeTagString(this, field = 1, value = "BRAVO-1")
        }.toByteArray()
        val detail = ByteArrayOutputStream().apply {
            writeTagBytes(this, field = 6, value = contact)
        }.toByteArray()
        val cot = ByteArrayOutputStream().apply {
            writeTagString(this, field = 1, value = "a-h-G")
            writeTagString(this, field = 5, value = "HOSTILE-1")
            writeTagFixed64Double(this, field = 10, value = 0.5)
            writeTagFixed64Double(this, field = 11, value = -0.25)
            writeTagBytes(this, field = 15, value = detail)
        }.toByteArray()
        val takMessage = ByteArrayOutputStream().apply {
            writeTagBytes(this, field = 2, value = cot)
        }.toByteArray()

        val event = AtakPluginParser.parse(takMessage)
        assertNotNull(event)
        assertEquals("HOSTILE-1", event!!.uid)
        assertEquals("BRAVO-1", event.callsign)
        assertEquals(0.5, event.lat, 1e-9)
        assertEquals(-0.25, event.lon, 1e-9)
    }

    @Test fun bare_cotEvent_without_takMessage_wrapper_parses() {
        // Some senders skip the TAKMessage outer envelope.
        val cot = ByteArrayOutputStream().apply {
            writeTagString(this, field = 1, value = "a-f-G")
            writeTagString(this, field = 5, value = "BARE-1")
            writeTagFixed64Double(this, field = 10, value = 10.0)
            writeTagFixed64Double(this, field = 11, value = 20.0)
        }.toByteArray()
        val event = AtakPluginParser.parse(cot)
        assertNotNull(event)
        assertEquals("BARE-1", event!!.uid)
        assertEquals("a-f-G", event.type)
        assertEquals(10.0, event.lat, 1e-9)
        assertEquals(20.0, event.lon, 1e-9)
    }

    @Test fun xml_fallback_returns_event_for_raw_cot_xml_payload() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            |<event version="2.0" uid="XML-UID-1" type="a-f-G-U-C" time="2026-04-25T00:00:00.000Z" stale="2026-04-25T00:05:00.000Z" how="m-g">
            |  <point lat="38.9072" lon="-77.0369" hae="0" ce="9" le="9"/>
            |  <detail><contact callsign="WHISKEY-1"/></detail>
            |</event>""".trimMargin()
        val event = AtakPluginParser.parse(xml.toByteArray(Charsets.UTF_8))
        assertNotNull(event)
        assertEquals("XML-UID-1", event!!.uid)
        assertEquals("a-f-G-U-C", event.type)
        assertEquals(38.9072, event.lat, 1e-9)
        assertEquals(-77.0369, event.lon, 1e-9)
        assertEquals("WHISKEY-1", event.callsign)
    }

    @Test fun xml_fallback_handles_event_root_without_xml_decl() {
        val xml = "<event version=\"2.0\" uid=\"X-2\" type=\"a-f\"><point lat=\"1.0\" lon=\"2.0\" hae=\"0\" ce=\"9\" le=\"9\"/></event>"
        val event = AtakPluginParser.parse(xml.toByteArray(Charsets.UTF_8))
        assertNotNull(event)
        assertEquals("X-2", event!!.uid)
        assertEquals(1.0, event.lat, 1e-9)
        assertEquals(2.0, event.lon, 1e-9)
    }

    @Test fun empty_input_returns_null() {
        assertNull(AtakPluginParser.parse(ByteArray(0)))
    }

    @Test fun garbage_input_returns_null_not_crash() {
        // Random bytes that aren't valid protobuf or XML.
        val garbage = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05)
        // Either null or a benign result; the contract is "don't crash".
        val event = AtakPluginParser.parse(garbage)
        // 0x00 0x01 0x02 0x03 0x04 0x05 — first byte is field=0 wire=0,
        // which is invalid. Parser should return null.
        assertNull(event)
    }

    @Test fun garbage_short_xml_like_returns_null() {
        // Looks like XML but has no event/point — fallback returns null.
        val xml = "<event/>".toByteArray(Charsets.UTF_8)
        assertNull(AtakPluginParser.parse(xml))
    }

    @Test fun build_to_radio_includes_portnum_72() {
        val event = CoTEvent(
            uid = "TX-1", type = "a-f-G-U-C", lat = 0.0, lon = 0.0,
        )
        val payload = AtakPluginSerializer.serialize(event)
        val toRadio = AtakPluginSerializer.buildToRadio(payload, channelIndex = 1u)
        // ToRadio: tag(field=1, wire=2) = 0x0A, then varint length, then MeshPacket.
        assertEquals(0x0A.toByte(), toRadio[0])
        // The encoded portnum-72 tag(field=1, wire=0)=0x08 followed by 0x48
        // (varint 72) must appear somewhere in the byte stream.
        var found = false
        for (i in 0 until toRadio.size - 1) {
            if (toRadio[i] == 0x08.toByte() && toRadio[i + 1] == 0x48.toByte()) {
                found = true
                break
            }
        }
        assertTrue("expected portnum=72 marker (08 48) in ToRadio bytes", found)
    }

    // region helpers -----------------------------------------------------

    private fun appendVarint(out: ByteArrayOutputStream, value: ULong) {
        var v = value
        while (v >= 0x80uL) {
            out.write(((v and 0x7FuL).toInt()) or 0x80)
            v = v shr 7
        }
        out.write(v.toInt() and 0x7F)
    }

    private fun writeTagBytes(out: ByteArrayOutputStream, field: Int, value: ByteArray) {
        appendVarint(out, ((field shl 3) or 2).toULong())
        appendVarint(out, value.size.toULong())
        out.write(value)
    }

    private fun writeTagString(out: ByteArrayOutputStream, field: Int, value: String) {
        writeTagBytes(out, field, value.toByteArray(Charsets.UTF_8))
    }

    private fun writeTagFixed64Double(out: ByteArrayOutputStream, field: Int, value: Double) {
        appendVarint(out, ((field shl 3) or 1).toULong())
        val bits = value.toRawBits()
        for (i in 0 until 8) {
            out.write(((bits ushr (i * 8)) and 0xFFL).toInt())
        }
    }

    // endregion
}
