package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/**
 * #260 - the chat log kept across restarts: what is written, what comes back,
 * the caps, and what happens to a file that is missing, empty or damaged.
 */
class ChatHistoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file() = File(File(tmp.root, ChatHistoryFile.DIR_NAME), ChatHistoryFile.FILE_NAME)

    private fun history() = ChatHistoryFile(file())

    /** Strictly increasing timestamps, one second apart, starting at the same instant. */
    private fun iso(second: Int): String =
        "2026-10-11T%02d:%02d:%02d.000Z".format(second / 3600, second / 60 % 60, second % 60)

    private fun message(
        id: String,
        conversationId: String = ChatRoom.ALL_USERS,
        second: Int = 0,
        text: String = "text $id",
        status: ChatStatus = ChatStatus.RECEIVED,
        isFromSelf: Boolean = false,
        serverId: String? = null,
        recipientUid: String? = null,
        recipientCallsign: String? = null,
    ) = ChatMessage(
        id = id,
        conversationId = conversationId,
        senderUid = if (isFromSelf) "ANDROID-me" else "BRAVO-2-UID",
        senderCallsign = if (isFromSelf) "ME" else "BRAVO-2",
        recipientUid = recipientUid,
        recipientCallsign = recipientCallsign,
        text = text,
        timeIso = iso(second),
        status = status,
        isFromSelf = isFromSelf,
        serverId = serverId,
    )

    private fun conversation(
        id: String,
        lastSecond: Int? = null,
        unread: Int = 0,
        isGroup: Boolean = false,
    ) = ChatConversation(
        id = id,
        title = id,
        isGroup = isGroup,
        lastActivityIso = lastSecond?.let { iso(it) },
        unread = unread,
    )

    /** A log with a broadcast room and a direct message that use every field once. */
    private fun sampleLog(): ChatSnapshot {
        val dmId = ChatRoom.directConversationId("ANDROID-me", "BRAVO-2-UID", serverId = "server-1")
        return ChatSnapshot(
            conversations = listOf(
                ChatConversation(
                    id = ChatRoom.ALL_USERS,
                    title = ChatRoom.ALL_USERS,
                    isGroup = true,
                    participants = listOf(ChatParticipant("BRAVO-2-UID", "BRAVO-2")),
                    lastMessagePreview = "all hands",
                    lastActivityIso = iso(20),
                    unread = 3,
                ),
                ChatConversation(
                    id = dmId,
                    title = "BRAVO-2",
                    isGroup = false,
                    participants = listOf(ChatParticipant("BRAVO-2-UID", "BRAVO-2")),
                    lastMessagePreview = "on my way",
                    lastActivityIso = iso(40),
                    unread = 0,
                    serverId = "server-1",
                ),
            ),
            messages = listOf(
                message("a1", ChatRoom.ALL_USERS, 10, "radio check"),
                message("a2", ChatRoom.ALL_USERS, 20, "all hands", status = ChatStatus.SENT, isFromSelf = true),
                message(
                    "d1", dmId, 30, "where are you?",
                    status = ChatStatus.FAILED, isFromSelf = true, serverId = "server-1",
                    recipientUid = "BRAVO-2-UID", recipientCallsign = "BRAVO-2",
                ),
                message("d2", dmId, 40, "on my way", serverId = "server-1"),
            ),
        )
    }

    // ---- what is written comes back ------------------------------------------------

    @Test fun `a saved log comes back the way it was written`() {
        val log = sampleLog()
        assertTrue(history().save(log))

        val loaded = history().load()

        assertEquals(log.conversations.toSet(), loaded.conversations.toSet())
        assertEquals(log.messages.toSet(), loaded.messages.toSet())
        // Oldest first inside a conversation.
        assertEquals(
            listOf("a1", "a2"),
            loaded.messages.filter { it.conversationId == ChatRoom.ALL_USERS }.map { it.id },
        )
    }

    @Test fun `every message field survives`() {
        val sent = message(
            "m1", ChatRoom.directConversationId("ANDROID-me", "BRAVO-2-UID"), 5, "line one\nline \"two\" \\ é中",
            status = ChatStatus.SENT, isFromSelf = true, serverId = "server-9",
            recipientUid = "BRAVO-2-UID", recipientCallsign = "BRAVO-2",
        )
        val log = ChatSnapshot(conversations = listOf(conversation(sent.conversationId, 5)), messages = listOf(sent))
        history().save(log)

        assertEquals(sent, history().load().messages.single())
    }

    @Test fun `unread counts come back as they were`() {
        val log = ChatSnapshot(
            conversations = listOf(
                conversation("read", lastSecond = 3, unread = 0),
                conversation("one", lastSecond = 2, unread = 1),
                conversation("many", lastSecond = 1, unread = 87),
            ),
        )
        history().save(log)

        val restored = history().load().restored()

        assertEquals(0, restored.conversations.first { it.id == "read" }.unread)
        assertEquals(1, restored.conversations.first { it.id == "one" }.unread)
        assertEquals(87, restored.conversations.first { it.id == "many" }.unread)
    }

    @Test fun `the log is one json file under the chat folder of the files dir`() {
        val inFilesDir = ChatHistoryFile.inFilesDir(tmp.root)
        inFilesDir.save(sampleLog())

        val written = File(tmp.root, "chat/history.json")
        assertTrue(written.isFile)
        assertTrue(written.readText().contains("\"version\":1"))
        assertEquals(sampleLog().messages.toSet(), inFilesDir.load().messages.toSet())
    }

    // ---- a file that is missing, empty or damaged ----------------------------------

    @Test fun `a missing file loads as an empty log`() {
        val loaded = history().load()

        assertTrue(loaded.conversations.isEmpty())
        assertTrue(loaded.messages.isEmpty())
    }

    @Test fun `an empty or blank file loads as an empty log`() {
        for (content in listOf("", "   ", "\n\n")) {
            file().apply { parentFile.mkdirs() }.writeText(content)

            val loaded = history().load()

            assertTrue("content <$content>", loaded.conversations.isEmpty() && loaded.messages.isEmpty())
        }
    }

    @Test fun `a damaged file loads as an empty log and nothing throws`() {
        val whole = sampleLog().let { log ->
            history().save(log)
            file().readText()
        }
        val damaged = listOf(
            "this is not json",
            whole.substring(0, whole.length / 2), // cut off mid-write
            whole.dropLast(1), // lost the closing brace
            "[]", // the wrong shape
            "null",
            "{\"conversations\": \"nope\"}",
            "{\"messages\": [{\"id\": 1}]}", // a message with nothing a message needs
            "{\"messages\": [{\"id\": \"x\"}]}",
            "\u0000\u0001\u0002\u0003",
        )
        for (content in damaged) {
            file().writeText(content)

            val loaded = history().load()

            assertTrue("content <${content.take(40)}>", loaded.conversations.isEmpty() && loaded.messages.isEmpty())
        }
    }

    @Test fun `a file of random bytes loads as an empty log`() {
        file().apply { parentFile.mkdirs() }.writeBytes(ByteArray(4096) { (it * 31 + 7).toByte() })

        val loaded = history().load()

        assertTrue(loaded.conversations.isEmpty() && loaded.messages.isEmpty())
    }

    @Test fun `the next save replaces a damaged file`() {
        file().apply { parentFile.mkdirs() }.writeText("{ broken")
        assertTrue(history().load().messages.isEmpty())

        assertTrue(history().save(sampleLog()))

        assertEquals(sampleLog().messages.toSet(), history().load().messages.toSet())
    }

    @Test fun `a file far past the caps is not read`() {
        file().apply { parentFile.mkdirs() }
        RandomAccessFile(file(), "rw").use { it.setLength(ChatHistoryFile.MAX_FILE_BYTES + 1) }

        val loaded = history().load()

        assertTrue(loaded.conversations.isEmpty() && loaded.messages.isEmpty())
    }

    @Test fun `a log from a newer build still loads`() {
        file().apply { parentFile.mkdirs() }.writeText(
            """
            {
              "version": 7,
              "somethingNew": {"a": [1, 2, 3]},
              "conversations": [
                {"id": "All Chat Users", "title": "All Chat Users", "isGroup": true, "unread": 2, "pinned": true}
              ],
              "messages": [
                {"id": "m1", "conversationId": "All Chat Users", "senderUid": "u", "senderCallsign": "c",
                 "text": "hello", "timeIso": "2026-10-11T00:00:01.000Z", "status": "DELIVERED", "reaction": "x"}
              ]
            }
            """.trimIndent(),
        )

        val loaded = history().load().restored()

        assertEquals(2, loaded.conversations.single().unread)
        val m = loaded.messages.single()
        assertEquals("hello", m.text)
        // A status this build does not know falls back to the default, it does not lose the message.
        assertEquals(ChatStatus.RECEIVED, m.status)
    }

    // ---- sending when the process ended --------------------------------------------

    @Test fun `a message still sending is restored as failed`() {
        val log = ChatSnapshot(
            conversations = listOf(conversation(ChatRoom.ALL_USERS, 4, isGroup = true)),
            messages = listOf(
                message("sending", second = 1, status = ChatStatus.SENDING, isFromSelf = true),
                message("sent", second = 2, status = ChatStatus.SENT, isFromSelf = true),
                message("failed", second = 3, status = ChatStatus.FAILED, isFromSelf = true),
                message("received", second = 4, status = ChatStatus.RECEIVED),
            ),
        )

        val restored = log.restored().messages.associateBy { it.id }

        assertEquals(ChatStatus.FAILED, restored.getValue("sending").status)
        assertEquals(ChatStatus.SENT, restored.getValue("sent").status)
        assertEquals(ChatStatus.FAILED, restored.getValue("failed").status)
        assertEquals(ChatStatus.RECEIVED, restored.getValue("received").status)
        // Only the status changes.
        assertEquals("text sending", restored.getValue("sending").text)
        assertTrue(restored.getValue("sending").isFromSelf)
    }

    @Test fun `the file keeps a sending message as it was and the restore turns it into a failed one`() {
        val log = ChatSnapshot(
            conversations = listOf(conversation(ChatRoom.ALL_USERS, 1, isGroup = true)),
            messages = listOf(message("m", second = 1, status = ChatStatus.SENDING, isFromSelf = true)),
        )
        history().save(log)

        val raw = history().load()

        assertEquals(ChatStatus.SENDING, raw.messages.single().status)
        assertEquals(ChatStatus.FAILED, raw.restored().messages.single().status)
    }

    // ---- caps ----------------------------------------------------------------------

    @Test fun `only the newest 500 messages of a conversation are kept`() {
        val busy = ChatRoom.ALL_USERS
        val quiet = "DM-quiet"
        val log = ChatSnapshot(
            conversations = listOf(conversation(busy, 519, isGroup = true), conversation(quiet, 3)),
            messages = (0 until 520).map { message("b%03d".format(it), busy, it) } +
                (1..3).map { message("q$it", quiet, it) },
        )
        history().save(log)

        val loaded = history().load()

        val kept = loaded.messages.filter { it.conversationId == busy }
        assertEquals(500, kept.size)
        assertEquals("b020", kept.first().id)
        assertEquals("b519", kept.last().id)
        assertEquals(kept.map { it.timeIso }.sorted(), kept.map { it.timeIso })
        // Another conversation is not touched by it.
        assertEquals(listOf("q1", "q2", "q3"), loaded.messages.filter { it.conversationId == quiet }.map { it.id })
    }

    @Test fun `only the newest 100 conversations are kept and their messages go with the rest`() {
        val log = ChatSnapshot(
            conversations = (0 until 120).map { conversation("c%03d".format(it), lastSecond = it) },
            messages = (0 until 120).map { message("m%03d".format(it), "c%03d".format(it), it) },
        )
        history().save(log)

        val loaded = history().load()

        assertEquals(100, loaded.conversations.size)
        assertEquals((20 until 120).map { "c%03d".format(it) }.toSet(), loaded.conversations.map { it.id }.toSet())
        assertEquals((20 until 120).map { "m%03d".format(it) }.toSet(), loaded.messages.map { it.id }.toSet())
    }

    @Test fun `a conversation with no activity yet is the first to go`() {
        val withActivity = (1..100).map { conversation("active$it", lastSecond = it) }
        val stub = conversation("stub", lastSecond = null)

        val capped = ChatSnapshot(conversations = withActivity + stub).capped()

        assertEquals(100, capped.conversations.size)
        assertFalse(capped.conversations.any { it.id == "stub" })

        // ...and it is kept while there is room.
        val roomy = ChatSnapshot(conversations = withActivity.drop(1) + stub).capped()
        assertTrue(roomy.conversations.any { it.id == "stub" })
    }

    @Test fun `the caps also apply to a log that was not written by this build`() {
        val big = ChatSnapshot(
            conversations = listOf(conversation(ChatRoom.ALL_USERS, 599, isGroup = true)),
            messages = (0 until 600).map { message("m%03d".format(it), second = it) },
        )
        // Written without the caps, the way another build could have.
        file().apply { parentFile.mkdirs() }.writeText(
            kotlinx.serialization.json.Json { encodeDefaults = true }
                .encodeToString(ChatSnapshot.serializer(), big),
        )

        val restored = history().load().restored()

        assertEquals(500, restored.messages.size)
        assertEquals("m100", restored.messages.first().id)
    }

    @Test fun `a message id repeated in a conversation is kept once`() {
        // The message list keys its rows by id, so a repeat would crash the screen.
        val log = ChatSnapshot(
            conversations = listOf(conversation(ChatRoom.ALL_USERS, 2, isGroup = true)),
            messages = listOf(message("same", second = 1, text = "first"), message("same", second = 2, text = "again")),
        )

        val kept = log.capped().messages

        assertEquals(1, kept.size)
        assertEquals("first", kept.single().text)
    }

    @Test fun `a message whose conversation is gone is dropped`() {
        val log = ChatSnapshot(
            conversations = listOf(conversation("here", 1)),
            messages = listOf(message("kept", "here", 1), message("orphan", "gone", 2)),
        )

        assertEquals(listOf("kept"), log.capped().messages.map { it.id })
    }

    @Test fun `a flood of very large messages stays inside the size ceiling and keeps the newest`() {
        val huge = "x".repeat(2 * 1024 * 1024) // one string, shared by every message below
        val flood = ChatSnapshot(
            conversations = listOf(conversation(ChatRoom.ALL_USERS, 99, isGroup = true)),
            messages = (0 until 100).map { message("big%02d".format(it), second = it, text = huge) },
        )

        val capped = flood.capped()

        val kept = capped.messages
        assertTrue("kept ${kept.size}", kept.size in 1..12) // 24 MiB / 2 MiB
        assertEquals("big99", kept.last().id)
        assertEquals((100 - kept.size until 100).map { "big%02d".format(it) }, kept.map { it.id })
        assertEquals(1, capped.conversations.size)
    }

    // ---- writing -------------------------------------------------------------------

    @Test fun `a save leaves no temporary file behind`() {
        history().save(sampleLog())

        assertEquals(listOf(ChatHistoryFile.FILE_NAME), file().parentFile.list()!!.toList())
    }

    @Test fun `a temporary file left by a killed save is harmless`() {
        history().save(sampleLog())
        val stale = File(file().parentFile, ChatHistoryFile.FILE_NAME + ".tmp")
        stale.writeText("{ half a wri")

        // The real log is still what the last complete save wrote...
        assertEquals(sampleLog().messages.toSet(), history().load().messages.toSet())
        // ...and the next save carries on.
        val next = ChatSnapshot(
            conversations = listOf(conversation(ChatRoom.ALL_USERS, 1, isGroup = true)),
            messages = listOf(message("n", second = 1)),
        )
        assertTrue(history().save(next))
        assertEquals(listOf("n"), history().load().messages.map { it.id })
        assertFalse(stale.exists())
    }

    @Test fun `a save that cannot be written says so and does not throw`() {
        // A plain file where the folder should be.
        val blocker = tmp.newFile("chat")
        val unwritable = ChatHistoryFile(File(blocker, ChatHistoryFile.FILE_NAME))

        assertFalse(unwritable.save(sampleLog()))
        assertTrue(unwritable.load().messages.isEmpty())
    }

    @Test fun `a save that cannot replace the file keeps the previous one and cleans up`() {
        // A folder in the way of the rename.
        val inTheWay = file().apply { mkdirs() }
        File(inTheWay, "keep").writeText("x")

        assertFalse(history().save(sampleLog()))

        assertTrue(inTheWay.isDirectory)
        assertFalse(File(inTheWay.parentFile, ChatHistoryFile.FILE_NAME + ".tmp").exists())
    }

    // ---- the direct-message rule (#263) --------------------------------------------

    @Test fun `mirrorsToMesh is worked out from the id and never written`() {
        val dm = ChatRoom.directConversationId("ANDROID-me", "BRAVO-2-UID")
        val log = ChatSnapshot(
            conversations = listOf(
                ChatConversation(id = ChatRoom.ALL_USERS, title = ChatRoom.ALL_USERS, isGroup = true, lastActivityIso = iso(2)),
                ChatConversation(id = dm, title = "BRAVO-2", isGroup = false, lastActivityIso = iso(1)),
            ),
        )
        history().save(log)

        assertFalse(file().readText().contains("mirrorsToMesh"))
        val restored = history().load().restored().conversations.associateBy { it.id }
        assertTrue(restored.getValue(ChatRoom.ALL_USERS).mirrorsToMesh)
        assertFalse(restored.getValue(dm).mirrorsToMesh)
    }

    @Test fun `a saved file cannot turn a direct message into a mirrored one`() {
        val dm = ChatRoom.directConversationId("ANDROID-me", "BRAVO-2-UID")
        file().apply { parentFile.mkdirs() }.writeText(
            """{"conversations":[{"id":"$dm","title":"BRAVO-2","isGroup":false,"mirrorsToMesh":true}]}""",
        )

        val convo = history().load().restored().conversations.single()

        assertFalse(convo.mirrorsToMesh)
    }

    @Test fun `an empty object loads as an empty log`() {
        file().apply { parentFile.mkdirs() }.writeText("{}")

        val loaded = history().load()

        assertEquals(ChatSnapshot.CURRENT_VERSION, loaded.version)
        assertTrue(loaded.conversations.isEmpty() && loaded.messages.isEmpty())
    }
}
