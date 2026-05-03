package soy.engindearing.omnitak.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * GAP-124 — pure-format tests for the DM conversation id helper. The
 * full RX → chatSink path needs an Android transport so it lives in
 * instrumented tests; this slice locks in the wire-id format that both
 * sender and receiver use to bucket DMs into a shared conversation.
 */
class MeshtasticManagerDmTest {

    @Test fun dm_convo_id_zero_padded_uppercase_hex() {
        val mgr = MeshtasticManager()
        assertEquals("MESH-DM-0BADF00D", mgr.meshDmConversationId(0x0BADF00DL))
    }

    @Test fun dm_convo_id_short_node_id_padded() {
        val mgr = MeshtasticManager()
        assertEquals("MESH-DM-00000001", mgr.meshDmConversationId(1L))
    }

    @Test fun dm_convo_id_full_uint32() {
        val mgr = MeshtasticManager()
        // Largest unsigned 32-bit value = -1 in signed, formatted as FFFFFFFF.
        assertEquals("MESH-DM-FFFFFFFF", mgr.meshDmConversationId(0xFFFFFFFFL))
    }

    @Test fun channel_convo_id_unchanged_format() {
        val mgr = MeshtasticManager()
        assertEquals("MESH-CH0", mgr.meshConversationId(0))
        assertEquals("MESH-CH3", mgr.meshConversationId(3))
    }
}
