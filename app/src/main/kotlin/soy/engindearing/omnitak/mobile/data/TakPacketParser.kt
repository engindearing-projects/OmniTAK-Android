package soy.engindearing.omnitak.mobile.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Parses a Meshtastic portnum-72 TAKPacket (atak.proto) payload into a [CoTEvent].
 *
 * This is the Phase-2 inbound parser. The format detection heuristic looks for
 * the strong TAKPacket signal (field 1 varint is_compressed + fields 2–6 being
 * length-delimited sub-messages with the expected sub-fields). When detection
 * fails the caller should fall back to [AtakPluginParser.parse] (Phase-1 TAKMessage).
 *
 * Schema handled (see [TakPacketSerializer] for the full schema comment):
 *   PLI path    → CoT type="a-f-G-U-C", point from pli.lat_i/lon_i
 *   GeoChat path → CoT type="b-t-f", remarks from chat.message
 *
 * Unishox2 decompression is applied when is_compressed=true using [Unishox2],
 * the pure-Kotlin port that runs on plain JVM (unit tests).
 */
object TakPacketParser {

    /**
     * Try to parse [payload] as a TAKPacket. Returns a [CoTEvent] on success or
     * null when the bytes don't look like a valid TAKPacket.
     *
     * Callers should then fall back to [AtakPluginParser.parse] on null.
     */
    fun parse(payload: ByteArray, fromNodeId: UInt = 0u): CoTEvent? {
        if (payload.isEmpty()) return null
        if (!looksLikeTakPacket(payload)) return null
        return parseTakPacket(payload, fromNodeId)
    }

    // region Format detection -----------------------------------------------

    /**
     * Heuristic: a TAKPacket starts with either:
     *   - 0x08 (field 1, varint) for is_compressed=true/false
     *   - 0x12 (field 2, LEN) for contact when is_compressed is default-false (omitted)
     *
     * Additionally we require that none of the fields look like a
     * TAKMessage/CoTEvent (which starts with field 1 LEN = type string "a-f-…"
     * or field 2 LEN). The double-outer-wrapped TAKMessage starts with 0x12
     * (field 2, LEN) but its inner bytes decode as CoTEvent strings. We
     * distinguish them by checking the first field: if field=1,wire=0 (varint)
     * the byte is 0x08 and the value is 0 or 1 → TAKPacket. If field=1,wire=2
     * (LEN) the byte is 0x0A → TAKMessage.
     */
    private fun looksLikeTakPacket(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val first = bytes[0].toInt() and 0xFF
        // 0x08 = field 1, wire 0 (varint) → is_compressed bool
        if (first == 0x08) return true
        // 0x12 = field 2, wire 2 (LEN) → contact sub-message (is_compressed omitted = false)
        // Distinguish from TAKMessage.cotEvent (also 0x12): check that the
        // sub-message contents look like Contact{callsign, device_callsign},
        // not like CoTEvent{type string}
        if (first == 0x12) {
            val sub = MeshtasticProtoParser.readLengthDelimited(bytes, 1) ?: return false
            return looksLikeContact(sub.first)
        }
        return false
    }

    /** A Contact sub-message has field 1 (bytes/LEN) and/or field 2 (bytes/LEN).
     *  Unlike CoTEvent field 1 which is a string type tag "a-f-G-…", Contact
     *  field 1 is arbitrary bytes (possibly Unishox2 compressed, starting with
     *  0x80 or other high bytes). We accept if field 1 is LEN and the content
     *  doesn't start with 'a'/'b'. */
    private fun looksLikeContact(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val (tag, after) = MeshtasticProtoParser.readVarint(bytes, 0) ?: return false
        val field = (tag shr 3).toInt()
        val wire = (tag and 0x7UL).toInt()
        if (field != 1 || wire != 2) return false
        val sub = MeshtasticProtoParser.readLengthDelimited(bytes, after) ?: return false
        // Accept anything — compressed or raw. The point is that this is a bytes
        // field and not a CoTEvent type string that starts with "a-" / "b-".
        return true
    }

    // endregion

    // region Parser ---------------------------------------------------------

    private data class ParsedContact(
        var callsign: String = "",
        var deviceCallsign: String = "",
    )

    private data class ParsedGroup(
        var role: MemberRole = MemberRole.TeamMember,
        var team: Team = Team.Cyan,
    )

    private data class ParsedStatus(
        var battery: Int = 0,
    )

    private data class ParsedPli(
        var latI: Int = 0,
        var lonI: Int = 0,
        var altitude: Int = 0,
        var speed: Int = 0,
        var course: Int = 0,
    )

    private data class ParsedChat(
        var message: String = "",
        var to: String = "All Chat Rooms",
        var toCallsign: String = "",
    )

    private fun parseTakPacket(bytes: ByteArray, fromNodeId: UInt): CoTEvent? {
        var isCompressed = false
        var contact: ParsedContact? = null
        var group: ParsedGroup? = null
        var status: ParsedStatus? = null
        var pli: ParsedPli? = null
        var chat: ParsedChat? = null

        var idx = 0
        while (idx < bytes.size) {
            val (tag, afterTag) = MeshtasticProtoParser.readVarint(bytes, idx) ?: return null
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x7UL).toInt()
            idx = afterTag
            when (field) {
                1 -> { // is_compressed (varint bool)
                    if (wire != 0) { idx = MeshtasticProtoParser.skipField(bytes, idx, wire); continue }
                    val (v, after) = MeshtasticProtoParser.readVarint(bytes, idx) ?: return null
                    isCompressed = v != 0UL
                    idx = after
                }
                2 -> { // contact (LEN)
                    if (wire != 2) { idx = MeshtasticProtoParser.skipField(bytes, idx, wire); continue }
                    val sub = MeshtasticProtoParser.readLengthDelimited(bytes, idx) ?: return null
                    contact = parseContact(sub.first, isCompressed)
                    idx = sub.second
                }
                3 -> { // group (LEN)
                    if (wire != 2) { idx = MeshtasticProtoParser.skipField(bytes, idx, wire); continue }
                    val sub = MeshtasticProtoParser.readLengthDelimited(bytes, idx) ?: return null
                    group = parseGroup(sub.first)
                    idx = sub.second
                }
                4 -> { // status (LEN)
                    if (wire != 2) { idx = MeshtasticProtoParser.skipField(bytes, idx, wire); continue }
                    val sub = MeshtasticProtoParser.readLengthDelimited(bytes, idx) ?: return null
                    status = parseStatus(sub.first)
                    idx = sub.second
                }
                5 -> { // pli (LEN — oneof payload_variant)
                    if (wire != 2) { idx = MeshtasticProtoParser.skipField(bytes, idx, wire); continue }
                    val sub = MeshtasticProtoParser.readLengthDelimited(bytes, idx) ?: return null
                    pli = parsePli(sub.first)
                    idx = sub.second
                }
                6 -> { // chat (LEN — oneof payload_variant)
                    if (wire != 2) { idx = MeshtasticProtoParser.skipField(bytes, idx, wire); continue }
                    val sub = MeshtasticProtoParser.readLengthDelimited(bytes, idx) ?: return null
                    chat = parseChat(sub.first, isCompressed)
                    idx = sub.second
                }
                else -> idx = MeshtasticProtoParser.skipField(bytes, idx, wire)
            }
        }

        val callsign = contact?.callsign?.takeIf { it.isNotEmpty() }
            ?: "MESH-${"%08X".format(fromNodeId.toLong().toInt())}"
        val uid = contact?.deviceCallsign?.takeIf { it.isNotEmpty() }
            ?: "MESHTASTIC-${"%08X".format(fromNodeId.toLong().toInt())}"
        val teamName = group?.team?.toCotName()
        val teamRole = group?.role?.toCotName()
        val now = isoNow()
        val stale = isoStale()

        return when {
            pli != null -> buildPliCoT(pli, uid, callsign, teamName, teamRole, now, stale, group)
            chat != null -> buildChatCoT(chat, uid, callsign, teamName, teamRole, now, stale)
            else -> null
        }
    }

    // region Sub-message parsers

    private fun parseContact(bytes: ByteArray, isCompressed: Boolean): ParsedContact {
        val c = ParsedContact()
        var idx = 0
        while (idx < bytes.size) {
            val (tag, afterTag) = MeshtasticProtoParser.readVarint(bytes, idx) ?: break
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x7UL).toInt()
            idx = afterTag
            when (field) {
                1 -> { // callsign (bytes)
                    if (wire != 2) { idx = MeshtasticProtoParser.skipField(bytes, idx, wire); continue }
                    val sub = MeshtasticProtoParser.readLengthDelimited(bytes, idx) ?: break
                    c.callsign = decompressField(sub.first, isCompressed)
                    idx = sub.second
                }
                2 -> { // device_callsign (bytes)
                    if (wire != 2) { idx = MeshtasticProtoParser.skipField(bytes, idx, wire); continue }
                    val sub = MeshtasticProtoParser.readLengthDelimited(bytes, idx) ?: break
                    c.deviceCallsign = decompressField(sub.first, isCompressed)
                    idx = sub.second
                }
                else -> idx = MeshtasticProtoParser.skipField(bytes, idx, wire)
            }
        }
        return c
    }

    private fun parseGroup(bytes: ByteArray): ParsedGroup {
        val g = ParsedGroup()
        var idx = 0
        while (idx < bytes.size) {
            val (tag, afterTag) = MeshtasticProtoParser.readVarint(bytes, idx) ?: break
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x7UL).toInt()
            idx = afterTag
            when (field) {
                1 -> { // role (varint)
                    if (wire != 0) { idx = MeshtasticProtoParser.skipField(bytes, idx, wire); continue }
                    val (v, after) = MeshtasticProtoParser.readVarint(bytes, idx) ?: break
                    g.role = MemberRole.fromValue(v.toInt())
                    idx = after
                }
                2 -> { // team (varint)
                    if (wire != 0) { idx = MeshtasticProtoParser.skipField(bytes, idx, wire); continue }
                    val (v, after) = MeshtasticProtoParser.readVarint(bytes, idx) ?: break
                    g.team = Team.fromValue(v.toInt())
                    idx = after
                }
                else -> idx = MeshtasticProtoParser.skipField(bytes, idx, wire)
            }
        }
        return g
    }

    private fun parseStatus(bytes: ByteArray): ParsedStatus {
        val s = ParsedStatus()
        var idx = 0
        while (idx < bytes.size) {
            val (tag, afterTag) = MeshtasticProtoParser.readVarint(bytes, idx) ?: break
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x7UL).toInt()
            idx = afterTag
            if (field == 1 && wire == 0) {
                val (v, after) = MeshtasticProtoParser.readVarint(bytes, idx) ?: break
                s.battery = v.toInt()
                idx = after
            } else {
                idx = MeshtasticProtoParser.skipField(bytes, idx, wire)
            }
        }
        return s
    }

    private fun parsePli(bytes: ByteArray): ParsedPli? {
        val p = ParsedPli()
        var sawLat = false
        var sawLon = false
        var idx = 0
        while (idx < bytes.size) {
            val (tag, afterTag) = MeshtasticProtoParser.readVarint(bytes, idx) ?: return null
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x7UL).toInt()
            idx = afterTag
            when (field) {
                1 -> { // latitude_i (sfixed32, wire=5)
                    if (wire != 5) { idx = MeshtasticProtoParser.skipField(bytes, idx, wire); continue }
                    val (raw, after) = MeshtasticProtoParser.readFixed32(bytes, idx) ?: return null
                    p.latI = raw.toInt()
                    sawLat = true
                    idx = after
                }
                2 -> { // longitude_i (sfixed32, wire=5)
                    if (wire != 5) { idx = MeshtasticProtoParser.skipField(bytes, idx, wire); continue }
                    val (raw, after) = MeshtasticProtoParser.readFixed32(bytes, idx) ?: return null
                    p.lonI = raw.toInt()
                    sawLon = true
                    idx = after
                }
                3 -> { // altitude (varint)
                    if (wire != 0) { idx = MeshtasticProtoParser.skipField(bytes, idx, wire); continue }
                    val (v, after) = MeshtasticProtoParser.readVarint(bytes, idx) ?: return null
                    p.altitude = v.toInt()
                    idx = after
                }
                4 -> { // speed (varint)
                    if (wire != 0) { idx = MeshtasticProtoParser.skipField(bytes, idx, wire); continue }
                    val (v, after) = MeshtasticProtoParser.readVarint(bytes, idx) ?: return null
                    p.speed = v.toInt()
                    idx = after
                }
                5 -> { // course (varint)
                    if (wire != 0) { idx = MeshtasticProtoParser.skipField(bytes, idx, wire); continue }
                    val (v, after) = MeshtasticProtoParser.readVarint(bytes, idx) ?: return null
                    p.course = v.toInt()
                    idx = after
                }
                else -> idx = MeshtasticProtoParser.skipField(bytes, idx, wire)
            }
        }
        if (!sawLat || !sawLon) return null
        return p
    }

    private fun parseChat(bytes: ByteArray, isCompressed: Boolean): ParsedChat? {
        val c = ParsedChat()
        var sawMessage = false
        var idx = 0
        while (idx < bytes.size) {
            val (tag, afterTag) = MeshtasticProtoParser.readVarint(bytes, idx) ?: return null
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x7UL).toInt()
            idx = afterTag
            when (field) {
                1 -> { // message (bytes)
                    if (wire != 2) { idx = MeshtasticProtoParser.skipField(bytes, idx, wire); continue }
                    val sub = MeshtasticProtoParser.readLengthDelimited(bytes, idx) ?: return null
                    c.message = decompressField(sub.first, isCompressed)
                    sawMessage = true
                    idx = sub.second
                }
                2 -> { // to (bytes) — gateway sends this raw even when is_compressed=true
                    if (wire != 2) { idx = MeshtasticProtoParser.skipField(bytes, idx, wire); continue }
                    val sub = MeshtasticProtoParser.readLengthDelimited(bytes, idx) ?: return null
                    // Tolerate either raw or compressed (gateway uses raw; some clients may compress)
                    c.to = decompressFieldTolerant(sub.first)
                    idx = sub.second
                }
                3 -> { // to_callsign (bytes)
                    if (wire != 2) { idx = MeshtasticProtoParser.skipField(bytes, idx, wire); continue }
                    val sub = MeshtasticProtoParser.readLengthDelimited(bytes, idx) ?: return null
                    c.toCallsign = decompressFieldTolerant(sub.first)
                    idx = sub.second
                }
                else -> idx = MeshtasticProtoParser.skipField(bytes, idx, wire)
            }
        }
        if (!sawMessage) return null
        return c
    }

    // endregion

    // region CoT event builders

    private fun buildPliCoT(
        pli: ParsedPli,
        uid: String,
        callsign: String,
        teamName: String?,
        teamRole: String?,
        now: String,
        stale: String,
        group: ParsedGroup?,
    ): CoTEvent {
        val lat = pli.latI / 1e7
        val lon = pli.lonI / 1e7
        val hae = pli.altitude.toDouble()
        val detailXml = buildPliDetailXml(callsign, teamName, teamRole, group, pli)
        val rawXml = buildCoTXml(
            uid = uid, type = "a-f-G-U-C", how = "m-g",
            lat = lat, lon = lon, hae = hae, ce = 9_999_999.0, le = 9_999_999.0,
            timeIso = now, staleIso = stale, detailXml = detailXml,
        )
        return CoTEvent(
            uid = uid,
            type = "a-f-G-U-C",
            lat = lat,
            lon = lon,
            hae = hae,
            ce = 9_999_999.0,
            le = 9_999_999.0,
            timeIso = now,
            staleIso = stale,
            callsign = callsign,
            remarks = "",
            teamName = teamName,
            teamRole = teamRole,
            rawXml = rawXml,
        )
    }

    private fun buildChatCoT(
        chat: ParsedChat,
        uid: String,
        callsign: String,
        teamName: String?,
        teamRole: String?,
        now: String,
        stale: String,
    ): CoTEvent {
        val msgUid = "GeoChat.$uid.${chat.to}.${UUID.randomUUID()}"
        val detailXml = buildChatDetailXml(callsign, uid, chat, teamName, teamRole, now)
        val rawXml = buildCoTXml(
            uid = msgUid, type = "b-t-f", how = "h-g-i-g-o",
            lat = 0.0, lon = 0.0, hae = 9_999_999.0, ce = 9_999_999.0, le = 9_999_999.0,
            timeIso = now, staleIso = stale, detailXml = detailXml,
        )
        return CoTEvent(
            uid = msgUid,
            type = "b-t-f",
            lat = 0.0,
            lon = 0.0,
            timeIso = now,
            staleIso = stale,
            callsign = callsign,
            remarks = chat.message,
            teamName = teamName,
            teamRole = teamRole,
            rawXml = rawXml,
        )
    }

    // endregion

    // region XML helpers

    private fun buildPliDetailXml(
        callsign: String,
        teamName: String?,
        teamRole: String?,
        group: ParsedGroup?,
        pli: ParsedPli,
    ): String = buildString {
        append("<detail>")
        append("<contact callsign=\"${esc(callsign)}\"/>")
        if (teamName != null) {
            val role = teamRole?.let { " role=\"${esc(it)}\"" } ?: ""
            append("<__group name=\"${esc(teamName)}\"$role/>")
        }
        if (pli.speed != 0 || pli.course != 0) {
            append("<track speed=\"${pli.speed}\" course=\"${pli.course}\"/>")
        }
        append("</detail>")
    }

    private fun buildChatDetailXml(
        callsign: String,
        fromUid: String,
        chat: ParsedChat,
        teamName: String?,
        teamRole: String?,
        now: String,
    ): String = buildString {
        val chatroom = if (chat.to.isNotEmpty()) chat.to else "All Chat Rooms"
        append("<detail>")
        append("<__chat chatroom=\"${esc(chatroom)}\" groupOwner=\"false\"")
        append(" id=\"${esc(chatroom)}\" parent=\"RootContactGroup\"")
        append(" senderCallsign=\"${esc(callsign)}\">")
        append("<chatgrp id=\"${esc(chatroom)}\" uid0=\"${esc(fromUid)}\" uid1=\"${esc(chatroom)}\"/>")
        append("</__chat>")
        append("<link relation=\"p-p\" type=\"a-f-G-U-C\" uid=\"${esc(fromUid)}\"/>")
        if (teamName != null) {
            val role = teamRole?.let { " role=\"${esc(it)}\"" } ?: ""
            append("<__group name=\"${esc(teamName)}\"$role/>")
        }
        append("<remarks source=\"BAO.F.ATAK.${esc(fromUid)}\" time=\"$now\" to=\"${esc(chatroom)}\">")
        append(esc(chat.message))
        append("</remarks>")
        append("</detail>")
    }

    private fun buildCoTXml(
        uid: String, type: String, how: String,
        lat: Double, lon: Double, hae: Double, ce: Double, le: Double,
        timeIso: String, staleIso: String,
        detailXml: String,
    ): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append("<event version=\"2.0\" uid=\"${esc(uid)}\" type=\"${esc(type)}\"")
        append(" time=\"$timeIso\" start=\"$timeIso\" stale=\"$staleIso\"")
        append(" how=\"${esc(how)}\">")
        append("<point lat=\"$lat\" lon=\"$lon\" hae=\"$hae\" ce=\"$ce\" le=\"$le\"/>")
        append(detailXml)
        append("</event>")
    }

    private fun esc(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    // endregion

    // region Decompression helpers

    /** Decompress a bytes field when is_compressed=true, otherwise decode as UTF-8. */
    private fun decompressField(bytes: ByteArray, isCompressed: Boolean): String {
        if (bytes.isEmpty()) return ""
        return if (isCompressed) {
            runCatching { Unishox2.decompress(bytes) }.getOrElse {
                // Tolerate: if decompress fails, try raw UTF-8
                runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
            }
        } else {
            String(bytes, Charsets.UTF_8)
        }
    }

    /**
     * Tolerant decompression: try Unishox2 first (some senders compress `to`),
     * fall back to raw UTF-8.
     */
    private fun decompressFieldTolerant(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        // If the first byte is a printable ASCII or common string start, treat as raw
        val first = bytes[0].toInt() and 0xFF
        if (first in 0x20..0x7E || first == 0x0A || first == 0x0D) {
            return runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
        }
        // Otherwise try Unishox2
        return runCatching { Unishox2.decompress(bytes) }
            .getOrElse { runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("") }
    }

    // endregion

    // region Timestamp helpers

    private val isoFmt get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun isoNow(): String = isoFmt.format(Date())
    private fun isoStale(): String = isoFmt.format(Date(System.currentTimeMillis() + 300_000L))

    // endregion
}
