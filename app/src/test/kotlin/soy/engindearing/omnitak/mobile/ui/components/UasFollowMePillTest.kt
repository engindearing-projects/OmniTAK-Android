package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import soy.engindearing.omnitak.mobile.domain.FollowHoldReason
import soy.engindearing.omnitak.mobile.domain.UASManager

/**
 * #249: the words the operator sees when the follow stream holds or gives up.
 * They depend on who is being followed. For the operator a stale position is a
 * GPS fix that stopped arriving; for a pursued contact, which is never aged, it
 * only ever means the contact has no position, and GPS has nothing to do with it.
 *
 * A reason with no wording would show nothing; a new one fails to compile here
 * until it has some.
 */
class UasFollowMePillTest {

    private val operator = UASManager.FollowSubject.Operator
    private val contact = UASManager.FollowSubject.Contact(uid = "uid-1", callsign = "BRAVO")

    // ------------------------------------------------------------ the pill

    @Test
    fun theHoldingPillForTheOperatorSaysWhyNothingIsBeingSent() {
        assertEquals("Follow: waiting for GPS", followHoldLabel(FollowHoldReason.StaleFix, operator))
        assertEquals(
            "Follow: no altitude reference",
            followHoldLabel(FollowHoldReason.NoAltitudeReference, operator),
        )
    }

    @Test
    fun theHoldingPillForAContactSaysItsPositionIsLost() {
        assertEquals("Follow: contact position lost", followHoldLabel(FollowHoldReason.StaleFix, contact))
        assertEquals(
            "Follow: no altitude reference",
            followHoldLabel(FollowHoldReason.NoAltitudeReference, contact),
        )
    }

    @Test
    fun aMissingSubjectReadsAsTheOperator() {
        for (reason in FollowHoldReason.entries) {
            assertEquals(followHoldLabel(reason, operator), followHoldLabel(reason, null))
            assertEquals(followStoppedMessage(reason, operator), followStoppedMessage(reason, null))
        }
    }

    // --------------------------------------------------------- the stop message

    @Test
    fun theStopMessageForTheOperatorSaysWhyAndWhereTheDroneIs() {
        assertEquals(
            "Follow stopped: GPS fix too old — drone parked in LOITER",
            followStoppedMessage(FollowHoldReason.StaleFix, operator),
        )
        assertEquals(
            "Follow stopped: no altitude reference — drone parked in LOITER",
            followStoppedMessage(FollowHoldReason.NoAltitudeReference, operator),
        )
    }

    @Test
    fun theStopMessageForAContactSaysItsPositionIsLost() {
        assertEquals(
            "Follow stopped: contact position lost — drone parked in LOITER",
            followStoppedMessage(FollowHoldReason.StaleFix, contact),
        )
        assertEquals(
            "Follow stopped: no altitude reference — drone parked in LOITER",
            followStoppedMessage(FollowHoldReason.NoAltitudeReference, contact),
        )
    }

    // ------------------------------------------------------------- both together

    @Test
    fun aContactIsNeverToldAboutGps() {
        for (reason in FollowHoldReason.entries) {
            assertFalse(followHoldLabel(reason, contact).contains("GPS"))
            assertFalse(followStoppedMessage(reason, contact).contains("GPS"))
        }
    }

    @Test
    fun everyReasonAndSubjectHasItsOwnWording() {
        for (subject in listOf(operator, contact)) {
            val labels = FollowHoldReason.entries.map { followHoldLabel(it, subject) }
            val messages = FollowHoldReason.entries.map { followStoppedMessage(it, subject) }
            assertEquals(labels.size, labels.toSet().size)
            assertEquals(messages.size, messages.toSet().size)
            (labels + messages).forEach { assertNotEquals("", it) }
        }
    }

    @Test
    fun theStaleWordingDiffersBetweenTheSubjectsAndNothingElseDoes() {
        assertNotEquals(
            followHoldLabel(FollowHoldReason.StaleFix, operator),
            followHoldLabel(FollowHoldReason.StaleFix, contact),
        )
        assertNotEquals(
            followStoppedMessage(FollowHoldReason.StaleFix, operator),
            followStoppedMessage(FollowHoldReason.StaleFix, contact),
        )
        assertEquals(
            followHoldLabel(FollowHoldReason.NoAltitudeReference, operator),
            followHoldLabel(FollowHoldReason.NoAltitudeReference, contact),
        )
        assertEquals(
            followStoppedMessage(FollowHoldReason.NoAltitudeReference, operator),
            followStoppedMessage(FollowHoldReason.NoAltitudeReference, contact),
        )
    }
}
