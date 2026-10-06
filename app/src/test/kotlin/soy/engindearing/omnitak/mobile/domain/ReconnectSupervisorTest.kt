package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #233: the reconnect supervisor must keep dialing a wanted server that is
 * down, including when every dial fails the same way and at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectSupervisorTest {

    /**
     * A connection whose dial has already settled by the time `dial()` returns.
     * That is what a phone with no network does (the connect fails within a
     * millisecond), and it is the worst case for code that waits to be told the
     * state changed: Failed(reason) -> Connecting -> Failed(reason) ends on the
     * value it started from.
     */
    private class InstantConn(initial: ConnectionState, var outcome: ConnectionState) {
        val state = MutableStateFlow(initial)
        var dials = 0
        fun dial() {
            dials++
            state.value = ConnectionState.Connecting("server")
            state.value = outcome
        }
    }

    private val unreachable = ConnectionState.Failed("Network is unreachable")
    private val up = ConnectionState.Connected("server", useTLS = false)

    @Test
    fun aDialThatFailsTheSameWayEveryTimeKeepsBeingRetried() = runTest {
        // The first dial failed before the supervisor started looking.
        val conn = InstantConn(initial = unreachable, outcome = unreachable)
        backgroundScope.launch {
            superviseReconnect(conn.state, ReconnectPolicy(), stillWanted = { true }, dial = conn::dial)
        }
        runCurrent()
        assertEquals("the first retry is immediate", 1, conn.dials)

        // 2 s, 4 s, 8 s, 16 s, then capped at 30 s.
        var expected = 1
        for (waitMs in listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L)) {
            advanceTimeBy(waitMs - 2)
            runCurrent()
            assertEquals("no dial before the backoff of $waitMs ms is over", expected, conn.dials)
            advanceTimeBy(2)
            runCurrent()
            expected++
            assertEquals("a dial after $waitMs ms", expected, conn.dials)
        }
    }

    @Test
    fun aDroppedConnectionIsDialedAtOnceAndComingUpResetsTheBackoff() = runTest {
        val conn = InstantConn(initial = up, outcome = unreachable)
        backgroundScope.launch {
            superviseReconnect(conn.state, ReconnectPolicy(), stillWanted = { true }, dial = conn::dial)
        }
        runCurrent()
        assertEquals("an up connection is left alone", 0, conn.dials)

        conn.state.value = ConnectionState.Disconnected
        runCurrent()
        assertEquals("the first retry after a drop is immediate", 1, conn.dials)

        // The next attempt, after the first backoff step, succeeds and holds.
        conn.outcome = up
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(2, conn.dials)
        assertEquals(up, conn.state.value)
        advanceTimeBy(STABLE_AFTER_MS + 1)
        runCurrent()

        // It drops again: having held, it reset the backoff, so this retry is immediate too.
        conn.outcome = unreachable
        conn.state.value = ConnectionState.Failed("No response from server")
        runCurrent()
        assertEquals(3, conn.dials)
    }

    @Test
    fun aServerThatDropsEveryConnectionAtOnceIsNotDialedInATightLoop() = runTest {
        // Every dial comes up, and the server closes it a second later.
        val conn = InstantConn(initial = ConnectionState.Disconnected, outcome = up)
        backgroundScope.launch {
            conn.state.collect {
                if (it is ConnectionState.Connected) {
                    delay(1_000)
                    conn.state.value = ConnectionState.Disconnected
                }
            }
        }
        backgroundScope.launch {
            superviseReconnect(conn.state, ReconnectPolicy(), stillWanted = { true }, dial = conn::dial)
        }
        advanceTimeBy(5 * 60_000L)
        runCurrent()
        // 0, 2, 4, 8, 16 s, then every 30 s, each plus the second the connection lasted:
        // about fourteen in five minutes. Re-dialing at once every time would be about 300.
        assertTrue("dials in five minutes: ${conn.dials}", conn.dials in 10..16)
    }

    @Test
    fun aServerThatIsSwitchedOffIsNotDialed() = runTest {
        val conn = InstantConn(initial = unreachable, outcome = unreachable)
        var wanted = false
        backgroundScope.launch {
            superviseReconnect(conn.state, ReconnectPolicy(), stillWanted = { wanted }, dial = conn::dial)
        }
        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertEquals(0, conn.dials)

        // Switched back on without the loop being restarted: it picks the server up again.
        wanted = true
        advanceTimeBy(ReconnectPolicy.DEFAULT_MAX_DELAY_MS + 1)
        runCurrent()
        assertEquals(1, conn.dials)
    }

    @Test
    fun aDialInFlightIsLeftAlone() = runTest {
        val conn = InstantConn(initial = ConnectionState.Connecting("server"), outcome = unreachable)
        backgroundScope.launch {
            superviseReconnect(conn.state, ReconnectPolicy(), stillWanted = { true }, dial = conn::dial)
        }
        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertEquals(0, conn.dials)

        // That dial fails: now it is the supervisor's turn.
        conn.state.value = unreachable
        runCurrent()
        assertEquals(1, conn.dials)
    }

    @Test
    fun aConnectionSomeoneElseBroughtBackDuringTheBackoffIsNotDialedAgain() = runTest {
        val conn = InstantConn(initial = unreachable, outcome = unreachable)
        backgroundScope.launch {
            superviseReconnect(conn.state, ReconnectPolicy(), stillWanted = { true }, dial = conn::dial)
        }
        runCurrent()
        assertEquals(1, conn.dials) // immediate retry failed; now waiting 2 s

        // The app comes to the foreground and dials by itself; that dial succeeds.
        conn.state.value = ConnectionState.Connecting("server")
        conn.state.value = up
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals("the backoff ended on a live connection: no dial", 1, conn.dials)
    }
}
