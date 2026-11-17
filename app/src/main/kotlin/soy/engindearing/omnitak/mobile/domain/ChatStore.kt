package soy.engindearing.omnitak.mobile.domain

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import soy.engindearing.omnitak.mobile.data.ChatConversation
import soy.engindearing.omnitak.mobile.data.ChatHistory
import soy.engindearing.omnitak.mobile.data.ChatMessage
import soy.engindearing.omnitak.mobile.data.ChatParticipant
import soy.engindearing.omnitak.mobile.data.ChatRoom
import soy.engindearing.omnitak.mobile.data.ChatSnapshot
import soy.engindearing.omnitak.mobile.data.ChatStatus

/**
 * Chat log. Keyed by conversationId → ordered list of messages,
 * plus a parallel map of conversation metadata. Everything sits behind
 * StateFlows so Compose screens observe reactively.
 *
 * A broadcast conversation ([ChatRoom.ALL_USERS]) is seeded on creation
 * so the "team chat" tab has a sensible landing target before any
 * contacts are discovered.
 *
 * Ingest is called from per-server collectors and the mesh pipeline
 * concurrently, so every read-modify-write runs inside
 * [MutableStateFlow.update]'s CAS loop — plain `value = value + x`
 * could drop a concurrent message under multi-server burst.
 *
 * #260 - with a [history] the log survives the process ending (force stop,
 * reboot, the system reclaiming memory, an app update):
 *  - it is loaded in the constructor, before the flows exist, so the Chat screen
 *    and the ingest paths only ever see a store that already holds it (the app
 *    builds the store on a background thread at launch; whoever needs it sooner
 *    waits for the lazy that holds it);
 *  - what comes back is cut to the caps in [ChatSnapshot] and a message that
 *    was still sending is a failed one ([ChatSnapshot.restored]); unread counts
 *    come back as they were;
 *  - every change is written again, off the calling thread: the first change
 *    opens a [writeDelayMs] window and everything that changes inside it goes
 *    out in one write when the window closes, so a burst of messages costs one
 *    write and a change is on disk well under a second after it happens.
 *
 * Only what is written is capped. The running session keeps every message it
 * has, as it always did (the conversation view scrolls to the end when the
 * message count grows, so trimming the live list would break that at the cap).
 *
 * Without a [history] the store stays in memory, which is what the tests of the
 * other classes use. A [history] without a [scope] is read once and never written.
 */
class ChatStore(
    private val history: ChatHistory? = null,
    scope: CoroutineScope? = null,
    private val writeDelayMs: Long = WRITE_DELAY_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    // Read first, before anything below exists: nothing can reach the store
    // until it holds what the last run left.
    private val restored: ChatSnapshot = loadRestored(history)

    private val _conversations = MutableStateFlow<Map<String, ChatConversation>>(
        mapOf(
            ChatRoom.ALL_USERS to ChatConversation(
                id = ChatRoom.ALL_USERS,
                title = ChatRoom.ALL_USERS,
                isGroup = true,
            )
        ) + restored.conversations.associateBy { it.id }
    )
    val conversations: StateFlow<Map<String, ChatConversation>> = _conversations.asStateFlow()

    private val _messagesByConversation = MutableStateFlow<Map<String, List<ChatMessage>>>(
        restored.messages.groupBy { it.conversationId }
    )
    val messagesByConversation: StateFlow<Map<String, List<ChatMessage>>> = _messagesByConversation.asStateFlow()

    /** Wakes the writer. Conflated: any number of changes while it waits count as one. */
    private val dirty = Channel<Unit>(Channel.CONFLATED)

    /** What the file holds as far as this store knows. Touched only by the writer. */
    private var lastWritten: ChatSnapshot = restored

    init {
        if (history != null && scope != null) {
            scope.launch {
                for (ignored in dirty) {
                    delay(writeDelayMs)
                    // Everything changed up to now goes into the snapshot taken
                    // below; a change after that sends a new signal.
                    dirty.tryReceive()
                    try {
                        writeIfChanged(history)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The writer must outlive any one failed write.
                        Log.w(TAG, "chat history not saved: ${e.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    /**
     * GAP-122 — let domain code (eg. MeshtasticManager → ChatStore wire-up
     * in OmniTAKApp) seed a conversation header up-front, before any
     * message arrives. Used so the Chat tab shows "Mesh: Primary" as a
     * channel even when no traffic has come through yet.
     */
    fun upsertConversationIfMissing(id: String, title: String, isGroup: Boolean = true) {
        editConversations { convos ->
            if (convos.containsKey(id)) convos
            else convos + (id to ChatConversation(id = id, title = title, isGroup = isGroup))
        }
    }

    /**
     * Seed a full [ChatConversation] if no entry exists for its id yet.
     * Used by ChatScreen to promote a contact stub (built from ContactStore)
     * into a real backing conversation with participant metadata so that
     * the send path can resolve the recipient UID and callsign.
     */
    fun upsertConversationIfMissing(conversation: ChatConversation) {
        editConversations { convos ->
            if (convos.containsKey(conversation.id)) convos
            else convos + (conversation.id to conversation)
        }
    }

    /**
     * GAP-123 — create the conversation if missing, OR rename it if the
     * existing title is still the placeholder we seeded before the radio
     * told us its real channel name. Used when admin-port channel reads
     * fold back into the chat list (e.g. "Mesh: Primary" → "Mesh: OmniTAK").
     */
    fun upsertOrRenameConversation(id: String, title: String, isGroup: Boolean = true) {
        editConversations { convos ->
            when (val current = convos[id]) {
                null -> convos + (id to ChatConversation(id = id, title = title, isGroup = isGroup))
                else ->
                    if (current.title == title) convos
                    else convos + (id to current.copy(title = title))
            }
        }
    }

    fun ingest(message: ChatMessage) {
        var added = false
        editMessages { byConvo ->
            val existing = byConvo[message.conversationId].orEmpty()
            if (existing.any { it.id == message.id }) {
                added = false
                byConvo
            } else {
                added = true
                byConvo + (message.conversationId to (existing + message).sortedBy { it.timeIso })
            }
        }
        if (added) upsertConversation(message, incrementUnread = !message.isFromSelf)
    }

    fun markOutgoing(message: ChatMessage) {
        editMessages { byConvo ->
            val existing = byConvo[message.conversationId].orEmpty()
            byConvo + (message.conversationId to (existing + message).sortedBy { it.timeIso })
        }
        upsertConversation(message, incrementUnread = false)
    }

    fun updateMessageStatus(conversationId: String, messageId: String, status: ChatStatus) {
        editMessages { byConvo ->
            val list = byConvo[conversationId].orEmpty()
            val idx = list.indexOfFirst { it.id == messageId }
            if (idx < 0) return@editMessages byConvo
            val updated = list.toMutableList()
            updated[idx] = updated[idx].copy(status = status)
            byConvo + (conversationId to updated)
        }
    }

    fun markRead(conversationId: String) {
        editConversations { convos ->
            val convo = convos[conversationId] ?: return@editConversations convos
            if (convo.unread == 0) convos
            else convos + (conversationId to convo.copy(unread = 0))
        }
    }

    private fun upsertConversation(message: ChatMessage, incrementUnread: Boolean) {
        editConversations { convos ->
            val current = convos[message.conversationId]
            val isGroup = message.conversationId == ChatRoom.ALL_USERS
            val title = current?.title
                ?: if (isGroup) ChatRoom.ALL_USERS else message.senderCallsign

            val participants = (current?.participants.orEmpty() + ChatParticipant(
                uid = message.senderUid,
                callsign = message.senderCallsign,
            )).distinctBy { it.uid }

            val next = (current ?: ChatConversation(
                id = message.conversationId,
                title = title,
                isGroup = isGroup,
            )).copy(
                title = title,
                participants = participants,
                lastMessagePreview = message.text,
                lastActivityIso = message.timeIso,
                unread = (current?.unread ?: 0) + if (incrementUnread) 1 else 0,
                // Per-server DM scoping: remember which server this thread is on
                // so replies route back to it. Group/broadcast rooms stay null.
                serverId = current?.serverId ?: if (!isGroup) message.serverId else null,
            )
            convos + (message.conversationId to next)
        }
    }

    // Every change to either flow goes through these two, so none can skip the
    // save. A transform that returns its argument unchanged is not a change.
    private inline fun editConversations(
        transform: (Map<String, ChatConversation>) -> Map<String, ChatConversation>,
    ) {
        val before = _conversations.value
        _conversations.update(transform)
        if (_conversations.value !== before) markDirty()
    }

    private inline fun editMessages(
        transform: (Map<String, List<ChatMessage>>) -> Map<String, List<ChatMessage>>,
    ) {
        val before = _messagesByConversation.value
        _messagesByConversation.update(transform)
        if (_messagesByConversation.value !== before) markDirty()
    }

    private fun markDirty() {
        if (history != null) dirty.trySend(Unit)
    }

    private fun currentSnapshot() = ChatSnapshot(
        conversations = _conversations.value.values.toList(),
        messages = _messagesByConversation.value.values.flatten(),
    )

    private suspend fun writeIfChanged(history: ChatHistory) {
        val snapshot = currentSnapshot().capped()
        if (snapshot == lastWritten) return
        if (withContext(ioDispatcher) { history.save(snapshot) }) {
            lastWritten = snapshot
        } else {
            // Stays unwritten; the next change tries again.
            Log.w(TAG, "chat history not saved")
        }
    }

    companion object {
        private const val TAG = "ChatStore"

        private fun loadRestored(history: ChatHistory?): ChatSnapshot {
            if (history == null) return ChatSnapshot()
            val startNs = System.nanoTime()
            val restored = history.load().restored()
            // How much came back and how long the read took: this runs where the
            // app starts, so the time is worth a line when something is slow or empty.
            Log.i(
                TAG,
                "restored ${restored.conversations.size} conversations and " +
                    "${restored.messages.size} messages in ${(System.nanoTime() - startNs) / 1_000_000} ms",
            )
            return restored
        }

        /** How long the first change in a burst waits for company before it is written. */
        const val WRITE_DELAY_MS = 500L
    }
}
