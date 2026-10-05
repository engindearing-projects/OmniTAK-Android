package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.ChatMessage
import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.CoTSource
import soy.engindearing.omnitak.mobile.data.MeshCoreFrameCodec
import soy.engindearing.omnitak.mobile.data.MeshNode
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What [MeshCoreManager] puts on the wire for a CoT event (#234).
 *
 * MeshCore has one place for a position: the radio's own advert. Before this
 * was pinned, every position event was written there, so dropping a marker
 * with a MeshCore radio connected told every MeshCore peer that the operator
 * was standing on the marker.
 */
class MeshCoreOutboundTest {

    private val own = CoTEvent(uid = "ANDROID-1234", type = "a-f-G-U-C", lat = 47.5, lon = -117.25, hae = 600.0)

    private fun latLonOf(frame: ByteArray): Pair<Int, Int> {
        val buf = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        buf.get()
        return buf.int to buf.int
    }

    @Test fun `the operators own position sets the advert and broadcasts it`() {
        val frames = MeshCoreManager.outboundFrames(own, ownPosition = true)

        assertEquals(2, frames.size)
        assertEquals(MeshCoreFrameCodec.CMD_SET_ADVERT_LATLON, frames[0][0])
        assertEquals(47_500_000 to -117_250_000, latLonOf(frames[0]))
        assertArrayEquals(MeshCoreFrameCodec.buildSendSelfAdvert(), frames[1])
    }

    @Test fun `a dropped marker is not written into the radios advert`() {
        // The quick drop in MapScreen: its own uid, an unknown-affiliation type,
        // coordinates that are not the operator's.
        val marker = CoTEvent(
            uid = "marker-7f3a", type = "a-u-G", lat = 10.0, lon = 20.0,
            callsign = "Marker 3", source = CoTSource.LOCAL,
        )

        assertTrue(MeshCoreManager.outboundFrames(marker, ownPosition = false).isEmpty())
    }

    @Test fun `no other position event reaches the advert either`() {
        val others = listOf(
            // A marker saved from the edit sheet, friendly ground.
            CoTEvent(uid = "marker-1", type = "a-f-G", lat = 10.0, lon = 20.0),
            // A FEMA palette marker.
            CoTEvent(uid = "fema-1", type = "a-f-G-I", lat = 10.0, lon = 20.0, iconsetPath = "COT_MAPPING_FEMA/x/y"),
            // A military report pinned to a map position.
            CoTEvent(uid = "report-1", type = "b-r-f-h-c", lat = 10.0, lon = 20.0),
            // A hostile track, a spot map dot, a waypoint.
            CoTEvent(uid = "h-1", type = "a-h-G", lat = 10.0, lon = 20.0),
            CoTEvent(uid = "spot-1", type = "b-m-p-s-m", lat = 10.0, lon = 20.0),
            CoTEvent(uid = "wp-1", type = "b-m-p-w", lat = 10.0, lon = 20.0),
            // Somebody else's position report, as the server relay would pass it.
            CoTEvent(uid = "ANDROID-other", type = "a-f-G-U-C", lat = 10.0, lon = 20.0),
        )

        for (event in others) {
            assertTrue(
                "${event.type} must not be sent as this radio's position",
                MeshCoreManager.outboundFrames(event, ownPosition = false).isEmpty(),
            )
        }
    }

    @Test fun `the event type does not make a position the operators own`() {
        // The type of the operator's own report is the same as a teammate's.
        // Only the caller knows which it is.
        val sameTypeAsOwn = own.copy(uid = "ANDROID-other", lat = 1.0, lon = 2.0)

        assertTrue(MeshCoreManager.outboundFrames(sameTypeAsOwn, ownPosition = false).isEmpty())
        assertEquals(2, MeshCoreManager.outboundFrames(sameTypeAsOwn, ownPosition = true).size)
    }

    @Test fun `chat is never an advert whoever sends it`() {
        val chat = CoTEvent(uid = "GeoChat.ANDROID-1234.All Chat Rooms.1", type = "b-t-f", lat = 47.5, lon = -117.25)

        assertTrue(MeshCoreManager.outboundFrames(chat, ownPosition = false).isEmpty())
        assertTrue(MeshCoreManager.outboundFrames(chat, ownPosition = true).isEmpty())
    }

    @Test fun `an own position the radio cannot hold sends nothing`() {
        // No advert broadcast without the coordinates that go in it: the
        // radio would announce whatever it held before.
        for (bad in listOf(own.copy(lat = 91.0), own.copy(lon = -181.0), own.copy(lat = Double.NaN))) {
            assertTrue(MeshCoreManager.outboundFrames(bad, ownPosition = true).isEmpty())
        }
    }

    @Test fun `a caller that does not say so is not sending the operators own position`() = runTest {
        // The marker drops call sendCoTOverMesh(event) with nothing else. This
        // pins what the interface makes of that, so the default cannot be
        // flipped without a test failing.
        val seen = ArrayList<Boolean>()
        val mesh = object : MeshFrameworkManager {
            override val nodes: StateFlow<Map<Long, MeshNode>> = MutableStateFlow(emptyMap())
            override val activeConnectionState: StateFlow<ConnectionState> =
                MutableStateFlow(ConnectionState.Disconnected)
            override var cotSink: ((CoTEvent) -> Unit)? = null
            override var chatSink: ((ChatMessage) -> Unit)? = null
            override suspend fun connectBle(deviceAddress: String) = false
            override fun disconnect() = Unit
            override suspend fun sendMeshChat(text: String, channelIndex: Int, toNodeId: UInt?) = false
            override suspend fun sendCoTOverMesh(event: CoTEvent, channelIndex: UInt, ownPosition: Boolean): Boolean {
                seen += ownPosition
                return true
            }
            override suspend fun startMeshScan(timeoutMs: Long): Flow<MeshScanResult>? = null
            override fun stopMeshScan() = Unit
        }

        mesh.sendCoTOverMesh(own)
        mesh.sendCoTOverMesh(own, channelIndex = 1u)
        mesh.sendCoTOverMesh(own, ownPosition = true)

        assertEquals(listOf(false, false, true), seen)
    }
}
