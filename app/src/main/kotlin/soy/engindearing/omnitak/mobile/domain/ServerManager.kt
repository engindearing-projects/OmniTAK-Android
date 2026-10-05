package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
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
    // #179 — relay/gateway seam. Invoked for every inbound server CoT (already
    // tagged with its TAK_SERVER source) AFTER core ingest, so the mesh↔server
    // relay can decide whether to forward it down to the LoRa mesh. Nullable +
    // plain lambda keeps this class headless for unit tests and inert when no
    // gateway is wired (the default). The relay itself enforces enabled / both-
    // transports-connected / throttle, so this fires unconditionally.
    private val inboundRelay: ((CoTEvent) -> Unit)? = null,
    // Ditto peer-mesh seam — invoked for every frame the app ORIGINATES
    // (broadcasts with no serverId, isRelay false), before the server write
    // and regardless of whether any server is connected. That ordering is the
    // point: the peer mesh is the transport that still works when every
    // server is unreachable, so it must not be gated on the socket send.
    // Nullable + plain lambda keeps this class headless for unit tests.
    private val dittoPublish: ((xml: String) -> Unit)? = null,
    // Injected scope lets unit tests substitute a TestScope/StandardTestDispatcher
    // so coroutines run under test-scheduler control (advanceUntilIdle / runCurrent).
    // Production callers omit this and get the default app-wide scope.
    externalScope: CoroutineScope? = null,
) {

    private val scope = externalScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _servers = MutableStateFlow<List<TAKServer>>(emptyList())
    val servers: StateFlow<List<TAKServer>> = _servers.asStateFlow()

    // #176 — tombstones for just-deleted server IDs. deleteServer() removes the
    // server from _servers synchronously and launches an async DataStore persist.
    // Until that persist lands, the store.servers collector can receive a stale
    // pre-delete snapshot whose now-unknown id would be treated as an external
    // "incoming" insert (the QR/profile-import merge path below) and re-added —
    // resurrecting the row so a second tap was needed to make it stick. Recording
    // the id here lets the merge path skip it until the persist round-trips back
    // a list that no longer contains it.
    private val deletedIds = ConcurrentHashMap.newKeySet<String>()

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
    // Issue #102 — per-server auto-reconnect supervisor. Watches each live
    // connection's state and, when a previously-up socket dies in the
    // background (Doze / app-standby / a Wi-Fi↔LTE swap kills the TLS read
    // loop), re-dials with exponential backoff instead of waiting for the
    // operator to foreground the app (ON_RESUME). One job + one policy per
    // server; both are torn down by disconnect() so a deliberate
    // disconnect / toggle-off doesn't fight an instant auto-reconnect.
    private val reconnectJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val reconnectPolicies = ConcurrentHashMap<String, ReconnectPolicy>()
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
        // Issue #102 — application-level keepalive: re-dial this server with
        // exponential backoff whenever its socket drops while it's still a
        // wanted (enabled, in-map) connection. This is what makes a
        // backgrounded, screen-off device recover on its own instead of
        // only on the next ON_RESUME. conn.connect() is idempotent
        // (no-op while a connect is in flight), so re-dialing the same
        // TAKConnection instance is safe.
        val policy = ReconnectPolicy()
        reconnectPolicies[server.id] = policy
        reconnectJobs[server.id] = scope.launch {
            // drop(1): skip the StateFlow's initial Disconnected replay — the
            // explicit conn.connect() below performs the first dial, and
            // consuming a nextDelayMs() here would push the first *real* drop
            // off its intended immediate retry.
            conn.state.drop(1).collect { state ->
                when (state) {
                    is ConnectionState.Connected -> policy.reset()
                    is ConnectionState.Connecting -> { /* attempt in flight */ }
                    is ConnectionState.Disconnected, is ConnectionState.Failed -> {
                        // Only re-dial connections we still want: a deliberate
                        // disconnect()/toggle-off removes the server from the
                        // map (and cancels this job), and a disabled server
                        // must stay down.
                        val stillWanted = connections[server.id] === conn &&
                            (_servers.value.firstOrNull { it.id == server.id }?.enabled ?: false)
                        if (ReconnectPolicy.shouldReconnect(state, stillWanted)) {
                            val wait = policy.nextDelayMs()
                            if (wait > 0) kotlinx.coroutines.delay(wait)
                            // Re-check after the backoff sleep: the operator
                            // may have disconnected/disabled the server, or a
                            // foreground ON_RESUME reconnect may already have
                            // it Connecting/Connected, during the wait.
                            val target = connections[server.id]
                            val enabledNow = _servers.value
                                .firstOrNull { it.id == server.id }?.enabled ?: false
                            val downNow = target?.state?.value.let {
                                it is ConnectionState.Disconnected || it is ConnectionState.Failed
                            }
                            if (target === conn && enabledNow && downNow) {
                                conn.connect()
                            }
                        }
                    }
                }
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
                    CoTParser.parse(xml)?.let { parsed ->
                        // #180 — tag the transport at the ingest point so the
                        // marker detail sheet can show "TAK: <server>". Source
                        // is for display/debug only; it never rides the wire.
                        val event = parsed.copy(
                            source = soy.engindearing.omnitak.mobile.data.CoTSource
                                .takServer(server.name),
                        )
                        // Core store first (per the plugin RFC: handlers run
                        // AFTER the core ContactStore ingests), then fan the
                        // event out to any plugin-registered CoT handlers. The
                        // return value is advisory in v1 — ingest already
                        // happened, so "consumed" does not un-ingest.
                        contactStore?.ingest(event)
                        pluginCoTDispatch?.invoke(event)
                        // #179 — offer the server-origin CoT to the mesh↔server
                        // relay. It no-ops unless the gateway is on and both
                        // transports are up, and hard-throttles server→mesh.
                        inboundRelay?.invoke(event)
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
     *
     * Issue #102 — the background auto-reconnect supervisor now also keeps
     * sockets alive without a foreground resume, but a server can still be
     * mid-backoff when the operator opens the app. Reset every policy so
     * the resume forces an immediate re-dial instead of waiting out the
     * remaining backoff, then reconcile to cover any connection that was
     * fully removed (e.g. a never-connected enabled server).
     */
    fun reconnectIfNeeded() {
        reconnectPolicies.values.forEach { it.reset() }
        // Immediately re-dial any enabled server whose existing connection is
        // sitting Disconnected/Failed (the supervisor may be mid-backoff
        // sleep). connect() is idempotent, so this is a no-op for live ones.
        _servers.value.filter { it.enabled }.forEach { s ->
            val conn = connections[s.id]
            val down = conn?.state?.value.let {
                it is ConnectionState.Disconnected || it is ConnectionState.Failed
            }
            if (conn != null && down) conn.connect()
        }
        reconcileConnections(_servers.value)
    }

    /**
     * Send a CoT XML payload. With [serverId] null the frame is broadcast to
     * every connected server (group chat, PPLI, markers); with a specific id
     * it routes to just that server (DM replies stay on their origin server).
     * Returns true if at least one server accepted the write.
     *
     * [isRelay] marks traffic a gateway is forwarding on behalf of another
     * transport (Ditto peer mesh or the #179 LoRa relay). Relayed traffic
     * goes to the servers but is NOT re-published onto the peer mesh — it
     * already came from a mesh, and echoing it back would put every gateway
     * device in a loop with every other one. Mirrors iOS TAKService.sendCoT.
     */
    suspend fun sendCoT(xml: String, serverId: String? = null, isRelay: Boolean = false): Boolean {
        // Peers first, and unconditionally: not gated on any server being
        // connected or the writes below succeeding. Addressed traffic
        // (serverId != null — DMs) stays off the mesh; it is not situational
        // awareness everyone should see.
        if (!isRelay && serverId == null) {
            runCatching { dittoPublish?.invoke(xml) }
        }
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
        // Cancel the reconnect supervisor BEFORE tearing the socket down so
        // the resulting Disconnected transition can't trigger an auto-redial
        // of a server the operator just turned off (issue #102).
        reconnectJobs.remove(serverId)?.cancel()
        reconnectPolicies.remove(serverId)
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
                // #176 — once a persisted list no longer carries a tombstoned id,
                // the delete has round-tripped through DataStore; retire the
                // tombstone so a future re-add of the same id isn't suppressed.
                if (deletedIds.isNotEmpty()) {
                    val loadedIds = loaded.mapTo(HashSet()) { it.id }
                    deletedIds.removeAll { it !in loadedIds }
                }
                val knownIds = _servers.value.map { it.id }.toSet()
                // Skip ids we just deleted in-memory: a stale snapshot that still
                // lists them must not be treated as an external insert (#176).
                val incoming = loaded.filter { it.id !in knownIds && it.id !in deletedIds }
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
        // #176 — tombstone first so a stale DataStore re-emission (the pre-delete
        // snapshot still in flight) can't resurrect this row before the persist
        // below round-trips. Cleared once a persisted list confirms it's gone.
        deletedIds.add(id)
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
