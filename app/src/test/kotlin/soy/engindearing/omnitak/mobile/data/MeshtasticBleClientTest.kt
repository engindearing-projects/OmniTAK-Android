package soy.engindearing.omnitak.mobile.data

import android.bluetooth.BluetoothDevice
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import soy.engindearing.omnitak.mobile.domain.ConnectionState

/**
 * JVM unit tests for the pure helpers on [MeshtasticBleClient]. The
 * BLE manager itself can't be JVM-tested (it spins up a real Android
 * GATT stack), so we cover the chunking helper that splits ToRadio
 * payloads at the configured BLE write boundary, plus the inverse
 * concat-on-receive path that mirrors how partial fromRadio reads get
 * stitched back into a single FromRadio protobuf payload before the
 * Phase 1 parser sees it.
 */
class MeshtasticBleClientTest {

    // region chunkPayload ----------------------------------------------------

    @Test fun chunk_empty_payload_yields_single_empty_chunk() {
        val out = MeshtasticBleClient.chunkPayload(ByteArray(0), 500)
        assertEquals(1, out.size)
        assertEquals(0, out[0].size)
    }

    @Test fun chunk_below_chunk_size_returns_input_unchanged() {
        val payload = ByteArray(100) { it.toByte() }
        val out = MeshtasticBleClient.chunkPayload(payload, 500)
        assertEquals(1, out.size)
        // Single-chunk path returns the original array (no copy
        // overhead) — fine for our caller, which immediately writes
        // the bytes off and never mutates.
        assertSame(payload, out[0])
    }

    @Test fun chunk_at_exact_boundary_produces_one_chunk() {
        val payload = ByteArray(500) { (it and 0xFF).toByte() }
        val out = MeshtasticBleClient.chunkPayload(payload, 500)
        assertEquals(1, out.size)
        assertArrayEquals(payload, out[0])
    }

    @Test fun chunk_just_over_boundary_produces_two_chunks() {
        val payload = ByteArray(501) { (it and 0xFF).toByte() }
        val out = MeshtasticBleClient.chunkPayload(payload, 500)
        assertEquals(2, out.size)
        assertEquals(500, out[0].size)
        assertEquals(1, out[1].size)
        assertEquals(500.toByte(), out[1][0]) // (500 & 0xFF) = 0xF4
    }

    @Test fun chunk_large_payload_produces_correct_number_and_concatenates() {
        val payload = ByteArray(1234) { (it and 0xFF).toByte() }
        val out = MeshtasticBleClient.chunkPayload(payload, 500)
        // 1234 / 500 = 2 r 234 → 3 chunks
        assertEquals(3, out.size)
        assertEquals(500, out[0].size)
        assertEquals(500, out[1].size)
        assertEquals(234, out[2].size)
        // Concatenation of all chunks must equal the original payload.
        val rebuilt = out.fold(ByteArray(0)) { acc, c -> acc + c }
        assertArrayEquals(payload, rebuilt)
    }

    @Test fun chunk_rejects_zero_chunk_size() {
        try {
            MeshtasticBleClient.chunkPayload(ByteArray(10), 0)
            fail("expected IllegalArgumentException for chunkSize=0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("chunkSize") == true)
        }
    }

    // endregion

    // region constants -------------------------------------------------------

    @Test fun service_uuid_matches_meshtastic_canonical() {
        assertEquals("6ba1b218-15a8-461f-9fa8-5dcae273eafd", MeshtasticBleClient.SERVICE_UUID.toString())
    }

    @Test fun characteristic_uuids_match_meshtastic_firmware() {
        assertEquals("f75c76d2-129e-4dad-a1dd-7866124401e7", MeshtasticBleClient.TO_RADIO_UUID.toString())
        assertEquals("2c55e69e-4993-11ed-b878-0242ac120002", MeshtasticBleClient.FROM_RADIO_UUID.toString())
        assertEquals("ed9da18c-a800-4f66-a670-aa7547de15e6", MeshtasticBleClient.FROM_NUM_UUID.toString())
    }

    @Test fun chunk_size_under_negotiated_mtu() {
        // We chunk at 500B but request an MTU of 512. Leaves headroom
        // for the 3B ATT write header that BLE transports tack on.
        assertTrue(
            "chunk size must fit in negotiated MTU minus ATT header",
            MeshtasticBleClient.CHUNK_SIZE_BYTES + 3 <= MeshtasticBleClient.REQUESTED_MTU,
        )
    }

    // endregion

    // region fromRadio frame stitching ------------------------------------
    // Conceptually a fromRadio read returns a complete FromRadio
    // protobuf payload — but in practice the underlying GATT layer can
    // hand back data in chunks if the payload exceeds the negotiated
    // MTU. The drain loop relies on each individual read being a
    // complete payload, so this test confirms the inverse helper of
    // chunkPayload (concatenation) is associative with respect to the
    // chunk boundary.

    @Test fun roundtrip_chunk_then_concat_preserves_payload() {
        val payload = ByteArray(1500) { (it and 0xFF).toByte() }
        for (size in listOf(20, 100, 250, 499, 500, 501, 1024)) {
            val chunks = MeshtasticBleClient.chunkPayload(payload, size)
            val rebuilt = chunks.fold(ByteArray(0)) { acc, c -> acc + c }
            assertArrayEquals("chunkSize=$size", payload, rebuilt)
        }
    }

    // endregion

    // region connect timeout state transition (#175) ----------------------
    // Regression for issue #175 — "Meshtastic connection hangs on Connecting
    // indefinitely". When a reconnect to a previously-connected radio leaves a
    // stale GATT session, Nordic's own .timeout()/.fail() callbacks can never
    // fire, so the client used to sit in Connecting forever (app restart was the
    // only fix). connectToAddress now wraps the connect in a watchdog; on timeout
    // it resolves to a *retryable Disconnected* state via this pure helper so the
    // UI stops spinning and the user can simply tap Connect again.

    @Test fun watchdog_timeout_resolves_to_retryable_disconnected() {
        // attemptResult == null models the watchdog firing because Nordic never
        // called back (the stale-GATT hang).
        val state = MeshtasticBleClient.resolveFailedConnectState("AA:BB:CC:DD:EE:FF", null)
        assertTrue(
            "a connect timeout must reset to Disconnected (retryable), not stay Connecting",
            state is ConnectionState.Disconnected,
        )
    }

    @Test fun hard_failure_resolves_to_failed_not_connecting() {
        // attemptResult == false models a hard failure reported by the stack.
        val state = MeshtasticBleClient.resolveFailedConnectState("AA:BB:CC:DD:EE:FF", false)
        assertTrue(
            "a hard failure must surface Failed (still recoverable), never remain Connecting",
            state is ConnectionState.Failed,
        )
    }

    @Test fun watchdog_timeout_is_above_nordic_connect_timeout() {
        // The outer watchdog must sit above Nordic's own connect timeout so the
        // library gets first chance to report a clean failure before we force a
        // reset — otherwise we'd cut off legitimate slow connects.
        assertTrue(
            "watchdog must exceed Nordic's connect timeout",
            MeshtasticBleClient.WATCHDOG_TIMEOUT_MS > 15_000L,
        )
    }

    @Test fun hard_failure_uses_recorded_failure_message_when_present() {
        // #203 — connectToAddress must not overwrite a reason-bearing Failed
        // state (set by the ConnectionObserver's onDeviceFailedToConnect) with
        // a generic reason-less placeholder.
        val recorded = MeshtasticBleClient.BleFailure(
            timestampMs = 1_000L,
            phase = MeshtasticBleClient.BleFailure.Phase.CONNECT,
            nordicReason = 133,
            gattStatus = null,
            bondState = BluetoothDevice.BOND_NONE,
            consecutiveFailures = 1,
            message = "connect failed: reason=133",
        )
        val state = MeshtasticBleClient.resolveFailedConnectState("AA:BB:CC:DD:EE:FF", false, recorded)
        assertTrue(state is ConnectionState.Failed)
        assertEquals("connect failed: reason=133", (state as ConnectionState.Failed).reason)
    }

    @Test fun hard_failure_falls_back_to_generic_message_without_recorded_failure() {
        // No BleFailure was recorded for this attempt (e.g. a code path that
        // never reached the ConnectionObserver) — the old generic message is
        // still the safety net, not a crash or a blank reason.
        val state = MeshtasticBleClient.resolveFailedConnectState("AA:BB:CC:DD:EE:FF", false, null)
        assertTrue(state is ConnectionState.Failed)
        assertEquals("connect failed for AA:BB:CC:DD:EE:FF", (state as ConnectionState.Failed).reason)
    }

    // endregion

    // region #203 — BLE failure diagnostics ---------------------------------
    // BleFailure.next() is the pure builder behind MeshtasticBleClient's
    // lastFailure StateFlow (populated from onDeviceDisconnected /
    // onDeviceFailedToConnect / isRequiredServiceSupported / readOnce, none of
    // which can run on a live BleManager in a JVM test), so these drive the
    // builder and its formatting helpers directly.

    @Test fun failure_next_with_no_previous_starts_streak_at_one() {
        // Also stands in for the onDeviceReady reset contract: lastFailure is
        // set back to null there, so the *next* failure recorded afterwards
        // must start counting from 1 again, not continue an old streak.
        val next = MeshtasticBleClient.BleFailure.next(
            previous = null,
            phase = MeshtasticBleClient.BleFailure.Phase.CONNECT,
            reason = 133,
            status = null,
            bondState = BluetoothDevice.BOND_NONE,
            nowMs = 1_000L,
            message = "connect failed: reason=133",
        )
        assertEquals(1, next.consecutiveFailures)
    }

    @Test fun failure_next_with_same_signature_increments_streak() {
        val first = MeshtasticBleClient.BleFailure.next(
            previous = null,
            phase = MeshtasticBleClient.BleFailure.Phase.LINK_LOSS,
            reason = 8,
            status = null,
            bondState = BluetoothDevice.BOND_NONE,
            nowMs = 1_000L,
            message = "link lost: reason=8",
        )
        val second = MeshtasticBleClient.BleFailure.next(
            previous = first,
            phase = MeshtasticBleClient.BleFailure.Phase.LINK_LOSS,
            reason = 8,
            status = null,
            bondState = BluetoothDevice.BOND_NONE,
            nowMs = 2_000L,
            message = "link lost: reason=8",
        )
        val third = MeshtasticBleClient.BleFailure.next(
            previous = second,
            phase = MeshtasticBleClient.BleFailure.Phase.LINK_LOSS,
            reason = 8,
            status = null,
            bondState = BluetoothDevice.BOND_NONE,
            nowMs = 3_000L,
            message = "link lost: reason=8",
        )
        assertEquals(1, first.consecutiveFailures)
        assertEquals(2, second.consecutiveFailures)
        assertEquals(3, third.consecutiveFailures)
    }

    @Test fun failure_next_with_different_phase_resets_streak() {
        val first = MeshtasticBleClient.BleFailure.next(
            previous = null,
            phase = MeshtasticBleClient.BleFailure.Phase.CONNECT,
            reason = 133,
            status = null,
            bondState = BluetoothDevice.BOND_NONE,
            nowMs = 1_000L,
            message = "connect failed: reason=133",
        )
        val second = MeshtasticBleClient.BleFailure.next(
            previous = first,
            phase = MeshtasticBleClient.BleFailure.Phase.LINK_LOSS,
            reason = 133, // same reason, different phase
            status = null,
            bondState = BluetoothDevice.BOND_NONE,
            nowMs = 2_000L,
            message = "link lost: reason=133",
        )
        assertEquals(1, second.consecutiveFailures)
    }

    @Test fun failure_next_with_different_reason_resets_streak() {
        val first = MeshtasticBleClient.BleFailure.next(
            previous = null,
            phase = MeshtasticBleClient.BleFailure.Phase.CONNECT,
            reason = 133,
            status = null,
            bondState = BluetoothDevice.BOND_NONE,
            nowMs = 1_000L,
            message = "connect failed: reason=133",
        )
        val second = MeshtasticBleClient.BleFailure.next(
            previous = first,
            phase = MeshtasticBleClient.BleFailure.Phase.CONNECT,
            reason = 8, // different reason, same phase
            status = null,
            bondState = BluetoothDevice.BOND_NONE,
            nowMs = 2_000L,
            message = "connect failed: reason=8",
        )
        assertEquals(1, second.consecutiveFailures)
    }

    @Test fun failure_next_with_different_status_resets_streak() {
        val first = MeshtasticBleClient.BleFailure.next(
            previous = null,
            phase = MeshtasticBleClient.BleFailure.Phase.READ_TIMEOUT,
            reason = -5,
            status = null,
            bondState = BluetoothDevice.BOND_NONE,
            nowMs = 1_000L,
            message = "fromRadio read timed out",
        )
        val second = MeshtasticBleClient.BleFailure.next(
            previous = first,
            phase = MeshtasticBleClient.BleFailure.Phase.READ_TIMEOUT,
            reason = -5,
            status = 8, // different status, same phase+reason
            bondState = BluetoothDevice.BOND_NONE,
            nowMs = 2_000L,
            message = "fromRadio read timed out",
        )
        assertEquals(1, second.consecutiveFailures)
    }

    @Test fun failure_relative_time_buckets() {
        // Large enough that subtracting up to 3 days' worth of ms never goes
        // negative (a negative timestampMs would hit the <= 0 "—" branch).
        val now = 10_000_000_000L
        assertEquals("—", MeshtasticBleClient.BleFailure.relativeTime(0L, now))
        assertEquals("just now", MeshtasticBleClient.BleFailure.relativeTime(now + 5_000L, now))
        assertEquals("30s ago", MeshtasticBleClient.BleFailure.relativeTime(now - 30_000L, now))
        assertEquals("5m ago", MeshtasticBleClient.BleFailure.relativeTime(now - 5 * 60_000L, now))
        assertEquals("2h ago", MeshtasticBleClient.BleFailure.relativeTime(now - 2 * 3_600_000L, now))
        assertEquals("3d ago", MeshtasticBleClient.BleFailure.relativeTime(now - 3 * 86_400_000L, now))
    }

    @Test fun failure_summary_line_matches_expected_format() {
        val f = MeshtasticBleClient.BleFailure(
            timestampMs = 1_000L,
            phase = MeshtasticBleClient.BleFailure.Phase.CONNECT,
            nordicReason = 133,
            gattStatus = null,
            bondState = BluetoothDevice.BOND_NONE,
            consecutiveFailures = 1,
            message = "connect failed: reason=133",
        )
        assertEquals(
            "Last failure: CONNECT reason=133 status=-, bond=NONE, 30s ago",
            f.summaryLine(nowMs = 31_000L),
        )
    }

    @Test fun failure_summary_line_reports_status_and_bonded_state() {
        val f = MeshtasticBleClient.BleFailure(
            timestampMs = 1_000L,
            phase = MeshtasticBleClient.BleFailure.Phase.READ_TIMEOUT,
            nordicReason = -5,
            gattStatus = 8,
            bondState = BluetoothDevice.BOND_BONDED,
            consecutiveFailures = 1,
            message = "fromRadio read timed out after 10000ms",
        )
        assertTrue(
            f.summaryLine(nowMs = 1_000L)
                .startsWith("Last failure: READ_TIMEOUT reason=-5 status=8, bond=BONDED, "),
        )
    }

    @Test fun failure_summary_line_reports_bonding_state() {
        val f = MeshtasticBleClient.BleFailure(
            timestampMs = 1_000L,
            phase = MeshtasticBleClient.BleFailure.Phase.SERVICE_DISCOVERY,
            nordicReason = 0,
            gattStatus = null,
            bondState = BluetoothDevice.BOND_BONDING,
            consecutiveFailures = 1,
            message = "required GATT service not found",
        )
        assertTrue(f.summaryLine(nowMs = 1_000L).contains("bond=BONDING"))
    }

    // endregion
}
