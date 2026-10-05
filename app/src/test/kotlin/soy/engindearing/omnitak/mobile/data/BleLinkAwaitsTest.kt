package soy.engindearing.omnitak.mobile.data

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #203 — a wait on a BLE request must end when the link ends.
 *
 * The Nordic library drops queued requests without calling their callbacks
 * when a link goes down. The auto-reconnect loop awaits the handshake write
 * inline after every connect, so one write dropped that way left the loop
 * suspended for good. [BleLinkAwaits] is what every such wait goes through
 * now; these pin the guarantee.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleLinkAwaitsTest {

    @Test fun await_returns_the_value_the_request_completed_with() = runTest {
        val awaits = BleLinkAwaits()
        val result = awaits.await(onClosed = false) { complete -> complete(true) }
        assertTrue(result)
        assertEquals(0, awaits.pendingCount)
    }

    @Test fun close_ends_a_wait_whose_callback_never_comes() = runTest {
        val awaits = BleLinkAwaits()
        // The request is "sent" and its callback is dropped, as Nordic does
        // with requests still queued when the link goes down.
        val write = async(start = CoroutineStart.UNDISPATCHED) {
            awaits.await(onClosed = false) { _ -> }
        }
        assertFalse("the wait should still be pending", write.isCompleted)
        assertEquals(1, awaits.pendingCount)

        awaits.close()

        assertFalse("a write on a closed link reports failure", write.await())
        assertEquals(0, awaits.pendingCount)
    }

    @Test fun close_ends_every_pending_wait() = runTest {
        val awaits = BleLinkAwaits()
        val waits = (1..5).map { n ->
            async(start = CoroutineStart.UNDISPATCHED) {
                awaits.await(onClosed = -n) { _ -> }
            }
        }
        assertEquals(5, awaits.pendingCount)

        awaits.close()

        assertEquals(listOf(-1, -2, -3, -4, -5), waits.map { it.await() })
    }

    @Test fun after_close_a_request_is_not_started() = runTest {
        val awaits = BleLinkAwaits()
        awaits.close()
        var started = false
        val result = awaits.await(onClosed = "closed") { _ -> started = true }
        assertEquals("closed", result)
        assertFalse("nothing may be sent on a link that is gone", started)
        assertTrue(awaits.isClosed)
    }

    @Test fun a_callback_that_arrives_after_close_is_ignored() = runTest {
        val awaits = BleLinkAwaits()
        var lateComplete: ((Int) -> Unit)? = null
        val read = async(start = CoroutineStart.UNDISPATCHED) {
            awaits.await(onClosed = -1) { complete -> lateComplete = complete }
        }
        awaits.close()
        // Nordic can still deliver the callback of the request that was in
        // flight. A second resume would crash the coroutine machinery.
        lateComplete!!.invoke(42)
        assertEquals(-1, read.await())
    }

    @Test fun only_the_first_completion_counts() = runTest {
        val awaits = BleLinkAwaits()
        val result = awaits.await(onClosed = 0) { complete ->
            complete(1)
            complete(2)
        }
        assertEquals(1, result)
    }

    @Test fun a_cancelled_wait_is_forgotten() = runTest {
        val awaits = BleLinkAwaits()
        var lateComplete: ((Boolean) -> Unit)? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            awaits.await(onClosed = false) { complete -> lateComplete = complete }
        }
        assertEquals(1, awaits.pendingCount)

        job.cancel()
        job.join()

        assertEquals(0, awaits.pendingCount)
        // Neither a late callback nor close() may touch the cancelled wait.
        lateComplete!!.invoke(true)
        awaits.close()
    }

    @Test fun a_request_that_throws_while_starting_leaves_nothing_pending() = runTest {
        val awaits = BleLinkAwaits()
        val thrown = runCatching {
            awaits.await(onClosed = false) { _ -> error("enqueue failed") }
        }.exceptionOrNull()
        assertEquals("enqueue failed", thrown?.message)
        assertEquals(0, awaits.pendingCount)
    }
}
