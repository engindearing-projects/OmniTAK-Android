package soy.engindearing.omnitak.mobile.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean

private val Context.serverDataStore by preferencesDataStore(name = "tak_servers")

/**
 * Persistence contract used by [soy.engindearing.omnitak.mobile.domain.ServerManager].
 * Extracted so tests can supply an in-memory fake without needing an Android
 * [Context] or Preferences DataStore.
 */
interface ServerStoreApi {
    val servers: Flow<List<TAKServer>>
    val activeServerId: Flow<String?>
    suspend fun saveServers(list: List<TAKServer>)
    suspend fun saveActiveServerId(id: String?)
}

/**
 * Persists the TAK server list as a single JSON blob in a Preferences
 * DataStore. Mirrors the iOS UserDefaults+JSONEncoder scheme so both apps
 * stay read-compatible if we later share the format.
 *
 * Secrets ([TAKServer.password], [TAKServer.certificatePassword]) do NOT
 * live in that JSON anymore: [saveServers] strips them into the
 * Keystore-backed [CredentialStore] and the [servers] flow rehydrates them
 * on read, so in-memory consumers (TAKConnection, TakRestApiClient, CSR
 * enrollment) see fully-populated servers and nothing else changes.
 * Pre-0.36 plaintext blobs are migrated transparently the first time the
 * list is read: secrets are copied into the encrypted store and the JSON is
 * re-persisted with the plaintext fields blanked.
 */
class TAKServerStore(
    private val context: Context,
    private val credentials: CredentialStore = SecureCredentialStore(context),
) : ServerStoreApi {
    private val serversKey = stringPreferencesKey("servers_json")
    private val activeKey = stringPreferencesKey("active_server_id")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // One migration attempt per process is plenty — after the first
    // saveServers the persisted JSON carries no secrets to migrate.
    private val migrationAttempted = AtomicBoolean(false)

    override val servers: Flow<List<TAKServer>> = context.serverDataStore.data.map { prefs ->
        val raw = prefs[serversKey] ?: return@map emptyList()
        val decoded =
            runCatching { json.decodeFromString<List<TAKServer>>(raw) }.getOrElse { emptyList() }
        migrateLegacyPlaintext(decoded)
        rehydrateSecrets(decoded, credentials)
    }

    override val activeServerId: Flow<String?> = context.serverDataStore.data.map { it[activeKey] }

    override suspend fun saveServers(list: List<TAKServer>) {
        val raw = json.encodeToString(stripSecrets(list, credentials))
        context.serverDataStore.edit { it[serversKey] = raw }
    }

    override suspend fun saveActiveServerId(id: String?) {
        context.serverDataStore.edit { prefs ->
            if (id == null) prefs.remove(activeKey) else prefs[activeKey] = id
        }
    }

    /**
     * One-time migration of pre-0.36 blobs that still carry plaintext
     * secrets: [saveServers] writes them into the encrypted store and
     * re-persists the JSON with the secret fields nulled. The re-persist
     * triggers a fresh DataStore emission, which decodes secret-free and
     * rehydrates from the encrypted store — so collectors never see a gap.
     */
    private suspend fun migrateLegacyPlaintext(decoded: List<TAKServer>) {
        if (!credentials.available) return
        if (decoded.none { it.password != null || it.certificatePassword != null }) return
        if (!migrationAttempted.compareAndSet(false, true)) return
        Log.i(TAG, "Migrating ${decoded.size} server entries' secrets to encrypted storage")
        saveServers(decoded)
    }

    private companion object {
        const val TAG = "TAKServerStore"
    }
}

/**
 * Copy each server's secrets into [credentials] and return the list with the
 * secret fields blanked for JSON persistence. When the encrypted store is
 * unavailable this is the identity function — legacy plaintext behavior is
 * better than silently dropping the operator's credentials.
 */
internal fun stripSecrets(list: List<TAKServer>, credentials: CredentialStore): List<TAKServer> {
    if (!credentials.available) return list
    list.forEach { credentials.store(it.id, it.password, it.certificatePassword) }
    credentials.prune(list.map { it.id }.toSet())
    return list.map {
        if (it.password == null && it.certificatePassword == null) it
        else it.copy(password = null, certificatePassword = null)
    }
}

/**
 * Reverse of [stripSecrets]: fill secret fields from [credentials]. Falls
 * back to whatever the JSON carried (pre-migration plaintext, or null).
 */
internal fun rehydrateSecrets(list: List<TAKServer>, credentials: CredentialStore): List<TAKServer> {
    if (!credentials.available) return list
    return list.map { server ->
        val pw = credentials.password(server.id) ?: server.password
        val certPw = credentials.certificatePassword(server.id) ?: server.certificatePassword
        if (pw == server.password && certPw == server.certificatePassword) server
        else server.copy(password = pw, certificatePassword = certPw)
    }
}
