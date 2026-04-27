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
import soy.engindearing.omnitak.mobile.data.FromRadioFrame
import soy.engindearing.omnitak.mobile.data.MeshNode
import soy.engindearing.omnitak.mobile.data.MeshtasticProtoParser
import soy.engindearing.omnitak.mobile.data.MeshtasticTcpClient

/**
 * Application-scoped Meshtastic state holder. Owns the TCP client and
 * exposes node-table + connection state to screens via StateFlow.
 *
 * As of Phase 1 the protobuf decoder is wired in — every framed
 * payload from [MeshtasticTcpClient.frames] is dispatched through
 * [MeshtasticProtoParser.parseFromRadio], and recognised NodeInfo
 * frames flow into [_nodes]. POSITION_APP packets fold into the
 * existing entry without dropping unrelated metadata. ATAK plugin
 * frames (portnum 72) are logged for now — Phase 4 parses them.
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
