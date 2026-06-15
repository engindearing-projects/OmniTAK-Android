package soy.engindearing.omnitak.mobile.data

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Lightweight XmlPullParser-based CoT event parser. Pulls the fields
 * OmniTAK renders today (uid, type, time, stale, point, contact
 * callsign, __group team name/role, remarks, usericon iconsetpath).
 * Silently returns null on malformed input rather than throwing — the
 * read loop can't afford to die on a single bad event.
 *
 * Built on [XmlPullParserFactory] (not `android.util.Xml`) so it runs
 * on plain-JVM unit tests too — there is no separate "test fallback"
 * parser anymore; tests exercise exactly the production path.
 *
 * Usage:
 *   CoTParser.parse("<event …><point …/><detail><contact callsign=…/></detail></event>")
 */
object CoTParser {
    fun parse(xml: String): CoTEvent? = runCatching {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
        val parser: XmlPullParser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var uid: String? = null
        var type: String? = null
        var timeIso: String? = null
        var staleIso: String? = null
        var lat: Double? = null
        var lon: Double? = null
        var hae: Double = 0.0
        var ce: Double = 9_999_999.0
        var le: Double = 9_999_999.0
        var callsign: String? = null
        var teamName: String? = null
        var teamRole: String? = null
        var remarks: String? = null
        var iconsetPath: String? = null
        var colorArgb: Int? = null
        var courseHeading: Double? = null

        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "event" -> {
                        uid = parser.getAttributeValue(null, "uid")
                        type = parser.getAttributeValue(null, "type")
                        timeIso = parser.getAttributeValue(null, "time")
                        staleIso = parser.getAttributeValue(null, "stale")
                    }
                    "point" -> {
                        lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                        lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        hae = parser.getAttributeValue(null, "hae")?.toDoubleOrNull() ?: 0.0
                        ce = parser.getAttributeValue(null, "ce")?.toDoubleOrNull() ?: 9_999_999.0
                        le = parser.getAttributeValue(null, "le")?.toDoubleOrNull() ?: 9_999_999.0
                    }
                    "contact" -> {
                        callsign = parser.getAttributeValue(null, "callsign") ?: callsign
                    }
                    // TAK team assignment — <__group name="Red" role="Team Member"/>
                    // present under <detail> on PPLI events. When present it overrides
                    // the MIL-STD affiliation color so the contact matches Civtak/iTAK.
                    "__group" -> {
                        teamName = parser.getAttributeValue(null, "name") ?: teamName
                        teamRole = parser.getAttributeValue(null, "role") ?: teamRole
                    }
                    // Free-text remarks — chat fallback + marker notes read these.
                    "remarks" -> {
                        remarks = parser.nextText() ?: remarks
                    }
                    // Custom-glyph icon (FEMA etc.) — rebuildEvent re-emits it.
                    "usericon" -> {
                        iconsetPath = parser.getAttributeValue(null, "iconsetpath") ?: iconsetPath
                    }
                    // Marker colour — Spot Map points carry their swatch here
                    // (<color argb="-65536"/>); the TAK icon registry tints the
                    // dot from it so received spots match the sender's colour.
                    "color" -> {
                        colorArgb = parser.getAttributeValue(null, "argb")?.toIntOrNull() ?: colorArgb
                    }
                    // Course heading from <track course="270.0" speed="0.0"/>
                    "track" -> {
                        courseHeading = parser.getAttributeValue(null, "course")?.toDoubleOrNull() ?: courseHeading
                    }
                }
            }
            ev = parser.next()
        }

        if (uid == null || type == null || lat == null || lon == null) return@runCatching null
        CoTEvent(
            uid = uid,
            type = type,
            lat = lat,
            lon = lon,
            hae = hae,
            ce = ce,
            le = le,
            timeIso = timeIso,
            staleIso = staleIso,
            callsign = callsign,
            remarks = remarks ?: "",
            rawXml = xml,
            teamName = teamName,
            teamRole = teamRole,
            iconsetPath = iconsetPath,
            colorArgb = colorArgb,
            courseHeading = courseHeading,
        )
    }.getOrNull()
}
