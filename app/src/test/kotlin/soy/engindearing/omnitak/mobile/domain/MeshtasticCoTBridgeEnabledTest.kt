package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.MeshNode
import soy.engindearing.omnitak.mobile.data.MeshPosition

/**
 * Verifies the Phase 3 auto-publish toggle wired through
 * [MeshtasticCoTBridge.enabled]. When the bridge is disabled, node
 * updates flowing through the [MeshtasticManager.nodes] StateFlow must
 * NOT reach the cotSink. Re-enabling the bridge should resume forwarding
 * subsequent updates.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshtasticCoTBridgeEnabledTest {

    private fun positionedNode(id: Long, lat: Double, lon: Double, lastHeard: Long = 1) = MeshNode(
        id = id,
        shortName = "N${"%04X".format(id.toInt() and 0xFFFF)}",
        longName = "Node $id",
        position = MeshPosition(lat = lat, lon = lon, altitudeM = 0),
        lastHeardEpoch = lastHeard,
    )

    @Test
    fun disabling_bridge_stops_cotSink_calls() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val mgr = MeshtasticManager(context = null)
        val received = mutableListOf<CoTEvent>()
        val scope = CoroutineScope(dispatcher)
        val bridge = MeshtasticCoTBridge(
            mesh = mgr,
            cotSink = { received += it },
            scope = scope,
        )
        bridge.start()

        // First node — bridge is enabled by default, should propagate.
        mgr.upsertNode(positionedNode(1L, 37.42, -122.08, lastHeard = 100))
        advanceUntilIdle()
        assertEquals("first node should reach cotSink", 1, received.size)

        // Flip the toggle off and inject a different node — the sink
        // must not see it.
        bridge.enabled = false
        mgr.upsertNode(positionedNode(2L, 37.43, -122.09, lastHeard = 200))
        advanceUntilIdle()
        assertEquals(
            "no events should be delivered while disabled",
            1,
            received.size,
        )

        // Flipping back on lets a new node through.
        bridge.enabled = true
        mgr.upsertNode(positionedNode(3L, 37.44, -122.10, lastHeard = 300))
        advanceUntilIdle()
        assertTrue(
            "events should resume flowing once re-enabled",
            received.size >= 2,
        )

        bridge.stop()
    }

    @Test
    fun bridge_defaults_to_enabled() {
        val mgr = MeshtasticManager(context = null)
        val bridge = MeshtasticCoTBridge(
            mesh = mgr,
            cotSink = { /* unused */ },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        assertTrue("auto-publish must default to on for backwards compat", bridge.enabled)
        bridge.stop()
    }
}
