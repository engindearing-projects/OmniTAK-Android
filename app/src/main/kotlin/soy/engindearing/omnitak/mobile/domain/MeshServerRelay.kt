package soy.engindearing.omnitak.mobile.domain

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.CoTSource
import soy.engindearing.omnitak.mobile.data.MeshFramework
import soy.engindearing.omnitak.mobile.data.UserPrefs

/**
 * #179 — CoT relay between the LoRa mesh and the TAK server (ATAK
 * Meshtastic-gateway parity).
 *
 * When the device is connected to BOTH a TAK server AND a mesh, and the
 * operator has turned a relay direction on, this coordinator bridges CoT that
 * way: mesh-only nodes get pushed up to the server (so the wider TAK network
 * sees them), and server contacts get pushed down to the mesh (so off-grid
 * radios see the server picture). Each direction has its own switch (#212).
 *
 * This is powerful but dangerous if naive — a busy TAK server can flood the
 * LoRa channel and blow the radio's duty cycle. So the relay is built defensively:
 *
 *  - **Off by default, one switch per direction.** mesh→server and
 *    server→mesh are separate operator switches ([RelayInputs.toServerEnabled],
 *    [RelayInputs.toMeshEnabled], #212). A direction only relays when its own
 *    switch is on AND both transports are connected. server→mesh is forced
 *    off while MeshCore is the active mesh ([RelayDirections.effective]).
 *  - **Loop-proof.** The decision is driven entirely by the #180 [CoTSource]
 *    tag: a CoT that arrived from the mesh only ever goes UP to the server, a
 *    CoT that arrived from the server only ever goes DOWN to the mesh, and
 *    nothing is ever relayed back onto the transport it came from. A short
 *    per-uid+target recency window ([dedupWindowMs]) swallows the echo that
 *    comes back when the far side re-broadcasts the same uid (ping-pong).
 *  - **Throttled hard server→mesh** ([serverToMeshThrottleMs], default matching
 *    the existing per-uid mesh marker throttle), and only the meaningful CoT
 *    types (PLI/position, GeoChat, tactical markers) are sent down — chatter
 *    like t-x tasking / sensor / ack events stay off the air. mesh→server is
 *    looser (IP is cheap) and only deduped.
 *
 * The forwarding decision is the PURE function [relayTarget]; the per-uid
 * dedup/throttle gate is the PURE function [admitForward]. Both are
 * exhaustively unit-tested in `MeshServerRelayTest`. The instance just holds
 * the dedup state and wires the two pure functions to the injected send paths.
 */
class MeshServerRelay(
    /** Sends a CoT XML payload to every connected TAK server. Wired to
     *  `serverManager.sendCoT`. Returns true on accept. */
    private val sendToServer: suspend (xml: String) -> Boolean,
    /** Sends a CoT event over the active mesh transport. Wired to
     *  `activeMeshManager.sendCoTOverMesh`. Returns true on dispatch. */
    private val sendToMesh: suspend (event: CoTEvent) -> Boolean,
    /** Builds the CoT XML for a mesh-origin event being relayed up to the
     *  server. Wired to `CotBuilders.rebuildEvent` (preferring rawXml). */
    private val eventToServerXml: (CoTEvent) -> String,
    /** Snapshots whether ≥1 TAK server is connected. */
    private val serverConnected: () -> Boolean,
    /** Snapshots whether the active mesh transport is connected. */
    private val meshConnected: () -> Boolean,
    /** Snapshots which relay directions are in force right now: the operator's
     *  two switches (both default OFF), already narrowed by the active mesh
     *  framework. Wired to `RelayDirections.from(prefs)`. */
    private val relayDirections: () -> RelayDirections,
    /** Injectable clock for deterministic dedup/throttle tests. */
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** Last wall-clock ms a (uid → target) pair was forwarded, for the
     *  dedup/throttle gate. ConcurrentHashMap: inbound streams fire from the
     *  server read-loop coroutines and the mesh frame coroutine concurrently. */
    private val lastForwardMs = ConcurrentHashMap<String, Long>()

    /**
     * Called for every inbound CoT, tagged with the transport it [source]
     * arrived on. Computes the relay decision and, if the dedup/throttle gate
     * admits it, forwards via the matching send path. Suspends only across the
     * actual send; the decision + gate are non-blocking.
     */
    suspend fun onInbound(event: CoTEvent, source: CoTSource?) {
        val directions = relayDirections()
        val target = relayTarget(
            RelayInputs(
                event = event,
                source = source,
                serverConnected = serverConnected(),
                meshConnected = meshConnected(),
                toServerEnabled = directions.toServer,
                toMeshEnabled = directions.toMesh,
            ),
        )
        when (target) {
            RelayTarget.NONE -> return
            RelayTarget.TO_SERVER -> {
                if (!admitForward(event.uid, target, clock())) return
                runCatching { sendToServer(eventToServerXml(event)) }
                    .onSuccess { ok ->
                        Log.i(TAG, "relay mesh→server uid=${event.uid} type=${event.type} ok=$ok")
                    }
                    .onFailure { Log.w(TAG, "relay mesh→server failed uid=${event.uid}: ${it.message}") }
            }
            RelayTarget.TO_MESH -> {
                if (!admitForward(event.uid, target, clock())) return
                runCatching { sendToMesh(event) }
                    .onSuccess { ok ->
                        Log.i(TAG, "relay server→mesh uid=${event.uid} type=${event.type} ok=$ok")
                    }
                    .onFailure { Log.w(TAG, "relay server→mesh failed uid=${event.uid}: ${it.message}") }
            }
        }
    }

    /**
     * Per-uid+target dedup / throttle gate. Pure given [lastForwardMs] and the
     * supplied [now]. Returns true (and records [now]) when the forward is
     * allowed; false when the same uid was forwarded to the same target inside
     * the applicable window.
     *
     *  - server→mesh ([RelayTarget.TO_MESH]) uses the hard LoRa throttle
     *    [serverToMeshThrottleMs] — at most one relay per uid per window.
     *  - mesh→server ([RelayTarget.TO_SERVER]) uses the cheap [dedupWindowMs]
     *    that only suppresses the immediate ping-pong echo.
     *
     * Internal (not private) so the unit test can exercise the real per-uid
     * state machine across simulated time.
     */
    internal fun admitForward(uid: String, target: RelayTarget, now: Long): Boolean {
        val window = when (target) {
            RelayTarget.TO_MESH -> serverToMeshThrottleMs
            RelayTarget.TO_SERVER -> dedupWindowMs
            RelayTarget.NONE -> return false
        }
        val key = "$target:$uid"
        val last = lastForwardMs[key]
        if (last != null && now - last < window) return false
        lastForwardMs[key] = now
        return true
    }

    /** Drop dedup state — e.g. on transport teardown so a reconnect starts clean. */
    fun reset() = lastForwardMs.clear()

    /** Inputs to the pure [relayTarget] decision. Grouped so the function
     *  signature stays readable and the test can build cases declaratively.
     *  [toServerEnabled] / [toMeshEnabled] are the two direction switches
     *  (#212): mesh→server and server→mesh. */
    data class RelayInputs(
        val event: CoTEvent,
        val source: CoTSource?,
        val serverConnected: Boolean,
        val meshConnected: Boolean,
        val toServerEnabled: Boolean,
        val toMeshEnabled: Boolean,
    )

    /**
     * #212: which relay directions are in force right now. [toServer] relays
     * what the mesh hears up to the TAK server(s); [toMesh] relays server
     * contacts down onto the mesh radio.
     */
    data class RelayDirections(val toServer: Boolean, val toMesh: Boolean) {
        val anyActive: Boolean get() = toServer || toMesh

        /** The active directions in words, for the Meshtastic screen. */
        fun label(): String = when {
            toServer && toMesh -> "mesh → server and server → mesh"
            toServer -> "mesh → server"
            toMesh -> "server → mesh"
            else -> "off"
        }

        /**
         * One status line for the Meshtastic screen: what is switched on and,
         * when a direction is on but a transport is missing, what it is waiting
         * for (the relay does nothing until both ends are connected).
         */
        fun statusText(serverConnected: Boolean, meshConnected: Boolean): String = when {
            !anyActive -> "Relay is off."
            serverConnected && meshConnected -> "Relaying ${label()}."
            else -> {
                val missing = when {
                    !serverConnected && !meshConnected -> "a TAK server and a mesh radio"
                    !serverConnected -> "a TAK server"
                    else -> "a mesh radio"
                }
                "Set to relay ${label()}. Waiting for $missing to connect."
            }
        }

        companion object {
            val OFF = RelayDirections(toServer = false, toMesh = false)

            /**
             * Can the active mesh framework take server contacts down onto the
             * air? MeshCore cannot: the only place it has for a position is the
             * radio's own advert (SET_ADVERT_LATLON + SEND_SELF_ADVERT), and a
             * relayed server contact written there would move the operator's
             * advertised position onto that contact.
             * [MeshCoreManager.sendCoTOverMesh] refuses anything but the
             * operator's own position for that reason (#234); this keeps the
             * relay from offering a direction that would send nothing.
             */
            fun meshAcceptsServerContacts(framework: MeshFramework): Boolean =
                framework != MeshFramework.MESHCORE

            /** The operator's two switches, narrowed by what [framework] can carry. */
            fun effective(
                toServerEnabled: Boolean,
                toMeshEnabled: Boolean,
                framework: MeshFramework,
            ): RelayDirections = RelayDirections(
                toServer = toServerEnabled,
                toMesh = toMeshEnabled && meshAcceptsServerContacts(framework),
            )

            /** [effective] read straight off the persisted prefs. */
            fun from(prefs: UserPrefs): RelayDirections = effective(
                toServerEnabled = prefs.relayToServerEnabled,
                toMeshEnabled = prefs.relayToMeshEnabled,
                framework = prefs.selectedMeshFramework,
            )
        }
    }

    /** Where (if anywhere) an inbound CoT should be relayed. */
    enum class RelayTarget { NONE, TO_SERVER, TO_MESH }

    companion object {
        private const val TAG = "MeshServerRelay"

        /** Hard server→mesh per-uid throttle. Matches the existing per-uid
         *  marker send throttle in [MeshtasticManager] so the gateway never
         *  pushes a given uid onto LoRa faster than a local map-drop would. */
        const val serverToMeshThrottleMs: Long = 30_000L

        /** mesh→server dedup window. Just long enough to swallow the echo a
         *  re-broadcasting far side bounces back; IP relays are cheap so this
         *  stays small. */
        const val dedupWindowMs: Long = 5_000L

        /**
         * The PURE relay forwarding decision. No I/O, no state — given only the
         * event, its source transport, the two connection booleans and the two
         * direction switches, returns which way (if any) to relay.
         *
         * Rules (in order):
         *  1. Either transport down → [RelayTarget.NONE] (a gateway needs both
         *     ends; relaying with one side down is pointless and risks queueing).
         *  2. Arrived from the mesh → [RelayTarget.TO_SERVER] (never back to
         *     mesh), but only if [RelayInputs.toServerEnabled] is on.
         *  3. Arrived from the server → [RelayTarget.TO_MESH] (never back to
         *     server), but only if [RelayInputs.toMeshEnabled] is on AND only for
         *     the meaningful CoT types ([isRelayableToMesh]).
         *  4. Anything else (LOCAL / OTHER / untagged) → [RelayTarget.NONE].
         *     Local self-markers are already broadcast by the dedicated
         *     [SelfPositionBroadcaster]; untagged events have no safe origin to
         *     reason about, so we refuse to relay them (loop-safe default).
         *
         * Each direction is gated only by its own switch (#212). With both off
         * nothing relays, which is the default.
         */
        fun relayTarget(inputs: RelayInputs): RelayTarget {
            if (!inputs.serverConnected || !inputs.meshConnected) return RelayTarget.NONE
            return when (inputs.source?.transport) {
                CoTSource.Transport.MESH ->
                    if (inputs.toServerEnabled) RelayTarget.TO_SERVER
                    else RelayTarget.NONE
                CoTSource.Transport.TAK_SERVER ->
                    if (inputs.toMeshEnabled && isRelayableToMesh(inputs.event.type)) {
                        RelayTarget.TO_MESH
                    } else {
                        RelayTarget.NONE
                    }
                else -> RelayTarget.NONE
            }
        }

        /**
         * Whether a server-origin CoT [type] is worth spending LoRa airtime on.
         * To protect the channel + duty cycle we only push down the operationally
         * meaningful picture:
         *  - `a-*`     position/PLI + tactical markers (friendly/hostile/etc.)
         *  - `b-t-f`   GeoChat
         *  - `b-m-p-*` bookmark map points (waypoints / spot map)
         *
         * Everything else (`t-x-*` tasking/deletes, sensor `b-i-*`, ack/routing
         * chatter, etc.) is intentionally dropped — it's noise on a LoRa link.
         * Pure; unit-tested.
         */
        fun isRelayableToMesh(type: String): Boolean =
            type.startsWith("a-") ||
                type == "b-t-f" ||
                type.startsWith("b-m-p-")
    }
}
