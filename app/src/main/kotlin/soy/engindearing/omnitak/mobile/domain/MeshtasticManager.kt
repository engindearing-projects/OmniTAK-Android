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
import kotlinx.coroutines.launch
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
 * Both transports feed the same [MeshtasticProtoParser] pipeline — a
 * BLE drain read is byte-for-byte equivalent to a TCP framed payload,
 * so the consumer doesn't care which transport the frame arrived on.
 *
 * The active transport at any time is tracked via [activeTransport];
 * UI uses that to flip between the TCP and BLE link-status panels.
 *
 * Since BLE needs a [Context] to construct, the manager is created
 * lazily with one — the App owns this and just hands it through.
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

    fun connectTcp(host: String, port: Int = 4403) {
        // Tearing down a BLE session before opening the TCP one is
        // fine — we only ever drive one transport at a time.
        if (_activeTransport.value == MeshConnectionType.BLUETOOTH) disconnect()
        frameCollector?.cancel()
        _activeTransport.value = MeshConnectionType.TCP
        frameCollector = scope.launch {
            tcpClient.frames.collect { frame -> dispatchFrame(frame) }
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
        return client.connectToAddress(deviceAddress)
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
            PORTNUM_ATAK_PLUGIN -> {
                Log.i(
                    TAG,
                    "ATAK plugin payload received from ${packet.from}, ${packet.payload.size}B (parsing in Phase 4)",
                )
            }
            else -> Log.v(TAG, "MeshPacket portnum=${packet.portnum} from=${packet.from} payload=${packet.payload.size}B")
        }
    }

    companion object {
        private const val TAG = "MeshtasticManager"
        private const val PORTNUM_POSITION_APP = 3
        private const val PORTNUM_ATAK_PLUGIN = 72
    }
}
