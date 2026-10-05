package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.adminResponseFrame
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.channelFrame
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.channelMessage
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.configFrame
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.deviceConfig
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.keyBytes
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.loraConfig
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.myInfoFrame
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.nodeInfoFrame
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.packetFrame
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.positionConfig
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.userMessage

/**
 * [RadioSettingsCache], fed the way the app feeds it: raw FromRadio frames go
 * through [MeshtasticProtoParser], and what comes out goes to
 * [RadioSettingsCache.onFromRadio].
 *
 * Node numbers, names and key bytes are made up.
 */
class RadioSettingsCacheTest {

    private val me = 0x0A0B0C0D
    private val other = 0x01020304

    private fun feed(cache: RadioSettingsCache, frame: ByteArray, myNodeNum: Int? = me) {
        val parsed = MeshtasticProtoParser.parseFromRadio(frame)
        assertNotNull("the parser must recognise the frame", parsed)
        cache.onFromRadio(parsed!!, myNodeNum?.toUInt())
    }

    private fun assertSame(message: String, expected: ByteArray?, actual: ByteArray?) {
        // Not assertArrayEquals: its failure message would print key bytes.
        assertNotNull("$message: missing", actual)
        assertEquals("$message: length", expected!!.size, actual!!.size)
        assertTrue("$message: bytes differ", expected.contentEquals(actual))
    }

    // region the config download ---------------------------------------------

    @Test fun `every config variant the radio sends is kept, including the ones the screen cannot decode`() {
        val cache = RadioSettingsCache()
        val variants = mapOf(
            1 to deviceConfig(),
            2 to positionConfig(),
            3 to ProtoMsg().varint(4, 3).build(), // power
            4 to ProtoMsg().string(1, "ssid-example").varint(2, 1).build(), // network
            5 to ProtoMsg().varint(1, 30).build(), // display
            6 to loraConfig(),
            7 to ProtoMsg().bool(1, true).varint(2, 1).build(), // bluetooth
            8 to ProtoMsg().bytes(1, keyBytes(0x20)).build(), // security (made-up key)
            9 to ByteArray(0), // sessionkey: empty
            10 to ByteArray(0), // device_ui: empty
        )
        for ((variant, message) in variants) feed(cache, configFrame(variant, message))

        for ((variant, message) in variants) {
            assertSame("config variant $variant", message, cache.config(variant))
        }
        assertEquals(10, cache.size)
    }

    @Test fun `an empty config variant counts as present, not as missing`() {
        val cache = RadioSettingsCache()
        feed(cache, configFrame(1, ByteArray(0)))
        assertNotNull("an all-default DeviceConfig is sent as an empty message", cache.config(1))
        assertEquals(0, cache.config(1)!!.size)
    }

    @Test fun `every channel slot is kept under its own index, slot 0 included`() {
        val cache = RadioSettingsCache()
        for (i in 0..7) feed(cache, channelFrame(channelMessage(index = i, name = "Chan$i", role = if (i == 0) 1 else 0)))

        for (i in 0..7) {
            assertSame("channel $i", channelMessage(index = i, name = "Chan$i", role = if (i == 0) 1 else 0), cache.channel(i))
        }
    }

    @Test fun `a channel index past the last slot is not a channel`() {
        val cache = RadioSettingsCache()
        feed(cache, channelFrame(channelMessage(index = 8)))
        assertEquals(0, cache.size)
    }

    @Test fun `the radio's own node info is the owner record, another node's is not`() {
        val cache = RadioSettingsCache()
        feed(cache, nodeInfoFrame(other, userMessage(longName = "Someone Else", shortName = "SE")))
        assertNull("not our node", cache.owner())

        feed(cache, nodeInfoFrame(me, userMessage(longName = "Test Node One", shortName = "TNO")))
        assertSame("owner", userMessage(longName = "Test Node One", shortName = "TNO"), cache.owner())
    }

    @Test fun `the owner is the exact bytes of the user field, unknown fields and all`() {
        val cache = RadioSettingsCache()
        val user = userMessage()
        feed(cache, nodeInfoFrame(me, user))
        assertSame("owner", user, cache.owner())
    }

    @Test fun `a node info is not taken as ours before the radio has reported its node number`() {
        val cache = RadioSettingsCache()
        feed(cache, nodeInfoFrame(me, userMessage()), myNodeNum = null)
        assertNull(cache.owner())
    }

    @Test fun `a node info with no user gives no owner`() {
        val cache = RadioSettingsCache()
        feed(cache, nodeInfoFrame(me, null))
        assertNull(cache.owner())
    }

    // endregion

    // region admin responses -------------------------------------------------

    @Test fun `get_config_response, get_channel_response and get_owner_response all feed the cache`() {
        val cache = RadioSettingsCache()
        feed(cache, adminResponseFrame(from = me, to = me, adminField = 6, message = ProtoMsg().bytes(2, positionConfig(60)).build()))
        feed(cache, adminResponseFrame(from = me, to = me, adminField = 2, message = channelMessage(index = 3, name = "Local", role = 2)))
        feed(cache, adminResponseFrame(from = me, to = me, adminField = 4, message = userMessage(longName = "Reply Name")))

        assertSame("position", positionConfig(60), cache.config(2))
        assertSame("channel 3", channelMessage(index = 3, name = "Local", role = 2), cache.channel(3))
        assertSame("owner", userMessage(longName = "Reply Name"), cache.owner())
    }

    @Test fun `a later response replaces what the download said`() {
        val cache = RadioSettingsCache()
        feed(cache, configFrame(6, loraConfig(preset = 6)))
        feed(cache, adminResponseFrame(from = me, to = me, adminField = 6, message = ProtoMsg().bytes(6, loraConfig(preset = 4)).build()))
        assertSame("lora", loraConfig(preset = 4), cache.config(6))
    }

    @Test fun `an admin message from another node is not taken as the radio's own settings`() {
        // The radio hands the phone anything addressed to it. A response that does not come from our node
        // must not become the base of the next write.
        val cache = RadioSettingsCache()
        feed(cache, adminResponseFrame(from = other, to = me, adminField = 6, message = ProtoMsg().bytes(6, loraConfig(region = 0)).build()))
        feed(cache, adminResponseFrame(from = other, to = me, adminField = 4, message = userMessage()))
        feed(cache, adminResponseFrame(from = other, to = me, adminField = 2, message = channelMessage()))
        assertEquals(0, cache.size)
    }

    @Test fun `an admin response is ignored until the radio has reported its node number`() {
        val cache = RadioSettingsCache()
        feed(cache, adminResponseFrame(from = me, to = me, adminField = 6, message = ProtoMsg().bytes(6, loraConfig()).build()), myNodeNum = null)
        assertEquals(0, cache.size)
    }

    @Test fun `a packet on another port is not an admin response`() {
        val cache = RadioSettingsCache()
        val payload = ProtoMsg().bytes(6, ProtoMsg().bytes(6, loraConfig()).build()).build()
        feed(cache, packetFrame(from = me, to = me, portnum = 1, payload = payload))
        assertEquals(0, cache.size)
    }

    @Test fun `an admin message that is not a response, or is damaged, leaves the cache alone`() {
        val cache = RadioSettingsCache()
        feed(cache, adminResponseFrame(from = me, to = me, adminField = 34, message = ProtoMsg().bytes(6, loraConfig()).build()))
        assertEquals("a set_config echo is not a response", 0, cache.size)
        assertFalse(cache.ingestAdminMessage(byteArrayOf(0x32, 0x7f, 0x01)))
        assertEquals(0, cache.size)
    }

    // endregion

    // region lifecycle -------------------------------------------------------

    @Test fun `the first frame of a new download empties the cache`() {
        val cache = RadioSettingsCache()
        feed(cache, configFrame(6, loraConfig()))
        feed(cache, channelFrame(channelMessage()))
        assertEquals(2, cache.size)

        feed(cache, myInfoFrame(me))
        assertEquals("a new download must not start from the last one", 0, cache.size)
    }

    @Test fun `clear empties the cache`() {
        val cache = RadioSettingsCache()
        feed(cache, configFrame(6, loraConfig()))
        cache.clear()
        assertNull(cache.config(6))
        assertEquals(0, cache.size)
    }

    // endregion

    // region storage ----------------------------------------------------------

    @Test fun `bytes that are not a message are refused on the way in`() {
        val cache = RadioSettingsCache()
        assertFalse(cache.put(RadioSettingsCache.Key.Config(1), byteArrayOf(0x0a, 0x7f, 0x01)))
        assertNull(cache.config(1))
        assertTrue(cache.put(RadioSettingsCache.Key.Config(1), deviceConfig()))
    }

    @Test fun `what comes out is a copy, so a caller cannot change what is stored`() {
        val cache = RadioSettingsCache()
        val message = deviceConfig()
        cache.put(RadioSettingsCache.Key.Config(1), message)
        cache.config(1)!![0] = 0x7f
        message[1] = 0x7f
        assertSame("device config", deviceConfig(), cache.config(1))
    }

    @Test fun `toString shows which entries there are and never their contents`() {
        val cache = RadioSettingsCache()
        feed(cache, configFrame(8, ProtoMsg().bytes(1, keyBytes(0x55)).build()))
        feed(cache, channelFrame(channelMessage(name = "Secret Name")))
        val text = cache.toString()
        assertEquals("RadioSettingsCache(configs=[8], channels=[0], owner=false)", text)
        assertFalse(text.contains("Secret"))
    }

    // endregion
}
