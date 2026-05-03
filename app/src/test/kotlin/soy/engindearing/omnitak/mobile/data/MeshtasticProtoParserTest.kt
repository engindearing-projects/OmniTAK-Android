package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * JVM unit tests for the hand-rolled protobuf decoder. Verifies the
 * varint round-trip across boundary values, sfixed32 / fixed32 reads,
 * and end-to-end parsing of a hand-crafted NodeInfo + Position
 * submessage. Tests don't touch Android APIs so they can run on a
 * plain JVM.
 */
class MeshtasticProtoParserTest {

    // region wire-helper round trips ------------------------------------

    @Test fun varint_zero() = assertVarintRoundTrip(0u)
    @Test fun varint_127() = assertVarintRoundTrip(127u)
    @Test fun varint_128() = assertVarintRoundTrip(128u)
    @Test fun varint_uint32_max() = assertVarintRoundTrip(0xFFFFFFFFuL)
    @Test fun varint_uint64_max() = assertVarintRoundTrip(ULong.MAX_VALUE)

    @Test fun fixed32_le() {
        val bytes = byteArrayOf(0x78, 0x56, 0x34, 0x12)
        val (v, end) = MeshtasticProtoParser.readFixed32(bytes, 0)!!
        assertEquals(0x12345678u, v)
        assertEquals(4, end)
    }

    @Test fun sfixed32_negative() {
        // -1234567 as sfixed32 bytes (little-endian two's complement)
        // = 0xFFED2979 → bytes 79 29 ED FF
        val bytes = byteArrayOf(0x79, 0x29.toByte(), 0xED.toByte(), 0xFF.toByte())
        val (raw, _) = MeshtasticProtoParser.readFixed32(bytes, 0)!!
        assertEquals(-1234567, raw.toInt())
    }

    @Test fun skip_field_advances_for_each_wire_type() {
        // varint
        val v = byteArrayOf(0x96.toByte(), 0x01) // 150
        assertEquals(2, MeshtasticProtoParser.skipField(v, 0, 0))
        // 64-bit
        val f64 = ByteArray(8) { 0x42 }
        assertEquals(8, MeshtasticProtoParser.skipField(f64, 0, 1))
        // length-delimited (len=3)
        val ld = byteArrayOf(0x03, 0x41, 0x42, 0x43)
        assertEquals(4, MeshtasticProtoParser.skipField(ld, 0, 2))
        // 32-bit
        val f32 = ByteArray(4) { 0x55 }
        assertEquals(4, MeshtasticProtoParser.skipField(f32, 0, 5))
    }

    // endregion

    // region Position parse --------------------------------------------

    @Test fun position_round_trip_seattle() {
        // lat=37.7749, lon=-122.4194 — round-tripping through the *1e7
        // scale should land within the 1e-7 quantisation.
        val latI = (37.7749 * 1e7).toInt()
        val lonI = (-122.4194 * 1e7).toInt()
        val bytes = ByteArrayOutputStream().apply {
            writeTagSfixed32(this, field = 1, value = latI)
            writeTagSfixed32(this, field = 2, value = lonI)
            writeTagVarint(this, field = 3, value = 42UL) // altitude
        }.toByteArray()

        val pos = MeshtasticProtoParser.parsePosition(bytes)
        assertNotNull(pos)
        assertEquals(37.7749, pos!!.lat, 1e-7)
        assertEquals(-122.4194, pos.lon, 1e-7)
        assertEquals(42, pos.altitudeM)
    }

    @Test fun position_zero_zero_returns_null() {
        // Valid-but-empty position (lat=0, lon=0) is treated as "no
        // GPS lock" — parser returns null so we don't draw a node at
        // null island.
        val bytes = ByteArrayOutputStream().apply {
            writeTagSfixed32(this, field = 1, value = 0)
            writeTagSfixed32(this, field = 2, value = 0)
        }.toByteArray()
        assertNull(MeshtasticProtoParser.parsePosition(bytes))
    }

    // endregion

    // region NodeInfo + FromRadio integration --------------------------

    @Test fun fromRadio_nodeinfo_with_user_and_position() {
        // Hand-craft a User submessage (long_name + short_name).
        val user = ByteArrayOutputStream().apply {
            writeTagString(this, field = 2, value = "Alpha Long")
            writeTagString(this, field = 3, value = "ALF")
        }.toByteArray()

        // Position submessage.
        val position = ByteArrayOutputStream().apply {
            writeTagSfixed32(this, field = 1, value = (40.0 * 1e7).toInt())
            writeTagSfixed32(this, field = 2, value = (-80.0 * 1e7).toInt())
            writeTagVarint(this, field = 3, value = 100UL)
        }.toByteArray()

        // NodeInfo: num=0xDEADBEEF, user=user, position=position, snr=12.5,
        // last_heard=1700000000, hops_away=2.
        val nodeInfo = ByteArrayOutputStream().apply {
            writeTagVarint(this, field = 1, value = 0xDEADBEEFUL)
            writeTagBytes(this, field = 4, value = user)
            writeTagBytes(this, field = 5, value = position)
            writeTagFixed32Float(this, field = 7, value = 12.5f)
            writeTagFixed32(this, field = 9, value = 1_700_000_000)
            writeTagVarint(this, field = 11, value = 2UL)
        }.toByteArray()

        // FromRadio.node_info is canonical field 4 in mesh.proto.
        val fromRadio = ByteArrayOutputStream().apply {
            writeTagBytes(this, field = 4, value = nodeInfo)
        }.toByteArray()

        val parsed = MeshtasticProtoParser.parseFromRadio(fromRadio)
        assertTrue(parsed is FromRadioFrame.NodeInfoFrame)
        val node = (parsed as FromRadioFrame.NodeInfoFrame).node
        assertEquals(0xDEADBEEFL, node.id)
        assertEquals("ALF", node.shortName)
        assertEquals("Alpha Long", node.longName)
        assertNotNull(node.position)
        assertEquals(40.0, node.position!!.lat, 1e-7)
        assertEquals(-80.0, node.position!!.lon, 1e-7)
        assertEquals(100, node.position!!.altitudeM)
        assertEquals(12.5, node.snr!!, 1e-3)
        assertEquals(1_700_000_000L, node.lastHeardEpoch)
        assertEquals(2, node.hopDistance)
    }

    @Test fun fromRadio_my_info() {
        val myInfo = ByteArrayOutputStream().apply {
            writeTagVarint(this, field = 1, value = 0xCAFEBABEUL)
        }.toByteArray()
        // FromRadio.my_info is canonical field 3 in mesh.proto.
        val fromRadio = ByteArrayOutputStream().apply {
            writeTagBytes(this, field = 3, value = myInfo)
        }.toByteArray()
        val parsed = MeshtasticProtoParser.parseFromRadio(fromRadio)
        assertTrue(parsed is FromRadioFrame.MyInfo)
        assertEquals(0xCAFEBABEu, (parsed as FromRadioFrame.MyInfo).nodeNum)
    }

    @Test fun fromRadio_config_complete() {
        val fromRadio = ByteArrayOutputStream().apply {
            writeTagVarint(this, field = 7, value = 99UL)
        }.toByteArray()
        val parsed = MeshtasticProtoParser.parseFromRadio(fromRadio)
        assertTrue(parsed is FromRadioFrame.ConfigComplete)
        assertEquals(99u, (parsed as FromRadioFrame.ConfigComplete).id)
    }

    @Test fun fromRadio_meshpacket_with_position_payload() {
        // POSITION_APP packet wrapped in a MeshPacket → FromRadio.
        val position = ByteArrayOutputStream().apply {
            writeTagSfixed32(this, field = 1, value = (50.0 * 1e7).toInt())
            writeTagSfixed32(this, field = 2, value = (10.0 * 1e7).toInt())
        }.toByteArray()
        val data = ByteArrayOutputStream().apply {
            writeTagVarint(this, field = 1, value = 3UL) // portnum POSITION_APP
            writeTagBytes(this, field = 2, value = position)
        }.toByteArray()
        val meshPacket = ByteArrayOutputStream().apply {
            writeTagFixed32(this, field = 1, value = 0x12345678) // from
            writeTagFixed32(this, field = 2, value = -1) // to (broadcast)
            writeTagBytes(this, field = 4, value = data)
        }.toByteArray()
        val fromRadio = ByteArrayOutputStream().apply {
            writeTagBytes(this, field = 2, value = meshPacket)
        }.toByteArray()

        val parsed = MeshtasticProtoParser.parseFromRadio(fromRadio)
        assertTrue(parsed is FromRadioFrame.Packet)
        val pkt = (parsed as FromRadioFrame.Packet).packet
        assertEquals(0x12345678u, pkt.from)
        assertEquals(0xFFFFFFFFu, pkt.to)
        assertEquals(3u, pkt.portnum)
        val parsedPos = MeshtasticProtoParser.parsePosition(pkt.payload)
        assertNotNull(parsedPos)
        assertEquals(50.0, parsedPos!!.lat, 1e-7)
        assertEquals(10.0, parsedPos.lon, 1e-7)
    }

    // endregion

    // region helpers ---------------------------------------------------

    private fun assertVarintRoundTrip(value: ULong) {
        val out = ByteArrayOutputStream()
        appendVarint(out, value)
        val (read, end) = MeshtasticProtoParser.readVarint(out.toByteArray(), 0)!!
        assertEquals(value, read)
        assertEquals(out.size(), end)
    }

    /** Encode a varint in standard protobuf wire format. */
    private fun appendVarint(out: ByteArrayOutputStream, value: ULong) {
        var v = value
        while (v >= 0x80uL) {
            out.write(((v and 0x7FuL).toInt()) or 0x80)
            v = v shr 7
        }
        out.write(v.toInt() and 0x7F)
    }

    private fun writeTagVarint(out: ByteArrayOutputStream, field: Int, value: ULong) {
        appendVarint(out, ((field shl 3) or 0).toULong())
        appendVarint(out, value)
    }

    private fun writeTagBytes(out: ByteArrayOutputStream, field: Int, value: ByteArray) {
        appendVarint(out, ((field shl 3) or 2).toULong())
        appendVarint(out, value.size.toULong())
        out.write(value)
    }

    private fun writeTagString(out: ByteArrayOutputStream, field: Int, value: String) {
        writeTagBytes(out, field, value.toByteArray(Charsets.UTF_8))
    }

    private fun writeTagSfixed32(out: ByteArrayOutputStream, field: Int, value: Int) {
        appendVarint(out, ((field shl 3) or 5).toULong())
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }

    private fun writeTagFixed32(out: ByteArrayOutputStream, field: Int, value: Int) {
        writeTagSfixed32(out, field, value)
    }

    private fun writeTagFixed32Float(out: ByteArrayOutputStream, field: Int, value: Float) {
        writeTagSfixed32(out, field, value.toRawBits())
    }

    // endregion
}
