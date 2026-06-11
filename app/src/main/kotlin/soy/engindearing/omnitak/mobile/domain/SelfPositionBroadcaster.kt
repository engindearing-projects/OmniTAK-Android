package soy.engindearing.omnitak.mobile.domain

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.CotXml
import soy.engindearing.omnitak.mobile.data.SelfFix
import soy.engindearing.omnitak.mobile.data.UserPrefs
import soy.engindearing.omnitak.mobile.data.UserPrefsStore
import java.util.Locale
import java.util.UUID

/**
 * Periodic Position Location Information (PPLI) broadcaster. Mirrors the
 * iOS `PositionBroadcastService`: emits a self-SA CoT event every
 * [intervalMs] for as long as [start] has been called, until [stop].
 *
 * Lifecycle is owned by the caller (ServerManager): start when the
 * connection becomes Connected, stop on Disconnected. The first broadcast
 * fires immediately so TAK servers see a `Set client for subscription`
 * almost as soon as the TLS handshake completes — matching ATAK behavior.
 *
 * Self-UID is generated once and persisted via [UserPrefsStore] so the
 * server treats this device as a stable contact across restarts. The
 * `ANDROID-` prefix triggers the correct ATAK icon set.
 */
class SelfPositionBroadcaster internal constructor(
    private val scope: CoroutineScope,
    private val prefsFlow: Flow<UserPrefs>,
    private val updatePrefs: suspend ((UserPrefs) -> UserPrefs) -> Unit,
    private val sendCoT: suspend (String) -> Boolean,
    private val locationFix: StateFlow<SelfFix?>,
    // Issue #11 — supplies the device's real battery percentage (0..100).
    // Returns null when unknown (no Context yet, BatteryManager error, or
    // a tester-friendly default for unit tests). The previous hardcoded
    // "100" was misleading every peer that read our PPLI.
    private val batteryProvider: () -> Int? = { null },
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val staleSeconds: Long = DEFAULT_STALE_SECONDS,
    // Off-grid mesh PPLI support — when non-null, self-position is also
    // sent over the Meshtastic radio at a throttled rate so two operators
    // with radios and NO server can still see each other.
    private val sendToMesh: (suspend (CoTEvent) -> Boolean)? = null,
    private val meshConnected: () -> Boolean = { false },
    private val meshBroadcastEnabled: () -> Boolean = { true },
    // Lambda (like meshBroadcastEnabled) so the operator's
    // meshBroadcastIntervalSecs pref applies live to a running
    // broadcaster — a Long parameter froze whatever value was cached at
    // construction time (usually the 30 s default, before DataStore emitted).
    private val meshThrottleMs: () -> Long = { 30_000L },
    // Single source of wire identity (#9): production routes through
    // UserPrefsStore.ensureSelfUid via the secondary constructor so
    // exactly one code path mints the ANDROID-<uuid>. The default here
    // only exists for lambda-constructed unit tests.
    private val mintSelfUid: suspend () -> String = {
        val generated = "ANDROID-${UUID.randomUUID()}"
        updatePrefs { it.copy(selfUid = generated) }
        generated
    },
) {
    @Volatile private var lastMeshSendMs: Long = 0L
    constructor(
        scope: CoroutineScope,
        prefsStore: UserPrefsStore,
        sendCoT: suspend (String) -> Boolean,
        locationFix: StateFlow<SelfFix?>,
        batteryProvider: () -> Int? = { null },
        intervalMs: Long = DEFAULT_INTERVAL_MS,
        staleSeconds: Long = DEFAULT_STALE_SECONDS,
        sendToMesh: (suspend (CoTEvent) -> Boolean)? = null,
        meshConnected: () -> Boolean = { false },
        meshBroadcastEnabled: () -> Boolean = { true },
        meshThrottleMs: () -> Long = { 30_000L },
    ) : this(
        scope = scope,
        prefsFlow = prefsStore.prefs,
        updatePrefs = { mutator -> prefsStore.update(mutator) },
        sendCoT = sendCoT,
        locationFix = locationFix,
        batteryProvider = batteryProvider,
        intervalMs = intervalMs,
        staleSeconds = staleSeconds,
        sendToMesh = sendToMesh,
        meshConnected = meshConnected,
        meshBroadcastEnabled = meshBroadcastEnabled,
        meshThrottleMs = meshThrottleMs,
        mintSelfUid = { prefsStore.ensureSelfUid() },
    )

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            // Ensure we have a stable UID before broadcasting. First boot
            // generates one and writes it back so future runs reuse it.
            val prefs = ensureSelfUid()
            Log.i(TAG, "Starting PPLI broadcast — uid=${prefs.selfUid} callsign=${prefs.callsign}")
            broadcastOnce(prefs)
            while (isActive) {
                delay(intervalMs)
                if (!isActive) break
                val latest = currentPrefs()
                broadcastOnce(latest)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun ensureSelfUid(): UserPrefs {
        val current = currentPrefs()
        if (current.selfUid.isNotBlank()) return current
        return current.copy(selfUid = mintSelfUid())
    }

    private suspend fun currentPrefs(): UserPrefs = prefsFlow.first()

    internal suspend fun broadcastOnce(prefs: UserPrefs) {
        val fix = locationFix.value
        val lat: Double
        val lon: Double
        val hae: Double
        val speedKmh: Double
        val ce: Double
        if (fix != null) {
            lat = fix.lat
            lon = fix.lon
            hae = fix.altitudeM
            speedKmh = fix.speedKmh
            ce = if (fix.accuracyM.isNaN()) 9999999.0 else fix.accuracyM.toDouble()
        } else if (prefs.selfLat.isNaN() || prefs.selfLon.isNaN()) {
            // Issue #10 — never broadcast PPLI without a real fix; the
            // San Francisco fallback was misleading operators in Germany.
            Log.d(TAG, "PPLI suppressed — no GPS fix yet")
            return
        } else {
            // Issue #75 — selfLat/selfLon (+ selfHae) are now actually
            // written: OmniTAKApp persists every real fix (throttled), so
            // this fallback broadcasts the last known position with an
            // honest "unknown" circular error instead of going silent.
            lat = prefs.selfLat
            lon = prefs.selfLon
            hae = if (prefs.selfHae.isNaN()) 0.0 else prefs.selfHae
            speedKmh = 0.0
            ce = 9999999.0
        }
        val xml = buildSelfCoT(
            uid = prefs.selfUid.ifBlank { "ANDROID-fallback" },
            callsign = prefs.callsign,
            team = prefs.team,
            lat = lat,
            lon = lon,
            hae = hae,
            ce = ce,
            speedKmh = speedKmh,
            staleSeconds = staleSeconds,
            batteryPercent = batteryProvider().takeIf { it != null && it in 0..100 },
        )
        val ok = sendCoT(xml)
        if (ok) {
            Log.d(TAG, "PPLI sent — ${prefs.callsign} @ $lat,$lon")
        } else {
            Log.w(TAG, "PPLI send failed (no socket?)")
        }

        // Off-grid mesh PPLI — throttled to meshThrottleMs so we don’t
        // flood LoRa bandwidth. wantAck=false is mandatory (LoRa risk).
        val meshFn = sendToMesh
        if (meshFn != null && meshConnected() && meshBroadcastEnabled()) {
            val now = System.currentTimeMillis()
            if (now - lastMeshSendMs >= meshThrottleMs()) {
                val meshEvent = CoTEvent(
                    uid = prefs.selfUid.ifBlank { "ANDROID-fallback" },
                    type = "a-f-G-U-C",
                    lat = lat,
                    lon = lon,
                    hae = hae,
                    ce = ce,
                    callsign = prefs.callsign,
                    teamName = prefs.team,
                )
                val meshOk = meshFn(meshEvent)
                if (meshOk) {
                    lastMeshSendMs = now
                    Log.d(TAG, "Mesh PPLI sent — ${prefs.callsign} @ $lat,$lon")
                } else {
                    Log.w(TAG, "Mesh PPLI send failed")
                }
            }
        }
    }

    companion object {
        private const val TAG = "SelfPositionBroadcaster"
        const val DEFAULT_INTERVAL_MS: Long = 30_000L
        const val DEFAULT_STALE_SECONDS: Long = 180L

        /**
         * Build a self-SA CoT event matching the iOS `generateSelfSACoT`
         * format closely enough for TAK Server 5.7 to assign a callsign
         * and federate to other clients.
         */
        fun buildSelfCoT(
            uid: String,
            callsign: String,
            team: String,
            lat: Double,
            lon: Double,
            staleSeconds: Long,
            hae: Double = 0.0,
            ce: Double = 9999999.0,
            speedKmh: Double = 0.0,
            // null → omit `<status>` entirely. ATAK peers handle a missing
            // battery field gracefully (column shows blank); a hardcoded
            // 100 misled operators reading peer status panes.
            batteryPercent: Int? = null,
        ): String {
            val now = System.currentTimeMillis()
            val safeCallsign = CotXml.escape(callsign)
            val safeTeam = CotXml.escape(team.replaceFirstChar { it.uppercase() })
            // CoT track speed is m/s.
            val speedMs = speedKmh / 3.6
            val detail = buildString {
                append("<detail>")
                append("<contact callsign=\"").append(safeCallsign).append("\" endpoint=\"*:-1:stcp\"/>")
                append("<__group name=\"").append(safeTeam).append("\" role=\"Team Member\"/>")
                if (batteryPercent != null && batteryPercent in 0..100) {
                    append("<status battery=\"").append(batteryPercent).append("\"/>")
                }
                append("<takv device=\"AVD\" platform=\"OmniTAK-Android\" os=\"Android\" version=\"0.1\"/>")
                append("<track speed=\"").append(String.format(Locale.US, "%.2f", speedMs))
                    .append("\" course=\"0.00\"/>")
                append("<precisionlocation altsrc=\"GPS\" geopointsrc=\"GPS\"/>")
                append("<uid Droid=\"").append(safeCallsign).append("\"/>")
                append("<usericon iconsetpath=\"COT_MAPPING_2525B/a-f/a-f-G-U-C\"/>")
                append("</detail>")
            }
            return CotXml.buildEvent(
                uid = uid,
                type = "a-f-G-U-C",
                how = "m-g",
                lat = lat, lon = lon, hae = hae, ce = ce,
                timeIso = CotXml.isoMillis(now),
                staleIso = CotXml.isoMillis(now + staleSeconds * 1000L),
                detailXml = detail,
            )
        }
    }
}
