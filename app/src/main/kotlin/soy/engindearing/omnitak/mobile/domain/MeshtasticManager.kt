package soy.engindearing.omnitak.mobile.domain

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import soy.engindearing.omnitak.mobile.data.AtakPluginParser
import soy.engindearing.omnitak.mobile.data.AtakPluginSerializer
import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.FromRadioFrame
import soy.engindearing.omnitak.mobile.data.MeshNode
import soy.engindearing.omnitak.mobile.data.MeshtasticProtoParser
import soy.engindearing.omnitak.mobile.data.MeshtasticTcpClient

/**
 * Application-scoped Meshtastic state holder. Owns the TCP client and
 * exposes node-table + connection state to screens via StateFlow.
 *
 * Phase 1 wired the protobuf decoder — every framed payload from
 * [MeshtasticTcpClient.frames] is dispatched through
 * [MeshtasticProtoParser.parseFromRadio], and recognised NodeInfo
 * frames flow into [_nodes]. POSITION_APP packets fold into the
 * existing entry without dropping unrelated metadata.
 *
 * Phase 4 wires the ATAK-plugin parser: portnum-72 packets are decoded
 * into [CoTEvent] via [AtakPluginParser] and pushed into [cotSink], the
 * same sink [MeshtasticCoTBridge] already feeds. [sendCoTOverMesh]
 * provides the matching TX path over the active TCP transport — BLE
 * TX hooks in once Phase 2 lands.
 */
class MeshtasticManager {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _nodes = MutableStateFlow<Map<Long, MeshNode>>(emptyMap())
    val nodes: StateFlow<Map<Long, MeshNode>> = _nodes.asStateFlow()

    val tcpClient = MeshtasticTcpClient()

    private var frameCollector: Job? = null
    private var bytesRx: Long = 0L
    @Volatile private var _myNodeNum: UInt? = null
    val myNodeNum: UInt? get() = _myNodeNum

    val state get() = tcpClient.state
    val bytesReceived get() = tcpClient.bytesReceived

    /** Sink for CoT events parsed off the mesh — wired up by
     *  [OmniTAKApp] to [ContactStore.ingest] so portnum-72 ATAK-plugin
     *  payloads flow into the same map pipeline as TCP-server CoT. */
    @Volatile var cotSink: ((CoTEvent) -> Unit)? = null

    fun connectTcp(host: String, port: Int = 4403) {
        frameCollector?.cancel()
        frameCollector = scope.launch {
            tcpClient.frames.collect { frame ->
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
        }
        tcpClient.connect(host, port)
    }

    fun disconnect() {
        tcpClient.disconnect()
        frameCollector?.cancel()
        frameCollector = null
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
            else -> Log.v(TAG, "MeshPacket portnum=${packet.portnum} from=${packet.from} payload=${packet.payload.size}B")
        }
    }

    /**
     * Send a CoT event over the active Meshtastic transport as a
     * portnum-72 ATAK-plugin payload. Returns true when the framed
     * ToRadio bytes are dispatched to the radio, false when no
     * transport is connected or the write fails.
     *
     * BLE TX is Phase 2's job — once that lands the dispatch can branch
     * on the active transport. For now we only support TCP TX, which
     * matches how iOS shipped its first portnum-72 TX path.
     */
    suspend fun sendCoTOverMesh(event: CoTEvent, channelIndex: UInt = 0u): Boolean {
        val payload = AtakPluginSerializer.serialize(event)
        val toRadio = AtakPluginSerializer.buildToRadio(
            payloadBytes = payload,
            channelIndex = channelIndex,
        )
        return when (state.value) {
            is ConnectionState.Connected -> tcpClient.sendBytes(toRadio)
            else -> false
        }
    }

    companion object {
        private const val TAG = "MeshtasticManager"
        private const val PORTNUM_POSITION_APP = 3
        private const val PORTNUM_ATAK_PLUGIN = 72
        // Some ATAK plugin builds send via portnum 257 (ATAK_FORWARDER)
        // — accept both so OmniTAK can interop with both clients.
        private const val PORTNUM_ATAK_FORWARDER = 257
    }
}
