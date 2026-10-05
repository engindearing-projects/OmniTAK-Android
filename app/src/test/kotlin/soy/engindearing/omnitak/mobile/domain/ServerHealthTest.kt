package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.ConnectionProtocol
import soy.engindearing.omnitak.mobile.data.ServerStoreApi
import soy.engindearing.omnitak.mobile.data.TAKServer
import soy.engindearing.omnitak.mobile.domain.AggregateHealth.AMBER
import soy.engindearing.omnitak.mobile.domain.AggregateHealth.GREEN
import soy.engindearing.omnitak.mobile.domain.AggregateHealth.RED
import soy.engindearing.omnitak.mobile.domain.ServerHealth.CONNECTED
import soy.engindearing.omnitak.mobile.domain.ServerHealth.CONNECTING
import soy.engindearing.omnitak.mobile.domain.ServerHealth.DISABLED
import soy.engindearing.omnitak.mobile.domain.ServerHealth.DISCONNECTED
import soy.engindearing.omnitak.mobile.domain.ServerHealth.FAILED
import kotlin.coroutines.ContinuationInterceptor

/**
 * #209: the map's top bar and the Servers screen must read the same health
 * for the same [ServerManager] state. The pure projection is tested directly;
 * the two ServerManager tests (FakeServerStore + TestScope, same pattern as
 * [ServerManagerToggleTest]) check it against the real state flows the UI
 * collects.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerHealthTest {

    private val up = ConnectionState.Connected("A", useTLS = true)
    private val dialling = ConnectionState.Connecting("A")
    private val failed = ConnectionState.Failed("timeout")
    private val dropped = ConnectionState.Disconnected

    private fun server(id: String, enabled: Boolean = true) = TAKServer(
        id = id,
        name = id,
        host = "10.0.2.2",
        port = 8089,
        enabled = enabled,
    )

    private fun aggregate(vararg healths: ServerHealth) =
        ServerHealthProjection.aggregate(healths.toList())

    // region per-server projection --------------------------------------------

    @Test
    fun `a disabled server is DISABLED whatever state entry is left behind`() {
        for (state in listOf(null, dropped, dialling, up, failed)) {
            assertEquals(
                "state=$state",
                DISABLED,
                ServerHealthProjection.project(enabled = false, state = state),
            )
        }
    }

    @Test
    fun `an enabled server projects each connection state`() {
        assertEquals(CONNECTED, ServerHealthProjection.project(true, up))
        assertEquals(CONNECTING, ServerHealthProjection.project(true, dialling))
        assertEquals(FAILED, ServerHealthProjection.project(true, failed))
        assertEquals(DISCONNECTED, ServerHealthProjection.project(true, dropped))
    }

    @Test
    fun `an enabled server with no state entry is DISCONNECTED`() {
        // ServerManager.disconnect(id) (the row's Stop button) removes the entry.
        assertEquals(DISCONNECTED, ServerHealthProjection.project(true, null))
    }

    @Test
    fun `only CONNECTED and CONNECTING offer Stop`() {
        assertTrue(CONNECTED.isLive)
        assertTrue(CONNECTING.isLive)
        assertFalse(FAILED.isLive)
        assertFalse(DISCONNECTED.isLive)
        assertFalse(DISABLED.isLive)
    }

    // region aggregate --------------------------------------------------------

    @Test
    fun `no servers reads red`() {
        assertEquals(RED, aggregate())
    }

    @Test
    fun `only disabled servers reads red`() {
        assertEquals(RED, aggregate(DISABLED, DISABLED))
    }

    @Test
    fun `a lone server is green only when connected`() {
        assertEquals(GREEN, aggregate(CONNECTED))
        assertEquals(RED, aggregate(CONNECTING))
        assertEquals(RED, aggregate(FAILED))
        assertEquals(RED, aggregate(DISCONNECTED))
    }

    @Test
    fun `every enabled server connected reads green`() {
        assertEquals(GREEN, aggregate(CONNECTED, CONNECTED, CONNECTED))
    }

    @Test
    fun `one up and one failed reads amber (the 209 case)`() {
        assertEquals(AMBER, aggregate(CONNECTED, FAILED))
        assertEquals(AMBER, aggregate(FAILED, CONNECTED))
    }

    @Test
    fun `one up and one connecting or stopped reads amber`() {
        assertEquals(AMBER, aggregate(CONNECTED, CONNECTING))
        assertEquals(AMBER, aggregate(CONNECTED, DISCONNECTED))
    }

    @Test
    fun `none connected reads red even when some are still trying`() {
        assertEquals(RED, aggregate(FAILED, CONNECTING))
        assertEquals(RED, aggregate(FAILED, FAILED))
        assertEquals(RED, aggregate(CONNECTING, DISCONNECTED))
    }

    @Test
    fun `disabled servers do not turn a green bar amber`() {
        assertEquals(GREEN, aggregate(CONNECTED, DISABLED))
        assertEquals(AMBER, aggregate(CONNECTED, DISABLED, FAILED))
    }

    @Test
    fun `aggregate invariants hold for every combination of up to three servers`() {
        fun assertInvariants(combo: List<ServerHealth>) {
            val agg = ServerHealthProjection.aggregate(combo)
            val enabled = combo.filter { it != DISABLED }
            val connected = enabled.count { it == CONNECTED }
            when (agg) {
                GREEN -> assertTrue(
                    "GREEN needs at least one enabled server and all of them up: $combo",
                    enabled.isNotEmpty() && connected == enabled.size,
                )
                AMBER -> assertTrue(
                    "AMBER needs some, but not all, enabled servers up: $combo",
                    connected in 1 until enabled.size,
                )
                RED -> assertEquals("RED means no enabled server is up: $combo", 0, connected)
            }
            // The #209 symptom: a failed (red) row under a green bar.
            if (enabled.any { it == FAILED }) {
                assertTrue("a Failed server must never leave the bar green: $combo", agg != GREEN)
            }
        }

        val all = ServerHealth.entries
        assertInvariants(emptyList())
        for (a in all) {
            assertInvariants(listOf(a))
            for (b in all) {
                assertInvariants(listOf(a, b))
                for (c in all) assertInvariants(listOf(a, b, c))
            }
        }
    }

    // region summarize (what the bar shows) -----------------------------------

    @Test
    fun `summarize counts enabled servers only and keeps list order`() {
        val servers = listOf(server("a"), server("b", enabled = false), server("c"))
        // "b" still has a Connected entry: a stale leftover must not count.
        val states = mapOf("a" to up, "b" to up, "c" to failed)

        val summary = ServerHealthProjection.summarize(servers, states)

        assertEquals(listOf(CONNECTED, FAILED), summary.enabledServers)
        assertEquals(2, summary.enabledCount)
        assertEquals(1, summary.connectedCount)
        assertEquals(AMBER, summary.aggregate)
    }

    @Test
    fun `summarize ignores state entries for servers that are no longer listed`() {
        val servers = listOf(server("a"))
        val states = mapOf("a" to up, "gone" to failed)

        val summary = ServerHealthProjection.summarize(servers, states)

        assertEquals(listOf(CONNECTED), summary.enabledServers)
        assertEquals(GREEN, summary.aggregate)
    }

    @Test
    fun `summarize with nothing configured reads red with zero counts`() {
        val summary = ServerHealthProjection.summarize(emptyList(), emptyMap())

        assertEquals(RED, summary.aggregate)
        assertEquals(0, summary.enabledCount)
        assertEquals(0, summary.connectedCount)
    }

    @Test
    fun `the bar and the rows read the same health for the same state`() {
        // Rows call project(server.enabled, states[server.id]); the bar calls
        // summarize(). Walk every state combination for two servers and make
        // sure they can't disagree.
        val perServerStates = listOf<ConnectionState?>(null, dropped, dialling, up, failed)
        for (stateA in perServerStates) for (stateB in perServerStates) {
            val servers = listOf(server("a"), server("b"))
            val states = buildMap<String, ConnectionState> {
                stateA?.let { put("a", it) }
                stateB?.let { put("b", it) }
            }

            val rows = servers.map { ServerHealthProjection.project(it.enabled, states[it.id]) }
            val bar = ServerHealthProjection.summarize(servers, states)

            assertEquals("a=$stateA b=$stateB", rows, bar.enabledServers)
            assertEquals("a=$stateA b=$stateB", ServerHealthProjection.aggregate(rows), bar.aggregate)
        }
    }

    // region against the real ServerManager -----------------------------------

    /** In-memory store that replays every save as a new emission (same shape as the one in ServerManagerToggleTest). */
    private class FakeServerStore : ServerStoreApi {
        private val _servers = MutableStateFlow<List<TAKServer>>(emptyList())
        private val _activeId = MutableStateFlow<String?>(null)

        override val servers: Flow<List<TAKServer>> = _servers.asStateFlow()
        override val activeServerId: Flow<String?> = _activeId.asStateFlow()

        override suspend fun saveServers(list: List<TAKServer>) {
            _servers.value = list
        }

        override suspend fun saveActiveServerId(id: String?) {
            _activeId.value = id
        }

        fun seed(list: List<TAKServer>) {
            _servers.value = list
        }
    }

    private fun tlsServer(enabled: Boolean) = TAKServer(
        id = "health-test-server",
        name = "HealthTest",
        host = "10.0.2.2",
        port = 8089,
        protocol = ConnectionProtocol.TLS.wire,
        useTLS = true,
        enabled = enabled,
    )

    private fun makeManager(store: ServerStoreApi, scope: TestScope): ServerManager {
        val dispatcher = scope.coroutineContext[ContinuationInterceptor]!!
        return ServerManager(
            store = store,
            externalScope = CoroutineScope(dispatcher + SupervisorJob()),
        )
    }

    @Test
    fun `projection follows the real ServerManager through toggle on and off`() = runTest {
        val store = FakeServerStore()
        val seeded = tlsServer(enabled = false)
        store.seed(listOf(seeded))
        val manager = makeManager(store, this)
        advanceUntilIdle()

        fun rowHealth(): ServerHealth {
            val current = manager.servers.value.first { it.id == seeded.id }
            return ServerHealthProjection.project(current.enabled, manager.serverStates.value[seeded.id])
        }
        fun barLight() = ServerHealthProjection
            .summarize(manager.servers.value, manager.serverStates.value).aggregate

        assertEquals(DISABLED, rowHealth())
        assertEquals(RED, barLight())

        manager.toggleEnabled(seeded.id)
        advanceUntilIdle()
        // A dial was started. The unit-test JVM has no route to 10.0.2.2, so it
        // may already have failed; what it must not be is DISABLED/DISCONNECTED.
        assertTrue(
            "after toggle ON the row must show a live attempt, got ${rowHealth()}",
            rowHealth() in setOf(CONNECTING, CONNECTED, FAILED),
        )
        // The bar must agree with the row: green only if the row is connected,
        // otherwise red (one server, so amber cannot occur).
        assertEquals(if (rowHealth() == CONNECTED) GREEN else RED, barLight())

        manager.toggleEnabled(seeded.id)
        advanceUntilIdle()
        assertEquals(DISABLED, rowHealth())
        assertEquals(RED, barLight())

        manager.disconnect()
    }

    @Test
    fun `Stop on a row leaves the enabled server DISCONNECTED and the bar red`() = runTest {
        val store = FakeServerStore()
        val manager = makeManager(store, this)
        advanceUntilIdle()

        val added = tlsServer(enabled = true)
        manager.addServer(added)
        advanceUntilIdle()

        // What the row's Stop button does.
        manager.disconnect(added.id)
        advanceUntilIdle()

        assertNull("Stop must drop the state entry", manager.serverStates.value[added.id])
        val listed = manager.servers.value.first { it.id == added.id }
        assertTrue("Stop does not disable the server", listed.enabled)
        assertEquals(
            DISCONNECTED,
            ServerHealthProjection.project(listed.enabled, manager.serverStates.value[added.id]),
        )
        assertEquals(
            RED,
            ServerHealthProjection.summarize(manager.servers.value, manager.serverStates.value).aggregate,
        )

        manager.disconnect()
    }
}
