package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.AdminResponse
import soy.engindearing.omnitak.mobile.data.AdminSetting
import soy.engindearing.omnitak.mobile.data.AdminTestFrames
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.adminResponseFrame
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.channelFrame
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.channelMessage
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.configFrame
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.deviceConfig
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.loraConfig
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.myInfoFrame
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.nodeInfoFrame
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.positionConfig
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.userMessage
import soy.engindearing.omnitak.mobile.data.AdminWriteResult
import soy.engindearing.omnitak.mobile.data.DeviceEdits
import soy.engindearing.omnitak.mobile.data.FakeRadio
import soy.engindearing.omnitak.mobile.data.MeshChannelPreset
import soy.engindearing.omnitak.mobile.data.MeshRegion
import soy.engindearing.omnitak.mobile.data.MeshRole
import soy.engindearing.omnitak.mobile.data.ProtoMsg
import soy.engindearing.omnitak.mobile.data.RebroadcastMode
import soy.engindearing.omnitak.mobile.data.RefusalReason

/**
 * The settings plumbing as the manager wires it: frames go in through
 * [MeshtasticManager.dispatchFrame] (the same entry point both transports
 * use), and writes go out through the manager's own methods to a [FakeRadio]
 * standing in for the link, which answers reads by dispatching the reply as a
 * frame from our own node.
 *
 * Node numbers, names and key bytes are made up.
 */
class MeshtasticManagerRadioSettingsTest {

    private val me = 0x0A0B0C0D
    private val other = 0x01020304

    /** The link: records what the app sent and lets [radio] answer. */
    private class Link(private val mgr: MeshtasticManager, val radio: FakeRadio, private val from: Int) {
        val frames = mutableListOf<ByteArray>()
        val send: suspend (ByteArray) -> Boolean = { frame ->
            frames += frame
            radio.handle(frame) { admin -> mgr.dispatchFrame(AdminTestFrames.packetFrame(from = from, to = from, portnum = 6, payload = admin)) }
            true
        }
    }

    /** A manager that has been through a config download from a factory radio, with the link standing in for the transport. */
    private fun connected(): Pair<MeshtasticManager, Link> {
        val mgr = MeshtasticManager()
        val link = Link(mgr, FakeRadio.factory(), me)
        mgr.adminSendOverride = link.send
        val radio = link.radio
        mgr.dispatchFrame(myInfoFrame(me))
        mgr.dispatchFrame(nodeInfoFrame(me, radio.owner))
        for (i in 0..7) mgr.dispatchFrame(channelFrame(radio.channels.getValue(i)))
        for (variant in 1..10) mgr.dispatchFrame(configFrame(variant, radio.config[variant] ?: ByteArray(0)))
        return mgr to link
    }

    // region the cache is fed ------------------------------------------------

    @Test fun `a config download dispatched through the manager fills the cache`() {
        val (mgr, link) = connected()
        assertNotNull(mgr.radioSettings.owner())
        assertNotNull(mgr.radioSettings.channel(0))
        assertTrue(link.radio.config.getValue(2).contentEquals(mgr.radioSettings.config(2)))
        assertTrue(link.radio.config.getValue(6).contentEquals(mgr.radioSettings.config(6)))
    }

    @Test fun `config variants the app does not patch are not kept, the security and network configs included`() {
        val mgr = MeshtasticManager()
        mgr.dispatchFrame(configFrame(3, ProtoMsg().varint(4, 3).build()))
        mgr.dispatchFrame(configFrame(4, ProtoMsg().string(3, "ssid-example").string(4, "password-example").build()))
        mgr.dispatchFrame(configFrame(8, ProtoMsg().bytes(1, AdminTestFrames.keyBytes(0x20)).build()))
        assertEquals(0, mgr.radioSettings.size)
        assertNull(mgr.radioSettings.config(4))
        assertNull(mgr.radioSettings.config(8))
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
                AdminResponse.DeviceConfig(MeshRole.TAK, RebroadcastMode.LOCAL_ONLY),
                AdminResponse.PositionConfig(300),
                AdminResponse.LoraConfig(MeshChannelPreset.MEDIUM_FAST, MeshRegion.US),
                AdminResponse.Channel(index = 0, name = "Alpha", role = 1),
            ),
            seen,
        )
    }

    @Test fun `a radio that sends no role and no name is reported as CLIENT and unnamed, not as nothing`() {
        // The factory-fresh radio: DeviceConfig without a role, a primary channel without a name.
        val mgr = MeshtasticManager()
        val seen = mutableListOf<AdminResponse>()
        mgr.adminResponseSink = { seen += it }
        val radio = FakeRadio.factory()

        mgr.dispatchFrame(configFrame(1, radio.config.getValue(1)))
        mgr.dispatchFrame(channelFrame(radio.channels.getValue(0)))
        mgr.dispatchFrame(configFrame(6, radio.config.getValue(6)))

        assertEquals(AdminResponse.DeviceConfig(MeshRole.CLIENT, RebroadcastMode.ALL), seen[0])
        assertEquals(AdminResponse.Channel(index = 0, name = "", role = 1), seen[1])
        assertEquals(AdminResponse.LoraConfig(MeshChannelPreset.LONG_FAST, MeshRegion.US), seen[2])
    }

    @Test fun `an admin response from our radio updates the cache and reaches the store`() {
        val (mgr, _) = connected()
        val seen = mutableListOf<AdminResponse>()
        mgr.adminResponseSink = { seen += it }

        mgr.dispatchFrame(
            adminResponseFrame(from = me, to = me, adminField = 6, message = ProtoMsg().bytes(6, loraConfig(preset = 4, region = 3)).build()),
        )

        assertTrue(loraConfig(preset = 4, region = 3).contentEquals(mgr.radioSettings.config(6)))
        assertEquals(listOf<AdminResponse>(AdminResponse.LoraConfig(MeshChannelPreset.MEDIUM_FAST, MeshRegion.EU_868)), seen)
    }

    @Test fun `an admin response from another node changes neither the cache nor the store`() {
        // The radio hands the phone any admin message addressed to it. One from another node must not become
        // the draft that the next push writes, or the chat titles.
        val (mgr, _) = connected()
        val seen = mutableListOf<AdminResponse>()
        mgr.adminResponseSink = { seen += it }
        val loraBefore = mgr.radioSettings.config(6)!!.copyOf()

        mgr.dispatchFrame(adminResponseFrame(from = other, to = me, adminField = 6, message = ProtoMsg().bytes(6, loraConfig(region = 0)).build()))
        mgr.dispatchFrame(adminResponseFrame(from = other, to = me, adminField = 2, message = channelMessage(name = "Evil")))
        mgr.dispatchFrame(adminResponseFrame(from = other, to = me, adminField = 4, message = userMessage(longName = "Evil")))
        mgr.dispatchFrame(adminResponseFrame(from = 0, to = me, adminField = 6, message = ProtoMsg().bytes(1, deviceConfig(role = 2)).build()))

        assertTrue("the sink heard nothing: $seen", seen.isEmpty())
        assertTrue(loraBefore.contentEquals(mgr.radioSettings.config(6)))
    }

    @Test fun `an admin response before the radio has said who it is is ignored`() {
        val mgr = MeshtasticManager()
        val seen = mutableListOf<AdminResponse>()
        mgr.adminResponseSink = { seen += it }
        mgr.dispatchFrame(adminResponseFrame(from = me, to = me, adminField = 2, message = channelMessage(name = "Early")))
        assertTrue(seen.isEmpty())
    }

    @Test fun `the radio's own node info reports its owner names, another node's does not`() {
        val mgr = MeshtasticManager()
        val seen = mutableListOf<AdminResponse>()
        mgr.adminResponseSink = { seen += it }
        mgr.dispatchFrame(myInfoFrame(me))

        mgr.dispatchFrame(nodeInfoFrame(other, userMessage(longName = "Someone Else", shortName = "SE")))
        assertTrue(seen.isEmpty())

        mgr.dispatchFrame(nodeInfoFrame(me, userMessage(longName = "Test Node One", shortName = "TNO")))
        assertEquals(listOf<AdminResponse>(AdminResponse.Owner("Test Node One", "TNO")), seen)
    }

    // endregion

    // region the cache is emptied --------------------------------------------

    @Test fun `a new download empties the cache as it starts`() {
        val (mgr, _) = connected()
        assertNotNull(mgr.radioSettings.config(6))

        mgr.dispatchFrame(myInfoFrame(me)) // my_info is the first frame of every download

        assertEquals(0, mgr.radioSettings.size)
    }

    @Test fun `the link going from connected to anything else empties the cache and forgets the node number`() {
        for (down in listOf(
            ConnectionState.Disconnected,
            ConnectionState.Failed("gone"),
            ConnectionState.Connecting("radio"),
        )) {
            val (mgr, _) = connected()
            val drops = mutableListOf<Unit>()
            mgr.linkDownSink = { drops += Unit }
            val stillUp = mgr.onLinkState(ConnectionState.Connected("radio", useTLS = false), wasConnected = true)
            assertTrue(stillUp)
            assertTrue("still connected: keep what we have", mgr.radioSettings.size > 0)
            assertEquals(me.toUInt(), mgr.myNodeNum)
            assertTrue(drops.isEmpty())

            val up = mgr.onLinkState(down, wasConnected = true)
            assertEquals("$down is not connected", false, up)
            assertEquals("$down: the cache must not outlive the link", 0, mgr.radioSettings.size)
            assertNull("$down: nothing may be addressed to the radio that was on this link", mgr.myNodeNum)
            assertEquals("$down: the store is told the radio's report no longer holds", 1, drops.size)
        }
    }

    @Test fun `a link that was never up does not empty a cache that is being filled`() {
        val (mgr, _) = connected()
        // Disconnected -> Connecting -> Connected is a new session starting, not a drop.
        mgr.onLinkState(ConnectionState.Disconnected, wasConnected = false)
        mgr.onLinkState(ConnectionState.Connecting("radio"), wasConnected = false)
        assertTrue(mgr.radioSettings.size > 0)
        assertEquals(me.toUInt(), mgr.myNodeNum)
    }

    /** Poll until [condition] holds, or fail after [timeoutMs]. */
    private fun waitFor(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    @Test fun `the watcher empties the cache and forgets the node when a real tcp link drops`() {
        // A throwaway "radio": accepts one connection, and drops it when told to.
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        var accepted: Socket? = null
        val acceptor = Thread { accepted = server.accept() }.apply { isDaemon = true; start() }
        val mgr = MeshtasticManager()
        val drops = mutableListOf<Unit>()
        mgr.linkDownSink = { drops += Unit }
        try {
            mgr.connectTcp("127.0.0.1", server.localPort)
            waitFor("the link to come up") { mgr.activeConnectionState.value is ConnectionState.Connected }
            acceptor.join(5_000)
            Thread.sleep(300) // let the watcher see Connected before anything is stored

            mgr.dispatchFrame(myInfoFrame(me))
            mgr.dispatchFrame(configFrame(6, loraConfig()))
            assertTrue("the cache holds the config while the link is up", mgr.radioSettings.size > 0)
            assertEquals(me.toUInt(), mgr.myNodeNum)

            accepted!!.close() // the radio goes away
            waitFor("the link to be seen as down") { mgr.activeConnectionState.value !is ConnectionState.Connected }
            waitFor("the cache to be emptied") { mgr.radioSettings.size == 0 }
            waitFor("the node number to be forgotten") { mgr.myNodeNum == null }
            waitFor("the store to be told") { drops.size == 1 }
        } finally {
            mgr.disconnect()
            runCatching { server.close() }
        }
    }

    // endregion

    // region writes through the manager ----------------------------------------

    @Test fun `a factory radio and a fresh install draft with one edit is one write and the role is not touched`() = runBlocking {
        val (mgr, link) = connected()
        val roleBefore = link.radio.config.getValue(1).copyOf()
        val channelBefore = link.radio.channels.getValue(0).copyOf()

        val result = mgr.pushDeviceConfig(DeviceEdits(positionBroadcastSecs = 300))

        assertEquals(AdminWriteResult.Sent(listOf(AdminSetting.POSITION_INTERVAL)), result)
        assertEquals(listOf("get_config:2", "begin", "set_config:2", "commit"), link.radio.log)
        assertTrue("role untouched", roleBefore.contentEquals(link.radio.config[1]))
        assertTrue("primary channel untouched", channelBefore.contentEquals(link.radio.channels[0]))
    }

    @Test fun `a push with nothing edited sends nothing`() = runBlocking {
        val (mgr, link) = connected()
        assertEquals(AdminWriteResult.NothingToChange, mgr.pushDeviceConfig(DeviceEdits()))
        assertTrue(link.frames.isEmpty())
    }

    @Test fun `after the link drops a write is refused, because nothing may be addressed to the old radio`() = runBlocking {
        val (mgr, link) = connected()
        mgr.onLinkState(ConnectionState.Connected("radio", useTLS = false), wasConnected = false)
        mgr.onLinkState(ConnectionState.Disconnected, wasConnected = true) // what the watcher does when the link drops
        link.frames.clear()

        assertEquals(AdminWriteResult.Refused(RefusalReason.NO_RADIO), mgr.pushDeviceConfig(DeviceEdits(role = MeshRole.ROUTER)))
        assertEquals(AdminWriteResult.Refused(RefusalReason.NO_RADIO), mgr.applyRebroadcastMode(RebroadcastMode.ALL))
        assertEquals(AdminWriteResult.Refused(RefusalReason.NO_RADIO), mgr.applyLoRaConfig(MeshRegion.US, null))
        assertEquals(0, mgr.requestDeviceConfig())
        assertTrue("not a frame addressed to the previous radio", link.frames.isEmpty())
    }

    @Test fun `with no link and no radio nothing is sent and the result says no radio`() = runBlocking {
        val mgr = MeshtasticManager() // no transport, no my_info
        assertEquals(AdminWriteResult.Refused(RefusalReason.NO_RADIO), mgr.pushDeviceConfig(DeviceEdits(positionBroadcastSecs = 60)))
        assertEquals(AdminWriteResult.Refused(RefusalReason.NO_RADIO), mgr.applyRebroadcastMode(RebroadcastMode.ALL))
    }

    @Test fun `reading everything sends twelve requests a gap apart`() = runBlocking {
        val (mgr, link) = connected()
        val started = System.currentTimeMillis()
        val sent = mgr.requestDeviceConfig()
        val took = System.currentTimeMillis() - started

        assertEquals(12, sent)
        assertEquals(12, link.frames.size)
        assertTrue("11 gaps of 100 ms, took $took ms", took >= 1_000)
    }

    // endregion

    // region telling the operator what the radio kept --------------------------------------

    @Test fun `a radio that kept its own value is named after its next report`() = runBlocking {
        val (mgr, link) = connected()
        val original = link.radio.config.getValue(1).copyOf()
        // A radio that takes the write and keeps its role anyway (managed, or a value it turns into another).
        link.radio.onSetConfig = { variant, radio -> if (variant == 1) radio.config[1] = original }

        val result = mgr.pushDeviceConfig(DeviceEdits(role = MeshRole.TAK))
        assertEquals(AdminWriteResult.Sent(listOf(AdminSetting.ROLE)), result)
        assertNull("nothing to say yet: the radio has not reported", mgr.settingsNotice.value)

        // The re-read after the push (or the download after the radio restarts) reports the role.
        mgr.dispatchFrame(adminResponseFrame(from = me, to = me, adminField = 6, message = ProtoMsg().bytes(1, original).build()))

        assertEquals("The radio did not take: role. It may be managed.", mgr.settingsNotice.value)
        mgr.clearSettingsNotice()
        assertNull(mgr.settingsNotice.value)
    }

    @Test fun `a radio that took the write says nothing`() = runBlocking {
        val (mgr, link) = connected()
        mgr.pushDeviceConfig(DeviceEdits(role = MeshRole.TAK))

        mgr.dispatchFrame(adminResponseFrame(from = me, to = me, adminField = 6, message = ProtoMsg().bytes(1, link.radio.config.getValue(1)).build()))

        assertNull(mgr.settingsNotice.value)
    }

    @Test fun `the note survives a restart of the same radio, and is not blamed on another radio`() = runBlocking {
        val (mgr, link) = connected()
        val original = link.radio.config.getValue(1).copyOf()
        link.radio.onSetConfig = { variant, radio -> if (variant == 1) radio.config[1] = original }
        mgr.pushDeviceConfig(DeviceEdits(role = MeshRole.TAK))

        // The radio restarts after the commit: the link drops, then the download reports the role again.
        mgr.onLinkState(ConnectionState.Connected("radio", useTLS = false), wasConnected = false)
        mgr.onLinkState(ConnectionState.Disconnected, wasConnected = true)
        mgr.dispatchFrame(myInfoFrame(other)) // a different radio first: its role says nothing about ours
        mgr.dispatchFrame(configFrame(1, original))
        assertNull(mgr.settingsNotice.value)

        mgr.dispatchFrame(myInfoFrame(me))
        mgr.dispatchFrame(configFrame(1, original))
        assertEquals("The radio did not take: role. It may be managed.", mgr.settingsNotice.value)
    }

    // endregion

    // region what a push said is kept ------------------------------------------------

    @Test fun `what a push said is kept until the screen clears it for the next edit or push`() = runBlocking {
        val (mgr, _) = connected()
        assertNull(mgr.lastPushResult.value)

        mgr.pushDeviceConfig(DeviceEdits(positionBroadcastSecs = 300))
        assertEquals("Sent to the radio: position interval. The radio restarts to save them.", mgr.lastPushResult.value)

        mgr.clearLastPushResult() // the operator edited a field
        assertNull(mgr.lastPushResult.value)

        mgr.pushDeviceConfig(DeviceEdits(channelName = "ops"))
        assertEquals("Sent to the radio: channel name. The radio restarts to save them.", mgr.lastPushResult.value)
    }

    @Test fun `the result of a push and the note that the radio did not take it stay through the link drop that follows a push`() = runBlocking {
        val (mgr, link) = connected()
        val original = link.radio.config.getValue(1).copyOf()
        link.radio.onSetConfig = { variant, radio -> if (variant == 1) radio.config[1] = original } // the radio keeps its role
        val sent = "Sent to the radio: role. The radio restarts to save them."
        val note = "The radio did not take: role. It may be managed."

        mgr.pushDeviceConfig(DeviceEdits(role = MeshRole.TAK))
        assertEquals(sent, mgr.lastPushResult.value)

        // The re-read right after the push says the radio kept its role.
        mgr.dispatchFrame(adminResponseFrame(from = me, to = me, adminField = 6, message = ProtoMsg().bytes(1, original).build()))
        assertEquals(note, mgr.settingsNotice.value)

        // Then the radio restarts to save the push, and over TCP the link drops with it.
        mgr.onLinkState(ConnectionState.Connected("radio", useTLS = false), wasConnected = false)
        mgr.onLinkState(ConnectionState.Disconnected, wasConnected = true)
        assertEquals("the result is still there with the link down", sent, mgr.lastPushResult.value)
        assertEquals("and so is the note", note, mgr.settingsNotice.value)

        // The link comes back and the radio downloads its settings again.
        mgr.dispatchFrame(myInfoFrame(me))
        mgr.dispatchFrame(configFrame(1, original))
        assertEquals("and after the download", sent, mgr.lastPushResult.value)
        assertEquals(note, mgr.settingsNotice.value)
    }

    @Test fun `a refused push is kept too, so the operator can read why nothing was sent`() = runBlocking {
        val (mgr, _) = connected()
        mgr.onLinkState(ConnectionState.Connected("radio", useTLS = false), wasConnected = false)
        mgr.onLinkState(ConnectionState.Disconnected, wasConnected = true)

        assertEquals(AdminWriteResult.Refused(RefusalReason.NO_RADIO), mgr.pushDeviceConfig(DeviceEdits(role = MeshRole.ROUTER)))
        assertEquals(RefusalReason.NO_RADIO.message, mgr.lastPushResult.value)
    }

    @Test fun `a push still records what it said when the screen that started it is left`() = runBlocking {
        val (mgr, _) = connected()
        val push = launch { mgr.pushDeviceConfig(DeviceEdits(positionBroadcastSecs = 300)) }
        delay(50) // inside the push: its frames go out a gap apart, so it is still running
        push.cancel()
        push.join()

        assertEquals("Sent to the radio: position interval. The radio restarts to save them.", mgr.lastPushResult.value)
    }

    // endregion
}
