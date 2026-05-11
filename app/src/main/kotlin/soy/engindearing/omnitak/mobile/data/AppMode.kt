package soy.engindearing.omnitak.mobile.data

import androidx.compose.ui.graphics.Color

/**
 * Operator persona for the app — same enum as iOS
 * `AppModePreferences.swift`. Switches terminology, the radial-menu
 * action set, and the accent palette so the same client serves a
 * tactical operator, a fire/rescue ICP, a SAR team K9 handler, or a
 * civilian / hobbyist user without forcing one community's vocabulary
 * on the others.
 *
 * SAR is the immediate driver — K9Blue's TROP feedback (5/9/26) named
 * the lack of SAR-specific iconography + terminology as a major QOL
 * gap. We surface "Searcher / Clue / POI / Hazard" in SAR mode so
 * dropped markers actually mean what the team calls them.
 */
enum class AppMode(val wire: String) {
    TACTICAL("tactical"),
    FIRE_RESCUE("fire_rescue"),
    SAR("sar"),
    CIVILIAN("civilian");

    val displayName: String
        get() = when (this) {
            TACTICAL -> "Tactical"
            FIRE_RESCUE -> "Fire/Rescue"
            SAR -> "SAR"
            CIVILIAN -> "Civilian"
        }

    val subtitle: String
        get() = when (this) {
            TACTICAL -> "Military & Law Enforcement"
            FIRE_RESCUE -> "Firefighting & EMS"
            SAR -> "Search & Rescue"
            CIVILIAN -> "General Use"
        }

    val accentColor: Color
        get() = when (this) {
            TACTICAL -> Color(0xFFFFFC00)   // TAK Yellow
            FIRE_RESCUE -> Color(0xFFFF4444) // Fire Red
            SAR -> Color(0xFFFF8C00)        // SAR Orange
            CIVILIAN -> Color(0xFF4A90D9)   // Calm Blue
        }

    val secondaryColor: Color
        get() = when (this) {
            TACTICAL -> Color(0xFF00FF00)
            FIRE_RESCUE -> Color(0xFFFFD700)
            SAR -> Color(0xFF32CD32)
            CIVILIAN -> Color(0xFF50C878)
        }

    val terminology: ModeTerminology
        get() = when (this) {
            TACTICAL -> ModeTerminology(
                friendlyMarker = "Friendly",
                hostileMarker = "Hostile",
                unknownMarker = "Unknown",
                neutralMarker = "Neutral",
                waypoint = "Waypoint",
                team = "Unit",
                base = "FOB",
            )
            FIRE_RESCUE -> ModeTerminology(
                friendlyMarker = "Crew",
                hostileMarker = "Hazard",
                unknownMarker = "Unknown",
                neutralMarker = "Resource",
                waypoint = "Marker",
                team = "Engine",
                base = "ICP",
            )
            SAR -> ModeTerminology(
                friendlyMarker = "Searcher",
                hostileMarker = "Hazard",
                unknownMarker = "Clue",
                neutralMarker = "POI",
                waypoint = "Waypoint",
                team = "Team",
                base = "Base Camp",
            )
            CIVILIAN -> ModeTerminology(
                friendlyMarker = "Friend",
                hostileMarker = "Caution",
                unknownMarker = "Unknown",
                neutralMarker = "Point",
                waypoint = "Pin",
                team = "Group",
                base = "Home",
            )
        }

    companion object {
        fun fromWire(wire: String?): AppMode =
            values().firstOrNull { it.wire.equals(wire, ignoreCase = true) } ?: TACTICAL
    }
}

data class ModeTerminology(
    val friendlyMarker: String,
    val hostileMarker: String,
    val unknownMarker: String,
    val neutralMarker: String,
    val waypoint: String,
    val team: String,
    val base: String,
)
