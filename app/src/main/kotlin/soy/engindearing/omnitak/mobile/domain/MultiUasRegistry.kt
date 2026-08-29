package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import soy.engindearing.omnitak.mobile.data.SelfFix
import soy.engindearing.omnitak.mobile.data.uas.MavlinkConnection

/**
 * Registry of connected UAS managers — enables multi-drone operations.
 * One manager per connected drone, each with its own MAVLink link,
 * mission, cruise altitude, geofence, RTSP URL, etc.
 *
 * Exactly one manager is "active" at any time — that's the drone the
 * HUD, control bar, mission banner, video PIP, pre-flight panel, etc.
 * are bound to. Switching active is the operator's "now I'm commanding
 * drone B" gesture. Inactive drones still:
 *  - hold their own state (telemetry, mission, follow-me, etc.)
 *  - render their marker on the map (via [AllDronesOverlay])
 *  - federate their CoT marker to the TAK server independently
 *
 * Stable identity guarantee: the registry always has at least one
 * manager. On startup it's empty-but-present (idle). [connect] either
 * activates the empty manager or adds a new one. This lets long-lived
 * Compose state (`app.uasManager.state.collectAsState()`) keep working
 * via the [active] StateFlow even before any drone is connected.
 */
class MultiUasRegistry(
    private val sendCoT: suspend (String) -> Boolean,
    private val operatorFix: () -> SelfFix?,
) {
    private val _drones = MutableStateFlow<List<Entry>>(emptyList())
    val drones: StateFlow<List<Entry>> = _drones.asStateFlow()

    /** Sentinel for the always-present idle manager — exists so code
     *  that does `app.uasManager.state` works even before any
     *  connection. Replaced once the first real drone connects. */
    private val idleManager = UASManager(sendCoT, operatorFix = operatorFix)
    private val _active = MutableStateFlow(idleManager)
    val active: StateFlow<UASManager> = _active.asStateFlow()

    data class Entry(
        val droneId: String,
        val callsign: String,
        val host: String,
        val port: Int,
        val transport: MavlinkConnection.Transport,
        val manager: UASManager,
    )

    /** Connect a new drone. If [droneId] already exists, the existing
     *  manager is re-connected (cleanly disconnect + reconnect). New
     *  drones automatically become active. */
    fun connect(
        droneId: String,
        host: String,
        port: Int = MavlinkConnection.DEFAULT_DRONE_PORT,
        callsign: String = "OmniTAK-UAS",
        transport: MavlinkConnection.Transport = MavlinkConnection.Transport.UDP,
    ) {
        val existing = _drones.value.firstOrNull { it.droneId == droneId }
        val mgr = existing?.manager
            ?: if (_drones.value.isEmpty() && _active.value === idleManager) {
                // First-ever connect: reuse the sentinel idle manager so
                // any Compose state already collecting `active.state`
                // keeps the same object identity.
                idleManager
            } else {
                UASManager(sendCoT, operatorFix = operatorFix)
            }
        mgr.connect(host, port, callsign, transport = transport)
        val updated = Entry(droneId, callsign, host, port, transport, mgr)
        _drones.value = (_drones.value.filterNot { it.droneId == droneId } + updated)
        _active.value = mgr
    }

    /** Disconnect + remove a drone. If it was active, the active slot
     *  flips to another connected drone, or back to the idle sentinel
     *  if none remain. */
    fun disconnect(droneId: String) {
        val entry = _drones.value.firstOrNull { it.droneId == droneId } ?: return
        entry.manager.disconnect()
        _drones.value = _drones.value.filterNot { it.droneId == droneId }
        if (_active.value === entry.manager) {
            _active.value = _drones.value.firstOrNull()?.manager ?: idleManager
        }
    }

    fun setActive(droneId: String) {
        _drones.value.firstOrNull { it.droneId == droneId }?.let { _active.value = it.manager }
    }

    /** Convenience for the existing single-drone "Disconnect" button —
     *  finds whichever entry the active manager belongs to and removes
     *  it. No-op if active is the idle sentinel. */
    fun disconnectActive() {
        val activeMgr = _active.value
        if (activeMgr === idleManager) return
        val entry = _drones.value.firstOrNull { it.manager === activeMgr } ?: return
        disconnect(entry.droneId)
    }
}
