package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The premises of the tests that follow a lost link through a restart: [FakeRadio] does with an edit transaction
 * what firmware 2.7.26 does (AdminModule.cpp, `begin_edit_settings` line 332, `commit_edit_settings` line 338,
 * `saveChanges` lines 1364 to 1375).
 */
class FakeRadioTransactionTest {

    private val me = 0x0A0B0C0D

    private fun frame(admin: ByteArray): ByteArray =
        MeshWire.buildToRadio(portnum = 6UL, payload = admin, to = me.toUInt(), wantAck = true, wantResponse = true)

    private val begin get() = frame(ProtoMsg().bool(64, true).build())
    private val commit get() = frame(ProtoMsg().bool(65, true).build())
    private fun setOwner(name: String) = frame(ProtoMsg().bytes(32, ProtoMsg().string(2, name).build()).build())

    private fun FakeRadio.send(f: ByteArray) = handle(f) { _, _ -> }

    private fun FakeRadio.name(): String = AdminTestFrames.fields(owner).first { it.number == 2 }.bytes.toString(Charsets.UTF_8)

    @Test fun `a transaction that was never committed is lost at a restart, with what it held`() {
        val radio = FakeRadio.stock()
        radio.send(begin)
        radio.send(setOwner("Unsaved"))
        assertEquals("in memory it is the radio's value", "Unsaved", radio.name())
        assertFalse("nothing asked for a restart", radio.restartPending)

        radio.restart()

        assertEquals("Sim Radio One", radio.name())
        assertFalse(radio.inTransaction)
    }

    @Test fun `a commit saves what is in memory and asks for a restart`() {
        val radio = FakeRadio.stock()
        radio.send(begin)
        radio.send(setOwner("Saved"))
        radio.send(commit)
        assertTrue(radio.restartPending)
        assertFalse(radio.inTransaction)

        radio.restart()

        assertEquals("Saved", radio.name())
        assertFalse(radio.restartPending)
    }

    @Test fun `a write outside a transaction is saved at once and asks for a restart`() {
        val radio = FakeRadio.stock()
        radio.send(setOwner("Plain"))
        assertTrue(radio.restartPending)
        radio.restart()
        assertEquals("Plain", radio.name())
    }

    @Test fun `a radio that counts its restarts counts them, and one that does not reports 0`() {
        val counting = FakeRadio.stock().also { it.countsRestarts = true; it.rebootCount = 5 }
        assertEquals(FromRadioFrame.MyInfo(me.toUInt(), 5u), MeshtasticProtoParser.parseFromRadio(counting.myInfo(me)))
        counting.restart()
        assertEquals(FromRadioFrame.MyInfo(me.toUInt(), 6u), MeshtasticProtoParser.parseFromRadio(counting.myInfo(me)))

        val plain = FakeRadio.stock()
        plain.restart()
        assertEquals(FromRadioFrame.MyInfo(me.toUInt(), 0u), MeshtasticProtoParser.parseFromRadio(plain.myInfo(me)))
    }
}
