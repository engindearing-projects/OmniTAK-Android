package soy.engindearing.omnitak.mobile.data

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import soy.engindearing.omnitak.mobile.domain.ConnectionState

/**
 * Framework-neutral byte transport for a mesh radio link.
 *
 * Captures the small surface a [soy.engindearing.omnitak.mobile.domain.MeshFrameworkManager]
 * needs from its underlying link, regardless of whether that link is a
 * Meshtastic TCP/BLE socket or a MeshCore Nordic-UART companion radio:
 *  - [state]  — the live connection state.
 *  - [frames] — inbound payloads. For Meshtastic this is one FromRadio
 *               protobuf payload per emission; for MeshCore companion
 *               BLE it is one companion notification frame per emission.
 *  - [send]   — write one outbound payload (framing is the impl's job).
 *  - [disconnect] — tear the link down.
 *
 * The Meshtastic clients keep their richer transport-specific APIs
 * ([MeshtasticBleClient.sendToRadio], [MeshtasticTcpClient.sendBytes],
 * scan helpers, etc.); this interface is the common denominator that
 * lets the manager layer stay transport-agnostic. Extracting it does
 * not change Meshtastic wire behavior — the concrete methods still do
 * exactly what they did before.
 */
interface MeshTransport {
    val state: StateFlow<ConnectionState>
    val frames: SharedFlow<ByteArray>

    /** Write one outbound payload. Returns true on a successful
     *  wire-layer dispatch. */
    suspend fun send(payload: ByteArray): Boolean

    /** Tear down the link. May be called when already disconnected. */
    fun disconnect()
}
