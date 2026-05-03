package soy.engindearing.omnitak.mobile.data

/**
 * Parses Meshtastic portnum-72 (ATAK_PLUGIN) payloads into [CoTEvent]
 * objects. Mirrors `ATAKPluginParser.swift` on iOS — same wire format,
 * same fallback ladder.
 *
 * The portnum-72 payload is normally a TAK protobuf `TAKMessage`
 * containing a `CoTEvent` submessage (the schema documented in
 * atak-civ / mesh-protobufs). Older clients sometimes wrap raw CoT XML
 * directly in the payload — we attempt the protobuf path first and
 * fall back to XML when the bytes look like a CoT XML document.
 *
 * The encoder lives in [AtakPluginSerializer]. Both hand-roll the
 * protobuf wire format using [MeshtasticProtoParser]'s primitives — no
 * `protobuf-javalite` dependency.
 *
 * TAK protobuf schema (subset used here):
 * ```
 * message TAKMessage {
 *     TakControl takControl = 1;
 *     CoTEvent   cotEvent   = 2;
 * }
 * message CoTEvent {
 *     string type      = 1;
 *     string access    = 2;
 *     string qos       = 3;
 *     string opex      = 4;
 *     string uid       = 5;
 *     uint64 sendTime  = 6;
 *     uint64 startTime = 7;
 *     uint64 staleTime = 8;
 *     string how       = 9;
 *     double lat       = 10;
 *     double lon       = 11;
 *     double hae       = 12;
 *     double ce        = 13;
 *     double le        = 14;
 *     Detail detail    = 15;
 * }
 * message Detail {
 *     string xmlDetail               = 1;
 *     Group group                    = 2;
 *     PrecisionLocation precision    = 3;
 *     Status status                  = 4;
 *     Takv takv                      = 5;
 *     Contact contact                = 6;
 *     Track track                    = 7;
 * }
 * ```
 */
object AtakPluginParser {

    // region Public entry -------------------------------------------------

    /** Parse a portnum-72 payload. Returns the populated [CoTEvent], or
     *  null when the bytes are neither a recognisable TAKMessage
     *  protobuf nor a CoT XML document. */
    fun parse(payload: ByteArray): CoTEvent? {
        if (payload.isEmpty()) return null

        // XML fast-path — older ATAK plugin clients shove raw CoT XML
        // directly into the portnum-72 payload, no protobuf wrapper.
        if (looksLikeXML(payload)) {
            val xml = runCatching { String(payload, Charsets.UTF_8) }.getOrNull()
                ?: return null
            // Try the production XmlPullParser-backed CoTParser first
            // (full-featured on Android); fall back to a string-based
            // extractor on plain JVM (unit tests, where android.util.Xml
            // isn't available).
            return tryAndroidCoTParser(xml) ?: parseCoTXmlFallback(xml)
        }

        // Try TAKMessage protobuf path. If that fails, try a bare CoTEvent
        // (some senders skip the outer envelope).
        return parseTakMessage(payload) ?: parseCoTEvent(payload)
    }

    // endregion

    // region TAKMessage protobuf -----------------------------------------

    private fun parseTakMessage(bytes: ByteArray): CoTEvent? {
        var idx = 0
        var cotEventBytes: ByteArray? = null
        while (idx < bytes.size) {
            val (tag, afterTag) = MeshtasticProtoParser.readVarint(bytes, idx) ?: return null
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x7UL).toInt()
            idx = afterTag
            when {
                field == 1 && wire == 2 -> {
                    // takControl — skip
                    val sub = MeshtasticProtoParser.readLengthDelimited(bytes, idx) ?: return null
                    idx = sub.second
                }
                field == 2 && wire == 2 -> {
                    val sub = MeshtasticProtoParser.readLengthDelimited(bytes, idx) ?: return null
                    cotEventBytes = sub.first
                    idx = sub.second
                }
                else -> idx = MeshtasticProtoParser.skipField(bytes, idx, wire)
            }
        }
        return cotEventBytes?.let { parseCoTEvent(it) }
    }

    private fun parseCoTEvent(bytes: ByteArray): CoTEvent? {
        if (bytes.isEmpty()) return null
        var idx = 0
        var typeStr: String? = null
        var uid: String? = null
        var how: String? = null
        var sendTime: ULong = 0u
        var startTime: ULong = 0u
        var staleTime: ULong = 0u
        var lat = 0.0
        var lon = 0.0
        var hae = 0.0
        var ce = 9_999_999.0
        var le = 9_999_999.0
        var detailBytes: ByteArray? = null
        var sawAnyKnownField = false

        while (idx < bytes.size) {
            val (tag, afterTag) = MeshtasticProtoParser.readVarint(bytes, idx) ?: return null
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x7UL).toInt()
            idx = afterTag
            when (field to wire) {
                1 to 2 -> {
                    val (s, after) = MeshtasticProtoParser.readString(bytes, idx) ?: return null
                    typeStr = s
                    sawAnyKnownField = true
                    idx = after
                }
                2 to 2, 3 to 2, 4 to 2, 9 to 2 -> {
                    val (s, after) = MeshtasticProtoParser.readString(bytes, idx) ?: return null
                    if (field == 9) how = s
                    sawAnyKnownField = true
                    idx = after
                }
                5 to 2 -> {
                    val (s, after) = MeshtasticProtoParser.readString(bytes, idx) ?: return null
                    uid = s
                    sawAnyKnownField = true
                    idx = after
                }
                6 to 0 -> {
                    val (v, after) = MeshtasticProtoParser.readVarint(bytes, idx) ?: return null
                    sendTime = v; sawAnyKnownField = true; idx = after
                }
                7 to 0 -> {
                    val (v, after) = MeshtasticProtoParser.readVarint(bytes, idx) ?: return null
                    startTime = v; sawAnyKnownField = true; idx = after
                }
                8 to 0 -> {
                    val (v, after) = MeshtasticProtoParser.readVarint(bytes, idx) ?: return null
                    staleTime = v; sawAnyKnownField = true; idx = after
                }
                10 to 1 -> {
                    val (v, after) = MeshtasticProtoParser.readFixed64(bytes, idx) ?: return null
                    lat = Double.fromBits(v.toLong()); sawAnyKnownField = true; idx = after
                }
                11 to 1 -> {
                    val (v, after) = MeshtasticProtoParser.readFixed64(bytes, idx) ?: return null
                    lon = Double.fromBits(v.toLong()); sawAnyKnownField = true; idx = after
                }
                12 to 1 -> {
                    val (v, after) = MeshtasticProtoParser.readFixed64(bytes, idx) ?: return null
                    hae = Double.fromBits(v.toLong()); sawAnyKnownField = true; idx = after
                }
                13 to 1 -> {
                    val (v, after) = MeshtasticProtoParser.readFixed64(bytes, idx) ?: return null
                    ce = Double.fromBits(v.toLong()); sawAnyKnownField = true; idx = after
                }
                14 to 1 -> {
                    val (v, after) = MeshtasticProtoParser.readFixed64(bytes, idx) ?: return null
                    le = Double.fromBits(v.toLong()); sawAnyKnownField = true; idx = after
                }
                15 to 2 -> {
                    val sub = MeshtasticProtoParser.readLengthDelimited(bytes, idx) ?: return null
                    detailBytes = sub.first
                    sawAnyKnownField = true
                    idx = sub.second
                }
                else -> idx = MeshtasticProtoParser.skipField(bytes, idx, wire)
            }
        }

        if (!sawAnyKnownField) return null
        val resolvedUid = uid ?: return null
        val resolvedType = typeStr ?: return null

        val detail = detailBytes?.let { parseDetail(it) } ?: ParsedDetail()

        // Reconstruct a CoT XML detail block so callers (ContactStore etc)
        // that round-trip on rawXml don't lose group/contact/takv/etc.
        val detailXml = renderDetailXml(detail)
        val sendDate = sendTime.takeIf { it != 0uL }
        val staleDate = staleTime.takeIf { it != 0uL } ?: sendDate?.let { it + 60_000uL }
        val timeIso = sendDate?.let { isoFromMillis(it) }
        val staleIso = staleDate?.let { isoFromMillis(it) }
        val callsign = detail.callsign ?: detail.groupName ?: resolvedUid
        val cotXml = renderCoTXml(
            uid = resolvedUid,
            type = resolvedType,
            timeIso = timeIso,
            staleIso = staleIso,
            how = how ?: "m-g",
            lat = lat, lon = lon, hae = hae, ce = ce, le = le,
            detailXml = detailXml,
        )

        return CoTEvent(
            uid = resolvedUid,
            type = resolvedType,
            lat = lat,
            lon = lon,
            hae = hae,
            ce = ce,
            le = le,
            timeIso = timeIso,
            staleIso = staleIso,
            callsign = callsign,
            remarks = detail.remarks ?: "",
            // Preserve the protobuf-derived CoT XML so existing CoT
            // pipelines (ContactStore, marker rendering) can mine the
            // structured Detail fields we don't carry on CoTEvent today.
            // TODO: widen CoTEvent.detail to carry group/takv/track.
            rawXml = cotXml,
        )
    }

    // endregion

    // region Detail submessage -------------------------------------------

    private data class ParsedDetail(
        var xmlDetail: String? = null,
        var groupName: String? = null,
        var groupRole: String? = null,
        var precisionGeo: String? = null,
        var precisionAlt: String? = null,
        var battery: Int? = null,
        var device: String? = null,
        var platform: String? = null,
        var os: String? = null,
        var version: String? = null,
        var callsign: String? = null,
        var endpoint: String? = null,
        var speed: Double? = null,
        var course: Double? = null,
        var remarks: String? = null,
    )

    private fun parseDetail(bytes: ByteArray): ParsedDetail {
        val d = ParsedDetail()
        var idx = 0
        while (idx < bytes.size) {
            val (tag, afterTag) = MeshtasticProtoParser.readVarint(bytes, idx) ?: break
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x7UL).toInt()
            idx = afterTag
            when (field to wire) {
                1 to 2 -> {
                    val (s, after) = MeshtasticProtoParser.readString(bytes, idx) ?: break
                    d.xmlDetail = s; idx = after
                }
                2 to 2 -> {
                    val sub = MeshtasticProtoParser.readLengthDelimited(bytes, idx) ?: break
                    parseGroup(sub.first, d); idx = sub.second
                }
                3 to 2 -> {
                    val sub = MeshtasticProtoParser.readLengthDelimited(bytes, idx) ?: break
                    parsePrecisionLocation(sub.first, d); idx = sub.second
                }
                4 to 2 -> {
                    val sub = MeshtasticProtoParser.readLengthDelimited(bytes, idx) ?: break
                    parseStatus(sub.first, d); idx = sub.second
                }
                5 to 2 -> {
                    val sub = MeshtasticProtoParser.readLengthDelimited(bytes, idx) ?: break
                    parseTakv(sub.first, d); idx = sub.second
                }
                6 to 2 -> {
                    val sub = MeshtasticProtoParser.readLengthDelimited(bytes, idx) ?: break
                    parseContact(sub.first, d); idx = sub.second
                }
                7 to 2 -> {
                    val sub = MeshtasticProtoParser.readLengthDelimited(bytes, idx) ?: break
                    parseTrack(sub.first, d); idx = sub.second
                }
                else -> idx = MeshtasticProtoParser.skipField(bytes, idx, wire)
            }
        }
        // Pull remarks out of xmlDetail if present so consumers that only
        // look at CoTEvent.remarks still see them.
        d.xmlDetail?.let { xml ->
            extractInnerText("remarks", xml)?.let { d.remarks = it }
        }
        return d
    }

    private fun parseGroup(bytes: ByteArray, d: ParsedDetail) {
        var idx = 0
        while (idx < bytes.size) {
            val (tag, afterTag) = MeshtasticProtoParser.readVarint(bytes, idx) ?: return
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x7UL).toInt()
            idx = afterTag
            when (field to wire) {
                1 to 2 -> {
                    val (s, after) = MeshtasticProtoParser.readString(bytes, idx) ?: return
                    d.groupName = s; idx = after
                }
                2 to 2 -> {
                    val (s, after) = MeshtasticProtoParser.readString(bytes, idx) ?: return
                    d.groupRole = s; idx = after
                }
                else -> idx = MeshtasticProtoParser.skipField(bytes, idx, wire)
            }
        }
    }

    private fun parsePrecisionLocation(bytes: ByteArray, d: ParsedDetail) {
        var idx = 0
        while (idx < bytes.size) {
            val (tag, afterTag) = MeshtasticProtoParser.readVarint(bytes, idx) ?: return
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x7UL).toInt()
            idx = afterTag
            when (field to wire) {
                1 to 2 -> {
                    val (s, after) = MeshtasticProtoParser.readString(bytes, idx) ?: return
                    d.precisionGeo = s; idx = after
                }
                2 to 2 -> {
                    val (s, after) = MeshtasticProtoParser.readString(bytes, idx) ?: return
                    d.precisionAlt = s; idx = after
                }
                else -> idx = MeshtasticProtoParser.skipField(bytes, idx, wire)
            }
        }
    }

    private fun parseStatus(bytes: ByteArray, d: ParsedDetail) {
        var idx = 0
        while (idx < bytes.size) {
            val (tag, afterTag) = MeshtasticProtoParser.readVarint(bytes, idx) ?: return
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x7UL).toInt()
            idx = afterTag
            if (field == 1 && wire == 0) {
                val (v, after) = MeshtasticProtoParser.readVarint(bytes, idx) ?: return
                d.battery = v.toInt(); idx = after
            } else {
                idx = MeshtasticProtoParser.skipField(bytes, idx, wire)
            }
        }
    }

    private fun parseTakv(bytes: ByteArray, d: ParsedDetail) {
        var idx = 0
        while (idx < bytes.size) {
            val (tag, afterTag) = MeshtasticProtoParser.readVarint(bytes, idx) ?: return
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x7UL).toInt()
            idx = afterTag
            when (field to wire) {
                1 to 2 -> {
                    val (s, after) = MeshtasticProtoParser.readString(bytes, idx) ?: return
                    d.device = s; idx = after
                }
                2 to 2 -> {
                    val (s, after) = MeshtasticProtoParser.readString(bytes, idx) ?: return
                    d.platform = s; idx = after
                }
                3 to 2 -> {
                    val (s, after) = MeshtasticProtoParser.readString(bytes, idx) ?: return
                    d.os = s; idx = after
                }
                4 to 2 -> {
                    val (s, after) = MeshtasticProtoParser.readString(bytes, idx) ?: return
                    d.version = s; idx = after
                }
                else -> idx = MeshtasticProtoParser.skipField(bytes, idx, wire)
            }
        }
    }

    private fun parseContact(bytes: ByteArray, d: ParsedDetail) {
        var idx = 0
        while (idx < bytes.size) {
            val (tag, afterTag) = MeshtasticProtoParser.readVarint(bytes, idx) ?: return
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x7UL).toInt()
            idx = afterTag
            when (field to wire) {
                1 to 2 -> {
                    val (s, after) = MeshtasticProtoParser.readString(bytes, idx) ?: return
                    d.callsign = s; idx = after
                }
                2 to 2 -> {
                    val (s, after) = MeshtasticProtoParser.readString(bytes, idx) ?: return
                    d.endpoint = s; idx = after
                }
                else -> idx = MeshtasticProtoParser.skipField(bytes, idx, wire)
            }
        }
    }

    private fun parseTrack(bytes: ByteArray, d: ParsedDetail) {
        var idx = 0
        while (idx < bytes.size) {
            val (tag, afterTag) = MeshtasticProtoParser.readVarint(bytes, idx) ?: return
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x7UL).toInt()
            idx = afterTag
            when (field to wire) {
                1 to 1 -> {
                    val (v, after) = MeshtasticProtoParser.readFixed64(bytes, idx) ?: return
                    d.speed = Double.fromBits(v.toLong()); idx = after
                }
                2 to 1 -> {
                    val (v, after) = MeshtasticProtoParser.readFixed64(bytes, idx) ?: return
                    d.course = Double.fromBits(v.toLong()); idx = after
                }
                else -> idx = MeshtasticProtoParser.skipField(bytes, idx, wire)
            }
        }
    }

    // endregion

    // region XML rendering / fallback ------------------------------------

    private fun renderDetailXml(d: ParsedDetail): String {
        // Prefer the verbatim xmlDetail when the sender included one — it
        // may already wrap <detail>...</detail> tags.
        d.xmlDetail?.takeIf { it.isNotBlank() }?.let { xml ->
            val trimmed = xml.trim()
            return if (trimmed.startsWith("<detail")) trimmed else "<detail>$trimmed</detail>"
        }

        val inner = StringBuilder()
        d.callsign?.let { cs ->
            val endpoint = d.endpoint?.let { " endpoint=\"${escape(it)}\"" } ?: ""
            inner.append("<contact callsign=\"${escape(cs)}\"$endpoint/>")
        }
        d.groupName?.let { gn ->
            val role = d.groupRole?.let { " role=\"${escape(it)}\"" } ?: ""
            inner.append("<__group name=\"${escape(gn)}\"$role/>")
        }
        if (d.precisionGeo != null || d.precisionAlt != null) {
            val geo = d.precisionGeo?.let { " geopointsrc=\"${escape(it)}\"" } ?: ""
            val alt = d.precisionAlt?.let { " altsrc=\"${escape(it)}\"" } ?: ""
            inner.append("<precisionlocation$geo$alt/>")
        }
        d.battery?.let { inner.append("<status battery=\"$it\"/>") }
        if (d.device != null || d.platform != null || d.os != null || d.version != null) {
            val sb = StringBuilder()
            d.device?.let { sb.append(" device=\"${escape(it)}\"") }
            d.platform?.let { sb.append(" platform=\"${escape(it)}\"") }
            d.os?.let { sb.append(" os=\"${escape(it)}\"") }
            d.version?.let { sb.append(" version=\"${escape(it)}\"") }
            inner.append("<takv$sb/>")
        }
        if (d.speed != null || d.course != null) {
            val sb = StringBuilder()
            d.speed?.let { sb.append(" speed=\"$it\"") }
            d.course?.let { sb.append(" course=\"$it\"") }
            inner.append("<track$sb/>")
        }
        d.remarks?.let { inner.append("<remarks>${escape(it)}</remarks>") }
        return "<detail>$inner</detail>"
    }

    private fun renderCoTXml(
        uid: String,
        type: String,
        timeIso: String?,
        staleIso: String?,
        how: String,
        lat: Double, lon: Double, hae: Double, ce: Double, le: Double,
        detailXml: String,
    ): String {
        val t = timeIso ?: ""
        val s = staleIso ?: t
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append("<event version=\"2.0\" uid=\"${escape(uid)}\" type=\"${escape(type)}\"")
            if (t.isNotEmpty()) append(" time=\"$t\" start=\"$t\"")
            if (s.isNotEmpty()) append(" stale=\"$s\"")
            append(" how=\"${escape(how)}\">")
            append("<point lat=\"$lat\" lon=\"$lon\" hae=\"$hae\" ce=\"$ce\" le=\"$le\"/>")
            append(detailXml)
            append("</event>")
        }
    }

    /** Bare-bones XML fallback that does not depend on `android.util.Xml`
     *  so unit tests can exercise it. Pulls the same fields the real
     *  [CoTParser] does. */
    internal fun parseCoTXmlFallback(xml: String): CoTEvent? {
        val uid = extractAttr(xml, "event", "uid") ?: return null
        val type = extractAttr(xml, "event", "type") ?: return null
        val time = extractAttr(xml, "event", "time")
        val stale = extractAttr(xml, "event", "stale")
        val lat = extractAttr(xml, "point", "lat")?.toDoubleOrNull() ?: return null
        val lon = extractAttr(xml, "point", "lon")?.toDoubleOrNull() ?: return null
        val hae = extractAttr(xml, "point", "hae")?.toDoubleOrNull() ?: 0.0
        val ce = extractAttr(xml, "point", "ce")?.toDoubleOrNull() ?: 9_999_999.0
        val le = extractAttr(xml, "point", "le")?.toDoubleOrNull() ?: 9_999_999.0
        val callsign = extractAttr(xml, "contact", "callsign")
        return CoTEvent(
            uid = uid,
            type = type,
            lat = lat,
            lon = lon,
            hae = hae,
            ce = ce,
            le = le,
            timeIso = time,
            staleIso = stale,
            callsign = callsign,
            rawXml = xml,
        )
    }

    /** Try the production CoTParser (Android-only). Wrapped in
     *  reflection-via-runCatching so unit tests on a plain JVM don't
     *  blow up linking against `android.util.Xml`. */
    private fun tryAndroidCoTParser(xml: String): CoTEvent? = runCatching {
        CoTParser.parse(xml)
    }.getOrNull()

    private fun looksLikeXML(bytes: ByteArray): Boolean {
        var i = 0
        // Skip optional UTF-8 BOM and leading whitespace.
        if (bytes.size >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xEF &&
            (bytes[1].toInt() and 0xFF) == 0xBB &&
            (bytes[2].toInt() and 0xFF) == 0xBF
        ) i = 3
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0x20 || b == 0x09 || b == 0x0A || b == 0x0D) { i += 1; continue }
            break
        }
        if (i >= bytes.size) return false
        val xmlMarker = byteArrayOf(0x3C, 0x3F, 0x78, 0x6D, 0x6C) // <?xml
        val eventMarker = byteArrayOf(0x3C, 0x65, 0x76, 0x65, 0x6E, 0x74) // <event
        if (i + xmlMarker.size <= bytes.size && bytes.regionMatches(i, xmlMarker)) return true
        if (i + eventMarker.size <= bytes.size && bytes.regionMatches(i, eventMarker)) return true
        return false
    }

    private fun ByteArray.regionMatches(offset: Int, other: ByteArray): Boolean {
        if (offset + other.size > size) return false
        for (k in other.indices) {
            if (this[offset + k] != other[k]) return false
        }
        return true
    }

    // endregion

    // region Misc helpers -------------------------------------------------

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun extractAttr(xml: String, tag: String, attr: String): String? {
        val openIdx = xml.indexOf("<$tag")
        if (openIdx < 0) return null
        val closeIdx = xml.indexOf('>', openIdx)
        if (closeIdx < 0) return null
        val region = xml.substring(openIdx, closeIdx)
        val key = "$attr=\""
        val k = region.indexOf(key)
        if (k < 0) return null
        val start = k + key.length
        val end = region.indexOf('"', start)
        if (end < 0) return null
        return unescape(region.substring(start, end))
    }

    private fun extractInnerText(tag: String, xml: String): String? {
        val open = "<$tag>"
        val close = "</$tag>"
        val o = xml.indexOf(open)
        if (o < 0) return null
        val c = xml.indexOf(close, o + open.length)
        if (c < 0) return null
        return unescape(xml.substring(o + open.length, c))
    }

    private fun unescape(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    private fun isoFromMillis(ms: ULong): String {
        if (ms == 0uL) return ""
        val date = java.util.Date(ms.toLong())
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return fmt.format(date)
    }

    // endregion
}
