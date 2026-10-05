package soy.engindearing.omnitak.mobile.data

import android.bluetooth.BluetoothDevice
import org.junit.Assert.assertArrayEquals
import no.nordicsemi.android.ble.callback.FailCallback
import no.nordicsemi.android.ble.observer.ConnectionObserver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

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
        // #203 — this asserted …aa7547de15e6 before, the same typo the client
        // had; no radio has a characteristic by that id, so fromNum
        // notifications were never enabled. The value below is FROMNUM_UUID
        // in the Meshtastic Python client (meshtastic/ble_interface.py).
        assertEquals("ed9da18c-a800-4f66-a670-aa7547e34453", MeshtasticBleClient.FROM_NUM_UUID.toString())
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

    // region #208 — BLE link display name -------------------------------
    // resolveDisplayName() picks what the Link state shows for a Meshtastic
    // BLE radio: the node's real long name (once NodeInfo for our own node
    // arrives) beats the name advertised at scan time, which beats a bare
    // MAC shown only when nothing else is known yet.

    @Test fun display_name_advertised_name_wins_over_mac() {
        val name = MeshtasticBleClient.resolveDisplayName(
            advertisedName = "Meshtastic_ab12",
            longName = null,
            address = "AA:BB:CC:DD:EE:FF",
        )
        assertEquals("Meshtastic_ab12", name)
    }

    @Test fun display_name_long_name_wins_over_advertised_name() {
        val name = MeshtasticBleClient.resolveDisplayName(
            advertisedName = "Meshtastic_ab12",
            longName = "Basecamp Radio",
            address = "AA:BB:CC:DD:EE:FF",
        )
        assertEquals("Basecamp Radio", name)
    }

    @Test fun display_name_falls_back_to_mac_when_nothing_known() {
        val name = MeshtasticBleClient.resolveDisplayName(
            advertisedName = null,
            longName = null,
            address = "AA:BB:CC:DD:EE:FF",
        )
        assertEquals("AA:BB:CC:DD:EE:FF", name)
    }

    @Test fun display_name_ignores_blank_long_name_and_falls_back_to_advertised() {
        val name = MeshtasticBleClient.resolveDisplayName(
            advertisedName = "Meshtastic_ab12",
            longName = "   ",
            address = "AA:BB:CC:DD:EE:FF",
        )
        assertEquals("Meshtastic_ab12", name)
    }

    @Test fun display_name_ignores_blank_advertised_name_and_falls_back_to_mac() {
        val name = MeshtasticBleClient.resolveDisplayName(
            advertisedName = "",
            longName = null,
            address = "AA:BB:CC:DD:EE:FF",
        )
        assertEquals("AA:BB:CC:DD:EE:FF", name)
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

    // region connect deadline (#175, #203) ---------------------------------
    // #175 — "Meshtastic connection hangs on Connecting indefinitely": a
    // connect attempt that never hears back must run out of time.
    // #203 — but not while Android is pairing with the radio, and not in the
    // moment right after a pairing either.

    private fun deadline(idleLimitMs: Long = MeshtasticBleClient.CONNECT_TIMEOUT_MS) =
        MeshtasticBleClient.ProgressDeadline(
            startedAtMs = 0L,
            idleLimitMs = idleLimitMs,
            pairingLimitMs = MeshtasticBleClient.PAIRING_TIMEOUT_MS,
        )

    @Test fun attempt_runs_out_of_time_when_nothing_happens() {
        val d = deadline()
        assertFalse(d.expired(MeshtasticBleClient.CONNECT_TIMEOUT_MS - 1, pairing = false))
        assertTrue(
            "an attempt with no answer must end, not sit on Connecting",
            d.expired(MeshtasticBleClient.CONNECT_TIMEOUT_MS, pairing = false),
        )
    }

    @Test fun attempt_is_not_cut_off_while_android_is_pairing() {
        // The attempt used to be torn down 15 s in, mid PIN entry, which also
        // dismissed the pairing request.
        val d = deadline()
        assertFalse(d.expired(3_000, pairing = true))
        assertFalse(d.expired(15_000, pairing = true))
        assertFalse(d.expired(40_000, pairing = true))
    }

    @Test fun attempt_gets_its_full_time_again_after_a_pairing() {
        // PIN accepted 20 s in. Service discovery, the cache refresh, MTU and
        // the notification subscribe all still have to happen: ending the
        // attempt on the next tick ("pairing over, and we are past 15 s")
        // would throw away a pairing that just succeeded.
        val d = deadline()
        assertFalse(d.expired(3_000, pairing = true))
        assertFalse(d.expired(20_000, pairing = true))
        assertFalse(d.expired(21_000, pairing = false))
        assertFalse(d.expired(20_000 + MeshtasticBleClient.CONNECT_TIMEOUT_MS - 1, pairing = false))
        assertTrue(d.expired(20_000 + MeshtasticBleClient.CONNECT_TIMEOUT_MS, pairing = false))
    }

    @Test fun one_pairing_cannot_run_forever() {
        val d = deadline()
        assertFalse(d.expired(2_000, pairing = true))
        assertFalse(d.expired(2_000 + MeshtasticBleClient.PAIRING_TIMEOUT_MS - 1, pairing = true))
        assertTrue(d.expired(2_000 + MeshtasticBleClient.PAIRING_TIMEOUT_MS, pairing = true))
    }

    @Test fun a_second_pairing_gets_its_own_limit() {
        val d = deadline()
        assertFalse(d.expired(1_000, pairing = true))
        assertFalse(d.expired(50_000, pairing = true))
        assertFalse(d.expired(51_000, pairing = false)) // first pairing over
        assertFalse(d.expired(55_000, pairing = true)) // asked again
        assertFalse(d.expired(55_000 + MeshtasticBleClient.PAIRING_TIMEOUT_MS - 1, pairing = true))
        assertTrue(d.expired(55_000 + MeshtasticBleClient.PAIRING_TIMEOUT_MS, pairing = true))
    }

    @Test fun write_deadline_uses_the_same_rules_with_its_own_limit() {
        val d = deadline(idleLimitMs = MeshtasticBleClient.WRITE_TIMEOUT_MS)
        assertFalse(d.expired(MeshtasticBleClient.WRITE_TIMEOUT_MS - 1, pairing = false))
        assertTrue(d.expired(MeshtasticBleClient.WRITE_TIMEOUT_MS, pairing = false))
    }

    @Test fun nordic_backstop_sits_above_a_pairing_and_the_setup_after_it() {
        // Our own deadline decides when an attempt is over. If Nordic's timeout
        // fired first it would end a pairing that is still within its window,
        // or the setup that follows it.
        assertTrue(
            MeshtasticBleClient.NORDIC_BACKSTOP_TIMEOUT_MS >
                MeshtasticBleClient.PAIRING_TIMEOUT_MS + MeshtasticBleClient.CONNECT_TIMEOUT_MS,
        )
    }

    @Test fun a_read_is_given_up_only_after_nordic_had_its_chance_to_report_it() {
        // READ_AWAIT_MS ends a session whose read got no answer of any kind.
        // It must sit above Nordic's own 10 s read timeout, or a read that is
        // merely slow would take the session down with it.
        assertTrue(MeshtasticBleClient.READ_AWAIT_MS > 10_000L)
    }

    // endregion

    // region #203 — when a session or an attempt is given another go --------

    @Test fun reads_that_keep_failing_end_the_session() {
        val max = MeshtasticBleClient.MAX_READ_FAILURES
        assertFalse(MeshtasticBleClient.shouldDropAfterReadFailures(max - 1, bonding = false))
        assertTrue(MeshtasticBleClient.shouldDropAfterReadFailures(max, bonding = false))
    }

    @Test fun failing_reads_do_not_end_a_session_that_is_pairing() {
        // Reads fail until the pairing request is answered; that is the first
        // connect to every radio that wants a PIN.
        assertFalse(
            MeshtasticBleClient.shouldDropAfterReadFailures(MeshtasticBleClient.MAX_READ_FAILURES + 10, bonding = true),
        )
    }

    @Test fun a_stack_error_before_the_link_is_up_is_retried_at_once() {
        assertTrue(MeshtasticBleClient.isQuickRetryable(facts(failStatus = 133)))
        assertTrue(MeshtasticBleClient.isQuickRetryable(facts(failStatus = 147)))
    }

    @Test fun other_failures_wait_for_the_reconnect_loop() {
        // No answer at all: trying again at once changes nothing.
        assertFalse(MeshtasticBleClient.isQuickRetryable(facts(timedOut = true)))
        // The link was up: whatever went wrong is not the stack's connect.
        assertFalse(MeshtasticBleClient.isQuickRetryable(facts(failStatus = 133, linkCameUp = true)))
        // Nordic's own reason codes (negative) are not stack errors.
        assertFalse(MeshtasticBleClient.isQuickRetryable(facts(failStatus = FailCallback.REASON_DEVICE_DISCONNECTED)))
        assertFalse(MeshtasticBleClient.isQuickRetryable(facts(failStatus = FailCallback.REASON_BLUETOOTH_DISABLED)))
        // Not a Meshtastic radio.
        assertFalse(
            MeshtasticBleClient.isQuickRetryable(
                facts(failStatus = FailCallback.REASON_DEVICE_NOT_SUPPORTED, linkCameUp = true, serviceMissing = true),
            ),
        )
    }

    // endregion

    // region #203 — what reaches the log -------------------------------------
    // Nordic prints every value it reads, writes and is notified of. On this
    // link those are FromRadio and ToRadio frames: channel keys, positions,
    // messages. The log goes to Logcat and into "Copy diagnostics", which
    // people are asked to paste into bug reports, so only sizes are kept.

    @Test fun a_read_value_is_reduced_to_its_size() {
        assertEquals(
            "Read Response received from 2c55e69e-4993-11ed-b878-0242ac120002, value: 3 bytes",
            MeshtasticBleClient.redactPayload(
                "Read Response received from 2c55e69e-4993-11ed-b878-0242ac120002, value: (0x) 1A-2B-08",
            ),
        )
    }

    @Test fun a_notification_value_is_reduced_to_its_size() {
        assertEquals(
            "Notification received from ed9da18c-a800-4f66-a670-aa7547e34453, value: 4 bytes",
            MeshtasticBleClient.redactPayload(
                "Notification received from ed9da18c-a800-4f66-a670-aa7547e34453, value: (0x) 00-00-00-00",
            ),
        )
    }

    @Test fun a_written_value_is_reduced_to_its_size() {
        assertEquals(
            "gatt.writeCharacteristic(f75c76d2-129e-4dad-a1dd-7866124401e7, value=6 bytes, WRITE REQUEST)",
            MeshtasticBleClient.redactPayload(
                "gatt.writeCharacteristic(f75c76d2-129e-4dad-a1dd-7866124401e7, value=0x18EA9A8EE603, WRITE REQUEST)",
            ),
        )
        assertEquals(
            "gatt.writeDescriptor(00002902-0000-1000-8000-00805f9b34fb, value=2 bytes)",
            MeshtasticBleClient.redactPayload("gatt.writeDescriptor(00002902-0000-1000-8000-00805f9b34fb, value=0x01-00)"),
        )
        // The form Nordic uses before Android 13.
        assertEquals(
            "characteristic.setValue(4 bytes)",
            MeshtasticBleClient.redactPayload("characteristic.setValue(0x0A6315FF)"),
        )
    }

    @Test fun no_hex_from_a_frame_survives_redaction() {
        val frame = "0A-63-15-FF-FF-FF-FF-22-55-08-48-12-51"
        val line = MeshtasticBleClient.redactPayload("Read Response received from 2c55e69e, value: (0x) $frame")
        assertFalse(line.contains("0A-63"))
        assertFalse(line.contains("FF-FF"))
        assertTrue(line.endsWith("value: 13 bytes"))
    }

    @Test fun lines_without_a_value_are_left_as_they_are() {
        for (line in listOf(
            "Connected to AA:BB:CC:DD:EE:FF",
            "Error (0x85): GATT ERROR",
            "Error: (0x93): UNKNOWN (147)",
            "Read Response received from 2c55e69e-4993-11ed-b878-0242ac120002, value: ",
            "Data written to f75c76d2-129e-4dad-a1dd-7866124401e7",
            "gatt.requestMtu(512)",
            "Connection parameters updated (interval: 45.0ms, latency: 0, timeout: 5000ms)",
        )) {
            assertEquals(line, MeshtasticBleClient.redactPayload(line))
        }
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

    // region #203 — what a failed connect attempt gets recorded as ----------

    private fun facts(
        timedOut: Boolean = false,
        failStatus: Int? = null,
        observerReason: Int? = null,
        linkCameUp: Boolean = false,
        pairingSeen: Boolean = false,
        bondState: Int = BluetoothDevice.BOND_NONE,
        serviceMissing: Boolean = false,
        cacheRefreshed: Boolean = false,
        services: List<String> = emptyList(),
    ) = MeshtasticBleClient.ConnectAttemptFacts(
        timedOut = timedOut,
        failStatus = failStatus,
        observerReason = observerReason,
        linkCameUp = linkCameUp,
        pairingSeen = pairingSeen,
        bondState = bondState,
        serviceMissing = serviceMissing,
        cacheRefreshed = cacheRefreshed,
        services = services,
    )

    @Test fun timeout_without_a_link_points_at_the_radio() {
        val d = MeshtasticBleClient.describeConnectFailure(facts(timedOut = true))
        assertEquals(MeshtasticBleClient.BleFailure.Phase.CONNECT, d.phase)
        assertEquals(ConnectionObserver.REASON_TIMEOUT, d.reason)
        assertEquals("no answer from the radio", d.message)
        assertEquals(MeshtasticBleClient.HINT_NO_ANSWER, d.hint)
    }

    @Test fun timeout_while_pairing_asks_for_the_pin() {
        val d = MeshtasticBleClient.describeConnectFailure(
            facts(timedOut = true, linkCameUp = true, bondState = BluetoothDevice.BOND_BONDING),
        )
        assertEquals("pairing did not finish", d.message)
        assertEquals(MeshtasticBleClient.HINT_PAIRING, d.hint)
    }

    @Test fun timeout_after_pairing_was_seen_still_asks_for_the_pin() {
        // Android's own pairing timeout can put the bond back to NONE before
        // our deadline; the attempt still failed on pairing.
        val d = MeshtasticBleClient.describeConnectFailure(
            facts(timedOut = true, linkCameUp = true, pairingSeen = true, bondState = BluetoothDevice.BOND_NONE),
        )
        assertEquals("pairing did not finish", d.message)
        assertEquals(MeshtasticBleClient.HINT_PAIRING, d.hint)
    }

    @Test fun timeout_on_a_bonded_radio_whose_link_came_up_suggests_pairing_again() {
        // The radio lost its side of the pairing (reflash, factory reset): the
        // link comes up, encryption never does, setup stalls.
        val d = MeshtasticBleClient.describeConnectFailure(
            facts(timedOut = true, linkCameUp = true, bondState = BluetoothDevice.BOND_BONDED),
        )
        assertEquals("connected, but setup did not finish", d.message)
        assertEquals(MeshtasticBleClient.HINT_REPAIR, d.hint)
    }

    @Test fun missing_service_after_a_cache_refresh_is_a_wrong_device() {
        val services = listOf("00001800-0000-1000-8000-00805f9b34fb", "6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val d = MeshtasticBleClient.describeConnectFailure(
            facts(
                failStatus = FailCallback.REASON_DEVICE_NOT_SUPPORTED,
                observerReason = ConnectionObserver.REASON_NOT_SUPPORTED,
                linkCameUp = true,
                bondState = BluetoothDevice.BOND_BONDED,
                serviceMissing = true,
                cacheRefreshed = true,
                services = services,
            ),
        )
        assertEquals(MeshtasticBleClient.BleFailure.Phase.SERVICE_DISCOVERY, d.phase)
        assertEquals(ConnectionObserver.REASON_NOT_SUPPORTED, d.reason)
        assertEquals(services, d.services)
        assertEquals(MeshtasticBleClient.HINT_NOT_MESHTASTIC, d.hint)
    }

    @Test fun missing_service_without_a_cache_refresh_blames_the_cached_list() {
        // The refresh could not run, so the list may be Android's old copy.
        val d = MeshtasticBleClient.describeConnectFailure(
            facts(linkCameUp = true, serviceMissing = true, cacheRefreshed = false, services = listOf("1800")),
        )
        assertEquals(MeshtasticBleClient.BleFailure.Phase.SERVICE_DISCOVERY, d.phase)
        assertEquals(MeshtasticBleClient.HINT_STALE_SERVICES, d.hint)
    }

    @Test fun nordic_failure_keeps_the_gatt_status() {
        val d = MeshtasticBleClient.describeConnectFailure(
            facts(failStatus = 133, observerReason = ConnectionObserver.REASON_UNKNOWN),
        )
        assertEquals(MeshtasticBleClient.BleFailure.Phase.CONNECT, d.phase)
        assertEquals(ConnectionObserver.REASON_UNKNOWN, d.reason)
        assertEquals(133, d.status)
        assertEquals("connect failed: reason=-1, status=133", d.message)
        assertNull(d.hint)
    }

    @Test fun nordic_failure_with_its_own_reason_code_has_no_gatt_status() {
        val d = MeshtasticBleClient.describeConnectFailure(
            facts(failStatus = FailCallback.REASON_DEVICE_DISCONNECTED, linkCameUp = true),
        )
        assertEquals(FailCallback.REASON_DEVICE_DISCONNECTED, d.reason)
        assertNull(d.status)
        assertEquals("connect failed: reason=-1", d.message)
    }

    @Test fun bluetooth_off_says_so() {
        val d = MeshtasticBleClient.describeConnectFailure(
            facts(failStatus = FailCallback.REASON_BLUETOOTH_DISABLED),
        )
        assertEquals(MeshtasticBleClient.HINT_BLUETOOTH_OFF, d.hint)
    }

    @Test fun radio_hanging_up_during_pairing_asks_for_the_pin() {
        // A wrong PIN: the radio drops the link, Nordic reports a disconnect.
        val d = MeshtasticBleClient.describeConnectFailure(
            facts(failStatus = FailCallback.REASON_DEVICE_DISCONNECTED, linkCameUp = true, pairingSeen = true),
        )
        assertEquals(MeshtasticBleClient.HINT_PAIRING, d.hint)
    }

    @Test fun a_read_that_times_out_while_android_is_pairing_points_at_the_pairing_request() {
        // First connect to a radio that wants a PIN: the session is up, the
        // first read triggers pairing, and Android raises the request as a
        // notification. Nothing arrives until someone answers it.
        assertEquals(
            MeshtasticBleClient.HINT_PAIRING,
            MeshtasticBleClient.sessionFailureHint(BluetoothDevice.BOND_BONDING, pairingSeen = false),
        )
    }

    @Test fun a_link_that_drops_after_a_failed_pairing_points_at_the_pairing_request() {
        // Pairing left unanswered: Android gives up, the bond is back to NONE
        // and the radio drops the link.
        assertEquals(
            MeshtasticBleClient.HINT_PAIRING,
            MeshtasticBleClient.sessionFailureHint(BluetoothDevice.BOND_NONE, pairingSeen = true),
        )
    }

    @Test fun an_ordinary_link_loss_on_a_paired_radio_has_no_pairing_hint() {
        assertNull(MeshtasticBleClient.sessionFailureHint(BluetoothDevice.BOND_BONDED, pairingSeen = false))
        // Paired during this session and dropped later for another reason.
        assertNull(MeshtasticBleClient.sessionFailureHint(BluetoothDevice.BOND_BONDED, pairingSeen = true))
        assertNull(MeshtasticBleClient.sessionFailureHint(BluetoothDevice.BOND_NONE, pairingSeen = false))
    }

    // endregion

    // region #203 — failure log ---------------------------------------------

    private fun MeshtasticBleClient.BleFailureLog.linkLoss(nowMs: Long) = record(
        phase = MeshtasticBleClient.BleFailure.Phase.LINK_LOSS,
        reason = ConnectionObserver.REASON_TIMEOUT,
        status = null,
        bondState = BluetoothDevice.BOND_BONDED,
        message = "link lost: reason=10",
        nowMs = nowMs,
    )

    @Test fun failure_log_counts_repeats_of_the_same_failure() {
        val log = MeshtasticBleClient.BleFailureLog()
        assertNull(log.last.value)
        assertEquals(1, log.linkLoss(1_000L).consecutiveFailures)
        assertEquals(2, log.linkLoss(2_000L).consecutiveFailures)
        assertEquals(2, log.last.value?.consecutiveFailures)
    }

    @Test fun failure_log_keeps_the_last_failure_after_the_link_recovers() {
        // A drop in the night must still be readable off the BLE pane after
        // the automatic reconnect has worked.
        val log = MeshtasticBleClient.BleFailureLog()
        val dropped = log.linkLoss(1_000L)
        log.sessionCameUp()
        assertSame(dropped, log.last.value)
    }

    @Test fun failure_log_starts_a_new_streak_after_a_session() {
        val log = MeshtasticBleClient.BleFailureLog()
        log.linkLoss(1_000L)
        log.linkLoss(2_000L)
        log.sessionCameUp()
        assertEquals(1, log.linkLoss(3_000L).consecutiveFailures)
        assertEquals(2, log.linkLoss(4_000L).consecutiveFailures)
    }

    @Test fun failure_log_records_a_draft_with_its_hint_and_services() {
        val log = MeshtasticBleClient.BleFailureLog()
        val draft = MeshtasticBleClient.describeConnectFailure(
            facts(linkCameUp = true, serviceMissing = true, cacheRefreshed = true, services = listOf("1800", "1801")),
        )
        val failure = log.record(draft, bondState = BluetoothDevice.BOND_BONDED, nowMs = 5_000L)
        assertEquals(MeshtasticBleClient.BleFailure.Phase.SERVICE_DISCOVERY, failure.phase)
        assertEquals(listOf("1800", "1801"), failure.discoveredServices)
        assertEquals(MeshtasticBleClient.HINT_NOT_MESHTASTIC, failure.hint)
        assertEquals(BluetoothDevice.BOND_BONDED, failure.bondState)
        assertEquals(5_000L, failure.timestampMs)
    }

    // endregion

    // region #203 — diagnostics text and scan names -------------------------

    @Test fun diagnostics_text_carries_the_hint_and_the_discovered_services() {
        val failure = MeshtasticBleClient.BleFailure(
            timestampMs = 1_000L,
            phase = MeshtasticBleClient.BleFailure.Phase.SERVICE_DISCOVERY,
            nordicReason = ConnectionObserver.REASON_NOT_SUPPORTED,
            gattStatus = null,
            bondState = BluetoothDevice.BOND_BONDED,
            consecutiveFailures = 1,
            message = "Meshtastic service not found on this device",
            discoveredServices = listOf("1800", "6e400001"),
            hint = MeshtasticBleClient.HINT_NOT_MESHTASTIC,
        )
        val text = MeshtasticBleClient.formatDiagnostics(failure, listOf("1 I #3 Connected", "2 W #3 gone"), nowMs = 2_000L)
        val lines = text.lines()
        assertEquals("Last failure: SERVICE_DISCOVERY reason=4 status=-, bond=BONDED, 1s ago", lines[0])
        assertEquals("Meshtastic service not found on this device", lines[1])
        assertEquals(MeshtasticBleClient.HINT_NOT_MESHTASTIC, lines[2])
        assertEquals("Discovered services: 1800, 6e400001", lines[3])
        assertTrue(text.endsWith("--- BLE log (last 2) ---\n1 I #3 Connected\n2 W #3 gone"))
    }

    @Test fun diagnostics_text_without_a_failure_says_so() {
        val text = MeshtasticBleClient.formatDiagnostics(null, emptyList(), nowMs = 0L)
        assertTrue(text.startsWith("No BLE failures recorded"))
    }

    @Test fun scan_lists_a_radio_under_the_name_it_advertises_now() {
        // Android remembers a name per address; a board reflashed from
        // MeshCore to Meshtastic kept its old one in the scan list.
        assertEquals(
            "Meshtastic_02d8",
            MeshtasticBleClient.scanDisplayName(advertised = "Meshtastic_02d8", cached = "MeshCore-25C70E7E"),
        )
    }

    @Test fun scan_falls_back_to_the_remembered_name() {
        assertEquals("Heltec", MeshtasticBleClient.scanDisplayName(advertised = null, cached = "Heltec"))
        assertEquals("Heltec", MeshtasticBleClient.scanDisplayName(advertised = "  ", cached = "Heltec"))
        assertNull(MeshtasticBleClient.scanDisplayName(advertised = null, cached = ""))
    }

    // endregion
}
