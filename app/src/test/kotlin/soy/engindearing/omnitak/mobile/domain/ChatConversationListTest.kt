package soy.engindearing.omnitak.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import soy.engindearing.omnitak.mobile.data.ChatConversation
import soy.engindearing.omnitak.mobile.data.ChatHistoryFile
import soy.engindearing.omnitak.mobile.data.ChatMessage
import soy.engindearing.omnitak.mobile.data.ChatParticipant
import soy.engindearing.omnitak.mobile.data.ChatRoom
import soy.engindearing.omnitak.mobile.data.ChatSnapshot
import soy.engindearing.omnitak.mobile.data.ChatStatus
import soy.engindearing.omnitak.mobile.data.CoTEvent

/**
 * The rows of the Chat tab, and in particular #260: after a restart a
 * direct-message thread is listed before its contact has been seen again.
 */
class ChatConversationListTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val me = "ANDROID-me"
    private val bravo = "BRAVO-2-UID"
    private val dmId = ChatRoom.directConversationId(me, bravo)

    private fun contact(uid: String, callsign: String?, type: String = "a-f-G-U-C") =
        CoTEvent(uid = uid, type = type, lat = 47.0, lon = -117.0, callsign = callsign)

    private fun thread(id: String, title: String, last: String?, uid: String? = null) = ChatConversation(
        id = id,
        title = title,
        isGroup = false,
        participants = uid?.let { listOf(ChatParticipant(it, title)) }.orEmpty(),
        lastActivityIso = last,
    )

    @Test fun `a thread restored after a restart is listed with no contact in sight`() {
        // The run before: the user talked to BRAVO-2 in the broadcast room and in a direct message.
        val log = ChatSnapshot(
            conversations = listOf(
                ChatConversation(
                    id = ChatRoom.ALL_USERS, title = ChatRoom.ALL_USERS, isGroup = true,
                    lastMessagePreview = "all hands", lastActivityIso = "2026-10-11T10:00:01.000Z",
                ),
                ChatConversation(
                    id = dmId, title = "BRAVO-2", isGroup = false,
                    participants = listOf(ChatParticipant(bravo, "BRAVO-2")),
                    lastMessagePreview = "on my way", lastActivityIso = "2026-10-11T10:00:02.000Z",
                ),
            ),
            messages = listOf(
                ChatMessage(
                    id = "m1", conversationId = dmId, senderUid = bravo, senderCallsign = "BRAVO-2",
                    text = "on my way", timeIso = "2026-10-11T10:00:02.000Z", status = ChatStatus.RECEIVED,
                ),
            ),
        )
        ChatHistoryFile.inFilesDir(tmp.root).save(log)

        // The next run: a new store over the same file, and the contact list is still empty.
        val store = ChatStore(history = ChatHistoryFile.inFilesDir(tmp.root))
        val rows = ChatConversationList.merged(store.conversations.value.values, contacts = emptyList(), selfUid = me)

        assertEquals(listOf(dmId, ChatRoom.ALL_USERS), rows.map { it.id })
        val dm = rows.first()
        assertEquals("BRAVO-2", dm.title)
        assertEquals("on my way", dm.lastMessagePreview)
        assertEquals(listOf("m1"), store.messagesByConversation.value.getValue(dmId).map { it.id })
    }

    @Test fun `the contact seen again does not get a second row`() {
        val restored = listOf(thread(dmId, "BRAVO-2", "2026-10-11T10:00:02.000Z", uid = bravo))

        val rows = ChatConversationList.merged(restored, listOf(contact(bravo, "BRAVO-2")), me)

        assertEquals(listOf(dmId), rows.map { it.id })
    }

    @Test fun `a contact with no thread gets a no-messages row below the threads`() {
        val restored = listOf(thread(dmId, "BRAVO-2", "2026-10-11T10:00:02.000Z", uid = bravo))
        val contacts = listOf(contact("CHARLIE-UID", "charlie"), contact("ALPHA-UID", "Alpha"), contact(bravo, "BRAVO-2"))

        val rows = ChatConversationList.merged(restored, contacts, me)

        assertEquals(listOf(dmId, ChatRoom.directConversationId(me, "ALPHA-UID"), ChatRoom.directConversationId(me, "CHARLIE-UID")), rows.map { it.id })
        val stub = rows[1]
        assertEquals("Alpha", stub.title)
        assertEquals(null, stub.lastMessagePreview)
        assertEquals(listOf("ALPHA-UID"), stub.participants.map { it.uid })
    }

    @Test fun `threads are listed newest first and a thread with no activity last`() {
        val threads = listOf(
            thread("DM-old", "old", "2026-10-11T09:00:00.000Z"),
            thread("DM-empty", "empty", null),
            thread("DM-new", "new", "2026-10-11T11:00:00.000Z"),
        )

        assertEquals(listOf("DM-new", "DM-old", "DM-empty"), ChatConversationList.merged(threads, emptyList(), me).map { it.id })
    }

    @Test fun `you are not offered as a contact and neither are dropped markers`() {
        val contacts = listOf(
            contact(me, "ME"),
            contact("local-1716000000000", "Drop-1"),
            contact("server-marker", "Spot", type = "b-m-p-s-m"),
        )

        val rows = ChatConversationList.merged(emptyList(), contacts, me)

        assertTrue(rows.isEmpty())
    }

    @Test fun `a stub uses the same id the thread gets once a message is sent`() {
        val rows = ChatConversationList.merged(emptyList(), listOf(contact(bravo, "BRAVO-2")), me)

        assertEquals(dmId, rows.single().id)
        assertFalse(rows.single().mirrorsToMesh)
    }
}
