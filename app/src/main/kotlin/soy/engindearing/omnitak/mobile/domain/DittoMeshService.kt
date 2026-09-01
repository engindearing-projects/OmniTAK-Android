package soy.engindearing.omnitak.mobile.domain

import android.content.Context
import android.util.Log
import com.ditto.kotlin.Ditto
import com.ditto.kotlin.DittoConfig
import com.ditto.kotlin.DittoFactory
import com.ditto.kotlin.DittoQueryResult
import com.ditto.kotlin.DittoStoreObserver
import com.ditto.kotlin.DittoSyncSubscription
import com.ditto.kotlin.DittoAuthenticationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import soy.engindearing.omnitak.mobile.BuildConfig
import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.CoTParser
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Peer-to-peer CoT sync over Ditto — the "no server, no internet" transport.
 * Android port of iOS `DittoMeshService.swift`; the two must stay
 * wire-identical (same document schema, same Ditto SDK version — 5.1.0,
 * pinned in build.gradle.kts and in iOS's scripts/add_ditto_mesh.rb).
 *
 * OmniTAK already has two mesh paths, but both are LoRa: Meshtastic and
 * MeshCore move a few hundred bytes at a time, so CoT has to be squeezed into
 * compact TAKPacket encodings that drop most of the event. Ditto is a different
 * animal — it syncs over Bluetooth LE, Wi-Fi Aware and the LAN at real
 * bandwidth, so it carries the *full* CoT XML with nothing thrown away.
 *
 * Model: Ditto is a CRDT document store, and CoT maps onto it as *state* keyed
 * by uid, not as a message stream. Publishing is an upsert keyed on uid
 * (chat is per-message — see [DittoWire.docId]), last-write-wins is correct
 * because each uid has exactly one authoritative emitter, and every peer
 * evicts independently on the CoT stale time the producer stamped, so the
 * mesh converges with no coordinator and no tombstones.
 *
 * House style: every forwarding/keying/parsing decision is a pure function in
 * [DittoWire] (JVM unit-tested, no SDK needed); this class only owns the SDK
 * lifecycle and the coroutine plumbing — same split as [MeshServerRelay].
 *
 * Inert unless build credentials exist (see [bundledIdentity]) AND the
 * operator has switched the mesh on. A fresh clone builds and runs with the
 * mesh simply off.
 */
class DittoMeshService(
    context: Context,
    private val scope: CoroutineScope,
    /** Forward one mesh-heard XML frame to the TAK servers (gateway mode).
     *  Wire this to `ServerManager.sendCoT(xml, isRelay = true)` — the
     *  isRelay flag is what keeps relayed traffic from echoing back onto
     *  the mesh and looping between gateway devices. */
    private val sendToServer: suspend (xml: String) -> Boolean,
    private val serverConnected: () -> Boolean,
) {

    sealed class State {
        data object Disabled : State()
        data object Starting : State()
        data object Syncing : State()
        data class Failed(val message: String) : State()

        val label: String
            get() = when (this) {
                Disabled -> "Off"
                Starting -> "Starting…"
                Syncing -> "Meshing"
                is Failed -> "Error: $message"
            }
    }

    /** How this build authenticates onto the mesh. The database ID is the
     *  sync boundary — peers only ever see each other if they share one,
     *  which is why credentials are per-build, not per-user.
     *
     *  Playground is development-only (expiring token, needs Ditto's cloud
     *  once); SharedKey is the shipping mode — self-signed certs from one
     *  pre-shared key, air-gap capable, offline license from Ditto. SharedKey
     *  wins when both are present: a build carrying a real offline key must
     *  never silently fall back to a development token. */
    sealed class Identity {
        data class Playground(val databaseId: String, val url: String, val token: String) : Identity()
        data class SharedKey(val databaseId: String, val key: String, val licenseToken: String) : Identity()
    }

    private val _state = MutableStateFlow<State>(State.Disabled)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _peerCount = MutableStateFlow(0)
    val peerCount: StateFlow<Int> = _peerCount.asStateFlow()

    /** Peers in range running a wire-incompatible Ditto build. Nonzero means
     *  someone nearby will never sync no matter how close they stand — the
     *  failure mode if iOS and Android ever drift to different SDK versions. */
    private val _incompatiblePeerCount = MutableStateFlow(0)
    val incompatiblePeerCount: StateFlow<Int> = _incompatiblePeerCount.asStateFlow()

    private val _published = MutableStateFlow(0)
    val published: StateFlow<Int> = _published.asStateFlow()

    private val _received = MutableStateFlow(0)
    val received: StateFlow<Int> = _received.asStateFlow()

    private val _relayed = MutableStateFlow(0)
    val relayed: StateFlow<Int> = _relayed.asStateFlow()

    private val _lastInboundAtMs = MutableStateFlow<Long?>(null)
    val lastInboundAtMs: StateFlow<Long?> = _lastInboundAtMs.asStateFlow()

    /** Mesh-heard events land here (already parsed, rawXml preserved).
     *  OmniTAKApp wires this to the same classify → chat/contact ingest the
     *  Meshtastic sink uses, tagged `CoTSource.mesh("Ditto")`. */
    @Volatile var cotSink: ((CoTEvent) -> Unit)? = null

    private var ditto: Ditto? = null
    private var subscription: DittoSyncSubscription? = null
    private var observer: DittoStoreObserver? = null
    private var presenceJob: Job? = null
    private var evictionJob: Job? = null

    /** Last `sentAt` ingested per document — the observer re-delivers the
     *  whole matching set on every change, not a delta, so without this every
     *  marker on the mesh would re-ingest each time any one of them moved. */
    private val ingested = ConcurrentHashMap<String, Long>()

    /** Stable per-install id, stamped on every document we write so the
     *  inbound query can exclude our own writes in DQL. */
    private val peerKey: String

    @Volatile private var enabled = false
    @Volatile private var channel = DittoWire.DEFAULT_CHANNEL
    @Volatile private var gatewayEnabled = false

    init {
        val prefs = context.getSharedPreferences("ditto_mesh", Context.MODE_PRIVATE)
        peerKey = prefs.getString(PEER_KEY_PREF, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(PEER_KEY_PREF, it).apply()
        }
    }

    val isConfigured: Boolean get() = bundledIdentity() != null

    /** Prefs-driven lifecycle. Called from OmniTAKApp's UserPrefs collector on
     *  every prefs write; cheap and idempotent. A channel change while running
     *  tears down and rebuilds so the subscription and observer re-bind. */
    fun applyPrefs(enabled: Boolean, channel: String, gatewayEnabled: Boolean) {
        val cleanChannel = channel.trim().ifBlank { DittoWire.DEFAULT_CHANNEL }
        this.gatewayEnabled = gatewayEnabled
        val channelChanged = cleanChannel != this.channel
        val enableChanged = enabled != this.enabled
        this.channel = cleanChannel
        this.enabled = enabled
        when {
            !enabled && ditto != null -> stop()
            enabled && ditto == null -> start()
            enabled && channelChanged -> { stop(); start() }
            enableChanged && !enabled -> _state.value = State.Disabled
        }
    }

    /** Bring the mesh up. Safe to call repeatedly; a running mesh is left alone. */
    fun start() {
        if (ditto != null) return
        if (!enabled) { _state.value = State.Disabled; return }
        val identity = bundledIdentity() ?: run { _state.value = State.Disabled; return }

        _state.value = State.Starting
        try {
            val instance: Ditto
            when (identity) {
                is Identity.Playground -> {
                    instance = DittoFactory.create(
                        config = DittoConfig(
                            databaseId = identity.databaseId,
                            connect = DittoConfig.Connect.Server(identity.url),
                        ),
                        coroutineScope = scope,
                    )
                    // Playground tokens expire. Re-login on the expiry callback,
                    // otherwise a long-running device quietly stops syncing and
                    // it presents as a range problem rather than an auth one.
                    // Handler must be set before sync.start() per the SDK docs.
                    instance.auth?.expirationHandler = { d, _ ->
                        runCatching {
                            d.auth?.login(identity.token, DittoAuthenticationProvider.development())
                        }.onFailure { Log.e(TAG, "re-auth failed: ${it.message}") }
                    }
                    scope.launch {
                        runCatching {
                            instance.auth?.login(identity.token, DittoAuthenticationProvider.development())
                        }.onFailure { Log.e(TAG, "auth failed: ${it.message}") }
                    }
                }
                is Identity.SharedKey -> {
                    // No cloud in this path at all: peers authenticate each
                    // other with certificates signed by the shared key, so a
                    // device that has never had a signal still meshes.
                    instance = DittoFactory.create(
                        config = DittoConfig(
                            databaseId = identity.databaseId,
                            connect = DittoConfig.Connect.SmallPeersOnly(identity.key),
                        ),
                        coroutineScope = scope,
                    )
                    instance.setOfflineOnlyLicenseToken(identity.licenseToken)
                }
            }

            // Bluetooth LE + Wi-Fi Aware + LAN. This is what makes two phones
            // in a field with no infrastructure find each other.
            instance.updateTransportConfig { cfg ->
                cfg.peerToPeer {
                    bluetoothLe { enabled = true }
                    lan { enabled = true }
                    wifiAware { enabled = true }
                }
            }

            instance.sync.start()

            // Replicate only this channel's traffic. Subscription arguments are
            // fixed at registration; staleness is deliberately NOT part of the
            // predicate — the eviction loop handles it.
            subscription = instance.sync.registerSubscription(
                query = "SELECT * FROM ${DittoWire.COLLECTION} WHERE channel = :channel",
                arguments = mapOf<String, Any?>("channel" to channel),
            )
            observer = instance.store.registerObserver(
                query = "SELECT * FROM ${DittoWire.COLLECTION} WHERE channel = :channel AND peer != :me",
                arguments = mapOf<String, Any?>("channel" to channel, "me" to peerKey),
            ) { result -> ingest(result) }

            presenceJob = scope.launch {
                instance.presence.observe().collect { graph ->
                    _peerCount.value = graph.remotePeers.size
                    _incompatiblePeerCount.value = graph.remotePeers.count { it.isCompatible == false }
                }
            }
            evictionJob = scope.launch {
                // Every peer evicts on the same `staleAt` the producer stamped,
                // so the mesh converges on what is live with no coordination.
                while (isActive) {
                    delay(EVICT_INTERVAL_MS)
                    evictStale()
                }
            }

            ditto = instance
            _state.value = State.Syncing
        } catch (t: Throwable) {
            _state.value = State.Failed(t.message ?: t.javaClass.simpleName)
            Log.e(TAG, "Ditto start failed", t)
            stopInternals()
        }
    }

    fun stop() {
        stopInternals()
        runCatching { ditto?.sync?.stop() }
        ditto = null
        ingested.clear()
        _peerCount.value = 0
        _incompatiblePeerCount.value = 0
        _state.value = State.Disabled
    }

    private fun stopInternals() {
        runCatching { observer?.close() };     observer = null
        runCatching { subscription?.close() }; subscription = null
        presenceJob?.cancel();                 presenceJob = null
        evictionJob?.cancel();                 evictionJob = null
    }

    // MARK: inbound ----------------------------------------------------------

    private fun ingest(result: DittoQueryResult) {
        val nowMs = System.currentTimeMillis()
        for (item in result.items) {
            val doc = runCatching { Json.parseToJsonElement(item.jsonString()).jsonObject }
                .getOrNull() ?: continue
            val id = doc["_id"]?.jsonPrimitive?.contentOrNull ?: continue
            val xml = doc["xml"]?.jsonPrimitive?.contentOrNull ?: continue
            if (xml.isEmpty()) continue

            // A newer OmniTAK may write fields this build has never heard of.
            // Skipping is the safe move: the full CoT XML still rides in `xml`,
            // so a later version can add structure without this one inventing
            // meaning for it.
            val version = doc["v"]?.jsonPrimitive?.longOrNull ?: 1L
            if (!DittoWire.admitVersion(version)) continue

            val sentAt = doc["sentAt"]?.jsonPrimitive?.longOrNull ?: 0L
            val seen = ingested[id]
            if (seen != null && seen >= sentAt) continue
            ingested[id] = sentAt

            // Drop events that were already stale when they reached us rather
            // than briefly painting a dead track on the map.
            val staleAt = doc["staleAt"]?.jsonPrimitive?.longOrNull ?: 0L
            if (DittoWire.isExpired(staleAt, nowMs)) continue

            // Onto our own map first — that path is unconditional and is what
            // makes two phones alone in a field useful to each other.
            // CoTParser preserves rawXml, so chat classification downstream
            // can re-parse the full GeoChat detail.
            CoTParser.parse(xml)?.let { event -> runCatching { cotSink?.invoke(event) } }
            _received.update { it + 1 }
            _lastInboundAtMs.value = nowMs

            // Then, if this device is the gateway, push it onward. The ORIGINAL
            // xml is forwarded rather than a re-encoding of the parsed event:
            // re-encoding would drop every detail element OmniTAK does not
            // model, and the gateway's job is to be a pipe, not an editor.
            if (gatewayEnabled && serverConnected()) {
                scope.launch {
                    if (runCatching { sendToServer(xml) }.getOrDefault(false)) {
                        _relayed.update { it + 1 }
                    }
                }
            }
        }
    }

    // MARK: outbound ---------------------------------------------------------

    /** Upsert one CoT event onto the mesh. Called from the single outbound
     *  choke point in ServerManager.sendCoT, so anything OmniTAK emits to a
     *  server also reaches peers — including when no server is connected. */
    fun publish(xml: String) {
        val instance = ditto ?: return
        if (_state.value != State.Syncing) return
        val meta = DittoWire.meta(xml, System.currentTimeMillis()) ?: return
        val doc = DittoWire.buildDoc(channel, peerKey, xml, meta)
        scope.launch {
            runCatching {
                instance.store.execute(
                    query = "INSERT INTO ${DittoWire.COLLECTION} DOCUMENTS (:doc) ON ID CONFLICT DO UPDATE",
                    arguments = mapOf<String, Any?>("doc" to doc),
                )
                _published.update { it + 1 }
            }.onFailure { Log.e(TAG, "publish failed: ${it.message}") }
        }
    }

    // MARK: eviction ---------------------------------------------------------

    private suspend fun evictStale() {
        val instance = ditto ?: return
        val nowMs = System.currentTimeMillis()
        runCatching {
            instance.store.execute(
                query = "EVICT FROM ${DittoWire.COLLECTION} WHERE staleAt < :now AND staleAt > 0",
                arguments = mapOf<String, Any?>("now" to nowMs),
            )
        }.onFailure { Log.d(TAG, "evict failed: ${it.message}") }
        val floor = nowMs - INGESTED_RETENTION_MS
        ingested.entries.removeIf { it.value <= floor }
    }

    companion object {
        private const val TAG = "DittoMesh"
        private const val PEER_KEY_PREF = "peer_key"
        private const val EVICT_INTERVAL_MS = 60_000L
        private const val INGESTED_RETENTION_MS = 3_600_000L

        /** Credentials come from local.properties via BuildConfig, the same
         *  route the Cesium token takes. Absent means "not built with mesh
         *  credentials" — the default for a fresh clone — and the mesh stays
         *  entirely off. */
        fun bundledIdentity(): Identity? {
            fun str(v: String): String? = v.trim().ifEmpty { null }
            val db = str(BuildConfig.DITTO_DATABASE_ID) ?: return null
            val key = str(BuildConfig.DITTO_SHARED_KEY)
            val license = str(BuildConfig.DITTO_OFFLINE_LICENSE)
            if (key != null && license != null) return Identity.SharedKey(db, key, license)
            val token = str(BuildConfig.DITTO_TOKEN)
            val url = str(BuildConfig.DITTO_URL)
            if (token != null && url != null) return Identity.Playground(db, url, token)
            return null
        }
    }
}

/**
 * The pure half of the Ditto mesh: wire schema constants, CoT metadata
 * extraction and every keying/admission decision — no SDK types, fully
 * JVM-unit-testable ([DittoWireTest]). MUST stay semantically identical to
 * the iOS `CoTWireMeta` + `DittoMeshService` document logic; the schema
 * version below is the cross-platform compatibility contract.
 */
internal object DittoWire {

    /** Ditto collection holding CoT state. One collection, scoped by channel. */
    const val COLLECTION = "cot"

    /** Wire schema version for documents this build writes. Bump on any change
     *  to the field set; readers skip versions newer than they understand
     *  rather than guessing. Must match iOS `DittoMeshService.schemaVersion`. */
    const val SCHEMA_VERSION = 1L

    /** Default room. Teams that want isolation change this to a shared word;
     *  only peers on the same channel exchange tracks. */
    const val DEFAULT_CHANNEL = "omnitak"

    /** GeoChat CoT type — chat is a conversation, not state: two messages from
     *  the same sender must both survive, so they get per-message doc ids
     *  instead of sharing a uid-keyed document the way a position does. */
    const val CHAT_TYPE = "b-t-f"

    /** The handful of fields the mesh needs to index a CoT document, pulled
     *  straight off the `<event>` and `<point>` attributes. Deliberately not
     *  [CoTParser]: the mesh has to carry ANY well-formed CoT — including
     *  types OmniTAK does not render yet — and the full XML rides along
     *  untouched regardless. */
    data class Meta(
        val uid: String,
        val type: String,
        val lat: Double,
        val lon: Double,
        val sentAt: Long,
        val staleAt: Long,
    )

    fun meta(xml: String, nowMs: Long): Meta? {
        val uid = attr("uid", xml)?.takeIf { it.isNotEmpty() } ?: return null
        val type = attr("type", xml)?.takeIf { it.isNotEmpty() } ?: return null
        return Meta(
            uid = uid,
            type = type,
            lat = attr("lat", xml)?.toDoubleOrNull() ?: 0.0,
            lon = attr("lon", xml)?.toDoubleOrNull() ?: 0.0,
            sentAt = nowMs,
            staleAt = parseIsoMs(attr("stale", xml)),
        )
    }

    /** First `name="value"` match. CoT attribute values are XML-escaped and
     *  never contain a raw quote, so scanning to the next quote suffices. */
    fun attr(name: String, xml: String): String? {
        val marker = "$name=\""
        val start = xml.indexOf(marker)
        if (start < 0) return null
        val valueStart = start + marker.length
        val end = xml.indexOf('"', valueStart)
        if (end < 0) return null
        return xml.substring(valueStart, end)
    }

    /** ISO-8601 → epoch ms; 0 when absent/unparseable (0 = "never expires",
     *  matching iOS). Instant.parse handles both fractional and whole-second
     *  timestamps. */
    fun parseIsoMs(iso: String?): Long {
        if (iso.isNullOrBlank()) return 0L
        return try {
            Instant.parse(iso).toEpochMilli()
        } catch (_: DateTimeParseException) {
            0L
        }
    }

    /** Chat keys per-message; everything else keys per-uid (state upsert). */
    fun docId(channel: String, meta: Meta): String =
        if (meta.type == CHAT_TYPE) "$channel|${meta.uid}|${meta.sentAt}"
        else "$channel|${meta.uid}"

    fun buildDoc(channel: String, peerKey: String, xml: String, meta: Meta): Map<String, Any?> = mapOf(
        "_id" to docId(channel, meta),
        // Schema version: costs one integer now and is the only thing that
        // makes it possible to change this document shape later without older
        // installs in the field misreading the new fields.
        "v" to SCHEMA_VERSION,
        "uid" to meta.uid,
        "type" to meta.type,
        "channel" to channel,
        "peer" to peerKey,
        "xml" to xml,
        "lat" to meta.lat,
        "lon" to meta.lon,
        "sentAt" to meta.sentAt,
        "staleAt" to meta.staleAt,
    )

    fun admitVersion(v: Long): Boolean = v <= SCHEMA_VERSION

    fun isExpired(staleAt: Long, nowMs: Long): Boolean = staleAt in 1 until nowMs
}
