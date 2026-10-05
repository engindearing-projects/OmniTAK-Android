package soy.engindearing.omnitak.mobile.data

import java.util.UUID

enum class ChatStatus { SENDING, SENT, FAILED, RECEIVED }

/**
 * TAK GeoChat message. Mirrors the iOS ChatMessage minus image
 * attachments — image fileshare lands in a later slice alongside
 * the photo capture flow. conversationId is either [ChatRoom.ALL_USERS]
 * for broadcast traffic or a deterministic DM-{uidA}-{uidB} string.
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val senderUid: String,
    val senderCallsign: String,
    val recipientUid: String? = null,
    val recipientCallsign: String? = null,
    val text: String,
    val timeIso: String,
    val status: ChatStatus = ChatStatus.RECEIVED,
    val isFromSelf: Boolean = false,
    /**
     * Source server (received) / target server (sent), = [TAKServer.id].
     * null for mesh chat and for messages predating multi-server. Drives the
     * per-message server badge and per-server DM conversation scoping.
     */
    val serverId: String? = null,
)

data class ChatParticipant(
    val uid: String,
    val callsign: String,
)

data class ChatConversation(
    val id: String,
    val title: String,
    val isGroup: Boolean,
    val participants: List<ChatParticipant> = emptyList(),
    val lastMessagePreview: String? = null,
    val lastActivityIso: String? = null,
    val unread: Int = 0,
    /**
     * TAK server this (per-server) DM thread belongs to, = [TAKServer.id].
     * null for the merged All-Chat broadcast room and for mesh chat, which
     * broadcast to every connected server. Used to route DM replies back to
     * their origin server (multi-server parity with iOS).
     */
    val serverId: String? = null,
) {
    /**
     * Whether a message sent in this conversation is also sent over the mesh
     * radio. Only the broadcast room is. The mesh copy is a broadcast to
     * everyone on the radio channel, written as an "All Chat Rooms" message,
     * so a direct message must never get one: it would be read by the whole
     * channel. Mesh conversations (MESH-..., MESHCORE-...) have their own send
     * path and are not mirrored either.
     */
    val mirrorsToMesh: Boolean get() = id == ChatRoom.ALL_USERS
}

object ChatRoom {
    // ATAK's canonical broadcast chatroom name — interop with ATAK/iTAK.
    const val ATAK_CHATROOM = "All Chat Rooms"
    const val ALL_USERS = "All Chat Users"
    const val BROADCAST = "BROADCAST"

    /**
     * Deterministic DM bucket. When [serverId] is given the bucket is scoped
     * per server (DM-{serverId}-{uidA}-{uidB}) so a DM with the same callsign
     * on two different TAK servers stays in two separate threads — matches
     * iOS's per-server DM scoping.
     */
    fun directConversationId(uidA: String, uidB: String, serverId: String? = null): String {
        val sorted = listOf(uidA, uidB).sorted()
        return if (serverId != null) {
            "DM-$serverId-${sorted[0]}-${sorted[1]}"
        } else {
            "DM-${sorted[0]}-${sorted[1]}"
        }
    }
}
