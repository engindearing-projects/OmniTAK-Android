package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * Keeps one wanted connection dialed (#102, #233). Runs until cancelled.
 *
 * Each pass waits for the connection to settle, then either sleeps until it is
 * no longer up, or waits out the backoff and dials again.
 *
 * Why a loop and not `state.collect { ... }`: a StateFlow collector is only
 * handed a value that differs from the last one it was handed. A dial that
 * fails the same way twice goes Failed(reason) -> Connecting -> Failed(reason),
 * and when that happens faster than the collector comes back around (a phone
 * with no network fails a dial within a millisecond), the collector sees no
 * change and the retries stop until the app is opened. For the same reason a
 * `drop(1)` meant to skip the initial Disconnected could swallow a first dial
 * that had already failed. Reading the state afresh on every pass has neither
 * problem.
 *
 * [dial] must leave the state at Connecting before it returns (as
 * `TAKConnection.connect()` does), so the next pass waits for that attempt.
 * [stillWanted] is false once the server is switched off or its connection
 * object has been replaced.
 */
internal suspend fun superviseReconnect(
    state: StateFlow<ConnectionState>,
    policy: ReconnectPolicy,
    stillWanted: () -> Boolean,
    dial: () -> Unit,
    unwantedPollMs: Long = ReconnectPolicy.DEFAULT_MAX_DELAY_MS,
) {
    while (true) {
        // A dial in flight decides nothing: wait for it to come up or fail.
        when (state.first { it !is ConnectionState.Connecting }) {
            is ConnectionState.Connected -> {
                policy.reset()
                state.first { it !is ConnectionState.Connected }
            }
            else -> { // Disconnected or Failed
                if (!stillWanted()) {
                    // Switched off or replaced. Whoever did that normally cancels this
                    // loop; look again later in case they did not.
                    delay(unwantedPollMs)
                    continue
                }
                val wait = policy.nextDelayMs()
                if (wait > 0) delay(wait)
                // Look again after the wait: the operator may have switched the server
                // off, or a foreground resume may already have it dialing or up.
                if (ReconnectPolicy.shouldReconnect(state.value, stillWanted())) dial()
            }
        }
    }
}
