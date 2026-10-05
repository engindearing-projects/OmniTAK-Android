package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.AdminSetting
import soy.engindearing.omnitak.mobile.data.AdminTestFrames
import soy.engindearing.omnitak.mobile.data.AdminWriteResult
import soy.engindearing.omnitak.mobile.data.DeviceEdits
import soy.engindearing.omnitak.mobile.data.FakeRadio
import soy.engindearing.omnitak.mobile.data.InterruptedWrite
import soy.engindearing.omnitak.mobile.data.MeshChannel
import soy.engindearing.omnitak.mobile.data.PositionFixtures
import soy.engindearing.omnitak.mobile.data.RefusalReason

/**
 * A sequence whose commit never went out because the link dropped, and what the app does about the transaction it
 * left open on the radio at the next link-up (firmware 2.7.26, AdminModule.cpp: an open transaction survives a
 * client that goes away, nothing is saved or restarted while it is open, and a restart clears it and the changes
 * with it; see [InterruptedWrite]).
 *
 * The app and the radio are wired as in [RadioApp]. The radio is a [FakeRadio] that follows the firmware's rule for
 * transactions, in two kinds: one that counts its restarts (ESP32: `my_info.reboot_count`) and one that does not
 * (every other radio, the simulator included: the count is 0 each time). Each is tried with the radio still running
 * and with the radio restarted since.
 *
 * Node numbers, names and keys are made up.
 */
class MeshtasticManagerOpenTransactionTest {

    private val me = 0x0A0B0C0D
    private val other = 0x01020304

    private fun counting(count: Int = 5): FakeRadio = FakeRadio.stock().also { it.countsRestarts = true; it.rebootCount = count }

    private fun plain(): FakeRadio = FakeRadio.stock()

    private fun ownerName(radio: FakeRadio): String =
        AdminTestFrames.fields(radio.owner).lastOrNull { it.number == 2 }?.bytes?.toString(Charsets.UTF_8) ?: ""

    private fun waitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    /** Long enough for a frame that is going to be sent to have been sent. */
    private fun settle() = Thread.sleep(300)

    /**
     * An owner rename whose commit does not go out: the link takes the read, `begin_edit_settings` and `set_owner`
     * (frames 1 to 3) and refuses the commit (frame 4). The radio then holds the new name in memory, unsaved, with
     * the transaction open.
     */
    private fun interruptedRename(app: RadioApp, name: String = "Renamed Radio", short: String = ""): AdminWriteResult {
        app.failFromFrame = 4
        val result = runBlocking { app.mgr.pushDeviceConfig(DeviceEdits(longName = name, shortName = short.ifEmpty { null })) }
        app.failFromFrame = null
        return result
    }

    private val renamed = "Renamed Radio"
    private val original = "Sim Radio One"

    // region a radio that counts its restarts ---------------------------------------------------------------

    @Test fun `a counting radio that has not restarted gets the commit first and the change is saved`() {
        val radio = counting(5)
        val app = RadioApp(radio).connect()
        val result = interruptedRename(app)
        assertTrue(result.toString(), result is AdminWriteResult.Incomplete && !result.committed)
        assertTrue("the transaction is open", radio.inTransaction)
        assertEquals("the new name is in memory, unsaved", renamed, ownerName(radio))
        app.drop()
        val before = radio.log.size

        app.connect()

        assertTrue("the commit goes out", waitUntil { radio.restartPending })
        settle()
        assertEquals("the commit is the only frame: no read goes out before it or after it", listOf("commit"), radio.log.drop(before))
        assertFalse(radio.inTransaction)
        assertEquals(InterruptedWrite.SAVED, app.mgr.lastPushResult.value)

        app.restart()
        assertEquals("it was saved: the name survives the restart", renamed, ownerName(radio))
    }

    @Test fun `a counting radio that has restarted since gets nothing, and the result says the change was not saved`() {
        val radio = counting(5)
        val app = RadioApp(radio).connect()
        interruptedRename(app)
        app.restart() // a power cycle: memory is lost, the count goes up to 6
        assertEquals("the old name is back", original, ownerName(radio))
        val before = radio.log.size

        app.connect()
        settle()

        assertEquals("nothing is sent to it", before, radio.log.size)
        assertEquals(InterruptedWrite.NOT_SAVED, app.mgr.lastPushResult.value)
        assertEquals(original, ownerName(radio))
        assertFalse(radio.restartPending)
    }

    @Test fun `a radio that starts reporting a count is not treated as one that does not count`() {
        // The sequence was remembered with no count; the radio that connects now has one. Both counts decide nothing
        // alike: the rule for a radio that counts applies as soon as either is not 0.
        val app = RadioApp(plain()).connect()
        interruptedRename(app)
        app.drop()
        val nowCounting = counting(5)
        nowCounting.owner = app.radio.owner // the same radio, now on firmware that counts
        val before = nowCounting.log.size

        app.connect(to = nowCounting, as_ = me)
        settle()

        assertEquals("a different count: it has restarted, nothing is sent", before, nowCounting.log.size)
        assertEquals(InterruptedWrite.NOT_SAVED, app.mgr.lastPushResult.value)
    }

    @Test fun `a transaction with nothing written yet is closed too, on a counting radio that has not restarted`() {
        val radio = counting(5)
        val app = RadioApp(radio).connect()
        app.failFromFrame = 3 // the read and begin go out, the first set does not
        val result = runBlocking { app.mgr.pushDeviceConfig(DeviceEdits(longName = renamed)) }
        app.failFromFrame = null
        assertTrue(result.toString(), result is AdminWriteResult.Incomplete)
        assertTrue("begin went out: the transaction is open and empty", radio.inTransaction)
        app.drop()
        val before = radio.log.size

        app.connect()

        assertTrue(waitUntil { radio.restartPending })
        assertEquals(listOf("commit"), radio.log.drop(before))
        assertFalse(radio.inTransaction)
    }

    @Test fun `a commit that does not go out at the reconnect is tried again at the next one`() {
        val radio = counting(5)
        val app = RadioApp(radio).connect()
        interruptedRename(app)
        app.drop()
        val before = radio.log.size

        app.failFromFrame = 1 // this link refuses the commit too
        app.connect()
        settle()
        assertEquals("nothing reached the radio", before, radio.log.size)
        assertTrue("and nothing was said: the change is still waiting", app.mgr.lastPushResult.value != InterruptedWrite.SAVED)
        assertTrue(radio.inTransaction)

        app.failFromFrame = null
        app.drop().connect()

        assertTrue(waitUntil { radio.restartPending })
        assertEquals(listOf("commit"), radio.log.drop(before))
        assertEquals(InterruptedWrite.SAVED, app.mgr.lastPushResult.value)
    }

    @Test(timeout = 30_000) fun `no other admin frame goes out ahead of the commit, on a slow link`() {
        val radio = counting(5)
        val app = RadioApp(radio).connect()
        interruptedRename(app)
        app.drop()
        val before = radio.log.size
        // The link holds the commit for a moment: anything the app does not hold back would go out ahead of it.
        app.delayFrame = { frame -> if (AdminTestFrames.decode(frame).admin.first().number == 65) 400L else 0L }

        app.connect()
        val reads = runBlocking { app.mgr.requestDeviceConfig() } // the screen reads on connect

        assertEquals("the reads went out", 12, reads)
        val frames = radio.log.drop(before)
        assertEquals("the commit is the first frame the radio sees", "commit", frames.first())
        assertEquals("then the reads", "get_owner", frames[1])
        assertEquals(13, frames.size)
    }

    // endregion

    // region a radio that does not count its restarts -----------------------------------------------------------

    @Test fun `a radio that does not count waits for its download, finds the change still in its memory and saves it`() {
        val radio = plain()
        val app = RadioApp(radio).connect()
        interruptedRename(app)
        app.drop()
        val before = radio.log.size

        val download = radio.download(me)
        app.mgr.onLinkState(ConnectionState.Connected("radio", useTLS = false), wasConnected = false)
        app.mgr.dispatchFrame(download.first()) // my_info: the count is 0 both times, so it cannot tell
        settle()
        assertEquals("nothing goes out until the download says what the radio holds", before, radio.log.size)

        download.drop(1).forEach { app.mgr.dispatchFrame(it) }

        assertTrue(waitUntil { radio.restartPending })
        assertEquals(listOf("commit"), radio.log.drop(before))
        assertEquals(InterruptedWrite.SAVED, app.mgr.lastPushResult.value)
        app.restart()
        assertEquals("saved", renamed, ownerName(radio))
    }

    @Test fun `a radio that does not count and has restarted since is sent nothing, and the result says it was not saved`() {
        val radio = plain()
        val app = RadioApp(radio).connect()
        interruptedRename(app)
        app.restart()
        assertEquals(original, ownerName(radio))
        val before = radio.log.size

        app.connect()
        settle()

        assertEquals("a commit to a radio that restarted would restart it a second time for nothing", before, radio.log.size)
        assertEquals(InterruptedWrite.NOT_SAVED, app.mgr.lastPushResult.value)
        assertFalse(radio.restartPending)
    }

    @Test fun `a value the radio held before the sequence is no evidence that its memory survived`() {
        // The rename also names the short name the radio already has: reporting it says nothing about a restart.
        val radio = plain()
        val app = RadioApp(radio).connect()
        interruptedRename(app, name = renamed, short = "SR1")
        app.restart()
        val before = radio.log.size

        app.connect()
        settle()

        assertEquals("long name: old, short name: the same as sent but also the same as before", before, radio.log.size)
        assertEquals(InterruptedWrite.NOT_SAVED, app.mgr.lastPushResult.value)
    }

    @Test fun `with nothing written yet nothing is sent, and a transaction that is still open is closed by the next push`() {
        val radio = plain()
        val app = RadioApp(radio).connect()
        app.failFromFrame = 3
        runBlocking { app.mgr.pushDeviceConfig(DeviceEdits(longName = renamed)) }
        app.failFromFrame = null
        assertTrue(radio.inTransaction)
        val said = app.mgr.lastPushResult.value
        app.drop()
        val before = radio.log.size

        app.connect()
        settle()

        assertEquals("nothing is sent", before, radio.log.size)
        assertEquals("and nothing new is said", said, app.mgr.lastPushResult.value)
        assertTrue("the transaction is still open", radio.inTransaction)

        assertEquals(AdminWriteResult.Sent(listOf(AdminSetting.LONG_NAME)), runBlocking { app.mgr.pushDeviceConfig(DeviceEdits(longName = renamed)) })
        assertFalse("the next push closed it", radio.inTransaction)
    }

    @Test fun `a rebroadcast mode that was written is found in the download like any other setting`() {
        for (restarted in listOf(false, true)) {
            val radio = plain()
            val app = RadioApp(radio).connect()
            app.failFromFrame = 4 // the read, begin, the write, and the commit is refused
            runBlocking { app.mgr.applyRebroadcastMode(soy.engindearing.omnitak.mobile.data.RebroadcastMode.KNOWN_ONLY) }
            app.failFromFrame = null
            if (restarted) app.restart() else app.drop()
            val before = radio.log.size

            app.connect()

            if (restarted) {
                settle()
                assertEquals("restarted: sent nothing", before, radio.log.size)
                assertEquals(InterruptedWrite.NOT_SAVED, app.mgr.lastPushResult.value)
            } else {
                assertTrue("still in memory: committed", waitUntil { radio.restartPending })
                assertEquals(listOf("commit"), radio.log.drop(before))
                assertEquals(InterruptedWrite.SAVED, app.mgr.lastPushResult.value)
            }
        }
    }

    @Test fun `a sequence that wrote something the app cannot compare is not guessed at`() {
        val radio = plain()
        val app = RadioApp(radio).connect()
        app.failFromFrame = 4 // the free slot is found with one read, then begin, set_channel, and the commit is refused
        val result = runBlocking { app.mgr.applyChannel(MeshChannel(name = "imported", psk = PositionFixtures.privateKey())) }
        app.failFromFrame = null
        assertTrue(result.toString(), result is AdminWriteResult.Incomplete)
        app.drop()
        val before = radio.log.size

        app.connect()
        settle()

        assertEquals("not sent: an imported channel is not a value the app can find again", before, radio.log.size)
        assertEquals(InterruptedWrite.CHECK, app.mgr.lastPushResult.value)
    }

    @Test(timeout = 30_000) fun `an operator write that starts while the decision waits goes out after the commit`() {
        val radio = plain()
        val app = RadioApp(radio).connect()
        interruptedRename(app)
        app.drop()
        val before = radio.log.size
        val download = radio.download(me)
        app.mgr.onLinkState(ConnectionState.Connected("radio", useTLS = false), wasConnected = false)
        app.mgr.dispatchFrame(download.first())

        // The operator pushes on another thread while the app is waiting for the download.
        val started = CoroutineScope(Dispatchers.Default).async { app.mgr.pushDeviceConfig(DeviceEdits(longName = "Second Name")) }
        Thread.sleep(300)
        assertEquals("held: nothing has gone out", before, radio.log.size)
        download.drop(1).forEach { app.mgr.dispatchFrame(it) }
        val push = runBlocking { started.await() }

        assertEquals(AdminWriteResult.Sent(listOf(AdminSetting.LONG_NAME)), push)
        val frames = radio.log.drop(before)
        assertEquals("the commit that closes the old transaction comes first", "commit", frames.first())
        assertEquals(listOf("get_owner", "begin", "set_owner", "commit"), frames.drop(1))
    }

    @Test(timeout = 30_000) fun `a download that never completes does not hold the writer for ever, and the sequence is forgotten`() {
        val radio = plain()
        val app = RadioApp(radio).connect()
        interruptedRename(app)
        app.drop()
        app.mgr.settleTimeoutMs = 300
        val before = radio.log.size
        app.mgr.onLinkState(ConnectionState.Connected("radio", useTLS = false), wasConnected = false)
        app.mgr.dispatchFrame(radio.download(me).first()) // my_info and then nothing

        val read = runBlocking { app.mgr.requestDeviceConfig() } // waits, then goes out when the hold ends

        assertEquals(12, read)
        assertFalse("nothing was decided, so no commit", radio.log.drop(before).contains("commit"))
        // Forgotten: a later link-up sends nothing.
        app.drop()
        val after = radio.log.size
        app.connect()
        settle()
        assertEquals(after, radio.log.size)
    }

    @Test(timeout = 30_000) fun `a link that drops while the decision waits releases the writer and keeps the sequence for the next link-up`() {
        val radio = plain()
        val app = RadioApp(radio).connect()
        interruptedRename(app)
        app.drop()
        val before = radio.log.size
        app.mgr.onLinkState(ConnectionState.Connected("radio", useTLS = false), wasConnected = false)
        app.mgr.dispatchFrame(radio.download(me).first()) // my_info only
        // The screen reads on another thread while the app waits for the download.
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val reading = CoroutineScope(Dispatchers.Default).async { app.mgr.requestDeviceConfig().also { finished.set(true) } }
        Thread.sleep(200)
        assertFalse("held while the decision waits", finished.get())
        app.drop()
        assertTrue("the drop releases it at once, to find no radio to read", waitUntil(2_000) { finished.get() })
        assertEquals(0, runBlocking { reading.await() })
        assertEquals("nothing was sent on that link", before, radio.log.size)

        app.connect() // the next one, with its whole download

        assertTrue(waitUntil { radio.restartPending })
        assertEquals(listOf("commit"), radio.log.drop(before))
        assertEquals(InterruptedWrite.SAVED, app.mgr.lastPushResult.value)
    }

    // endregion

    // region what is not a decision -------------------------------------------------------------------------------

    @Test fun `a different radio is sent nothing, and the sequence is forgotten`() {
        val first = counting(5)
        val app = RadioApp(first).connect()
        interruptedRename(app)
        app.drop()
        val second = counting(5)

        app.connect(to = second, as_ = other)
        settle()
        assertTrue("nothing was sent to the other radio", second.log.isEmpty())
        assertTrue("and it says nothing", app.mgr.lastPushResult.value != InterruptedWrite.SAVED && app.mgr.lastPushResult.value != InterruptedWrite.NOT_SAVED)

        app.drop()
        val before = first.log.size
        app.connect(to = first, as_ = me) // the first radio again, with the same count
        settle()
        assertEquals("forgotten when the other radio connected: nothing is sent to the first one either", before, first.log.size)
        assertTrue("its transaction is still open: the next push of the app closes it", first.inTransaction)
    }

    @Test fun `a later sequence that commits closes the record`() {
        val radio = counting(5)
        val app = RadioApp(radio).connect()
        interruptedRename(app)
        // The link is back and the operator pushes again: this sequence commits, which closes the open transaction.
        assertEquals(AdminWriteResult.Sent(listOf(AdminSetting.LONG_NAME)), runBlocking { app.mgr.pushDeviceConfig(DeviceEdits(longName = "Third Name")) })
        assertFalse(radio.inTransaction)
        app.drop()
        val before = radio.log.size

        app.connect()
        settle()

        assertEquals("nothing is left to close: no stray commit, no second restart", before, radio.log.size)
    }

    @Test fun `a sequence that committed leaves nothing to close`() {
        val radio = counting(5)
        val app = RadioApp(radio).connect()
        assertEquals(AdminWriteResult.Sent(listOf(AdminSetting.LONG_NAME)), runBlocking { app.mgr.pushDeviceConfig(DeviceEdits(longName = renamed)) })
        app.drop()
        val before = radio.log.size

        app.connect()
        settle()

        assertEquals(before, radio.log.size)
    }

    @Test fun `a sequence that was refused before it began leaves nothing to close`() {
        val radio = counting(5)
        val app = RadioApp(radio).connect()
        app.linkOpen = false
        assertEquals(AdminWriteResult.Refused(RefusalReason.NO_RADIO), runBlocking { app.mgr.pushDeviceConfig(DeviceEdits(longName = renamed)) })
        app.linkOpen = true
        app.drop()
        val before = radio.log.size

        app.connect()
        settle()

        assertEquals(before, radio.log.size)
    }

    @Test fun `the sequence is judged once, at the first link-up`() {
        val radio = counting(5)
        val app = RadioApp(radio).connect()
        interruptedRename(app)
        app.drop().connect()
        assertTrue(waitUntil { radio.restartPending })
        app.restart().connect() // the radio restarted because of the commit: the count is 6
        settle()
        app.drop().connect()
        settle()

        assertEquals("one commit, not one for each link-up", 1, radio.log.count { it == "commit" })
    }

    @Test fun `the wording the operator reads`() {
        assertEquals(
            "The link dropped while settings were being sent. The radio still held them unsaved, so they were saved now and the radio restarts.",
            InterruptedWrite.SAVED,
        )
        assertEquals(
            "The link dropped while settings were being sent, and the radio does not hold them. That change was not saved.",
            InterruptedWrite.NOT_SAVED,
        )
        assertEquals(
            "The link dropped while settings were being sent. Check what the radio has, and push again if something is missing.",
            InterruptedWrite.CHECK,
        )
    }

    // endregion
}
