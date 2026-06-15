package soy.engindearing.omnitak.mobile.domain

/**
 * Pure backoff + dead-socket policy for application-level auto-reconnect
 * (issue #102). Holds no Android types and does no I/O so it is fully
 * JVM-unit-testable — the [ServerManager] reconnect supervisor that owns
 * the sockets delegates its timing + "should I re-dial?" decisions here.
 *
 * The escalation curve matches the proven [GybManager] BLE reconnect loop:
 * the first retry after a drop is immediate so a transient Wi-Fi↔LTE swap
 * recovers in well under the issue's ~30s target, then delays grow
 * geometrically and cap so a server that is genuinely down doesn't get
 * hammered (and the radio doesn't stay awake spinning).
 *
 *   drop → 0ms → [initialDelayMs] → ×2 → ×2 → … → capped at [maxDelayMs]
 *
 * A successful (re)connect calls [reset] so the next independent drop
 * starts the curve over from immediate.
 *
 * Instances are single-server and confined to one reconnect coroutine, so
 * the mutable [attempt] counter needs no synchronization.
 */
class ReconnectPolicy(
    private val initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
    private val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
) {
    // Number of delays handed out since the last reset. 0 → the upcoming
    // retry is the immediate one.
    private var attempt: Int = 0

    /**
     * The delay to wait before the next reconnect attempt, then advances
     * the curve. First call after a [reset] (or construction) returns 0
     * (retry now); subsequent calls return [initialDelayMs], then double
     * each time, capped at [maxDelayMs].
     */
    fun nextDelayMs(): Long {
        val delay = when (attempt) {
            0 -> 0L
            else -> {
                // attempt 1 → initial, 2 → initial×2, 3 → initial×4 …
                // Compute in Double to avoid Long overflow on a long-lived
                // loop, then clamp to the cap before converting back.
                val raw = initialDelayMs.toDouble() * (1L shl (attempt - 1))
                raw.coerceAtMost(maxDelayMs.toDouble()).toLong()
            }
        }
        attempt++
        return delay
    }

    /** Reset the curve after a successful connect — next drop retries now. */
    fun reset() {
        attempt = 0
    }

    companion object {
        const val DEFAULT_INITIAL_DELAY_MS = 2_000L
        const val DEFAULT_MAX_DELAY_MS = 30_000L

        /**
         * Dead-socket detection: should a server whose connection is in
         * [state] be (re)dialed? True only when the server is still
         * [serverEnabled] AND the socket is down ([ConnectionState.Disconnected]
         * or [ConnectionState.Failed]). A live [ConnectionState.Connected] or an
         * in-flight [ConnectionState.Connecting] is left alone so we never tear
         * down a healthy socket or stack duplicate dials.
         */
        fun shouldReconnect(state: ConnectionState, serverEnabled: Boolean): Boolean {
            if (!serverEnabled) return false
            return when (state) {
                is ConnectionState.Disconnected, is ConnectionState.Failed -> true
                is ConnectionState.Connected, is ConnectionState.Connecting -> false
            }
        }
    }
}
