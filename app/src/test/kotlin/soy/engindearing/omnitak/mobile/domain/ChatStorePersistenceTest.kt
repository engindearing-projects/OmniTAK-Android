package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import soy.engindearing.omnitak.mobile.data.ChatConversation
import soy.engindearing.omnitak.mobile.data.ChatHistory
import soy.engindearing.omnitak.mobile.data.ChatHistoryFile
import soy.engindearing.omnitak.mobile.data.ChatMessage
import soy.engindearing.omnitak.mobile.data.ChatParticipant
import soy.engindearing.omnitak.mobile.data.ChatRoom
import soy.engindearing.omnitak.mobile.data.ChatSnapshot
import soy.engindearing.omnitak.mobile.data.ChatStatus
import java.io.File

/**
 * #260 - [ChatStore] over a saved chat log: it loads before anything can read
 * it, writes every change once the burst is over, and the next run finds the
 * same conversations, messages and unread counts, with a message that was still
 * sending marked failed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatStorePersistenceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val me = "ANDROID-me"
    private val bravo = "BRAVO-2-UID"
    private val dmId = ChatRoom.directConversationId(me, bravo)

    /** A log in memory that records what the store does to it, in order. */
    private class FakeHistory(private var onDisk: ChatSnapshot = ChatSnapshot()) : ChatHistory {
        val events = mutableListOf<String>()
        val saves = mutableListOf<ChatSnapshot>()
        var failNextSaves = 0

        override fun load(): ChatSnapshot {
            events += "load"
            return onDisk
        }

        override fun save(snapshot: ChatSnapshot): Boolean {
            events += "save"
            if (failNextSaves > 0) {
                failNextSaves--
                return false
            }
            saves += snapshot
            onDisk = snapshot
            return true
        }
    }

    private fun TestScope.storeOver(history: ChatHistory) = ChatStore(
        history = history,
        scope = backgroundScope,
        writeDelayMs = WRITE_DELAY,
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    /** Moves virtual time on and runs everything due, including a write that was waiting. */
    private fun TestScope.settle(ms: Long = WRITE_DELAY) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun iso(second: Int): String =
        "2026-10-11T%02d:%02d:%02d.000Z".format(second / 3600, second / 60 % 60, second % 60)

    private fun received(
        id: String,
        second: Int,
        conversationId: String = ChatRoom.ALL_USERS,
        text: String = "text $id",
    ) = ChatMessage(
        id = id,
        conversationId = conversationId,
        senderUid = bravo,
        senderCallsign = "BRAVO-2",
        text = text,
        timeIso = iso(second),
        status = ChatStatus.RECEIVED,
    )

    private fun sentByMe(
        id: String,
        second: Int,
        conversationId: String,
        status: ChatStatus = ChatStatus.SENDING,
    ) = ChatMessage(
        id = id,
        conversationId = conversationId,
        senderUid = me,
        senderCallsign = "ME",
        recipientUid = if (conversationId == dmId) bravo else null,
        recipientCallsign = if (conversationId == dmId) "BRAVO-2" else null,
        text = "text $id",
        timeIso = iso(second),
        status = status,
        isFromSelf = true,
    )

    /** The conversation the Chat tab seeds when the user taps a contact. */
    private fun contactStub() = ChatConversation(
        id = dmId,
        title = "BRAVO-2",
        isGroup = false,
        participants = listOf(ChatParticipant(uid = bravo, callsign = "BRAVO-2")),
    )

    // ---- the load comes first ------------------------------------------------------

    @Test fun `a store over a saved log already holds it when anything first reads`() = runTest {
        val history = FakeHistory(
            ChatSnapshot(
                conversations = listOf(
                    ChatConversation(
                        id = ChatRoom.ALL_USERS, title = ChatRoom.ALL_USERS, isGroup = true,
                        lastMessagePreview = "radio check", lastActivityIso = iso(5), unread = 4,
                    ),
                ),
                messages = listOf(received("m1", 5, text = "radio check")),
            ),
        )

        val store = storeOver(history)

        // No scheduler step, no collector: the very first read sees the log.
        assertEquals(listOf("load"), history.events)
        assertEquals(4, store.conversations.value.getValue(ChatRoom.ALL_USERS).unread)
        assertEquals(listOf("m1"), store.messagesByConversation.value.getValue(ChatRoom.ALL_USERS).map { it.id })
    }

    @Test fun `the log is read once and is not written straight back`() = runTest {
        val history = FakeHistory(
            ChatSnapshot(
                conversations = listOf(ChatConversation(id = dmId, title = "BRAVO-2", isGroup = false, lastActivityIso = iso(1))),
                messages = listOf(received("m1", 1, dmId)),
            ),
        )
        storeOver(history)

        settle(10 * WRITE_DELAY)

        assertEquals(listOf("load"), history.events)
    }

    @Test fun `a store with nothing saved still has the broadcast room`() = runTest {
        val store = storeOver(FakeHistory())

        val room = store.conversations.value.getValue(ChatRoom.ALL_USERS)
        assertTrue(room.isGroup)
        assertEquals(0, room.unread)
        assertTrue(store.messagesByConversation.value.isEmpty())
    }

    @Test fun `what was saved wins over the starting broadcast room`() = runTest {
        val history = FakeHistory(
            ChatSnapshot(
                conversations = listOf(
                    ChatConversation(
                        id = ChatRoom.ALL_USERS, title = ChatRoom.ALL_USERS, isGroup = true,
                        lastMessagePreview = "kept preview", lastActivityIso = iso(9), unread = 7,
                        participants = listOf(ChatParticipant(bravo, "BRAVO-2")),
                    ),
                ),
            ),
        )

        val room = storeOver(history).conversations.value.getValue(ChatRoom.ALL_USERS)

        assertEquals(7, room.unread)
        assertEquals("kept preview", room.lastMessagePreview)
        assertEquals(listOf(bravo), room.participants.map { it.uid })
    }

    @Test fun `an unreadable log leaves a working empty store`() = runTest {
        val filesDir = tmp.newFolder("files")
        val history = ChatHistoryFile.inFilesDir(filesDir)
        File(filesDir, "chat").mkdirs()
        File(filesDir, "chat/history.json").writeText("{ not json")

        val store = storeOver(history)
        store.ingest(received("m1", 1))
        settle()

        assertEquals(listOf("m1"), store.messagesByConversation.value.getValue(ChatRoom.ALL_USERS).map { it.id })
        assertEquals(listOf("m1"), history.load().messages.map { it.id })
    }

    // ---- writing -------------------------------------------------------------------

    @Test fun `a change is written after the delay and not before`() = runTest {
        val history = FakeHistory()
        val store = storeOver(history)

        store.ingest(received("m1", 1))
        advanceTimeBy(WRITE_DELAY - 1)
        runCurrent()
        assertTrue("written too early", history.saves.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, history.saves.size)
        assertEquals(listOf("m1"), history.saves.single().messages.map { it.id })
        assertTrue(history.saves.single().conversations.any { it.id == ChatRoom.ALL_USERS && it.unread == 1 })
    }

    @Test fun `a burst of changes costs one write`() = runTest {
        val history = FakeHistory()
        val store = storeOver(history)

        repeat(50) {
            store.ingest(received("m$it", it))
            advanceTimeBy(5)
        }
        settle()

        assertEquals(1, history.saves.size)
        assertEquals(50, history.saves.single().messages.size)

        // The next change opens a new window.
        store.ingest(received("late", 60))
        settle()
        assertEquals(2, history.saves.size)
        assertEquals(51, history.saves.last().messages.size)
    }

    @Test fun `every kind of change is written`() = runTest {
        val history = FakeHistory()
        val store = storeOver(history)

        store.upsertConversationIfMissing("MESH-CH0", "Mesh: Primary")
        settle()
        assertTrue(history.saves.last().conversations.any { it.id == "MESH-CH0" })

        store.upsertOrRenameConversation("MESH-CH0", "Mesh: OmniTAK")
        settle()
        assertEquals("Mesh: OmniTAK", history.saves.last().conversations.first { it.id == "MESH-CH0" }.title)

        store.upsertConversationIfMissing(contactStub())
        settle()
        assertTrue(history.saves.last().conversations.any { it.id == dmId })

        store.markOutgoing(sentByMe("out", 3, dmId))
        settle()
        assertEquals(ChatStatus.SENDING, history.saves.last().messages.single { it.id == "out" }.status)

        store.updateMessageStatus(dmId, "out", ChatStatus.SENT)
        settle()
        assertEquals(ChatStatus.SENT, history.saves.last().messages.single { it.id == "out" }.status)

        store.ingest(received("in", 4))
        settle()
        assertEquals(1, history.saves.last().conversations.first { it.id == ChatRoom.ALL_USERS }.unread)

        store.markRead(ChatRoom.ALL_USERS)
        settle()
        assertEquals(0, history.saves.last().conversations.first { it.id == ChatRoom.ALL_USERS }.unread)
    }

    @Test fun `nothing is written when nothing changed`() = runTest {
        val history = FakeHistory()
        val store = storeOver(history)

        store.upsertConversationIfMissing(ChatRoom.ALL_USERS, "ignored: already there")
        store.upsertOrRenameConversation(ChatRoom.ALL_USERS, ChatRoom.ALL_USERS)
        store.markRead(ChatRoom.ALL_USERS) // nothing unread
        store.markRead("no such conversation")
        store.updateMessageStatus("no such conversation", "no such message", ChatStatus.SENT)
        settle(10 * WRITE_DELAY)

        assertTrue(history.saves.isEmpty())
        assertEquals(listOf("load"), history.events)
    }

    @Test fun `a message seen twice is written once`() = runTest {
        val history = FakeHistory()
        val store = storeOver(history)

        store.ingest(received("m1", 1))
        settle()
        store.ingest(received("m1", 1)) // the same message again
        settle(10 * WRITE_DELAY)

        assertEquals(1, history.saves.size)
        assertEquals(1, store.conversations.value.getValue(ChatRoom.ALL_USERS).unread)
    }

    @Test fun `a write that failed is tried again at the next change`() = runTest {
        val history = FakeHistory().apply { failNextSaves = 1 }
        val store = storeOver(history)

        store.ingest(received("m1", 1))
        settle()
        assertTrue(history.saves.isEmpty())
        assertEquals(listOf("load", "save"), history.events)

        store.ingest(received("m2", 2))
        settle()
        assertEquals(1, history.saves.size)
        assertEquals(listOf("m1", "m2"), history.saves.single().messages.map { it.id })
    }

    @Test fun `a store without a log works in memory as before`() = runTest {
        val store = ChatStore()

        store.ingest(received("m1", 1))
        store.markOutgoing(sentByMe("out", 2, ChatRoom.ALL_USERS))
        store.updateMessageStatus(ChatRoom.ALL_USERS, "out", ChatStatus.SENT)
        store.markRead(ChatRoom.ALL_USERS)

        assertEquals(2, store.messagesByConversation.value.getValue(ChatRoom.ALL_USERS).size)
        assertEquals(0, store.conversations.value.getValue(ChatRoom.ALL_USERS).unread)
    }

    // ---- the next run --------------------------------------------------------------

    @Test fun `conversations, messages and unread counts are there after a restart`() = runTest {
        val file = ChatHistoryFile.inFilesDir(tmp.root)
        val first = storeOver(file)
        first.upsertConversationIfMissing("MESH-CH0", "Mesh: Primary")
        first.ingest(received("a1", 1))
        first.ingest(received("a2", 2))
        first.markOutgoing(sentByMe("a3", 3, ChatRoom.ALL_USERS, ChatStatus.SENT))
        first.upsertConversationIfMissing(contactStub())
        first.ingest(received("d1", 4, dmId, "are you there?"))
        first.ingest(received("d2", 5, dmId, "hello?"))
        first.markRead(dmId)
        first.ingest(received("d3", 6, dmId, "last one"))
        settle()

        val second = storeOver(ChatHistoryFile.inFilesDir(tmp.root))

        assertEquals(first.conversations.value.keys, second.conversations.value.keys)
        for ((id, convo) in first.conversations.value) {
            assertEquals(id, convo, second.conversations.value.getValue(id))
        }
        assertEquals(first.messagesByConversation.value, second.messagesByConversation.value)
        // The numbers behind the badge on the Chat tab.
        assertEquals(2, second.conversations.value.getValue(ChatRoom.ALL_USERS).unread)
        assertEquals(1, second.conversations.value.getValue(dmId).unread)
        assertEquals("last one", second.conversations.value.getValue(dmId).lastMessagePreview)
    }

    @Test fun `a message still sending when the process ended comes back failed`() = runTest {
        val file = ChatHistoryFile.inFilesDir(tmp.root)
        val first = storeOver(file)
        first.upsertConversationIfMissing(contactStub())
        first.markOutgoing(sentByMe("stuck", 1, dmId)) // SENDING, and the process dies before it settles
        first.markOutgoing(sentByMe("done", 2, dmId, ChatStatus.SENT))
        settle()
        assertEquals(ChatStatus.SENDING, first.messagesByConversation.value.getValue(dmId).first { it.id == "stuck" }.status)

        val second = storeOver(ChatHistoryFile.inFilesDir(tmp.root))

        val restored = second.messagesByConversation.value.getValue(dmId).associateBy { it.id }
        assertEquals(ChatStatus.FAILED, restored.getValue("stuck").status)
        assertEquals(ChatStatus.SENT, restored.getValue("done").status)
        // The run that is still going is not touched.
        assertEquals(ChatStatus.SENDING, first.messagesByConversation.value.getValue(dmId).first { it.id == "stuck" }.status)
    }

    @Test fun `a direct message thread is there after a restart before its contact is seen again`() = runTest {
        val file = ChatHistoryFile.inFilesDir(tmp.root)
        val first = storeOver(file)
        // What the Chat tab does when the user taps BRAVO-2 and sends: seed the thread, send into it.
        first.upsertConversationIfMissing(contactStub())
        first.markOutgoing(sentByMe("hi", 1, dmId, ChatStatus.SENT))
        settle()

        val second = storeOver(ChatHistoryFile.inFilesDir(tmp.root))

        val thread = second.conversations.value.getValue(dmId)
        assertEquals("BRAVO-2", thread.title)
        assertFalse(thread.isGroup)
        assertTrue(thread.participants.any { it.uid == bravo && it.callsign == "BRAVO-2" })
        assertEquals("text hi", thread.lastMessagePreview)
        assertEquals(listOf("hi"), second.messagesByConversation.value.getValue(dmId).map { it.id })
    }

    @Test fun `a thread opened from a contact but never written in is kept too`() = runTest {
        val file = ChatHistoryFile.inFilesDir(tmp.root)
        storeOver(file).upsertConversationIfMissing(contactStub())
        settle()

        val second = storeOver(ChatHistoryFile.inFilesDir(tmp.root))

        assertNotNull(second.conversations.value[dmId])
        assertTrue(second.messagesByConversation.value[dmId].isNullOrEmpty())
    }

    @Test fun `only the written log is capped, the running session keeps every message`() = runTest {
        val file = ChatHistoryFile.inFilesDir(tmp.root)
        val first = storeOver(file)
        // 520 messages in one second-by-second sequence.
        repeat(520) { first.ingest(received("m%03d".format(it), it)) }
        settle()

        val live = first.messagesByConversation.value.getValue(ChatRoom.ALL_USERS)
        assertEquals(520, live.size)

        val second = storeOver(ChatHistoryFile.inFilesDir(tmp.root))
        val back = second.messagesByConversation.value.getValue(ChatRoom.ALL_USERS)
        assertEquals(500, back.size)
        assertEquals("m020", back.first().id)
        assertEquals("m519", back.last().id)
    }

    @Test fun `restoring does not count as incoming traffic`() = runTest {
        val history = FakeHistory(
            ChatSnapshot(
                conversations = listOf(
                    ChatConversation(id = dmId, title = "BRAVO-2", isGroup = false, lastActivityIso = iso(1), unread = 2),
                ),
                messages = listOf(received("m1", 1, dmId), received("m2", 1, dmId)),
            ),
        )

        val store = storeOver(history)

        // Loading is not ingest(): no second count of the same messages, nothing re-sent.
        assertEquals(2, store.conversations.value.getValue(dmId).unread)
        assertEquals(2, store.messagesByConversation.value.getValue(dmId).size)
    }

    // ---- the direct-message rule (#263) --------------------------------------------

    @Test fun `a restored direct message is still never mirrored to the mesh`() = runTest {
        val file = ChatHistoryFile.inFilesDir(tmp.root)
        val first = storeOver(file)
        first.upsertConversationIfMissing(contactStub())
        first.markOutgoing(sentByMe("hi", 1, dmId, ChatStatus.SENT))
        first.ingest(received("all", 2))
        settle()

        val second = storeOver(ChatHistoryFile.inFilesDir(tmp.root))

        assertFalse(second.conversations.value.getValue(dmId).mirrorsToMesh)
        assertTrue(second.conversations.value.getValue(ChatRoom.ALL_USERS).mirrorsToMesh)
    }

    private companion object {
        const val WRITE_DELAY = 500L
    }
}
