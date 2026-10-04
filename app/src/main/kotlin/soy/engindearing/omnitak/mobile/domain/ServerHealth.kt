package soy.engindearing.omnitak.mobile.domain

import soy.engindearing.omnitak.mobile.data.TAKServer

/**
 * #209: the single health projection of the TAK server connections.
 *
 * Before this the map's top bar and the Servers screen each folded
 * [ServerManager]'s per-server [ConnectionState] into colours on their own,
 * with different rules: the multi-server "N/M" text on the bar went green as
 * soon as ANY server was up, the Servers row painted a red Stop button on a
 * healthy (Connected) row, and a not-connected row could show a dim green dot.
 * Operators read that as "green bar, red server".
 *
 * Both surfaces now call the functions below, so a given set of server states
 * can only ever produce one answer. Pure Kotlin, no Android types, no I/O,
 * fully JVM-unit-testable (see `ServerHealthTest`).
 *
 * Scope: this projects the state [ServerManager] already tracks. It does NOT
 * detect a half-open socket (the OS still says "connected" after the network
 * is gone); [TAKConnection] has no read timeout or keepalive, so such a server
 * stays [ServerHealth.CONNECTED] here until the socket actually errors.
 */
enum class ServerHealth {
    /** Enabled and the socket is up. */
    CONNECTED,

    /** Enabled and a dial is in flight. */
    CONNECTING,

    /** Enabled and the last attempt failed. The reconnect supervisor keeps retrying. */
    FAILED,

    /**
     * Enabled but there is no live socket and no dial in flight: the operator
     * pressed Stop on the row, or the socket dropped and the re-dial has not
     * started yet. Not an error, but not up either.
     */
    DISCONNECTED,

    /** Switched off by the operator. Never dialled; ignored by the aggregate. */
    DISABLED;

    /** A socket is up or being dialled: the Servers row offers Stop, not Play. */
    val isLive: Boolean get() = this == CONNECTED || this == CONNECTING
}

/** The map bar's overall light. */
enum class AggregateHealth {
    /** Every enabled server is connected. */
    GREEN,

    /** At least one enabled server is connected and at least one is not. */
    AMBER,

    /** No enabled server is connected (this includes "no enabled servers"). */
    RED,
}

/**
 * What the map bar needs, computed once from the same inputs the Servers rows
 * use. [enabledServers] holds the health of each ENABLED server in list order
 * (disabled servers do not count towards the bar).
 */
data class ServerHealthSummary(
    val enabledServers: List<ServerHealth>,
    val aggregate: AggregateHealth,
) {
    val enabledCount: Int get() = enabledServers.size
    val connectedCount: Int get() = enabledServers.count { it == ServerHealth.CONNECTED }
}

object ServerHealthProjection {

    /**
     * Health of one server. [enabled] wins over everything: a disabled server
     * is [ServerHealth.DISABLED] even if a stale state entry is still around.
     * [state] is that server's entry in [ServerManager.serverStates]; null
     * (no entry) means there is no live connection object for it.
     */
    fun project(enabled: Boolean, state: ConnectionState?): ServerHealth = when {
        !enabled -> ServerHealth.DISABLED
        state is ConnectionState.Connected -> ServerHealth.CONNECTED
        state is ConnectionState.Connecting -> ServerHealth.CONNECTING
        state is ConnectionState.Failed -> ServerHealth.FAILED
        // ConnectionState.Disconnected, or no entry at all.
        else -> ServerHealth.DISCONNECTED
    }

    /**
     * Overall light for a set of per-server healths. [ServerHealth.DISABLED]
     * entries are ignored, so callers may pass every server.
     *
     *  - every enabled server connected          -> GREEN
     *  - none connected (or none enabled)        -> RED
     *  - some connected, some Connecting / Failed / Disconnected -> AMBER
     *
     * "None connected" is checked before "some not connected" so a lone
     * Failed or Connecting server reads RED, not AMBER; amber is reserved for
     * the mixed case that used to read green on the bar.
     */
    fun aggregate(healths: Collection<ServerHealth>): AggregateHealth {
        val enabled = healths.filter { it != ServerHealth.DISABLED }
        val connected = enabled.count { it == ServerHealth.CONNECTED }
        return when {
            connected == 0 -> AggregateHealth.RED
            connected == enabled.size -> AggregateHealth.GREEN
            else -> AggregateHealth.AMBER
        }
    }

    /**
     * Fold the server list and [ServerManager.serverStates] into everything
     * the bar shows. State entries for ids that are not in [servers] are
     * ignored.
     */
    fun summarize(
        servers: List<TAKServer>,
        states: Map<String, ConnectionState>,
    ): ServerHealthSummary {
        val enabledServers = servers
            .filter { it.enabled }
            .map { project(enabled = true, state = states[it.id]) }
        return ServerHealthSummary(
            enabledServers = enabledServers,
            aggregate = aggregate(enabledServers),
        )
    }
}
