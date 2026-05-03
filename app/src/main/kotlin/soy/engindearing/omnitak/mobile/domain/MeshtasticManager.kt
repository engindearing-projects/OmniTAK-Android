package soy.engindearing.omnitak.mobile.domain

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import soy.engindearing.omnitak.mobile.data.AdminMessageParser
import soy.engindearing.omnitak.mobile.data.AdminMessageSerializer
import soy.engindearing.omnitak.mobile.data.AdminResponse
import soy.engindearing.omnitak.mobile.data.AtakPluginParser
import soy.engindearing.omnitak.mobile.data.ChatMessage
import soy.engindearing.omnitak.mobile.data.ChatStatus
import soy.engindearing.omnitak.mobile.data.MeshDeviceConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import soy.engindearing.omnitak.mobile.data.AtakPluginSerializer
import soy.engindearing.omnitak.mobile.data.CoTEvent
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
class MeshtasticManager(private val context: Context? = null) {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _nodes = MutableStateFlow<Map<Long, MeshNode>>(emptyMap())
    val nodes: StateFlow<Map<Long, MeshNode>> = _nodes.asStateFlow()

    val tcpClient = MeshtasticTcpClient()
    private var bleClient: MeshtasticBleClient? = null

    private val _activeTransport = MutableStateFlow<MeshConnectionType?>(null)
    val activeTransport: StateFlow<MeshConnectionType?> = _activeTransport.asStateFlow()

    private var frameCollector: Job? = null
    private var bytesRx: Long = 0L
    @Volatile private var _myNodeNum: UInt? = null
    val myNodeNum: UInt? get() = _myNodeNum

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
    @Volatile var cotSink: ((CoTEvent) -> Unit)? = null

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
    suspend fun connectBle(deviceAddress: String): Boolean {
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

    /** RSSI of the active BLE link, or null if BLE not initialized. */
    fun bleRssi(): StateFlow<Int>? = bleClient?.rssi

    fun disconnect() {
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
            PORTNUM_ATAK_PLUGIN, PORTNUM_ATAK_FORWARDER -> {
                val event = AtakPluginParser.parse(packet.payload)
                if (event != null) {
                    runCatching { cotSink?.invoke(event) }
                        .onFailure { Log.w(TAG, "cotSink failed for ATAK plugin event: ${it.message}") }
                    Log.i(
                        TAG,
                        "RX ATAK plugin from ${packet.from.toString(16)} -> CoT ${event.uid} ($PORTNUM_ATAK_PLUGIN bytes=${packet.payload.size})",
                    )
                } else {
                    Log.w(
                        TAG,
                        "RX ATAK plugin from ${packet.from.toString(16)} unparseable, ${packet.payload.size}B",
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
                // We surface it as a ChatMessage in conversation "MESH-CHn"
                // where n is the channel index it arrived on. The Chat tab
                // picks these up via the chatSink wired in OmniTAKApp.
                val text = runCatching { String(packet.payload, Charsets.UTF_8) }.getOrNull()
                if (text.isNullOrEmpty()) return
                val nodeId = packet.from.toLong() and 0xFFFFFFFFL
                val node = _nodes.value[nodeId]
                val callsign = node?.longName?.takeIf { it.isNotBlank() }
                    ?: node?.shortName?.takeIf { it.isNotBlank() }
                    ?: "Node ${"%08x".format(nodeId.toInt())}"
                val now = System.currentTimeMillis()
                val nowIso = chatTimeFormatter.format(Date(now))
                val conversationId = meshConversationId(packet.channel.toInt())
                val msg = ChatMessage(
                    conversationId = conversationId,
                    senderUid = "MESHTASTIC-${"%08X".format(nodeId.toInt())}",
                    senderCallsign = callsign,
                    text = text,
                    timeIso = nowIso,
                    status = ChatStatus.RECEIVED,
                    isFromSelf = false,
                )
                Log.i(TAG, "RX mesh text from $callsign on ch${packet.channel}: $text")
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
    @Volatile var chatSink: ((ChatMessage) -> Unit)? = null

    /**
     * GAP-122 — send a text message over the Meshtastic transport on
     * the requested channel. Builds a ToRadio with portnum=1
     * (TEXT_MESSAGE_APP) and dispatches via the active TCP / BLE.
     * Returns true on successful wire-layer dispatch.
     */
    suspend fun sendMeshChat(text: String, channelIndex: Int = 0): Boolean {
        if (text.isEmpty()) return false
        val transport = _activeTransport.value ?: return false
        val payload = text.toByteArray(Charsets.UTF_8)
        val toRadio = soy.engindearing.omnitak.mobile.data.AtakPluginSerializer.buildToRadio(
            payloadBytes = ByteArray(0), // we override portnum manually below — see note
            channelIndex = channelIndex.toUInt(),
        )
        // We can't reuse AtakPluginSerializer directly (it hardcodes portnum
        // to 72). Use the dedicated helper below instead.
        val frame = buildTextMessageToRadio(payload, channelIndex.toUInt())
        return when (transport) {
            MeshConnectionType.TCP -> tcpClient.sendBytes(frame)
            MeshConnectionType.BLUETOOTH -> bleClient?.sendToRadio(frame) ?: false
        }
    }

    /** Build a ToRadio { MeshPacket { Data { portnum=1, payload } } } frame. */
    private fun buildTextMessageToRadio(text: ByteArray, channelIndex: UInt): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        // Data { 1=portnum varint=1, 2=payload bytes=text }
        val data = java.io.ByteArrayOutputStream().apply {
            // tag (1<<3)|0 = 0x08, varint 1
            write(0x08); write(0x01)
            // tag (2<<3)|2 = 0x12, length-delim
            write(0x12)
            writeVarintTo(this, text.size.toULong())
            write(text)
        }.toByteArray()
        // MeshPacket { 2=to fixed32, 3=channel varint (if non-zero), 4=decoded data, 6=id fixed32, 10=want_ack varint=0 }
        val pkt = java.io.ByteArrayOutputStream().apply {
            // to: fixed32 broadcast
            write(0x15)
            write(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
            // channel
            if (channelIndex != 0u) {
                write(0x18); writeVarintTo(this, channelIndex.toULong())
            }
            // decoded data submessage
            write(0x22)
            writeVarintTo(this, data.size.toULong())
            write(data)
            // id: random non-zero fixed32
            val id = (1..Int.MAX_VALUE).random()
            write(0x35)
            write(byteArrayOf(
                (id and 0xFF).toByte(),
                ((id shr 8) and 0xFF).toByte(),
                ((id shr 16) and 0xFF).toByte(),
                ((id shr 24) and 0xFF).toByte(),
            ))
        }.toByteArray()
        // ToRadio { 1=packet length-delim }
        out.write(0x0A)
        writeVarintTo(out, pkt.size.toULong())
        out.write(pkt)
        return out.toByteArray()
    }

    private fun writeVarintTo(out: java.io.OutputStream, value: ULong) {
        var v = value
        while (v >= 0x80uL) {
            out.write(((v and 0x7FuL).toInt()) or 0x80)
            v = v shr 7
        }
        out.write(v.toInt() and 0x7F)
    }

    /** Conversation id used by [ChatStore] to bucket incoming mesh text by channel. */
    fun meshConversationId(channelIndex: Int): String = "MESH-CH$channelIndex"

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
    suspend fun sendCoTOverMesh(event: CoTEvent, channelIndex: UInt = 0u): Boolean {
        val payload = AtakPluginSerializer.serialize(event)
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

    companion object {
        private const val TAG = "MeshtasticManager"
        private const val PORTNUM_TEXT_MESSAGE_APP = 1
        private const val PORTNUM_POSITION_APP = 3
        private const val PORTNUM_ADMIN_APP = 6
        // ConfigType enum values (admin.proto)
        private const val GET_CONFIG_DEVICE = 0
        private const val GET_CONFIG_POSITION = 1
        private const val GET_CONFIG_LORA = 5

        private val chatTimeFormatter = SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            Locale.US,
        ).apply { timeZone = TimeZone.getTimeZone("UTC") }
        private const val PORTNUM_ATAK_PLUGIN = 72
        // Some ATAK plugin builds send via portnum 257 (ATAK_FORWARDER)
        // — accept both so OmniTAK can interop with both clients.
        private const val PORTNUM_ATAK_FORWARDER = 257
    }
}
