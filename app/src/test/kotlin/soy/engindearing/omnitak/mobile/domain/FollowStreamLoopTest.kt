package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #249: the 1 Hz follow stream against a fake host, in virtual time. The
 * decisions themselves are pinned in [FollowTargetRulesTest]; this is the loop
 * around them: what goes out each second, what the HUD is told, and that a
 * stalled fix really ends in a stop.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FollowStreamLoopTest {

    private val t0 = 1_790_000_000_000L
    private val lat = 47.6
    private val lon = -117.4

    private class FakeHost(private val clock: () -> Long) : FollowStreamLoop.Host {
        var target: () -> FollowTargetPoint? = { null }
        var terrain: () -> Double? = { null }
        var operatorAltitude: Double? = null
        var cruise = 80.0
        var terrainDelayMs = 0L

        val sent = mutableListOf<FollowDecision.Send>()
        val published = mutableListOf<FollowHealth>()
        val stops = mutableListOf<Pair<FollowHoldReason, Long>>()
        var terrainCalls = 0

        override fun nowMs() = clock()
        override fun readTarget() = target()
        override suspend fun terrainMslAt(latDeg: Double, lonDeg: Double): Double? {
            terrainCalls++
            if (terrainDelayMs > 0) delay(terrainDelayMs)
            return terrain()
        }
        override fun operatorAltitudeM() = operatorAltitude
        override fun cruiseMeters() = cruise
        override suspend fun send(target: FollowDecision.Send) { sent += target }
        override fun publish(health: FollowHealth) { published += health }
        override suspend fun stop(reason: FollowHoldReason, atMs: Long) { stops += reason to atMs }
    }

    private fun TestScope.host() = FakeHost { t0 + testScheduler.currentTime }

    /** Moves virtual time on by [ms] and runs everything due at the new time. */
    private fun TestScope.runFor(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    /** A position observed right now. */
    private fun FakeHost.live() = FollowTargetPoint(lat, lon, nowMs())

    // ---------------------------------------------------------------- steady

    @Test
    fun aFreshTargetIsSentEverySecond() = runTest {
        val h = host()
        h.target = { h.live() }
        h.terrain = { 562.0 }
        val job = backgroundScope.launch { FollowStreamLoop(h).run() }

        runFor(5_000) // ticks at 0, 1, 2, 3, 4, 5 s

        assertEquals(6, h.sent.size)
        assertTrue(h.sent.all { it.altMsl == 642.0 && it.cruiseMeters == 80.0 })
        assertEquals(setOf<FollowHealth>(FollowHealth.Streaming), h.published.toSet())
        assertTrue(h.stops.isEmpty())
        assertTrue(job.isActive)
    }

    @Test
    fun theCruiseHeightIsReadEveryTick() = runTest {
        val h = host()
        h.target = { h.live() }
        h.terrain = { 500.0 }
        backgroundScope.launch { FollowStreamLoop(h).run() }

        runFor(1_000)
        h.cruise = 120.0
        runFor(1_000)

        assertEquals(listOf(580.0, 580.0, 620.0), h.sent.map { it.altMsl })
    }

    // ----------------------------------------------------- a fix that stalls

    @Test
    fun aFixThatStopsUpdatingIsHeldAndThenTheStreamStops() = runTest {
        val h = host()
        h.operatorAltitude = 600.0
        val lastFix = t0 + 5_000 // the last fix the GPS ever delivers
        h.target = { FollowTargetPoint(lat, lon, minOf(h.nowMs(), lastFix)) }
        val job = backgroundScope.launch { FollowStreamLoop(h).run() }

        // At 15 s the fix is exactly 10 s old: still sent.
        runFor(15_000)
        assertEquals(16, h.sent.size)

        // At 16 s it is 11 s old: nothing goes out and the HUD is told.
        runFor(1_000)
        assertEquals(16, h.sent.size)
        assertEquals(FollowHealth.Holding(FollowHoldReason.StaleFix, t0 + 16_000), h.published.last())

        // The hold is 29 s old at 45 s: still nothing, still no stop.
        runFor(29_000)
        assertEquals(16, h.sent.size)
        assertTrue(h.stops.isEmpty())
        assertTrue(job.isActive)

        // 30 s of hold, at 46 s: the stream gives up, once, and ends.
        runFor(1_000)
        assertEquals(listOf(FollowHoldReason.StaleFix to t0 + 46_000), h.stops)
        assertTrue(job.isCompleted)

        // After the stop the loop does nothing at all.
        val calls = h.terrainCalls
        val published = h.published.size
        runFor(10_000)
        assertEquals(16, h.sent.size)
        assertEquals(1, h.stops.size)
        assertEquals(calls, h.terrainCalls)
        assertEquals(published, h.published.size)
    }

    @Test
    fun nothingIsSentWhileTheFixIsStale() = runTest {
        val h = host()
        h.operatorAltitude = 600.0
        val lastFix = t0 + 2_000
        h.target = { FollowTargetPoint(lat, lon, minOf(h.nowMs(), lastFix)) }
        backgroundScope.launch { FollowStreamLoop(h).run() }

        runFor(45_000)

        // Ticks 0..12 s sent (the fix is 10 s old at 12 s); 13 s on is stale.
        assertEquals(13, h.sent.size)
        assertEquals(1, h.stops.size)
    }

    @Test
    fun aFixThatComesBackBeforeTheLimitResumesTheStream() = runTest {
        val h = host()
        h.operatorAltitude = 600.0
        // Stalled from 5 s to 25 s, back until 40 s, then stalled for good.
        h.target = {
            val t = h.nowMs() - t0
            val fixAt = when {
                t < 5_000 -> t
                t < 25_000 -> 5_000
                t < 40_000 -> t
                else -> 40_000
            }
            FollowTargetPoint(lat, lon, t0 + fixAt)
        }
        backgroundScope.launch { FollowStreamLoop(h).run() }

        // Stale from 16 s to 24 s: a 9 s hold, ended by the fix at 25 s.
        runFor(30_000)
        assertEquals(FollowHealth.Streaming, h.published.last())
        assertTrue(h.stops.isEmpty())
        assertEquals(16 + 6, h.sent.size) // sent 0..15 s, held 16..24 s, sent 25..30 s
        val sentAfterRecovery = h.sent.size

        // The second stall is stale from 51 s. If the first hold had been
        // counted too, the stream would stop at 72 s. It must not.
        runFor(45_000) // to 75 s
        assertTrue(h.stops.isEmpty())
        assertTrue(h.sent.size > sentAfterRecovery)

        // Its own 30 s run out at 81 s.
        runFor(6_000)
        assertEquals(listOf(FollowHoldReason.StaleFix to t0 + 81_000), h.stops)
    }

    @Test
    fun aTargetThatVanishesIsHeldAsStale() = runTest {
        val h = host()
        h.terrain = { 500.0 }
        h.target = { if (h.nowMs() < t0 + 5_000) h.live() else null }
        backgroundScope.launch { FollowStreamLoop(h).run() }

        runFor(5_000)
        assertEquals(5, h.sent.size)
        assertEquals(FollowHealth.Holding(FollowHoldReason.StaleFix, t0 + 5_000), h.published.last())

        runFor(30_000)
        assertEquals(listOf(FollowHoldReason.StaleFix to t0 + 35_000), h.stops)
    }

    // ------------------------------------------------ no altitude reference

    @Test
    fun withNoAltitudeReferenceNothingIsEverSentAndTheStreamStops() = runTest {
        val h = host()
        h.target = { h.live() }
        h.terrain = { null }
        h.operatorAltitude = null
        val job = backgroundScope.launch { FollowStreamLoop(h).run() }

        runFor(29_000)
        assertTrue(h.sent.isEmpty())
        assertEquals(
            FollowHealth.Holding(FollowHoldReason.NoAltitudeReference, t0),
            h.published.last(),
        )
        assertTrue(h.stops.isEmpty())

        runFor(1_000)
        assertTrue(h.sent.isEmpty())
        assertEquals(listOf(FollowHoldReason.NoAltitudeReference to t0 + 30_000), h.stops)
        assertTrue(job.isCompleted)
    }

    @Test
    fun theOperatorsAltitudeTakesOverWhenTerrainIsLost() = runTest {
        val h = host()
        h.target = { h.live() }
        h.operatorAltitude = 600.0
        h.terrain = { if (h.nowMs() < t0 + 3_000) 500.0 else null }
        backgroundScope.launch { FollowStreamLoop(h).run() }

        runFor(5_000)

        assertEquals(listOf(580.0, 580.0, 580.0, 680.0, 680.0, 680.0), h.sent.map { it.altMsl })
        assertEquals(setOf<FollowHealth>(FollowHealth.Streaming), h.published.toSet())
    }

    @Test
    fun aReferenceThatComesBackEndsTheHold() = runTest {
        val h = host()
        h.target = { h.live() }
        h.terrain = { if (h.nowMs() in (t0 + 3_000) until (t0 + 10_000)) null else 500.0 }
        backgroundScope.launch { FollowStreamLoop(h).run() }

        runFor(9_000)
        assertEquals(
            FollowHealth.Holding(FollowHoldReason.NoAltitudeReference, t0 + 3_000),
            h.published.last(),
        )
        assertEquals(3, h.sent.size)

        runFor(2_000)
        assertEquals(FollowHealth.Streaming, h.published.last())
        assertEquals(5, h.sent.size)
        assertTrue(h.stops.isEmpty())
    }

    // ---------------------------------------------------------------- timing

    @Test
    fun theAgeIsJudgedAfterTheTerrainLookupNotBefore() = runTest {
        val h = host()
        // One fix, taken at t0 and never updated. The terrain lookup takes
        // 11 s; when it returns the fix is stale, though it was fresh when read.
        h.target = { FollowTargetPoint(lat, lon, t0) }
        h.terrain = { 500.0 }
        h.terrainDelayMs = 11_000
        backgroundScope.launch { FollowStreamLoop(h).run() }

        runFor(11_000)

        assertTrue(h.sent.isEmpty())
        assertEquals(FollowHealth.Holding(FollowHoldReason.StaleFix, t0 + 11_000), h.published.last())
        assertTrue(h.stops.isEmpty())
    }
}
