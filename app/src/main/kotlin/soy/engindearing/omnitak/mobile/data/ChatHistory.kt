package soy.engindearing.omnitak.mobile.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.IdentityHashMap

/**
 * #260 - the whole chat log as one saved document: every conversation and the
 * messages kept for them. Pure Kotlin (no Android imports) so the rules below
 * are unit-testable on the JVM.
 *
 * iOS keeps everything it has ever seen (`ChatManager.saveMessages` and
 * `saveConversations` write the full arrays and nothing trims them), so there
 * is no iOS number to copy. Android keeps the newest [MAX_MESSAGES_PER_CONVERSATION]
 * messages of each conversation and the newest [MAX_CONVERSATIONS] conversations.
 */
@Serializable
data class ChatSnapshot(
    val version: Int = CURRENT_VERSION,
    val conversations: List<ChatConversation> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
) {

    /**
     * What a store should hold after a restart: this snapshot cut down to the
     * caps, with a message that was still [ChatStatus.SENDING] when the process
     * ended restored as [ChatStatus.FAILED]. Nothing is going to finish sending
     * it, and a bubble stuck on "sending" for ever would be a lie. Unread counts
     * are left exactly as they were saved.
     */
    fun restored(): ChatSnapshot {
        val capped = capped()
        return capped.copy(
            messages = capped.messages.map {
                if (it.status == ChatStatus.SENDING) it.copy(status = ChatStatus.FAILED) else it
            },
        )
    }

    /**
     * This snapshot cut down to what is worth keeping, in a fixed order so two
     * snapshots of the same state compare equal:
     *  - the newest [MAX_CONVERSATIONS] conversations by last activity (a
     *    conversation with no activity yet counts as the oldest),
     *  - for each, its newest [MAX_MESSAGES_PER_CONVERSATION] messages, oldest
     *    first, one message per id (a repeated id would crash the message list,
     *    which keys its rows by id),
     *  - no message whose conversation is gone,
     *  - and no more than about [MAX_APPROX_BYTES] of message text overall, so a
     *    flood of very large messages cannot grow the file without bound.
     */
    fun capped(): ChatSnapshot {
        val keptConversations = conversations
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<ChatConversation> { it.lastActivityIso ?: "" }.thenBy { it.id },
            )
            .take(MAX_CONVERSATIONS)
        val byConversation = messages.groupBy { it.conversationId }
        val keptMessages = keptConversations.flatMap { convo ->
            byConversation[convo.id].orEmpty()
                .distinctBy { it.id }
                .sortedBy { it.timeIso }
                .takeLast(MAX_MESSAGES_PER_CONVERSATION)
        }
        return copy(
            version = CURRENT_VERSION,
            conversations = keptConversations,
            messages = withinByteBudget(keptMessages),
        )
    }

    companion object {
        const val CURRENT_VERSION = 1

        /** Newest messages kept per conversation. */
        const val MAX_MESSAGES_PER_CONVERSATION = 500

        /** Newest conversations kept, by last activity. */
        const val MAX_CONVERSATIONS = 100

        /**
         * Rough ceiling for the text kept in all messages together, 24 MiB.
         * Even the most the counts allow, 100 conversations of 500 ordinary
         * messages, comes to about 20 MB, so it only bites when something
         * floods the log with very large messages.
         */
        const val MAX_APPROX_BYTES = 24L * 1024 * 1024

        private const val APPROX_OVERHEAD_BYTES = 360L

        private fun approxBytes(m: ChatMessage): Long =
            APPROX_OVERHEAD_BYTES + m.text.length + m.senderCallsign.length +
                (m.recipientCallsign?.length ?: 0)

        /** Keeps the newest messages that fit in [MAX_APPROX_BYTES], in their order. */
        private fun withinByteBudget(messages: List<ChatMessage>): List<ChatMessage> {
            var total = 0L
            for (m in messages) total += approxBytes(m)
            if (total <= MAX_APPROX_BYTES) return messages
            val keep: MutableSet<ChatMessage> = Collections.newSetFromMap(IdentityHashMap())
            var used = 0L
            for (m in messages.sortedByDescending { it.timeIso }) {
                used += approxBytes(m)
                if (used > MAX_APPROX_BYTES) break
                keep.add(m)
            }
            return messages.filter { it in keep }
        }
    }
}

/** Where the chat log is kept between runs. */
interface ChatHistory {
    /**
     * The saved chat log as it was written. Never throws: a log that is
     * missing, empty or unreadable comes back as an empty [ChatSnapshot].
     */
    fun load(): ChatSnapshot

    /** Writes [snapshot]. Returns false when it could not be written. Never throws. */
    fun save(snapshot: ChatSnapshot): Boolean
}

/**
 * [ChatHistory] in one JSON file, `<filesDir>/chat/history.json` in the app. Same
 * place and encoding as the other file-backed stores (offline regions, KML
 * overlays): a file in the private files directory, kotlinx JSON.
 *
 * A save writes a temporary file next to the real one, syncs it and renames it
 * over the real one, so a process killed mid-write leaves the previous log
 * intact instead of a half-written file.
 */
class ChatHistoryFile(private val file: File) : ChatHistory {

    override fun load(): ChatSnapshot = try {
        when {
            !file.isFile -> ChatSnapshot()
            // Far more than the caps allow. Reading it could run the app out of
            // memory during start-up, and that would repeat on every launch.
            file.length() > MAX_FILE_BYTES -> ChatSnapshot()
            else -> {
                val text = file.readText(Charsets.UTF_8)
                if (text.isBlank()) ChatSnapshot() else JSON.decodeFromString<ChatSnapshot>(text)
            }
        }
    } catch (e: Exception) {
        // Truncated, hand-edited or from something else entirely: start empty.
        // The next save replaces it.
        ChatSnapshot()
    } catch (e: OutOfMemoryError) {
        ChatSnapshot()
    }

    override fun save(snapshot: ChatSnapshot): Boolean = try {
        val bytes = JSON.encodeToString(snapshot.capped()).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) {
            // Keep the previous file: a larger one would be refused on the next load.
            false
        } else {
            val dir = file.absoluteFile.parentFile
            if (dir == null || !(dir.isDirectory || dir.mkdirs())) {
                false
            } else {
                val tmp = File(dir, file.name + ".tmp")
                FileOutputStream(tmp).use { out ->
                    out.write(bytes)
                    // Best effort: a filesystem that cannot sync must not stop saving.
                    runCatching { out.fd.sync() }
                }
                if (tmp.renameTo(file)) {
                    true
                } else {
                    tmp.delete()
                    false
                }
            }
        }
    } catch (e: Exception) {
        false
    } catch (e: OutOfMemoryError) {
        false
    }

    companion object {
        const val DIR_NAME = "chat"
        const val FILE_NAME = "history.json"

        /** A saved log larger than this is not read, and a larger one is not written. */
        const val MAX_FILE_BYTES = 64L * 1024 * 1024

        /** `<filesDir>/chat/history.json` */
        fun inFilesDir(filesDir: File): ChatHistoryFile =
            ChatHistoryFile(File(File(filesDir, DIR_NAME), FILE_NAME))

        private val JSON = Json {
            // A log written by a newer build still loads here.
            ignoreUnknownKeys = true
            // ...and a status this build does not know falls back to its default
            // instead of failing the whole file.
            coerceInputValues = true
            encodeDefaults = true
        }
    }
}
