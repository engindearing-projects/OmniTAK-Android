package soy.engindearing.omnitak.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.profileDataStore by preferencesDataStore(name = "config_profiles")

/**
 * DataStore-backed store for [ConfigProfile]s.
 *
 * Maintains a list of named profiles plus an "active profile" ID pointer.
 * The active profile is informational only — the live config always lives in
 * [UserPrefsStore] and [TAKServerStore]; switching profiles calls [apply]
 * which writes to those stores directly.
 *
 * Schema: two DataStore keys
 *  - `profiles_json`    — JSON array of [ConfigProfile]
 *  - `active_profile_id` — String UUID of the active profile (may be empty/absent)
 */
class ConfigProfileStore(
    private val context: Context,
    private val userPrefsStore: UserPrefsStore,
    private val serverStore: TAKServerStore,
) {
    private val KEY_PROFILES = stringPreferencesKey("profiles_json")
    private val KEY_ACTIVE_ID = stringPreferencesKey("active_profile_id")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Observable list of all saved profiles. */
    val profiles: Flow<List<ConfigProfile>> = context.profileDataStore.data.map { prefs ->
        val raw = prefs[KEY_PROFILES] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<ConfigProfile>>(raw) }.getOrElse { emptyList() }
    }

    /** Observable active-profile ID (null if none selected). */
    val activeProfileId: Flow<String?> = context.profileDataStore.data.map { prefs ->
        prefs[KEY_ACTIVE_ID]?.takeIf { it.isNotBlank() }
    }

    /**
     * Snapshot the current live config into a new [ConfigProfile] and persist it.
     *
     * @param name Human-readable profile name (e.g. "Team Alpha").
     * @param servers The live server list — fetched by the caller so this function
     *   stays non-suspending with respect to TAKServerStore's cold-flow semantics.
     *   Pass `serverStore.servers.first()` at the call site.
     * @return The newly-created profile.
     */
    suspend fun snapshotCurrent(name: String, servers: List<TAKServer>): ConfigProfile {
        val prefs = userPrefsStore.prefs.first()
        val enrollmentPointer = buildEnrollmentPointer(servers)

        val profile = ConfigProfile(
            id = UUID.randomUUID().toString(),
            name = name,
            team = prefs.team,
            servers = servers.map { ProfileServer.fromServer(it) },
            enrollmentPointer = enrollmentPointer,
            mapProvider = prefs.mapProvider.name,
            customTileUrl = prefs.customTileUrl,
            coordFormat = prefs.coordFormat.name,
            distanceUnit = prefs.distanceUnit.name,
            callsignCardVisible = prefs.callsignCardVisible,
            gridEnabled = prefs.gridEnabled,
            drawingsVisible = prefs.drawingsVisible,
            aircraftVisible = prefs.aircraftVisible,
            contactsVisible = prefs.contactsVisible,
            useMilStdSelfSymbol = prefs.useMilStdSelfSymbol,
            autoPublishMeshToTak = prefs.autoPublishMeshToTak,
            broadcastOverMesh = prefs.broadcastOverMesh,
            meshBroadcastIntervalSecs = prefs.meshBroadcastIntervalSecs,
            meshNodesLayerVisible = prefs.meshNodesLayerVisible,
        )

        saveProfile(profile)
        return profile
    }

    /**
     * Apply a [ConfigProfile] to the live stores.
     *
     * - Applies all non-secret prefs to [UserPrefsStore].
     * - Merges the profile's server list into [TAKServerStore] (no-op for duplicates).
     * - Sets the active profile ID to this profile's ID.
     * - Does NOT overwrite the operator's callsign (each teammate keeps their own).
     * - Does NOT touch credentials — those remain in SecureCredentialStore / CertVault.
     */
    suspend fun apply(profile: ConfigProfile) {
        // 1. Apply UserPrefs — keep the operator's callsign and self-fix.
        userPrefsStore.update { current ->
            current.copy(
                team = profile.team,
                mapProvider = runCatching { MapProvider.valueOf(profile.mapProvider) }.getOrElse { current.mapProvider },
                customTileUrl = profile.customTileUrl,
                coordFormat = runCatching { CoordFormat.valueOf(profile.coordFormat) }.getOrElse { current.coordFormat },
                distanceUnit = runCatching { DistanceUnit.valueOf(profile.distanceUnit) }.getOrElse { current.distanceUnit },
                callsignCardVisible = profile.callsignCardVisible,
                gridEnabled = profile.gridEnabled,
                drawingsVisible = profile.drawingsVisible,
                aircraftVisible = profile.aircraftVisible,
                contactsVisible = profile.contactsVisible,
                useMilStdSelfSymbol = profile.useMilStdSelfSymbol,
                autoPublishMeshToTak = profile.autoPublishMeshToTak,
                broadcastOverMesh = profile.broadcastOverMesh,
                meshBroadcastIntervalSecs = profile.meshBroadcastIntervalSecs.coerceIn(30, 60),
                meshNodesLayerVisible = profile.meshNodesLayerVisible,
                // Callsign intentionally preserved — teammates keep their own.
                // Self-fix, selfUid, selfLat/Lon/Hae, camera prefs also preserved.
            )
        }

        // 2. Merge servers — skip any whose endpoint already exists.
        val existingServers = serverStore.servers.first()
        val newServers = profile.servers
            .map { ProfileServer.toServer(it) }
            .filter { incoming -> existingServers.none { it.matchesEndpoint(incoming) } }
        if (newServers.isNotEmpty()) {
            serverStore.saveServers(existingServers + newServers)
        }

        // 3. Mark this profile as active.
        setActiveProfileId(profile.id)
    }

    // ── CRUD ──────────────────────────────────────────────────────────────

    suspend fun saveProfile(profile: ConfigProfile) {
        context.profileDataStore.edit { prefs ->
            val current = prefs[KEY_PROFILES]
                ?.let { runCatching { json.decodeFromString<List<ConfigProfile>>(it) }.getOrElse { emptyList() } }
                ?: emptyList()
            val updated = current.filter { it.id != profile.id } + profile
            prefs[KEY_PROFILES] = json.encodeToString(updated)
        }
    }

    suspend fun renameProfile(id: String, newName: String) {
        context.profileDataStore.edit { prefs ->
            val current = prefs[KEY_PROFILES]
                ?.let { runCatching { json.decodeFromString<List<ConfigProfile>>(it) }.getOrElse { emptyList() } }
                ?: emptyList()
            val updated = current.map { if (it.id == id) it.copy(name = newName) else it }
            prefs[KEY_PROFILES] = json.encodeToString(updated)
        }
    }

    suspend fun deleteProfile(id: String) {
        context.profileDataStore.edit { prefs ->
            val current = prefs[KEY_PROFILES]
                ?.let { runCatching { json.decodeFromString<List<ConfigProfile>>(it) }.getOrElse { emptyList() } }
                ?: emptyList()
            prefs[KEY_PROFILES] = json.encodeToString(current.filter { it.id != id })
            // If we just deleted the active profile, clear the pointer.
            if (prefs[KEY_ACTIVE_ID] == id) prefs.remove(KEY_ACTIVE_ID)
        }
    }

    suspend fun setActiveProfileId(id: String?) {
        context.profileDataStore.edit { prefs ->
            if (id == null) prefs.remove(KEY_ACTIVE_ID) else prefs[KEY_ACTIVE_ID] = id
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Build an [EnrollmentPointer] from the server list if any server needs
     * CSR enrollment (TLS + username). Picks the first such server.
     */
    private fun buildEnrollmentPointer(servers: List<TAKServer>): EnrollmentPointer? {
        val enrollable = servers.firstOrNull { it.useTLS && !it.username.isNullOrBlank() }
            ?: return null
        return EnrollmentPointer(
            host = enrollable.host,
            enrollmentPort = 8446,
            username = enrollable.username,
        )
    }
}
