package soy.engindearing.omnitak.mobile.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Build Cursor-on-Target XML payloads for shapes the operator draws on
 * the map. Closes K9Blue's "Bullseye could not be shared within a
 * mission" feedback by extending the same multi-server fan-out
 * ([ServerManager.sendCoT]) that already carries markers and PPLI to
 * every line, polygon, circle, and bullseye the operator finishes.
 *
 * CoT type conventions follow what ATAK 5.x peers expect:
 *   - LINE / POLYGON → `u-d-f` (freeform drawing). The vertex chain is
 *     rendered as `<link uid type point relation>` siblings inside
 *     `<detail>`, in vertex order. Closed paths add `closed_polygon=1`
 *     in `<__shapeExtras>` so ATAK fills the interior.
 *   - CIRCLE → `u-d-c-c`. Centre is the `<point>`; radius is carried
 *     both in `<shape><ellipse minor major angle>` (the ATAK-canonical
 *     form) and in `<remarks>` ("radius=Nm") so older / open-source
 *     clients that don't read the ellipse extension still see the
 *     intent.
 *   - BULLSEYE → emitted as N concentric CIRCLE events sharing a base
 *     UID. The "multiple concentric circles" approach in the plan was
 *     picked over the `<bullseye>` detail extension because every TAK
 *     client renders circles, but only ATAK reads the extension.
 *
 * Stale time defaults to 6 h — a search team should still see their
 * own shapes after a refuel break, but the map shouldn't fossilise a
 * day-old plan. Operators can override via [staleSeconds].
 */
object DrawingCoT {

    const val DEFAULT_STALE_SECONDS: Long = 6L * 60L * 60L

    /** SAR-standard ring set when an operator drops a bullseye without
     *  customising radii in the finish sheet. 100 m / 500 m / 1 km
     *  maps onto the "are we within rifle / patrol / vehicle range"
     *  decision rungs K9Blue uses in field briefings. */
    val DEFAULT_BULLSEYE_RADII_M: List<Double> = listOf(100.0, 500.0, 1_000.0)

    /**
     * Top-level dispatch: serialize [drawing] into one or more CoT
     * events. Returns a single-element list for line / polygon /
     * circle, an N-element list for bullseye (one event per ring).
     */
    fun buildAll(
        drawing: Drawing,
        callsign: String,
        nowMs: Long = System.currentTimeMillis(),
        staleSeconds: Long = DEFAULT_STALE_SECONDS,
    ): List<String> {
        return when (drawing.kind) {
            DrawingKind.LINE -> listOf(
                buildLine(drawing, callsign, closed = false, nowMs, staleSeconds)
            )
            DrawingKind.POLYGON -> listOf(
                buildLine(drawing, callsign, closed = true, nowMs, staleSeconds)
            )
            DrawingKind.CIRCLE -> {
                val (centre, radiusM) = circleCentreAndRadius(drawing)
                    ?: return emptyList()
                listOf(
                    buildCircle(
                        uid = drawing.id,
                        callsign = callsign.ifBlank { drawing.name.ifBlank { "Circle" } },
                        lat = centre.first,
                        lon = centre.second,
                        radiusM = radiusM,
                        colorHex = drawing.colorHex,
                        widthPx = drawing.widthPx,
                        nowMs = nowMs,
                        staleSeconds = staleSeconds,
                    )
                )
            }
            DrawingKind.BULLSEYE -> {
                val centre = drawing.points.firstOrNull() ?: return emptyList()
                val radii = (drawing.radiiM ?: DEFAULT_BULLSEYE_RADII_M)
                    .filter { it > 0.0 }
                    .sorted()
                if (radii.isEmpty()) return emptyList()
                val baseCs = callsign.ifBlank { drawing.name.ifBlank { "Bullseye" } }
                radii.mapIndexed { idx, r ->
                    buildCircle(
                        // Stable per-ring UID so the receiver's idempotent
                        // ingest replaces an earlier broadcast cleanly.
                        uid = "${drawing.id}-ring${idx + 1}",
                        callsign = "$baseCs R${idx + 1}",
                        lat = centre.first,
                        lon = centre.second,
                        radiusM = r,
                        colorHex = drawing.colorHex,
                        widthPx = drawing.widthPx,
                        nowMs = nowMs,
                        staleSeconds = staleSeconds,
                        // Detail tag that tells a smart receiver the
                        // ring is part of a multi-ring bullseye group;
                        // dumb receivers just see a plain circle.
                        bullseyeGroup = drawing.id,
                        bullseyeRing = idx + 1,
                        bullseyeRingCount = radii.size,
                    )
                }
            }
        }
    }

    /**
     * Build a freeform line (or closed polygon) event. Vertex chain is
     * encoded as `<link>` siblings — ATAK reads the chain as the
     * shape's geometry; the event's `<point>` becomes the first vertex
     * so a non-ATAK client at least sees one waypoint.
     */
    fun buildLine(
        drawing: Drawing,
        callsign: String,
        closed: Boolean,
        nowMs: Long = System.currentTimeMillis(),
        staleSeconds: Long = DEFAULT_STALE_SECONDS,
    ): String {
        val pts = drawing.points
        require(pts.size >= 2) { "Line/polygon needs at least 2 points" }
        val anchor = pts.first()
        val safeCs = escape(callsign.ifBlank { drawing.name.ifBlank { if (closed) "Polygon" else "Line" } })
        val argb = hexToArgbInt(drawing.colorHex)
        val time = isoUtc(nowMs)
        val stale = isoUtc(nowMs + staleSeconds * 1000L)
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append("<event version=\"2.0\"")
            append(" uid=\"").append(escape(drawing.id)).append('"')
            append(" type=\"u-d-f\"")
            append(" time=\"").append(time).append('"')
            append(" start=\"").append(time).append('"')
            append(" stale=\"").append(stale).append('"')
            append(" how=\"h-e\">")
            append("<point lat=\"").append(anchor.first).append('"')
            append(" lon=\"").append(anchor.second).append('"')
            append(" hae=\"0.0\" ce=\"9999999.0\" le=\"9999999.0\"/>")
            append("<detail>")
            // Vertex chain. Each <link> carries lat,lon,hae as ATAK
            // expects in shape definitions.
            for ((i, p) in pts.withIndex()) {
                append("<link")
                append(" uid=\"").append(escape(drawing.id)).append("-v").append(i).append('"')
                append(" type=\"b-m-p\"")
                append(" point=\"").append(p.first).append(',').append(p.second).append(",0.0\"")
                append(" relation=\"p-p\"")
                append("/>")
            }
            // Repeat the first vertex as the final link so consuming
            // clients that don't honour `closed_polygon` still render a
            // visually-closed path. Cheap, harmless on open lines? —
            // we only do it for closed.
            if (closed) {
                append("<link")
                append(" uid=\"").append(escape(drawing.id)).append("-v").append(pts.size).append('"')
                append(" type=\"b-m-p\"")
                append(" point=\"").append(anchor.first).append(',').append(anchor.second).append(",0.0\"")
                append(" relation=\"p-p\"")
                append("/>")
                append("<__shapeExtras editable=\"true\" closed_polygon=\"1\"/>")
            } else {
                append("<__shapeExtras editable=\"true\"/>")
            }
            append("<strokeColor value=\"").append(argb).append("\"/>")
            append("<strokeWeight value=\"").append(drawing.widthPx).append("\"/>")
            append("<fillColor value=\"").append(if (closed) (argb and 0x33FFFFFF.toInt()) else 0).append("\"/>")
            append("<labels_on value=\"false\"/>")
            append("<contact callsign=\"").append(safeCs).append("\"/>")
            append("<remarks>OmniTAK drawing — ")
                .append(if (closed) "polygon" else "line")
                .append(", ").append(pts.size).append(" vertices</remarks>")
            append("</detail>")
            append("</event>")
        }
    }

    /**
     * Build a single circle event. Radius is duplicated into `<shape>`
     * (ATAK ellipse form) and `<remarks>` so older / open-source
     * clients that ignore the ellipse extension still recover it from
     * the human-readable remarks. [bullseyeGroup] / [bullseyeRing] /
     * [bullseyeRingCount] mark concentric rings so a smart receiver
     * can re-group them; absent for ad-hoc single circles.
     */
    fun buildCircle(
        uid: String,
        callsign: String,
        lat: Double,
        lon: Double,
        radiusM: Double,
        colorHex: String = "#4ADE80",
        widthPx: Float = 3f,
        nowMs: Long = System.currentTimeMillis(),
        staleSeconds: Long = DEFAULT_STALE_SECONDS,
        bullseyeGroup: String? = null,
        bullseyeRing: Int? = null,
        bullseyeRingCount: Int? = null,
    ): String {
        val argb = hexToArgbInt(colorHex)
        val time = isoUtc(nowMs)
        val stale = isoUtc(nowMs + staleSeconds * 1000L)
        val safeCs = escape(callsign)
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append("<event version=\"2.0\"")
            append(" uid=\"").append(escape(uid)).append('"')
            append(" type=\"u-d-c-c\"")
            append(" time=\"").append(time).append('"')
            append(" start=\"").append(time).append('"')
            append(" stale=\"").append(stale).append('"')
            append(" how=\"h-e\">")
            append("<point lat=\"").append(lat).append('"')
            append(" lon=\"").append(lon).append('"')
            append(" hae=\"0.0\" ce=\"9999999.0\" le=\"9999999.0\"/>")
            append("<detail>")
            append("<shape>")
            // ATAK ellipse: minor == major == radius for a true circle.
            append("<ellipse minor=\"").append(formatDouble(radiusM)).append('"')
            append(" major=\"").append(formatDouble(radiusM)).append('"')
            append(" angle=\"0\"/>")
            append("</shape>")
            append("<strokeColor value=\"").append(argb).append("\"/>")
            append("<strokeWeight value=\"").append(widthPx).append("\"/>")
            append("<fillColor value=\"0\"/>")
            append("<labels_on value=\"false\"/>")
            append("<contact callsign=\"").append(safeCs).append("\"/>")
            if (bullseyeGroup != null && bullseyeRing != null) {
                append("<__bullseye")
                append(" group=\"").append(escape(bullseyeGroup)).append('"')
                append(" ring=\"").append(bullseyeRing).append('"')
                if (bullseyeRingCount != null) {
                    append(" rings=\"").append(bullseyeRingCount).append('"')
                }
                append(" radius=\"").append(formatDouble(radiusM)).append('"')
                append("/>")
            }
            append("<remarks>OmniTAK drawing — circle, radius=")
                .append(formatDouble(radiusM)).append("m</remarks>")
            append("</detail>")
            append("</event>")
        }
    }

    /**
     * Recover the (lat,lon) + radius-in-metres from a [Drawing] with
     * `kind == CIRCLE` and the conventional `[center, edge]` point
     * list. Returns null when the shape is malformed.
     */
    fun circleCentreAndRadius(drawing: Drawing): Pair<Pair<Double, Double>, Double>? {
        if (drawing.points.size < 2) return null
        val centre = drawing.points[0]
        val edge = drawing.points[1]
        val r = haversineMetres(centre.first, centre.second, edge.first, edge.second)
        if (r <= 0.0 || r.isNaN()) return null
        return centre to r
    }

    /**
     * Parse an incoming drawing CoT event back into a [Drawing] for
     * ingest into [DrawingStore]. Supports the same set we serialise:
     * `u-d-f` (line / polygon) and `u-d-c-c` (circle / bullseye ring).
     * Returns null for anything that doesn't look like a drawing — the
     * caller falls through to the normal contact / chat parsers.
     *
     * Round-trip with [buildAll] is exercised in `DrawingCoTTest`.
     */
    fun parse(xml: String): Drawing? = runCatching {
        val typeRegex = Regex("""type="(u-d-[a-z\-]+)"""")
        val type = typeRegex.find(xml)?.groupValues?.get(1) ?: return@runCatching null
        val uid = Regex("""<event[^>]*uid="([^"]+)"""").find(xml)?.groupValues?.get(1)
            ?: return@runCatching null
        val callsign = Regex("""callsign="([^"]+)"""").find(xml)?.groupValues?.get(1) ?: ""
        val colorArgb = Regex("""<strokeColor value="(-?\d+)"/>""").find(xml)?.groupValues?.get(1)
            ?.toIntOrNull()
        val colorHex = colorArgb?.let { argbToHex(it) } ?: "#4ADE80"
        val widthPx = Regex("""<strokeWeight value="([\d.]+)"/>""").find(xml)
            ?.groupValues?.get(1)?.toFloatOrNull() ?: 3f
        return@runCatching when (type) {
            "u-d-f" -> {
                val linkRegex = Regex(
                    """<link\b[^/>]*\bpoint="([\-\d.]+),([\-\d.]+)(?:,[\-\d.]+)?"[^/>]*/>"""
                )
                val raw = linkRegex.findAll(xml).map {
                    val lat = it.groupValues[1].toDoubleOrNull()
                    val lon = it.groupValues[2].toDoubleOrNull()
                    if (lat == null || lon == null) null else lat to lon
                }.filterNotNull().toList()
                if (raw.size < 2) return@runCatching null
                // Closed polygon: the last <link> repeats the first
                // vertex. Drop the duplicate so the in-memory shape
                // matches what we originally stored.
                val closedTag = xml.contains("closed_polygon=\"1\"") ||
                    xml.contains("closed_polygon=\"true\"")
                val points = if (closedTag && raw.size >= 4 && raw.first() == raw.last()) {
                    raw.dropLast(1)
                } else {
                    raw
                }
                val kind = if (closedTag) DrawingKind.POLYGON else DrawingKind.LINE
                Drawing(
                    id = uid,
                    kind = kind,
                    name = callsign,
                    points = points,
                    colorHex = colorHex,
                    widthPx = widthPx,
                )
            }
            "u-d-c-c" -> {
                val lat = Regex("""<point lat="([\-\d.]+)"""").find(xml)?.groupValues?.get(1)
                    ?.toDoubleOrNull() ?: return@runCatching null
                val lon = Regex(""" lon="([\-\d.]+)"""").find(xml)?.groupValues?.get(1)
                    ?.toDoubleOrNull() ?: return@runCatching null
                val radius = Regex("""minor="([\d.]+)"""").find(xml)?.groupValues?.get(1)
                    ?.toDoubleOrNull()
                    ?: Regex("""radius=([\d.]+)m""").find(xml)?.groupValues?.get(1)
                        ?.toDoubleOrNull()
                    ?: return@runCatching null
                val edge = destinationPoint(lat, lon, bearingDeg = 0.0, distanceM = radius)
                // Bullseye-ring receivers reconstruct as a plain CIRCLE
                // — the renderer can re-collapse rings sharing the same
                // group attribute into a BULLSEYE if/when it cares.
                Drawing(
                    id = uid,
                    kind = DrawingKind.CIRCLE,
                    name = callsign,
                    points = listOf(lat to lon, edge),
                    colorHex = colorHex,
                    widthPx = widthPx,
                )
            }
            else -> null
        }
    }.getOrNull()

    // ---- Geo helpers ----------------------------------------------------

    /** Haversine distance in metres between two (lat,lon) points. */
    fun haversineMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val phi1 = lat1 * PI / 180.0
        val phi2 = lat2 * PI / 180.0
        val dPhi = (lat2 - lat1) * PI / 180.0
        val dLam = (lon2 - lon1) * PI / 180.0
        val a = sin(dPhi / 2).let { it * it } +
            cos(phi1) * cos(phi2) * sin(dLam / 2).let { it * it }
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    /** Point [distanceM] metres along [bearingDeg] from (lat,lon). Used
     *  to synthesise an edge point when we receive a circle and need to
     *  fit it into Drawing's [center, edge] convention. */
    fun destinationPoint(
        lat: Double,
        lon: Double,
        bearingDeg: Double,
        distanceM: Double,
    ): Pair<Double, Double> {
        val r = 6_371_000.0
        val brg = bearingDeg * PI / 180.0
        val phi1 = lat * PI / 180.0
        val lam1 = lon * PI / 180.0
        val d = distanceM / r
        val phi2 = kotlin.math.asin(
            sin(phi1) * cos(d) + cos(phi1) * sin(d) * cos(brg)
        )
        val lam2 = lam1 + atan2(
            sin(brg) * sin(d) * cos(phi1),
            cos(d) - sin(phi1) * sin(phi2),
        )
        return (phi2 * 180.0 / PI) to (lam2 * 180.0 / PI)
    }

    // ---- XML / colour helpers ------------------------------------------

    private val ISO_FMT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun isoUtc(epochMs: Long): String = synchronized(ISO_FMT) {
        ISO_FMT.format(Date(epochMs))
    }

    private fun formatDouble(v: Double): String =
        if (v == v.toLong().toDouble()) "${v.toLong()}.0"
        else String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')

    /** "#RRGGBB" or "#AARRGGBB" → ARGB signed Int. Defaults alpha to FF. */
    fun hexToArgbInt(hex: String): Int {
        val s = hex.removePrefix("#")
        val padded = when (s.length) {
            6 -> "FF$s"
            8 -> s
            else -> "FF4ADE80"
        }
        return padded.toLong(16).toInt()
    }

    /** Inverse of [hexToArgbInt] for receive-side reconstruction. */
    fun argbToHex(argb: Int): String {
        val u = argb.toLong() and 0xFFFFFFFFL
        return "#" + String.format(Locale.US, "%08X", u)
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
