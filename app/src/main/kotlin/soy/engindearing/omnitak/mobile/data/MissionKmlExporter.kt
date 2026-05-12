package soy.engindearing.omnitak.mobile.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Bundle every marker (CoT contact) + drawing on the map into a single
 * KML file. Mirrors what a SAR team needs to hand off mid-search: a
 * file the IC can drop into CalTopo, ATAK, or another K9 handler's
 * client without translation.
 *
 * Closes K9Blue's TROP feedback (5/9/26): "Exporting Missions is not
 * possible from the app. Which really stinks because sometimes we will
 * be asked to provide search data on the spot when we do not have a
 * CalTopo file or equivalent."
 *
 * Pure: takes the in-memory state, returns a string. Caller writes to
 * disk + share-sheets it.
 */
object MissionKmlExporter {

    /**
     * @param markers CoT contacts indexed by uid (e.g. ContactStore.contacts.value)
     * @param drawings All drawings on the map (DrawingStore.drawings.value)
     * @param missionName Free-text name; defaults to "OmniTAK Mission YYYY-MM-DD HHmm"
     */
    fun export(
        markers: Map<String, CoTEvent>,
        drawings: List<Drawing>,
        missionName: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        val name = missionName ?: "OmniTAK Mission ${ISO_FILE.format(Date(nowMs))}"
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n")
        sb.append("<Document>\n")
        sb.append("<name>").append(escape(name)).append("</name>\n")
        sb.append("<description>Exported from OmniTAK at ")
            .append(ISO_HUMAN.format(Date(nowMs))).append("</description>\n")

        // Styles for affiliations
        for ((id, color) in AFFILIATION_STYLES) {
            sb.append("<Style id=\"").append(id).append("\">")
                .append("<IconStyle><color>").append(color).append("</color>")
                .append("<Icon><href>http://maps.google.com/mapfiles/kml/shapes/placemark_circle.png</href></Icon></IconStyle>")
                .append("<LineStyle><color>").append(color).append("</color><width>3</width></LineStyle>")
                .append("<PolyStyle><color>40").append(color.drop(2)).append("</color></PolyStyle>")
                .append("</Style>\n")
        }
        sb.append("<Style id=\"sar_drawing\">")
            .append("<LineStyle><color>ff80de4a</color><width>3</width></LineStyle>")
            .append("<PolyStyle><color>4080de4a</color></PolyStyle>")
            .append("</Style>\n")

        // Markers folder
        sb.append("<Folder><name>Markers</name>\n")
        for ((_, m) in markers) {
            sb.append("<Placemark>\n")
            sb.append("<name>").append(escape(m.callsign?.takeIf { it.isNotBlank() } ?: m.uid)).append("</name>\n")
            sb.append("<styleUrl>#").append(styleIdFor(m.affiliation)).append("</styleUrl>\n")
            if (!m.remarks.isNullOrBlank()) {
                sb.append("<description>").append(escape(m.remarks!!)).append("</description>\n")
            }
            sb.append("<Point><coordinates>")
                .append(m.lon).append(',').append(m.lat).append(',').append(m.hae)
                .append("</coordinates></Point>\n")
            sb.append("</Placemark>\n")
        }
        sb.append("</Folder>\n")

        // Drawings folder
        sb.append("<Folder><name>Drawings</name>\n")
        for (d in drawings) {
            when (d.kind) {
                DrawingKind.LINE -> appendLine(sb, d)
                DrawingKind.POLYGON -> appendPolygon(sb, d)
                DrawingKind.CIRCLE -> appendCircle(sb, d)
                // == S3:bullseye BEGIN ==
                DrawingKind.BULLSEYE -> appendBullseye(sb, d)
                // == S3:bullseye END ==
            }
        }
        sb.append("</Folder>\n")

        sb.append("</Document>\n</kml>\n")
        return sb.toString()
    }

    private fun appendLine(sb: StringBuilder, d: Drawing) {
        sb.append("<Placemark><name>").append(escape(d.name.ifBlank { "Line" })).append("</name>")
            .append("<styleUrl>#sar_drawing</styleUrl>")
            .append("<LineString><tessellate>1</tessellate><coordinates>")
        for ((lat, lon) in d.points) sb.append(lon).append(',').append(lat).append(",0 ")
        sb.append("</coordinates></LineString></Placemark>\n")
    }

    private fun appendPolygon(sb: StringBuilder, d: Drawing) {
        if (d.points.isEmpty()) return
        sb.append("<Placemark><name>").append(escape(d.name.ifBlank { "Polygon" })).append("</name>")
            .append("<styleUrl>#sar_drawing</styleUrl>")
            .append("<Polygon><outerBoundaryIs><LinearRing><coordinates>")
        for ((lat, lon) in d.points) sb.append(lon).append(',').append(lat).append(",0 ")
        // Close the ring.
        val (lat0, lon0) = d.points.first()
        sb.append(lon0).append(',').append(lat0).append(",0")
        sb.append("</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>\n")
    }

    private fun appendCircle(sb: StringBuilder, d: Drawing) {
        if (d.points.size < 2) return
        val (cLat, cLon) = d.points[0]
        val (eLat, eLon) = d.points[1]
        val radiusM = haversineMeters(cLat, cLon, eLat, eLon)
        sb.append("<Placemark><name>")
            .append(escape(d.name.ifBlank { "Circle" }))
            .append(" (").append(formatRadius(radiusM)).append(")</name>")
            .append("<styleUrl>#sar_drawing</styleUrl>")
            .append("<Polygon><outerBoundaryIs><LinearRing><coordinates>")
        // Approximate the circle with 64 points so it imports cleanly into
        // CalTopo / Google Earth (no native circle primitive in KML 2.2).
        val steps = 64
        for (i in 0..steps) {
            val ang = (i.toDouble() / steps) * 2.0 * Math.PI
            val dLat = (radiusM / EARTH_R_M) * Math.cos(ang)
            val dLon = (radiusM / EARTH_R_M) * Math.sin(ang) / Math.cos(Math.toRadians(cLat))
            val plat = cLat + Math.toDegrees(dLat)
            val plon = cLon + Math.toDegrees(dLon)
            sb.append(plon).append(',').append(plat).append(",0 ")
        }
        sb.append("</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>\n")
    }

    // == S3:bullseye BEGIN ==
    /**
     * K9 feedback (5/9/26): "Bullseye could not be shared within a
     * mission." Each ring renders as its own closed polygon so CalTopo
     * and the wider KML ecosystem (which has no bullseye primitive)
     * still reproduces the SAR-standard 100 m / 500 m / 1 km set the
     * operator drew.
     */
    private fun appendBullseye(sb: StringBuilder, d: Drawing) {
        val (cLat, cLon) = d.points.firstOrNull() ?: return
        val radii = (d.radiiM ?: DrawingCoT.DEFAULT_BULLSEYE_RADII_M)
            .filter { it > 0.0 }
            .sorted()
        if (radii.isEmpty()) return
        radii.forEachIndexed { idx, radiusM ->
            sb.append("<Placemark><name>")
                .append(escape(d.name.ifBlank { "Bullseye" }))
                .append(" R").append(idx + 1)
                .append(" (").append(formatRadius(radiusM)).append(")</name>")
                .append("<styleUrl>#sar_drawing</styleUrl>")
                .append("<Polygon><outerBoundaryIs><LinearRing><coordinates>")
            val steps = 64
            for (i in 0..steps) {
                val ang = (i.toDouble() / steps) * 2.0 * Math.PI
                val dLat = (radiusM / EARTH_R_M) * Math.cos(ang)
                val dLon = (radiusM / EARTH_R_M) * Math.sin(ang) /
                    Math.cos(Math.toRadians(cLat))
                val plat = cLat + Math.toDegrees(dLat)
                val plon = cLon + Math.toDegrees(dLon)
                sb.append(plon).append(',').append(plat).append(",0 ")
            }
            sb.append("</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>\n")
        }
    }
    // == S3:bullseye END ==

    private val AFFILIATION_STYLES = mapOf(
        "friend" to "ff00ff00",   // green (kml is aabbggrr)
        "hostile" to "ff0000ff",  // red
        "neutral" to "ffffffff",  // white
        "unknown" to "ff00ffff",  // yellow
    )

    private fun styleIdFor(a: CoTAffiliation): String = when (a) {
        CoTAffiliation.FRIEND -> "friend"
        CoTAffiliation.HOSTILE -> "hostile"
        CoTAffiliation.NEUTRAL -> "neutral"
        CoTAffiliation.UNKNOWN -> "unknown"
        else -> "unknown"
    }

    private const val EARTH_R_M = 6_371_000.0

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2).let { it * it }
        return 2.0 * EARTH_R_M * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    private fun formatRadius(m: Double): String = when {
        m >= 1000.0 -> "%.1f km".format(m / 1000.0)
        else -> "%.0f m".format(m)
    }

    private val ISO_FILE = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val ISO_HUMAN = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
