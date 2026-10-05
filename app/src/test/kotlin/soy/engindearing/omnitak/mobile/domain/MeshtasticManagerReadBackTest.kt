package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.packetIdOf
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.positionConfig
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.userMessage
import soy.engindearing.omnitak.mobile.data.AdminWriteResult
import soy.engindearing.omnitak.mobile.data.DeviceEdits
import soy.engindearing.omnitak.mobile.data.FakeRadio
import soy.engindearing.omnitak.mobile.data.MeshChannelPreset
import soy.engindearing.omnitak.mobile.data.MeshRegion
import soy.engindearing.omnitak.mobile.data.ProtoMsg
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the manager takes as the radio's answer to a read of ours, through the frame path the transports use
 * ([MeshtasticManager.dispatchFrame]). The same rule, one case at a time, is in `AdminReadsTest`.
 *
 * Here the link records what the app sends and answers nothing; each test decides what arrives and when. An
 * answer that passes has the radio's own node as sender, quotes the packet id of a read of ours that is waiting
 * for that entry, and carries none of the marks of a packet that was received (a signal strength, a signal to
 * noise ratio, MQTT, another transport). A download frame is not a mesh packet and needs none of that.
 *
 * Node numbers, names and key bytes are made up.
 */
class MeshtasticManagerReadBackTest {

    private val me = 0x0A0B0C0D
    private val other = 0x01020304

    /** Time as the manager's reads see it. */
    private var nowMs = 1_000L

    private class Quiet(val mgr: MeshtasticManager, val radio: FakeRadio) {
        val sent = CopyOnWriteArrayList<ByteArray>()
        val send: suspend (ByteArray) -> Boolean = { frame -> sent += frame; true }

        /** The packet id of the first frame sent whose AdminMessage starts with [field] (and, when given, [value]). */
        fun idOf(field: Int, value: Int? = null): Int {
            val frame = sent.first { f ->
                val first = AdminTestFrames.decode(f).admin.first()
                first.number == field && (value == null || first.varint == value.toULong())
            }
            return packetIdOf(frame)
        }

        fun names(): List<String> = sent.map { f ->
            val first = AdminTestFrames.decode(f).admin.first()
            when (first.number) {
                1 -> "get_channel:${first.varint.toInt() - 1}"
                3 -> "get_owner"
                5 -> "get_config:${first.varint.toInt() + 1}"
                33 -> "set_channel"
                34 -> "set_config"
                64 -> "begin"
                65 -> "commit"
                else -> "admin:${first.number}"
            }
        }
    }

    /** A manager that has had a download from a factory radio, on a link that answers nothing by itself. */
    private fun quiet(): Quiet {
        val mgr = MeshtasticManager(readClock = { nowMs })
        val q = Quiet(mgr, FakeRadio.factory())
        mgr.adminSendOverride = q.send
        mgr.dispatchFrame(myInfoFrame(me))
        mgr.dispatchFrame(nodeInfoFrame(me, q.radio.owner))
        for (i in 0..7) mgr.dispatchFrame(channelFrame(q.radio.channels.getValue(i)))
        for (variant in 1..10) mgr.dispatchFrame(configFrame(variant, q.radio.config[variant] ?: ByteArray(0)))
        return q
    }

    private val loraDownload get() = FakeRadio.factory().config.getValue(6)

    /** `AdminMessage { get_config_response = Config { <variant> = message } }` as a frame from the radio. */
    private fun configAnswer(variant: Int, message: ByteArray, from: Int = me, requestId: Int? = null, rxRssi: Int = 0, rxSnr: Float = 0f,
                             viaMqtt: Boolean = false, transportMechanism: Int = 0, rxTime: Int = 0, hopStart: Int = 0) =
        adminResponseFrame(
            from = from, to = me, adminField = 6, message = ProtoMsg().bytes(variant, message).build(), requestId = requestId,
            rxRssi = rxRssi, rxSnr = rxSnr, viaMqtt = viaMqtt, transportMechanism = transportMechanism, rxTime = rxTime, hopStart = hopStart,
        )

    private fun assertLoraIs(what: String, expected: ByteArray, q: Quiet) {
        // Not assertArrayEquals: a failure message must never print bytes that could be a key.
        val actual = q.mgr.radioSettings.config(6)
        assertTrue("$what: the LoRa entry changed", actual != null && expected.contentEquals(actual))
    }

    // region an answer that is taken ------------------------------------------------------------

    @Test fun `the answer to a read of ours is taken, and reaches the cache and the store`() = runBlocking {
        val q = quiet()
        val seen = mutableListOf<AdminResponse>()
        q.mgr.adminResponseSink = { seen += it }
        q.mgr.requestDeviceConfig()

        val id = q.idOf(5, 5)
        q.mgr.dispatchFrame(configAnswer(6, loraConfig(preset = 4, region = 3), requestId = id))

        assertTrue(loraConfig(preset = 4, region = 3).contentEquals(q.mgr.radioSettings.config(6)))
        assertEquals(listOf<AdminResponse>(AdminResponse.LoraConfig(MeshChannelPreset.MEDIUM_FAST, MeshRegion.EU_868)), seen)
    }

    @Test fun `rx_time and hop_start on an answer do not make it fail, only the marks of a received packet do`() = runBlocking {
        // A radio with a clock stamps rx_time on what it makes itself, and some firmware sets hop_start too.
        val q = quiet()
        q.mgr.requestDeviceConfig()

        q.mgr.dispatchFrame(configAnswer(6, loraConfig(preset = 4), requestId = q.idOf(5, 5), rxTime = 1_790_000_000, hopStart = 3))

        assertTrue(loraConfig(preset = 4).contentEquals(q.mgr.radioSettings.config(6)))
    }

    // endregion

    // region answers that are not -----------------------------------------------------------------

    @Test fun `right sender but no request id is ignored`() = runBlocking {
        val q = quiet()
        val seen = mutableListOf<AdminResponse>()
        q.mgr.adminResponseSink = { seen += it }
        q.mgr.requestDeviceConfig()

        q.mgr.dispatchFrame(configAnswer(6, loraConfig(preset = 4), requestId = null))

        assertLoraIs("no request id", loraDownload, q)
        assertTrue("the store heard nothing: $seen", seen.isEmpty())
    }

    @Test fun `right sender and a wrong request id is ignored`() = runBlocking {
        val q = quiet()
        val seen = mutableListOf<AdminResponse>()
        q.mgr.adminResponseSink = { seen += it }
        q.mgr.requestDeviceConfig()
        val real = q.idOf(5, 5)

        q.mgr.dispatchFrame(configAnswer(6, loraConfig(preset = 4), requestId = real xor 0x5555))
        q.mgr.dispatchFrame(configAnswer(6, loraConfig(preset = 4), requestId = 1))

        assertLoraIs("wrong request id", loraDownload, q)
        assertTrue("the store heard nothing: $seen", seen.isEmpty())
    }

    @Test fun `the right request id for another entry is ignored, and the read still gets its own answer`() = runBlocking {
        val q = quiet()
        q.mgr.requestDeviceConfig()
        val ownerRead = q.idOf(3)
        val loraRead = q.idOf(5, 5)

        // The LoRa answer quoting the owner read's id.
        q.mgr.dispatchFrame(configAnswer(6, loraConfig(preset = 4), requestId = ownerRead))
        assertLoraIs("an id asked for another entry", loraDownload, q)

        q.mgr.dispatchFrame(configAnswer(6, loraConfig(preset = 4), requestId = loraRead))
        assertTrue(loraConfig(preset = 4).contentEquals(q.mgr.radioSettings.config(6)))
        // The owner read was not used up by the wrong answer.
        q.mgr.dispatchFrame(adminResponseFrame(from = me, to = me, adminField = 4, message = userMessage(longName = "Reply Name"), requestId = ownerRead))
        assertTrue(userMessage(longName = "Reply Name").contentEquals(q.mgr.radioSettings.owner()))
    }

    @Test fun `the right request id from another node is ignored`() = runBlocking {
        val q = quiet()
        q.mgr.requestDeviceConfig()

        q.mgr.dispatchFrame(configAnswer(6, loraConfig(preset = 4), from = other, requestId = q.idOf(5, 5)))
        q.mgr.dispatchFrame(configAnswer(6, loraConfig(preset = 4), from = 0, requestId = q.idOf(5, 5)))

        assertLoraIs("another node", loraDownload, q)
    }

    @Test fun `the right request id with a mark of a received packet is ignored, each mark on its own`() = runBlocking {
        val q = quiet()
        val seen = mutableListOf<AdminResponse>()
        q.mgr.adminResponseSink = { seen += it }
        q.mgr.requestDeviceConfig()
        val deviceRead = q.idOf(5, 0)
        val positionRead = q.idOf(5, 1)
        val loraRead = q.idOf(5, 5)
        val ownerRead = q.idOf(3)
        val channelRead = q.idOf(1, 1)
        val before = q.mgr.radioSettings.toString()

        q.mgr.dispatchFrame(configAnswer(6, loraConfig(preset = 4), requestId = loraRead, rxRssi = -80))
        q.mgr.dispatchFrame(configAnswer(2, positionConfig(60), requestId = positionRead, rxSnr = 6.5f))
        q.mgr.dispatchFrame(configAnswer(1, deviceConfig(role = 2), requestId = deviceRead, viaMqtt = true))
        q.mgr.dispatchFrame(adminResponseFrame(from = me, to = me, adminField = 4, message = userMessage(longName = "Not the radio"), requestId = ownerRead, transportMechanism = 1))
        q.mgr.dispatchFrame(adminResponseFrame(from = me, to = me, adminField = 2, message = channelMessage(name = "Not the radio"), requestId = channelRead, rxRssi = -91))

        assertLoraIs("rx_rssi", loraDownload, q)
        assertTrue("rx_snr: the position entry changed", FakeRadio.factory().config.getValue(2).contentEquals(q.mgr.radioSettings.config(2)))
        assertTrue("via_mqtt: the device entry changed", FakeRadio.factory().config.getValue(1).contentEquals(q.mgr.radioSettings.config(1)))
        assertTrue("transport_mechanism: the owner changed", FakeRadio.factory().owner.contentEquals(q.mgr.radioSettings.owner()))
        assertTrue("rx_rssi on a channel: the channel changed", FakeRadio.factory().channels.getValue(0).contentEquals(q.mgr.radioSettings.channel(0)))
        assertEquals("nothing was added or dropped either", before, q.mgr.radioSettings.toString())
        assertTrue("the store heard nothing: $seen", seen.isEmpty())

        // None of those used its read up: the radio's own answers still get in.
        q.mgr.dispatchFrame(configAnswer(6, loraConfig(preset = 4), requestId = loraRead))
        q.mgr.dispatchFrame(configAnswer(2, positionConfig(60), requestId = positionRead))
        assertTrue(loraConfig(preset = 4).contentEquals(q.mgr.radioSettings.config(6)))
        assertTrue(positionConfig(60).contentEquals(q.mgr.radioSettings.config(2)))
    }

    @Test fun `an id is used once, the same id again is ignored`() = runBlocking {
        val q = quiet()
        q.mgr.requestDeviceConfig()
        val id = q.idOf(5, 5)

        q.mgr.dispatchFrame(configAnswer(6, loraConfig(preset = 4), requestId = id))
        q.mgr.dispatchFrame(configAnswer(6, loraConfig(preset = 6, region = 3), requestId = id))

        assertTrue("the first answer stands", loraConfig(preset = 4).contentEquals(q.mgr.radioSettings.config(6)))
    }

    @Test fun `an id is not taken after its deadline`() = runBlocking {
        val q = quiet()
        q.mgr.requestDeviceConfig()
        val id = q.idOf(5, 5)

        nowMs += 60_000 // a minute later: far past any read timeout

        q.mgr.dispatchFrame(configAnswer(6, loraConfig(preset = 4), requestId = id))
        assertLoraIs("an id past its deadline", loraDownload, q)
    }

    @Test fun `reads of an earlier session are not answered in the next one`() = runBlocking {
        val q = quiet()
        q.mgr.requestDeviceConfig()
        val id = q.idOf(5, 5)

        // The link drops and the same radio connects again, with its download.
        q.mgr.onLinkState(ConnectionState.Connected("radio", useTLS = false), wasConnected = false)
        q.mgr.onLinkState(ConnectionState.Disconnected, wasConnected = true)
        q.mgr.dispatchFrame(myInfoFrame(me))
        q.mgr.dispatchFrame(configFrame(6, loraDownload))

        q.mgr.dispatchFrame(configAnswer(6, loraConfig(preset = 4), requestId = id))
        assertLoraIs("an id from the session before", loraDownload, q)
    }

    // endregion

    // region the read of a write ------------------------------------------------------------------

    @Test fun `a frame that is not the radio's answer does not end the read of a write, and the genuine answer after it does`() = runBlocking {
        val q = quiet()
        // What another client could leave on the radio: a position config the app must never patch.
        val notOurs = ProtoMsg().varint(1, 777).varint(7, 1234).build()
        val push = async { q.mgr.pushDeviceConfig(DeviceEdits(positionBroadcastSecs = 300)) }

        // The write starts by asking the radio for its position config.
        withTimeout(2_000) { while (q.sent.isEmpty()) delay(10) }
        val id = q.idOf(5, 1)

        q.mgr.dispatchFrame(configAnswer(2, notOurs, requestId = null))
        q.mgr.dispatchFrame(configAnswer(2, notOurs, requestId = id xor 0x5555))
        q.mgr.dispatchFrame(configAnswer(2, notOurs, from = other, requestId = id))
        q.mgr.dispatchFrame(configAnswer(2, notOurs, requestId = id, rxRssi = -80))
        q.mgr.dispatchFrame(configAnswer(2, notOurs, requestId = id, rxSnr = 6.5f))
        q.mgr.dispatchFrame(configAnswer(2, notOurs, requestId = id, viaMqtt = true))
        q.mgr.dispatchFrame(configAnswer(2, notOurs, requestId = id, transportMechanism = 1))
        delay(300)
        assertTrue("the read is still waiting", push.isActive)
        assertEquals("nothing but the read has gone out", listOf("get_config:2"), q.names())

        // The radio's own answer.
        q.mgr.dispatchFrame(configAnswer(2, FakeRadio.factory().config.getValue(2), requestId = id))
        val result = withTimeout(5_000) { push.await() }

        assertEquals(AdminWriteResult.Sent(listOf(AdminSetting.POSITION_INTERVAL)), result)
        assertEquals(listOf("get_config:2", "begin", "set_config", "commit"), q.names())
        val (variant, written) = AdminTestFrames.decode(q.sent[2]).setConfig()
        assertEquals(2, variant)
        val expected = ProtoMsg().varint(1, 300).varint(7, 811).varint(13, 1).build() // the radio's own bytes, one field changed
        assertTrue("the write was patched onto the radio's own answer", expected.contentEquals(written))
    }

    @Test fun `a write whose read gets no genuine answer changes nothing and says the radio did not answer`() = runBlocking {
        val q = quiet()
        val push = async { q.mgr.pushDeviceConfig(DeviceEdits(positionBroadcastSecs = 300)) }
        withTimeout(2_000) { while (q.sent.isEmpty()) delay(10) }
        val id = q.idOf(5, 1)
        // Only what is not the radio's answer arrives.
        q.mgr.dispatchFrame(configAnswer(2, positionConfig(60), requestId = null))
        q.mgr.dispatchFrame(configAnswer(2, positionConfig(60), requestId = id, rxRssi = -80))

        val result = withTimeout(10_000) { push.await() }

        assertTrue("refused, not written: $result", result is AdminWriteResult.Refused)
        assertEquals(listOf("get_config:2"), q.names())
        assertFalse(result.reachedRadio)
    }

    // endregion
}
