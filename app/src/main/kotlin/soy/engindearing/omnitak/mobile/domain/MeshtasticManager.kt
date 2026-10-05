package soy.engindearing.omnitak.mobile.domain

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import soy.engindearing.omnitak.mobile.data.AdminMessageParser
import soy.engindearing.omnitak.mobile.data.AdminReads
import soy.engindearing.omnitak.mobile.data.AdminResponse
import soy.engindearing.omnitak.mobile.data.AdminWriteResult
import soy.engindearing.omnitak.mobile.data.DeviceEdits
import soy.engindearing.omnitak.mobile.data.AtakPluginParser
import soy.engindearing.omnitak.mobile.data.ChatMessage
import soy.engindearing.omnitak.mobile.data.ChatStatus
import soy.engindearing.omnitak.mobile.data.MeshChannel
import soy.engindearing.omnitak.mobile.data.MeshChannelPreset
import soy.engindearing.omnitak.mobile.data.MeshRegion
import soy.engindearing.omnitak.mobile.data.RebroadcastMode
import soy.engindearing.omnitak.mobile.data.AtakPluginSerializer
import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.TakPacketParser
import soy.engindearing.omnitak.mobile.data.TakPacketSerializer
import soy.engindearing.omnitak.mobile.data.TakPacketV2Codec
import soy.engindearing.omnitak.mobile.data.MeshWire
import soy.engindearing.omnitak.mobile.data.AdminSetting
import soy.engindearing.omnitak.mobile.data.DeviceSettingsState
import soy.engindearing.omnitak.mobile.data.FromRadioFrame
import soy.engindearing.omnitak.mobile.data.InterruptedWrite
import soy.engindearing.omnitak.mobile.data.MeshConnectionType
import soy.engindearing.omnitak.mobile.data.MeshNode
import soy.engindearing.omnitak.mobile.data.MeshtasticBleClient
import soy.engindearing.omnitak.mobile.data.MeshtasticProtoParser
import soy.engindearing.omnitak.mobile.data.MeshtasticTcpClient
import soy.engindearing.omnitak.mobile.data.PositionFacts
import soy.engindearing.omnitak.mobile.data.ProtoFields
import soy.engindearing.omnitak.mobile.data.RadioSettings
import soy.engindearing.omnitak.mobile.data.RadioSettingsCache
import soy.engindearing.omnitak.mobile.data.SentLedger
import soy.engindearing.omnitak.mobile.data.SentSettings

/**
 * Application-scoped Meshtastic state holder. Owns the TCP and BLE
 * transports and exposes node-table + connection state to screens via
 * StateFlow.
 *
 * Phase 1 wired the protobuf decoder — every framed payload from
 * [MeshtasticTcpClient.frames] (TCP) or [MeshtasticBleClient.frames]
 * (BLE) is dispatched through [MeshtasticProtoParser.parseFromRadio],
 * and recognised NodeInfo frames flow into [_nodes]. POSITION_APP
 * packets fold into the existing entry without dropping unrelated
 * metadata. A BLE drain read is byte-for-byte equivalent to a TCP
 * framed payload so the consumer doesn't care which transport
 * delivered it.
 *
 * Phase 2 added the BLE transport. The active transport at any time
 * is tracked via [activeTransport]; UI uses that to flip between the
 * TCP and BLE link-status panels. Since BLE needs a [Context] to
 * construct, the manager is created lazily with one — the App owns
 * this and just hands it through.
 *
 * Phase 4 wires the ATAK-plugin parser: portnum-72 packets are decoded
 * into [CoTEvent] via [AtakPluginParser] and pushed into [cotSink], the
 * same sink [MeshtasticCoTBridge] already feeds. [sendCoTOverMesh]
 * provides the matching TX path over the active TCP transport — BLE
 * TX hooks in as a follow-up.
 */
class MeshtasticManager(
    private val context: Context? = null,
    /** Milliseconds on a monotonic clock, for the deadlines of reads. Tests supply their own. */
    readClock: () -> Long = AdminReads.MONOTONIC_MS,
) : MeshFrameworkManager {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _nodes = MutableStateFlow<Map<Long, MeshNode>>(emptyMap())
    override val nodes: StateFlow<Map<Long, MeshNode>> = _nodes.asStateFlow()

    val tcpClient = MeshtasticTcpClient()
    private var bleClient: MeshtasticBleClient? = null

    private val _activeTransport = MutableStateFlow<MeshConnectionType?>(null)
    val activeTransport: StateFlow<MeshConnectionType?> = _activeTransport.asStateFlow()

    private var frameCollector: Job? = null
    private var bytesRx: Long = 0L
    @Volatile private var _myNodeNum: UInt? = null
    val myNodeNum: UInt? get() = _myNodeNum

    /** BLE auto-reconnect — the address [connectBle] was last asked to
     *  reach. Non-null keeps [ensureReconnectLoopStarted]'s loop retrying
     *  every [BLE_RECONNECT_INTERVAL_MS] whenever we're not connected; cleared
     *  by a user-initiated [disconnect] so tapping "Disconnect" doesn't
     *  immediately reconnect. Survives an involuntary drop (radio
     *  power-off / out of range) so it resumes once back in range. */
    private val _reconnectTargetAddress = MutableStateFlow<String?>(null)
    private var reconnectTargetAddress: String?
        get() = _reconnectTargetAddress.value
        set(value) { _reconnectTargetAddress.value = value }
    private var reconnectLoopStarted = false

    /** #208 — BLE advertised name (scan result / `BluetoothDevice` name),
     *  keyed by address, captured when the operator picks a radio from the
     *  scan list. Lives next to [reconnectTargetAddress] so the automatic
     *  reconnect loop's re-connects still have a name to show — it keeps
     *  reusing the same remembered address, so the lookup below keeps
     *  resolving. Outlives a single connection (cleared only by picking a
     *  different address), matching the "auto-reconnect has it" ask; not
     *  persisted across an app restart. */
    private val advertisedNameByAddress = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** #208 — remember the BLE-advertised name for [address] (e.g. from the
     *  scan result the operator tapped). Call before/alongside [connectBle]
     *  so [bleDisplayName] has it. Blank/null names are ignored — see
     *  [MeshtasticBleClient.resolveDisplayName]. */
    fun rememberBleAdvertisedName(address: String, name: String?) {
        if (!name.isNullOrBlank()) advertisedNameByAddress[address] = name
    }

    /** #208 — human-readable name for the BLE link at [address]: the node's
     *  real long name (once NodeInfo for [myNodeNum] has arrived in [nodes])
     *  beats the name remembered via [rememberBleAdvertisedName], which
     *  beats the bare MAC. [nodes] defaults to the current snapshot, but
     *  callers that want this to update live as NodeInfo streams in should
     *  pass their own collected `nodes` state (e.g. Compose's
     *  `nodes.collectAsState()`) so recomposition picks up the change. */
    fun bleDisplayName(address: String, nodes: Map<Long, MeshNode> = _nodes.value): String {
        val longName = _myNodeNum?.let { nodes[it.toLong() and 0xFFFFFFFFL]?.longName }
        return MeshtasticBleClient.resolveDisplayName(
            advertisedName = advertisedNameByAddress[address],
            longName = longName,
            address = address,
        )
    }

    /** True whenever the BLE auto-reconnect loop has a radio it's trying
     *  to reach. OmniTAKApp keeps the foreground service alive on this
     *  signal alone — not just "Connected" — so Android's Doze mode
     *  doesn't stall the reconnect loop's delay/BLE calls once the
     *  screen turns off between retries. */
    val autoReconnectPending: StateFlow<Boolean> =
        _reconnectTargetAddress.map { it != null }
            .stateIn(scope, SharingStarted.Eagerly, false)

    /** #203 — increments each time [ensureReconnectLoopStarted]'s loop fires an
     *  automatic retry; resets to 0 on a successful [connectBle] (manual or
     *  automatic) and on a user-initiated [disconnect]. Lets the BLE pane show
     *  "Reconnecting, attempt n" instead of a silent retry loop. */
    private val _reconnectAttempt = MutableStateFlow(0)
    val reconnectAttempt: StateFlow<Int> = _reconnectAttempt.asStateFlow()

    private fun ensureReconnectLoopStarted() {
        if (reconnectLoopStarted) return
        reconnectLoopStarted = true
        scope.launch {
            while (true) {
                delay(BLE_RECONNECT_INTERVAL_MS)
                val target = reconnectTargetAddress ?: continue
                if (_activeTransport.value != MeshConnectionType.BLUETOOTH) continue
                val current = activeConnectionState.value
                if (current is ConnectionState.Connected || current is ConnectionState.Connecting) continue
                _reconnectAttempt.value += 1
                Log.i(TAG, "BLE auto-reconnect: retrying $target (attempt ${_reconnectAttempt.value})")
                // #203 — one attempt must never be able to end the loop. The
                // BLE client bounds every wait it makes, so this cap should
                // never fire; it is here so that a wait which does hang costs
                // one attempt instead of all of them.
                val finished = withTimeoutOrNull(BLE_RECONNECT_ATTEMPT_CAP_MS) { connectBle(target) }
                if (finished == null) Log.w(TAG, "BLE auto-reconnect: attempt ${_reconnectAttempt.value} did not finish, moving on")
            }
        }
    }

    /** #171 — last wall-clock ms a given marker uid was sent over mesh, for
     *  the per-uid send debounce. Guarded by its own monitor; the map can
     *  fire repeated saves of the same marker faster than LoRa can carry. */
    private val markerLastSentMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Default link state — the TCP client. Existing screens (and the
     * MeshtasticScreen TCP tab) keep observing this. The BLE tab
     * collects [bleState] / [bleBytesReceived] separately so each tab
     * can show its own transport's status without one transport's
     * state-flow leaking into the other tab.
     */
    val state: StateFlow<ConnectionState> get() = tcpClient.state
    val bytesReceived: StateFlow<Long> get() = tcpClient.bytesReceived

    /** BLE-specific state, lazily wired when the user opens the BLE tab. */
    fun bleState(): StateFlow<ConnectionState>? = bleClientOrNull()?.state
    fun bleBytesReceived(): StateFlow<Long>? = bleClientOrNull()?.bytesReceived

    /**
     * Transport-aware connection state. Tracks whichever transport
     * [_activeTransport] currently points at — TCP, BLE, or neither.
     *
     * Screens that don't care which transport is live (e.g. Device
     * Settings, which just needs to know whether *any* radio is
     * reachable to enable the push button) should observe this rather
     * than [state] (TCP-only). Fixes #36 where a BLE-connected radio
     * showed as "No device connected" in Device Settings.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override val activeConnectionState: StateFlow<ConnectionState> =
        _activeTransport.flatMapLatest { transport ->
            when (transport) {
                MeshConnectionType.TCP -> tcpClient.state
                MeshConnectionType.BLUETOOTH ->
                    bleClientOrNull()?.state ?: flowOf(ConnectionState.Disconnected)
                null -> flowOf(ConnectionState.Disconnected)
            }
        }.stateIn(scope, SharingStarted.Eagerly, ConnectionState.Disconnected)

    /**
     * What the radio last told us about its own settings, as raw bytes: the
     * device, position and LoRa configs (the ones the app patches; the security
     * and network configs are never kept), every channel, and its owner record. Fed by every frame
     * in [dispatchFrame] that comes from the radio, emptied when the link drops
     * or a new config download begins. A write does not patch what is in here
     * (it may be stale); it asks the radio for the entry again and patches the
     * answer, which arrives here.
     */
    internal val radioSettings = RadioSettingsCache()

    private val positionFactsLock = Any()
    private val _positionFacts = MutableStateFlow(PositionFacts.read(radioSettings))

    /**
     * What the radio reported about the settings that decide the firmware's position floor: its channels, its
     * LoRa config and its role ([PositionFacts]). Read from [radioSettings] each time that changes, so it is
     * what the radio said and never the app's own list of saved channels. Parts the radio has not reported are
     * null, and the screen says nothing about the floor until they are in.
     */
    val positionFacts: StateFlow<PositionFacts> = _positionFacts.asStateFlow()

    init {
        // Under a lock, so the last change to the cache is the last one to be read into the flow.
        radioSettings.onChange = {
            synchronized(positionFactsLock) { _positionFacts.value = PositionFacts.read(radioSettings) }
        }
    }

    /** Test seam: when set, settings writes go here instead of the BLE/TCP transport. Null in the app. */
    @Volatile internal var adminSendOverride: (suspend (ByteArray) -> Boolean)? = null

    /** What was last sent to each radio, until that radio reports it again. */
    private val sentLedger = SentLedger()

    private val _settingsNotice = MutableStateFlow<String?>(null)

    /**
     * Something the operator should know about a write that already went out: the radio reported a setting and
     * had kept its own value. Shown by the settings screens; cleared by [clearSettingsNotice].
     */
    val settingsNotice: StateFlow<String?> = _settingsNotice.asStateFlow()

    fun clearSettingsNotice() {
        _settingsNotice.value = null
    }

    private val _lastPushResult = MutableStateFlow<String?>(null)

    /**
     * What the last Device settings push said ("Sent to the radio: position interval. ..."). A push ends with the
     * radio restarting to save it, which takes the link down, so the result is kept here, not on the screen: it
     * stays while the link is down, after it comes back and after the screen is left, until the operator edits
     * again or pushes again ([clearLastPushResult]).
     */
    val lastPushResult: StateFlow<String?> = _lastPushResult.asStateFlow()

    /**
     * Forget what the last push said, what it sent and the note about what the radio did with it
     * ([restartNote]). Called when the operator edits a field or pushes again: all of it was about the
     * settings as they were before.
     */
    fun clearLastPushResult() {
        _lastPushResult.value = null
        synchronized(noteLock) {
            lastPush = null
            _restartNote.value = null
        }
    }

    /**
     * Guards [lastPush] and [restartNote]: the download that judges a push comes in on the frame thread, and the
     * operator's edit or push that forgets it on another, and a note must not outlive the edit that cleared it.
     */
    private val noteLock = Any()

    /** What the last Device settings push sent, and to which radio. Judged when that radio's download is in. */
    @Volatile private var lastPush: SentSettings? = null

    /**
     * What the radio reported in this session, setting by setting, the way the settings screen reads it. Starts
     * empty with every download; what [lastPush] is compared with.
     */
    @Volatile private var reported = RadioSettings()

    private val _restartNote = MutableStateFlow<String?>(null)

    /**
     * Set when the radio is back after a push and reports a value other than the one that was sent: one sentence
     * for each, with both values, and the reason when the firmware's position floor explains an interval
     * ([PositionFloor]). Null while there is nothing to say. It stays through link drops and reconnects of the
     * same radio, until the operator edits or pushes ([clearLastPushResult]) or a different radio connects. A value
     * the radio kept says nothing.
     */
    val restartNote: StateFlow<String?> = _restartNote.asStateFlow()

    /**
     * The download of the radio that [lastPush] went to is in: compare what it reports with what was sent. Judged
     * again at every download of that radio until the operator edits or pushes, so a download that came before
     * the radio had restarted (and still held what it received) does not hide what the next one shows.
     */
    private fun judgeLastPush() = synchronized(noteLock) {
        val sent = lastPush ?: return@synchronized
        if (sent.node != _myNodeNum) return@synchronized
        _restartNote.value = sent.check(reported, PositionFacts.read(radioSettings))
    }

    /** True from the end of a config download (`config_complete_id`) until the next one starts or the link drops. */
    @Volatile private var downloadComplete = false
    @Volatile private var downloadCompletedNanos = 0L

    /** When the last frame came in from the radio, on the monotonic clock. */
    @Volatile private var lastFrameNanos = System.nanoTime()

    /** How long the link has to be quiet after a download before reads go out. Tests shorten it. */
    @Volatile internal var settleQuietMs: Long = SETTLE_QUIET_MS

    /** The longest a read waits for the download to finish. Tests shorten it. */
    @Volatile internal var settleTimeoutMs: Long = SETTLE_TIMEOUT_MS

    /**
     * The reads this app has sent and not had answered, and the rule for what counts as the radio's answer to one
     * ([AdminReads]). Every admin response goes through [AdminReads.admit] before it reaches the cache, the settings
     * state or a write.
     */
    internal val adminReads = AdminReads(clock = readClock)

    private val settingsWriter = MeshSettingsWriter(
        cache = radioSettings,
        destination = { if (adminLinkUp()) adminDestination() else null },
        send = { bytes -> sendAdminFrame(bytes) },
        ledger = sentLedger,
        // Longer over Bluetooth: an answer waits there for the drain or the poll of the BLE client.
        readTimeoutMs = { MeshSettingsWriter.readTimeoutFor(_activeTransport.value) },
        reads = adminReads,
        onTransactionEnd = { node, leftOpen, settings, values -> sequenceEnded(node, leftOpen, settings, values) },
    )

    // region An edit transaction a lost link left open --------------------------------------------------

    /** Guards the state below. */
    private val transactionLock = Any()

    /**
     * The sequence that began an edit transaction on a radio and did not get its commit out ([InterruptedWrite]),
     * until a link-up decides what to do about it: close it, or say it is gone.
     */
    private var interrupted: InterruptedWrite? = null

    /** `my_info.reboot_count` of the latest link-up, which a sequence that ends on that link is remembered with. */
    @Volatile private var rebootCount: UInt = 0u

    /** The interrupted sequence of a radio that does not count its restarts, until its download says what the radio holds. */
    private var awaitingDownload: InterruptedWrite? = null

    /** The writer's hold while a decision is pending: released when it is made, when the link drops or after [settleTimeoutMs]. */
    private var decisionGate: CompletableDeferred<Unit>? = null

    private fun sequenceEnded(
        node: UInt, leftOpen: Boolean, settings: List<AdminSetting>, values: Map<AdminSetting, InterruptedWrite.WrittenValue>,
    ) = synchronized(transactionLock) {
        if (leftOpen) {
            Log.w(TAG, "a sequence ended without its commit: ${settings.size} setting(s) written, the transaction may be open")
            interrupted = InterruptedWrite(node, rebootCount, settings, values)
        } else if (interrupted?.node == node) {
            // The commit of a later sequence closed it.
            interrupted = null
        }
    }

    /**
     * The radio has said who it is (`my_info`) and an earlier sequence may have left a transaction open on one. What
     * the firmware does with it (AdminModule.cpp, [InterruptedWrite]): the transaction survives a disconnect, nothing
     * is saved or restarted while it is open, and a restart clears it and the changes with it.
     *
     *  - another radio: forget it, send nothing;
     *  - a radio that counts its restarts (either count is not 0): the same count means it has not restarted, so the
     *    commit goes out now, first, and the result line says the change was saved; a different count means it has
     *    restarted, so nothing is sent and the result line says the change was not saved;
     *  - a radio that does not (both counts are 0, as on nRF52 and the simulator) cannot say, and a commit to a radio
     *    that did restart saves nothing new and restarts it a second time. So nothing is decided until its download
     *    is in ([decideFromDownload]), and nothing is sent until then: the writer is held, so no other admin frame
     *    goes out before the decision is made. With nothing written yet there is nothing to decide, and nothing is
     *    sent: a transaction that is still open is closed by the next write, as before.
     */
    private fun settleInterrupted(node: UInt, count: UInt) {
        val rec = synchronized(transactionLock) { interrupted } ?: return
        // What the sequence wrote is judged here from now on. The ledger would blame a radio that restarted since for
        // ignoring it ("It may be managed"), when the writes were simply lost with the restart.
        if (rec.node == node) sentLedger.forget(node)
        when {
            rec.node != node -> forgetInterrupted(rec)
            rec.rebootCount != 0u || count != 0u ->
                if (rec.rebootCount == count) {
                    closeInterrupted(rec, holdForDecision(), InterruptedWrite.SAVED)
                } else {
                    forgetInterrupted(rec)
                    _lastPushResult.value = InterruptedWrite.NOT_SAVED
                }
            rec.settings.isEmpty() -> forgetInterrupted(rec)
            else -> {
                val gate = holdForDecision()
                synchronized(transactionLock) { awaitingDownload = rec }
                // Bounded: a download that never completes must not hold the writer for ever.
                scope.launch {
                    delay(settleTimeoutMs)
                    synchronized(transactionLock) {
                        if (awaitingDownload === rec) {
                            awaitingDownload = null
                            if (interrupted === rec) interrupted = null
                        }
                    }
                    gate.complete(Unit)
                }
            }
        }
    }

    /** The download is complete: judge the interrupted sequence of a radio that does not count its restarts. */
    private fun decideFromDownload() {
        val (rec, gate) = synchronized(transactionLock) {
            val rec = awaitingDownload ?: return
            awaitingDownload = null
            rec to (decisionGate ?: return)
        }
        when (rec.verdict(reported)) {
            InterruptedWrite.Verdict.HOLDS -> closeInterrupted(rec, gate, InterruptedWrite.SAVED)
            InterruptedWrite.Verdict.GONE -> {
                forgetInterrupted(rec)
                _lastPushResult.value = InterruptedWrite.NOT_SAVED
                gate.complete(Unit)
            }
            InterruptedWrite.Verdict.UNKNOWN -> {
                forgetInterrupted(rec)
                _lastPushResult.value = InterruptedWrite.CHECK
                gate.complete(Unit)
            }
        }
    }

    /** Hold every other admin frame back until the returned gate is completed. */
    private fun holdForDecision(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { gate ->
        synchronized(transactionLock) { decisionGate = gate }
        settingsWriter.holdUntil(gate)
    }

    /** Send the commit, first, and say what happened. If the frame does not go out the sequence is kept for the next link-up. */
    private fun closeInterrupted(rec: InterruptedWrite, gate: CompletableDeferred<Unit>, text: String) {
        scope.launch {
            try {
                if (settingsWriter.commitLeftOpen()) {
                    forgetInterrupted(rec)
                    _lastPushResult.value = text
                }
            } finally {
                gate.complete(Unit)
            }
        }
    }

    private fun forgetInterrupted(rec: InterruptedWrite) = synchronized(transactionLock) {
        if (interrupted === rec) interrupted = null
    }

    /** The link dropped before a decision was made: release the writer. The sequence is kept for the next link-up. */
    private fun abandonDecision() {
        val gate = synchronized(transactionLock) {
            awaitingDownload = null
            decisionGate
        }
        gate?.complete(Unit)
    }

    // endregion

    init {
        // The cache must not outlive the link it was read over: a write built
        // from another session's settings (or another radio's) would put
        // stale values back. Cleared here when the link drops, and by
        // [RadioSettingsCache.onFromRadio] when the first frame of a new
        // config download (my_info) arrives. A user disconnect and an
        // involuntary drop both show up as the state leaving Connected, so
        // this one watcher covers TCP and BLE without touching either
        // transport's connect or reconnect code.
        scope.launch {
            var wasConnected = false
            activeConnectionState.collect { state ->
                wasConnected = onLinkState(state, wasConnected)
            }
        }
    }

    /** One step of the link-drop watcher. Returns whether the link is up now. */
    internal fun onLinkState(state: ConnectionState, wasConnected: Boolean): Boolean {
        val connected = state is ConnectionState.Connected
        if (wasConnected && !connected) {
            Log.i(TAG, "link dropped, forgetting the radio's settings")
            radioSettings.clear()
            // A read sent on this link cannot be answered on another one, or by another radio.
            adminReads.clear()
            downloadComplete = false
            reported = RadioSettings()
            // A decision about an interrupted transaction was waiting for this link's download: release the writer.
            abandonDecision()
            // Nothing may be addressed to the radio that was on this link: the next one reports its own number.
            _myNodeNum = null
            runCatching { linkDownSink?.invoke() }
                .onFailure { Log.w(TAG, "linkDownSink failed: ${it.message}") }
        }
        return connected
    }

    /** Eagerly construct the BLE client (if a Context is available) so
     *  the BLE tab can observe its state flows even before any
     *  scan/connect has been issued. */
    fun ensureBleReady(): Boolean = bleClientOrNull() != null

    private fun bleClientOrNull(): MeshtasticBleClient? {
        val existing = bleClient
        if (existing != null) return existing
        val ctx = context ?: return null
        return MeshtasticBleClient(ctx).also { bleClient = it }
    }

    /** Sink for CoT events parsed off the mesh — wired up by
     *  [OmniTAKApp] to [ContactStore.ingest] so portnum-72 ATAK-plugin
     *  payloads flow into the same map pipeline as TCP-server CoT. */
    @Volatile override var cotSink: ((CoTEvent) -> Unit)? = null

    fun connectTcp(host: String, port: Int = 4403) {
        // Tearing down a BLE session before opening the TCP one is
        // fine — we only ever drive one transport at a time.
        if (_activeTransport.value == MeshConnectionType.BLUETOOTH) disconnect()
        frameCollector?.cancel()
        _activeTransport.value = MeshConnectionType.TCP
        frameCollector = scope.launch {
            tcpClient.frames.collect { frame -> dispatchFrame(frame) }
        }
        // Once the TCP link comes up, kick the radio with want_config_id
        // so it streams its node DB. Without this the radio sits silent.
        scope.launch {
            tcpClient.state.first { it is ConnectionState.Connected }
            tcpClient.sendBytes(buildWantConfig())
            Log.i(TAG, "TX want_config_id (TCP)")
        }
        tcpClient.connect(host, port)
    }

    /**
     * Open a BLE session to the radio at [deviceAddress]. Mirrors
     * `connectTcp` — same frame collector funnels into the same
     * parser. Requires the manager to have been constructed with a
     * Context (`MeshtasticManager(applicationContext)`).
     */
    override suspend fun connectBle(deviceAddress: String): Boolean {
        val client = bleClientOrNull() ?: run {
            Log.w(TAG, "connectBle called but BLE client unavailable")
            return false
        }
        // Tearing down a TCP session before opening BLE.
        if (_activeTransport.value == MeshConnectionType.TCP) disconnect()
        frameCollector?.cancel()
        _activeTransport.value = MeshConnectionType.BLUETOOTH
        // Remember this address so the auto-reconnect loop keeps retrying
        // it if the link drops (radio power-off / out of range) — covers
        // both "was connected then lost" and "this very attempt failed
        // because the radio isn't in range yet".
        reconnectTargetAddress = deviceAddress
        ensureReconnectLoopStarted()
        frameCollector = scope.launch {
            client.frames.collect { frame -> dispatchFrame(frame) }
        }
        // The handshake rides on the connect: ask the radio to dump its
        // config + node database. Without it the radio doesn't push any state
        // and the node list stays empty, so the client does not keep a session
        // it could not deliver it on.
        var result = client.connect(deviceAddress, handshake = buildWantConfig())
        // #203 — a connect that died on a Bluetooth stack error before the
        // link was up (status 133 and its relatives) usually works on the
        // next try, so try again right away instead of leaving it to the
        // reconnect loop's next pass.
        var quickRetries = 0
        while (result == MeshtasticBleClient.ConnectResult.FAILED_BEFORE_LINK_UP && quickRetries < BLE_QUICK_RETRIES) {
            quickRetries++
            delay(BLE_QUICK_RETRY_DELAY_MS)
            // Checked after the wait: not if the operator disconnected, picked
            // another radio or switched to TCP in the meantime.
            if (reconnectTargetAddress != deviceAddress || _activeTransport.value != MeshConnectionType.BLUETOOTH) break
            Log.i(TAG, "BLE connect: quick retry $quickRetries for $deviceAddress")
            result = client.connect(deviceAddress, handshake = buildWantConfig())
        }
        if (result != MeshtasticBleClient.ConnectResult.CONNECTED) return false
        Log.i(TAG, "TX want_config_id (BLE)")
        // #203 — a session that is up (manual tap or automatic retry) means
        // whatever streak of failures preceded it is over.
        _reconnectAttempt.value = 0
        return true
    }

    /**
     * [connectBle] on the manager's own scope, for callers whose own scope
     * can end mid-connect. The BLE pane launched the connect in the screen's
     * scope: leaving the pane cancelled it, which now ends the attempt.
     */
    fun connectBleInBackground(deviceAddress: String) {
        scope.launch { connectBle(deviceAddress) }
    }

    /**
     * Build a ToRadio { want_config_id } protobuf payload. Tag 0x18 is
     * field 3, wire type 0 (varint). The radio responds by streaming
     * NodeInfo / Channel / Config / ModuleConfig frames terminated by
     * a ConfigComplete with this same id. Matches iOS's buildWantConfig.
     */
    private fun buildWantConfig(): ByteArray {
        val configId = (1..Int.MAX_VALUE).random().toULong()
        return ByteArrayOutputStream().apply {
            write(0x18) // field 3, wire type 0
            // varint encode configId
            var v = configId
            while (v >= 0x80u) {
                write(((v and 0x7Fu) or 0x80u).toInt())
                v = v shr 7
            }
            write(v.toInt())
        }.toByteArray()
    }

    /**
     * Begin a BLE scan and return a flow of discovered devices. The
     * scan auto-stops after ~10 s; callers can invoke [stopBleScan]
     * earlier (e.g. when the user taps a result).
     */
    suspend fun startBleScan(timeoutMs: Long = 10_000): Flow<MeshtasticBleClient.BleScanResult>? {
        val client = bleClientOrNull() ?: return null
        client.startScan(timeoutMs)
        return client.scanResults
    }

    fun stopBleScan() {
        bleClient?.stopScan()
    }

    /** [MeshFrameworkManager] scan — maps the Meshtastic BLE scan results
     *  into the framework-neutral [MeshScanResult] the picker consumes. */
    override suspend fun startMeshScan(timeoutMs: Long): Flow<MeshScanResult>? =
        startBleScan(timeoutMs)?.map { MeshScanResult(it.name, it.address, it.rssi) }

    override fun stopMeshScan() = stopBleScan()

    /** RSSI of the active BLE link, or null if BLE not initialized. */
    fun bleRssi(): StateFlow<Int>? = bleClient?.rssi

    /** #203 — the BLE client's most recent connect/link failure, or null if
     *  BLE not initialized. */
    fun bleLastFailure(): StateFlow<MeshtasticBleClient.BleFailure?>? = bleClient?.lastFailure

    /** #203 — text block for the BLE pane's "Copy diagnostics" action (last
     *  failure + BLE log ring buffer). */
    fun bleDiagnosticsSnapshot(): String = bleClient?.diagnosticsSnapshot() ?: "BLE not initialized"

    /** #203 — push the operator's verbose-BLE-logging preference down to the
     *  BLE client, constructing it (like [ensureBleReady]) if a [Context] is
     *  available; a no-op only when the manager was built without one. */
    fun setVerboseBleLogging(enabled: Boolean) {
        bleClientOrNull()?.verboseLoggingEnabled = enabled
    }

    override fun disconnect() {
        when (_activeTransport.value) {
            MeshConnectionType.TCP -> tcpClient.disconnect()
            MeshConnectionType.BLUETOOTH -> {
                // User-initiated — stop the auto-reconnect loop from
                // immediately reclaiming the link. An involuntary drop
                // (radio power-off / out of range) never calls this, so
                // reconnectTargetAddress stays set for that case.
                reconnectTargetAddress = null
                // #203 — a user-initiated disconnect isn't a failure streak;
                // don't carry a stale attempt count into the next session.
                _reconnectAttempt.value = 0
                // Fire-and-forget — the BLE client's own scope handles
                // the suspending teardown, and the connection observer
                // flips state to Disconnected.
                scope.launch { bleClient?.disconnectClean() }
            }
            null -> Unit
        }
        frameCollector?.cancel()
        frameCollector = null
        _activeTransport.value = null
    }

    internal fun dispatchFrame(frame: ByteArray) {
        lastFrameNanos = System.nanoTime()
        bytesRx += frame.size
        val parsed = MeshtasticProtoParser.parseFromRadio(frame)
        when (parsed) {
            is FromRadioFrame.NodeInfoFrame -> {
                upsertNode(parsed.node)
                reportOwnNodeInfo(parsed)
            }
            is FromRadioFrame.Packet -> handlePacket(parsed.packet)
            is FromRadioFrame.MyInfo -> {
                _myNodeNum = parsed.nodeNum
                // The first frame of a session: no read of an earlier one is waiting for an answer any more,
                // and the download that follows is not finished.
                adminReads.clear()
                downloadComplete = false
                // This download reports from nothing, and a note about a push is only for the radio it went to.
                reported = RadioSettings()
                synchronized(noteLock) {
                    if (lastPush?.node != parsed.nodeNum) {
                        lastPush = null
                        _restartNote.value = null
                    }
                }
                // First of everything the app sends to this radio: close an edit transaction a lost link left open,
                // or say it is gone. The writer is held until that is decided.
                settleInterrupted(parsed.nodeNum, parsed.rebootCount)
                rebootCount = parsed.rebootCount
                Log.i(TAG, "my_node_num=${parsed.nodeNum}")
            }
            is FromRadioFrame.ConfigComplete -> {
                Log.i(TAG, "config complete id=${parsed.id}")
                downloadCompletedNanos = System.nanoTime()
                downloadComplete = true
                judgeLastPush()
                decideFromDownload()
            }
            is FromRadioFrame.ConfigFrame -> {
                Log.i(TAG, "RX FromRadio.config (post-want_config_id dump): ${parsed.response ?: "variant with no decoded value"}")
                // The settings screen only decodes device, position and lora;
                // the other variants still reach the settings cache below.
                parsed.response?.let { report(it) }
            }
            is FromRadioFrame.ChannelFrame -> {
                Log.i(TAG, "RX FromRadio.channel: ${parsed.response}")
                report(parsed.response)
            }
            is FromRadioFrame.Unknown -> Log.v(TAG, "unrecognised FromRadio frame (${frame.size}B)")
            null -> Log.w(TAG, "frame parse returned null (${frame.size}B)")
        }
        // After the branch above, so my_info has already set our node number
        // when the cache checks that a NodeInfo or admin response is ours.
        if (parsed != null) {
            runCatching { radioSettings.onFromRadio(parsed, _myNodeNum) }
                .onFailure { Log.w(TAG, "settings cache could not take a frame: ${it.message}") }
        }
    }

    fun upsertNode(node: MeshNode) {
        val existing = _nodes.value[node.id]
        // Merge with existing entry — incoming NodeInfo frames don't
        // always carry every field we've previously learned (e.g. a
        // late battery telemetry frame would otherwise wipe a known
        // position).
        val merged = if (existing != null) node.copy(
            position = node.position ?: existing.position,
            snr = node.snr ?: existing.snr,
            hopDistance = node.hopDistance ?: existing.hopDistance,
            batteryLevel = node.batteryLevel ?: existing.batteryLevel,
            lastHeardEpoch = node.lastHeardEpoch ?: existing.lastHeardEpoch,
            shortName = node.shortName.ifBlank { existing.shortName },
            longName = node.longName.ifBlank { existing.longName },
            role = node.role ?: existing.role,
        ) else node
        _nodes.value = _nodes.value + (merged.id to merged)
    }

    fun clearNodes() {
        _nodes.value = emptyMap()
    }

    private fun handlePacket(packet: soy.engindearing.omnitak.mobile.data.MeshPacketDecoded) {
        when (packet.portnum.toInt()) {
            PORTNUM_POSITION_APP -> {
                val pos = MeshtasticProtoParser.parsePosition(packet.payload) ?: return
                val nodeId = packet.from.toLong() and 0xFFFFFFFFL
                val existing = _nodes.value[nodeId]
                // A packet that just came off the radio means the node was heard
                // now. The radio's rx_time is preferred, but it is absent when the
                // radio has no clock, so fall back to the phone's.
                val heardAt = packet.rxTime ?: (System.currentTimeMillis() / 1000)
                if (existing != null) {
                    upsertNode(existing.copy(position = pos, lastHeardEpoch = heardAt))
                } else {
                    upsertNode(
                        MeshNode(
                            id = nodeId,
                            shortName = "%04X".format((nodeId and 0xFFFFL).toInt()),
                            longName = "Node %08X".format(nodeId.toInt()),
                            position = pos,
                            lastHeardEpoch = heardAt,
                            snr = packet.rxSnr?.toDouble(),
                        ),
                    )
                }
            }
            PORTNUM_ATAK_PLUGIN_V2 -> {
                // #171 — TAKPacketV2 (port 78) marker. Decode the 0xFF
                // uncompressed envelope into a CoTEvent and push to cotSink so
                // it lands on the MAP as a marker (not chat), deduped by uid.
                // 0x00/0x01 dict-compressed bodies decode to null and are
                // dropped (we don't ship the GPL zstd dictionary).
                val event = TakPacketV2Codec.decode(packet.payload)
                if (event != null) {
                    runCatching { cotSink?.invoke(event) }
                        .onFailure { Log.w(TAG, "cotSink failed for TAKPacketV2 marker: ${it.message}") }
                    Log.i(
                        TAG,
                        "RX TAKPacketV2 marker from ${packet.from.toString(16)} -> CoT ${event.uid} (${packet.payload.size}B)",
                    )
                } else {
                    Log.w(
                        TAG,
                        "RX TAKPacketV2 from ${packet.from.toString(16)} undecodable (non-0xFF envelope?), ${packet.payload.size}B",
                    )
                }
            }
            PORTNUM_ATAK_PLUGIN, PORTNUM_ATAK_FORWARDER -> {
                // Phase 2: try TAKPacket (atak.proto) first for interop with stock
                // Meshtastic ATAK Plugin / gateway. Fall back to Phase-1 TAKMessage
                // parser for OmniTAK-to-OmniTAK links and older clients.
                val event = TakPacketParser.parse(packet.payload, packet.from)
                    ?: AtakPluginParser.parse(packet.payload)
                if (event != null) {
                    runCatching { cotSink?.invoke(event) }
                        .onFailure { Log.w(TAG, "cotSink failed for ATAK plugin event: ${it.message}") }
                    Log.i(
                        TAG,
                        "RX ATAK plugin from ${packet.from.toString(16)} -> CoT ${event.uid} (bytes=${packet.payload.size})",
                    )
                } else {
                    Log.w(
                        TAG,
                        "RX ATAK plugin from ${packet.from.toString(16)} unparseable (tried TAKPacket+TAKMessage), ${packet.payload.size}B",
                    )
                }
            }
            PORTNUM_ADMIN_APP -> {
                // GAP-109 read-back: the radio's answer to one of our get_*_request
                // messages. The radio hands the phone any admin message addressed
                // to it, so a packet being here is not evidence of what the radio
                // holds. It counts only as the answer to a read of ours, from the
                // radio itself ([AdminReads]); anything else is ignored, so
                // nothing else reaches the cache, the settings state or a write.
                val decision = adminReads.admit(packet, _myNodeNum)
                val answer = decision.answer
                if (decision.admission != AdminReads.Admission.ACCEPTED || answer == null) {
                    Log.w(TAG, "ignored an admin message: ${decision.admission.reason}")
                    return
                }
                radioSettings.put(answer.key, answer.bytes)
                AdminMessageParser.parse(packet.payload)?.let { response ->
                    Log.i(TAG, "RX admin response: $response")
                    report(response)
                }
                // Last, so a write waiting for this answer goes on with the cache and the screen up to date.
                decision.deliver()
            }
            PORTNUM_TEXT_MESSAGE_APP -> {
                // GAP-122 — Meshtastic text message. Payload is plain UTF-8.
                // GAP-124 — directed packets (packet.to == my node num) are
                // surfaced as DM conversations "MESH-DM-{otherNodeId}";
                // broadcasts (packet.to == 0xFFFFFFFF) stay on channel
                // conversations "MESH-CHn".
                //
                // Echo skip: when our own outgoing text round-trips through
                // the radio it comes back with from == my_node_num. The TX
                // path already inserted the message via markOutgoing, so
                // ingesting again would double-display it.
                val myNum = _myNodeNum
                if (myNum != null && packet.from == myNum) return
                val text = runCatching { String(packet.payload, Charsets.UTF_8) }.getOrNull()
                if (text.isNullOrEmpty()) return
                val nodeId = packet.from.toLong() and 0xFFFFFFFFL
                val node = _nodes.value[nodeId]
                val callsign = node?.longName?.takeIf { it.isNotBlank() }
                    ?: node?.shortName?.takeIf { it.isNotBlank() }
                    ?: "Node ${"%08x".format(nodeId.toInt())}"
                val now = System.currentTimeMillis()
                val nowIso = soy.engindearing.omnitak.mobile.data.CotXml.isoSeconds(now)
                val isDm = myNum != null && packet.to != BROADCAST_ADDR && packet.to == myNum
                val conversationId = if (isDm) {
                    meshDmConversationId(nodeId)
                } else {
                    meshConversationId(packet.channel.toInt())
                }
                val msg = ChatMessage(
                    conversationId = conversationId,
                    senderUid = "MESHTASTIC-${"%08X".format(nodeId.toInt())}",
                    senderCallsign = callsign,
                    text = text,
                    timeIso = nowIso,
                    status = ChatStatus.RECEIVED,
                    isFromSelf = false,
                )
                Log.i(
                    TAG,
                    if (isDm) "RX mesh DM from $callsign: $text"
                    else "RX mesh text from $callsign on ch${packet.channel}: $text",
                )
                runCatching { chatSink?.invoke(msg) }
                    .onFailure { Log.w(TAG, "chatSink failed: ${it.message}") }
            }
            else -> Log.v(TAG, "MeshPacket portnum=${packet.portnum} from=${packet.from} payload=${packet.payload.size}B")
        }
    }

    /**
     * GAP-122 — listener for decoded Meshtastic text messages. Wired in
     * [OmniTAKApp] to [ChatStore.ingest] so the Chat tab surfaces them
     * in a "Mesh: channel N" conversation.
     */
    @Volatile override var chatSink: ((ChatMessage) -> Unit)? = null

    /**
     * GAP-122 — send a text message over the Meshtastic transport on
     * the requested channel. Builds a ToRadio with portnum=1
     * (TEXT_MESSAGE_APP) and dispatches via the active TCP / BLE.
     * Returns true on successful wire-layer dispatch.
     *
     * GAP-124 — when [toNodeId] is non-null the message is sent as a
     * directed packet (DM) by setting `MeshPacket.to` to that nodeNum
     * instead of the broadcast address. Recipients see it as a DM in
     * conversation "MESH-DM-<myNodeId>".
     */
    override suspend fun sendMeshChat(text: String, channelIndex: Int, toNodeId: UInt?): Boolean {
        if (text.isEmpty()) return false
        val transport = _activeTransport.value ?: return false
        val payload = text.toByteArray(Charsets.UTF_8)
        val frame = buildTextMessageToRadio(payload, channelIndex.toUInt(), toNodeId ?: BROADCAST_ADDR)
        return when (transport) {
            MeshConnectionType.TCP -> tcpClient.sendBytes(frame)
            MeshConnectionType.BLUETOOTH -> bleClient?.sendToRadio(frame) ?: false
        }
    }

    /** Build a ToRadio { MeshPacket { Data { portnum=1, payload } } } frame.
     *  `to` is broadcast (0xFFFFFFFF) for channel-wide chat, a specific
     *  nodeNum for DMs (GAP-124). Framing lives in [MeshWire]. */
    private fun buildTextMessageToRadio(text: ByteArray, channelIndex: UInt, toNodeNum: UInt): ByteArray =
        soy.engindearing.omnitak.mobile.data.MeshWire.buildToRadio(
            portnum = PORTNUM_TEXT_MESSAGE_APP.toULong(),
            payload = text,
            to = toNodeNum,
            channelIndex = channelIndex,
        )

    /** Conversation id used by [ChatStore] to bucket incoming mesh text by channel. */
    fun meshConversationId(channelIndex: Int): String = "MESH-CH$channelIndex"

    /** GAP-124 — conversation id used by [ChatStore] to bucket directed
     *  mesh text by the *other* party's nodenum. Both my outgoing DM to
     *  node X and X's reply to me end up in the same bucket. */
    fun meshDmConversationId(nodeId: Long): String =
        "MESH-DM-${"%08X".format(nodeId.toInt())}"

    /**
     * GAP-109 read-back: listener for what the connected radio reports about
     * its settings: the config download, and the answers to our own requests.
     * Wired in [OmniTAKApp] to [MeshDeviceConfigStore.applyAdminResponse].
     * Only the radio's own reports reach it (admin messages from any other node
     * are dropped before this point).
     */
    @Volatile var adminResponseSink: ((AdminResponse) -> Unit)? = null

    /**
     * Called when the link to the radio drops, so what the radio reported stops counting as what it
     * holds. Wired in [OmniTAKApp] to [MeshDeviceConfigStore.onLinkDown].
     */
    @Volatile var linkDownSink: (() -> Unit)? = null

    /** One report from our own radio: hand it on, and check it against what we last sent that radio. */
    private fun report(response: AdminResponse) {
        runCatching { adminResponseSink?.invoke(response) }
            .onFailure { Log.w(TAG, "adminResponseSink failed: ${it.message}") }
        val node = _myNodeNum ?: return
        reported = DeviceSettingsState(radio = reported).withReport(response).radio ?: reported
        val kept = sentLedger.check(node, response)
        if (kept.isNotEmpty()) {
            _settingsNotice.value = "The radio did not take: ${kept.joinToString(", ") { it.label }}. It may be managed."
        }
    }

    /** The radio's own NodeInfo carries its owner record, so the names reach the settings screen without a request. */
    private fun reportOwnNodeInfo(frame: FromRadioFrame.NodeInfoFrame) {
        val me = _myNodeNum ?: return
        if (frame.node.id != (me.toLong() and 0xFFFFFFFFL)) return
        val user = frame.userRaw?.let { ProtoFields.parse(it) } ?: return
        report(
            AdminResponse.Owner(
                longName = ProtoFields.lastString(user, USER_LONG_NAME) ?: "",
                shortName = ProtoFields.lastString(user, USER_SHORT_NAME) ?: "",
            ),
        )
    }

    /**
     * Ask the connected radio for its owner / device role / PLI cadence /
     * LoRa config / channels. Sends 12 admin requests, spaced like writes and
     * taking their turn behind any write in progress; the answers arrive
     * asynchronously via [adminResponseSink].
     *
     * The requests wait until the radio has finished its config download
     * (`config_complete_id`) and the link has been quiet for a moment, so they
     * do not land in the middle of the stream: the firmware keeps few packets
     * for the phone and drops the oldest when they pile up, without telling
     * anyone. The wait is bounded and gives up when the link drops. Reading
     * back after a push goes through here too.
     *
     * Returns the count of requests that went out, 0 when there is no radio.
     */
    suspend fun requestDeviceConfig(): Int {
        awaitDownloadSettled()
        return settingsWriter.readAll()
    }

    /** Wait for the config download to finish and the link to go quiet. Returns at once when no radio is attached. */
    internal suspend fun awaitDownloadSettled() {
        withTimeoutOrNull(settleTimeoutMs) {
            while (true) {
                // No radio to read from: nothing to wait for, and the read will say so.
                if (!adminLinkUp() || adminDestination() == null) return@withTimeoutOrNull
                if (downloadComplete) {
                    val now = System.nanoTime()
                    val quietMs = (now - lastFrameNanos) / 1_000_000L
                    val sinceCompleteMs = (now - downloadCompletedNanos) / 1_000_000L
                    // A busy mesh may never go quiet: the quiet wait is only for the moments after the download.
                    if (quietMs >= settleQuietMs || sinceCompleteMs >= SETTLE_MAX_QUIET_WAIT_MS) return@withTimeoutOrNull
                }
                delay(25)
            }
        }
    }

    /**
     * Send a CoT event over the active Meshtastic transport as a
     * portnum-72 ATAK-plugin payload. Returns true when the framed
     * ToRadio bytes are dispatched to the radio, false when no
     * transport is connected or the write fails.
     *
     * Dispatches by [activeTransport]: TCP writes go through the
     * 0x94C3-framing path on [MeshtasticTcpClient.sendBytes]; BLE
     * writes go through the toRadio characteristic on
     * [MeshtasticBleClient.sendToRadio] (chunked at the negotiated MTU).
     */
    override suspend fun sendCoTOverMesh(event: CoTEvent, channelIndex: UInt, ownPosition: Boolean): Boolean {
        // #171 — tactical MARKER CoT types ride TAKPacketV2 on port 78 so the
        // raw CoT type, color and iconset survive the hop (the v1 port-72 path
        // is PLI + GeoChat only and would degrade a marker to a text line).
        // This branch runs BEFORE the b-t-f / PLI split below.
        if (isTacticalMarker(event.type)) {
            return sendMarkerOverMesh(event, channelIndex)
        }

        // Phase 2: Emit standard TAKPacket (atak.proto) for interop with stock
        // Meshtastic ATAK Plugin, Meshtastic phone-app TAK role, and the
        // TAK_Meshtastic_Gateway. The MeshPacket wrapper still uses
        // AtakPluginSerializer.buildToRadio (portnum 72 framing).
        val payload = when {
            event.type == "b-t-f" -> TakPacketSerializer.serializeChat(event)
            else -> TakPacketSerializer.serializePli(event)
        }
        val toRadio = AtakPluginSerializer.buildToRadio(
            payloadBytes = payload,
            channelIndex = channelIndex,
        )
        return when (_activeTransport.value) {
            MeshConnectionType.TCP -> tcpClient.sendBytes(toRadio)
            MeshConnectionType.BLUETOOTH -> bleClient?.sendToRadio(toRadio) ?: false
            null -> false
        }
    }

    /**
     * #171 — send a tactical marker on port 78 (TAKPacketV2). Encodes the
     * 0xFF-envelope body via [TakPacketV2Codec]; broadcast (want_ack=false),
     * hop_limit 3. Debounced per-uid so a held save doesn't flood the channel.
     * Returns false when no transport is active, the marker is throttled, or
     * the encode exceeds the LoRa wire budget (caller may fall back to v1).
     */
    private suspend fun sendMarkerOverMesh(event: CoTEvent, channelIndex: UInt): Boolean {
        val now = System.currentTimeMillis()
        val last = markerLastSentMs[event.uid]
        if (last != null && now - last < MARKER_SEND_THROTTLE_MS) {
            Log.v(TAG, "marker ${event.uid} throttled (${now - last}ms since last send)")
            return false
        }

        val payload = TakPacketV2Codec.encodeMarker(event)
        if (payload == null) {
            Log.w(TAG, "marker ${event.uid} too large for TAKPacketV2 wire budget; not sent")
            return false
        }
        val toRadio = MeshWire.buildToRadio(
            portnum = PORTNUM_ATAK_PLUGIN_V2.toULong(),
            payload = payload,
            channelIndex = channelIndex,
            hopLimit = 3u,
            wantAck = false,
        )
        val sent = when (_activeTransport.value) {
            MeshConnectionType.TCP -> tcpClient.sendBytes(toRadio)
            MeshConnectionType.BLUETOOTH -> bleClient?.sendToRadio(toRadio) ?: false
            null -> false
        }
        if (sent) markerLastSentMs[event.uid] = now
        return sent
    }

    /**
     * GAP-109a: write the settings the operator edited to the connected
     * radio via portnum-6 (ADMIN_APP) AdminMessage payloads.
     *
     * [edits] names the settings that were changed and the values to send;
     * nothing else is written, whatever the draft holds. Each one is the
     * radio's own message, asked for again just before it is patched, with that
     * one field changed (the firmware replaces a whole config with what it
     * receives), and the role goes first. The writes ride one
     * `begin_edit_settings` / `commit_edit_settings` pair, so the radio saves
     * and reboots once. If the radio does not answer the read, nothing is
     * changed and the result says so.
     *
     * Doesn't wait for AdminMessage acks: those come back as
     * `FromRadio.routing` frames and would need protobuf decode we haven't
     * built yet (filed under GAP-109b). The radio's next report says what it
     * kept, and [settingsNotice] says when that differs from what was sent.
     * What the push said is kept in [lastPushResult], even if the screen that
     * started it is left before it finishes (the writer runs a started
     * sequence to its commit, and its result still comes back).
     */
    suspend fun pushDeviceConfig(edits: DeviceEdits): AdminWriteResult {
        // A new push replaces what the last one sent and what was said about it.
        synchronized(noteLock) {
            lastPush = null
            _restartNote.value = null
        }
        return settingsWriter.pushDeviceConfig(edits) { synchronized(noteLock) { lastPush = it } }
            .also { _lastPushResult.value = it.describe() }
    }

    /**
     * #172: import a [MeshChannel] (from a scanned/pasted
     * `meshtastic.org/e/#…` share) into the connected radio via a
     * `set_channel` AdminMessage. A full replacement of the slot it lands in,
     * by design: the shared name and key become that channel. It goes into the
     * first free secondary slot; the primary channel is replaced only when
     * [replacePrimary] says the operator asked for that.
     */
    suspend fun applyChannel(channel: MeshChannel, replacePrimary: Boolean = false): AdminWriteResult =
        settingsWriter.applyChannel(channel, replacePrimary)

    /**
     * #172 — set the radio's rebroadcast scope (PatoG1899's "known channels
     * only"). The radio's other device settings are carried over.
     */
    suspend fun applyRebroadcastMode(mode: RebroadcastMode): AdminWriteResult =
        settingsWriter.applyRebroadcastMode(mode)

    /**
     * #181 — set the radio's LoRa region + modem preset in one admin write
     * (`set_config { lora { use_preset, modem_preset, region } }`). Region is
     * the band a fresh radio needs before it will transmit; preset is the
     * range/throughput profile. Every other LoRa setting (hop limit, transmit
     * switch, ...) is carried over from what the radio reported.
     */
    suspend fun applyLoRaConfig(
        region: MeshRegion,
        preset: MeshChannelPreset?,
        usePreset: Boolean = true,
    ): AdminWriteResult = settingsWriter.applyLoRaConfig(region, preset, usePreset)

    /**
     * #181 — set the radio's owner (display name) via `set_owner { User }`.
     * Long name shows in the node list; short name is the 4-char tag. The rest
     * of the owner record, including the licensed flag, stays as the radio has
     * it unless [isLicensed] says otherwise.
     */
    suspend fun applyOwner(
        longName: String,
        shortName: String,
        isLicensed: Boolean? = null,
    ): AdminWriteResult = settingsWriter.applyOwner(longName, shortName, isLicensed)

    /**
     * #185 — the destination for an admin write: the node number of the radio
     * we are attached to. Null until the radio has reported it (`my_node_num`
     * arrives early in the FromRadio config stream). Broadcast is never a
     * valid admin destination — the firmware ignores it and the frame goes on
     * the air — so callers must refuse to send rather than fall back.
     */
    private fun adminDestination(): UInt? = _myNodeNum?.takeIf { it != 0u && it != BROADCAST_ADDR }

    /** True when an admin write has somewhere to go: a live link (or the test seam). */
    private fun adminLinkUp(): Boolean =
        adminSendOverride != null || activeConnectionState.value is ConnectionState.Connected

    /** Hand one framed admin ToRadio to the active transport. False when there is none or the write failed. */
    private suspend fun sendAdminFrame(bytes: ByteArray): Boolean {
        adminSendOverride?.let { return it(bytes) }
        return when (_activeTransport.value) {
            MeshConnectionType.TCP -> tcpClient.sendBytes(bytes)
            MeshConnectionType.BLUETOOTH -> bleClient?.sendToRadio(bytes) ?: false
            null -> false
        }
    }

    companion object {
        private const val TAG = "MeshtasticManager"

        /** The longest a read waits for the config download to finish before it goes out anyway. */
        const val SETTLE_TIMEOUT_MS = 20_000L

        /** How long the link has to be quiet after the download: what is still queued for the phone drains meanwhile. */
        const val SETTLE_QUIET_MS = 400L

        /** After this long past the end of the download the quiet wait is over, whatever traffic there is. */
        const val SETTLE_MAX_QUIET_WAIT_MS = 2_000L

        /** How often the BLE auto-reconnect loop checks whether the last
         *  radio is back in range. */
        private const val BLE_RECONNECT_INTERVAL_MS: Long = 20_000

        /** #203 — upper bound on one automatic reconnect attempt, above the
         *  BLE client's own deadlines (a pairing, the setup after it, quick
         *  retries and the handshake write). */
        private const val BLE_RECONNECT_ATTEMPT_CAP_MS: Long = 180_000

        /** #203 — immediate retries after a connect that failed on a stack
         *  error before the link was up. */
        private const val BLE_QUICK_RETRIES: Int = 2
        private const val BLE_QUICK_RETRY_DELAY_MS: Long = 300
        private const val PORTNUM_TEXT_MESSAGE_APP = 1
        private const val PORTNUM_POSITION_APP = 3
        private const val PORTNUM_ADMIN_APP = 6
        /** Meshtastic broadcast address — channel-wide chat / position / etc. */
        private val BROADCAST_ADDR: UInt = 0xFFFFFFFFu
        // User (mesh.proto) field numbers read off our own NodeInfo.
        private const val USER_LONG_NAME = 2
        private const val USER_SHORT_NAME = 3

        private const val PORTNUM_ATAK_PLUGIN = 72
        // Some ATAK plugin builds send via portnum 257 (ATAK_FORWARDER)
        // — accept both so OmniTAK can interop with both clients.
        private const val PORTNUM_ATAK_FORWARDER = 257
        // #171 — TAKPacketV2 markers ride port 78 (ATAK_PLUGIN_V2).
        private const val PORTNUM_ATAK_PLUGIN_V2 = 78
        // #171 — debounce repeat sends of the same marker uid so a held
        // map-drop doesn't flood the LoRa channel.
        private const val MARKER_SEND_THROTTLE_MS = 30_000L

        /** The bare friendly-ground-unit PLI type self/contacts broadcast.
         *  Shares its `a-f-G-U-` prefix with friendly markers, so it must be
         *  excluded explicitly or self-PLI would misroute to port 78. */
        private const val PLI_CONTACT_TYPE = "a-f-G-U-C"

        /**
         * #171 — true when [type] is a tactical marker that should ride
         * TAKPacketV2 (port 78) rather than the v1 PLI/GeoChat path:
         *  - `a-u-*`     unknown-affiliation map markers
         *  - `a-h-*`     hostile map markers
         *  - `a-f-G-U-*` friendly ground-unit markers (operator-dropped),
         *                EXCEPT the bare `a-f-G-U-C` PLI type, which is the
         *                self/contact position report and keeps v1 routing
         *  - `b-m-p-*`   bookmark map points (waypoint / spot / checkpoint)
         *
         * GeoChat (`b-t-f`) and plain PLI are intentionally excluded so they
         * keep their existing v1 routing.
         */
        fun isTacticalMarker(type: String): Boolean {
            if (type == PLI_CONTACT_TYPE) return false
            return type.startsWith("a-u-") ||
                type.startsWith("a-h-") ||
                type.startsWith("a-f-G-U-") ||
                type.startsWith("b-m-p-")
        }
    }
}
