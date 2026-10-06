package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.channelMessage
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.configFrame

/**
 * A field the radio does not send is its proto3 default, not "unknown".
 *
 * A radio holding role CLIENT, rebroadcast mode ALL, modem preset LONG_FAST,
 * region UNSET or an unnamed primary channel does not put those on the wire.
 * The decoder used to turn the missing role and preset into null, and the
 * draft then kept its own TAK and its own preset instead of the radio's. A
 * number the app has no name for is the opposite case: it is unknown, and
 * stays unknown.
 */
class AdminResponseDefaultsTest {

    private fun decode(variant: Int, message: ByteArray): AdminResponse? =
        (MeshtasticProtoParser.parseFromRadio(configFrame(variant, message)) as FromRadioFrame.ConfigFrame).response

    @Test fun `an empty device config is role CLIENT and rebroadcast ALL`() {
        assertEquals(AdminResponse.DeviceConfig(MeshRole.CLIENT, RebroadcastMode.ALL), decode(1, ByteArray(0)))
    }

    @Test fun `a device config with other fields but no role is still role CLIENT`() {
        val message = ProtoMsg().varint(7, 7200).string(11, "PST8PDT,M3.2.0,M11.1.0").build()
        assertEquals(AdminResponse.DeviceConfig(MeshRole.CLIENT, RebroadcastMode.ALL), decode(1, message))
    }

    @Test fun `a role the app has no name for is unknown`() {
        // ROUTER_LATE (11) and CLIENT_BASE (12) are real firmware roles that MeshRole does not list.
        assertEquals(AdminResponse.DeviceConfig(null, RebroadcastMode.ALL), decode(1, ProtoMsg().varint(1, 11).build()))
        assertEquals(AdminResponse.DeviceConfig(null, RebroadcastMode.ALL), decode(1, ProtoMsg().varint(1, 12).build()))
    }

    @Test fun `a rebroadcast mode with no name is unknown and a named one decodes`() {
        assertEquals(AdminResponse.DeviceConfig(MeshRole.CLIENT, null), decode(1, ProtoMsg().varint(6, 5).build()))
        assertEquals(AdminResponse.DeviceConfig(MeshRole.TAK, RebroadcastMode.KNOWN_ONLY), decode(1, ProtoMsg().varint(1, 7).varint(6, 3).build()))
    }

    @Test fun `an empty position config is an interval of zero`() {
        assertEquals(AdminResponse.PositionConfig(0), decode(2, ByteArray(0)))
        assertEquals(AdminResponse.PositionConfig(900), decode(2, ProtoMsg().varint(1, 900).build()))
    }

    @Test fun `an empty lora config is preset LONG_FAST and region UNSET`() {
        assertEquals(AdminResponse.LoraConfig(MeshChannelPreset.LONG_FAST, MeshRegion.UNSET), decode(6, ByteArray(0)))
    }

    @Test fun `a lora config with a region and no preset is LONG_FAST in that region`() {
        val message = ProtoMsg().bool(1, true).varint(7, 3).varint(8, 5).build()
        assertEquals(AdminResponse.LoraConfig(MeshChannelPreset.LONG_FAST, MeshRegion.EU_868), decode(6, message))
    }

    @Test fun `a preset or region with no name is unknown`() {
        // Modem preset 7 (LONG_MODERATE) is not in MeshChannelPreset; region 99 does not exist yet in MeshRegion.
        assertEquals(AdminResponse.LoraConfig(null, null), decode(6, ProtoMsg().varint(2, 7).varint(7, 99).build()))
    }

    @Test fun `a config message that is damaged gives no report instead of defaults`() {
        // Length 9, one byte: not a message. Reading it as "all defaults" would tell the draft the radio is CLIENT.
        for (variant in listOf(1, 2, 6)) {
            val frame = (MeshtasticProtoParser.parseFromRadio(configFrame(variant, byteArrayOf(0x0a, 0x09, 0x01))) as FromRadioFrame.ConfigFrame)
            assertNull("variant $variant", frame.response)
        }
    }

    @Test fun `a channel with no settings has an empty name`() {
        val frame = MeshtasticProtoParser.parseFromRadio(AdminTestFrames.channelFrame(ProtoMsg().varint(3, 1).build())) as FromRadioFrame.ChannelFrame
        assertEquals(AdminResponse.Channel(index = 0, name = "", role = 1), frame.response)
    }

    @Test fun `a channel with a name and a key reports the name`() {
        val frame = MeshtasticProtoParser.parseFromRadio(AdminTestFrames.channelFrame(channelMessage(name = "Alpha"))) as FromRadioFrame.ChannelFrame
        assertEquals(AdminResponse.Channel(index = 0, name = "Alpha", role = 1), frame.response)
    }

    @Test fun `the same defaults apply to a get_config_response`() {
        val admin = ProtoMsg().msg(6, ProtoMsg().bytes(1, ByteArray(0))).build()
        assertEquals(AdminResponse.DeviceConfig(MeshRole.CLIENT, RebroadcastMode.ALL), AdminMessageParser.parse(admin))
    }
}
