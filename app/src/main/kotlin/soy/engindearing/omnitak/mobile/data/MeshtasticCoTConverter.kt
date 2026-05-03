package soy.engindearing.omnitak.mobile.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Converts a [MeshNode] into a [CoTEvent] + the matching XML payload
 * iOS produces. The XML layout (and crucially the `<__meshtastic__>`
 * extension block) is a 1:1 mirror of
 * `MeshtasticCoTConverter.swift::generateCoT` so a node observed by
 * iOS and one observed by Android look identical to a TAK server.
 *
 * Default affiliation is friendly civilian (`a-f-G-U-C`) — Phase 4 may
 * upgrade this once we know the node's role from its ATAK-plugin
 * payloads. Until then "friendly" is the safer default for an OmniTAK
 * user's own mesh.
 *
 * UID format: `MESHTASTIC-{NODEID_HEX_UPPER}` — matches the iOS
 * `MeshtasticCOTBridge` UID convention exactly. The legacy
 * `mesh-{nodeid_hex_lower}` UID lives in `takUidLegacy()` for the
 * `MeshtasticCoTConverter.swift::generateCoT` interop path.
 */
object MeshtasticCoTConverter {

    /** Default stale window for mesh nodes — iOS uses 5 minutes. */
    private const val DEFAULT_STALE_SECONDS = 300L

    /** Convert a [MeshNode] to a [CoTEvent]. Returns null if the node
     *  has no position (CoT events without a point are useless on a
     *  map). */
    fun nodeToCoT(
        node: MeshNode,
        staleSeconds: Long = DEFAULT_STALE_SECONDS,
        isOwnNode: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ): CoTEvent? {
        val position = node.position ?: return null
        val now = Date(nowMs)
        val stale = Date(nowMs + staleSeconds * 1_000)
        val time = isoFormatter.format(now)
        val staleIso = isoFormatter.format(stale)

        val uid = takUid(node.id, isOwnNode)
        val type = if (isOwnNode) "a-f-G-U-C" else "a-f-G-U-C"
        val callsign = (node.longName.ifBlank { node.shortName.ifBlank { "Node ${node.idHex}" } })

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

    /** TAK UID for a mesh node — matches iOS `MeshtasticCOTBridge`. */
    fun takUid(nodeId: Long, isOwnNode: Boolean = false): String {
        val hex = "%08X".format(nodeId.toInt())
        return if (isOwnNode) "mesh-self-${hex.lowercase()}" else "MESHTASTIC-$hex"
    }

    /** Legacy UID format used by iOS `MeshtasticCoTConverter.generateCoT`
     *  (lowercase, `mesh-` prefix). */
    fun takUidLegacy(nodeId: Long): String =
        "mesh-${"%08X".format(nodeId.toInt()).lowercase()}"

    private fun buildRemarks(node: MeshNode): String {
        val sb = StringBuilder("Meshtastic Node | ID: !${node.idHex}")
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
        val nodeIdUpper = "%08X".format(node.id.toInt())
        val shortEsc = escape(node.shortName)
        val longEsc = escape(callsign)
        val lastHeardIso = isoFormatter.format(Date(node.lastHeardEpoch * 1_000L))
        val snr = node.snr ?: 0.0
        val hops = node.hopDistance ?: 0
        val battery = node.batteryLevel ?: -1
        val remarksEsc = escape(remarks)
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <event version="2.0" uid="$uid" type="$type" time="$time" start="$time" stale="$stale" how="m-g">
                <point lat="${position.lat}" lon="${position.lon}" hae="$hae" ce="50.0" le="50.0"/>
                <detail>
                    <contact callsign="$longEsc"/>
                    <__group name="Cyan" role="Team Member"/>
                    <usericon iconsetpath="COT_MAPPING_2525C/a-f-G"/>
                    <precisionlocation altsrc="GPS" geopointsrc="Meshtastic"/>
                    <status readiness="true"/>
                    <remarks>$remarksEsc</remarks>
                    <__meshtastic__>
                        <node_id>$nodeIdUpper</node_id>
                        <short_name>$shortEsc</short_name>
                        <long_name>$longEsc</long_name>
                        <snr>$snr</snr>
                        <hop_distance>$hops</hop_distance>
                        <battery>$battery</battery>
                        <last_heard>$lastHeardIso</last_heard>
                    </__meshtastic__>
                    <takv device="Meshtastic" platform="OmniTAK" os="Android" version="0.1.0"/>
                </detail>
            </event>
        """.trimIndent()
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private val isoFormatter: SimpleDateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
