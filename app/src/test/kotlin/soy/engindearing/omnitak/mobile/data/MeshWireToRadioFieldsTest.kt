package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The ToRadio frames we send, checked field by field against the official
 * protos. Frames are decoded with a generic reader and compared with the
 * numbers and wire types below, so a wrong number cannot pass by matching a
 * byte pattern that was copied from the code under test.
 *
 *   ToRadio    { MeshPacket packet = 1;  uint32 want_config_id = 3; }
 *   MeshPacket { fixed32 to = 2;  uint32 channel = 3;  Data decoded = 4;
 *                fixed32 id = 6;  uint32 hop_limit = 9;  bool want_ack = 10; }
 *   Data       { PortNum portnum = 1;  bytes payload = 2;  bool want_response = 3;
 *                fixed32 dest = 4;  fixed32 source = 5;  fixed32 request_id = 6; }
 *   AdminMessage { uint32 get_channel_request = 1;  bool get_owner_request = 3;
 *                  ConfigType get_config_request = 5;  User set_owner = 32;
 *                  Channel set_channel = 33;  Config set_config = 34; }
 *   Config     { DeviceConfig device = 1;  PositionConfig position = 2;  LoRaConfig lora = 6; }
 *   DeviceConfig { Role role = 1;  RebroadcastMode rebroadcast_mode = 6; }
 *   LoRaConfig { bool use_preset = 1;  ModemPreset modem_preset = 2;  RegionCode region = 7; }
 *   User       { string id = 1;  string long_name = 2;  string short_name = 3; }
 *
 * The first test here is the one that failed before the fix: `want_response`
 * was written to field 5.
 */
class MeshWireToRadioFieldsTest {

    private companion object {
        const val TO_RADIO_PACKET = 1

        const val PACKET_TO = 2
        const val PACKET_CHANNEL = 3
        const val PACKET_DECODED = 4
        const val PACKET_ID = 6
        const val PACKET_HOP_LIMIT = 9
        const val PACKET_WANT_ACK = 10

        const val DATA_PORTNUM = 1
        const val DATA_PAYLOAD = 2
        const val DATA_WANT_RESPONSE = 3
        const val DATA_DEST = 4
        const val DATA_SOURCE = 5

        const val PORTNUM_ADMIN_APP = 6

        const val NODE = 0x0A0B0C0Du
    }

    /** One decoded field. [value] is a ULong for varint, a UInt for fixed32,
     *  and a ByteArray for length-delimited. */
    private data class Field(val number: Int, val wire: Int, val value: Any?) {
        val varint: ULong get() = value as ULong
        val fixed32: UInt get() = value as UInt
        val bytes: ByteArray get() = value as ByteArray
    }

    private fun fields(data: ByteArray): List<Field> {
        val r = ProtoReader(data)
        val out = mutableListOf<Field>()
        while (r.hasMore()) {
            val tag = r.readTag() ?: break
            val value: Any? = when (tag.wire) {
                0 -> r.readVarint()
                5 -> r.readFixed32()
                2 -> r.readLengthDelimited()
                else -> error("unexpected wire type ${tag.wire} for field ${tag.field}")
            }
            out += Field(tag.field, tag.wire, value)
        }
        return out
    }

    private fun List<Field>.single(number: Int): Field {
        val matches = filter { it.number == number }
        assertEquals("expected exactly one field $number in ${map { it.number }}", 1, matches.size)
        return matches.single()
    }

    private fun List<Field>.has(number: Int) = any { it.number == number }

    private fun packetFields(toRadio: ByteArray): List<Field> {
        val top = fields(toRadio)
        assertEquals("ToRadio should carry only the packet", listOf(TO_RADIO_PACKET), top.map { it.number })
        return fields(top.single(TO_RADIO_PACKET).bytes)
    }

    private fun dataFields(toRadio: ByteArray): List<Field> = fields(packetFields(toRadio).single(PACKET_DECODED).bytes)

    private fun adminPayload(toRadio: ByteArray): List<Field> = fields(dataFields(toRadio).single(DATA_PAYLOAD).bytes)

    // region want_response (the fix) ------------------------------------------

    @Test fun want_response_is_data_field_3_as_a_varint() {
        val frame = MeshWire.buildToRadio(
            portnum = PORTNUM_ADMIN_APP.toULong(),
            payload = byteArrayOf(1),
            wantResponse = true,
            packetId = 7u,
        )
        val data = dataFields(frame)

        val wantResponse = data.single(DATA_WANT_RESPONSE)
        assertEquals("want_response is a bool varint", 0, wantResponse.wire)
        assertEquals(1uL, wantResponse.varint)
        assertFalse("field 5 is `source`, a fixed32; want_response must not be written there", data.has(DATA_SOURCE))
    }

    @Test fun want_response_is_left_out_when_not_asked_for() {
        val data = dataFields(MeshWire.buildToRadio(portnum = 1UL, payload = byteArrayOf(1), packetId = 7u))
        assertFalse(data.has(DATA_WANT_RESPONSE))
        assertFalse(data.has(DATA_SOURCE))
    }

    @Test fun every_admin_frame_asks_for_a_response_on_field_3_and_writes_nothing_to_field_5() {
        val channel = MeshChannel(name = "Test Chan", psk = ByteArray(16) { it.toByte() })
        val frames = mapOf(
            "set_owner" to AdminMessageSerializer.buildSetOwner(NODE, "Test Node Alpha", "TNA", ByteArray(0))!!.frame,
            "set_channel" to AdminMessageSerializer.buildSetChannel(NODE, channel, index = 1),
            "set_channel0_name" to AdminMessageSerializer.buildSetChannel0Name(NODE, "Test Chan", ByteArray(0))!!.frame,
            "set_device_role" to AdminMessageSerializer.buildSetDeviceRole(NODE, MeshRole.TAK, ByteArray(0))!!.frame,
            "set_rebroadcast_mode" to AdminMessageSerializer.buildSetRebroadcastMode(NODE, RebroadcastMode.KNOWN_ONLY, ByteArray(0))!!.frame,
            "set_position_secs" to AdminMessageSerializer.buildSetPositionBroadcastSecs(NODE, 900, ByteArray(0))!!.frame,
            "set_lora_preset" to AdminMessageSerializer.buildSetLoraPreset(NODE, MeshChannelPreset.LONG_FAST, ByteArray(0))!!.frame,
            "set_lora_config" to AdminMessageSerializer.buildSetLoRaConfig(NODE, MeshRegion.US, MeshChannelPreset.LONG_FAST, ByteArray(0))!!.frame,
            "begin_edit_settings" to AdminMessageSerializer.buildBeginEditSettings(NODE),
            "commit_edit_settings" to AdminMessageSerializer.buildCommitEditSettings(NODE),
            "get_owner" to AdminMessageSerializer.buildGetOwnerRequest(NODE),
            "get_config" to AdminMessageSerializer.buildGetConfigRequest(NODE, 0),
            "get_channel" to AdminMessageSerializer.buildGetChannelRequest(NODE, 0),
        )
        for ((name, frame) in frames) {
            val data = dataFields(frame)
            assertEquals("$name: portnum", PORTNUM_ADMIN_APP.toULong(), data.single(DATA_PORTNUM).varint)
            assertEquals("$name: want_response", 1uL, data.single(DATA_WANT_RESPONSE).varint)
            assertFalse("$name: Data.source (field 5) must not be written", data.has(DATA_SOURCE))
            assertFalse("$name: Data.dest (field 4) must not be written", data.has(DATA_DEST))
        }
    }

    // endregion

    // region MeshPacket and Data ---------------------------------------------

    @Test fun mesh_packet_fields_use_the_official_numbers_and_wire_types() {
        val frame = MeshWire.buildToRadio(
            portnum = 1UL,
            payload = byteArrayOf(0x68, 0x69),
            to = 0x12345678u,
            channelIndex = 2u,
            packetId = 0xA1B2C3D4u,
            hopLimit = 3u,
            wantAck = true,
        )
        val packet = packetFields(frame)

        assertEquals(5, packet.single(PACKET_TO).wire)
        assertEquals(0x12345678u, packet.single(PACKET_TO).fixed32)
        assertEquals(0, packet.single(PACKET_CHANNEL).wire)
        assertEquals(2uL, packet.single(PACKET_CHANNEL).varint)
        assertEquals(5, packet.single(PACKET_ID).wire)
        assertEquals(0xA1B2C3D4u, packet.single(PACKET_ID).fixed32)
        assertEquals(0, packet.single(PACKET_HOP_LIMIT).wire)
        assertEquals(3uL, packet.single(PACKET_HOP_LIMIT).varint)
        assertEquals(0, packet.single(PACKET_WANT_ACK).wire)
        assertEquals(1uL, packet.single(PACKET_WANT_ACK).varint)

        val data = fields(packet.single(PACKET_DECODED).bytes)
        assertEquals(1uL, data.single(DATA_PORTNUM).varint)
        assertEquals(listOf<Byte>(0x68, 0x69), data.single(DATA_PAYLOAD).bytes.toList())
    }

    // endregion

    // region AdminMessage numbers ---------------------------------------------

    @Test fun get_requests_use_fields_1_3_and_5() {
        // get_owner_request = 3 (bool)
        assertEquals(1uL, adminPayload(AdminMessageSerializer.buildGetOwnerRequest(NODE)).single(3).varint)
        // get_config_request = 5 (enum), LORA_CONFIG = 5
        assertEquals(5uL, adminPayload(AdminMessageSerializer.buildGetConfigRequest(NODE, 5)).single(5).varint)
        // get_config_request carries DEVICE_CONFIG = 0 as an explicit oneof member
        assertEquals(0uL, adminPayload(AdminMessageSerializer.buildGetConfigRequest(NODE, 0)).single(5).varint)
        // get_channel_request = 1 (uint32), sent as index + 1
        assertEquals(1uL, adminPayload(AdminMessageSerializer.buildGetChannelRequest(NODE, 0)).single(1).varint)
        assertEquals(4uL, adminPayload(AdminMessageSerializer.buildGetChannelRequest(NODE, 3)).single(1).varint)
    }

    @Test fun set_owner_is_field_32_with_the_user_names_on_2_and_3() {
        val admin = adminPayload(AdminMessageSerializer.buildSetOwner(NODE, "Test Node Alpha", "TNA", ByteArray(0))!!.frame)
        val user = fields(admin.single(32).bytes)
        assertEquals("Test Node Alpha", user.single(2).bytes.toString(Charsets.UTF_8))
        assertEquals("TNA", user.single(3).bytes.toString(Charsets.UTF_8))
    }

    @Test fun set_config_is_field_34_and_picks_the_sub_config_by_number() {
        // device = 1, role = 1 (TAK = 7)
        val role = fields(fields(adminPayload(AdminMessageSerializer.buildSetDeviceRole(NODE, MeshRole.TAK, ByteArray(0))!!.frame).single(34).bytes).single(1).bytes)
        assertEquals(7uL, role.single(1).varint)

        // device = 1, rebroadcast_mode = 6 (KNOWN_ONLY = 3)
        val rebroadcast = fields(
            fields(adminPayload(AdminMessageSerializer.buildSetRebroadcastMode(NODE, RebroadcastMode.KNOWN_ONLY, ByteArray(0))!!.frame).single(34).bytes)
                .single(1).bytes,
        )
        assertEquals(3uL, rebroadcast.single(6).varint)

        // lora = 6: use_preset = 1, modem_preset = 2 (MEDIUM_FAST = 4), region = 7 (TW = 8)
        val lora = fields(
            fields(
                adminPayload(
                    AdminMessageSerializer.buildSetLoRaConfig(NODE, MeshRegion.TW, MeshChannelPreset.MEDIUM_FAST, ByteArray(0))!!.frame,
                ).single(34).bytes,
            ).single(6).bytes,
        )
        assertEquals(1uL, lora.single(1).varint)
        assertEquals(4uL, lora.single(2).varint)
        assertEquals(8uL, lora.single(7).varint)

        val preset = fields(
            fields(
                adminPayload(AdminMessageSerializer.buildSetLoraPreset(NODE, MeshChannelPreset.SHORT_FAST, ByteArray(0))!!.frame).single(34).bytes,
            ).single(6).bytes,
        )
        assertEquals(1uL, preset.single(1).varint)
        assertEquals(6uL, preset.single(2).varint)
    }

    // endregion
}
