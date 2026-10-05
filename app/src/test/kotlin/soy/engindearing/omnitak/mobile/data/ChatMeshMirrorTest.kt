package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which conversations are copied onto the mesh radio when a message is sent.
 *
 * The mesh copy is a broadcast to everyone on the radio channel, written as an
 * "All Chat Rooms" message. Only the broadcast room may get one. A direct
 * message used to get it as well, so the whole channel could read it.
 */
class ChatMeshMirrorTest {

    private fun conversation(id: String, isGroup: Boolean, serverId: String? = null) =
        ChatConversation(id = id, title = id, isGroup = isGroup, serverId = serverId)

    @Test fun `the broadcast room is mirrored to the mesh`() {
        assertTrue(conversation(ChatRoom.ALL_USERS, isGroup = true).mirrorsToMesh)
    }

    @Test fun `a direct message is never mirrored to the mesh`() {
        val dm = conversation(ChatRoom.directConversationId("ANDROID-me", "ANDROID-them"), isGroup = false)

        assertFalse(dm.mirrorsToMesh)
    }

    @Test fun `a direct message on one server is never mirrored to the mesh`() {
        val dm = conversation(
            ChatRoom.directConversationId("ANDROID-me", "ANDROID-them", serverId = "server-1"),
            isGroup = false,
            serverId = "server-1",
        )

        assertFalse(dm.mirrorsToMesh)
    }

    @Test fun `a contact named like the broadcast room does not make its direct message a broadcast`() {
        // The id decides, not the title or a callsign that happens to match.
        val dm = ChatConversation(
            id = ChatRoom.directConversationId("ANDROID-me", ChatRoom.ALL_USERS),
            title = ChatRoom.ALL_USERS,
            isGroup = false,
            participants = listOf(ChatParticipant(uid = ChatRoom.ALL_USERS, callsign = ChatRoom.ALL_USERS)),
        )

        assertFalse(dm.mirrorsToMesh)
    }

    @Test fun `mesh conversations are not mirrored a second time`() {
        // They are sent by the mesh path itself.
        for (id in listOf("MESH-CH0", "MESH-CH3", "MESH-DM-a1b2c3d4", "MESHCORE-CH0", "MESHCORE-DM-a1b2c3d4e5f6")) {
            assertFalse(id, conversation(id, isGroup = !id.contains("-DM-")).mirrorsToMesh)
        }
    }

    @Test fun `being a group is not enough`() {
        // Any other room would be widened to the whole channel in the same way.
        assertFalse(conversation("Team Red", isGroup = true).mirrorsToMesh)
        assertFalse(conversation(ChatRoom.ATAK_CHATROOM, isGroup = true).mirrorsToMesh)
        assertFalse(conversation(ChatRoom.BROADCAST, isGroup = true).mirrorsToMesh)
    }
}
