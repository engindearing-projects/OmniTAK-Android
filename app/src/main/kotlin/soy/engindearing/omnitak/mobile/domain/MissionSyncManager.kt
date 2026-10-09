package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import soy.engindearing.omnitak.mobile.data.CertVault
import soy.engindearing.omnitak.mobile.data.TAKServer
import soy.engindearing.omnitak.mobile.data.TakDataPackageInfo
import soy.engindearing.omnitak.mobile.data.TakMissionInfo
import soy.engindearing.omnitak.mobile.data.TakRestApiClient

/**
 * Multi-server mission / data-package sync, driven entirely by the real TAK
 * Marti REST API ([TakRestApiClient]) — no stubs. The Android counterpart to
 * iOS's MissionSyncManager.
 *
 * Servers come from [ServerManager] (single source of truth): every ENABLED,
 * TLS, cert-bearing server participates at once. Each is probed + fetched in
 * parallel; the screen aggregates across all of them. Connection status is
 * derived from a live reachability probe on every refresh, not a sticky flag,
 * so it always reflects reality.
 */
class MissionSyncManager(
    private val serverManager: ServerManager,
    private val certVault: CertVault?,
) {

    private val _sessions = MutableStateFlow<List<ServerSyncSession>>(emptyList())
    val sessions: StateFlow<List<ServerSyncSession>> = _sessions.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _lastRefresh = MutableStateFlow<Long?>(null)
    val lastRefresh: StateFlow<Long?> = _lastRefresh.asStateFlow()

    /**
     * Enabled TLS servers that can authenticate to the Marti API: with a
     * client certificate, or with a stored username and password, which
     * reach the API through the enrollment port (iOS #169 parity).
     */
    private fun enabledServers(): List<TAKServer> =
        serverManager.servers.value.filter {
            it.enabled && it.useTLS && (it.certificateName != null || it.hasStoredCredentials)
        }

    /**
     * Refresh every enabled server in parallel. Existing data is preserved and
     * rows flip to [MissionServerStatus.Checking] while in flight, so the UI
     * never blanks mid-refresh.
     */
    suspend fun refreshAll() {
        val servers = enabledServers()
        if (servers.isEmpty()) {
            _sessions.value = emptyList()
            return
        }
        _isRefreshing.value = true
        val prior = _sessions.value.associateBy { it.serverId }
        _sessions.value = servers.map { sv ->
            prior[sv.id]?.copy(status = MissionServerStatus.Checking)
                ?: ServerSyncSession(sv.id, sv.name, sv.host, MissionServerStatus.Checking)
        }

        val results = coroutineScope {
            servers.map { sv -> async(Dispatchers.IO) { sync(sv) } }.awaitAll()
        }
        _sessions.value = results.sortedBy { it.serverName.lowercase() }
        _isRefreshing.value = false
        _lastRefresh.value = System.currentTimeMillis()
    }

    /**
     * Upload a TAK Mission Package (.zip) to the first online,
     * cert-enrolled, TLS server. Returns [UploadOutcome.Hash] with the
     * Marti-assigned SHA on success, [UploadOutcome.NoServer] when no
     * enrolled+enabled+TLS server is configured, or
     * [UploadOutcome.Failed] with the server's reason on HTTP errors.
     *
     * Slice 3 of #30 — paired with [LassoExporters.writeMissionPackage]
     * so the existing "Add to Data Package" button can also hand off to
     * the TAK server instead of (or in addition to) the local share
     * sheet. Returns just the hash; attaching it to a specific named
     * mission is the slice-2 authoring flow.
     */
    suspend fun uploadDataPackage(
        zipBytes: ByteArray,
        filename: String,
        creatorUid: String,
    ): UploadOutcome {
        val candidates = enabledServers()
        if (candidates.isEmpty()) return UploadOutcome.NoServer
        return withContext(Dispatchers.IO) {
            for (server in candidates) {
                val client = TakRestApiClient(server, certVault)
                val attempt = runCatching {
                    client.connect()
                    client.uploadDataPackage(zipBytes, filename, creatorUid)
                }
                val hash = attempt.getOrNull()
                if (hash != null) {
                    return@withContext UploadOutcome.Hash(
                        hash = hash,
                        serverId = server.id,
                        serverName = server.name,
                    )
                }
            }
            // Last attempt's failure is the most informative one; the
            // earlier ones were probably the same network condition.
            val lastFailure = candidates.last().let { sv ->
                runCatching {
                    TakRestApiClient(sv, certVault).also { it.connect() }.uploadDataPackage(zipBytes, filename, creatorUid)
                }.exceptionOrNull()
            }
            UploadOutcome.Failed(
                reason = lastFailure?.message ?: "All ${candidates.size} server(s) refused the upload"
            )
        }
    }

    /**
     * Create a mission on a specific enabled server, then refresh that
     * server so the new mission appears in the list. Slice 2 of #30 —
     * the UI consumer the staged [TakRestApiClient.createMission] API
     * was waiting for (Android counterpart to iOS MissionCreationSheet).
     */
    suspend fun createMission(
        serverId: String,
        name: String,
        description: String?,
        creatorUid: String,
    ): Result<TakMissionInfo> {
        val sv = enabledServers().firstOrNull { it.id == serverId }
            ?: return Result.failure(IllegalStateException("Server is not available for mission sync"))
        val result = withContext(Dispatchers.IO) {
            runCatching {
                TakRestApiClient(sv, certVault).also { it.connect() }.createMission(
                    name = name,
                    creatorUid = creatorUid,
                    description = description?.takeIf { it.isNotBlank() },
                )
            }
        }
        if (result.isSuccess) refresh(serverId)
        return result
    }

    /**
     * Attach an already-uploaded data-package hash to a mission on the
     * same server (`PUT /Marti/api/missions/{name}/contents`), then
     * refresh so the mission's content count updates.
     */
    suspend fun attachPackageToMission(
        serverId: String,
        missionName: String,
        hash: String,
    ): Result<Unit> {
        val sv = enabledServers().firstOrNull { it.id == serverId }
            ?: return Result.failure(IllegalStateException("Server is not available for mission sync"))
        val result = withContext(Dispatchers.IO) {
            runCatching {
                TakRestApiClient(sv, certVault).also { it.connect() }.attachHashToMission(missionName, hash)
            }
        }
        if (result.isSuccess) refresh(serverId)
        return result
    }

    /** Refresh a single server (tap-to-retry on its row). */
    suspend fun refresh(serverId: String) {
        val sv = enabledServers().firstOrNull { it.id == serverId } ?: return
        _sessions.value = _sessions.value.map {
            if (it.serverId == serverId) it.copy(status = MissionServerStatus.Checking) else it
        }
        val updated = withContext(Dispatchers.IO) { sync(sv) }
        _sessions.value = if (_sessions.value.any { it.serverId == serverId }) {
            _sessions.value.map { if (it.serverId == serverId) updated else it }
        } else {
            (_sessions.value + updated).sortedBy { it.serverName.lowercase() }
        }
    }

    /**
     * Probe + fetch one server (blocking; call on Dispatchers.IO). Missions and
     * data packages are fetched independently and tolerated per-endpoint — taky
     * has no `/Marti/api/missions`, so missions come back empty while its data
     * packages still load.
     */
    private fun sync(server: TAKServer): ServerSyncSession {
        val client = TakRestApiClient(server, certVault)
        val now = System.currentTimeMillis()
        val base = ServerSyncSession(server.id, server.name, server.host, MissionServerStatus.Checking, lastChecked = now)
        return try {
            // Certificate port first; username/password on the enrollment
            // port when that is refused and credentials are stored (#169).
            val route = client.connect()
            val missions = runCatching { client.getMissions() }.getOrDefault(emptyList())
            val packages = runCatching { client.getDataPackages() }.getOrDefault(emptyList())
            base.copy(
                status = MissionServerStatus.Online,
                missions = missions,
                dataPackages = packages,
                lastChecked = System.currentTimeMillis(),
                authLabel = route.label.takeIf { route.usesCredentials },
            )
        } catch (t: Throwable) {
            base.copy(
                status = MissionServerStatus.Offline(
                    t.message ?: t.javaClass.simpleName,
                    (t as? TakRestApiClient.ApiException)?.detail,
                ),
            )
        }
    }
}

// MARK: - Per-server status + session + aggregated rows

sealed interface MissionServerStatus {
    data object Checking : MissionServerStatus
    data object Online : MissionServerStatus
    /** [reason] is the row text; [detail] the full diagnosis for the details dialog. */
    data class Offline(val reason: String, val detail: String? = null) : MissionServerStatus

    val isOnline: Boolean get() = this is Online
}

data class ServerSyncSession(
    val serverId: String,
    val serverName: String,
    val host: String,
    val status: MissionServerStatus,
    val missions: List<TakMissionInfo> = emptyList(),
    val dataPackages: List<TakDataPackageInfo> = emptyList(),
    val lastChecked: Long? = null,
    /** How the Marti API was reached when not by client certificate, e.g. "username and password (8446)". */
    val authLabel: String? = null,
) {
    val itemCount: Int get() = missions.size + dataPackages.size
}

data class AggregatedMission(
    val serverId: String,
    val serverName: String,
    val mission: TakMissionInfo,
) {
    val id: String get() = "$serverId:${mission.name}"
}

data class AggregatedPackage(
    val serverId: String,
    val serverName: String,
    val pkg: TakDataPackageInfo,
) {
    val id: String get() = "$serverId:${pkg.hash}"
}

/**
 * Result of [MissionSyncManager.uploadDataPackage]. Wire-shape design
 * goal: caller can pattern-match for a snappy toast — no exception
 * juggling at the UI layer.
 */
sealed interface UploadOutcome {
    data class Hash(val hash: String, val serverId: String, val serverName: String) : UploadOutcome
    data object NoServer : UploadOutcome
    data class Failed(val reason: String) : UploadOutcome
}
