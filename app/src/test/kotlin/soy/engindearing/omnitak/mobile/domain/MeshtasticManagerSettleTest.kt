package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.configCompleteFrame
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.configFrame
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.myInfoFrame
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.nodeInfoFrame
import soy.engindearing.omnitak.mobile.data.FakeRadio
import java.util.concurrent.CopyOnWriteArrayList

/**
 * When the twelve reads of [MeshtasticManager.requestDeviceConfig] go out: not into the middle of the radio's
 * config download. The firmware keeps few packets for the phone and drops the oldest when they pile up, without
 * telling anyone, so a read sent while the download streams can lose its answer. The reads wait for
 * `config_complete_id` and for the link to go quiet, and give up waiting when the link drops or after a
 * bound. The read-back after a push goes through the same call.
 *
 * Real time, kept short. Node numbers are made up.
 */
class MeshtasticManagerSettleTest {

    private val me = 0x0A0B0C0D
    private val other = 0x01020304

    private class Attached(val mgr: MeshtasticManager) {
        val sent = CopyOnWriteArrayList<ByteArray>()
    }

    /** A manager attached to a radio that has started its download (my_info, one config) and not finished it. */
    private fun attached(): Attached {
        val mgr = MeshtasticManager()
        val a = Attached(mgr)
        mgr.adminSendOverride = { frame -> a.sent += frame; true }
        mgr.settleQuietMs = 0
        mgr.dispatchFrame(myInfoFrame(me))
        mgr.dispatchFrame(configFrame(6, FakeRadio.factory().config.getValue(6)))
        return a
    }

    @Test fun `reads wait for the download to finish`() = runBlocking {
        val a = attached()
        val read = async { a.mgr.requestDeviceConfig() }

        delay(400)
        assertTrue("nothing goes out while the radio is still streaming its download", a.sent.isEmpty())

        a.mgr.dispatchFrame(configCompleteFrame())
        assertEquals(12, withTimeout(5_000) { read.await() })
        assertEquals(12, a.sent.size)
    }

    @Test fun `reads wait for the link to go quiet after the download`() = runBlocking {
        val a = attached()
        a.mgr.settleQuietMs = 1_000
        a.mgr.dispatchFrame(configCompleteFrame())
        val read = async { a.mgr.requestDeviceConfig() }

        // What the radio still had queued for the phone keeps arriving for a little while.
        repeat(6) {
            a.mgr.dispatchFrame(nodeInfoFrame(other, null))
            delay(100)
        }
        assertTrue("nothing goes out while frames are still arriving", a.sent.isEmpty())

        assertEquals(12, withTimeout(5_000) { read.await() })
    }

    @Test fun `a new download makes reads wait again`() = runBlocking {
        val a = attached()
        a.mgr.dispatchFrame(configCompleteFrame())
        a.mgr.dispatchFrame(myInfoFrame(me)) // the radio starts another download
        val read = async { a.mgr.requestDeviceConfig() }

        delay(300)
        assertTrue("the second download is not finished", a.sent.isEmpty())

        a.mgr.dispatchFrame(configCompleteFrame())
        assertEquals(12, withTimeout(5_000) { read.await() })
    }

    @Test fun `the wait ends when the link drops`() = runBlocking {
        val a = attached()
        val read = async { a.mgr.requestDeviceConfig() }
        delay(200)

        a.mgr.onLinkState(ConnectionState.Connected("radio", useTLS = false), wasConnected = false)
        a.mgr.onLinkState(ConnectionState.Disconnected, wasConnected = true)

        assertEquals("no radio, no reads", 0, withTimeout(2_000) { read.await() })
        assertTrue(a.sent.isEmpty())
    }

    @Test fun `with no radio attached a read does not wait`() = runBlocking {
        val mgr = MeshtasticManager()
        assertEquals(0, withTimeout(1_000) { mgr.requestDeviceConfig() })
    }

    @Test fun `a download that never finishes does not hold a read for ever`() = runBlocking {
        val a = attached()
        a.mgr.settleTimeoutMs = 300

        assertEquals("the reads go out anyway", 12, withTimeout(5_000) { a.mgr.requestDeviceConfig() })
    }
}
