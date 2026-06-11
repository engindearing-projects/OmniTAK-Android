package soy.engindearing.omnitak.mobile.data

/**
 * Enumerations mirroring the meshtastic atak.proto Team and MemberRole
 * enums used in TAKPacket. Values match the proto's integer assignments
 * exactly — the gateway and unishox2-py3 golden vectors were produced
 * with these exact integer values so they must not be renumbered.
 *
 * CoT / ATAK use a space-separated display form ("Dark Blue", "Team Member")
 * while the proto uses an underscore form ("Dark_Blue", "TeamMember").
 * [Team.toCotName] / [Team.fromCotName] and [MemberRole.toCotName] /
 * [MemberRole.fromCotName] bridge between the two forms.
 */

enum class Team(val value: Int, private val cotName: String) {
    Unspecifed_Color(0, "Unspecified"),
    White(1,       "White"),
    Yellow(2,      "Yellow"),
    Orange(3,      "Orange"),
    Magenta(4,     "Magenta"),
    Red(5,         "Red"),
    Maroon(6,      "Maroon"),
    Purple(7,      "Purple"),
    Dark_Blue(8,   "Dark Blue"),
    Blue(9,        "Blue"),
    Cyan(10,       "Cyan"),
    Teal(11,       "Teal"),
    Green(12,      "Green"),
    Dark_Green(13, "Dark Green"),
    Brown(14,      "Brown");

    /** The CoT display name used inside `<__group name="…"/>` attributes. */
    fun toCotName(): String = cotName

    companion object {
        /** Lookup by integer value. Returns [Unspecifed_Color] on miss. */
        fun fromValue(v: Int): Team = entries.firstOrNull { it.value == v } ?: Unspecifed_Color

        /**
         * Lookup by CoT display name (case-insensitive space form,
         * e.g. "Dark Blue") OR proto enum name (underscore form,
         * e.g. "Dark_Blue"). Returns [Unspecifed_Color] on miss.
         */
        fun fromCotName(name: String?): Team {
            if (name == null) return Unspecifed_Color
            val lower = name.lowercase()
            return entries.firstOrNull {
                it.cotName.lowercase() == lower ||
                it.name.lowercase() == lower ||
                it.name.lowercase() == lower.replace(' ', '_') ||
                it.cotName.lowercase() == lower.replace('_', ' ')
            } ?: Unspecifed_Color
        }
    }
}

enum class MemberRole(val value: Int, private val cotName: String) {
    Unspecifed(0,       "Unspecified"),
    TeamMember(1,       "Team Member"),
    TeamLead(2,         "Team Lead"),
    HQ(3,               "HQ"),
    Sniper(4,           "Sniper"),
    Medic(5,            "Medic"),
    ForwardObserver(6,  "Forward Observer"),
    RTO(7,              "RTO"),
    K9(8,               "K9");

    /** The CoT display name used inside `<__group role="…"/>` attributes. */
    fun toCotName(): String = cotName

    companion object {
        /** Lookup by integer value. Returns [Unspecifed] on miss. */
        fun fromValue(v: Int): MemberRole = entries.firstOrNull { it.value == v } ?: Unspecifed

        /**
         * Lookup by CoT role name ("Team Member") or proto name ("TeamMember").
         * Case-insensitive, space ↔ no-space both accepted.
         */
        fun fromCotName(name: String?): MemberRole {
            if (name == null) return Unspecifed
            val lower = name.lowercase()
            return entries.firstOrNull {
                it.cotName.lowercase() == lower ||
                it.name.lowercase() == lower ||
                it.name.lowercase() == lower.replace(' ', '_').replace("_", "") ||
                it.cotName.lowercase() == lower.replace("_", " ")
            } ?: Unspecifed
        }
    }
}
