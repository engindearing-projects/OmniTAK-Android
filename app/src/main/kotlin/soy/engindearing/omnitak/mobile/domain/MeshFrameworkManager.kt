package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.ChatMessage
import soy.engindearing.omnitak.mobile.data.MeshNode

/**
 * Framework-agnostic mesh manager surface.
 *
 * Captures the part of [MeshtasticManager]'s public API that the UI and
 * [OmniTAKApp] actually drive, so a second framework ([MeshCoreManager])
 * can slot into the same wiring: the CoT bridge, the self-position
 * broadcaster, and the mesh screen all talk to this interface instead of
 * a concrete manager.
 *
 * `MeshtasticManager` keeps its richer Meshtastic-specific API
 * (admin-config read-back, TCP-vs-BLE tab state flows, etc.); this
 * interface is the common denominator. Extracting it leaves Meshtastic
 * behavior unchanged.
 */
interface MeshFrameworkManager {
    /** Node table keyed by node id. The CoT bridge observes this. */
    val nodes: StateFlow<Map<Long, MeshNode>>

    /** Connection state of whichever transport is currently active
     *  (or [ConnectionState.Disconnected] when none is). */
    val activeConnectionState: StateFlow<ConnectionState>

    /** Sink for CoT events decoded off the mesh (PLI/markers/chat).
     *  Wired by [OmniTAKApp] into the contact/chat stores. */
    var cotSink: ((CoTEvent) -> Unit)?

    /** Sink for plain text messages decoded off the mesh, surfaced in
     *  the Chat tab. */
    var chatSink: ((ChatMessage) -> Unit)?

    /** Open a BLE session to the radio at [deviceAddress]. */
    suspend fun connectBle(deviceAddress: String): Boolean

    /** Tear down whatever transport is active. */
    fun disconnect()

    /** Send a plain-text chat message over the active mesh transport. */
    suspend fun sendMeshChat(text: String, channelIndex: Int = 0, toNodeId: UInt? = null): Boolean

    /** Send a CoT event (self-PLI or GeoChat) over the active mesh
     *  transport. Returns true on wire-layer dispatch. */
    suspend fun sendCoTOverMesh(event: CoTEvent, channelIndex: UInt = 0u): Boolean

    /** Start a BLE scan, returning a flow of framework-neutral hits.
     *  Null when BLE is unavailable (e.g. no Context / radio off). */
    suspend fun startMeshScan(timeoutMs: Long = 10_000): Flow<MeshScanResult>?

    /** Stop an in-flight BLE scan. */
    fun stopMeshScan()
}

/** Framework-neutral BLE discovery result for the mesh framework picker. */
data class MeshScanResult(
    val name: String?,
    val address: String,
    val rssi: Int,
)
