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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.ConnectionProtocol
import soy.engindearing.omnitak.mobile.data.ServerStoreApi
import soy.engindearing.omnitak.mobile.data.TAKServer
import kotlin.coroutines.ContinuationInterceptor

/**
 * Regression test for issue #63 — "Server toggle ON never triggers TCP connect".
 *
 * Root cause: [ServerManager.hydrate] unconditionally overwrote the authoritative
 * in-memory [ServerManager.servers] StateFlow with the DataStore emission on every
 * persist, then re-ran reconcileConnections against that potentially-stale snapshot.
 * When the toggle-OFF persist arrived *after* toggle-ON had already reconnected,
 * reconcileConnections(enabled=false) tore the new connection back down; if the
 * toggle-ON persist emission was the last one processed (or never arrived in the test
 * window), the server stayed permanently disconnected.
 *
 * Fix: after cold-start initialisation, [ServerManager.hydrate] no longer overwrites
 * `_servers.value` from DataStore emissions; it only picks up server IDs that an
 * external writer (ConfigProfileStore) inserted without going through the public API.
 * All enabled-flag changes are applied synchronously in-memory by the public mutators
 * (toggleEnabled, addServer, deleteServer) before their async persist fires.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerManagerToggleTest {

    /**
     * In-memory fake that replays every saveServers call as a new emission.
     * Uses [MutableStateFlow] so collectors (hydrate) stay alive — callers
     * must cancel the manager scope or call [manager.disconnect] to let
     * the TestScope complete cleanly.
     */
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

        /** Inject a server list as if it had been written at cold-start. */
        fun seed(list: List<TAKServer>) {
            _servers.value = list
        }

        /** Simulate a stale DataStore re-emission (e.g. out-of-order async persist). */
        fun replayStale(list: List<TAKServer>) {
            _servers.value = list
        }
    }

    private fun testServer(enabled: Boolean = true) = TAKServer(
        id = "test-server-id",
        name = "TestServer",
        host = "10.0.2.2",
        port = 8089,
        protocol = ConnectionProtocol.TLS.wire,
        useTLS = true,
        enabled = enabled,
    )

    private fun makeManager(store: FakeServerStore, scope: TestScope): ServerManager {
        // Pull the StandardTestDispatcher from the test scope so that coroutines
        // launched inside ServerManager share the test scheduler and are advanced by
        // advanceUntilIdle(). A fresh SupervisorJob (not a child of TestScope's Job)
        // prevents runTest from failing on the infinite hydrate() collector.
        val dispatcher = scope.coroutineContext[ContinuationInterceptor]!!
        val managerScope = CoroutineScope(dispatcher + SupervisorJob())
        return ServerManager(
            store = store,
            // certVault, contactStore, etc. all null — headless / no-network.
            externalScope = managerScope,
        )
    }

    // ── Test 1 ───────────────────────────────────────────────────────────────

    /**
     * Toggling a server ON must cause a connection attempt: serverStates for
     * the server must leave Disconnected and become Connecting (or beyond).
     *
     * Repro from #63 step-by-step:
     *  1. Server is persisted with enabled=false.
     *  2. App cold-starts, hydrate() loads the disabled server — no connect.
     *  3. User toggles the server ON.
     *  4. Expected: server enters Connecting state.
     *  5. Actual (before fix): serverStates[id] stays Disconnected / absent
     *     because stale DataStore re-emission from the cold-start load undid
     *     the reconciliation triggered by toggleEnabled.
     */
    @Test
    fun `toggle ON initiates a connection attempt`() = runTest {
        val store = FakeServerStore()
        val server = testServer(enabled = false)
        store.seed(listOf(server))

        val manager = makeManager(store, this)
        advanceUntilIdle()

        // Cold-start: server is disabled, no connection.
        assertFalse(
            "disabled server must not be in serverStates after cold-start",
            manager.serverStates.value.containsKey(server.id),
        )

        // Toggle ON — this is the action described in #63.
        manager.toggleEnabled(server.id)
        advanceUntilIdle()

        val state = manager.serverStates.value[server.id]
        assertTrue(
            "server must have a state entry after toggle ON (got null — connect never called)",
            state != null,
        )
        assertTrue(
            "server state after toggle ON must be Connecting or beyond, got $state",
            state is ConnectionState.Connecting ||
                state is ConnectionState.Connected ||
                state is ConnectionState.Failed,
        )
    }

    // ── Test 2 ───────────────────────────────────────────────────────────────

    /**
     * Stale DataStore re-emission must NOT disconnect a server that was just
     * connected via toggleEnabled.  This reproduces the exact race in #63:
     *  1. toggleEnabled(ON) → reconcile → connect → server in Connecting.
     *  2. DataStore re-emits the old enabled=false snapshot (out-of-order persist).
     *  3. Expected (fixed): server stays in Connecting — stale emission ignored.
     *  4. Actual (before fix): hydrate() called reconcile(enabled=false) →
     *     disconnect → server dropped from serverStates.
     */
    @Test
    fun `stale DataStore re-emission does not disconnect a server toggled ON`() = runTest {
        val store = FakeServerStore()
        val server = testServer(enabled = false)
        store.seed(listOf(server))

        val manager = makeManager(store, this)
        advanceUntilIdle()

        // Toggle ON — connects the server.
        manager.toggleEnabled(server.id)
        advanceUntilIdle()

        val afterToggle = manager.serverStates.value[server.id]
        assertTrue(
            "server must be connecting after toggle ON",
            afterToggle is ConnectionState.Connecting ||
                afterToggle is ConnectionState.Connected ||
                afterToggle is ConnectionState.Failed,
        )

        // Simulate the stale DataStore re-emission (enabled=false) arriving
        // after the toggle-ON has already fired.
        store.replayStale(listOf(server.copy(enabled = false)))
        advanceUntilIdle()

        val afterStale = manager.serverStates.value[server.id]
        assertTrue(
            "stale enabled=false emission must NOT disconnect the server; got $afterStale",
            afterStale is ConnectionState.Connecting ||
                afterStale is ConnectionState.Connected ||
                afterStale is ConnectionState.Failed,
        )
    }

    // ── Test 3 ───────────────────────────────────────────────────────────────

    /**
     * addServer with enabled=true must immediately attempt a connection.
     * The UI says "green toggle ON / red status dot" right after save —
     * the connect must at least reach Connecting before anything else.
     */
    @Test
    fun `addServer with enabled=true initiates a connection attempt`() = runTest {
        val store = FakeServerStore()
        // Cold-start with no servers.
        val manager = makeManager(store, this)
        advanceUntilIdle()

        val server = testServer(enabled = true)
        manager.addServer(server)
        advanceUntilIdle()

        val state = manager.serverStates.value[server.id]
        assertTrue(
            "addServer(enabled=true) must result in a connect attempt; got $state",
            state is ConnectionState.Connecting ||
                state is ConnectionState.Connected ||
                state is ConnectionState.Failed,
        )
    }

    // ── Test 4 ───────────────────────────────────────────────────────────────

    /**
     * After addServer's DataStore persist fires, hydrate() must not undo the
     * initial connection attempt even if the emission arrives out of order.
     */
    @Test
    fun `DataStore emission after addServer does not drop the connection`() = runTest {
        val store = FakeServerStore()
        val manager = makeManager(store, this)
        advanceUntilIdle()

        val server = testServer(enabled = true)
        manager.addServer(server)
        advanceUntilIdle()

        // Simulate DataStore replaying the same enabled=true list (expected case).
        store.replayStale(listOf(server.copy(enabled = true)))
        advanceUntilIdle()

        val state = manager.serverStates.value[server.id]
        assertTrue(
            "DataStore re-emission of enabled=true must keep server connected; got $state",
            state is ConnectionState.Connecting ||
                state is ConnectionState.Connected ||
                state is ConnectionState.Failed,
        )
    }
}
