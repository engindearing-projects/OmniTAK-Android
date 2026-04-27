package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
}
