package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.channelMessage
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.decode
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.deviceConfig
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.fields
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.has
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.keyBytes
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.loraConfig
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.positionConfig
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.single
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.userMessage

/**
 * The setters write the radio's own message with one field changed.
 *
 * The firmware replaces a whole config (or channel, or owner) with what a
 * write carries, so a message that names only the changed field resets every
 * other field. Measured against firmware 2.7.26 on a simulated radio, a write
 * built from the changed field alone:
 *  - LoRa preset: region US to UNSET, hop limit 5 to 0, transmit on to off, boosted gain on to off
 *  - channel rename: key length 32 to 0, position precision 13 to 0
 *  - position interval: GPS mode ENABLED to DISABLED, position flags 811 to 0
 *  - device role or rebroadcast: time zone cleared, LED switch reset
 *  - owner rename: the licensed flag true to false
 *
 * Each test here hands a builder a message the way a radio reports it, with
 * extra fields in it (including numbers this app has never heard of), and
 * checks that the frame on the wire carries the same message with exactly one
 * field different, byte for byte. Expected messages are built by hand from the
 * fixtures in [AdminTestFrames], and frames are read back with [ProtoReader],
 * not with the code under test.
 */
class AdminReadModifyWriteTest {

    private val node = 0x0A0B0C0Du

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private fun assertSameBytes(message: String, expected: ByteArray, actual: ByteArray) {
        // Not assertArrayEquals: its failure message would print key bytes.
        assertEquals("$message (length)", expected.size, actual.size)
        assertTrue("$message (bytes differ)", expected.contentEquals(actual))
    }

    /** Check the frame carries [expected] as the [variant] of a set_config, and that the write reports the same message. */
    private fun assertSetConfig(variant: Int, expected: ByteArray, write: AdminMessageSerializer.AdminWrite?) {
        assertNotNull("the builder refused", write)
        val (gotVariant, message) = decode(write!!.frame).setConfig()
        assertEquals("Config variant", variant, gotVariant)
        assertSameBytes("message on the wire", expected, message)
        assertSameBytes("message the app will remember", expected, write.message)
    }

    // region device ------------------------------------------------------------

    @Test fun `a role change keeps every other device setting`() {
        val write = AdminMessageSerializer.buildSetDeviceRole(node, MeshRole.ROUTER, deviceConfig(role = 7, rebroadcast = 2))
        assertSetConfig(1, deviceConfig(role = 2, rebroadcast = 2), write)
    }

    @Test fun `the default role is removed from the wire and the rest stays`() {
        val write = AdminMessageSerializer.buildSetDeviceRole(node, MeshRole.CLIENT, deviceConfig())
        val withoutRole = ProtoMsg()
            .varint(6, 2).varint(7, 7200).string(11, "PST8PDT,M3.2.0,M11.1.0").bool(12, true).varint(13, 2)
            .bytes(99, byteArrayOf(0x11, 0x22, 0x33))
            .build()
        assertSetConfig(1, withoutRole, write)
    }

    @Test fun `a rebroadcast change keeps the role, time zone and the field this app does not know`() {
        val write = AdminMessageSerializer.buildSetRebroadcastMode(node, RebroadcastMode.KNOWN_ONLY, deviceConfig(role = 7, rebroadcast = 2))
        assertSetConfig(1, deviceConfig(role = 7, rebroadcast = 3), write)
    }

    @Test fun `a rebroadcast mode of ALL removes the field and the rest stays`() {
        val write = AdminMessageSerializer.buildSetRebroadcastMode(node, RebroadcastMode.ALL, deviceConfig())
        val withoutMode = ProtoMsg()
            .varint(1, 7).varint(7, 7200).string(11, "PST8PDT,M3.2.0,M11.1.0").bool(12, true).varint(13, 2)
            .bytes(99, byteArrayOf(0x11, 0x22, 0x33))
            .build()
        assertSetConfig(1, withoutMode, write)
    }

    @Test fun `a device config the radio sent empty gets just the edited field`() {
        val write = AdminMessageSerializer.buildSetRebroadcastMode(node, RebroadcastMode.LOCAL_ONLY, ByteArray(0))
        assertSetConfig(1, ProtoMsg().varint(6, 2).build(), write)
    }

    // endregion

    // region position ----------------------------------------------------------

    @Test fun `a position interval change keeps GPS mode, flags, smart broadcast and the rest`() {
        val write = AdminMessageSerializer.buildSetPositionBroadcastSecs(node, 300, positionConfig(secs = 900))
        assertSetConfig(2, positionConfig(secs = 300), write)
    }

    @Test fun `a position interval of zero removes the field and the rest stays`() {
        val write = AdminMessageSerializer.buildSetPositionBroadcastSecs(node, 0, positionConfig())
        val withoutSecs = ProtoMsg()
            .bool(2, true).varint(5, 120).varint(7, 811).varint(10, 150).varint(11, 30).varint(13, 1)
            .fixed32(77, 0x01020304)
            .build()
        assertSetConfig(2, withoutSecs, write)
    }

    @Test fun `a position interval above a day is capped at a day`() {
        val write = AdminMessageSerializer.buildSetPositionBroadcastSecs(node, 999_999, positionConfig())
        assertSetConfig(2, positionConfig(secs = 86_400), write)
    }

    @Test fun `the position interval is written to field 1 and never to the deprecated gps_enabled at 4`() {
        val (_, message) = decode(AdminMessageSerializer.buildSetPositionBroadcastSecs(node, 900, ByteArray(0))!!.frame).setConfig()
        val position = fields(message)
        assertEquals(900uL, position.single(1).varint)
        assertFalse(position.has(4))
    }

    // endregion

    // region lora --------------------------------------------------------------

    @Test fun `a preset change keeps region, hop limit, transmit switch and the rest`() {
        val write = AdminMessageSerializer.buildSetLoraPreset(node, MeshChannelPreset.MEDIUM_FAST, loraConfig(preset = 6, region = 1))
        assertSetConfig(6, loraConfig(preset = 4, region = 1), write)
    }

    @Test fun `a preset change never takes the radio off its region`() {
        val msg = decode(AdminMessageSerializer.buildSetLoraPreset(node, MeshChannelPreset.LONG_SLOW, loraConfig(region = 3))!!.frame).setConfig().second
        val lora = fields(msg)
        assertEquals("region", 3uL, lora.single(7).varint)
        assertEquals("hop limit", 5uL, lora.single(8).varint)
        assertEquals("transmit enabled", 1uL, lora.single(9).varint)
        assertEquals("boosted gain", 1uL, lora.single(13).varint)
        assertEquals("use_preset", 1uL, lora.single(1).varint)
        assertEquals("modem_preset", 1uL, lora.single(2).varint)
    }

    @Test fun `the default preset is removed from the wire and the rest stays`() {
        val write = AdminMessageSerializer.buildSetLoraPreset(node, MeshChannelPreset.LONG_FAST, loraConfig())
        val withoutPreset = ProtoMsg()
            .bool(1, true).varint(3, 250).varint(4, 11).varint(5, 5).varint(7, 1).varint(8, 5).bool(9, true)
            .varint(10, 27).bool(13, true).bytes(103, byteArrayOf(0x05, 0x06, 0x07)).varint(106, 1)
            .fixed32(200, 0x0A0B0C0D)
            .build()
        assertSetConfig(6, withoutPreset, write)
    }

    @Test fun `a region of UNSET leaves the radio's region alone`() {
        val write = AdminMessageSerializer.buildSetLoRaConfig(node, MeshRegion.UNSET, MeshChannelPreset.MEDIUM_FAST, loraConfig(preset = 6, region = 3))
        assertSetConfig(6, loraConfig(preset = 4, region = 3), write)
    }

    @Test fun `a real region replaces only the region`() {
        val write = AdminMessageSerializer.buildSetLoRaConfig(node, MeshRegion.EU_868, MeshChannelPreset.SHORT_FAST, loraConfig(preset = 6, region = 1))
        assertSetConfig(6, loraConfig(preset = 6, region = 3), write)
    }

    @Test fun `a region is added to a config that has none, in numeric order`() {
        val noRegion = ProtoMsg().bool(1, true).varint(2, 6).varint(8, 5).build()
        val write = AdminMessageSerializer.buildSetLoRaConfig(node, MeshRegion.US, MeshChannelPreset.SHORT_FAST, noRegion)
        assertSetConfig(6, ProtoMsg().bool(1, true).varint(2, 6).varint(7, 1).varint(8, 5).build(), write)
    }

    @Test fun `use_preset false removes the flag and nothing else changes`() {
        val write = AdminMessageSerializer.buildSetLoRaConfig(node, MeshRegion.UNSET, MeshChannelPreset.SHORT_FAST, loraConfig(preset = 6), usePreset = false)
        val msg = decode(write!!.frame).setConfig().second
        val lora = fields(msg)
        assertFalse("use_preset", lora.has(1))
        assertEquals(6uL, lora.single(2).varint)
        assertEquals(1uL, lora.single(7).varint)
    }

    // endregion

    // region channel -----------------------------------------------------------

    @Test fun `a channel rename keeps the key, id, uplink, downlink, module settings and role`() {
        val current = channelMessage(name = "Alpha")
        val write = AdminMessageSerializer.buildSetChannel0Name(node, "Bravo", current)
        assertNotNull(write)
        val channel = decode(write!!.frame).setChannel()
        assertSameBytes("channel on the wire", channelMessage(name = "Bravo"), channel)
        assertSameBytes("channel the app will remember", channelMessage(name = "Bravo"), write.message)
    }

    @Test fun `a channel rename keeps a 32 byte key`() {
        val key = keyBytes(0x77)
        val write = AdminMessageSerializer.buildSetChannel0Name(node, "Bravo", channelMessage(name = "Alpha", key = key))!!
        val settings = fields(fields(decode(write.frame).setChannel()).single(2).bytes)
        val sent = settings.single(2).bytes
        assertEquals("key length", 32, sent.size)
        assertTrue("key bytes", key.contentEquals(sent))
        assertEquals("Bravo", settings.single(3).bytes.toString(Charsets.UTF_8))
    }

    @Test fun `a channel rename keeps the position precision in the module settings`() {
        val write = AdminMessageSerializer.buildSetChannel0Name(node, "Bravo", channelMessage())!!
        val settings = fields(fields(decode(write.frame).setChannel()).single(2).bytes)
        val module = fields(settings.single(7).bytes)
        assertEquals("position_precision", 13uL, module.single(1).varint)
        assertEquals("is_muted", 1uL, module.single(2).varint)
        assertEquals("uplink", 1uL, settings.single(5).varint)
        assertEquals("downlink", 1uL, settings.single(6).varint)
        assertEquals("id", 0x5A5A1234u, settings.single(4).fixed32)
    }

    @Test fun `a rename of a channel in another slot keeps its index and role`() {
        val write = AdminMessageSerializer.buildSetChannel0Name(node, "Bravo", channelMessage(index = 2, name = "Alpha", role = 2))!!
        assertSameBytes("channel", channelMessage(index = 2, name = "Bravo", role = 2), decode(write.frame).setChannel())
    }

    @Test fun `a blank channel name clears the name and keeps the key`() {
        val write = AdminMessageSerializer.buildSetChannel0Name(node, "", channelMessage(name = "Alpha"))!!
        val settings = fields(fields(decode(write.frame).setChannel()).single(2).bytes)
        assertFalse("name", settings.has(3))
        assertEquals(32, settings.single(2).bytes.size)
    }

    @Test fun `a channel name is cut to the firmware's 11 bytes on a character boundary`() {
        val write = AdminMessageSerializer.buildSetChannel0Name(node, "台".repeat(5), channelMessage())!!
        val settings = fields(fields(decode(write.frame).setChannel()).single(2).bytes)
        val name = settings.single(3).bytes
        assertEquals("three whole characters, nine bytes", "台".repeat(3), name.toString(Charsets.UTF_8))
        assertTrue("fits char[12]", name.size <= 11)
    }

    @Test fun `a channel with no settings gets a settings message holding only the name`() {
        val write = AdminMessageSerializer.buildSetChannel0Name(node, "Bravo", ProtoMsg().varint(3, 1).build())!!
        val channel = fields(decode(write.frame).setChannel())
        assertEquals(1uL, channel.single(3).varint)
        assertEquals("Bravo", fields(channel.single(2).bytes).single(3).bytes.toString(Charsets.UTF_8))
    }

    // endregion

    // region owner -------------------------------------------------------------

    @Test fun `an owner rename keeps the licensed flag, key and everything else`() {
        val write = AdminMessageSerializer.buildSetOwner(node, "New Long", "NEW", userMessage(licensed = true))
        assertNotNull(write)
        val user = decode(write!!.frame).setOwner()
        assertSameBytes("user on the wire", userMessage(longName = "New Long", shortName = "NEW", licensed = true), user)
        assertSameBytes("user the app will remember", userMessage(longName = "New Long", shortName = "NEW", licensed = true), write.message)
        assertEquals("is_licensed", 1uL, fields(user).single(6).varint)
    }

    @Test fun `an owner rename keeps is_unmessagable when the radio reported it, even as false`() {
        val user = fields(decode(AdminMessageSerializer.buildSetOwner(node, "New Long", "NEW", userMessage())!!.frame).setOwner())
        assertTrue("optional bool 9 must still be present", user.has(9))
        assertEquals(0uL, user.single(9).varint)
        assertEquals("public key length", 32, user.single(8).bytes.size)
        assertEquals("hw_model", 37uL, user.single(5).varint)
        assertEquals("role", 7uL, user.single(7).varint)
    }

    @Test fun `an unlicensed owner stays unlicensed`() {
        val user = fields(decode(AdminMessageSerializer.buildSetOwner(node, "New Long", "NEW", userMessage(licensed = false))!!.frame).setOwner())
        assertFalse(user.has(6))
    }

    @Test fun `a blank name is left as the radio has it`() {
        val user = fields(decode(AdminMessageSerializer.buildSetOwner(node, "New Long", "  ", userMessage(shortName = "TNO"))!!.frame).setOwner())
        assertEquals("New Long", user.single(2).bytes.toString(Charsets.UTF_8))
        assertEquals("TNO", user.single(3).bytes.toString(Charsets.UTF_8))
    }

    @Test fun `owner names are cut to 39 and 4 bytes on a character boundary`() {
        val user = fields(decode(AdminMessageSerializer.buildSetOwner(node, "台".repeat(39), "台台", userMessage())!!.frame).setOwner())
        assertEquals("台".repeat(13), user.single(2).bytes.toString(Charsets.UTF_8))
        assertEquals("台", user.single(3).bytes.toString(Charsets.UTF_8))
    }

    @Test fun `the licensed flag changes only when asked`() {
        val on = fields(decode(AdminMessageSerializer.buildSetOwner(node, "A", "B", userMessage(licensed = false), isLicensed = true)!!.frame).setOwner())
        assertEquals(1uL, on.single(6).varint)
        val off = fields(decode(AdminMessageSerializer.buildSetOwner(node, "A", "B", userMessage(licensed = true), isLicensed = false)!!.frame).setOwner())
        assertFalse(off.has(6))
    }

    // endregion

    // region the frame ---------------------------------------------------------

    private fun everyWriteFrame(): Map<String, ByteArray> = mapOf(
        "role" to AdminMessageSerializer.buildSetDeviceRole(node, MeshRole.TAK, deviceConfig())!!.frame,
        "rebroadcast" to AdminMessageSerializer.buildSetRebroadcastMode(node, RebroadcastMode.KNOWN_ONLY, deviceConfig())!!.frame,
        "position" to AdminMessageSerializer.buildSetPositionBroadcastSecs(node, 60, positionConfig())!!.frame,
        "lora preset" to AdminMessageSerializer.buildSetLoraPreset(node, MeshChannelPreset.SHORT_FAST, loraConfig())!!.frame,
        "lora config" to AdminMessageSerializer.buildSetLoRaConfig(node, MeshRegion.US, MeshChannelPreset.SHORT_FAST, loraConfig())!!.frame,
        "channel 0 name" to AdminMessageSerializer.buildSetChannel0Name(node, "Bravo", channelMessage())!!.frame,
        "owner" to AdminMessageSerializer.buildSetOwner(node, "New Long", "NEW", userMessage())!!.frame,
        "begin" to AdminMessageSerializer.buildBeginEditSettings(node),
        "commit" to AdminMessageSerializer.buildCommitEditSettings(node),
    )

    @Test fun `every write is addressed to the radio, asks for an ack and a response, and goes to the admin port`() {
        for ((name, frame) in everyWriteFrame()) {
            val admin = decode(frame)
            assertEquals("$name: to", node, admin.to)
            assertTrue("$name: want_ack", admin.wantAck)
            assertTrue("$name: want_response", admin.wantResponse)
            assertEquals("$name: portnum", 6uL, admin.portnum)
        }
    }

    @Test fun `begin and commit are AdminMessage fields 64 and 65 as a bool`() {
        val begin = decode(AdminMessageSerializer.buildBeginEditSettings(node)).admin
        assertEquals(listOf(64), begin.map { it.number })
        assertEquals(0, begin.single().wire)
        assertEquals(1uL, begin.single().varint)

        val commit = decode(AdminMessageSerializer.buildCommitEditSettings(node)).admin
        assertEquals(listOf(65), commit.map { it.number })
        assertEquals(1uL, commit.single().varint)
    }

    // endregion

    // region what it will not build ---------------------------------------------

    @Test fun `bytes that are not a message give no write`() {
        val garbage = byteArrayOf(0x0a, 0x7f, 0x01) // field 1, length 127, one byte
        assertNull(AdminMessageSerializer.buildSetDeviceRole(node, MeshRole.TAK, garbage))
        assertNull(AdminMessageSerializer.buildSetRebroadcastMode(node, RebroadcastMode.ALL, garbage))
        assertNull(AdminMessageSerializer.buildSetPositionBroadcastSecs(node, 60, garbage))
        assertNull(AdminMessageSerializer.buildSetLoraPreset(node, MeshChannelPreset.SHORT_FAST, garbage))
        assertNull(AdminMessageSerializer.buildSetLoRaConfig(node, MeshRegion.US, MeshChannelPreset.SHORT_FAST, garbage))
        assertNull(AdminMessageSerializer.buildSetChannel0Name(node, "Bravo", garbage))
        assertNull(AdminMessageSerializer.buildSetOwner(node, "A", "B", garbage))
    }

    @Test fun `a result too big for one admin message gives no write`() {
        // The radio drops an AdminMessage longer than the 233 bytes a Data payload holds, so it is never sent.
        val huge = ProtoMsg().varint(1, 1).bytes(99, ByteArray(240) { 1 }).build()
        assertNull(AdminMessageSerializer.buildSetDeviceRole(node, MeshRole.TAK, huge))
        assertNull(AdminMessageSerializer.buildSetOwner(node, "A", "B", huge))
        assertNull(AdminMessageSerializer.buildSetChannel0Name(node, "Bravo", huge))
    }

    // endregion

    // region the import is still a full replacement -------------------------------

    @Test fun `an imported channel is a full replacement with the new name and key`() {
        val key = keyBytes(0x30)
        val write = AdminMessageSerializer.buildSetChannelWrite(node, MeshChannel(name = "Shared", psk = key), index = 0)
        val channel = fields(decode(write.frame).setChannel())
        val settings = fields(channel.single(2).bytes)
        assertEquals("Shared", settings.single(3).bytes.toString(Charsets.UTF_8))
        assertTrue(key.contentEquals(settings.single(2).bytes))
        assertFalse("nothing is carried over from the old channel", settings.has(7))
        assertEquals("PRIMARY", 1uL, channel.single(3).varint)
        assertSameBytes("remembered", decode(write.frame).setChannel(), write.message)
    }

    // endregion
}
