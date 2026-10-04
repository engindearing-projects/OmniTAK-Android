package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in GAP-030b — UserPrefs defaults to NaN sentinels for self lat/lon
 * so SelfPositionBroadcaster suppresses PPLI until a real GPS fix arrives.
 * Previously these defaulted to 37.7749 / -122.4194 (San Francisco), which
 * caused issue #10 (HUD showing SF for users in Germany).
 */
class UserPrefsDefaultsTest {

    @Test fun default_selfLat_is_NaN() {
        assertTrue("selfLat default must be NaN, was ${UserPrefs().selfLat}",
            UserPrefs().selfLat.isNaN())
    }

    @Test fun default_selfLon_is_NaN() {
        assertTrue("selfLon default must be NaN, was ${UserPrefs().selfLon}",
            UserPrefs().selfLon.isNaN())
    }

    @Test fun default_selfMarkerVisible_is_true() {
        // #210 - the own-marker toggle is opt-out: every existing install keeps
        // drawing its marker until the operator hides it.
        assertTrue("selfMarkerVisible must default to true", UserPrefs().selfMarkerVisible)
    }

    @Test fun hiding_the_marker_does_not_touch_position_reporting() {
        // #210 is UI only; reporting is the separate #211 switch.
        val hidden = UserPrefs().copy(selfMarkerVisible = false)
        assertTrue("reporting must stay on when only the marker is hidden", hidden.positionReportingEnabled)
        val notReporting = UserPrefs().copy(positionReportingEnabled = false)
        assertTrue("the marker must stay visible when only reporting is off", notReporting.selfMarkerVisible)
    }
}
