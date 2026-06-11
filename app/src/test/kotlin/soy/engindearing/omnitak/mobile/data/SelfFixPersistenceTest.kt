package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #75 — self-marker persistence rules. The marker used to vanish
 * after screen-off and stay gone until GPS reacquired; these lock the
 * pure logic behind the fix: persisted-fix restore (write/read), the
 * 30 s staleness boundary, the DataStore write throttle, and the
 * newer-wins merge that keeps a stale restored fix from ever clobbering
 * live GPS.
 */
class SelfFixPersistenceTest {

    private val t0 = 1_700_000_000_000L

    private fun fix(timeMs: Long, lat: Double = 47.6, lon: Double = -117.4) = SelfFix(
        lat = lat,
        lon = lon,
        altitudeM = 120.5,
        speedKmh = 3.2,
        accuracyM = 5f,
        timeMs = timeMs,
    )

    // ── restore (read side of persistence) ──────────────────────────────

    @Test fun restore_returns_null_for_default_prefs() {
        assertNull(
            "NaN sentinels (no fix ever persisted) must restore to null",
            SelfFixPersistence.restoredFixOrNull(UserPrefs()),
        )
    }

    @Test fun restore_returns_null_when_only_one_coordinate_present() {
        assertNull(SelfFixPersistence.restoredFixOrNull(UserPrefs(selfLat = 47.6)))
        assertNull(SelfFixPersistence.restoredFixOrNull(UserPrefs(selfLon = -117.4)))
    }

    @Test fun restore_round_trips_position_and_time() {
        val prefs = UserPrefs(
            selfLat = 47.6062,
            selfLon = -117.4194,
            selfHae = 562.0,
            selfFixTimeMs = t0,
        )
        val restored = SelfFixPersistence.restoredFixOrNull(prefs)
        assertNotNull(restored)
        assertEquals(47.6062, restored!!.lat, 0.0)
        assertEquals(-117.4194, restored.lon, 0.0)
        assertEquals(562.0, restored.altitudeM, 0.0)
        assertEquals(t0, restored.timeMs)
    }

    @Test fun restored_fix_never_claims_live_confidence() {
        val restored = SelfFixPersistence.restoredFixOrNull(
            UserPrefs(selfLat = 47.6, selfLon = -117.4, selfHae = 562.0, selfFixTimeMs = t0),
        )!!
        // NaN accuracy → the PPLI broadcaster emits ce=9999999 ("unknown"),
        // and zero speed — a restored position must not look like live GPS.
        assertTrue("restored accuracy must be NaN", restored.accuracyM.isNaN())
        assertEquals(0.0, restored.speedKmh, 0.0)
    }

    @Test fun restore_maps_NaN_hae_to_zero() {
        val restored = SelfFixPersistence.restoredFixOrNull(
            UserPrefs(selfLat = 47.6, selfLon = -117.4, selfFixTimeMs = t0),
        )!!
        assertEquals(0.0, restored.altitudeM, 0.0)
    }

    // ── write side of persistence (UserPrefs carries the fix) ───────────

    @Test fun prefs_default_to_no_persisted_fix() {
        assertTrue("selfHae default must be NaN", UserPrefs().selfHae.isNaN())
        assertEquals("selfFixTimeMs default must be 0", 0L, UserPrefs().selfFixTimeMs)
    }

    @Test fun prefs_copy_round_trips_a_fix_write() {
        // Mirrors UserPrefsStore.setLastSelfFix's copy() — the DataStore
        // string/long codec is exercised by toString/toDoubleOrNull below.
        val f = fix(t0)
        val written = UserPrefs().copy(
            selfLat = f.lat, selfLon = f.lon, selfHae = f.altitudeM, selfFixTimeMs = f.timeMs,
        )
        assertEquals(f.lat, written.selfLat.toString().toDouble(), 0.0)
        assertEquals(f.lon, written.selfLon.toString().toDouble(), 0.0)
        assertEquals(f.altitudeM, written.selfHae.toString().toDouble(), 0.0)
        val restored = SelfFixPersistence.restoredFixOrNull(written)!!
        assertEquals(f.lat, restored.lat, 0.0)
        assertEquals(f.timeMs, restored.timeMs)
    }

    // ── staleness (~30 s boundary) ──────────────────────────────────────

    @Test fun fix_is_fresh_within_30_seconds() {
        assertFalse(SelfFixPersistence.isStale(fixTimeMs = t0, nowMs = t0))
        assertFalse(SelfFixPersistence.isStale(fixTimeMs = t0, nowMs = t0 + 29_999L))
        assertFalse(SelfFixPersistence.isStale(fixTimeMs = t0, nowMs = t0 + 30_000L))
    }

    @Test fun fix_is_stale_after_30_seconds() {
        assertTrue(SelfFixPersistence.isStale(fixTimeMs = t0, nowMs = t0 + 30_001L))
        assertTrue(SelfFixPersistence.isStale(fixTimeMs = t0, nowMs = t0 + 3_600_000L))
    }

    @Test fun unknown_fix_time_is_always_stale() {
        assertTrue(SelfFixPersistence.isStale(fixTimeMs = 0L, nowMs = t0))
        assertTrue(SelfFixPersistence.isStale(fixTimeMs = -1L, nowMs = t0))
    }

    @Test fun clock_skewed_future_fix_is_not_stale() {
        assertFalse(SelfFixPersistence.isStale(fixTimeMs = t0 + 60_000L, nowMs = t0))
    }

    // ── persist throttle ────────────────────────────────────────────────

    @Test fun first_fix_always_persists() {
        assertTrue(SelfFixPersistence.shouldPersist(fixTimeMs = t0, lastPersistedFixTimeMs = 0L))
    }

    @Test fun same_or_older_fix_never_persists() {
        assertFalse(SelfFixPersistence.shouldPersist(fixTimeMs = t0, lastPersistedFixTimeMs = t0))
        assertFalse(
            SelfFixPersistence.shouldPersist(fixTimeMs = t0 - 1L, lastPersistedFixTimeMs = t0),
        )
    }

    @Test fun fix_inside_throttle_window_is_skipped() {
        assertFalse(
            "a fix 10 s after the last persisted one must be throttled",
            SelfFixPersistence.shouldPersist(
                fixTimeMs = t0 + 10_000L, lastPersistedFixTimeMs = t0,
            ),
        )
    }

    @Test fun fix_past_throttle_window_persists() {
        assertTrue(
            SelfFixPersistence.shouldPersist(
                fixTimeMs = t0 + 15_000L, lastPersistedFixTimeMs = t0,
            ),
        )
    }

    // ── newer-wins merge ────────────────────────────────────────────────

    @Test fun candidate_lands_when_no_current_fix() {
        val seed = fix(t0)
        assertSame(seed, SelfFixPersistence.newerOf(null, seed))
    }

    @Test fun newer_candidate_replaces_current() {
        val old = fix(t0)
        val fresh = fix(t0 + 5_000L)
        assertSame(fresh, SelfFixPersistence.newerOf(old, fresh))
    }

    @Test fun stale_seed_never_clobbers_live_fix() {
        val live = fix(t0)
        val persistedSeed = fix(t0 - 3_600_000L)
        assertSame(
            "an hour-old restored fix must not replace live GPS",
            live,
            SelfFixPersistence.newerOf(live, persistedSeed),
        )
    }

    @Test fun equal_timestamp_prefers_candidate() {
        // Live updates can legitimately re-deliver the same instant —
        // the latest delivery wins so the flow keeps moving.
        val a = fix(t0, lat = 1.0)
        val b = fix(t0, lat = 2.0)
        assertSame(b, SelfFixPersistence.newerOf(a, b))
    }
}
