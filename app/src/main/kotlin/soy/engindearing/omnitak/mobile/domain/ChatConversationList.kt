package soy.engindearing.omnitak.mobile.domain

import soy.engindearing.omnitak.mobile.data.ChatConversation
import soy.engindearing.omnitak.mobile.data.ChatParticipant
import soy.engindearing.omnitak.mobile.data.ChatRoom
import soy.engindearing.omnitak.mobile.data.CoTEvent

/**
 * The rows of the Chat tab. Moved out of ChatScreen as it was, so that what the
 * list shows after a restart (#260) can be tested without the screen.
 */
object ChatConversationList {

    /**
     * Every conversation the chat log holds, newest first, then a "no messages
     * yet" stub for each contact that has no direct-message thread, so the user
     * can start one - matching Civtak / iTak behavior. The own UID is excluded
     * (don't show yourself as a contact to chat with).
     *
     * The rows come from [conversations], not from [contacts]: a direct-message
     * thread restored after a restart is listed whether or not its contact has
     * been seen again. Its participants also cover the contact, so the contact
     * does not get a second row (a stub) when it shows up.
     */
    fun merged(
        conversations: Collection<ChatConversation>,
        contacts: Collection<CoTEvent>,
        selfUid: String,
    ): List<ChatConversation> {
        val existing = conversations.toList()

        // Collect UIDs that already have a direct-message thread so stubs
        // aren't created for contacts you've already chatted with.
        val coveredUids = existing
            .filter { !it.isGroup }
            .flatMap { it.participants }
            .map { it.uid }
            .toSet()

        val contactStubs = contacts
            .filter { contact ->
                contact.uid != selfUid &&
                    contact.uid !in coveredUids &&
                    ContactStore.isEndpoint(contact)
            }
            .map { contact ->
                // Use the same deterministic conversation-id formula as
                // ChatRoom.directConversationId so that when a real GeoChat
                // arrives later the stub seamlessly promotes to a real thread.
                val convoId = ChatRoom.directConversationId(selfUid, contact.uid)
                ChatConversation(
                    id = convoId,
                    title = contact.callsign ?: contact.uid,
                    isGroup = false,
                    participants = listOf(
                        ChatParticipant(
                            uid = contact.uid,
                            callsign = contact.callsign ?: contact.uid,
                        ),
                    ),
                )
            }

        // Real threads (with activity) sorted newest-first; contact stubs
        // sorted alphabetically below them.
        return existing.sortedByDescending { it.lastActivityIso ?: "" } +
            contactStubs.sortedBy { it.title.lowercase() }
    }
}
