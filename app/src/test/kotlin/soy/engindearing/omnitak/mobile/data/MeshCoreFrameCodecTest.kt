package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Unit tests for [MeshCoreFrameCodec] — the MeshCore companion BLE
 * protocol encode/decode, ported from the TAK-MeshCore ATAK plugin's
 * `BtConnectionManager.java`. Builds frames byte-for-byte to the
 * reference layout and verifies round-trips. No Android deps.
 */
class MeshCoreFrameCodecTest {

    // ---- ENCODE ---------------------------------------------------------

    @Test fun `app_start has code at 0 and app id at offset 8`() {
        val out = MeshCoreFrameCodec.buildAppStart("meshcore-flutter")
        assertEquals(0x01.toByte(), out[0])
        // bytes 1..7 reserved-zero
        for (i in 1..7) assertEquals("byte $i must be 0", 0.toByte(), out[i])
        val id = String(out, 8, out.size - 8, StandardCharsets.UTF_8)
        assertEquals("meshcore-flutter", id)
    }

    @Test fun `get_next_message is single 0x0A byte`() {
        assertTrue(MeshCoreFrameCodec.buildGetNextMessage().contentEquals(byteArrayOf(0x0A)))
    }

    @Test fun `device_query is 0x16 0x03`() {
        assertTrue(MeshCoreFrameCodec.buildDeviceQuery().contentEquals(byteArrayOf(0x16, 0x03)))
    }

    @Test fun `get_battery is single 0x14 byte`() {
        assertTrue(MeshCoreFrameCodec.buildGetBattery().contentEquals(byteArrayOf(0x14)))
    }

    @Test fun `send_self_advert is single 0x07 byte`() {
        assertTrue(MeshCoreFrameCodec.buildSendSelfAdvert().contentEquals(byteArrayOf(0x07)))
    }

    @Test fun `set_advert_latlon encodes E6 little-endian lat lon alt`() {
        val lat = 47.6588
        val lon = -117.4260
        val out = MeshCoreFrameCodec.buildSetAdvertLatLon(lat, lon, 600.0)
        assertNotNull(out)
        out!!
        assertEquals("13-byte frame", 13, out.size)
        assertEquals(0x0E.toByte(), out[0])
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(Math.round(lat * 1_000_000.0).toInt(), bb.getInt(1))
        assertEquals(Math.round(lon * 1_000_000.0).toInt(), bb.getInt(5))
        assertEquals(600, bb.getInt(9))
    }

    @Test fun `set_advert_latlon rejects out of range`() {
        assertNull(MeshCoreFrameCodec.buildSetAdvertLatLon(91.0, 0.0))
        assertNull(MeshCoreFrameCodec.buildSetAdvertLatLon(0.0, 181.0))
        assertNull(MeshCoreFrameCodec.buildSetAdvertLatLon(Double.NaN, 0.0))
    }

    @Test fun `send_txt_msg frames code type attempt ts prefix text`() {
        val pub = "1a2b3c4d5e6f0011" // 8 bytes hex; first 6 used as prefix
        val out = MeshCoreFrameCodec.buildSendTxtMsg(pub, "hi", nowSec = 0x01020304)
        assertNotNull(out)
        out!!
        assertEquals(0x02.toByte(), out[0]) // CMD_SEND_TXT_MSG
        assertEquals(0x00.toByte(), out[1]) // TXT_TYPE_PLAIN
        assertEquals(0x00.toByte(), out[2]) // attempt
        val ts = ByteBuffer.wrap(out, 3, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(0x01020304, ts)
        // prefix is first 6 bytes of the pubkey
        val expectedPrefix = byteArrayOf(0x1a, 0x2b, 0x3c, 0x4d, 0x5e, 0x6f)
        val gotPrefix = out.copyOfRange(7, 13)
        assertTrue(expectedPrefix.contentEquals(gotPrefix))
        val text = String(out, 13, out.size - 13, StandardCharsets.UTF_8)
        assertEquals("hi", text)
    }

    @Test fun `send_txt_msg rejects short pubkey`() {
        assertNull(MeshCoreFrameCodec.buildSendTxtMsg("abcd", "x"))
    }

    // ---- DECODE: control codes -----------------------------------------

    @Test fun `decode messages_waiting`() {
        assertEquals(
            MeshCoreFrameCodec.Decoded.MessagesWaiting,
            MeshCoreFrameCodec.decode(byteArrayOf(0x83.toByte())),
        )
    }

    @Test fun `decode no_more_messages`() {
        assertEquals(
            MeshCoreFrameCodec.Decoded.NoMoreMessages,
            MeshCoreFrameCodec.decode(byteArrayOf(0x0A)),
        )
    }

    @Test fun `decode empty or null returns null`() {
        assertNull(MeshCoreFrameCodec.decode(null))
        assertNull(MeshCoreFrameCodec.decode(ByteArray(0)))
    }

    // ---- DECODE: battery -----------------------------------------------

    @Test fun `decode battery resp 0x0C`() {
        // 4000 mV = 0x0FA0 -> LE [A0, 0F]
        val pkt = byteArrayOf(0x0C, 0xA0.toByte(), 0x0F)
        val d = MeshCoreFrameCodec.decode(pkt)
        assertTrue(d is MeshCoreFrameCodec.Decoded.Battery)
        d as MeshCoreFrameCodec.Decoded.Battery
        assertEquals(4000, d.milliVolts)
        // (4000-3300)/(4200-3300) = 700/900 ~= 78%
        assertEquals(78, d.percent)
    }

    @Test fun `decode stats core battery 0x18`() {
        // type=0x00, 3700 mV = 0x0E74 -> LE [74,0E]
        val pkt = byteArrayOf(0x18, 0x00, 0x74, 0x0E)
        val d = MeshCoreFrameCodec.decode(pkt)
        assertTrue(d is MeshCoreFrameCodec.Decoded.Battery)
        d as MeshCoreFrameCodec.Decoded.Battery
        assertEquals(3700, d.milliVolts)
    }

    @Test fun `battery mv to percent clamps`() {
        assertEquals(-1, MeshCoreFrameCodec.batteryMvToPercent(0))
        assertEquals(0, MeshCoreFrameCodec.batteryMvToPercent(3000))
        assertEquals(100, MeshCoreFrameCodec.batteryMvToPercent(4300))
    }

    // ---- DECODE: device info -------------------------------------------

    @Test fun `decode device info 0x0D minimal frame`() {
        // [0]=0x0D [1]=fwCode [2]=maxContactsHalf [3]=maxChannels
        val pkt = byteArrayOf(0x0D, 0x02, 0x20, 0x04) // fwCode=2, contacts=64, channels=4
        val d = MeshCoreFrameCodec.decode(pkt)
        assertTrue("Expected DeviceInfo, got: $d", d is MeshCoreFrameCodec.Decoded.DeviceInfo)
        d as MeshCoreFrameCodec.Decoded.DeviceInfo
        assertEquals(2, d.fwCode)
        assertEquals(64, d.maxContacts)
        assertEquals(4, d.maxChannels)
        assertEquals("", d.manufacturer) // short frame — no manufacturer
        assertEquals("", d.version)
    }

    @Test fun `decode device info 0x0D full frame parses manufacturer and version`() {
        // Full layout: [0]=code [1]=fwCode [2]=maxContactsHalf [3]=maxChannels
        // [20..59]=manufacturer(40B) [60..79]=version(20B) => need at least 80 bytes
        val pkt = ByteArray(80)
        pkt[0] = 0x0D
        pkt[1] = 0x03 // fwCode
        pkt[2] = 0x10 // maxContacts/2 = 16 → 32 contacts
        pkt[3] = 0x08 // maxChannels
        val mfg = "TestMaker".toByteArray(StandardCharsets.UTF_8)
        val ver = "1.16.0".toByteArray(StandardCharsets.UTF_8)
        System.arraycopy(mfg, 0, pkt, 20, mfg.size)
        System.arraycopy(ver, 0, pkt, 60, ver.size)
        val d = MeshCoreFrameCodec.decode(pkt)
        assertTrue(d is MeshCoreFrameCodec.Decoded.DeviceInfo)
        d as MeshCoreFrameCodec.Decoded.DeviceInfo
        assertEquals(3, d.fwCode)
        assertEquals(32, d.maxContacts)
        assertEquals(8, d.maxChannels)
        assertEquals("TestMaker", d.manufacturer)
        assertEquals("1.16.0", d.version)
    }

    @Test fun `decode device info 0x0D too short returns unhandled`() {
        val d = MeshCoreFrameCodec.decode(byteArrayOf(0x0D, 0x01))
        assertTrue("Expected Unhandled, got: $d", d is MeshCoreFrameCodec.Decoded.Unhandled)
    }

    // ---- DECODE: contact/advert ----------------------------------------

    /**
     * Build an advert/contact frame to the companion-radio upstream layout:
     *  [0]=code [1..32]=pubkey [33]=advType [100..131]=name(null-padded)
     *  [132..135]=ts(LE) [136..139]=latE6(LE) [140..143]=lonE6(LE).
     */
    private fun buildAdvertFrame(
        code: Byte,
        pubKey: ByteArray,
        advType: Int,
        name: String,
        tsSec: Int,
        latE6: Int,
        lonE6: Int,
        size: Int = 144,
    ): ByteArray {
        val pkt = ByteArray(size)
        pkt[0] = code
        System.arraycopy(pubKey, 0, pkt, 1, minOf(32, pubKey.size))
        pkt[33] = advType.toByte()
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        System.arraycopy(nameBytes, 0, pkt, 100, minOf(32, nameBytes.size))
        val bb = ByteBuffer.wrap(pkt).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(132, tsSec)
        bb.putInt(136, latE6)
        bb.putInt(140, lonE6)
        return pkt
    }

    @Test fun `decode advert with position yields contact node`() {
        val pub = ByteArray(32) { (it + 1).toByte() } // 01 02 03 04 ...
        val frame = buildAdvertFrame(
            code = 0x80.toByte(),
            pubKey = pub,
            advType = 0x01, // not repeater
            name = "ALPHA",
            tsSec = 1_700_000_000,
            latE6 = 47_658_800,
            lonE6 = -117_426_000,
        )
        val d = MeshCoreFrameCodec.decode(frame)
        assertTrue(d is MeshCoreFrameCodec.Decoded.Contact)
        d as MeshCoreFrameCodec.Decoded.Contact
        assertEquals(false, d.isRepeater)
        assertEquals("ALPHA", d.node.longName)
        // node id = first 6 bytes BE of pubkey: 01 02 03 04 05 06
        assertEquals(0x010203040506L, d.node.id)
        val pos = d.node.position
        assertNotNull("advert with non-zero lat/lon must produce a position", pos)
        assertEquals(47.6588, pos!!.lat, 1e-6)
        assertEquals(-117.426, pos.lon, 1e-6)
        assertEquals(1_700_000_000L, d.node.lastHeardEpoch)
    }

    @Test fun `decode advert with zero position has no position`() {
        val pub = ByteArray(32) { 0x10.toByte() }
        val frame = buildAdvertFrame(
            code = 0x80.toByte(),
            pubKey = pub,
            advType = 0x01,
            name = "NOFIX",
            tsSec = 1_700_000_000,
            latE6 = 0,
            lonE6 = 0,
        )
        val d = MeshCoreFrameCodec.decode(frame) as MeshCoreFrameCodec.Decoded.Contact
        assertNull("zero lat/lon must not produce a position", d.node.position)
    }

    @Test fun `decode repeater advert flags isRepeater`() {
        val pub = ByteArray(32) { 0x20.toByte() }
        val frame = buildAdvertFrame(
            code = 0x03, // RESP_CODE_CONTACT
            pubKey = pub,
            advType = 0x02, // ADV_TYPE_REPEATER
            name = "RPT-1",
            tsSec = 1_700_000_000,
            latE6 = 47_000_000,
            lonE6 = -117_000_000,
        )
        val d = MeshCoreFrameCodec.decode(frame) as MeshCoreFrameCodec.Decoded.Contact
        assertTrue(d.isRepeater)
    }

    @Test fun `decode advert too short returns unhandled`() {
        val d = MeshCoreFrameCodec.decode(byteArrayOf(0x80.toByte(), 0x01, 0x02))
        assertTrue(d is MeshCoreFrameCodec.Decoded.Unhandled)
    }

    // ---- DECODE: self-info ---------------------------------------------

    @Test fun `decode self_info parses pubkey name and position`() {
        // [0]=0x05 [4..35]=pubkey [36..39]=latE6 [40..43]=lonE6 [58..]=name
        val name = "MYNODE"
        val pkt = ByteArray(58 + name.length)
        pkt[0] = 0x05
        val pub = ByteArray(32) { (it + 0x40).toByte() }
        System.arraycopy(pub, 0, pkt, 4, 32)
        val bb = ByteBuffer.wrap(pkt).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(36, 47_658_800)
        bb.putInt(40, -117_426_000)
        System.arraycopy(name.toByteArray(StandardCharsets.UTF_8), 0, pkt, 58, name.length)
        val d = MeshCoreFrameCodec.decode(pkt)
        assertTrue(d is MeshCoreFrameCodec.Decoded.SelfInfo)
        d as MeshCoreFrameCodec.Decoded.SelfInfo
        assertEquals("MYNODE", d.nodeName)
        assertEquals(47.6588, d.lat!!, 1e-6)
        assertEquals(-117.426, d.lon!!, 1e-6)
        assertEquals(64, pkt[4].toInt() and 0xFF) // sanity on pubkey start
        assertTrue(d.pubKeyHex.startsWith("40")) // 0x40
    }

    // ---- DECODE: messages ----------------------------------------------

    @Test fun `decode contact message v1 extracts sender prefix and text`() {
        // [0]=0x07 [1..6]=senderPrefix [8]=txtType [13..]=text
        val text = "hello mesh"
        val pkt = ByteArray(13 + text.length)
        pkt[0] = 0x07
        val prefix = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0x11, 0x22, 0x33)
        System.arraycopy(prefix, 0, pkt, 1, 6)
        pkt[8] = 0x00 // txtType plain
        System.arraycopy(text.toByteArray(StandardCharsets.UTF_8), 0, pkt, 13, text.length)
        val d = MeshCoreFrameCodec.decode(pkt)
        assertTrue(d is MeshCoreFrameCodec.Decoded.ContactMessage)
        d as MeshCoreFrameCodec.Decoded.ContactMessage
        assertEquals("aabbcc112233", d.senderPubKeyPrefixHex)
        assertEquals("hello mesh", d.text)
    }

    @Test fun `decode contact message timestamped type shifts text by 4`() {
        // txtType==2 means text starts 4 bytes later (after a 4-byte ts).
        val text = "ts msg"
        val pkt = ByteArray(17 + text.length)
        pkt[0] = 0x07
        pkt[8] = 0x02 // txtType timestamped
        System.arraycopy(text.toByteArray(StandardCharsets.UTF_8), 0, pkt, 17, text.length)
        val d = MeshCoreFrameCodec.decode(pkt) as MeshCoreFrameCodec.Decoded.ContactMessage
        assertEquals("ts msg", d.text)
    }

    @Test fun `decode channel message v1 extracts index and text`() {
        // [0]=0x08 [1]=channelIndex [8..]=text
        val text = "channel hi"
        val pkt = ByteArray(8 + text.length)
        pkt[0] = 0x08
        pkt[1] = 0x03 // channel 3
        System.arraycopy(text.toByteArray(StandardCharsets.UTF_8), 0, pkt, 8, text.length)
        val d = MeshCoreFrameCodec.decode(pkt)
        assertTrue(d is MeshCoreFrameCodec.Decoded.ChannelMessage)
        d as MeshCoreFrameCodec.Decoded.ChannelMessage
        assertEquals(3, d.channelIndex)
        assertEquals("channel hi", d.text)
    }

    // ---- pubkey helpers -------------------------------------------------

    @Test fun `nodeId from pubkey is first 6 bytes big-endian`() {
        // deadbeef0011 = 6 bytes → 0xDEADBEEF0011L
        assertEquals(0xDEADBEEF0011L, MeshCoreFrameCodec.nodeIdFromPubKey("deadbeef0011"))
        // Short pubkey returns 0
        assertEquals(0L, MeshCoreFrameCodec.nodeIdFromPubKey("aa")) // too short
        assertEquals(0L, MeshCoreFrameCodec.nodeIdFromPubKey("deadbeef")) // 4 bytes = still short
    }

    @Test fun `pubkey prefix bytes`() {
        val p = MeshCoreFrameCodec.pubKeyPrefixBytes("aabbccddeeff0011", 6)
        assertNotNull(p)
        assertTrue(
            byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())
                .contentEquals(p!!),
        )
        assertNull(MeshCoreFrameCodec.pubKeyPrefixBytes("aabb", 6))
        assertNull(MeshCoreFrameCodec.pubKeyPrefixBytes(null, 6))
    }
}
