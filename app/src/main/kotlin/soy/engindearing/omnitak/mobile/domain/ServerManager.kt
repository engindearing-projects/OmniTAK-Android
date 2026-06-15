package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import soy.engindearing.omnitak.mobile.data.CertVault
import soy.engindearing.omnitak.mobile.data.ChatXml
import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.CoTParser
import soy.engindearing.omnitak.mobile.data.LocationProvider
import soy.engindearing.omnitak.mobile.data.ServerStoreApi
import soy.engindearing.omnitak.mobile.data.TAKConnection
import soy.engindearing.omnitak.mobile.data.TAKServer
import soy.engindearing.omnitak.mobile.data.UserPrefsStore
import java.util.concurrent.ConcurrentHashMap

/**
 * Application-scoped TAK server registry. Exposes [servers] and
 * [activeServer] as StateFlows so Compose screens can observe reactively.
 *
 * Mirrors iOS ServerManager behavior:
 *  - addServer is idempotent on host + port + protocol (iOS #42)
 *  - toggling/disabling the active server hands off to the next enabled
 *    server so the UI doesn't keep pointing at a disabled one (iOS #41)
 *  - newly added enabled server becomes active when no enabled active exists
 */
class ServerManager(
    private val store: ServerStoreApi,
    private val contactStore: ContactStore? = null,
    private val chatStore: ChatStore? = null,
    private val certVault: CertVault? = null,
    private val userPrefsStore: UserPrefsStore? = null,
    private val locationProvider: LocationProvider? = null,
    // Issue #11 — wired from OmniTAKApp to read BatteryManager. Lambda
    // (not Context) keeps this class headless for unit tests.
    private val batteryProvider: () -> Int? = { null },
    // Plugin SDK seam — invoked for every inbound CoT event AFTER the core
    // ContactStore ingests it (per the plugin RFC). Wired from OmniTAKApp to
    // AppPluginHost.dispatchCoT. Nullable + a plain lambda keeps this class
    // headless for unit tests and inert when no plugin registers a CoT handler.
    private val pluginCoTDispatch: ((CoTEvent) -> Boolean)? = null,
    // Injected scope lets unit tests substitute a TestScope/StandardTestDispatcher
    // so coroutines run under test-scheduler control (advanceUntilIdle / runCurrent).
    // Production callers omit this and get the default app-wide scope.
    externalScope: CoroutineScope? = null,
) {

    private val scope = externalScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _servers = MutableStateFlow<List<TAKServer>>(emptyList())
    val servers: StateFlow<List<TAKServer>> = _servers.asStateFlow()

    private val _activeServer = MutableStateFlow<TAKServer?>(null)
    val activeServer: StateFlow<TAKServer?> = _activeServer.asStateFlow()

    // Aggregate connection state across all servers. Kept for back-compat
    // with the map status bar / FGS lifecycle / single-server callers:
    //  - any Connected  → Connected (single server's name, or "N servers")
    //  - else any Connecting → Connecting
    //  - else any Failed → Failed
    //  - else Disconnected
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Per-server connection state, keyed by TAKServer.id. The Servers screen
    // and the map's multi-server indicator read this so every enabled server
    // shows its own live status, not just whichever one is "active".
    private val _serverStates = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())
    val serverStates: StateFlow<Map<String, ConnectionState>> = _serverStates.asStateFlow()

    // The subset of [_serverStates] that is currently Connected. Drives the
    // "N/M ●●●" map indicator and is the broadcast target set for sendCoT.
    private val _connectedServerIds = MutableStateFlow<Set<String>>(emptySet())
    val connectedServerIds: StateFlow<Set<String>> = _connectedServerIds.asStateFlow()

    // GAP-030c — real ATAK-style status-bar counters. The status bar
    // chips ↓ / ↑ on MapScreen previously read hardcoded zeros, which
    // made the app look silent even when CoT was flowing both ways.
    // Now aggregated across all connected servers.
    private val _messagesReceived = MutableStateFlow(0)
    val messagesReceived: StateFlow<Int> = _messagesReceived.asStateFlow()

    private val _messagesSent = MutableStateFlow(0)
    val messagesSent: StateFlow<Int> = _messagesSent.asStateFlow()

    // One live socket per enabled server (iOS: TAKService.serverConnections).
    // ConcurrentHashMap because reconcile runs on the store-collector
    // coroutine while connect/disconnect can be called from the UI thread.
    private val connections = ConcurrentHashMap<String, TAKConnection>()
    // Guards the containsKey→put window in connect()/disconnect(): two
    // concurrent connect(server) calls (reconcile coroutine + UI thread)
    // could otherwise both construct a live TAKConnection, with the loser
    // of the map put leaking its socket + read loop and double-ingesting
    // every CoT frame until process death.
    private val connectionsLock = Any()
    private val stateJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val receivedJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    // Single PLI broadcaster — self-position fans out to every connected
    // server via the broadcast path of sendCoT, so one broadcaster covers all.
    private var pliBroadcaster: SelfPositionBroadcaster? = null

    init { scope.launch { hydrate() } }

    /**
     * Open a connection to [server] alongside any others. Idempotent — a
     * second call for an already-connected server is a no-op (matches iOS
     * where every enabled server is connected at once, not one-at-a-time).
     */
    fun connect(server: TAKServer): Unit = synchronized(connectionsLock) {
        if (connections.containsKey(server.id)) return
        // Reset the shared ↓/↑ counters only when going from zero live
        // sockets to the first one, so adding a 2nd server doesn't zero them.
        if (connections.isEmpty()) {
            _messagesReceived.value = 0
            _messagesSent.value = 0
        }
        val conn = TAKConnection(server, certVault)
        connections[server.id] = conn
        stateJobs[server.id] = scope.launch {
            conn.state.collect { state ->
                _serverStates.update { it + (server.id to state) }
                recomputeAggregate()
                // PLI broadcaster lives as long as ≥1 server is connected.
                if (_connectedServerIds.value.isNotEmpty()) startPliBroadcast()
                else stopPliBroadcast()
            }
        }
        receivedJobs[server.id] = scope.launch {
            conn.received.collect { xml ->
                _messagesReceived.update { it + 1 }
                // A frame is either a chat event or a contact/marker event,
                // depending on the CoT type. Parsing chat first is cheap
                // (string probe) and avoids double-ingesting as a contact.
                // Tag the source server so chat attribution + per-server DM
                // scoping work (multi-server parity with iOS).
                val chat = ChatXml.parse(xml, serverId = server.id)
                if (chat != null) {
                    chatStore?.ingest(chat)
                } else {
                    CoTParser.parse(xml)?.let { event ->
                        // Core store first (per the plugin RFC: handlers run
                        // AFTER the core ContactStore ingests), then fan the
                        // event out to any plugin-registered CoT handlers. The
                        // return value is advisory in v1 — ingest already
                        // happened, so "consumed" does not un-ingest.
                        contactStore?.ingest(event)
                        pluginCoTDispatch?.invoke(event)
                    }
                }
            }
        }
        conn.connect()
    }

    /**
     * Connect every enabled server that isn't already on the wire, and drop
     * any live connection whose server was disabled or removed. Called on
     * cold-launch hydrate and whenever the server list changes so the set of
     * live sockets always matches the set of enabled servers.
     */
    private fun reconcileConnections(servers: List<TAKServer>) {
        servers.filter { it.enabled }.forEach { s ->
            if (!connections.containsKey(s.id)) connect(s)
        }
        val enabledIds = servers.filter { it.enabled }.map { it.id }.toSet()
        connections.keys.filterNot { it in enabledIds }.forEach { disconnect(it) }
    }

    /**
     * Re-open connections for any enabled server with no live socket.
     * Called from MainActivity's ON_RESUME hook so that coming back from
     * the background (where Android may have killed read loops after Doze /
     * app-standby) recovers without the operator tapping play. Issue #6.
     */
    fun reconnectIfNeeded() {
        reconcileConnections(_servers.value)
    }

    /**
     * Send a CoT XML payload. With [serverId] null the frame is broadcast to
     * every connected server (group chat, PPLI, markers); with a specific id
     * it routes to just that server (DM replies stay on their origin server).
     * Returns true if at least one server accepted the write.
     */
    suspend fun sendCoT(xml: String, serverId: String? = null): Boolean {
        val targets = if (serverId != null) {
            listOfNotNull(connections[serverId])
        } else {
            connections.values.toList()
        }
        if (targets.isEmpty()) return false
        var anyOk = false
        for (conn in targets) {
            if (conn.send(xml)) anyOk = true
        }
        if (anyOk) _messagesSent.update { it + 1 }
        return anyOk
    }

    /** Disconnect a single server by id, leaving the others connected. */
    fun disconnect(serverId: String): Unit = synchronized(connectionsLock) {
        connections.remove(serverId)?.disconnect()
        stateJobs.remove(serverId)?.cancel()
        receivedJobs.remove(serverId)?.cancel()
        _serverStates.update { it - serverId }
        recomputeAggregate()
        if (_connectedServerIds.value.isEmpty()) stopPliBroadcast()
    }

    /** Disconnect every live connection. */
    fun disconnect() {
        connections.keys.toList().forEach { disconnect(it) }
    }

    /**
     * Fold the per-server state map into the single aggregate the status bar
     * and FGS lifecycle read, and refresh the connected-id set.
     */
    private fun recomputeAggregate() {
        val states = _serverStates.value
        _connectedServerIds.value = states
            .filterValues { it is ConnectionState.Connected }
            .keys.toSet()

        val connected = states.values.filterIsInstance<ConnectionState.Connected>()
        _connectionState.value = when {
            connected.size == 1 -> connected.first()
            connected.size > 1 ->
                ConnectionState.Connected("${connected.size} servers", connected.all { it.useTLS })
            states.values.any { it is ConnectionState.Connecting } ->
                states.values.first { it is ConnectionState.Connecting }
            states.values.any { it is ConnectionState.Failed } ->
                states.values.first { it is ConnectionState.Failed }
            else -> ConnectionState.Disconnected
        }
    }

    // Off-grid mesh plan Step 1b: PPLI broadcaster ownership moved to
    // OmniTAKApp so it starts when EITHER a server OR mesh radio is
    // connected. ServerManager's PLI methods are retained as stubs to
    // preserve the call sites (stateJobs collectors call them); they are
    // intentional no-ops — the app-level broadcaster handles everything.
    private fun startPliBroadcast() {
        // Intentional no-op: broadcaster is owned by OmniTAKApp.
        // Left in place so callers in stateJobs collectors compile without change.
    }

    private fun stopPliBroadcast() {
        // Intentional no-op: broadcaster is owned by OmniTAKApp.
        pliBroadcaster?.stop()
        pliBroadcaster = null
    }

    private suspend fun hydrate() {
        var initialized = false
        store.servers.collect { loaded ->
            if (!initialized) {
                // Cold-start: DataStore is the only source of truth.
                _servers.value = loaded
                if (loaded.isNotEmpty()) {
                    initialized = true
                    // Pick a sensible default-target ("active") server once. The
                    // active server is just which one new DMs route to by default;
                    // every enabled server is connected regardless.
                    val activeId = peekActiveId()
                    val active = loaded.firstOrNull { it.id == activeId }
                        ?: loaded.firstOrNull { it.enabled }
                        ?: loaded.firstOrNull()
                    _activeServer.value = active
                }
                reconcileConnections(loaded)
            } else {
                // Post-init: in-memory _servers.value is authoritative for all
                // server state (especially `enabled`). The async persist coroutines
                // launched by toggleEnabled/addServer/deleteServer may fire in any
                // order, so a DataStore emission received here can be stale relative
                // to the in-memory state that was already set synchronously and
                // reconciled.  Overwriting with `loaded` and re-reconciling would
                // undo a more-recent in-memory toggle, breaking the connection.
                //
                // Only action here: pick up server IDs that an external writer
                // (e.g. ConfigProfileStore QR/profile import) added to the DataStore
                // without going through ServerManager's public API.
                val knownIds = _servers.value.map { it.id }.toSet()
                val incoming = loaded.filter { it.id !in knownIds }
                if (incoming.isNotEmpty()) {
                    val merged = _servers.value + incoming
                    _servers.value = merged
                    reconcileConnections(merged)
                }
                // Enabled-flag changes were applied synchronously in-memory by
                // toggleEnabled / addServer / deleteServer before their async persist
                // fired, and reconcileConnections was already called there too —
                // no further action needed.
            }
        }
    }

    private suspend fun peekActiveId(): String? = store.activeServerId.firstOrNull()

    fun addServer(server: TAKServer): TAKServer {
        val existing = _servers.value.firstOrNull { it.matchesEndpoint(server) }
        if (existing != null) return existing

        val updated = _servers.value + server
        _servers.value = updated
        // First enabled server becomes the default DM-target ("active").
        if (server.enabled && (_activeServer.value == null || _activeServer.value?.enabled != true)) {
            _activeServer.value = server
            persistActive(server.id)
        }
        persist(updated)
        // Enabled servers go on the wire immediately, alongside any others.
        // Users expect "I added a server, it works" — no manual play needed.
        reconcileConnections(updated)
        return server
    }

    fun updateServer(server: TAKServer) {
        val idx = _servers.value.indexOfFirst { it.id == server.id }
        if (idx < 0) return
        val updated = _servers.value.toMutableList().apply { this[idx] = server }
        _servers.value = updated
        if (_activeServer.value?.id == server.id) _activeServer.value = server
        persist(updated)
    }

    fun deleteServer(id: String) {
        val updated = _servers.value.filterNot { it.id == id }
        _servers.value = updated
        if (_activeServer.value?.id == id) {
            val next = updated.firstOrNull { it.enabled }
            _activeServer.value = next
            persistActive(next?.id)
        }
        persist(updated)
        disconnect(id)
        reconcileConnections(updated)
    }

    fun toggleEnabled(id: String) {
        val updated = _servers.value.map {
            if (it.id == id) it.copy(enabled = !it.enabled) else it
        }
        _servers.value = updated
        val toggled = updated.firstOrNull { it.id == id }

        // Keep the "active" default-target pointed at an enabled server: hand
        // off when the active one is disabled, adopt one when none is active.
        if (toggled?.enabled == false && _activeServer.value?.id == id) {
            val next = updated.firstOrNull { it.enabled }
            _activeServer.value = next
            persistActive(next?.id)
        } else if (toggled?.enabled == true &&
            (_activeServer.value == null || _activeServer.value?.enabled != true)) {
            _activeServer.value = toggled
            persistActive(toggled.id)
        }
        persist(updated)
        // Connect newly-enabled / disconnect newly-disabled servers.
        reconcileConnections(updated)
    }

    fun setActive(id: String) {
        val target = _servers.value.firstOrNull { it.id == id } ?: return
        _activeServer.value = target
        persistActive(target.id)
    }

    private fun persist(list: List<TAKServer>) {
        scope.launch { store.saveServers(list) }
    }

    private fun persistActive(id: String?) {
        scope.launch { store.saveActiveServerId(id) }
    }
}
