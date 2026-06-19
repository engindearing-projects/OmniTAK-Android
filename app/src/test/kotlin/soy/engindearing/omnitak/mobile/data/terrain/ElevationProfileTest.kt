package soy.engindearing.omnitak.mobile.data.terrain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #153 — Elevation profile + Line-of-Sight math (ported from iOS
 * ElevationProfileService). Pure over a list of (distance, elevation) samples
 * so it unit-tests directly; the Android layer samples those points along the
 * drawn line with TerrainSampler and feeds them in.
 */
class ElevationProfileTest {

    private fun pts(vararg de: Pair<Double, Double>) =
        de.map { ProfilePoint(it.first, it.second) }

    @Test fun steepness_thresholds_use_absolute_grade() {
        assertEquals(Steepness.FLAT, Steepness.from(2.0))
        assertEquals(Steepness.GENTLE, Steepness.from(5.0))
        assertEquals(Steepness.MODERATE, Steepness.from(10.0))
        assertEquals(Steepness.STEEP, Steepness.from(-20.0)) // abs
        assertEquals(Steepness.VERY_STEEP, Steepness.from(30.0))
        assertEquals(Steepness.EXTREME, Steepness.from(50.0))
    }

    @Test fun stats_climb_descent_min_max_grades() {
        val s = ElevationProfile.stats(pts(0.0 to 100.0, 100.0 to 110.0, 200.0 to 105.0))
        assertEquals(100.0, s.minElevationM, 1e-9)
        assertEquals(110.0, s.maxElevationM, 1e-9)
        assertEquals(10.0, s.totalClimbM, 1e-9)
        assertEquals(5.0, s.totalDescentM, 1e-9)
        assertEquals(200.0, s.totalDistanceM, 1e-9)
        assertEquals(10.0, s.maxGradePct, 1e-9)   // |+10%| then |-5%|
        assertEquals(7.5, s.avgGradePct, 1e-9)    // mean |grade| = (10+5)/2
    }

    @Test fun gradients_segment_grade_and_category() {
        val g = ElevationProfile.gradients(pts(0.0 to 0.0, 100.0 to 20.0)) // +20% over 100m
        assertEquals(1, g.size)
        assertEquals(20.0, g[0].gradePct, 1e-9)
        assertEquals(Steepness.STEEP, g[0].steepness)
    }

    @Test fun los_clear_over_a_valley() {
        // endpoints high, dip in the middle, observers 2m AGL — clearly visible
        val r = ElevationProfile.lineOfSight(
            pts(0.0 to 100.0, 500.0 to 50.0, 1000.0 to 100.0),
            observerHeightM = 2.0, targetHeightM = 2.0, earthCurvature = false,
        )
        assertTrue(r.clear)
        assertNull(r.firstObstructionIndex)
        assertTrue(r.minClearanceM > 0)
    }

    @Test fun los_blocked_by_a_hill() {
        val r = ElevationProfile.lineOfSight(
            pts(0.0 to 100.0, 500.0 to 200.0, 1000.0 to 100.0),
            observerHeightM = 2.0, targetHeightM = 2.0, earthCurvature = false,
        )
        assertFalse(r.clear)
        assertEquals(1, r.firstObstructionIndex)
        assertEquals(500.0, r.firstObstructionDistanceM!!, 1e-9)
        assertTrue(r.minClearanceM < 0)
    }

    @Test fun los_raising_observer_clears_the_hill() {
        val profile = pts(0.0 to 100.0, 500.0 to 150.0, 1000.0 to 100.0)
        assertFalse(ElevationProfile.lineOfSight(profile, 2.0, 2.0, earthCurvature = false).clear)
        // lift both ends enough that the mid sight line (avg) tops the 150 hill
        assertTrue(ElevationProfile.lineOfSight(profile, 60.0, 60.0, earthCurvature = false).clear)
    }

    @Test fun earth_curvature_blocks_a_long_flat_shot() {
        // 30 km of flat sea-level terrain, observers on the deck
        val flat = (0..6).map { ProfilePoint(it * 5000.0, 0.0) }
        assertTrue(ElevationProfile.lineOfSight(flat, 0.0, 0.0, earthCurvature = false).clear)
        val curved = ElevationProfile.lineOfSight(flat, 0.0, 0.0, earthCurvature = true)
        assertFalse("curvature should drop the target below the horizon", curved.clear)
    }
}
