package soy.engindearing.omnitak.mobile.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.AdminReads.Admission
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.channelMessage
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.deviceConfig
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.keyBytes
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.loraConfig
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.positionConfig
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.userMessage
import soy.engindearing.omnitak.mobile.data.RadioSettingsCache.Key

/**
 * The rule for what counts as the radio's own answer to a read of ours ([AdminReads]), and how a response is
 * read into an entry ([AdminAnswer]). The same rule is exercised through the manager's frame path in
 * `MeshtasticManagerReadBackTest`.
 *
 * Node numbers, names and key bytes are made up.
 */
class AdminReadsTest {

    private val me = 0x0A0B0C0Du
    private val other = 0x01020304u

    private var now = 1_000L

    /** Ids come from this list, in order, so a test can see which are skipped. */
    private fun reads(vararg ids: Int) = AdminReads(clock = { now }, nextId = ids.iterator().let { it::next })

    private val loraKey = Key.Config(RadioSettingsCache.CONFIG_LORA)

    /** `AdminMessage { get_config_response = Config { lora = ... } }`. */
    private fun loraAnswer(preset: Int = 4): ByteArray = ProtoMsg().msg(6, ProtoMsg().bytes(6, loraConfig(preset = preset))).build()

    private fun packet(
        payload: ByteArray = loraAnswer(),
        from: UInt = me,
        requestId: UInt? = null,
        rxRssi: Int? = null,
        rxSnr: Float? = null,
        viaMqtt: Boolean = false,
        transportMechanism: Int = 0,
        rxTime: Long? = null,
    ) = MeshPacketDecoded(
        from = from, to = me, channel = 0u, portnum = 6u, payload = payload,
        rxTime = rxTime, rxRssi = rxRssi, rxSnr = rxSnr,
        requestId = requestId, viaMqtt = viaMqtt, transportMechanism = transportMechanism,
    )

    // region the ids ------------------------------------------------------------------------

    @Test fun `every read gets a fresh id that is not zero and not one that is waiting`() {
        // The source offers 0, then an id twice, then another: zero is skipped, and so is an id already waiting.
        val reads = reads(0, 0x1111, 0x1111, 0x2222)
        val first = reads.open(loraKey, 3_000)
        val second = reads.open(Key.Owner, 3_000)

        assertEquals(0x1111u, first.id)
        assertEquals(0x2222u, second.id)
        assertEquals(2, reads.size)
    }

    @Test fun `the default ids are random`() {
        val reads = AdminReads()
        val ids = (1..50).map { reads.open(loraKey, 3_000).id }.toSet()
        assertEquals("fifty reads, fifty different ids", 50, ids.size)
        assertFalse(0u in ids)
    }

    // endregion

    // region an answer ---------------------------------------------------------------------------

    @Test fun `the answer to a read of ours is accepted, and uses the read up`() {
        val reads = reads(0x1111)
        val request = reads.open(loraKey, 3_000)

        val decision = reads.admit(packet(requestId = request.id), me)

        assertEquals(Admission.ACCEPTED, decision.admission)
        assertEquals(loraKey, decision.answer!!.key)
        assertTrue(loraConfig(preset = 4).contentEquals(decision.answer!!.bytes))
        assertEquals(0, reads.size)
        assertEquals("an id is used once", Admission.UNKNOWN_REQUEST, reads.admit(packet(requestId = request.id), me).admission)
    }

    @Test fun `an answer delivers its bytes to the writer waiting for it`() {
        val reads = reads(0x1111, 0x2222)
        val awaited = reads.open(loraKey, 3_000, awaited = true)
        val nobodyWaits = reads.open(Key.Owner, 3_000)

        reads.admit(packet(requestId = awaited.id), me).deliver()
        val owner = ProtoMsg().bytes(4, userMessage(longName = "Reply")).build()
        reads.admit(packet(payload = owner, requestId = nobodyWaits.id), me).deliver() // nothing to complete, no failure

        val answer: CompletableDeferred<ByteArray> = awaited.answer!!
        assertTrue(answer.isCompleted)
        assertTrue(loraConfig(preset = 4).contentEquals(runBlocking { answer.await() }))
        assertNull(nobodyWaits.answer)
    }

    @Test fun `an id nobody issued is not accepted`() {
        val reads = reads(0x1111)
        reads.open(loraKey, 3_000)
        assertEquals(Admission.UNKNOWN_REQUEST, reads.admit(packet(requestId = 0x9999u), me).admission)
        assertEquals("the read is still waiting", 1, reads.size)
    }

    @Test fun `a message that quotes no id answers nothing`() {
        val reads = reads(0x1111)
        reads.open(loraKey, 3_000)
        assertEquals(Admission.NO_REQUEST_ID, reads.admit(packet(requestId = null), me).admission)
        assertEquals("zero is no id either", Admission.NO_REQUEST_ID, reads.admit(packet(requestId = 0u), me).admission)
    }

    @Test fun `an id that was asked for a different entry is not accepted and is not used up`() {
        val reads = reads(0x1111)
        val request = reads.open(Key.Config(RadioSettingsCache.CONFIG_DEVICE), 3_000)
        val deviceAnswer = ProtoMsg().msg(6, ProtoMsg().bytes(1, deviceConfig())).build()

        assertEquals("a LoRa config answering a device config read", Admission.WRONG_ENTRY, reads.admit(packet(requestId = request.id), me).admission)
        val ownerAnswer = ProtoMsg().bytes(4, userMessage()).build()
        assertEquals("an owner answering a device config read", Admission.WRONG_ENTRY, reads.admit(packet(payload = ownerAnswer, requestId = request.id), me).admission)
        assertEquals("the right entry still gets in", Admission.ACCEPTED, reads.admit(packet(payload = deviceAnswer, requestId = request.id), me).admission)
    }

    @Test fun `a channel answer has to be for the channel that was asked for`() {
        val reads = reads(0x1111)
        val request = reads.open(Key.Channel(2), 3_000)
        val channel3 = ProtoMsg().bytes(2, channelMessage(index = 3, name = "Other", role = 2)).build()
        val channel2 = ProtoMsg().bytes(2, channelMessage(index = 2, name = "Mine", role = 2)).build()

        assertEquals(Admission.WRONG_ENTRY, reads.admit(packet(payload = channel3, requestId = request.id), me).admission)
        assertEquals(Admission.ACCEPTED, reads.admit(packet(payload = channel2, requestId = request.id), me).admission)
    }

    @Test fun `an id is not accepted after its deadline`() {
        val reads = reads(0x1111, 0x2222)
        val request = reads.open(loraKey, timeoutMs = 3_000)

        now += 3_000 // on the deadline: still in time
        assertEquals(Admission.ACCEPTED, reads.admit(packet(requestId = request.id), me).admission)

        val late = reads.open(loraKey, timeoutMs = 3_000)
        now += 3_001
        assertEquals(Admission.EXPIRED, reads.admit(packet(requestId = late.id), me).admission)
        assertEquals("and it is gone", Admission.UNKNOWN_REQUEST, reads.admit(packet(requestId = late.id), me).admission)
    }

    @Test fun `a read that was cancelled or cleared cannot be answered`() {
        val reads = reads(0x1111, 0x2222)
        val cancelled = reads.open(loraKey, 3_000)
        val cleared = reads.open(Key.Owner, 3_000)

        reads.cancel(cancelled.id)
        assertEquals(Admission.UNKNOWN_REQUEST, reads.admit(packet(requestId = cancelled.id), me).admission)

        reads.clear()
        val owner = ProtoMsg().bytes(4, userMessage()).build()
        assertEquals(Admission.UNKNOWN_REQUEST, reads.admit(packet(payload = owner, requestId = cleared.id), me).admission)
        assertEquals(0, reads.size)
    }

    @Test fun `a message that is not a settings answer does not use up the id`() {
        val reads = reads(0x1111)
        val request = reads.open(loraKey, 3_000)
        val setConfigEcho = ProtoMsg().msg(34, ProtoMsg().bytes(6, loraConfig())).build()

        assertEquals(Admission.NOT_A_READ_BACK, reads.admit(packet(payload = setConfigEcho, requestId = request.id), me).admission)
        assertEquals(Admission.NOT_A_READ_BACK, reads.admit(packet(payload = byteArrayOf(0x32, 0x7f, 0x01), requestId = request.id), me).admission)
        assertEquals(Admission.ACCEPTED, reads.admit(packet(requestId = request.id), me).admission)
    }

    // endregion

    // region where it came from -------------------------------------------------------------------

    @Test fun `only our own node is believed`() {
        val reads = reads(0x1111)
        val request = reads.open(loraKey, 3_000)

        assertEquals(Admission.NOT_OUR_NODE, reads.admit(packet(from = other, requestId = request.id), me).admission)
        assertEquals(Admission.NOT_OUR_NODE, reads.admit(packet(from = 0u, requestId = request.id), me).admission)
        assertEquals("before the radio has said who it is", Admission.NOT_OUR_NODE, reads.admit(packet(requestId = request.id), null).admission)
        assertEquals("the read is still waiting for its real answer", Admission.ACCEPTED, reads.admit(packet(requestId = request.id), me).admission)
    }

    @Test fun `a packet that carries the marks of one that was received is not an answer, whatever id it quotes`() {
        val reads = reads(0x1111)
        val request = reads.open(loraKey, 3_000)
        val id = request.id

        assertEquals(Admission.HAS_RX_RSSI, reads.admit(packet(requestId = id, rxRssi = -80), me).admission)
        assertEquals(Admission.HAS_RX_RSSI, reads.admit(packet(requestId = id, rxRssi = 12), me).admission)
        assertEquals(Admission.HAS_RX_SNR, reads.admit(packet(requestId = id, rxSnr = 6.5f), me).admission)
        assertEquals(Admission.HAS_RX_SNR, reads.admit(packet(requestId = id, rxSnr = -4.25f), me).admission)
        assertEquals("a negative zero is not zero", Admission.HAS_RX_SNR, reads.admit(packet(requestId = id, rxSnr = -0.0f), me).admission)
        assertEquals(Admission.VIA_MQTT, reads.admit(packet(requestId = id, viaMqtt = true), me).admission)
        assertEquals(Admission.HAS_TRANSPORT, reads.admit(packet(requestId = id, transportMechanism = 1), me).admission)
        assertEquals(Admission.HAS_TRANSPORT, reads.admit(packet(requestId = id, transportMechanism = 7), me).admission)

        assertEquals("none of them used the read up", 1, reads.size)
        assertEquals(Admission.ACCEPTED, reads.admit(packet(requestId = id), me).admission)
    }

    @Test fun `zero values on the receive fields are the same as absent`() {
        val reads = reads(0x1111)
        val request = reads.open(loraKey, 3_000)
        assertEquals(Admission.ACCEPTED, reads.admit(packet(requestId = request.id, rxRssi = 0, rxSnr = 0f, viaMqtt = false, transportMechanism = 0), me).admission)
    }

    @Test fun `rx_time is not part of the rule, a radio with a clock stamps its own answers with it`() {
        val reads = reads(0x1111)
        val request = reads.open(loraKey, 3_000)
        assertEquals(Admission.ACCEPTED, reads.admit(packet(requestId = request.id, rxTime = 1_790_000_000L), me).admission)
    }

    // endregion

    // region reading a response ---------------------------------------------------------------------

    @Test fun `a response is read into the entry it carries`() {
        val position = AdminAnswer.parse(ProtoMsg().msg(6, ProtoMsg().bytes(2, positionConfig(60))).build())!!
        assertEquals(Key.Config(RadioSettingsCache.CONFIG_POSITION), position.key)
        assertTrue(positionConfig(60).contentEquals(position.bytes))

        val channel0 = AdminAnswer.parse(ProtoMsg().bytes(2, channelMessage(index = 0, name = "", role = 1)).build())!!
        assertEquals("slot 0 is not on the wire at all", Key.Channel(0), channel0.key)
        val channel3 = AdminAnswer.parse(ProtoMsg().bytes(2, channelMessage(index = 3, name = "Local", role = 2)).build())!!
        assertEquals(Key.Channel(3), channel3.key)

        val owner = AdminAnswer.parse(ProtoMsg().bytes(4, userMessage(longName = "Reply Name")).build())!!
        assertEquals(Key.Owner, owner.key)
        assertTrue(userMessage(longName = "Reply Name").contentEquals(owner.bytes))
    }

    @Test fun `the session passkey the radio adds to a response is not in the way`() {
        val withPasskey = ProtoMsg().msg(6, ProtoMsg().bytes(6, loraConfig())).bytes(101, ByteArray(8) { it.toByte() }).build()
        assertEquals(loraKey, AdminAnswer.parse(withPasskey)!!.key)
    }

    @Test fun `what the app does not keep, or cannot read, is not an answer`() {
        // The security and network configs hold the private key and the Wi-Fi password: never an entry.
        val security = ProtoMsg().bytes(1, keyBytes(0x20)).build()
        assertNull(AdminAnswer.parse(ProtoMsg().msg(6, ProtoMsg().bytes(8, security)).build()))
        assertNull(AdminAnswer.parse(ProtoMsg().msg(6, ProtoMsg().bytes(4, ProtoMsg().string(3, "ssid-example").build())).build()))
        // A Config with two variants, or none.
        assertNull(AdminAnswer.parse(ProtoMsg().msg(6, ProtoMsg().bytes(1, deviceConfig()).bytes(6, loraConfig())).build()))
        assertNull(AdminAnswer.parse(ProtoMsg().msg(6, ProtoMsg()).build()))
        // A channel past the last slot.
        assertNull(AdminAnswer.parse(ProtoMsg().bytes(2, channelMessage(index = 8)).build()))
        // Two responses in one message.
        assertNull(AdminAnswer.parse(ProtoMsg().bytes(4, userMessage()).bytes(2, channelMessage()).build()))
        // Not a response at all, or damaged.
        assertNull(AdminAnswer.parse(ProtoMsg().msg(34, ProtoMsg().bytes(6, loraConfig())).build()))
        assertNull(AdminAnswer.parse(ByteArray(0)))
        assertNull(AdminAnswer.parse(byteArrayOf(0x32, 0x7f, 0x01)))
        assertNotNull(AdminAnswer.parse(loraAnswer()))
        assertNotEquals(0, loraAnswer().size)
    }

    // endregion
}
