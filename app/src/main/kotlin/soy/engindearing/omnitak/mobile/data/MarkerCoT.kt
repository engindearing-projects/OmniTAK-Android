package soy.engindearing.omnitak.mobile.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Build a Cursor-on-Target XML payload for a manually-dropped marker.
 * Mirrors the structure of [SelfPositionBroadcaster.buildSelfCoT] but
 * produces a standalone marker rather than a PPLI self-position. Used by
 * the "Mark My Position" FAB and any future broadcast-on-save marker
 * flow so that dropped markers reach the operator's whole team across
 * every connected TAK server (true multi-server fan-out via
 * [ServerManager.sendCoT]).
 *
 * `type` follows ATAK 2525B convention `a-{affiliation}-G-U-C` for
 * ground unit / combat. SAR-mode callers will swap in non-combat
 * variants when [AppMode] support lands on Android.
 */
object MarkerCoT {

    /**
     * Returns a fresh "MyPos-HHMM" callsign — what a SAR operator
     * defaults to when they hit "Mark My Position" mid-search and just
     * needs the timestamp to reconcile against their notes later.
     */
    fun timestampedCallsign(now: Long = System.currentTimeMillis()): String =
        "MyPos-" + HM_FMT.format(Date(now))

    fun build(
        uid: String,
        callsign: String,
        type: String,
        lat: Double,
        lon: Double,
        hae: Double = 0.0,
        ce: Double = 9999999.0,
        team: String = "Cyan",
        remarks: String = "",
        staleSeconds: Long = 60L * 60L, // 1h — SAR field markers should
        // outlive a coffee break, but not haunt the map a week later.
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        val time = isoUtc(nowMs)
        val stale = isoUtc(nowMs + staleSeconds * 1000L)
        val safeCallsign = escape(callsign)
        val safeTeam = escape(team.replaceFirstChar { it.uppercase() })
        val safeRemarks = escape(remarks)
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append("<event version=\"2.0\"")
            append(" uid=\"").append(escape(uid)).append('"')
            append(" type=\"").append(escape(type)).append('"')
            append(" time=\"").append(time).append('"')
            append(" start=\"").append(time).append('"')
            append(" stale=\"").append(stale).append('"')
            append(" how=\"h-g-i-g-o\">") // human-input, ground, individual, observed
            append("<point lat=\"").append(lat).append('"')
            append(" lon=\"").append(lon).append('"')
            append(" hae=\"").append(formatDouble(hae)).append('"')
            append(" ce=\"").append(formatDouble(ce)).append('"')
            append(" le=\"9999999.0\"/>")
            append("<detail>")
            append("<contact callsign=\"").append(safeCallsign).append("\"/>")
            append("<__group name=\"").append(safeTeam).append("\" role=\"Team Member\"/>")
            if (remarks.isNotBlank()) {
                append("<remarks>").append(safeRemarks).append("</remarks>")
            }
            append("<usericon iconsetpath=\"COT_MAPPING_2525B/")
                .append(escape(type.removePrefix("a-").removeSuffix("-G-U-C")))
                .append("\"/>")
            append("</detail>")
            append("</event>")
        }
    }

    private val HM_FMT = SimpleDateFormat("HHmm", Locale.US)
    private val ISO_FMT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun isoUtc(epochMs: Long): String = synchronized(ISO_FMT) {
        ISO_FMT.format(Date(epochMs))
    }

    private fun formatDouble(v: Double): String =
        if (v == v.toLong().toDouble()) "${v.toLong()}.0" else v.toString()

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
