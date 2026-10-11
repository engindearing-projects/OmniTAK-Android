package soy.engindearing.omnitak.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #249: what the follow stream sends. A stale position is held and then
 * dropped, and a missing altitude reference is never filled in with sea level.
 */
class FollowTargetRulesTest {

    private val now = 1_790_000_000_000L
    private val lat = 47.6
    private val lon = -117.4

    /** A target observed [ageMs] before [now]; null = the source has no time. */
    private fun point(ageMs: Long?) = FollowTargetPoint(lat, lon, ageMs?.let { now - it })

    private fun decide(
        target: FollowTargetPoint? = point(0),
        terrain: Double? = null,
        operatorAltitude: Double? = null,
        cruise: Double = 80.0,
        holdSince: Long? = null,
        at: Long = now,
        limits: FollowLimits = FollowLimits(),
    ) = FollowTargetRules.decide(
        nowMs = at,
        target = target,
        terrainMsl = terrain,
        operatorAltitudeM = operatorAltitude,
        cruiseMeters = cruise,
        holdSinceMs = holdSince,
        limits = limits,
    )

    // ---------------------------------------------------------- the numbers

    @Test
    fun theLimitsAreTenSecondsOfAgeAndThirtySecondsOfHold() {
        assertEquals(10_000L, FollowTargetRules.MAX_TARGET_AGE_MS)
        assertEquals(30_000L, FollowTargetRules.MAX_HOLD_MS)
        assertEquals(FollowLimits(10_000L, 30_000L), FollowLimits())
    }

    // ---------------------------------------------------------------- send

    @Test
    fun aFreshPointWithTerrainIsSentAtTerrainPlusCruise() {
        val d = decide(terrain = 562.0, cruise = 80.0)
        assertEquals(FollowDecision.Send(lat, lon, altMsl = 642.0, cruiseMeters = 80.0), d)
    }

    @Test
    fun terrainIsPreferredOverTheOperatorsAltitude() {
        val d = decide(terrain = 562.0, operatorAltitude = 600.0, cruise = 80.0)
        assertEquals(642.0, (d as FollowDecision.Send).altMsl, 0.0)
    }

    @Test
    fun theOperatorsAltitudeStandsInWhenThereIsNoTerrain() {
        val d = decide(terrain = null, operatorAltitude = 600.0, cruise = 80.0)
        assertEquals(680.0, (d as FollowDecision.Send).altMsl, 0.0)
    }

    @Test
    fun aRealZeroTerrainSampleIsAReference() {
        // A coast is at sea level. Zero is a reference when it was measured,
        // and only then.
        val d = decide(terrain = 0.0, operatorAltitude = null, cruise = 80.0)
        assertEquals(80.0, (d as FollowDecision.Send).altMsl, 0.0)
    }

    @Test
    fun terrainBelowSeaLevelIsAReference() {
        val d = decide(terrain = -35.0, cruise = 50.0)
        assertEquals(15.0, (d as FollowDecision.Send).altMsl, 0.0)
    }

    @Test
    fun aNonFiniteAltitudeIsNotAReference() {
        // NaN terrain falls through to the operator's altitude...
        val viaOperator = decide(terrain = Double.NaN, operatorAltitude = 600.0, cruise = 80.0)
        assertEquals(680.0, (viaOperator as FollowDecision.Send).altMsl, 0.0)
        // ...and with nothing else there is no reference at all.
        val none = decide(terrain = Double.NaN, operatorAltitude = Double.POSITIVE_INFINITY)
        assertEquals(FollowDecision.Hold(FollowHoldReason.NoAltitudeReference, now), none)
    }

    @Test
    fun theSendCarriesTheTargetPositionUnchanged() {
        val d = decide(target = FollowTargetPoint(-33.86, 151.21, now), terrain = 10.0) as FollowDecision.Send
        assertEquals(-33.86, d.latDeg, 0.0)
        assertEquals(151.21, d.lonDeg, 0.0)
    }

    // ------------------------------------------------- no altitude reference

    @Test
    fun noReferenceIsAHoldAndNeverASend() {
        for (cruise in listOf(0.0, 10.0, 80.0, 400.0)) {
            val d = decide(terrain = null, operatorAltitude = null, cruise = cruise)
            assertEquals(FollowDecision.Hold(FollowHoldReason.NoAltitudeReference, now), d)
        }
    }

    @Test
    fun theReferenceHelperIsNullWithoutATerrainSampleOrAnAltitude() {
        assertNull(FollowTargetRules.referenceMsl(null, null))
        assertEquals(0.0, FollowTargetRules.referenceMsl(0.0, null)!!, 0.0)
        assertEquals(0.0, FollowTargetRules.referenceMsl(null, 0.0)!!, 0.0)
        assertEquals(562.0, FollowTargetRules.referenceMsl(562.0, 600.0)!!, 0.0)
    }

    // ---------------------------------------------------------------- stale

    @Test
    fun aPointOlderThanTheLimitIsStale() {
        val d = decide(target = point(10_001), terrain = 562.0)
        assertEquals(FollowDecision.Hold(FollowHoldReason.StaleFix, now), d)
    }

    @Test
    fun aPointExactlyAtTheLimitIsStillFresh() {
        val d = decide(target = point(10_000), terrain = 562.0)
        assertTrue(d is FollowDecision.Send)
    }

    @Test
    fun aMissingTargetIsHeldAsStale() {
        val d = decide(target = null, terrain = 562.0)
        assertEquals(FollowDecision.Hold(FollowHoldReason.StaleFix, now), d)
    }

    @Test
    fun aPointWithNoTimeIsNotAged() {
        // The source cannot say when it saw the point: the age check is
        // skipped, nothing is made up.
        assertTrue(decide(target = point(null), terrain = 562.0) is FollowDecision.Send)
        assertFalse(FollowTargetRules.isStale(now, point(null)))
    }

    @Test
    fun aNonPositiveTimeIsAVeryOldTime() {
        val unset = FollowTargetPoint(lat, lon, timeMs = 0L)
        assertEquals(
            FollowDecision.Hold(FollowHoldReason.StaleFix, now),
            decide(target = unset, terrain = 562.0),
        )
    }

    @Test
    fun aStalePointIsReportedAsStaleBeforeAMissingReference() {
        val d = decide(target = point(60_000), terrain = null, operatorAltitude = null)
        assertEquals(FollowHoldReason.StaleFix, (d as FollowDecision.Hold).reason)
    }

    @Test
    fun aPointFromJustAheadOfTheClockIsFresh() {
        // GPS time and the device clock disagree by a little; that is not age.
        assertTrue(decide(target = point(-800), terrain = 562.0) is FollowDecision.Send)
    }

    // ----------------------------------------------------------- hold -> stop

    @Test
    fun aHoldKeepsTheTimeItBegan() {
        val began = now - 5_000
        val d = decide(target = point(20_000), holdSince = began)
        assertEquals(FollowDecision.Hold(FollowHoldReason.StaleFix, began), d)
    }

    @Test
    fun aHoldBecomesAStopAtThirtySeconds() {
        val began = now - 30_000
        assertEquals(
            FollowDecision.Stop(FollowHoldReason.StaleFix),
            decide(target = point(40_000), holdSince = began),
        )
    }

    @Test
    fun aHoldJustUnderThirtySecondsIsStillAHold() {
        val began = now - 29_999
        assertTrue(decide(target = point(40_000), holdSince = began) is FollowDecision.Hold)
    }

    @Test
    fun aHoldPastThirtySecondsIsAStop() {
        assertTrue(decide(target = point(90_000), holdSince = now - 31_000) is FollowDecision.Stop)
    }

    @Test
    fun aMissingReferenceStopsWithItsOwnReason() {
        val d = decide(terrain = null, operatorAltitude = null, holdSince = now - 30_000)
        assertEquals(FollowDecision.Stop(FollowHoldReason.NoAltitudeReference), d)
    }

    @Test
    fun theHoldClockKeepsRunningWhenTheReasonChanges() {
        // Stale for 20 s, then a fresh fix arrives but there is still no
        // reference: that is still the same hold, 20 s old.
        val began = now - 20_000
        val d = decide(target = point(0), terrain = null, operatorAltitude = null, holdSince = began)
        assertEquals(FollowDecision.Hold(FollowHoldReason.NoAltitudeReference, began), d)
        // And when it runs out, the stop carries the reason of the moment.
        val later = decide(
            target = point(0), terrain = null, operatorAltitude = null,
            holdSince = now - 30_000,
        )
        assertEquals(FollowDecision.Stop(FollowHoldReason.NoAltitudeReference), later)
    }

    @Test
    fun aHoldStartedAheadOfTheClockCountsAsStartingNow() {
        // The device clock was set back during the hold.
        val d = decide(target = point(20_000), holdSince = now + 60_000)
        assertEquals(FollowDecision.Hold(FollowHoldReason.StaleFix, now), d)
    }

    // -------------------------------------------------------------- recovery

    @Test
    fun aFreshPointWithAReferenceEndsAHoldEvenNearTheLimit() {
        val d = decide(target = point(500), terrain = 562.0, holdSince = now - 29_000)
        assertTrue(d is FollowDecision.Send)
    }

    @Test
    fun afterARecoveryTheNextStallStartsAFreshHold() {
        // The loop passes holdSince = null after a send, so a new stall is
        // not charged with the time of the old one.
        val d = decide(target = point(20_000), holdSince = null)
        assertEquals(FollowDecision.Hold(FollowHoldReason.StaleFix, now), d)
    }

    // ------------------------------------------------------------ the limits

    @Test
    fun shorterLimitsAreHonoured() {
        val limits = FollowLimits(maxTargetAgeMs = 1_000, maxHoldMs = 2_000)
        assertTrue(decide(target = point(1_000), terrain = 1.0, limits = limits) is FollowDecision.Send)
        assertTrue(decide(target = point(1_001), terrain = 1.0, limits = limits) is FollowDecision.Hold)
        assertTrue(decide(target = point(5_000), holdSince = now - 1_999, limits = limits) is FollowDecision.Hold)
        assertTrue(decide(target = point(5_000), holdSince = now - 2_000, limits = limits) is FollowDecision.Stop)
    }
}
