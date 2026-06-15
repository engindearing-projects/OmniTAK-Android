package soy.engindearing.omnitak.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure backoff / dead-socket policy that drives
 * application-level auto-reconnect (issue #102). No Android types here —
 * this is the JVM-testable core of the keepalive feature, separated from
 * the foreground-service + socket plumbing that needs a real device.
 *
 * Behaviour mirrors the proven GybManager BLE reconnect loop:
 *   - the first retry after a drop is immediate (0 ms),
 *   - subsequent retries escalate 2s → 4s → 8s → … capped at 30s,
 *   - a successful (re)connect resets the backoff to 0.
 */
class ReconnectPolicyTest {

    private fun policy() = ReconnectPolicy(
        initialDelayMs = 2_000L,
        maxDelayMs = 30_000L,
    )

    @Test
    fun first_retry_is_immediate() {
        val p = policy()
        assertEquals(
            "the first reconnect attempt after a drop should fire immediately",
            0L,
            p.nextDelayMs(),
        )
    }

    @Test
    fun delays_escalate_then_cap() {
        val p = policy()
        // 0 (immediate) → 2s → 4s → 8s → 16s → 30s (capped) → 30s …
        assertEquals(0L, p.nextDelayMs())
        assertEquals(2_000L, p.nextDelayMs())
        assertEquals(4_000L, p.nextDelayMs())
        assertEquals(8_000L, p.nextDelayMs())
        assertEquals(16_000L, p.nextDelayMs())
        assertEquals("backoff must cap at maxDelayMs", 30_000L, p.nextDelayMs())
        assertEquals("backoff stays at the cap", 30_000L, p.nextDelayMs())
    }

    @Test
    fun reset_returns_to_immediate() {
        val p = policy()
        p.nextDelayMs() // 0
        p.nextDelayMs() // 2s
        p.nextDelayMs() // 4s
        p.reset()
        assertEquals(
            "after a successful connect the next drop should retry immediately again",
            0L,
            p.nextDelayMs(),
        )
    }

    @Test
    fun custom_bounds_are_honored() {
        val p = ReconnectPolicy(initialDelayMs = 1_000L, maxDelayMs = 5_000L)
        assertEquals(0L, p.nextDelayMs())
        assertEquals(1_000L, p.nextDelayMs())
        assertEquals(2_000L, p.nextDelayMs())
        assertEquals(4_000L, p.nextDelayMs())
        assertEquals("capped at the custom max", 5_000L, p.nextDelayMs())
        assertEquals(5_000L, p.nextDelayMs())
    }

    // --- dead-socket detection: which states warrant a reconnect ---

    @Test
    fun dropped_states_want_reconnect_when_enabled() {
        assertTrue(
            "a server that was connected and is now Disconnected should be re-dialed",
            ReconnectPolicy.shouldReconnect(
                state = ConnectionState.Disconnected,
                serverEnabled = true,
            ),
        )
        assertTrue(
            "a Failed read loop should be re-dialed",
            ReconnectPolicy.shouldReconnect(
                state = ConnectionState.Failed("read timeout"),
                serverEnabled = true,
            ),
        )
    }

    @Test
    fun live_or_inflight_states_do_not_reconnect() {
        assertFalse(
            "an already-connected socket must not be torn down + re-dialed",
            ReconnectPolicy.shouldReconnect(
                state = ConnectionState.Connected("srv", useTLS = true),
                serverEnabled = true,
            ),
        )
        assertFalse(
            "a connect already in flight must not be duplicated",
            ReconnectPolicy.shouldReconnect(
                state = ConnectionState.Connecting("srv"),
                serverEnabled = true,
            ),
        )
    }

    @Test
    fun disabled_server_never_reconnects() {
        assertFalse(
            "a server the operator disabled must stay down even if the socket dropped",
            ReconnectPolicy.shouldReconnect(
                state = ConnectionState.Disconnected,
                serverEnabled = false,
            ),
        )
        assertFalse(
            ReconnectPolicy.shouldReconnect(
                state = ConnectionState.Failed("boom"),
                serverEnabled = false,
            ),
        )
    }
}
