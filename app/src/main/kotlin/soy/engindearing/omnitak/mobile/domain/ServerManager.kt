package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import soy.engindearing.omnitak.mobile.data.CertVault
import soy.engindearing.omnitak.mobile.data.ChatXml
import soy.engindearing.omnitak.mobile.data.CoTParser
import soy.engindearing.omnitak.mobile.data.DrawingCoT
import soy.engindearing.omnitak.mobile.data.LocationProvider
import soy.engindearing.omnitak.mobile.data.TAKConnection
import soy.engindearing.omnitak.mobile.data.TAKServer
import soy.engindearing.omnitak.mobile.data.TAKServerStore
import soy.engindearing.omnitak.mobile.data.UserPrefsStore

/**
 * Application-scoped TAK server registry with TRUE multi-server
 * connectivity. Every server whose `enabled` flag is set holds its own
 * live [TAKConnection] in parallel; PPLI fans out to all of them and
 * receive multiplexes back into the shared chat/contact stores.
 *
 * Closes the feedback loop from P-E (Discord 2026-05-11): "ATAK on my
 * own server shows EUDs, but not the Omnitak EUD." The legacy
 * single-connection model meant toggling on a second server flipped
 * the "active" flag without actually opening the socket — PPLI kept
 * going to the original server.
 *
 * Per-server states surface via [connectionStates]; the legacy
 * single-state [connectionState] flow is preserved as a derived view
 * (returns the highest-priority state across all enabled servers) for
 * the existing status-bar consumers.
 */
class ServerManager(
    private val store: TAKServerStore,
    private val contactStore: ContactStore? = null,
    private val chatStore: ChatStore? = null,
    private val certVault: CertVault? = null,
    private val userPrefsStore: UserPrefsStore? = null,
    private val locationProvider: LocationProvider? = null,
    private val batteryProvider: () -> Int? = { null },
    // == S3:drawing-cot-receive BEGIN ==
    // Optional sink for incoming drawing CoT (u-d-* events). Wired by
    // OmniTAKApp so a peer's bullseye / line / polygon / circle lands
    // in the same DrawingStore the operator's own shapes do.
    private val drawingStore: soy.engindearing.omnitak.mobile.domain.DrawingStore? = null,
    // == S3:drawing-cot-receive END ==
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _servers = MutableStateFlow<List<TAKServer>>(emptyList())
    val servers: StateFlow<List<TAKServer>> = _servers.asStateFlow()

    private val _activeServer = MutableStateFlow<TAKServer?>(null)
    /** "Primary display" server — what shows in the top status bar by default. */
    val activeServer: StateFlow<TAKServer?> = _activeServer.asStateFlow()

    /**
     * Per-server connection state. The Servers screen reads this map so
     * each row reflects its own real socket status — no more "bright
     * green for the default, dark green for mine" confusion.
     */
    private val _connectionStates = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())
    val connectionStates: StateFlow<Map<String, ConnectionState>> = _connectionStates.asStateFlow()

    /**
     * Legacy derived flow — "any connection in the highest state". Status
     * bar reads this; falls back to the active server's state when it's
     * connected, else any connection that's connecting, else disconnected.
     */
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _messagesReceived = MutableStateFlow(0)
    val messagesReceived: StateFlow<Int> = _messagesReceived.asStateFlow()

    private val _messagesSent = MutableStateFlow(0)
    val messagesSent: StateFlow<Int> = _messagesSent.asStateFlow()

    /** Per-server live sockets. Keyed by [TAKServer.id]. */
    private val connections: MutableMap<String, TAKConnection> = mutableMapOf()
    private val connectionJobs: MutableMap<String, MutableList<Job>> = mutableMapOf()
    private var pliBroadcaster: SelfPositionBroadcaster? = null

    init { scope.launch { hydrate() } }

    /**
     * Ensure [server] has a live connection. If one exists, no-op. If
     * not, open it and start collecting state + received frames.
     */
    fun connect(server: TAKServer) {
        if (connections.containsKey(server.id)) return
        val conn = TAKConnection(server, certVault)
        connections[server.id] = conn
        val jobs = mutableListOf<Job>()
        jobs += scope.launch {
            conn.state.collect { state ->
                _connectionStates.value = _connectionStates.value + (server.id to state)
                recomputeDerivedState()
                if (state is ConnectionState.Connected) startPliBroadcastIfNeeded()
                if (!anyConnectedOrConnecting()) stopPliBroadcast()
            }
        }
        jobs += scope.launch {
            conn.received.collect { xml ->
                _messagesReceived.value = _messagesReceived.value + 1
                val chat = ChatXml.parse(xml)
                if (chat != null) {
                    chatStore?.ingest(chat)
                } else {
                    // == S3:drawing-cot-receive BEGIN ==
                    // u-d-* events are drawings (line / polygon / circle /
                    // bullseye ring). DrawingCoT.parse returns null for
                    // anything else, so contact ingest still wins for
                    // markers and PPLI.
                    val drawing = DrawingCoT.parse(xml)
                    if (drawing != null) {
                        drawingStore?.add(drawing)
                    } else {
                        CoTParser.parse(xml)?.let { contactStore?.ingest(it) }
                    }
                    // == S3:drawing-cot-receive END ==
                }
            }
        }
        connectionJobs[server.id] = jobs
        conn.connect()
    }

    /** Reconnect any enabled server whose socket has dropped. */
    fun reconnectIfNeeded() {
        for (s in _servers.value.filter { it.enabled }) {
            val state = _connectionStates.value[s.id]
            if (state is ConnectionState.Connected || state is ConnectionState.Connecting) continue
            if (connections[s.id] == null) connect(s)
        }
    }

    /**
     * Send [xml] to every Connected server. Returns true if at least one
     * connection accepted the frame. PPLI uses this fan-out — operators
     * on every enabled server see the operator's position.
     */
    suspend fun sendCoT(xml: String): Boolean {
        var any = false
        for ((_, conn) in connections) {
            val ok = conn.send(xml)
            if (ok) any = true
        }
        if (any) _messagesSent.value = _messagesSent.value + 1
        return any
    }

    /** Disconnect every live connection. Used on app shutdown. */
    fun disconnect() {
        stopPliBroadcast()
        for ((id, _) in connections.toMap()) {
            disconnectOne(id)
        }
        _connectionStates.value = emptyMap()
        _connectionState.value = ConnectionState.Disconnected
    }

    /** Disconnect just one server's socket — used when an operator toggles it off. */
    private fun disconnectOne(id: String) {
        connections.remove(id)?.disconnect()
        connectionJobs.remove(id)?.forEach { it.cancel() }
        _connectionStates.value = _connectionStates.value - id
        recomputeDerivedState()
        if (!anyConnectedOrConnecting()) stopPliBroadcast()
    }

    private fun recomputeDerivedState() {
        // Priority: prefer the active server's state when present, else
        // any Connected, else any Connecting, else Disconnected. This is
        // what the legacy status bar reads — multi-server callers should
        // read [connectionStates] directly.
        val states = _connectionStates.value
        val activeId = _activeServer.value?.id
        val active = activeId?.let { states[it] }
        if (active != null && active !is ConnectionState.Disconnected) {
            _connectionState.value = active
            return
        }
        val connected = states.values.firstOrNull { it is ConnectionState.Connected }
        if (connected != null) {
            _connectionState.value = connected
            return
        }
        val connecting = states.values.firstOrNull { it is ConnectionState.Connecting }
        _connectionState.value = connecting ?: ConnectionState.Disconnected
    }

    private fun anyConnectedOrConnecting(): Boolean =
        _connectionStates.value.values.any {
            it is ConnectionState.Connected || it is ConnectionState.Connecting
        }

    private fun startPliBroadcastIfNeeded() {
        if (pliBroadcaster != null || userPrefsStore == null) return
        val fixFlow = locationProvider?.fix
            ?: kotlinx.coroutines.flow.MutableStateFlow(null)
        pliBroadcaster = SelfPositionBroadcaster(
            scope = scope,
            prefsStore = userPrefsStore,
            sendCoT = { xml -> sendCoT(xml) },
            locationFix = fixFlow,
            batteryProvider = batteryProvider,
        ).also { it.start() }
    }

    private fun stopPliBroadcast() {
        pliBroadcaster?.stop()
        pliBroadcaster = null
    }

    private suspend fun hydrate() {
        var initial: List<TAKServer> = emptyList()
        store.servers.collect { loaded ->
            _servers.value = loaded
            if (initial.isEmpty() && loaded.isNotEmpty()) {
                initial = loaded
                val activeId = peekActiveId()
                _activeServer.value = loaded.firstOrNull { it.id == activeId }
                    ?: loaded.firstOrNull { it.enabled }
                    ?: loaded.firstOrNull()
                // Resume every enabled server on cold launch — true
                // multi-server means a tester running OTS + the demo
                // box gets both back without manual taps.
                for (s in loaded.filter { it.enabled }) {
                    if (!connections.containsKey(s.id)) connect(s)
                }
            } else {
                // Re-sync: open new enabled servers, close disabled ones.
                val enabledIds = loaded.filter { it.enabled }.map { it.id }.toSet()
                for (id in connections.keys.toList() - enabledIds) {
                    disconnectOne(id)
                }
                for (s in loaded.filter { it.enabled && !connections.containsKey(it.id) }) {
                    connect(s)
                }
            }
        }
    }

    private suspend fun peekActiveId(): String? = store.activeServerId.firstOrNull()

    fun addServer(server: TAKServer): TAKServer {
        val existing = _servers.value.firstOrNull { it.matchesEndpoint(server) }
        if (existing != null) return existing

        val updated = _servers.value + server
        _servers.value = updated
        // First server in becomes the "primary display" if no other is
        // active yet — doesn't affect connection topology, only what the
        // top bar names by default.
        if (_activeServer.value == null || _activeServer.value?.enabled != true) {
            _activeServer.value = server
            persistActive(server.id)
        }
        persist(updated)
        if (server.enabled && !connections.containsKey(server.id)) connect(server)
        return server
    }

    fun updateServer(server: TAKServer) {
        val idx = _servers.value.indexOfFirst { it.id == server.id }
        if (idx < 0) return
        val updated = _servers.value.toMutableList().apply { this[idx] = server }
        _servers.value = updated
        if (_activeServer.value?.id == server.id) _activeServer.value = server
        persist(updated)
        // If the operator just changed cert / host / port via Edit, the
        // socket needs to be torn down and re-opened with the new params.
        // No-op when the server is disabled or wasn't connected.
        if (connections.containsKey(server.id)) {
            disconnectOne(server.id)
            if (server.enabled) connect(server)
        } else if (server.enabled) {
            connect(server)
        }
    }

    fun deleteServer(id: String) {
        disconnectOne(id)
        val updated = _servers.value.filterNot { it.id == id }
        _servers.value = updated
        if (_activeServer.value?.id == id) {
            val next = updated.firstOrNull { it.enabled }
            _activeServer.value = next
            persistActive(next?.id)
        }
        persist(updated)
    }

    fun toggleEnabled(id: String) {
        val updated = _servers.value.map {
            if (it.id == id) it.copy(enabled = !it.enabled) else it
        }
        _servers.value = updated

        val toggled = updated.firstOrNull { it.id == id } ?: return
        if (toggled.enabled) {
            if (!connections.containsKey(toggled.id)) connect(toggled)
            // If nothing else is the display server, claim that slot.
            if (_activeServer.value == null || _activeServer.value?.enabled != true) {
                _activeServer.value = toggled
                persistActive(toggled.id)
            }
        } else {
            disconnectOne(toggled.id)
            if (_activeServer.value?.id == toggled.id) {
                val next = updated.firstOrNull { it.enabled }
                _activeServer.value = next
                persistActive(next?.id)
            }
        }
        persist(updated)
    }

    /**
     * Set which server's name + state the top-bar should display by
     * default. Does NOT change connection topology — every enabled
     * server stays connected; this is purely a UI focus pick.
     */
    fun setActive(id: String) {
        val target = _servers.value.firstOrNull { it.id == id } ?: return
        _activeServer.value = target
        persistActive(target.id)
        recomputeDerivedState()
    }

    private fun persist(list: List<TAKServer>) {
        scope.launch { store.saveServers(list) }
    }

    private fun persistActive(id: String?) {
        scope.launch { store.saveActiveServerId(id) }
    }
}
