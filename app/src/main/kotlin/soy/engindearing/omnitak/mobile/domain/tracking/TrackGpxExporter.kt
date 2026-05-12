package soy.engindearing.omnitak.mobile.domain.tracking

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Render a [Track] as GPX 1.1 with a single `<trkseg>`. Pure: takes
 * the track in, returns a string. Caller writes to disk + share-
 * sheets it.
 *
 * GPX is the universal interchange format for any tracking-aware
 * tool (CalTopo, Strava, Garmin BaseCamp, OsmAnd…). KML is fine for
 * CalTopo + Google Earth, but the GPX path is what makes the data
 * portable beyond mapping software.
 */
object TrackGpxExporter {

    fun export(track: Track): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"OmniTAK Android\"\n")
        sb.append("  xmlns=\"http://www.topografix.com/GPX/1/1\"\n")
        sb.append("  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
        sb.append("  xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
        sb.append("  <metadata>\n")
        sb.append("    <name>").append(escape(track.name)).append("</name>\n")
        sb.append("    <time>").append(ISO.format(Date(track.startedMs))).append("</time>\n")
        sb.append("  </metadata>\n")
        sb.append("  <trk>\n")
        sb.append("    <name>").append(escape(track.name)).append("</name>\n")
        if (!track.notes.isNullOrBlank()) {
            sb.append("    <desc>").append(escape(track.notes)).append("</desc>\n")
        }
        sb.append("    <trkseg>\n")
        for (p in track.points) {
            sb.append("      <trkpt lat=\"").append(p.lat).append("\" lon=\"").append(p.lon).append("\">\n")
            sb.append("        <ele>").append(p.altitudeM).append("</ele>\n")
            sb.append("        <time>").append(ISO.format(Date(p.timestampMs))).append("</time>\n")
            sb.append("      </trkpt>\n")
        }
        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
        return sb.toString()
    }

    private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
