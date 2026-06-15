package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.CoTEvent

/**
 * #118 — ContactStore chat-filter predicate and chatCandidates flow.
 *
 * These tests are RED until [ContactStore.isEndpoint] and
 * [ContactStore.chatCandidates] are implemented.
 */
class ContactStoreChatFilterTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun event(uid: String, type: String) = CoTEvent(
        uid = uid,
        type = type,
        lat = 0.0,
        lon = 0.0,
    )

    // ── #118 RED tests ───────────────────────────────────────────────────────

    @Test fun chat_filter_excludes_dropped_point_marker_by_uid() {
        val marker = event(uid = "local-1716000000000", type = "a-f-G-U-C")
        assertFalse(
            "local-* uid must be excluded from chat",
            ContactStore.isEndpoint(marker),
        )
    }

    @Test fun chat_filter_excludes_b_m_p_type_from_server() {
        val spotMarker = event(uid = "some-server-uid-abc", type = "b-m-p-s-m")
        assertFalse(
            "b-m-p-* type must be excluded from chat regardless of uid source",
            ContactStore.isEndpoint(spotMarker),
        )
    }

    @Test fun chat_filter_includes_ppli_unit() {
        val eud = event(uid = "ANDROID-abc123", type = "a-f-G-U-C")
        assertTrue(
            "a-f-G-U-C (friendly ground unit) must be a valid chat endpoint",
            ContactStore.isEndpoint(eud),
        )
    }

    @Test fun chat_filter_includes_rid_drone() {
        val drone = event(uid = "RID-FA123456789", type = "a-u-A-M-F-Q-r")
        assertTrue(
            "RID- uid with a-u-A drone type must be a valid chat endpoint",
            ContactStore.isEndpoint(drone),
        )
    }

    @Test fun chat_filter_includes_mesh_node() {
        val meshNode = event(uid = "MESHTASTIC-abcdef01", type = "a-f-G-U-C")
        assertTrue(
            "MESHTASTIC- uid must be a valid chat endpoint",
            ContactStore.isEndpoint(meshNode),
        )
    }

    @Test fun chatCandidates_flow_excludes_markers() = runBlocking {
        val store = ContactStore()

        // Ingest a mix: one real EUD, one local dropped marker, one b-m-p spot
        store.ingest(event("ANDROID-eud1", "a-f-G-U-C"))
        store.ingest(event("local-1716000000001", "a-f-G-U-C"))
        store.ingest(event("spot-server-uid", "b-m-p-s-m"))

        val candidates = store.chatCandidates.first()

        assertTrue(
            "real EUD must appear in chatCandidates",
            "ANDROID-eud1" in candidates,
        )
        assertFalse(
            "local-* marker must NOT appear in chatCandidates",
            "local-1716000000001" in candidates,
        )
        assertFalse(
            "b-m-p spot marker must NOT appear in chatCandidates",
            "spot-server-uid" in candidates,
        )
    }
}
