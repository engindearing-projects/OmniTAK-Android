package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.SelfFix
import soy.engindearing.omnitak.mobile.data.UserPrefs

/**
 * Off-grid mesh PPLI plan Step 1 unit tests.
 *
 * Verifies the mesh send path in [SelfPositionBroadcaster]:
 *  - skipped when no mesh connection (meshConnected = false)
 *  - skipped when toggle disabled (meshBroadcastEnabled = false)
 *  - fires correct CoT type + callsign + team when connected
 *  - throttle suppresses second send within the window
 *  - resumes after window elapses
 *  - server PPLI (sendCoT) always fires regardless of mesh state
 *  - #211: the master "Report my position" switch gates BOTH sends
 */
class SelfPositionBroadcasterMeshTest {

    private val testLocation = SelfFix(lat = 47.66, lon = -117.43, altitudeM = 600.0, speedKmh = 0.0, accuracyM = 5.0f, timeMs = 0L)
    private val testPrefs = UserPrefs(
        callsign = "ALPHA-1",
        team = "Cyan",
        selfUid = "ANDROID-test-uid",
        selfLat = 47.66,
        selfLon = -117.43,
    )

    /** Build a broadcaster under test with explicit control knobs. */
    private fun makeBroadcaster(
        scope: TestScope,
        sendCoT: suspend (String) -> Boolean = { true },
        meshConnected: () -> Boolean = { true },
        meshBroadcastEnabled: () -> Boolean = { true },
        meshThrottleMs: Long = 10_000L,
        positionReportingEnabled: () -> Boolean = { true },
        capturedMeshEvents: MutableList<CoTEvent> = mutableListOf(),
    ): SelfPositionBroadcaster {
        val fixFlow = MutableStateFlow<SelfFix?>(testLocation)
        val prefsFlow = MutableStateFlow(testPrefs)
        return SelfPositionBroadcaster(
            scope = scope,
            prefsFlow = prefsFlow,
            updatePrefs = { _ -> },
            sendCoT = sendCoT,
            locationFix = fixFlow,
            batteryProvider = { null },
            intervalMs = 5_000L,
            staleSeconds = 180L,
            sendToMesh = { event ->
                capturedMeshEvents.add(event)
                true
            },
            meshConnected = meshConnected,
            meshBroadcastEnabled = meshBroadcastEnabled,
            meshThrottleMs = { meshThrottleMs },
            positionReportingEnabled = positionReportingEnabled,
        )
    }

    @Test
    fun `mesh send skipped when disconnected`() = runTest {
        val meshEvents = mutableListOf<CoTEvent>()
        val b = makeBroadcaster(
            scope = this,
            meshConnected = { false },
            capturedMeshEvents = meshEvents,
        )
        b.broadcastOnce(testPrefs)
        assertEquals("no mesh send when disconnected", 0, meshEvents.size)
    }

    @Test
    fun `mesh send skipped when toggle off`() = runTest {
        val meshEvents = mutableListOf<CoTEvent>()
        val b = makeBroadcaster(
            scope = this,
            meshConnected = { true },
            meshBroadcastEnabled = { false },
            capturedMeshEvents = meshEvents,
        )
        b.broadcastOnce(testPrefs)
        assertEquals("no mesh send when toggle off", 0, meshEvents.size)
    }

    @Test
    fun `mesh send fires with correct type callsign and team`() = runTest {
        val meshEvents = mutableListOf<CoTEvent>()
        val b = makeBroadcaster(
            scope = this,
            meshConnected = { true },
            meshBroadcastEnabled = { true },
            meshThrottleMs = 0L,
            capturedMeshEvents = meshEvents,
        )
        b.broadcastOnce(testPrefs)
        assertEquals("expected one mesh event", 1, meshEvents.size)
        val ev = meshEvents[0]
        assertEquals("a-f-G-U-C", ev.type)
        assertEquals("ALPHA-1", ev.callsign)
        assertEquals("Cyan", ev.teamName)
        assertEquals("ANDROID-test-uid", ev.uid)
    }

    @Test
    fun `throttle suppresses second send within window`() = runTest {
        val meshEvents = mutableListOf<CoTEvent>()
        val throttleMs = 10_000L
        val b = makeBroadcaster(
            scope = this,
            meshConnected = { true },
            meshBroadcastEnabled = { true },
            meshThrottleMs = throttleMs,
            capturedMeshEvents = meshEvents,
        )
        b.broadcastOnce(testPrefs)
        assertEquals("first send fires", 1, meshEvents.size)
        // Second call immediately — should be suppressed
        b.broadcastOnce(testPrefs)
        assertEquals("second within throttle suppressed", 1, meshEvents.size)
    }

    @Test
    fun `mesh send resumes after throttle window`() = runTest {
        val meshEvents = mutableListOf<CoTEvent>()
        val b = makeBroadcaster(
            scope = this,
            meshConnected = { true },
            meshBroadcastEnabled = { true },
            meshThrottleMs = 0L, // zero throttle for simplicity
            capturedMeshEvents = meshEvents,
        )
        b.broadcastOnce(testPrefs)
        assertEquals("first send", 1, meshEvents.size)
        b.broadcastOnce(testPrefs)
        assertEquals("second send after zero-throttle window", 2, meshEvents.size)
    }

    @Test
    fun `server sendCoT always fires regardless of mesh state`() = runTest {
        val serverSends = mutableListOf<String>()
        val b = makeBroadcaster(
            scope = this,
            sendCoT = { xml -> serverSends.add(xml); true },
            meshConnected = { false },
        )
        b.broadcastOnce(testPrefs)
        assertEquals("server send fires even with mesh disconnected", 1, serverSends.size)
        assertTrue("XML is a CoT event", serverSends[0].contains("<event"))
    }

    @Test
    fun no_send_when_position_reporting_disabled() = runTest {
        // #211 - one switch, both paths: with "Report my position" off neither
        // the server PPLI nor the mesh PPLI goes out, even with a fix, a
        // connected radio, the mesh toggle on and no throttle in the way.
        val serverSends = mutableListOf<String>()
        val meshEvents = mutableListOf<CoTEvent>()
        var reporting = false
        val b = makeBroadcaster(
            scope = this,
            sendCoT = { xml -> serverSends.add(xml); true },
            meshConnected = { true },
            meshBroadcastEnabled = { true },
            meshThrottleMs = 0L,
            positionReportingEnabled = { reporting },
            capturedMeshEvents = meshEvents,
        )

        b.broadcastOnce(testPrefs)
        b.broadcastOnce(testPrefs)
        assertEquals("no server PPLI while reporting is off", 0, serverSends.size)
        assertEquals("no mesh PPLI while reporting is off", 0, meshEvents.size)

        // Switched back on: the running broadcaster picks it up on its next
        // tick, no restart, and both paths fire again.
        reporting = true
        b.broadcastOnce(testPrefs)
        assertEquals("server PPLI resumes when reporting is switched back on", 1, serverSends.size)
        assertEquals("mesh PPLI resumes when reporting is switched back on", 1, meshEvents.size)
    }
}
