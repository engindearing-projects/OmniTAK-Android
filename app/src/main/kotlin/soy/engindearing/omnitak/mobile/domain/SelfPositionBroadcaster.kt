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
import soy.engindearing.omnitak.mobile.data.SelfFix
import soy.engindearing.omnitak.mobile.data.UserPrefs
import soy.engindearing.omnitak.mobile.data.UserPrefsStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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
    // Step 2 of unified identity. Returns the operator's own mesh-node
    // fix or null. Caller is responsible for matching the node to the
    // operator (callsign equality) and filtering by freshness
    // ([UserPrefs.selfMeshFixFreshnessSecs]). Receives the live prefs so
    // wiring can read both threshold + callsign without a separate
    // suspend lookup. Default `{ null }` keeps existing test wiring
    // unchanged.
    private val selfMeshFix: (UserPrefs) -> SelfFix? = { null },
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val staleSeconds: Long = DEFAULT_STALE_SECONDS,
) {
    constructor(
        scope: CoroutineScope,
        prefsStore: UserPrefsStore,
        sendCoT: suspend (String) -> Boolean,
        locationFix: StateFlow<SelfFix?>,
        batteryProvider: () -> Int? = { null },
        selfMeshFix: (UserPrefs) -> SelfFix? = { null },
        intervalMs: Long = DEFAULT_INTERVAL_MS,
        staleSeconds: Long = DEFAULT_STALE_SECONDS,
    ) : this(
        scope = scope,
        prefsFlow = prefsStore.prefs,
        updatePrefs = { mutator -> prefsStore.update(mutator) },
        sendCoT = sendCoT,
        locationFix = locationFix,
        batteryProvider = batteryProvider,
        selfMeshFix = selfMeshFix,
        intervalMs = intervalMs,
        staleSeconds = staleSeconds,
    )

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            // Ensure we have a stable UID before broadcasting. First boot
            // generates one and writes it back so future runs reuse it.
            val prefs = ensureSelfUid()
            Log.i(TAG, "Starting PPLI broadcast — uid=${prefs.selfUid} callsign=${prefs.callsign} interval=${prefs.pliIntervalSecs}s")
            broadcastOnce(prefs)
            while (isActive) {
                // Re-read pliIntervalSecs each iteration so an operator
                // pushing a new value via tak://preference or
                // configBundleUrl takes effect on the next tick without a
                // restart. Floor at 5s to avoid accidental DoS of the
                // mesh / TAK server.
                val latest = currentPrefs()
                val intervalMs = (latest.pliIntervalSecs.coerceAtLeast(5)) * 1000L
                delay(intervalMs)
                if (!isActive) break
                broadcastOnce(currentPrefs())
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
        val generated = "ANDROID-${UUID.randomUUID()}"
        updatePrefs { it.copy(selfUid = generated) }
        return current.copy(selfUid = generated)
    }

    private suspend fun currentPrefs(): UserPrefs = prefsFlow.first()

    internal suspend fun broadcastOnce(prefs: UserPrefs) {
        // Step 2 of unified identity. When the operator runs a Meshtastic
        // node in TAK_TRACKER role with the same callsign, that node's GPS
        // (worn on body) is the authoritative position. The phone GPS is
        // only a fallback if the radio is unreachable / stale.
        val meshFix = if (prefs.preferMeshFixForSelfPpli) selfMeshFix(prefs) else null
        val phoneFix = locationFix.value
        val (fix, source) = when {
            meshFix != null -> meshFix to PositionSource.MESHTASTIC
            phoneFix != null -> phoneFix to PositionSource.GPS
            else -> null to PositionSource.GPS
        }

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
            lat = prefs.selfLat
            lon = prefs.selfLon
            hae = 0.0
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
            positionSource = source,
        )
        val ok = sendCoT(xml)
        if (ok) {
            Log.d(TAG, "PPLI sent — ${prefs.callsign} @ $lat,$lon (src=${source.wire})")
        } else {
            Log.w(TAG, "PPLI send failed (no socket?)")
        }
    }

    /**
     * Tags the `<precisionlocation geopointsrc="…">` attribute so peers
     * know whether this PPLI came from the phone's GPS or from the
     * operator's mesh node. ATAK and TAKaware both surface this in the
     * contact details pane.
     */
    enum class PositionSource(val wire: String) {
        GPS("GPS"),
        MESHTASTIC("Meshtastic"),
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
            positionSource: PositionSource = PositionSource.GPS,
        ): String {
            val now = System.currentTimeMillis()
            val time = isoUtc(now)
            val stale = isoUtc(now + staleSeconds * 1000L)
            val safeCallsign = escapeXml(callsign)
            val safeTeam = escapeXml(team.replaceFirstChar { it.uppercase() })
            // CoT track speed is m/s.
            val speedMs = speedKmh / 3.6
            return buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                append("<event version=\"2.0\"")
                append(" uid=\"").append(escapeXml(uid)).append('"')
                append(" type=\"a-f-G-U-C\"")
                append(" time=\"").append(time).append('"')
                append(" start=\"").append(time).append('"')
                append(" stale=\"").append(stale).append('"')
                append(" how=\"m-g\">")
                append("<point lat=\"").append(lat).append('"')
                append(" lon=\"").append(lon).append('"')
                append(" hae=\"").append(formatDouble(hae)).append('"')
                append(" ce=\"").append(formatDouble(ce)).append('"')
                append(" le=\"9999999.0\"/>")
                append("<detail>")
                append("<contact callsign=\"").append(safeCallsign).append("\" endpoint=\"*:-1:stcp\"/>")
                append("<__group name=\"").append(safeTeam).append("\" role=\"Team Member\"/>")
                if (batteryPercent != null && batteryPercent in 0..100) {
                    append("<status battery=\"").append(batteryPercent).append("\"/>")
                }
                append("<takv device=\"AVD\" platform=\"OmniTAK-Android\" os=\"Android\" version=\"0.1\"/>")
                append("<track speed=\"").append(String.format(Locale.US, "%.2f", speedMs))
                    .append("\" course=\"0.00\"/>")
                append("<precisionlocation altsrc=\"").append(positionSource.wire)
                    .append("\" geopointsrc=\"").append(positionSource.wire).append("\"/>")
                append("<uid Droid=\"").append(safeCallsign).append("\"/>")
                append("<usericon iconsetpath=\"COT_MAPPING_2525B/a-f/a-f-G-U-C\"/>")
                append("</detail>")
                append("</event>")
            }
        }

        private fun formatDouble(v: Double): String =
            if (v == v.toLong().toDouble()) "${v.toLong()}.0" else v.toString()

        private val ISO_FMT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        private fun isoUtc(epochMs: Long): String = synchronized(ISO_FMT) {
            ISO_FMT.format(Date(epochMs))
        }

        private fun escapeXml(s: String): String = s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
