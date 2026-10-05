package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.ui.screens.relativeTime

/**
 * NodeInfo decoding against the official mesh.proto numbers.
 *
 * Every frame here is built with the field numbers and wire types from
 * mesh.proto (see the constants), so each test fails if the parser reads a
 * different number. The numbers are the same in every tagged release since
 * v2.0.12, and in firmware 2.7.26.
 *
 *   message NodeInfo {
 *     uint32 num = 1;  User user = 2;  Position position = 3;
 *     float snr = 4;   fixed32 last_heard = 5;  DeviceMetrics device_metrics = 6;
 *     uint32 channel = 7;  bool via_mqtt = 8;  optional uint32 hops_away = 9;
 *     bool is_favorite = 10;  bool is_ignored = 11;
 *   }
 *
 * All node ids, names and coordinates are made up.
 */
class MeshtasticNodeInfoFieldsTest {

    private companion object {
        // NodeInfo
        const val NUM = 1
        const val USER = 2
        const val POSITION = 3
        const val SNR = 4
        const val LAST_HEARD = 5
        const val DEVICE_METRICS = 6
        const val CHANNEL = 7
        const val VIA_MQTT = 8
        const val HOPS_AWAY = 9
        const val IS_FAVORITE = 10
        const val IS_IGNORED = 11

        // User
        const val USER_LONG_NAME = 2
        const val USER_SHORT_NAME = 3
        const val USER_ROLE = 7

        // Position
        const val POS_LATITUDE_I = 1
        const val POS_LONGITUDE_I = 2
        const val POS_ALTITUDE = 3
        const val POS_TIME = 4

        // DeviceMetrics
        const val METRICS_BATTERY_LEVEL = 1
        const val METRICS_VOLTAGE = 2
        const val METRICS_CHANNEL_UTILIZATION = 3

        // FromRadio.node_info
        const val FROM_RADIO_NODE_INFO = 4

        const val NODE_ID = 0x0A0B0C0D
    }

    private fun decode(nodeInfo: ProtoMsg.() -> Unit): MeshNode {
        val body = ProtoMsg().varint(NUM, NODE_ID.toLong()).apply(nodeInfo)
        val frame = ProtoMsg().msg(FROM_RADIO_NODE_INFO, body).build()
        val parsed = MeshtasticProtoParser.parseFromRadio(frame)
        assertTrue("expected a NodeInfo frame, got $parsed", parsed is FromRadioFrame.NodeInfoFrame)
        return (parsed as FromRadioFrame.NodeInfoFrame).node
    }

    // region one field per test ---------------------------------------------

    @Test fun num_is_field_1() {
        assertEquals(NODE_ID.toLong(), decode { }.id)
    }

    @Test fun user_is_the_message_in_field_2() {
        val node = decode {
            msg(
                USER,
                ProtoMsg()
                    .string(USER_LONG_NAME, "Test Node Alpha")
                    .string(USER_SHORT_NAME, "TNA")
                    .varint(USER_ROLE, MeshNode.ROLE_TAK),
            )
        }
        assertEquals("Test Node Alpha", node.longName)
        assertEquals("TNA", node.shortName)
        assertEquals(MeshNode.ROLE_TAK, node.role)
    }

    @Test fun a_length_delimited_field_4_is_not_a_user() {
        // Field 4 is snr (a float). A length-delimited value there is not the
        // User message, so the names must fall back to the id-derived defaults.
        val node = decode {
            bytes(4, ProtoMsg().string(USER_LONG_NAME, "Wrong Place").string(USER_SHORT_NAME, "WP").build())
        }
        assertEquals("Node 0A0B0C0D", node.longName)
        assertEquals("0C0D", node.shortName)
    }

    @Test fun position_is_the_message_in_field_3() {
        val node = decode {
            msg(
                POSITION,
                ProtoMsg()
                    .fixed32(POS_LATITUDE_I, 12_345_678)
                    .fixed32(POS_LONGITUDE_I, -23_456_789)
                    .varint(POS_ALTITUDE, 15)
                    .fixed32(POS_TIME, 1_790_000_000),
            )
        }
        assertNotNull("a NodeInfo position must be read", node.position)
        assertEquals(1.2345678, node.position!!.lat, 1e-9)
        assertEquals(-2.3456789, node.position!!.lon, 1e-9)
        assertEquals(15, node.position!!.altitudeM)
    }

    @Test fun snr_is_the_float_in_field_4() {
        val node = decode { float(SNR, -6.5f) }
        assertEquals(-6.5, node.snr!!, 1e-6)
        assertNull("snr must not leak into last heard", node.lastHeardEpoch)
    }

    @Test fun last_heard_is_the_fixed32_in_field_5() {
        val node = decode { fixed32(LAST_HEARD, 1_790_000_000) }
        assertEquals(1_790_000_000L, node.lastHeardEpoch)
        assertNull("last_heard must not be read as an SNR", node.snr)
    }

    @Test fun battery_is_battery_level_inside_device_metrics_field_6() {
        val node = decode {
            msg(
                DEVICE_METRICS,
                ProtoMsg()
                    .varint(METRICS_BATTERY_LEVEL, 87)
                    .float(METRICS_VOLTAGE, 4.05f)
                    .float(METRICS_CHANNEL_UTILIZATION, 12.5f),
            )
        }
        assertEquals(87, node.batteryLevel)
    }

    @Test fun hops_away_is_the_varint_in_field_9() {
        val node = decode { varint(HOPS_AWAY, 3) }
        assertEquals(3, node.hopDistance)
        assertNull("hops_away must not be read as an epoch", node.lastHeardEpoch)
    }

    @Test fun zero_hops_away_is_a_direct_neighbour_not_unknown() {
        assertEquals(0, decode { varint(HOPS_AWAY, 0) }.hopDistance)
    }

    @Test fun missing_hops_away_stays_unknown() {
        assertNull(decode { }.hopDistance)
    }

    // endregion

    // region fields that must not be mistaken for anything ------------------

    @Test fun is_ignored_is_not_hops_away() {
        assertNull(decode { bool(IS_IGNORED, true) }.hopDistance)
    }

    @Test fun is_favorite_is_not_device_metrics() {
        assertNull(decode { bool(IS_FAVORITE, true) }.batteryLevel)
    }

    @Test fun channel_via_mqtt_is_favorite_and_is_ignored_are_not_mistaken_for_anything() {
        val node = decode {
            varint(CHANNEL, 2)
            bool(VIA_MQTT, true)
            bool(IS_FAVORITE, true)
            bool(IS_IGNORED, true)
        }
        assertNull(node.snr)
        assertNull(node.lastHeardEpoch)
        assertNull(node.hopDistance)
        assertNull(node.batteryLevel)
        assertNull(node.position)
        assertNull(node.role)
    }

    // endregion

    // region last heard is unknown, never "now" ------------------------------

    @Test fun missing_last_heard_is_unknown_not_now() {
        assertNull(decode { }.lastHeardEpoch)
    }

    @Test fun last_heard_zero_is_unknown() {
        assertNull(decode { fixed32(LAST_HEARD, 0) }.lastHeardEpoch)
    }

    @Test fun an_unknown_last_heard_renders_as_a_dash() {
        assertEquals("—", relativeTime(decode { }.lastHeardEpoch))
    }

    // endregion

    /**
     * A node-database entry shaped like the one in the bug report: a real
     * epoch in last_heard, a small hop count, a negative SNR. Before the fix
     * this rendered "20731d ago" and an SNR of about 1.1e26.
     */
    @Test fun a_node_database_entry_decodes_to_sane_values() {
        val lastHeard = 1_790_000_000
        val node = decode {
            msg(
                USER,
                ProtoMsg().string(USER_LONG_NAME, "Test Node Bravo").string(USER_SHORT_NAME, "TNB"),
            )
            msg(
                POSITION,
                ProtoMsg().fixed32(POS_LATITUDE_I, 12_345_678).fixed32(POS_LONGITUDE_I, -23_456_789),
            )
            float(SNR, -6.5f)
            fixed32(LAST_HEARD, lastHeard)
            msg(DEVICE_METRICS, ProtoMsg().varint(METRICS_BATTERY_LEVEL, 64))
            varint(CHANNEL, 0)
            bool(VIA_MQTT, false)
            varint(HOPS_AWAY, 3)
            bool(IS_FAVORITE, true)
        }

        assertEquals(lastHeard.toLong(), node.lastHeardEpoch)
        assertEquals(-6.5, node.snr!!, 1e-6)
        assertTrue("SNR should be a plausible dB value, was ${node.snr}", node.snr!! in -30.0..30.0)
        assertEquals(3, node.hopDistance)
        assertEquals(64, node.batteryLevel)
        assertNotNull(node.position)
        assertEquals("Test Node Bravo", node.longName)
        // Five minutes after the radio heard it.
        assertEquals("5m ago", relativeTime(node.lastHeardEpoch, nowMs = (lastHeard + 300L) * 1_000L))
    }

    // region battery label ----------------------------------------------------

    @Test fun battery_level_above_100_means_powered() {
        // telemetry.proto: battery_level is "0-100 (>100 means powered)"; the
        // firmware reports 101.
        val node = decode { msg(DEVICE_METRICS, ProtoMsg().varint(METRICS_BATTERY_LEVEL, 101)) }
        assertEquals(101, node.batteryLevel)
        assertEquals("powered", node.batteryLabel)
    }

    @Test fun battery_label_is_a_percentage_up_to_100() {
        fun label(level: Int?) = MeshNode(id = 1L, shortName = "T", longName = "T", lastHeardEpoch = null, batteryLevel = level).batteryLabel
        assertEquals("0%", label(0))
        assertEquals("73%", label(73))
        assertEquals("100%", label(100))
        assertNull(label(null))
    }

    // endregion
}
