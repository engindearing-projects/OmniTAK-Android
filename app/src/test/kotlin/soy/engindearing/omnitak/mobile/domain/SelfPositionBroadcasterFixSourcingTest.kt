package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.SelfFix
import soy.engindearing.omnitak.mobile.data.UserPrefs

/**
 * GAP-030b regression — PPLI broadcasts must come from the live GPS fix,
 * not the stale San Francisco prefs default. Issue #10: HUD showed SF for
 * users in Germany.
 *
 * #205 — and not from the position restored from the previous session
 * either: it is drawn on the map but PPLI holds back until a live fix
 * replaces it, then sends at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SelfPositionBroadcasterFixSourcingTest {

    private fun broadcaster(
        fixFlow: MutableStateFlow<SelfFix?>,
        sent: MutableList<String>,
        prefs: UserPrefs = UserPrefs(selfUid = "ANDROID-test"),
    ): SelfPositionBroadcaster = SelfPositionBroadcaster(
        scope = CoroutineScope(Dispatchers.Unconfined),
        prefsFlow = MutableStateFlow(prefs),
        updatePrefs = { /* no-op for these unit tests */ },
        sendCoT = { xml -> sent += xml; true },
        locationFix = fixFlow,
    )

    @Test fun no_broadcast_when_fix_null_and_prefs_NaN() = runTest {
        val fixFlow = MutableStateFlow<SelfFix?>(null)
        val sent = mutableListOf<String>()
        // UserPrefs() defaults selfLat/selfLon to NaN per GAP-030b.
        val prefs = UserPrefs(selfUid = "ANDROID-test")
        broadcaster(fixFlow, sent, prefs).broadcastOnce(prefs)
        assertTrue("no PPLI should be sent without a fix", sent.isEmpty())
    }

    @Test fun broadcasts_berlin_coords_when_fix_set() = runTest {
        val berlin = SelfFix(
            lat = 52.5,
            lon = 13.4,
            altitudeM = 34.0,
            speedKmh = 0.0,
            accuracyM = 5f,
            timeMs = 1_700_000_000_000L,
        )
        val fixFlow = MutableStateFlow<SelfFix?>(berlin)
        val sent = mutableListOf<String>()
        val prefs = UserPrefs(selfUid = "ANDROID-test")
        broadcaster(fixFlow, sent, prefs).broadcastOnce(prefs)
        assertTrue("expected one PPLI", sent.size == 1)
        val xml = sent.single()
        assertTrue("Berlin lat in XML, was: $xml", xml.contains("lat=\"52.5\""))
        assertTrue("Berlin lon in XML, was: $xml", xml.contains("lon=\"13.4\""))
        assertFalse("must not contain SF lat", xml.contains("37.77"))
        assertFalse("must not contain SF lon", xml.contains("-122.4"))
    }

    @Test fun never_broadcasts_persisted_prefs_position_when_fix_null() = runTest {
        // #205 inverts the old #75 behaviour: selfLat/selfLon in prefs are
        // only the store the map's restored seed is rebuilt from. Reading
        // them as a second PPLI source leaked a stale position, stamped
        // "now", whenever the in-memory fix was still null.
        val fixFlow = MutableStateFlow<SelfFix?>(null)
        val sent = mutableListOf<String>()
        val prefs = UserPrefs(
            selfUid = "ANDROID-test",
            selfLat = 47.6062,
            selfLon = -117.4194,
            selfHae = 562.0,
            selfFixTimeMs = 1_700_000_000_000L,
        )
        broadcaster(fixFlow, sent, prefs).broadcastOnce(prefs)
        assertTrue("persisted prefs must never be broadcast, sent: $sent", sent.isEmpty())
    }

    @Test fun holds_restored_fix_until_live() = runTest {
        val restored = SelfFix(
            lat = 47.6062, lon = -117.4194, altitudeM = 562.0,
            speedKmh = 0.0, accuracyM = Float.NaN, timeMs = 1_700_000_000_000L,
            restored = true,
        )
        val live = SelfFix(
            lat = 52.5, lon = 13.4, altitudeM = 34.0,
            speedKmh = 4.0, accuracyM = 5f, timeMs = 1_700_003_600_000L,
        )
        val fixFlow = MutableStateFlow<SelfFix?>(restored)
        val sent = mutableListOf<String>()
        val meshSent = mutableListOf<CoTEvent>()
        val prefs = UserPrefs(selfUid = "ANDROID-test")
        val b = SelfPositionBroadcaster(
            scope = CoroutineScope(Dispatchers.Unconfined),
            prefsFlow = MutableStateFlow(prefs),
            updatePrefs = { /* no-op */ },
            sendCoT = { xml -> sent += xml; true },
            locationFix = fixFlow,
            sendToMesh = { event -> meshSent += event; true },
            meshConnected = { true },
            meshBroadcastEnabled = { true },
            meshThrottleMs = { 0L },
        )

        // Two ticks with only the restored fix: nothing on the server link,
        // nothing on the mesh.
        b.broadcastOnce(prefs)
        b.broadcastOnce(prefs)
        assertTrue("restored fix must not go to the server, sent: $sent", sent.isEmpty())
        assertTrue("restored fix must not go to the mesh, sent: $meshSent", meshSent.isEmpty())

        // First live fix replaces it: the next send carries live coordinates
        // and nothing from the restored position.
        fixFlow.value = live
        b.broadcastOnce(prefs)
        assertEquals("one server PPLI once live", 1, sent.size)
        assertEquals("one mesh PPLI once live", 1, meshSent.size)
        val xml = sent.single()
        assertTrue("live lat in XML, was: $xml", xml.contains("lat=\"52.5\""))
        assertTrue("live lon in XML, was: $xml", xml.contains("lon=\"13.4\""))
        assertFalse("restored lat must not leak, was: $xml", xml.contains("47.6062"))
        assertFalse("restored lon must not leak, was: $xml", xml.contains("-117.4194"))
        assertEquals(52.5, meshSent.single().lat, 0.0)
        assertEquals(13.4, meshSent.single().lon, 0.0)
    }

    @Test fun sends_immediately_on_first_live_fix() = runTest {
        // The loop only ticks every intervalMs. A send skipped at start
        // because only the restored fix existed must not wait out the rest
        // of the interval once a live fix lands, or the server keeps the old
        // position for up to 30 s after the link comes up.
        val restored = SelfFix(
            lat = 47.6062, lon = -117.4194, altitudeM = 562.0,
            speedKmh = 0.0, accuracyM = Float.NaN, timeMs = 1_700_000_000_000L,
            restored = true,
        )
        val live = SelfFix(
            lat = 52.5, lon = 13.4, altitudeM = 34.0,
            speedKmh = 0.0, accuracyM = 5f, timeMs = 1_700_003_600_000L,
        )
        val fixFlow = MutableStateFlow<SelfFix?>(restored)
        val sent = mutableListOf<String>()
        val b = SelfPositionBroadcaster(
            scope = backgroundScope,
            prefsFlow = MutableStateFlow(UserPrefs(selfUid = "ANDROID-test")),
            updatePrefs = { /* no-op */ },
            sendCoT = { xml -> sent += xml; true },
            locationFix = fixFlow,
            intervalMs = 30_000L,
        )

        b.start()
        runCurrent()
        assertTrue("restored fix must not go out when the link comes up", sent.isEmpty())

        advanceTimeBy(10_000L)
        runCurrent()
        assertTrue("still held 10 s in, no live fix yet", sent.isEmpty())

        // Live fix lands 10 s into the interval: sent right away, with no
        // further virtual time passing.
        fixFlow.value = live
        runCurrent()
        assertEquals("live fix goes out at once, not at the next tick", 1, sent.size)
        assertTrue("live lat, was: ${sent.single()}", sent.single().contains("lat=\"52.5\""))

        // Regular cadence resumes from that send: nothing at +29.999 s,
        // the next one at +30 s.
        advanceTimeBy(29_999L)
        runCurrent()
        assertEquals("no extra send inside the interval", 1, sent.size)
        advanceTimeBy(2L)
        runCurrent()
        assertEquals("next scheduled send 30 s after the immediate one", 2, sent.size)
        b.stop()
    }

    @Test fun sends_immediately_when_first_fix_ever_arrives() = runTest {
        // No persisted fix at all (fresh install, GPS still acquiring): the
        // same early send applies, not just the restored-seed case.
        val live = SelfFix(
            lat = 52.5, lon = 13.4, altitudeM = 34.0,
            speedKmh = 0.0, accuracyM = 5f, timeMs = 1_700_003_600_000L,
        )
        val fixFlow = MutableStateFlow<SelfFix?>(null)
        val sent = mutableListOf<String>()
        val b = SelfPositionBroadcaster(
            scope = backgroundScope,
            prefsFlow = MutableStateFlow(UserPrefs(selfUid = "ANDROID-test")),
            updatePrefs = { /* no-op */ },
            sendCoT = { xml -> sent += xml; true },
            locationFix = fixFlow,
            intervalMs = 30_000L,
        )

        b.start()
        runCurrent()
        assertTrue("nothing to send without a fix", sent.isEmpty())

        advanceTimeBy(5_000L)
        fixFlow.value = live
        runCurrent()
        assertEquals("first fix goes out at once", 1, sent.size)
        b.stop()
    }

    @Test fun never_broadcasts_san_francisco_when_fix_null() = runTest {
        val fixFlow = MutableStateFlow<SelfFix?>(null)
        val sent = mutableListOf<String>()
        // Even if some legacy migration left non-NaN prefs, the
        // regression is about the *default*. Verify by leaving prefs at
        // their NaN defaults and asserting nothing went out.
        val prefs = UserPrefs(selfUid = "ANDROID-test")
        broadcaster(fixFlow, sent, prefs).broadcastOnce(prefs)
        assertNull("no XML should have been sent", sent.firstOrNull())
        // And as a paranoid belt-and-suspenders, no message anywhere
        // contains the old hardcoded SF coords.
        assertTrue(sent.none { it.contains("37.77") || it.contains("-122.4") })
    }
}
