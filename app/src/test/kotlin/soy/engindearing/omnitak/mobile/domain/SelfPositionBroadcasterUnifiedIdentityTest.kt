package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.DeepLinkAction
import soy.engindearing.omnitak.mobile.data.PreferenceUriApplier
import soy.engindearing.omnitak.mobile.data.SelfFix
import soy.engindearing.omnitak.mobile.data.UserPrefs

/**
 * Receipts for step 2 of the unified-identity work mata sketched on
 * 2026-05-11. Step 1 (autoSync + hide-self dedup) shipped in v0.5.0 —
 * see MataAsksTest. Step 2 routes the *PPLI source* to the operator's
 * own mesh node when one is connected and reporting fresh fixes, so
 * "PLI from node, messaging from client" is realised end-to-end on
 * the TAK server side.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SelfPositionBroadcasterUnifiedIdentityTest {

    private val berlin = SelfFix(
        lat = 52.5,
        lon = 13.4,
        altitudeM = 34.0,
        speedKmh = 0.0,
        accuracyM = 5f,
        timeMs = 1_700_000_000_000L,
    )

    private val warsaw = SelfFix(
        lat = 52.23,
        lon = 21.01,
        altitudeM = 110.0,
        speedKmh = 0.0,
        accuracyM = Float.NaN,
        timeMs = 1_700_000_500_000L,
    )

    private fun broadcaster(
        phoneFix: SelfFix?,
        meshFix: SelfFix?,
        prefs: UserPrefs,
        sent: MutableList<String>,
    ): SelfPositionBroadcaster = SelfPositionBroadcaster(
        scope = CoroutineScope(Dispatchers.Unconfined),
        prefsFlow = MutableStateFlow(prefs),
        updatePrefs = { /* no-op */ },
        sendCoT = { xml -> sent += xml; true },
        locationFix = MutableStateFlow(phoneFix),
        selfMeshFix = { meshFix },
    )

    @Test fun mesh_fix_preferred_when_defer_enabled_and_mesh_present() = runTest {
        val prefs = UserPrefs(selfUid = "ANDROID-test", preferMeshFixForSelfPpli = true)
        val sent = mutableListOf<String>()
        broadcaster(phoneFix = berlin, meshFix = warsaw, prefs = prefs, sent = sent)
            .broadcastOnce(prefs)

        assertTrue("expected one PPLI", sent.size == 1)
        val xml = sent.single()
        assertTrue("Warsaw lat in XML, was: $xml", xml.contains("lat=\"52.23\""))
        assertTrue("Warsaw lon in XML", xml.contains("lon=\"21.01\""))
        assertFalse("must not contain Berlin (phone GPS) lon", xml.contains("lon=\"13.4\""))
        assertTrue(
            "geopointsrc must be Meshtastic so peers see the right provenance",
            xml.contains("geopointsrc=\"Meshtastic\""),
        )
    }

    @Test fun falls_back_to_phone_gps_when_mesh_fix_unavailable() = runTest {
        val prefs = UserPrefs(selfUid = "ANDROID-test", preferMeshFixForSelfPpli = true)
        val sent = mutableListOf<String>()
        broadcaster(phoneFix = berlin, meshFix = null, prefs = prefs, sent = sent)
            .broadcastOnce(prefs)

        val xml = sent.single()
        assertTrue("Berlin lat from phone GPS, was: $xml", xml.contains("lat=\"52.5\""))
        assertTrue(
            "geopointsrc remains GPS when the mesh-node path falls through",
            xml.contains("geopointsrc=\"GPS\""),
        )
    }

    @Test fun ignores_mesh_fix_when_defer_pref_disabled() = runTest {
        val prefs = UserPrefs(selfUid = "ANDROID-test", preferMeshFixForSelfPpli = false)
        val sent = mutableListOf<String>()
        broadcaster(phoneFix = berlin, meshFix = warsaw, prefs = prefs, sent = sent)
            .broadcastOnce(prefs)

        val xml = sent.single()
        assertTrue("Berlin lat (phone GPS) wins when defer pref is off", xml.contains("lat=\"52.5\""))
        assertFalse("Warsaw must not appear", xml.contains("lat=\"52.23\""))
        assertTrue(xml.contains("geopointsrc=\"GPS\""))
    }

    @Test fun defer_pref_pushable_via_preference_link() {
        val result = PreferenceUriApplier.apply(
            UserPrefs(),
            listOf(DeepLinkAction.PreferenceEntry("preferMeshFixForSelfPpli", "boolean", "false")),
        )
        assertFalse(result.prefs.preferMeshFixForSelfPpli)
        assertTrue(result.applied.contains("preferMeshFixForSelfPpli"))
    }

    @Test fun freshness_pref_pushable_and_clamped() {
        val accepted = PreferenceUriApplier.apply(
            UserPrefs(),
            listOf(DeepLinkAction.PreferenceEntry("selfMeshFixFreshnessSecs", "int", "120")),
        )
        assertTrue(accepted.prefs.selfMeshFixFreshnessSecs == 120)

        val tooBig = PreferenceUriApplier.apply(
            UserPrefs(),
            listOf(DeepLinkAction.PreferenceEntry("selfMeshFixFreshnessSecs", "int", "99999")),
        )
        // Out-of-range value is rejected; default survives.
        assertTrue(tooBig.prefs.selfMeshFixFreshnessSecs == 60)
        assertTrue(tooBig.ignored.contains("selfMeshFixFreshnessSecs"))
    }
}
