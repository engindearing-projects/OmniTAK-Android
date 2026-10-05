package soy.engindearing.omnitak.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.MeshNode
import soy.engindearing.omnitak.mobile.data.ProtoMsg

/**
 * The node list as the radio fills it: FromRadio frames go in through
 * [MeshtasticManager.dispatchFrame], and the [MeshtasticManager.nodes] table is
 * checked afterwards. Frames use the official mesh.proto numbers.
 *
 * Node ids and coordinates are made up.
 */
class MeshtasticManagerNodeListTest {

    private companion object {
        // FromRadio
        const val FROM_RADIO_PACKET = 2
        const val FROM_RADIO_NODE_INFO = 4

        // NodeInfo
        const val NI_NUM = 1
        const val NI_USER = 2
        const val NI_POSITION = 3
        const val NI_SNR = 4
        const val NI_LAST_HEARD = 5
        const val NI_DEVICE_METRICS = 6
        const val NI_HOPS_AWAY = 9

        // MeshPacket
        const val MP_FROM = 1
        const val MP_TO = 2
        const val MP_DECODED = 4
        const val MP_RX_TIME = 7
        const val MP_RX_SNR = 8
        const val MP_HOP_LIMIT = 9

        const val NODE_ID = 0x0A0B0C0D
        const val POSITION_APP = 3
    }

    private fun position(latI: Int, lonI: Int) =
        ProtoMsg().fixed32(1, latI).fixed32(2, lonI) // Position.latitude_i, longitude_i

    private fun nodeInfoFrame(build: ProtoMsg.() -> Unit): ByteArray {
        val nodeInfo = ProtoMsg().varint(NI_NUM, NODE_ID.toLong()).apply(build)
        return ProtoMsg().msg(FROM_RADIO_NODE_INFO, nodeInfo).build()
    }

    private fun positionPacketFrame(build: ProtoMsg.() -> Unit): ByteArray {
        val data = ProtoMsg()
            .varint(1, POSITION_APP) // Data.portnum
            .msg(2, position(latI = 12_345_678, lonI = -23_456_789)) // Data.payload (bytes)
        val packet = ProtoMsg()
            .fixed32(MP_FROM, NODE_ID)
            .fixed32(MP_TO, -1)
            .msg(MP_DECODED, data)
            .apply(build)
        return ProtoMsg().msg(FROM_RADIO_PACKET, packet).build()
    }

    private fun MeshtasticManager.node(): MeshNode? = nodes.value[NODE_ID.toLong()]

    @Test fun node_database_entry_keeps_every_field() {
        val mgr = MeshtasticManager()
        mgr.dispatchFrame(
            nodeInfoFrame {
                msg(NI_USER, ProtoMsg().string(2, "Test Node Alpha").string(3, "TNA"))
                msg(NI_POSITION, position(latI = 12_345_678, lonI = -23_456_789))
                float(NI_SNR, -6.5f)
                fixed32(NI_LAST_HEARD, 1_790_000_000)
                msg(NI_DEVICE_METRICS, ProtoMsg().varint(1, 64)) // DeviceMetrics.battery_level
                varint(NI_HOPS_AWAY, 3)
            },
        )

        val node = mgr.node()
        assertNotNull(node)
        assertEquals("Test Node Alpha", node!!.longName)
        assertEquals(1_790_000_000L, node.lastHeardEpoch)
        assertEquals(-6.5, node.snr!!, 1e-6)
        assertEquals(3, node.hopDistance)
        assertEquals(64, node.batteryLevel)
        assertEquals(1.2345678, node.position!!.lat, 1e-9)
    }

    @Test fun node_database_entry_without_last_heard_is_unknown_not_now() {
        val mgr = MeshtasticManager()
        mgr.dispatchFrame(nodeInfoFrame { varint(NI_HOPS_AWAY, 1) })
        assertNull(mgr.node()!!.lastHeardEpoch)
    }

    @Test fun a_later_node_info_without_last_heard_keeps_the_known_value() {
        val mgr = MeshtasticManager()
        mgr.dispatchFrame(nodeInfoFrame { fixed32(NI_LAST_HEARD, 1_790_000_000) })
        mgr.dispatchFrame(nodeInfoFrame { varint(NI_HOPS_AWAY, 2) })

        val node = mgr.node()!!
        assertEquals(1_790_000_000L, node.lastHeardEpoch)
        assertEquals(2, node.hopDistance)
    }

    @Test fun a_later_node_info_replaces_last_heard_when_it_has_one() {
        val mgr = MeshtasticManager()
        mgr.dispatchFrame(nodeInfoFrame { fixed32(NI_LAST_HEARD, 1_790_000_000) })
        mgr.dispatchFrame(nodeInfoFrame { fixed32(NI_LAST_HEARD, 1_790_000_900) })
        assertEquals(1_790_000_900L, mgr.node()!!.lastHeardEpoch)
    }

    @Test fun a_position_packet_uses_the_packets_rx_time_and_snr() {
        val mgr = MeshtasticManager()
        mgr.dispatchFrame(
            positionPacketFrame {
                fixed32(MP_RX_TIME, 1_790_000_123)
                float(MP_RX_SNR, -7.25f)
                varint(MP_HOP_LIMIT, 3)
            },
        )

        val node = mgr.node()!!
        // Before the fix rx_time was read from the rx_snr field, so this was the
        // float bits of -7.25 treated as an epoch.
        assertEquals(1_790_000_123L, node.lastHeardEpoch)
        assertEquals(-7.25, node.snr!!, 1e-6)
        assertNotNull(node.position)
    }

    @Test fun a_position_packet_without_rx_time_is_stamped_with_the_time_it_arrived() {
        val mgr = MeshtasticManager()
        val before = System.currentTimeMillis() / 1000
        mgr.dispatchFrame(positionPacketFrame { })
        val after = System.currentTimeMillis() / 1000

        val heard = mgr.node()!!.lastHeardEpoch
        assertNotNull(heard)
        assertTrue("expected $before..$after, was $heard", heard!! in before..after)
    }

    @Test fun a_position_packet_for_a_known_node_refreshes_last_heard_without_rx_time() {
        val mgr = MeshtasticManager()
        // The node list knows the node, but never saw it heard.
        mgr.dispatchFrame(nodeInfoFrame { varint(NI_HOPS_AWAY, 1) })
        assertNull(mgr.node()!!.lastHeardEpoch)

        val before = System.currentTimeMillis() / 1000
        mgr.dispatchFrame(positionPacketFrame { })
        val after = System.currentTimeMillis() / 1000

        val node = mgr.node()!!
        assertTrue("expected $before..$after, was ${node.lastHeardEpoch}", node.lastHeardEpoch!! in before..after)
        assertNotNull(node.position)
        assertEquals(1, node.hopDistance)
    }
}
