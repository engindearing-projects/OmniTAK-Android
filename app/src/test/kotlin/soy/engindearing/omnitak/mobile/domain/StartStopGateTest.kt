package soy.engindearing.omnitak.mobile.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #229 — a stop must never reach the service between startForegroundService()
 * and its startForeground() call. [StartStopGate] parks it instead.
 */
class StartStopGateTest {

    @Test
    fun stop_with_nothing_in_flight_goes_straight_through() {
        val gate = StartStopGate()
        assertTrue(gate.stopRequested(now = 0))
    }

    @Test
    fun stop_behind_an_undelivered_start_is_parked_and_runs_on_delivery() {
        val gate = StartStopGate()
        gate.startIssued(now = 1_000)

        assertFalse("the server dropped us before onStartCommand ran", gate.stopRequested(now = 1_100))
        assertTrue("onStartCommand carries the parked stop out", gate.startDelivered())
        assertTrue("and later stops are ordinary again", gate.stopRequested(now = 2_000))
    }

    @Test
    fun a_delivered_start_with_no_parked_stop_keeps_the_service() {
        val gate = StartStopGate()
        gate.startIssued(now = 0)
        assertFalse(gate.startDelivered())
    }

    @Test
    fun a_newer_start_cancels_a_parked_stop() {
        val gate = StartStopGate()
        gate.startIssued(now = 0)
        assertFalse(gate.stopRequested(now = 10))
        gate.startIssued(now = 20) // the link came back before the first start was delivered

        assertFalse("first delivery: another start is still in flight", gate.startDelivered())
        assertFalse("second delivery: the stop was cancelled by the newer start", gate.startDelivered())
    }

    @Test
    fun with_two_starts_in_flight_a_parked_stop_waits_for_the_last_delivery() {
        val gate = StartStopGate()
        gate.startIssued(now = 0)
        gate.startIssued(now = 5)
        assertFalse(gate.stopRequested(now = 10))

        assertFalse("one start is still undelivered", gate.startDelivered())
        assertTrue(gate.startDelivered())
    }

    @Test
    fun a_refused_start_does_not_hold_stops_back() {
        val gate = StartStopGate()
        gate.startIssued(now = 0)
        gate.startRefused() // startForegroundService() threw: nothing will be delivered
        assertTrue(gate.stopRequested(now = 10))
    }

    @Test
    fun a_start_that_is_never_delivered_stops_blocking_after_the_window() {
        val gate = StartStopGate(deliveryWindowMs = 10_000)
        gate.startIssued(now = 0)

        assertFalse(gate.stopRequested(now = 9_999))
        assertTrue(gate.stopRequested(now = 10_000))
        assertFalse("the stale start no longer counts", gate.startDelivered())
    }

    @Test
    fun a_system_restart_delivery_with_nothing_issued_is_harmless() {
        val gate = StartStopGate()
        assertFalse(gate.startDelivered()) // START_STICKY restart: no start() preceded it
        assertTrue(gate.stopRequested(now = 0))
    }
}
