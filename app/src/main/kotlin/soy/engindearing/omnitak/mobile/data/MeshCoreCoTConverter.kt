package soy.engindearing.omnitak.mobile.data

/**
 * Converts a MeshCore contact/advert [MeshNode] into a [CoTEvent] +
 * matching XML. Parallel to [MeshtasticCoTConverter], but emits a
 * MeshCore-flavored UID and extension block so a TAK server can tell a
 * MeshCore contact apart from a Meshtastic one.
 *
 * v1 scope: a MeshCore contact's advertised lat/lon becomes a friendly
 * civilian PLI (`a-f-G-U-C`). UID format: `MESHCORE-<idHex8>` — the node
 * id here is the first 4 bytes of the contact's 32-byte public key
 * (see [MeshCoreFrameCodec.nodeIdFromPubKey]). A node with no advertised
 * position produces no CoT (a point-less event is useless on a map).
 */
object MeshCoreCoTConverter {

    /** Default stale window for mesh contacts — mirror Meshtastic's 5 min. */
    private const val DEFAULT_STALE_SECONDS = 300L

    fun nodeToCoT(
        node: MeshNode,
        staleSeconds: Long = DEFAULT_STALE_SECONDS,
        nowMs: Long = System.currentTimeMillis(),
    ): CoTEvent? {
        val position = node.position ?: return null
        val time = CotXml.isoMillis(nowMs)
        val staleIso = CotXml.isoMillis(nowMs + staleSeconds * 1_000)

        val uid = takUid(node.id)
        val type = "a-f-G-U-C"
        val callsign = node.longName.ifBlank { node.shortName.ifBlank { "Node ${node.idHex}" } }

        val remarks = buildRemarks(node)
        val xml = buildXml(
            uid = uid,
            type = type,
            time = time,
            stale = staleIso,
            node = node,
            position = position,
            callsign = callsign,
            remarks = remarks,
        )

        return CoTEvent(
            uid = uid,
            type = type,
            lat = position.lat,
            lon = position.lon,
            hae = (position.altitudeM ?: 0).toDouble(),
            ce = 50.0,
            le = 50.0,
            timeIso = time,
            staleIso = staleIso,
            callsign = callsign,
            remarks = remarks,
            rawXml = xml,
        )
    }

    /** TAK UID for a MeshCore contact — `MESHCORE-<12 uppercase hex>` (6-byte prefix). */
    fun takUid(nodeId: Long): String = "MESHCORE-${"%012X".format(nodeId)}"

    private fun buildRemarks(node: MeshNode): String {
        val sb = StringBuilder("MeshCore Node | ID: !${node.idHex}")
        node.snr?.let { sb.append(" | SNR: ${"%.1f".format(it)}dB") }
        node.hopDistance?.let { sb.append(" | Hops: $it") }
        node.batteryLevel?.let { sb.append(" | Bat: $it%") }
        return sb.toString()
    }

    private fun buildXml(
        uid: String,
        type: String,
        time: String,
        stale: String,
        node: MeshNode,
        position: MeshPosition,
        callsign: String,
        remarks: String,
    ): String {
        val hae = (position.altitudeM ?: 0).toDouble()
        val nodeIdUpper = "%012X".format(node.id)
        val shortEsc = escape(node.shortName)
        val longEsc = escape(callsign)
        val lastHeardIso = CotXml.isoMillis(node.lastHeardEpoch * 1_000L)
        val snr = node.snr ?: 0.0
        val hops = node.hopDistance ?: 0
        val battery = node.batteryLevel ?: -1
        val remarksEsc = escape(remarks)
        val detail = buildString {
            append("<detail>")
            append("<contact callsign=\"$longEsc\"/>")
            append("<__group name=\"Cyan\" role=\"Team Member\"/>")
            append("<usericon iconsetpath=\"COT_MAPPING_2525C/a-f-G\"/>")
            append("<precisionlocation altsrc=\"GPS\" geopointsrc=\"MeshCore\"/>")
            append("<status readiness=\"true\"/>")
            append("<remarks>$remarksEsc</remarks>")
            append("<__meshcore__>")
            append("<node_id>$nodeIdUpper</node_id>")
            append("<short_name>$shortEsc</short_name>")
            append("<long_name>$longEsc</long_name>")
            append("<snr>$snr</snr>")
            append("<hop_distance>$hops</hop_distance>")
            append("<battery>$battery</battery>")
            append("<last_heard>$lastHeardIso</last_heard>")
            append("</__meshcore__>")
            append("<takv device=\"MeshCore\" platform=\"OmniTAK\" os=\"Android\" version=\"${soy.engindearing.omnitak.mobile.BuildConfig.VERSION_NAME}\"/>")
            append("</detail>")
        }
        return CotXml.buildEvent(
            uid = uid,
            type = type,
            how = "m-g",
            lat = position.lat, lon = position.lon, hae = hae, ce = 50.0, le = 50.0,
            timeIso = time,
            staleIso = stale,
            detailXml = detail,
        )
    }

    private fun escape(s: String): String = CotXml.escape(s)
}
