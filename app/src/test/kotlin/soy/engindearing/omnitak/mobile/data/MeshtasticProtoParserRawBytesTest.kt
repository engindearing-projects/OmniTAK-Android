package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.channelFrame
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.channelMessage
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.configFrame
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.deviceConfig
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.keyBytes
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.loraConfig
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.nodeInfoFrame
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.positionConfig
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.userMessage

/**
 * What the parser hands on besides the decoded value: the bytes the radio sent.
 *
 * The settings screen shows a handful of fields, so the parser decodes a
 * handful. A settings write has to start from the whole message the radio
 * reported (the firmware replaces a whole config with what it receives), so
 * for config, channel and the radio's own user record the raw bytes now come
 * along for every variant, decoded or not.
 */
class MeshtasticProtoParserRawBytesTest {

    private fun parse(frame: ByteArray) = MeshtasticProtoParser.parseFromRadio(frame)

    private fun assertSame(message: String, expected: ByteArray?, actual: ByteArray?) {
        assertEquals("$message: length", expected!!.size, actual!!.size)
        assertTrue("$message: bytes differ", expected.contentEquals(actual))
    }

    @Test fun `a lora config frame keeps its decoded value and carries the raw Config message`() {
        val frame = parse(configFrame(6, loraConfig(preset = 6, region = 1))) as FromRadioFrame.ConfigFrame

        assertEquals(AdminResponse.LoraConfig(preset = MeshChannelPreset.SHORT_FAST, region = MeshRegion.US), frame.response)
        assertSame("raw", ProtoMsg().bytes(6, loraConfig(preset = 6, region = 1)).build(), frame.raw)
    }

    @Test fun `device and position config frames still decode`() {
        val device = parse(configFrame(1, deviceConfig(role = 7))) as FromRadioFrame.ConfigFrame
        assertEquals(AdminResponse.DeviceConfig(role = MeshRole.TAK, rebroadcastMode = RebroadcastMode.LOCAL_ONLY), device.response)

        val position = parse(configFrame(2, positionConfig(secs = 900))) as FromRadioFrame.ConfigFrame
        assertEquals(AdminResponse.PositionConfig(broadcastSecs = 900), position.response)
    }

    @Test fun `a config variant the screen does not decode still reaches the dispatcher with its raw bytes`() {
        // Power, network, display, bluetooth, security: before, each of these became Unknown and its bytes were gone.
        // The frame carries them only for as long as it is being dispatched: RadioSettingsCache keeps the three
        // variants the app patches and drops these (see RadioSettingsCacheTest).
        for (variant in listOf(3, 4, 5, 7, 8)) {
            val message = ProtoMsg().varint(1, variant).bytes(2, keyBytes(variant)).build()
            val frame = parse(configFrame(variant, message))
            assertTrue("variant $variant should be a config frame, got $frame", frame is FromRadioFrame.ConfigFrame)
            frame as FromRadioFrame.ConfigFrame
            assertNull("variant $variant has no decoded value", frame.response)
            assertSame("variant $variant raw", ProtoMsg().bytes(variant, message).build(), frame.raw)
        }
    }

    @Test fun `a channel frame keeps its decoded value and carries the raw Channel message`() {
        val channel = channelMessage(index = 2, name = "Local", role = 2)
        val frame = parse(channelFrame(channel)) as FromRadioFrame.ChannelFrame

        assertEquals(AdminResponse.Channel(index = 2, name = "Local", role = 2), frame.response)
        assertSame("raw", channel, frame.raw)
    }

    @Test fun `a node info carries the exact bytes of its user, unknown fields and all`() {
        val user = userMessage(longName = "Test Node One", shortName = "TNO")
        val frame = parse(nodeInfoFrame(0x0A0B0C0D, user)) as FromRadioFrame.NodeInfoFrame

        assertEquals("Test Node One", frame.node.longName)
        assertEquals("TNO", frame.node.shortName)
        assertSame("userRaw", user, frame.userRaw)
    }

    @Test fun `a node info with no user has no raw user`() {
        val frame = parse(nodeInfoFrame(0x0A0B0C0D, null)) as FromRadioFrame.NodeInfoFrame
        assertNull(frame.userRaw)
    }

    @Test fun `frames compare by content and print sizes, never the bytes`() {
        val a = parse(configFrame(8, ProtoMsg().bytes(1, keyBytes(0x20)).build()))
        val b = parse(configFrame(8, ProtoMsg().bytes(1, keyBytes(0x20)).build()))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        val text = a.toString()
        assertTrue(text, text.contains("raw="))
        assertFalse("a frame can hold a key; its text must not", text.contains("[B@") || text.contains(keyBytes(0x20)[0].toString()))
    }
}
