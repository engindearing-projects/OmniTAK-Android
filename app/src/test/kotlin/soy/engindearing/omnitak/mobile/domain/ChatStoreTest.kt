package soy.engindearing.omnitak.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * GAP-123 — verifies the upsertOrRename behavior used to fold
 * radio-reported channel names into existing chat conversations.
 */
class ChatStoreTest {

    @Test fun upsertOrRename_creates_when_missing() {
        val store = ChatStore()
        store.upsertOrRenameConversation("MESH-CH3", "Mesh: Local")
        val convo = store.conversations.value["MESH-CH3"]
        assertEquals("Mesh: Local", convo?.title)
    }

    @Test fun upsertOrRename_updates_existing_title() {
        val store = ChatStore()
        store.upsertConversationIfMissing("MESH-CH0", "Mesh: Primary")
        store.upsertOrRenameConversation("MESH-CH0", "Mesh: OmniTAK")
        val convo = store.conversations.value["MESH-CH0"]
        assertEquals("Mesh: OmniTAK", convo?.title)
    }

    @Test fun upsertOrRename_no_op_when_title_matches() {
        val store = ChatStore()
        store.upsertConversationIfMissing("MESH-CH0", "Mesh: Primary")
        val before = store.conversations.value["MESH-CH0"]
        store.upsertOrRenameConversation("MESH-CH0", "Mesh: Primary")
        val after = store.conversations.value["MESH-CH0"]
        // Same instance — no churn for redundant updates.
        assertEquals(before, after)
    }
}
