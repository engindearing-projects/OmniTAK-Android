package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.AdminResponse
import soy.engindearing.omnitak.mobile.data.AdminTestFrames
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.adminResponseFrame
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.channelFrame
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.channelMessage
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.configFrame
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.decode
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.deviceConfig
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.loraConfig
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.myInfoFrame
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.nodeInfoFrame
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.positionConfig
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.userMessage
import soy.engindearing.omnitak.mobile.data.AdminWriteResult
import soy.engindearing.omnitak.mobile.data.MeshChannelPreset
import soy.engindearing.omnitak.mobile.data.MeshDeviceConfig
import soy.engindearing.omnitak.mobile.data.MeshRegion
import soy.engindearing.omnitak.mobile.data.MeshRole
import soy.engindearing.omnitak.mobile.data.ProtoMsg
import soy.engindearing.omnitak.mobile.data.RadioSettingsCache.Key
import soy.engindearing.omnitak.mobile.data.RefusalReason

/**
 * The settings cache as the manager wires it: frames go in through
 * [MeshtasticManager.dispatchFrame] (the same entry point both transports
 * use), and writes go out through the manager's own methods to a fake link.
 *
 * Node numbers, names and key bytes are made up.
 */
class MeshtasticManagerRadioSettingsTest {

    private val me = 0x0A0B0C0D

    private class Wire {
        val frames = mutableListOf<ByteArray>()
        val send: suspend (ByteArray) -> Boolean = { frames += it; true }
    }

    /** A manager that has been through a config download: my_info, own node info, channel 0, device, position, lora. */
    private fun downloaded(wire: Wire? = Wire()): Pair<MeshtasticManager, Wire?> {
        val mgr = MeshtasticManager()
        if (wire != null) mgr.adminSendOverride = wire.send
        mgr.dispatchFrame(myInfoFrame(me))
        mgr.dispatchFrame(nodeInfoFrame(me, userMessage(longName = "Test Node One", shortName = "TNO")))
        mgr.dispatchFrame(channelFrame(channelMessage(name = "Alpha")))
        mgr.dispatchFrame(configFrame(1, deviceConfig()))
        mgr.dispatchFrame(configFrame(2, positionConfig()))
        mgr.dispatchFrame(configFrame(6, loraConfig(preset = 6, region = 1)))
        return mgr to wire
    }

    // region the cache is fed ------------------------------------------------

    @Test fun `a config download dispatched through the manager fills the cache`() {
        val (mgr, _) = downloaded()
        assertNotNull(mgr.radioSettings.owner())
        assertNotNull(mgr.radioSettings.channel(0))
        assertTrue(deviceConfig().contentEquals(mgr.radioSettings.config(1)))
        assertTrue(positionConfig().contentEquals(mgr.radioSettings.config(2)))
        assertTrue(loraConfig(preset = 6, region = 1).contentEquals(mgr.radioSettings.config(6)))
    }

    @Test fun `config variants the settings screen cannot decode reach the cache`() {
        val mgr = MeshtasticManager()
        mgr.dispatchFrame(configFrame(3, ProtoMsg().varint(4, 3).build()))
        mgr.dispatchFrame(configFrame(8, ProtoMsg().bytes(1, AdminTestFrames.keyBytes(0x20)).build()))
        assertNotNull(mgr.radioSettings.config(3))
        assertNotNull(mgr.radioSettings.config(8))
    }

    @Test fun `the decoded values still reach the device settings store`() {
        val mgr = MeshtasticManager()
        val seen = mutableListOf<AdminResponse>()
        mgr.adminResponseSink = { seen += it }

        mgr.dispatchFrame(configFrame(1, deviceConfig(role = 7)))
        mgr.dispatchFrame(configFrame(2, positionConfig(secs = 300)))
        mgr.dispatchFrame(configFrame(6, loraConfig(preset = 4)))
        mgr.dispatchFrame(channelFrame(channelMessage(name = "Alpha")))
        mgr.dispatchFrame(configFrame(3, ProtoMsg().varint(4, 3).build())) // power: nothing to show

        assertEquals(
            listOf(
                AdminResponse.DeviceConfig(MeshRole.TAK),
                AdminResponse.PositionConfig(300),
                AdminResponse.LoraConfig(MeshChannelPreset.MEDIUM_FAST),
                AdminResponse.Channel(index = 0, name = "Alpha", role = 1),
            ),
            seen,
        )
    }

    @Test fun `an admin response from the radio updates the cache and still reaches the store`() {
        val (mgr, _) = downloaded()
        val seen = mutableListOf<AdminResponse>()
        mgr.adminResponseSink = { seen += it }

        mgr.dispatchFrame(
            adminResponseFrame(from = me, to = me, adminField = 6, message = ProtoMsg().bytes(6, loraConfig(preset = 4, region = 3)).build()),
        )

        assertTrue(loraConfig(preset = 4, region = 3).contentEquals(mgr.radioSettings.config(6)))
        assertEquals(listOf<AdminResponse>(AdminResponse.LoraConfig(MeshChannelPreset.MEDIUM_FAST)), seen)
    }

    @Test fun `an admin response that does not come from our radio does not change the cache`() {
        val (mgr, _) = downloaded()
        mgr.dispatchFrame(
            adminResponseFrame(from = 0x01020304, to = me, adminField = 6, message = ProtoMsg().bytes(6, loraConfig(region = 0)).build()),
        )
        assertTrue("region 1 is still there", loraConfig(preset = 6, region = 1).contentEquals(mgr.radioSettings.config(6)))
    }

    // endregion

    // region the cache is emptied --------------------------------------------

    @Test fun `a new download empties the cache as it starts`() {
        val (mgr, _) = downloaded()
        assertNotNull(mgr.radioSettings.config(6))

        mgr.dispatchFrame(myInfoFrame(me)) // my_info is the first frame of every download

        assertEquals(0, mgr.radioSettings.size)
    }

    @Test fun `the link going from connected to anything else empties the cache`() {
        for (down in listOf(
            ConnectionState.Disconnected,
            ConnectionState.Failed("gone"),
            ConnectionState.Connecting("radio"),
        )) {
            val (mgr, _) = downloaded()
            val stillUp = mgr.onLinkState(ConnectionState.Connected("radio", useTLS = false), wasConnected = true)
            assertTrue(stillUp)
            assertEquals("still connected: keep what we have", true, mgr.radioSettings.size > 0)

            val up = mgr.onLinkState(down, wasConnected = true)
            assertEquals("$down is not connected", false, up)
            assertEquals("$down: the cache must not outlive the link", 0, mgr.radioSettings.size)
        }
    }

    /** Poll until [condition] holds, or fail after [timeoutMs]. */
    private fun waitFor(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    @Test fun `the watcher empties the cache when a real tcp link drops`() {
        // A throwaway "radio": accepts one connection, and drops it when told to.
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        var accepted: Socket? = null
        val acceptor = Thread { accepted = server.accept() }.apply { isDaemon = true; start() }
        val mgr = MeshtasticManager()
        try {
            mgr.connectTcp("127.0.0.1", server.localPort)
            waitFor("the link to come up") { mgr.activeConnectionState.value is ConnectionState.Connected }
            acceptor.join(5_000)
            Thread.sleep(300) // let the watcher see Connected before anything is stored

            mgr.dispatchFrame(myInfoFrame(me))
            mgr.dispatchFrame(configFrame(6, loraConfig()))
            assertTrue("the cache holds the config while the link is up", mgr.radioSettings.size > 0)

            accepted!!.close() // the radio goes away
            waitFor("the link to be seen as down") { mgr.activeConnectionState.value !is ConnectionState.Connected }
            waitFor("the cache to be emptied") { mgr.radioSettings.size == 0 }
        } finally {
            mgr.disconnect()
            runCatching { server.close() }
        }
    }

    @Test fun `a link that was never up does not empty a cache that is being filled`() {
        val (mgr, _) = downloaded()
        // Disconnected -> Connecting -> Connected is a new session starting, not a drop.
        mgr.onLinkState(ConnectionState.Disconnected, wasConnected = false)
        mgr.onLinkState(ConnectionState.Connecting("radio"), wasConnected = false)
        assertTrue(mgr.radioSettings.size > 0)
    }

    // endregion

    // region writes through the manager ----------------------------------------

    private val matchingDraft = MeshDeviceConfig(
        longName = "Test Node One", shortName = "TNO", role = MeshRole.TAK,
        positionBroadcastSecs = 900, channelName = "Alpha", channelPreset = MeshChannelPreset.SHORT_FAST,
    )

    @Test fun `a push after a download sends begin, the one changed write, commit`() = runBlocking {
        val (mgr, wire) = downloaded()
        val result = mgr.pushDeviceConfig(matchingDraft.copy(channelPreset = MeshChannelPreset.MEDIUM_FAST))

        assertEquals(AdminWriteResult.Sent(1), result)
        assertEquals(listOf(64, 34, 65), wire!!.frames.map { decode(it).admin.single().number })
        val (variant, message) = decode(wire.frames[1]).setConfig()
        assertEquals(6, variant)
        assertTrue("region and hop limit carried over", loraConfig(preset = 4, region = 1).contentEquals(message))
    }

    @Test fun `a push with nothing changed sends nothing`() = runBlocking {
        val (mgr, wire) = downloaded()
        assertEquals(AdminWriteResult.NothingToChange, mgr.pushDeviceConfig(matchingDraft))
        assertTrue(wire!!.frames.isEmpty())
    }

    @Test fun `a push before the download has finished is refused and sends nothing`() = runBlocking {
        val wire = Wire()
        val mgr = MeshtasticManager()
        mgr.adminSendOverride = wire.send
        mgr.dispatchFrame(myInfoFrame(me)) // the radio has said who it is, and nothing else yet

        assertEquals(AdminWriteResult.Refused(RefusalReason.NOT_LOADED), mgr.pushDeviceConfig(matchingDraft.copy(role = MeshRole.ROUTER)))
        assertEquals(AdminWriteResult.Refused(RefusalReason.NOT_LOADED), mgr.applyLoRaConfig(MeshRegion.US, MeshChannelPreset.SHORT_FAST))
        assertEquals(AdminWriteResult.Refused(RefusalReason.NOT_LOADED), mgr.applyOwner("New Long", "NEW"))
        assertTrue(wire.frames.isEmpty())
    }

    @Test fun `with no link and no radio nothing is sent and the result says no radio`() = runBlocking {
        val mgr = MeshtasticManager() // no transport, no my_info
        assertEquals(AdminWriteResult.Refused(RefusalReason.NO_RADIO), mgr.pushDeviceConfig(matchingDraft))
        assertEquals(AdminWriteResult.Refused(RefusalReason.NO_RADIO), mgr.applyRebroadcastMode(soy.engindearing.omnitak.mobile.data.RebroadcastMode.ALL))
    }

    @Test fun `after a disconnect a write is refused until the next download`() = runBlocking {
        val (mgr, wire) = downloaded()
        mgr.onLinkState(ConnectionState.Connected("radio", useTLS = false), wasConnected = false)
        mgr.onLinkState(ConnectionState.Disconnected, wasConnected = true) // what the watcher does when the link drops
        val result = mgr.applyLoRaConfig(MeshRegion.UNSET, MeshChannelPreset.MEDIUM_FAST)
        assertEquals(AdminWriteResult.Refused(RefusalReason.NOT_LOADED), result)
        assertTrue(wire!!.frames.isEmpty())
        assertNull(mgr.radioSettings.get(Key.Config(6)))
    }

    // endregion
}
