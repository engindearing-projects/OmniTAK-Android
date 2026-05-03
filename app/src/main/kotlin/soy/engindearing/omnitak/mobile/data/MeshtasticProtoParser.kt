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
                    val node = parseNodeInfo(sub.first) ?: return FromRadioFrame.Unknown
                    return FromRadioFrame.NodeInfoFrame(node)
                }
                5 -> { // Config (canonical field 5) — DeviceConfig / PositionConfig / LoRaConfig
                    if (wire != 2) { idx = skipField(bytes, idx, wire); continue }
                    val sub = readLengthDelimited(bytes, idx) ?: return null
                    val response = AdminMessageParser.parseConfigPublic(sub.first)
                        ?: return FromRadioFrame.Unknown
                    return FromRadioFrame.ConfigFrame(response)
                }
                10 -> { // Channel (canonical field 10) — primary + secondary channels
                    if (wire != 2) { idx = skipField(bytes, idx, wire); continue }
                    val sub = readLengthDelimited(bytes, idx) ?: return null
                    val ch = AdminMessageParser.parseChannelPublic(sub.first)
                    return FromRadioFrame.ChannelFrame(ch)
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

    private fun parseNodeInfo(bytes: ByteArray): MeshNode? {
        var idx = 0
        var nodeNum: UInt = 0u
        var nodeNumSeen = false
        var shortName = ""
        var longName = ""
        var position: MeshPosition? = null
        var snr: Double? = null
        var lastHeard: Long = System.currentTimeMillis() / 1000
        var battery: Int? = null
        var hopsAway: Int? = null

        while (idx < bytes.size) {
            val (tag, afterTag) = readVarint(bytes, idx) ?: break
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x7UL).toInt()
            idx = afterTag
            when (field) {
                1 -> {
                    // num — proto says fixed32 in newer revs, but iOS handles
                    // it as varint. Accept either to stay compatible.
                    when (wire) {
                        0 -> {
                            val (v, after) = readVarint(bytes, idx) ?: break
                            nodeNum = v.toUInt(); nodeNumSeen = true; idx = after
                        }
                        5 -> {
                            val (v, after) = readFixed32(bytes, idx) ?: break
                            nodeNum = v; nodeNumSeen = true; idx = after
                        }
                        else -> idx = skipField(bytes, idx, wire)
                    }
                }
                4 -> {
                    // user submessage — long_name (2), short_name (3) are
                    // the only fields we surface. iOS uses field 2 = user
                    // historically; the modern proto puts user at field 4.
                    // Accept either.
                    if (wire != 2) { idx = skipField(bytes, idx, wire); continue }
                    val sub = readLengthDelimited(bytes, idx) ?: break
                    val (sn, ln) = parseUser(sub.first)
                    if (sn.isNotEmpty()) shortName = sn
                    if (ln.isNotEmpty()) longName = ln
                    idx = sub.second
                }
                2 -> {
                    // Older field number for user submessage; same
                    // payload shape.
                    if (wire != 2) { idx = skipField(bytes, idx, wire); continue }
                    val sub = readLengthDelimited(bytes, idx) ?: break
                    val (sn, ln) = parseUser(sub.first)
                    if (sn.isNotEmpty()) shortName = sn
                    if (ln.isNotEmpty()) longName = ln
                    idx = sub.second
                }
                5 -> {
                    // Position submessage (length-delimited) OR snr (float).
                    // Disambiguate by wire type.
                    when (wire) {
                        2 -> {
                            val sub = readLengthDelimited(bytes, idx) ?: break
                            position = parsePosition(sub.first) ?: position
                            idx = sub.second
                        }
                        5 -> {
                            val (raw, after) = readFixed32(bytes, idx) ?: break
                            snr = Float.fromBits(raw.toInt()).toDouble()
                            idx = after
                        }
                        else -> idx = skipField(bytes, idx, wire)
                    }
                }
                7 -> {
                    if (wire == 5) {
                        // snr (float)
                        val (raw, after) = readFixed32(bytes, idx) ?: break
                        snr = Float.fromBits(raw.toInt()).toDouble()
                        idx = after
                    } else {
                        idx = skipField(bytes, idx, wire)
                    }
                }
                9 -> {
                    if (wire == 5) {
                        val (raw, after) = readFixed32(bytes, idx) ?: break
                        lastHeard = raw.toLong() and 0xFFFFFFFFL
                        idx = after
                    } else if (wire == 0) {
                        val (v, after) = readVarint(bytes, idx) ?: break
                        lastHeard = v.toLong()
                        idx = after
                    } else {
                        idx = skipField(bytes, idx, wire)
                    }
                }
                10 -> {
                    if (wire != 2) { idx = skipField(bytes, idx, wire); continue }
                    val sub = readLengthDelimited(bytes, idx) ?: break
                    battery = parseDeviceMetricsBattery(sub.first)
                    idx = sub.second
                }
                11 -> {
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
        return MeshNode(
            id = id,
            shortName = resolvedShort,
            longName = resolvedLong,
            position = position,
            lastHeardEpoch = lastHeard,
            snr = snr,
            hopDistance = hopsAway,
            batteryLevel = battery,
        )
    }

    private fun parseUser(bytes: ByteArray): Pair<String, String> {
        var idx = 0
        var shortName = ""
        var longName = ""
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
                else -> idx = skipField(bytes, idx, wire)
            }
        }
        return shortName to longName
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
                8 -> {
                    if (wire != 5) { idx = skipField(bytes, idx, wire); continue }
                    val (v, after) = readFixed32(bytes, idx) ?: return null
                    rxTime = v.toLong() and 0xFFFFFFFFL
                    idx = after
                }
                9 -> {
                    if (wire != 5) { idx = skipField(bytes, idx, wire); continue }
                    val (v, after) = readFixed32(bytes, idx) ?: return null
                    rxSnr = Float.fromBits(v.toInt())
                    idx = after
                }
                10 -> {
                    if (wire != 0) { idx = skipField(bytes, idx, wire); continue }
                    val (v, after) = readVarint(bytes, idx) ?: return null
                    hopLimit = v.toInt(); idx = after
                }
                12 -> {
                    if (wire != 0) { idx = skipField(bytes, idx, wire); continue }
                    val (v, after) = readVarint(bytes, idx) ?: return null
                    rxRssi = v.toInt(); idx = after
                }
                16 -> {
                    if (wire != 0) { idx = skipField(bytes, idx, wire); continue }
                    val (v, after) = readVarint(bytes, idx) ?: return null
                    // sint32 zigzag decode
                    val zz = v.toLong()
                    rxRssi = ((zz ushr 1) xor -(zz and 1L)).toInt()
                    idx = after
                }
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
    data class NodeInfoFrame(val node: MeshNode) : FromRadioFrame
    /** GAP-109 — Config submessage at FromRadio.field=5. Wraps the same
     *  AdminResponse types so the downstream sink can treat radio-pushed
     *  config and admin-response config identically. */
    data class ConfigFrame(val response: AdminResponse) : FromRadioFrame
    /** GAP-109 — Channel submessage at FromRadio.field=10. */
    data class ChannelFrame(val response: AdminResponse.Channel) : FromRadioFrame
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
