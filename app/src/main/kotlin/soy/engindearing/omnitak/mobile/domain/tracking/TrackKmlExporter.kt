package soy.engindearing.omnitak.mobile.domain.tracking

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Render a [Track] as KML 2.2 with a single LineString. Pure: takes
 * the track in, returns a string. Caller writes to disk + share-
 * sheets it.
 *
 * Closes the K9 SAR ask "Exporting Missions is not possible from the
 * app" — paired with the per-track export, an IC drops the .kml
 * straight into CalTopo or Google Earth without translation.
 */
object TrackKmlExporter {

    fun export(track: Track): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n")
        sb.append("<Document>\n")
        sb.append("<name>").append(escape(track.name)).append("</name>\n")
        sb.append("<description>Recorded ")
            .append(ISO_HUMAN.format(Date(track.startedMs)))
            .append(" — ")
            .append("%.0f m, %d points".format(track.totalDistanceM, track.points.size))
            .append("</description>\n")

        sb.append("<Style id=\"trackStyle\">")
            .append("<LineStyle><color>")
            .append(hexToKmlColor(track.color))
            .append("</color><width>4</width></LineStyle>")
            .append("</Style>\n")

        sb.append("<Placemark>")
            .append("<name>").append(escape(track.name)).append("</name>")
            .append("<styleUrl>#trackStyle</styleUrl>")
            .append("<LineString><tessellate>1</tessellate><altitudeMode>clampToGround</altitudeMode><coordinates>")
        for (p in track.points) {
            sb.append(p.lon).append(',').append(p.lat).append(',').append(p.altitudeM).append(' ')
        }
        sb.append("</coordinates></LineString></Placemark>\n")
        sb.append("</Document>\n</kml>\n")
        return sb.toString()
    }

    /** "#RRGGBB" → "AABBGGRR" (KML byte order). */
    internal fun hexToKmlColor(hex: String): String {
        val clean = hex.trimStart('#')
        if (clean.length != 6) return "ff4ade80"
        val r = clean.substring(0, 2)
        val g = clean.substring(2, 4)
        val b = clean.substring(4, 6)
        return "ff$b$g$r"
    }

    private val ISO_HUMAN = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
