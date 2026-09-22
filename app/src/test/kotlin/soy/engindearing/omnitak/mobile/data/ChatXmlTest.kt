package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iOS #125 parity — a direct message must be keyed by the recipient's UID the
 * way ATAK keys 1:1 chats (`__chat id`, `chatgrp id`/`uid1`, `remarks to`,
 * event uid), with the callsign only naming the room. Sending the callsign as
 * the id made ATAK open a "group" named after itself.
 */
class ChatXmlTest {

    private fun dm(recipientUid: String? = "IOS-UID-9") = ChatXml.generateGeoChat(
        text = "sitrep?",
        senderUid = "ANDROID-UID-1",
        senderCallsign = "ALPHA-1",
        isGroup = false,
        recipientUid = recipientUid,
        recipientCallsign = "BRAVO-2",
        messageId = "MSG-1",
    )

    @Test fun directMessage_isKeyedByRecipientUid() {
        val xml = dm().xml
        assertTrue(xml, xml.contains("<__chat id=\"IOS-UID-9\" chatroom=\"BRAVO-2\" senderCallsign=\"ALPHA-1\""))
        assertTrue(xml, xml.contains("<chatgrp uid0=\"ANDROID-UID-1\" uid1=\"IOS-UID-9\" id=\"IOS-UID-9\"/>"))
        assertTrue(xml, xml.contains("to=\"IOS-UID-9\""))
        assertTrue(xml, xml.contains("uid=\"GeoChat.ANDROID-UID-1.IOS-UID-9.MSG-1\""))
        assertTrue(xml, xml.contains("<marti><dest callsign=\"BRAVO-2\"/></marti>"))
        assertFalse("recipient callsign must never be a conversation id", xml.contains("id=\"BRAVO-2\""))
        assertTrue(xml, xml.contains("messageId=\"MSG-1\""))
    }

    @Test fun directMessage_withoutRecipientUid_fallsBackToCallsign() {
        val xml = dm(recipientUid = null).xml
        assertTrue(xml, xml.contains("<__chat id=\"BRAVO-2\" chatroom=\"BRAVO-2\""))
        assertTrue(xml, xml.contains("uid1=\"BRAVO-2\" id=\"BRAVO-2\"/>"))
    }

    @Test fun groupChat_stillUsesAllChatRooms() {
        val xml = ChatXml.generateGeoChat(
            text = "all call", senderUid = "ANDROID-UID-1", senderCallsign = "ALPHA-1",
            isGroup = true, messageId = "MSG-2",
        ).xml
        val room = ChatRoom.ATAK_CHATROOM
        assertTrue(xml, xml.contains("<__chat id=\"$room\" chatroom=\"$room\""))
        assertTrue(xml, xml.contains("uid1=\"$room\" id=\"$room\"/>"))
        assertFalse(xml.contains("<marti>"))
    }

    /** What we send for a DM must parse back on the receiving side as a message from the sender to the recipient UID. */
    @Test fun directMessage_roundTripsThroughParser() {
        val parsed = ChatXml.parse(dm().xml, selfUid = "IOS-UID-9")
        assertNotNull(parsed)
        assertEquals("ANDROID-UID-1", parsed!!.senderUid)
        assertEquals("ALPHA-1", parsed.senderCallsign)
        assertEquals("IOS-UID-9", parsed.recipientUid)
        assertEquals("BRAVO-2", parsed.recipientCallsign)
        assertEquals("sitrep?", parsed.text)
        assertFalse(parsed.isFromSelf)
    }
}
