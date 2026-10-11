package soy.engindearing.omnitak.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.SelfFix

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
    fun theLimitsAreTwentySecondsOfAgeAndThirtySecondsOfHold() {
        assertEquals(20_000L, FollowTargetRules.MAX_TARGET_AGE_MS)
        assertEquals(30_000L, FollowTargetRules.MAX_HOLD_MS)
        assertEquals(FollowLimits(20_000L, 30_000L), FollowLimits())
    }

    @Test
    fun theAgeLimitIsTwoOfTheTenSecondFixIntervals() {
        // LocationProvider asks GPS for a fix every 10 s. Two of those, so a
        // healthy fix is never held for being old.
        val locationIntervalMs = 10_000L
        assertEquals(2 * locationIntervalMs, FollowTargetRules.MAX_TARGET_AGE_MS)
        // A fix that has just been replaced is at most one interval old at the
        // moment its successor lands.
        assertTrue(decide(target = point(locationIntervalMs + 1_500), terrain = 562.0) is FollowDecision.Send)
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
        val d = decide(target = point(20_001), terrain = 562.0)
        assertEquals(FollowDecision.Hold(FollowHoldReason.StaleFix, now), d)
    }

    @Test
    fun aPointExactlyAtTheLimitIsStillFresh() {
        val d = decide(target = point(20_000), terrain = 562.0)
        assertTrue(d is FollowDecision.Send)
    }

    @Test
    fun aMissingTargetIsHeldAsStale() {
        val d = decide(target = null, terrain = 562.0)
        assertEquals(FollowDecision.Hold(FollowHoldReason.StaleFix, now), d)
    }

    @Test
    fun aPointWithNoTimeIsNeverAged() {
        // A pursued contact: its position is its last report, however long ago
        // that was. It is followed, not aged.
        assertTrue(decide(target = point(null), terrain = 562.0) is FollowDecision.Send)
        assertFalse(FollowTargetRules.isStale(now, point(null)))
        assertFalse(FollowTargetRules.isStale(now + 24L * 3_600_000, point(null)))
    }

    @Test
    fun aPointWithNoTimeStillNeedsAnAltitudeReference() {
        // Not being aged does not excuse a missing reference.
        val d = decide(target = point(null), terrain = null, operatorAltitude = null)
        assertEquals(FollowDecision.Hold(FollowHoldReason.NoAltitudeReference, now), d)
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
        val d = decide(target = point(25_000), holdSince = began)
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
        // Inconsistent inputs, not something a monotonic clock does: still no
        // reason to stop early or to stop never.
        val d = decide(target = point(30_000), holdSince = now + 60_000)
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
        val d = decide(target = point(30_000), holdSince = null)
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

    // ------------------------------------- the operator's fix on the stream clock

    private val wallNow = 1_790_000_000_000L // what the device clock says
    private val elapsedNow = 5_000_000L // ms since boot, the monotonic clock

    private fun fix(
        timeMs: Long,
        elapsedRealtimeMs: Long? = null,
    ) = SelfFix(
        lat = lat,
        lon = lon,
        altitudeM = 600.0,
        speedKmh = 0.0,
        accuracyM = 5f,
        timeMs = timeMs,
        elapsedRealtimeMs = elapsedRealtimeMs,
    )

    /** What the stream decides for [fix] with a reference to fly from. */
    private fun verdict(fix: SelfFix, wall: Long = wallNow, elapsed: Long = elapsedNow): FollowDecision {
        val point = FollowTargetRules.operatorPoint(fix, wall, elapsed)
        return decide(target = point, terrain = 562.0, at = elapsed)
    }

    @Test
    fun aFixWithMonotonicTimeIsAgedOnThatClock() {
        val f = fix(timeMs = wallNow - 3_000, elapsedRealtimeMs = elapsedNow - 3_000)
        val point = FollowTargetRules.operatorPoint(f, wallNow, elapsedNow)
        assertEquals(elapsedNow - 3_000, point.timeMs)
        assertEquals(lat, point.lat, 0.0)
        assertEquals(lon, point.lon, 0.0)
        assertTrue(verdict(f) is FollowDecision.Send)
    }

    @Test
    fun aWallClockThatIsAnHourOffDoesNotMakeAMonotonicFixStale() {
        // The device clock was set back an hour, or forward: the fix was taken
        // 2 s ago by the monotonic clock whatever its wall time says.
        for (offset in listOf(-3_600_000L, 3_600_000L)) {
            val f = fix(timeMs = wallNow - 2_000 + offset, elapsedRealtimeMs = elapsedNow - 2_000)
            assertTrue("wall clock off by $offset", verdict(f) is FollowDecision.Send)
        }
    }

    @Test
    fun aMonotonicFixThatIsOldIsStaleWhateverTheWallClockSays() {
        // The wall time on the fix says "just now"; the monotonic clock says 25 s.
        val f = fix(timeMs = wallNow, elapsedRealtimeMs = elapsedNow - 25_000)
        assertEquals(
            FollowDecision.Hold(FollowHoldReason.StaleFix, elapsedNow),
            verdict(f),
        )
    }

    @Test
    fun aMonotonicFixCrossesTheLimitAsTheStreamClockMovesOn() {
        val f = fix(timeMs = wallNow, elapsedRealtimeMs = elapsedNow)
        val point = FollowTargetRules.operatorPoint(f, wallNow, elapsedNow)
        assertFalse(FollowTargetRules.isStale(elapsedNow + 20_000, point))
        assertTrue(FollowTargetRules.isStale(elapsedNow + 20_001, point))
    }

    @Test
    fun aFixWithNoMonotonicTimeFallsBackToItsWallClockTime() {
        val fresh = fix(timeMs = wallNow - 5_000, elapsedRealtimeMs = null)
        val point = FollowTargetRules.operatorPoint(fresh, wallNow, elapsedNow)
        // Its 5 s age against the wall clock, carried onto the stream's clock.
        assertEquals(elapsedNow - 5_000, point.timeMs)
        assertTrue(verdict(fresh) is FollowDecision.Send)

        val old = fix(timeMs = wallNow - 25_000, elapsedRealtimeMs = null)
        assertEquals(
            FollowDecision.Hold(FollowHoldReason.StaleFix, elapsedNow),
            verdict(old),
        )
    }

    @Test
    fun theFallbackAgeDoesNotDependOnWhereTheMonotonicClockIs() {
        val f = fix(timeMs = wallNow - 7_000, elapsedRealtimeMs = null)
        for (elapsed in listOf(7L, 5_000_000L, 9_000_000_000_000L)) {
            val point = FollowTargetRules.operatorPoint(f, wallNow, elapsed)
            assertEquals("age with the stream clock at $elapsed", 7_000L, elapsed - point.timeMs!!)
        }
    }

    @Test
    fun aFallbackFixWithNoTimeAtAllIsStale() {
        val f = fix(timeMs = 0L, elapsedRealtimeMs = null)
        assertTrue(FollowTargetRules.isStale(elapsedNow, FollowTargetRules.operatorPoint(f, wallNow, elapsedNow)))
    }

    @Test
    fun aFallbackFixFromJustAheadOfTheWallClockIsFresh() {
        val f = fix(timeMs = wallNow + 800, elapsedRealtimeMs = null)
        assertTrue(verdict(f) is FollowDecision.Send)
    }

    @Test
    fun aMonotonicFixWinsOverItsWallTimeEvenWhenTheWallTimeIsMissing() {
        // timeMs = 0 would read as ancient on its own.
        val f = fix(timeMs = 0L, elapsedRealtimeMs = elapsedNow - 1_000)
        assertTrue(verdict(f) is FollowDecision.Send)
    }
}
