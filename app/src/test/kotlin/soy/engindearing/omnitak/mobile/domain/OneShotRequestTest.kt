package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A request from one screen to another ("open the Go to Coordinate sheet",
 * "enter toolbar edit mode") has to be acted on exactly once.
 *
 * What went wrong before this (#286): the Tools launcher bumped a counter, and
 * the map opened its sheet whenever it saw a counter above zero. The counter
 * lives as long as the process, the map lives only while its tab is showing, so
 * every new composition of the map saw the old count and opened the sheet
 * again, on every return to the Map tab, until the app was killed.
 *
 * These pin the rules that fix it: a request waits for a screen that is not
 * there yet, is gone once a screen has acted on it, and is acted on once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OneShotRequestTest {

    /**
     * One composition of the screen that acts on the request: it listens the
     * way the app's `OnRequest` does, and stops when it leaves the screen.
     */
    private class Screen(request: OneShotRequest, scope: CoroutineScope) {
        var acted = 0
            private set
        private val listening = scope.launch { request.handleRequests { acted++ } }

        /** The tab is left, or the Activity is rebuilt: this composition ends. */
        fun leave() = listening.cancel()
    }

    // MARK: the request itself

    @Test fun nothing_is_pending_until_somebody_asks() {
        val request = OneShotRequest()
        assertFalse(request.pending)
        assertFalse(request.consume())
    }

    @Test fun a_request_is_taken_once() {
        val request = OneShotRequest()
        request.request()
        assertTrue(request.pending)

        assertTrue("the first to take it gets it", request.consume())
        assertFalse("it is gone once taken", request.pending)
        assertFalse("nobody can take it twice", request.consume())
    }

    @Test fun asking_again_after_it_was_taken_is_a_new_request() {
        // Using the tool twice in a row opens it twice.
        val request = OneShotRequest()
        request.request()
        assertTrue(request.consume())

        request.request()
        assertTrue(request.consume())
        assertFalse(request.consume())
    }

    @Test fun asking_twice_before_it_is_taken_is_still_one_request() {
        // Two taps on the tool before the map has looked is one sheet, not two.
        val request = OneShotRequest()
        request.request()
        request.request()

        assertTrue(request.consume())
        assertFalse(request.consume())
    }

    @Test fun when_many_race_for_one_request_exactly_one_gets_it() {
        repeat(200) {
            val request = OneShotRequest()
            request.request()
            val winners = AtomicInteger()
            val start = CountDownLatch(1)
            val threads = List(8) {
                thread {
                    start.await()
                    if (request.consume()) winners.incrementAndGet()
                }
            }
            start.countDown()
            threads.forEach { it.join() }
            assertEquals(1, winners.get())
        }
    }

    // MARK: a screen acting on it

    @Test fun a_screen_acts_on_a_request_made_while_it_is_showing() = runTest {
        val request = OneShotRequest()
        val screen = Screen(request, backgroundScope)
        runCurrent()
        assertEquals("nothing was asked yet", 0, screen.acted)

        request.request()
        runCurrent()

        assertEquals(1, screen.acted)
        assertFalse(request.pending)
    }

    @Test fun a_screen_acts_every_time_the_tool_is_used() = runTest {
        val request = OneShotRequest()
        val screen = Screen(request, backgroundScope)
        runCurrent()

        repeat(3) {
            request.request()
            runCurrent()
        }

        assertEquals(3, screen.acted)
    }

    @Test fun a_request_made_before_the_screen_exists_waits_for_it() = runTest {
        // Tools asks and switches to the map in the same frame, so the map is
        // composed after the request was made. It must still act on it.
        val request = OneShotRequest()
        request.request()

        val screen = Screen(request, backgroundScope)
        runCurrent()

        assertEquals(1, screen.acted)
        assertFalse(request.pending)
    }

    @Test fun a_request_made_while_the_screen_is_away_waits_for_its_return() = runTest {
        val request = OneShotRequest()
        val first = Screen(request, backgroundScope)
        runCurrent()
        first.leave()
        runCurrent()

        request.request()
        runCurrent()
        assertEquals("the screen that left does not act", 0, first.acted)
        assertTrue("the request is still waiting", request.pending)

        val rebuilt = Screen(request, backgroundScope)
        runCurrent()
        assertEquals(1, rebuilt.acted)
        assertFalse(request.pending)
    }

    // MARK: the bug in #286

    @Test fun a_rebuilt_screen_does_not_replay_a_request_already_acted_on() = runTest {
        val request = OneShotRequest()
        val first = Screen(request, backgroundScope)
        runCurrent()
        request.request()
        runCurrent()
        assertEquals("the sheet opened once", 1, first.acted)

        // Go or Cancel closes the sheet. The operator opens Settings (the map
        // leaves composition) and comes back (a new composition of the map).
        first.leave()
        runCurrent()
        val rebuilt = Screen(request, backgroundScope)
        runCurrent()

        assertEquals("the sheet must not open again by itself", 0, rebuilt.acted)
        assertEquals(1, first.acted)
    }

    @Test fun a_screen_rebuilt_many_times_never_replays_it() = runTest {
        val request = OneShotRequest()
        var screen = Screen(request, backgroundScope)
        runCurrent()
        request.request()
        runCurrent()

        var replays = 0
        repeat(5) {
            screen.leave()
            runCurrent()
            screen = Screen(request, backgroundScope)
            runCurrent()
            replays += screen.acted
        }

        assertEquals(0, replays)
    }

    @Test fun a_rebuilt_screen_still_acts_on_the_next_new_request() = runTest {
        val request = OneShotRequest()
        val first = Screen(request, backgroundScope)
        runCurrent()
        request.request()
        runCurrent()
        first.leave()
        runCurrent()

        val rebuilt = Screen(request, backgroundScope)
        runCurrent()
        request.request()
        runCurrent()

        assertEquals(1, rebuilt.acted)
    }

    @Test fun when_two_screens_overlap_only_one_acts() = runTest {
        // The map being left and the map coming back can both be composed for
        // a moment while the tab animates. One request is one sheet.
        val request = OneShotRequest()
        val leaving = Screen(request, backgroundScope)
        val arriving = Screen(request, backgroundScope)
        runCurrent()

        request.request()
        runCurrent()

        assertEquals(1, leaving.acted + arriving.acted)
    }
}
