package soy.engindearing.omnitak.mobile.domain

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.MeshNode

/**
 * Bridges a [MeshFrameworkManager]'s node table into the active TAK
 * output sink as CoT events. Mirrors iOS `MeshtasticCOTBridge.swift` —
 * observe the node table, convert each updated node to a [CoTEvent], and
 * ship it off to whatever publishes events to the active server (the
 * [ContactStore.ingest] sink in the Android case).
 *
 * The node→CoT conversion is injected ([nodeToCoT]) so the same bridge
 * serves any mesh framework: Meshtastic nodes go through
 * [soy.engindearing.omnitak.mobile.data.MeshtasticCoTConverter];
 * MeshCore contacts through
 * [soy.engindearing.omnitak.mobile.data.MeshCoreCoTConverter].
 *
 * Re-emits a node only when its content changes (lat/lon/snr/battery
 * shifted) so we don't spam the server with identical updates every
 * StateFlow re-collection.
 */
class MeshCoTBridge(
    private val mesh: MeshFrameworkManager,
    private val cotSink: (CoTEvent) -> Unit,
    private val nodeToCoT: (MeshNode) -> CoTEvent?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    @Volatile var enabled: Boolean = true

    private var collectJob: Job? = null
    private val lastSent = HashMap<Long, NodeFingerprint>()

    fun start() {
        if (collectJob?.isActive == true) return
        collectJob = scope.launch {
            mesh.nodes.collect { table ->
                if (!enabled) return@collect
                for ((id, node) in table) {
                    val fp = NodeFingerprint.of(node)
                    if (lastSent[id] == fp) continue
                    val event = nodeToCoT(node) ?: continue
                    runCatching { cotSink(event) }
                        .onFailure { Log.w(TAG, "cotSink failed for node ${node.idHex}: ${it.message}") }
                    lastSent[id] = fp
                }
            }
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
        lastSent.clear()
    }

    private data class NodeFingerprint(
        val lat: Double?, val lon: Double?, val alt: Int?,
        val snr: Double?, val battery: Int?, val hops: Int?,
        val lastHeard: Long, val short: String, val long: String,
    ) {
        companion object {
            fun of(node: MeshNode): NodeFingerprint = NodeFingerprint(
                lat = node.position?.lat,
                lon = node.position?.lon,
                alt = node.position?.altitudeM,
                snr = node.snr,
                battery = node.batteryLevel,
                hops = node.hopDistance,
                lastHeard = node.lastHeardEpoch,
                short = node.shortName,
                long = node.longName,
            )
        }
    }

    companion object {
        private const val TAG = "MeshCoTBridge"
    }
}
