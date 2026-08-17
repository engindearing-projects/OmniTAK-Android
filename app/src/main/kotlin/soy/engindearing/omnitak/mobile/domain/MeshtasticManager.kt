package soy.engindearing.omnitak.mobile.domain

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import java.io.ByteArrayOutputStream
import soy.engindearing.omnitak.mobile.data.AdminMessageParser
import soy.engindearing.omnitak.mobile.data.AdminMessageSerializer
import soy.engindearing.omnitak.mobile.data.AdminResponse
import soy.engindearing.omnitak.mobile.data.AtakPluginParser
import soy.engindearing.omnitak.mobile.data.ChatMessage
import soy.engindearing.omnitak.mobile.data.ChatStatus
import soy.engindearing.omnitak.mobile.data.MeshChannel
import soy.engindearing.omnitak.mobile.data.MeshChannelPreset
import soy.engindearing.omnitak.mobile.data.MeshDeviceConfig
import soy.engindearing.omnitak.mobile.data.MeshRegion
import soy.engindearing.omnitak.mobile.data.RebroadcastMode
import soy.engindearing.omnitak.mobile.data.AtakPluginSerializer
import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.TakPacketParser
import soy.engindearing.omnitak.mobile.data.TakPacketSerializer
import soy.engindearing.omnitak.mobile.data.TakPacketV2Codec
import soy.engindearing.omnitak.mobile.data.MeshWire
import soy.engindearing.omnitak.mobile.data.FromRadioFrame
import soy.engindearing.omnitak.mobile.data.MeshConnectionType
import soy.engindearing.omnitak.mobile.data.MeshNode
import soy.engindearing.omnitak.mobile.data.MeshtasticBleClient
import soy.engindearing.omnitak.mobile.data.MeshtasticProtoParser
import soy.engindearing.omnitak.mobile.data.MeshtasticTcpClient

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
class MeshtasticManager(private val context: Context? = null) : MeshFrameworkManager {

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
        frameCollector = scope.launch {
            client.frames.collect { frame -> dispatchFrame(frame) }
        }
        val ok = client.connectToAddress(deviceAddress)
        if (ok) {
            // Critical Meshtastic handshake: ask the radio to dump its
            // config + node database. Without this the radio doesn't
            // push any state and the node list stays empty.
            client.sendToRadio(buildWantConfig())
            Log.i(TAG, "TX want_config_id (BLE)")
        }
        return ok
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

    override fun disconnect() {
        when (_activeTransport.value) {
            MeshConnectionType.TCP -> tcpClient.disconnect()
            MeshConnectionType.BLUETOOTH -> {
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

    private fun dispatchFrame(frame: ByteArray) {
        bytesRx += frame.size
        when (val parsed = MeshtasticProtoParser.parseFromRadio(frame)) {
            is FromRadioFrame.NodeInfoFrame -> upsertNode(parsed.node)
            is FromRadioFrame.Packet -> handlePacket(parsed.packet)
            is FromRadioFrame.MyInfo -> {
                _myNodeNum = parsed.nodeNum
                Log.i(TAG, "my_node_num=${parsed.nodeNum}")
            }
            is FromRadioFrame.ConfigComplete -> Log.i(TAG, "config complete id=${parsed.id}")
            is FromRadioFrame.ConfigFrame -> {
                Log.i(TAG, "RX FromRadio.config (post-want_config_id dump): ${parsed.response}")
                runCatching { adminResponseSink?.invoke(parsed.response) }
                    .onFailure { Log.w(TAG, "adminResponseSink (config) failed: ${it.message}") }
            }
            is FromRadioFrame.ChannelFrame -> {
                Log.i(TAG, "RX FromRadio.channel: ${parsed.response}")
                runCatching { adminResponseSink?.invoke(parsed.response) }
                    .onFailure { Log.w(TAG, "adminResponseSink (channel) failed: ${it.message}") }
            }
            is FromRadioFrame.Unknown -> Log.v(TAG, "unrecognised FromRadio frame (${frame.size}B)")
            null -> Log.w(TAG, "frame parse returned null (${frame.size}B)")
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
                if (existing != null) {
                    upsertNode(existing.copy(position = pos, lastHeardEpoch = packet.rxTime ?: existing.lastHeardEpoch))
                } else {
                    upsertNode(
                        MeshNode(
                            id = nodeId,
                            shortName = "%04X".format((nodeId and 0xFFFFL).toInt()),
                            longName = "Node %08X".format(nodeId.toInt()),
                            position = pos,
                            lastHeardEpoch = packet.rxTime ?: (System.currentTimeMillis() / 1000),
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
                // GAP-109 read-back — radio's response to one of our
                // get_*_request admin messages. Decode and notify the
                // listener so MeshDeviceConfigStore can mirror radio state.
                val response = AdminMessageParser.parse(packet.payload)
                if (response != null) {
                    Log.i(TAG, "RX admin response: $response")
                    runCatching { adminResponseSink?.invoke(response) }
                        .onFailure { Log.w(TAG, "adminResponseSink failed: ${it.message}") }
                } else {
                    Log.v(TAG, "RX admin packet from=${packet.from} payload=${packet.payload.size}B (unrecognised)")
                }
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
     * GAP-109 read-back — listener for decoded AdminMessage responses.
     * Wired in [OmniTAKApp] to [MeshDeviceConfigStore.applyAdminResponse]
     * so the Device Settings screen reflects the radio's actual state
     * after a `requestDeviceConfig()` round-trip.
     */
    @Volatile var adminResponseSink: ((AdminResponse) -> Unit)? = null

    /**
     * Ask the connected radio for its current owner / device role / PLI
     * cadence / LoRa preset / primary-channel name. Sends 5 admin
     * requests; responses arrive asynchronously via [adminResponseSink].
     *
     * No-op when no transport is active. Returns the count successfully
     * dispatched so the caller can toast on partial / total failure.
     */
    suspend fun requestDeviceConfig(): Int {
        val transport = _activeTransport.value ?: return 0
        // GAP-123 — ask for all 8 channel slots (Meshtastic firmware caps
        // at 8). Disabled slots come back with role=0 and are filtered
        // out at the chat seeding layer; non-disabled ones become chat
        // conversations with the operator's actual channel names.
        val channelRequests = (0 until 8).map { idx ->
            AdminMessageSerializer.buildGetChannelRequest(idx)
        }
        val requests = listOf(
            AdminMessageSerializer.buildGetOwnerRequest(),
            AdminMessageSerializer.buildGetConfigRequest(GET_CONFIG_DEVICE),
            AdminMessageSerializer.buildGetConfigRequest(GET_CONFIG_POSITION),
            AdminMessageSerializer.buildGetConfigRequest(GET_CONFIG_LORA),
        ) + channelRequests
        var sent = 0
        for (bytes in requests) {
            val ok = when (transport) {
                MeshConnectionType.TCP -> tcpClient.sendBytes(bytes)
                MeshConnectionType.BLUETOOTH -> bleClient?.sendToRadio(bytes) ?: false
            }
            if (ok) sent += 1 else break
        }
        return sent
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
    override suspend fun sendCoTOverMesh(event: CoTEvent, channelIndex: UInt): Boolean {
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
     * GAP-109a — push the operator's draft device config to the connected
     * radio via portnum-6 (ADMIN_APP) AdminMessage payloads.
     *
     * Splits the config across four admin messages because the firmware
     * groups settings into separate protobuf submessages. Sends them
     * sequentially over the active transport; each one is a fully-framed
     * `ToRadio`, so a single missed write doesn't corrupt the others.
     *
     * Returns the count of messages successfully dispatched (0..4). The
     * caller can surface this to the operator — e.g. "3 of 4 settings
     * pushed; retry?". Doesn't wait for AdminMessage acks: those come
     * back as `FromRadio.routing` frames and would need protobuf decode
     * we haven't built yet (filed under GAP-109b).
     */
    suspend fun pushDeviceConfig(config: MeshDeviceConfig): Int {
        val transport = _activeTransport.value ?: return 0

        val messages = listOf(
            AdminMessageSerializer.buildSetOwner(config.longName, config.shortName),
            AdminMessageSerializer.buildSetDeviceRole(config.role),
            AdminMessageSerializer.buildSetPositionBroadcastSecs(config.positionBroadcastSecs),
            AdminMessageSerializer.buildSetChannel0Name(config.channelName),
            AdminMessageSerializer.buildSetLoraPreset(config.channelPreset),
        )
        var sent = 0
        for (bytes in messages) {
            val ok = when (transport) {
                MeshConnectionType.TCP -> tcpClient.sendBytes(bytes)
                MeshConnectionType.BLUETOOTH -> bleClient?.sendToRadio(bytes) ?: false
            }
            if (ok) sent += 1 else break // bail on first failure so we don't wedge mid-write
        }
        return sent
    }

    /**
     * #172 — push an imported [MeshChannel] (from a scanned/pasted
     * `meshtastic.org/e/#…` share) onto the connected radio at [index] via a
     * `set_channel` AdminMessage. Returns true on wire-layer dispatch.
     */
    suspend fun applyChannel(channel: MeshChannel, index: Int = 0): Boolean =
        dispatchAdmin(AdminMessageSerializer.buildSetChannel(channel, index))

    /**
     * #172 — set the radio's rebroadcast scope (PatoG1899's "known channels
     * only"). Returns true on wire-layer dispatch.
     */
    suspend fun applyRebroadcastMode(mode: RebroadcastMode): Boolean =
        dispatchAdmin(AdminMessageSerializer.buildSetRebroadcastMode(mode))

    /**
     * #181 — set the radio's LoRa region + modem preset in one admin write
     * (`set_config { lora { use_preset, modem_preset, region } }`). Region is
     * the band a fresh radio needs before it will transmit; preset is the
     * range/throughput profile. Returns true on wire-layer dispatch.
     */
    suspend fun applyLoRaConfig(
        region: MeshRegion,
        preset: MeshChannelPreset,
        usePreset: Boolean = true,
    ): Boolean =
        dispatchAdmin(AdminMessageSerializer.buildSetLoRaConfig(region, preset, usePreset))

    /**
     * #181 — set the radio's owner (display name) via `set_owner { User }`.
     * Long name shows in the node list; short name is the 4-char tag. Returns
     * true on wire-layer dispatch.
     */
    suspend fun applyOwner(
        longName: String,
        shortName: String,
        isLicensed: Boolean = false,
    ): Boolean =
        dispatchAdmin(AdminMessageSerializer.buildSetOwner(longName, shortName, isLicensed = isLicensed))

    /** Dispatch one already-framed ToRadio admin blob over the active transport. */
    private suspend fun dispatchAdmin(toRadio: ByteArray): Boolean =
        when (_activeTransport.value) {
            MeshConnectionType.TCP -> tcpClient.sendBytes(toRadio)
            MeshConnectionType.BLUETOOTH -> bleClient?.sendToRadio(toRadio) ?: false
            null -> false
        }

    companion object {
        private const val TAG = "MeshtasticManager"
        private const val PORTNUM_TEXT_MESSAGE_APP = 1
        private const val PORTNUM_POSITION_APP = 3
        private const val PORTNUM_ADMIN_APP = 6
        /** Meshtastic broadcast address — channel-wide chat / position / etc. */
        private val BROADCAST_ADDR: UInt = 0xFFFFFFFFu
        // ConfigType enum values (admin.proto)
        private const val GET_CONFIG_DEVICE = 0
        private const val GET_CONFIG_POSITION = 1
        private const val GET_CONFIG_LORA = 5

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
