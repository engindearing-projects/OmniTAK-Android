package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.SelfFix

/**
 * #235: Follow-Me must not steer a vehicle toward the position the app
 * remembered from its last session.
 */
class UASManagerFollowMeTest {

    private fun fix(restored: Boolean) = SelfFix(
        lat = 10.0,
        lon = 20.0,
        altitudeM = 100.0,
        speedKmh = 0.0,
        accuracyM = 5f,
        timeMs = 1_790_000_000_000L,
        restored = restored,
    )

    private fun manager(operatorFix: () -> SelfFix?) =
        UASManager(sendCoT = { true }, operatorFix = operatorFix)

    @Test
    fun aRestoredFixIsNotAPositionToFollow() = runBlocking {
        val result = manager { fix(restored = true) }.startFollowMe()
        assertEquals(UASManager.FollowMeResult.NoGpsFix, result)
    }

    @Test
    fun noFixAtAllStillReportsNoGpsFix() = runBlocking {
        val result = manager { null }.startFollowMe()
        assertEquals(UASManager.FollowMeResult.NoGpsFix, result)
    }

    @Test
    fun aLiveFixGetsPastThePositionCheck() = runBlocking {
        // No vehicle is connected in this test, so the next check is what answers.
        val result = manager { fix(restored = false) }.startFollowMe()
        assertEquals(UASManager.FollowMeResult.NotConnected, result)
    }

    @Test
    fun aLiveFixThatReplacesARestoredOneIsAccepted() = runBlocking {
        var current = fix(restored = true)
        val uas = manager { current }
        assertEquals(UASManager.FollowMeResult.NoGpsFix, uas.startFollowMe())
        current = fix(restored = false)
        assertEquals(UASManager.FollowMeResult.NotConnected, uas.startFollowMe())
    }
}
