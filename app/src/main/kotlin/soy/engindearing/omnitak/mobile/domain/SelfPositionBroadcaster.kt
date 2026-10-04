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
import kotlinx.coroutines.withTimeoutOrNull
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
 *
 * #205 — a position restored from the previous session ([SelfFix.restored])
 * is never broadcast: the wire stamps time=now, so it would read as a fresh
 * position and then jump when GPS catches up. While the only fix is a
 * restored one (or there is none yet) the tick is skipped, and the loop
 * wakes the moment a live fix lands so the server replaces whatever it has
 * instead of waiting out the rest of the interval. A manual position
 * ([manualFixProvider]) is the operator's deliberate choice and is always
 * broadcast.
 */
class SelfPositionBroadcaster internal constructor(
    private val scope: CoroutineScope,
    private val prefsFlow: Flow<UserPrefs>,
    private val updatePrefs: suspend ((UserPrefs) -> UserPrefs) -> Unit,
    private val sendCoT: suspend (String) -> Boolean,
    private val locationFix: StateFlow<SelfFix?>,
    // #82 — manual self-position override. When non-null, PPLI broadcasts
    // this coordinate instead of the live GPS fix so teammates / the TAK
    // server see the operator where they manually placed themselves (the
    // self-marker reposition flow). Returns null in normal GPS mode, which
    // falls through to the live fix.
    // A lambda (like meshBroadcastEnabled) so toggling manual mode on a
    // running broadcaster takes effect on the next tick without a restart.
    private val manualFixProvider: () -> SelfFix? = { null },
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
    // #205 - set when the last tick had nothing trustworthy to send (no fix
    // yet, or only the one restored from the last session). While set, the
    // loop waits for a live fix instead of sleeping the whole interval, so
    // the first real position goes out at once.
    @Volatile private var heldForLiveFix: Boolean = false
    // #205 - log "restored fix held back" once per held episode, not on every
    // 30 s tick while GPS is still acquiring.
    @Volatile private var loggedRestoredHold: Boolean = false
    constructor(
        scope: CoroutineScope,
        prefsStore: UserPrefsStore,
        sendCoT: suspend (String) -> Boolean,
        locationFix: StateFlow<SelfFix?>,
        manualFixProvider: () -> SelfFix? = { null },
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
        manualFixProvider = manualFixProvider,
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
                awaitNextTick()
                if (!isActive) break
                val latest = currentPrefs()
                broadcastOnce(latest)
            }
        }
    }

    /**
     * Wait for the next scheduled send. Normally a plain [intervalMs] delay.
     * #205 - when the last tick was held (no live fix yet) also wake as soon
     * as one lands, so the server replaces its old position at once instead
     * of up to a full interval later. Still bounded by [intervalMs] so a
     * manual position or a changed pref is picked up on the normal cadence.
     *
     * The wait is on the flow's current value, so a live fix that landed
     * between the held tick and this call wakes it immediately (no lost
     * wake-up). The early wake is consumed here: [heldForLiveFix] is cleared
     * on the way out and only a tick that holds again sets it, so if the tick
     * after a wake does not send for any other reason (reporting switched
     * off, say) the next wait is a plain interval, never a busy loop.
     */
    private suspend fun awaitNextTick() {
        if (!heldForLiveFix) {
            delay(intervalMs)
            return
        }
        withTimeoutOrNull(intervalMs) {
            locationFix.first { it != null && !it.restored }
        }
        heldForLiveFix = false
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
        // #82 — a manual self-position override wins over live GPS so PPLI
        // reports where the operator placed themselves. It carries no real
        // accuracy (Float.NaN → unknown CE), so peers don't read a manual
        // drop as a precise GPS lock.
        val manual = manualFixProvider()
        val fix = manual ?: locationFix.value
        // #205 - only a position we actually know goes out. No fix at all
        // (issue #10: the San Francisco fallback) or only the one restored
        // from the last session means hold: the wire stamps time=now, so a
        // restored position would read as fresh and then jump when GPS
        // catches up (other operators see two markers). Persisted prefs are
        // not a second source: they only feed the map's restored seed. A
        // manual drop is the operator's deliberate choice and is never held.
        if (fix == null) {
            heldForLiveFix = true
            Log.d(TAG, "PPLI suppressed - no GPS fix yet")
            return
        }
        if (manual == null && fix.restored) {
            heldForLiveFix = true
            if (!loggedRestoredHold) {
                loggedRestoredHold = true
                Log.i(TAG, "PPLI held - only a position restored from the last session; waiting for a live fix")
            }
            return
        }
        heldForLiveFix = false
        loggedRestoredHold = false
        val lat = fix.lat
        val lon = fix.lon
        val hae = fix.altitudeM
        val speedKmh = fix.speedKmh
        val ce = if (fix.accuracyM.isNaN()) 9999999.0 else fix.accuracyM.toDouble()
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
