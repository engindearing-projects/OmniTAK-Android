package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import soy.engindearing.omnitak.mobile.domain.FollowHoldReason

/**
 * #249: the words the operator sees when the follow stream holds or gives up.
 * A reason with no wording would show nothing; a new one fails to compile here
 * until it has some.
 */
class UasFollowMePillTest {

    @Test
    fun theHoldingPillSaysWhyNothingIsBeingSent() {
        assertEquals("Follow: waiting for GPS", followHoldLabel(FollowHoldReason.StaleFix))
        assertEquals(
            "Follow: no altitude reference",
            followHoldLabel(FollowHoldReason.NoAltitudeReference),
        )
    }

    @Test
    fun theStopMessageSaysWhyAndWhereTheDroneIs() {
        assertEquals(
            "Follow stopped: GPS fix too old — drone parked in LOITER",
            followStoppedMessage(FollowHoldReason.StaleFix),
        )
        assertEquals(
            "Follow stopped: no altitude reference — drone parked in LOITER",
            followStoppedMessage(FollowHoldReason.NoAltitudeReference),
        )
    }

    @Test
    fun everyReasonHasItsOwnWording() {
        for (reasons in listOf(FollowHoldReason.entries.map(::followHoldLabel), FollowHoldReason.entries.map(::followStoppedMessage))) {
            assertEquals(reasons.size, reasons.toSet().size)
            reasons.forEach { assertNotEquals("", it) }
        }
    }
}
