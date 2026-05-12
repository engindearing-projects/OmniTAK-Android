package soy.engindearing.omnitak.mobile.domain.tracking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the pure sampling rule. No coroutines, no Android Looper,
 * no real LocationProvider — that's the whole point of pulling
 * [TrackRecorder.shouldRecord] out as a companion helper.
 */
class TrackRecorderSamplingTest {

    private val config = TrackRecorderConfig()
    private val t0 = 1_700_000_000_000L

    @Test fun first_point_is_always_recorded() {
        val candidate = TrackPoint(lat = 47.0, lon = -122.0, timestampMs = t0)
        assertTrue(TrackRecorder.shouldRecord(previous = null, candidate = candidate, config = config))
    }

    @Test fun skips_when_too_soon_after_previous() {
        val prev = TrackPoint(lat = 47.0, lon = -122.0, timestampMs = t0)
        val candidate = TrackPoint(
            lat = 47.001, lon = -122.0,
            timestampMs = t0 + 500L,
        )
        assertFalse(TrackRecorder.shouldRecord(prev, candidate, config))
    }

    @Test fun skips_when_below_distance_threshold() {
        val prev = TrackPoint(lat = 47.0, lon = -122.0, timestampMs = t0)
        // ~2 m east — well under the 5 m default threshold.
        val candidate = TrackPoint(
            lat = 47.0, lon = -121.99997,
            timestampMs = t0 + 2_000L,
        )
        assertFalse(TrackRecorder.shouldRecord(prev, candidate, config))
    }

    @Test fun records_when_distance_threshold_met() {
        val prev = TrackPoint(lat = 47.0, lon = -122.0, timestampMs = t0)
        // ~11 m east — over the 5 m default threshold.
        val candidate = TrackPoint(
            lat = 47.0, lon = -121.99986,
            timestampMs = t0 + 2_000L,
        )
        assertTrue(TrackRecorder.shouldRecord(prev, candidate, config))
    }

    @Test fun force_records_after_maximum_interval_even_if_stationary() {
        val prev = TrackPoint(lat = 47.0, lon = -122.0, timestampMs = t0)
        val candidate = TrackPoint(
            lat = 47.0, lon = -122.0,
            timestampMs = t0 + 60_000L,
        )
        assertTrue(TrackRecorder.shouldRecord(prev, candidate, config))
    }
}
