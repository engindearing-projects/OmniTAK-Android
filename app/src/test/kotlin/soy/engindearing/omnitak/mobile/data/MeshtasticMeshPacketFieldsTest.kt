package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MeshPacket metadata decoding against the official mesh.proto numbers.
 *
 *   message MeshPacket {
 *     fixed32 from = 1;  fixed32 to = 2;  uint32 channel = 3;
 *     oneof payload_variant { Data decoded = 4;  bytes encrypted = 5; }
 *     fixed32 id = 6;  optional fixed32 rx_time = 7;  float rx_snr = 8;
 *     uint32 hop_limit = 9;  bool want_ack = 10;  Priority priority = 11;
 *     optional int32 rx_rssi = 12;  bool via_mqtt = 14;  uint32 hop_start = 15;
 *     bytes public_key = 16;  TransportMechanism transport_mechanism = 21;
 *   }
 *   message Data { PortNum portnum = 1;  bytes payload = 2;  ...  fixed32 request_id = 6;  ... }
 *
 * The parser used to read rx_time from field 8, rx_snr from field 9 and
 * hop_limit from field 10, which are rx_snr, hop_limit and want_ack.
 *
 * All ids and payloads are made up.
 */
class MeshtasticMeshPacketFieldsTest {

    private companion object {
        const val FROM = 1
        const val TO = 2
        const val CHANNEL = 3
        const val DECODED = 4
        const val ENCRYPTED = 5
        const val ID = 6
        const val RX_TIME = 7
        const val RX_SNR = 8
        const val HOP_LIMIT = 9
        const val WANT_ACK = 10
        const val PRIORITY = 11
        const val RX_RSSI = 12
        const val VIA_MQTT = 14
        const val HOP_START = 15
        const val PUBLIC_KEY = 16
        const val TRANSPORT_MECHANISM = 21

        // Data
        const val DATA_PORTNUM = 1
        const val DATA_PAYLOAD = 2
        const val DATA_REQUEST_ID = 6

        // FromRadio.packet
        const val FROM_RADIO_PACKET = 2

        const val SENDER = 0x01020304
    }

    private fun decode(packet: ProtoMsg.() -> Unit): MeshPacketDecoded {
        val body = ProtoMsg().fixed32(FROM, SENDER).fixed32(TO, -1).apply(packet)
        val frame = ProtoMsg().msg(FROM_RADIO_PACKET, body).build()
        val parsed = MeshtasticProtoParser.parseFromRadio(frame)
        assertTrue("expected a packet frame, got $parsed", parsed is FromRadioFrame.Packet)
        return (parsed as FromRadioFrame.Packet).packet
    }

    @Test fun rx_time_is_the_fixed32_in_field_7() {
        val pkt = decode { fixed32(RX_TIME, 1_790_000_123) }
        assertEquals(1_790_000_123L, pkt.rxTime)
        assertNull("rx_time must not be read as an SNR", pkt.rxSnr)
    }

    @Test fun rx_snr_is_the_float_in_field_8_and_not_an_rx_time() {
        val pkt = decode { float(RX_SNR, -7.25f) }
        assertEquals(-7.25f, pkt.rxSnr!!, 0f)
        assertNull("the float bits of an SNR are not an epoch", pkt.rxTime)
    }

    @Test fun hop_limit_is_field_9() {
        assertEquals(3, decode { varint(HOP_LIMIT, 3) }.hopLimit)
    }

    @Test fun want_ack_in_field_10_is_not_the_hop_limit() {
        assertNull(decode { bool(WANT_ACK, true) }.hopLimit)
    }

    @Test fun rx_rssi_is_a_signed_int32_in_field_12() {
        // A negative int32 is a 10-byte sign-extended varint.
        assertEquals(-92, decode { varint(RX_RSSI, -92) }.rxRssi)
    }

    @Test fun public_key_in_field_16_is_not_an_rssi() {
        val pkt = decode { bytes(PUBLIC_KEY, ByteArray(32) { it.toByte() }) }
        assertNull(pkt.rxRssi)
    }

    @Test fun rx_time_zero_is_unknown() {
        assertNull(decode { fixed32(RX_TIME, 0) }.rxTime)
    }

    @Test fun a_full_live_packet_decodes_each_field_on_its_own_number() {
        val payload = byteArrayOf(1, 2, 3)
        val pkt = decode {
            varint(CHANNEL, 2)
            msg(DECODED, ProtoMsg().varint(DATA_PORTNUM, 3).bytes(DATA_PAYLOAD, payload))
            fixed32(ID, 0x11223344)
            fixed32(RX_TIME, 1_790_000_123)
            float(RX_SNR, 5.5f)
            varint(HOP_LIMIT, 2)
            bool(WANT_ACK, true)
            varint(PRIORITY, 64)
            varint(RX_RSSI, -101)
            bool(VIA_MQTT, false)
            varint(HOP_START, 3)
            bytes(PUBLIC_KEY, ByteArray(32) { 0x7F })
        }

        assertEquals(SENDER.toUInt(), pkt.from)
        assertEquals(0xFFFFFFFFu, pkt.to)
        assertEquals(2u, pkt.channel)
        assertEquals(3u, pkt.portnum)
        assertArrayEquals(payload, pkt.payload)
        assertEquals(1_790_000_123L, pkt.rxTime)
        assertEquals(5.5f, pkt.rxSnr!!, 0f)
        assertEquals(2, pkt.hopLimit)
        assertEquals(-101, pkt.rxRssi)
    }

    @Test fun the_request_id_is_the_fixed32_in_field_6_of_data_and_not_the_packet_id() {
        val pkt = decode {
            fixed32(ID, 0x11223344)
            msg(DECODED, ProtoMsg().varint(DATA_PORTNUM, 6).bytes(DATA_PAYLOAD, byteArrayOf(1)).fixed32(DATA_REQUEST_ID, 0x55667788))
        }
        assertEquals(0x55667788u, pkt.requestId)
        assertEquals(6u, pkt.portnum)
    }

    @Test fun a_packet_that_answers_nothing_has_no_request_id() {
        assertNull(decode { msg(DECODED, ProtoMsg().varint(DATA_PORTNUM, 6).bytes(DATA_PAYLOAD, byteArrayOf(1))) }.requestId)
        assertNull("a request id on the wrong wire type is not one", decode { msg(DECODED, ProtoMsg().varint(DATA_REQUEST_ID, 5)) }.requestId)
    }

    @Test fun via_mqtt_and_transport_mechanism_are_read_from_their_own_numbers() {
        val plain = decode { varint(HOP_LIMIT, 3) }
        assertEquals(false, plain.viaMqtt)
        assertEquals(0, plain.transportMechanism)

        val pkt = decode {
            bool(VIA_MQTT, true)
            varint(HOP_START, 3)
            varint(TRANSPORT_MECHANISM, 2)
        }
        assertEquals(true, pkt.viaMqtt)
        assertEquals(2, pkt.transportMechanism)
    }

    @Test fun a_transport_mechanism_that_is_not_zero_never_reads_as_zero() {
        // However large the number, it is not the radio's own.
        assertEquals(true, decode { varint(TRANSPORT_MECHANISM, 0x1_0000_0000L) }.transportMechanism != 0)
        assertEquals(0, decode { varint(TRANSPORT_MECHANISM, 0) }.transportMechanism)
    }

    @Test fun an_encrypted_packet_keeps_its_metadata_and_has_no_payload() {
        val pkt = decode {
            bytes(ENCRYPTED, ByteArray(16) { 0x55 })
            fixed32(RX_TIME, 1_790_000_123)
            float(RX_SNR, 4.0f)
        }
        assertEquals(1_790_000_123L, pkt.rxTime)
        assertEquals(4.0f, pkt.rxSnr!!, 0f)
        assertEquals(0, pkt.payload.size)
    }
}
