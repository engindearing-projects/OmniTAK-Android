package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.SelfFix
import soy.engindearing.omnitak.mobile.data.UserPrefs

/**
 * #82 — when the operator manually repositions their self-marker, PPLI must
 * broadcast the MANUAL coordinate, not live GPS, so teammates / the TAK server
 * see them where they placed themselves (iOS PositionBroadcastService parity).
 * Clearing the override ("resume GPS") reverts to the live fix.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SelfPositionBroadcasterManualFixTest {

    private val berlin = SelfFix(
        lat = 52.5, lon = 13.4, altitudeM = 34.0,
        speedKmh = 12.0, accuracyM = 5f, timeMs = 1_700_000_000_000L,
    )

    private fun broadcaster(
        fixFlow: MutableStateFlow<SelfFix?>,
        manualFix: MutableStateFlow<SelfFix?>,
        sent: MutableList<String>,
        prefs: UserPrefs = UserPrefs(selfUid = "ANDROID-test"),
    ): SelfPositionBroadcaster = SelfPositionBroadcaster(
        scope = CoroutineScope(Dispatchers.Unconfined),
        prefsFlow = MutableStateFlow(prefs),
        updatePrefs = { /* no-op */ },
        sendCoT = { xml -> sent += xml; true },
        locationFix = fixFlow,
        manualFixProvider = { manualFix.value },
    )

    @Test fun broadcasts_manual_position_over_live_gps() = runTest {
        val fixFlow = MutableStateFlow<SelfFix?>(berlin)
        // Operator dropped themselves on a hilltop a few km away.
        val manual = SelfFix(
            lat = 52.55, lon = 13.45, altitudeM = 80.0,
            speedKmh = 0.0, accuracyM = Float.NaN, timeMs = 1_700_000_500_000L,
        )
        val manualFlow = MutableStateFlow<SelfFix?>(manual)
        val sent = mutableListOf<String>()
        val prefs = UserPrefs(selfUid = "ANDROID-test")
        broadcaster(fixFlow, manualFlow, sent, prefs).broadcastOnce(prefs)

        assertTrue("expected one PPLI", sent.size == 1)
        val xml = sent.single()
        assertTrue("manual lat in XML, was: $xml", xml.contains("lat=\"52.55\""))
        assertTrue("manual lon in XML, was: $xml", xml.contains("lon=\"13.45\""))
        // The live GPS coords must NOT leak onto the wire while manual is active.
        assertFalse("must not broadcast live GPS lat, was: $xml", xml.contains("lat=\"52.5\""))
        assertFalse("must not broadcast live GPS lon, was: $xml", xml.contains("lon=\"13.4\""))
    }

    @Test fun manual_drop_reports_unknown_circular_error() = runTest {
        // A manual drop carries accuracyM = NaN; the broadcaster must map that
        // to the "unknown" CE sentinel so peers don't read it as a precise lock.
        val fixFlow = MutableStateFlow<SelfFix?>(berlin)
        val manual = SelfFix(
            lat = 10.0, lon = 20.0, altitudeM = 0.0,
            speedKmh = 0.0, accuracyM = Float.NaN, timeMs = 1L,
        )
        val manualFlow = MutableStateFlow<SelfFix?>(manual)
        val sent = mutableListOf<String>()
        val prefs = UserPrefs(selfUid = "ANDROID-test")
        broadcaster(fixFlow, manualFlow, sent, prefs).broadcastOnce(prefs)

        val xml = sent.single()
        assertTrue("unknown CE for a manual drop, was: $xml", xml.contains("ce=\"9999999.0\""))
    }

    @Test fun reverts_to_live_gps_when_override_cleared() = runTest {
        val fixFlow = MutableStateFlow<SelfFix?>(berlin)
        val manualFlow = MutableStateFlow<SelfFix?>(
            SelfFix(lat = 1.0, lon = 2.0, altitudeM = 0.0, speedKmh = 0.0, accuracyM = Float.NaN, timeMs = 1L),
        )
        val sent = mutableListOf<String>()
        val prefs = UserPrefs(selfUid = "ANDROID-test")
        val b = broadcaster(fixFlow, manualFlow, sent, prefs)

        // First tick: manual active.
        b.broadcastOnce(prefs)
        assertTrue(sent.last().contains("lat=\"1.0\""))

        // Operator taps "resume GPS" → override cleared.
        manualFlow.value = null
        b.broadcastOnce(prefs)
        val resumed = sent.last()
        assertTrue("back to live GPS lat, was: $resumed", resumed.contains("lat=\"52.5\""))
        assertTrue("back to live GPS lon, was: $resumed", resumed.contains("lon=\"13.4\""))
    }

    @Test fun null_override_is_pure_passthrough_to_live_fix() = runTest {
        // Sanity: with no manual override, behaviour is identical to the
        // pre-#82 live-fix path (regression guard for the default lambda).
        val fixFlow = MutableStateFlow<SelfFix?>(berlin)
        val manualFlow = MutableStateFlow<SelfFix?>(null)
        val sent = mutableListOf<String>()
        val prefs = UserPrefs(selfUid = "ANDROID-test")
        broadcaster(fixFlow, manualFlow, sent, prefs).broadcastOnce(prefs)

        val xml = sent.single()
        assertTrue("live lat, was: $xml", xml.contains("lat=\"52.5\""))
        assertTrue("live lon, was: $xml", xml.contains("lon=\"13.4\""))
    }
}
