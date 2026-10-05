package soy.engindearing.omnitak.mobile.data

/**
 * Hand-rolled protobuf decoder for Meshtastic FromRadio frames. Mirrors
 * the iOS [MeshtasticTCPClient.parseFromRadio] approach — no codegen,
 * no protobuf-javalite dependency. We only decode the subset of fields
 * OmniTAK actually renders today (NodeInfo + Position + the wrapping
 * MeshPacket portnum/payload), and skip everything else with the
 * generic wire-type skipper for forward compatibility.
 *
 * Wire-format reference:
 *   https://protobuf.dev/programming-guides/encoding/
 *   https://github.com/meshtastic/protobufs
 *
 * FromRadio top-level fields (canonical Meshtastic mesh.proto):
 *   2  packet            — MeshPacket (length-delimited)
 *   3  my_info           — MyNodeInfo (length-delimited)
 *   4  node_info         — NodeInfo (length-delimited)
 *   5  config            — Config oneof (length-delimited) — radio dumps these after want_config_id
 *   7  config_complete_id (varint)
 *  10  channel           — Channel (length-delimited) — primary + secondary channel info
 *
 * Earlier revisions of this parser had my_info/node_info at fields 5/6
 * (a holdover from an outdated iOS impl). That meant real radio NodeInfo
 * frames at field 4 got skipped silently — Bytes RX ticked up but the
 * Nodes table stayed empty. Fixed in GAP-109 read-back debugging.
 */
object MeshtasticProtoParser {

    // region Public API ---------------------------------------------------

    /** Parse a single FromRadio frame. Returns null on a totally
     *  unrecognised top-level field set, or the matching sealed variant
     *  when one of the fields we care about lands first. */
    fun parseFromRadio(bytes: ByteArray): FromRadioFrame? {
        if (bytes.isEmpty()) return null
        var idx = 0
        // FromRadio is a oneof — but since the oneof maps to flat field
        // numbers on the wire, we just walk the buffer and return the
        // first variant we recognise. Unknown fields are skipped.
        while (idx < bytes.size) {
            val (tag, afterTag) = readVarint(bytes, idx) ?: return null
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x7UL).toInt()
            idx = afterTag
            when (field) {
                2 -> { // MeshPacket
                    if (wire != 2) { idx = skipField(bytes, idx, wire); continue }
                    val sub = readLengthDelimited(bytes, idx) ?: return null
                    val packet = parseMeshPacket(sub.first) ?: return FromRadioFrame.Unknown
                    return FromRadioFrame.Packet(packet)
                }
                3 -> { // MyNodeInfo (canonical field 3)
                    if (wire != 2) { idx = skipField(bytes, idx, wire); continue }
                    val sub = readLengthDelimited(bytes, idx) ?: return null
                    val nodeNum = parseMyNodeInfo(sub.first)
                    return FromRadioFrame.MyInfo(nodeNum)
                }
                4 -> { // NodeInfo (canonical field 4)
                    if (wire != 2) { idx = skipField(bytes, idx, wire); continue }
                    val sub = readLengthDelimited(bytes, idx) ?: return null
                    val parsed = parseNodeInfo(sub.first) ?: return FromRadioFrame.Unknown
                    return FromRadioFrame.NodeInfoFrame(parsed.node, userRaw = parsed.userRaw)
                }
                5 -> { // Config (canonical field 5): every Config variant the radio dumps
                    if (wire != 2) { idx = skipField(bytes, idx, wire); continue }
                    val sub = readLengthDelimited(bytes, idx) ?: return null
                    // The decoded value covers the variants the settings screen shows
                    // (device, position, lora); every other variant (power, network,
                    // display, bluetooth, security, ...) has none. The raw bytes ride
                    // along with every frame and last only as long as the frame does:
                    // RadioSettingsCache keeps the three variants the app patches and
                    // drops the rest, the security and network configs included.
                    val response = AdminMessageParser.parseConfigPublic(sub.first)
                    return FromRadioFrame.ConfigFrame(response, raw = sub.first)
                }
                10 -> { // Channel (canonical field 10) — primary + secondary channels
                    if (wire != 2) { idx = skipField(bytes, idx, wire); continue }
                    val sub = readLengthDelimited(bytes, idx) ?: return null
                    val ch = AdminMessageParser.parseChannelPublic(sub.first)
                    return FromRadioFrame.ChannelFrame(ch, raw = sub.first)
                }
                7 -> { // config_complete_id
                    if (wire != 0) { idx = skipField(bytes, idx, wire); continue }
                    val (v, after) = readVarint(bytes, idx) ?: return null
                    return FromRadioFrame.ConfigComplete(v.toUInt())
                }
                else -> idx = skipField(bytes, idx, wire)
            }
        }
        return FromRadioFrame.Unknown
    }

    /** Parse a Position submessage payload directly — used when we
     *  receive a POSITION_APP packet inside a MeshPacket and want to
     *  fold it into the existing node entry. */
    fun parsePosition(bytes: ByteArray): MeshPosition? {
        var idx = 0
        var lat: Double? = null
        var lon: Double? = null
        var alt: Int? = null
        while (idx < bytes.size) {
            val (tag, afterTag) = readVarint(bytes, idx) ?: return null
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x7UL).toInt()
            idx = afterTag
            when (field) {
                1 -> { // latitude_i (sfixed32)
                    if (wire != 5) { idx = skipField(bytes, idx, wire); continue }
                    val (raw, after) = readFixed32(bytes, idx) ?: return null
                    lat = raw.toInt().toDouble() / 1e7
                    idx = after
                }
                2 -> { // longitude_i (sfixed32)
                    if (wire != 5) { idx = skipField(bytes, idx, wire); continue }
                    val (raw, after) = readFixed32(bytes, idx) ?: return null
                    lon = raw.toInt().toDouble() / 1e7
                    idx = after
                }
                3 -> { // altitude (varint, int32)
                    if (wire != 0) { idx = skipField(bytes, idx, wire); continue }
                    val (v, after) = readVarint(bytes, idx) ?: return null
                    alt = v.toInt()
                    idx = after
                }
                else -> idx = skipField(bytes, idx, wire)
            }
        }
        if (lat == null || lon == null) return null
        if (lat == 0.0 && lon == 0.0) return null
        return MeshPosition(lat = lat, lon = lon, altitudeM = alt)
    }

    // endregion

    // region Submessage parsers -------------------------------------------

    private fun parseMyNodeInfo(bytes: ByteArray): UInt {
        // Only field we need today: 1 my_node_num (varint, uint32).
        var idx = 0
        var nodeNum: UInt = 0u
        while (idx < bytes.size) {
            val (tag, afterTag) = readVarint(bytes, idx) ?: break
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x7UL).toInt()
            idx = afterTag
            when (field) {
                1 -> {
                    if (wire != 0) { idx = skipField(bytes, idx, wire); continue }
                    val (v, after) = readVarint(bytes, idx) ?: break
                    nodeNum = v.toUInt()
                    idx = after
                }
                else -> idx = skipField(bytes, idx, wire)
            }
        }
        return nodeNum
    }

    /**
     * NodeInfo, using the field numbers and wire types from mesh.proto. Fields
     * 1 to 6 are unchanged in every tagged release since v2.0.12; 7 to 11 were
     * added later and never renumbered:
     *
     *    1 num (uint32)           2 user (User)             3 position (Position)
     *    4 snr (float)            5 last_heard (fixed32)    6 device_metrics (DeviceMetrics)
     *    7 channel (uint32)       8 via_mqtt (bool)         9 hops_away (optional uint32)
     *   10 is_favorite (bool)    11 is_ignored (bool)
     *
     * Fields we do not use (7, 8, 10, 11 and anything newer) are skipped by
     * wire type. 1.x firmware used a different layout (snr = 7, last_heard = 4,
     * before 2022-03-20). It is not decoded: field 4 means a different thing
     * there, and this app talks to 2.x firmware.
     *
     * `last_heard` is left null when the field is missing or 0 (the firmware
     * sends 0 for a node it has never heard). It is never defaulted to "now".
     */
    private fun parseNodeInfo(bytes: ByteArray): ParsedNodeInfo? {
        var idx = 0
        var nodeNum: UInt = 0u
        var nodeNumSeen = false
        var shortName = ""
        var longName = ""
        var userRaw: ByteArray? = null
        var role: Int? = null
        var position: MeshPosition? = null
        var snr: Double? = null
        var lastHeard: Long? = null
        var battery: Int? = null
        var hopsAway: Int? = null

        while (idx < bytes.size) {
            val (tag, afterTag) = readVarint(bytes, idx) ?: break
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x7UL).toInt()
            idx = afterTag
            when (field) {
                1 -> { // num (uint32, varint)
                    if (wire != 0) { idx = skipField(bytes, idx, wire); continue }
                    val (v, after) = readVarint(bytes, idx) ?: break
                    nodeNum = v.toUInt(); nodeNumSeen = true; idx = after
                }
                2 -> { // user (User): long_name (2), short_name (3), role (7)
                    if (wire != 2) { idx = skipField(bytes, idx, wire); continue }
                    val sub = readLengthDelimited(bytes, idx) ?: break
                    val user = parseUser(sub.first)
                    if (user.shortName.isNotEmpty()) shortName = user.shortName
                    if (user.longName.isNotEmpty()) longName = user.longName
                    if (user.role != null) role = user.role
                    userRaw = sub.first
                    idx = sub.second
                }
                3 -> { // position (Position)
                    if (wire != 2) { idx = skipField(bytes, idx, wire); continue }
                    val sub = readLengthDelimited(bytes, idx) ?: break
                    position = parsePosition(sub.first) ?: position
                    idx = sub.second
                }
                4 -> { // snr (float, dB of the last packet heard from this node)
                    if (wire != 5) { idx = skipField(bytes, idx, wire); continue }
                    val (raw, after) = readFixed32(bytes, idx) ?: break
                    snr = Float.fromBits(raw.toInt()).toDouble()
                    idx = after
                }
                5 -> { // last_heard (fixed32, epoch seconds)
                    if (wire != 5) { idx = skipField(bytes, idx, wire); continue }
                    val (raw, after) = readFixed32(bytes, idx) ?: break
                    lastHeard = raw.toLong().takeIf { it > 0L }
                    idx = after
                }
                6 -> { // device_metrics (DeviceMetrics): battery_level (1)
                    if (wire != 2) { idx = skipField(bytes, idx, wire); continue }
                    val sub = readLengthDelimited(bytes, idx) ?: break
                    battery = parseDeviceMetricsBattery(sub.first) ?: battery
                    idx = sub.second
                }
                9 -> { // hops_away (optional uint32; 0 = direct neighbour)
                    if (wire != 0) { idx = skipField(bytes, idx, wire); continue }
                    val (v, after) = readVarint(bytes, idx) ?: break
                    hopsAway = v.toInt()
                    idx = after
                }
                else -> idx = skipField(bytes, idx, wire)
            }
        }

        if (!nodeNumSeen) return null
        val id = nodeNum.toLong() and 0xFFFFFFFFL
        val resolvedShort = if (shortName.isNotEmpty()) shortName
            else "%04X".format((id and 0xFFFFL).toInt())
        val resolvedLong = if (longName.isNotEmpty()) longName
            else "Node %08X".format(id.toInt())
        val node = MeshNode(
            id = id,
            shortName = resolvedShort,
            longName = resolvedLong,
            position = position,
            lastHeardEpoch = lastHeard,
            snr = snr,
            hopDistance = hopsAway,
            batteryLevel = battery,
            role = role,
        )
        return ParsedNodeInfo(node, userRaw)
    }

    /** A decoded NodeInfo plus the exact bytes of its `user` field (null when it had none). The radio's own
     *  entry is how the app learns what its owner record holds, see [RadioSettingsCache]. */
    private class ParsedNodeInfo(val node: MeshNode, val userRaw: ByteArray?)

    /** Decoded `User` submessage fields we surface off a NodeInfo. */
    data class ParsedUser(val shortName: String, val longName: String, val role: Int?)

    private fun parseUser(bytes: ByteArray): ParsedUser {
        var idx = 0
        var shortName = ""
        var longName = ""
        var role: Int? = null
        while (idx < bytes.size) {
            val (tag, afterTag) = readVarint(bytes, idx) ?: break
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x7UL).toInt()
            idx = afterTag
            when (field) {
                2 -> { // long_name
                    if (wire != 2) { idx = skipField(bytes, idx, wire); continue }
                    val (s, after) = readString(bytes, idx) ?: break
                    longName = s; idx = after
                }
                3 -> { // short_name
                    if (wire != 2) { idx = skipField(bytes, idx, wire); continue }
                    val (s, after) = readString(bytes, idx) ?: break
                    shortName = s; idx = after
                }
                7 -> { // role (Config.DeviceConfig.Role) — TAK vs TAK_TRACKER
                    if (wire != 0) { idx = skipField(bytes, idx, wire); continue }
                    val (v, after) = readVarint(bytes, idx) ?: break
                    role = v.toInt(); idx = after
                }
                else -> idx = skipField(bytes, idx, wire)
            }
        }
        return ParsedUser(shortName, longName, role)
    }

    private fun parseDeviceMetricsBattery(bytes: ByteArray): Int? {
        var idx = 0
        while (idx < bytes.size) {
            val (tag, afterTag) = readVarint(bytes, idx) ?: break
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x7UL).toInt()
            idx = afterTag
            if (field == 1 && wire == 0) {
                val (v, _) = readVarint(bytes, idx) ?: break
                return v.toInt()
            }
            idx = skipField(bytes, idx, wire)
        }
        return null
    }

    /**
     * MeshPacket, using the field numbers and wire types from mesh.proto
     * (none renumbered since the 2.0 line):
     *
     *    1 from (fixed32)    2 to (fixed32)         3 channel (uint32)
     *    4 decoded (Data)    5 encrypted (bytes)    6 id (fixed32)
     *    7 rx_time (fixed32) 8 rx_snr (float)       9 hop_limit (uint32)
     *   10 want_ack (bool)  11 priority (enum)     12 rx_rssi (int32)
     *   14 via_mqtt (bool)  15 hop_start (uint32)  16 public_key (bytes)
     *
     * `from` and `to` also accept a varint. That is the layout before
     * 2021-02-17 (1.x firmware); it cannot collide with the fixed32 form, so
     * it is left in.
     */
    private fun parseMeshPacket(bytes: ByteArray): MeshPacketDecoded? {
        var idx = 0
        var from: UInt = 0u
        var to: UInt = 0u
        var channel: UInt = 0u
        var portnum: UInt = 0u
        var payload = ByteArray(0)
        var rxTime: Long? = null
        var rxRssi: Int? = null
        var rxSnr: Float? = null
        var hopLimit: Int? = null

        while (idx < bytes.size) {
            val (tag, afterTag) = readVarint(bytes, idx) ?: return null
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x7UL).toInt()
            idx = afterTag
            when (field) {
                1 -> {
                    if (wire == 5) {
                        val (v, after) = readFixed32(bytes, idx) ?: return null
                        from = v; idx = after
                    } else if (wire == 0) {
                        val (v, after) = readVarint(bytes, idx) ?: return null
                        from = v.toUInt(); idx = after
                    } else idx = skipField(bytes, idx, wire)
                }
                2 -> {
                    if (wire == 5) {
                        val (v, after) = readFixed32(bytes, idx) ?: return null
                        to = v; idx = after
                    } else if (wire == 0) {
                        val (v, after) = readVarint(bytes, idx) ?: return null
                        to = v.toUInt(); idx = after
                    } else idx = skipField(bytes, idx, wire)
                }
                3 -> {
                    if (wire != 0) { idx = skipField(bytes, idx, wire); continue }
                    val (v, after) = readVarint(bytes, idx) ?: return null
                    channel = v.toUInt(); idx = after
                }
                4 -> {
                    // decoded (Data submessage): portnum (1, varint), payload (2, length-delimited)
                    if (wire != 2) { idx = skipField(bytes, idx, wire); continue }
                    val sub = readLengthDelimited(bytes, idx) ?: return null
                    val parsed = parseDataSubmessage(sub.first)
                    portnum = parsed.first
                    payload = parsed.second
                    idx = sub.second
                }
                7 -> { // rx_time (fixed32, epoch seconds; 0 or absent when the radio has no clock)
                    if (wire != 5) { idx = skipField(bytes, idx, wire); continue }
                    val (v, after) = readFixed32(bytes, idx) ?: return null
                    rxTime = v.toLong().takeIf { it > 0L }
                    idx = after
                }
                8 -> { // rx_snr (float)
                    if (wire != 5) { idx = skipField(bytes, idx, wire); continue }
                    val (v, after) = readFixed32(bytes, idx) ?: return null
                    rxSnr = Float.fromBits(v.toInt())
                    idx = after
                }
                9 -> { // hop_limit (uint32)
                    if (wire != 0) { idx = skipField(bytes, idx, wire); continue }
                    val (v, after) = readVarint(bytes, idx) ?: return null
                    hopLimit = v.toInt(); idx = after
                }
                12 -> { // rx_rssi (int32: a negative value is a 10-byte sign-extended varint)
                    if (wire != 0) { idx = skipField(bytes, idx, wire); continue }
                    val (v, after) = readVarint(bytes, idx) ?: return null
                    rxRssi = v.toInt(); idx = after
                }
                // Skipped on purpose: 5 encrypted, 10 want_ack, 11 priority,
                // 14 via_mqtt, 15 hop_start, 16 public_key (bytes, not an RSSI).
                else -> idx = skipField(bytes, idx, wire)
            }
        }

        return MeshPacketDecoded(
            from = from, to = to, channel = channel,
            portnum = portnum, payload = payload,
            rxTime = rxTime, rxRssi = rxRssi,
            rxSnr = rxSnr, hopLimit = hopLimit,
        )
    }

    private fun parseDataSubmessage(bytes: ByteArray): Pair<UInt, ByteArray> {
        var idx = 0
        var portnum: UInt = 0u
        var payload = ByteArray(0)
        while (idx < bytes.size) {
            val (tag, afterTag) = readVarint(bytes, idx) ?: break
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x7UL).toInt()
            idx = afterTag
            when (field) {
                1 -> {
                    if (wire != 0) { idx = skipField(bytes, idx, wire); continue }
                    val (v, after) = readVarint(bytes, idx) ?: break
                    portnum = v.toUInt(); idx = after
                }
                2 -> {
                    if (wire != 2) { idx = skipField(bytes, idx, wire); continue }
                    val sub = readLengthDelimited(bytes, idx) ?: break
                    payload = sub.first; idx = sub.second
                }
                else -> idx = skipField(bytes, idx, wire)
            }
        }
        return portnum to payload
    }

    // endregion

    // region Wire helpers --------------------------------------------------

    /** Read a base-128 varint. Returns (value, newOffset) or null on
     *  truncation / >10-byte runaway. */
    fun readVarint(buf: ByteArray, offset: Int): Pair<ULong, Int>? {
        var result: ULong = 0u
        var shift = 0
        var idx = offset
        while (idx < buf.size) {
            val b = buf[idx].toInt() and 0xFF
            idx += 1
            result = result or ((b and 0x7F).toULong() shl shift)
            if (b and 0x80 == 0) return result to idx
            shift += 7
            if (shift >= 64) return null
        }
        return null
    }

    /** Read 4-byte little-endian as UInt. */
    fun readFixed32(buf: ByteArray, offset: Int): Pair<UInt, Int>? {
        if (offset + 4 > buf.size) return null
        val v = (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)
        return v.toUInt() to (offset + 4)
    }

    /** Read 8-byte little-endian as ULong. */
    fun readFixed64(buf: ByteArray, offset: Int): Pair<ULong, Int>? {
        if (offset + 8 > buf.size) return null
        var v: ULong = 0u
        for (i in 0 until 8) {
            v = v or ((buf[offset + i].toLong() and 0xFFL).toULong() shl (i * 8))
        }
        return v to (offset + 8)
    }

    /** Read a length-delimited region. Returns (bytes, newOffset). */
    fun readLengthDelimited(buf: ByteArray, offset: Int): Pair<ByteArray, Int>? {
        val (len, lenEnd) = readVarint(buf, offset) ?: return null
        val end = lenEnd + len.toInt()
        if (end > buf.size || end < lenEnd) return null
        return buf.copyOfRange(lenEnd, end) to end
    }

    /** Read a length-delimited UTF-8 string. */
    fun readString(buf: ByteArray, offset: Int): Pair<String, Int>? {
        val (bytes, end) = readLengthDelimited(buf, offset) ?: return null
        return runCatching { String(bytes, Charsets.UTF_8) to end }.getOrNull()
    }

    /** Skip an unknown field given its wire type. Always advances at
     *  least one byte to ensure forward progress. */
    fun skipField(buf: ByteArray, offset: Int, wireType: Int): Int {
        if (offset >= buf.size) return buf.size
        return when (wireType) {
            0 -> readVarint(buf, offset)?.second ?: (offset + 1)
            1 -> minOf(offset + 8, buf.size)
            2 -> {
                val (len, lenEnd) = readVarint(buf, offset) ?: return offset + 1
                minOf(lenEnd + len.toInt(), buf.size)
            }
            5 -> minOf(offset + 4, buf.size)
            else -> offset + 1
        }
    }

    // endregion
}

/** Top-level FromRadio variant we recognised. */
sealed interface FromRadioFrame {
    data class Packet(val packet: MeshPacketDecoded) : FromRadioFrame
    data class MyInfo(val nodeNum: UInt) : FromRadioFrame

    /** [userRaw] is the exact bytes of the NodeInfo's `user` field, null when it had none. */
    class NodeInfoFrame(val node: MeshNode, val userRaw: ByteArray? = null) : FromRadioFrame {
        override fun equals(other: Any?): Boolean =
            other is NodeInfoFrame && node == other.node &&
                (userRaw?.contentEquals(other.userRaw) ?: (other.userRaw == null))

        override fun hashCode(): Int = 31 * node.hashCode() + (userRaw?.contentHashCode() ?: 0)
        override fun toString(): String = "NodeInfoFrame(node=$node, userRaw=${userRaw?.size ?: 0}B)"
    }

    /** GAP-109: Config submessage at FromRadio.field=5.
     *
     *  [response] is the decoded value (the same AdminResponse types an admin
     *  response produces, so the downstream sink treats radio-pushed and
     *  requested config alike). It is null for a variant the settings screen
     *  does not show. [raw] is the Config message as the radio sent it, for
     *  every variant; [RadioSettingsCache] keeps only the variants the app patches. */
    class ConfigFrame(val response: AdminResponse?, val raw: ByteArray) : FromRadioFrame {
        override fun equals(other: Any?): Boolean =
            other is ConfigFrame && response == other.response && raw.contentEquals(other.raw)

        override fun hashCode(): Int = 31 * (response?.hashCode() ?: 0) + raw.contentHashCode()
        override fun toString(): String = "ConfigFrame(response=$response, raw=${raw.size}B)"
    }

    /** GAP-109: Channel submessage at FromRadio.field=10. [raw] is the Channel as the radio sent it. */
    class ChannelFrame(val response: AdminResponse.Channel, val raw: ByteArray) : FromRadioFrame {
        override fun equals(other: Any?): Boolean =
            other is ChannelFrame && response == other.response && raw.contentEquals(other.raw)

        override fun hashCode(): Int = 31 * response.hashCode() + raw.contentHashCode()
        override fun toString(): String = "ChannelFrame(response=$response, raw=${raw.size}B)"
    }

    data class ConfigComplete(val id: UInt) : FromRadioFrame
    data object Unknown : FromRadioFrame
}

/** Decoded MeshPacket — Phase 4 will fan out on `portnum`. */
data class MeshPacketDecoded(
    val from: UInt,
    val to: UInt,
    val channel: UInt,
    val portnum: UInt,
    val payload: ByteArray,
    val rxTime: Long? = null,
    val rxRssi: Int? = null,
    val rxSnr: Float? = null,
    val hopLimit: Int? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MeshPacketDecoded) return false
        return from == other.from && to == other.to && channel == other.channel &&
            portnum == other.portnum && payload.contentEquals(other.payload) &&
            rxTime == other.rxTime && rxRssi == other.rxRssi &&
            rxSnr == other.rxSnr && hopLimit == other.hopLimit
    }

    override fun hashCode(): Int {
        var r = from.hashCode()
        r = 31 * r + to.hashCode()
        r = 31 * r + channel.hashCode()
        r = 31 * r + portnum.hashCode()
        r = 31 * r + payload.contentHashCode()
        r = 31 * r + (rxTime?.hashCode() ?: 0)
        r = 31 * r + (rxRssi ?: 0)
        r = 31 * r + (rxSnr?.hashCode() ?: 0)
        r = 31 * r + (hopLimit ?: 0)
        return r
    }
}
