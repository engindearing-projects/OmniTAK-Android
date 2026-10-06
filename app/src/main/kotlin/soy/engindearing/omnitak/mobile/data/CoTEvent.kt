package soy.engindearing.omnitak.mobile.data

import kotlinx.serialization.Serializable

/**
 * MIL-STD-2525 affiliation parsed from a CoT event `type` attribute
 * (the second token after "a-", e.g. `a-f-…` → Friend). Colors roughly
 * follow the ATAK palette — green/cyan/red/yellow.
 */
enum class CoTAffiliation(val code: Char) {
    FRIEND('f'),
    HOSTILE('h'),
    NEUTRAL('n'),
    UNKNOWN('u'),
    PENDING('p'),
    ASSUMED('a'),
    SUSPECT('s'),
    EXERCISE('j');

    companion object {
        fun fromCode(c: Char?): CoTAffiliation = entries.firstOrNull { it.code == c } ?: UNKNOWN
    }
}

/**
 * TAK team color palette — the 13-color set used by CivTAK / iTAK when an
 * EUD belongs to a named team (`<__group name="Red" role="Team Member"/>`).
 * Hex values sourced from the open-source TAKAware Android codebase
 * (Apache-2.0, https://github.com/FlightTactics/TAKAware) and cross-checked
 * against the CivTAK team-color legend.
 *
 * When [teamName] is present on a [CoTEvent], [CoTEvent.displayColor] returns
 * the matching ARGB color rather than the MIL-STD affiliation fallback.
 */
object TakTeamColor {
    /** ARGB color for a TAK team name, or null if the name is unrecognised. */
    fun forName(name: String?): Int? = TABLE[name?.lowercase()]

    private val TABLE: Map<String, Int> = mapOf(
        "white"      to 0xFFFFFFFF.toInt(),
        "yellow"     to 0xFFFFFF00.toInt(),
        "orange"     to 0xFFFF6600.toInt(),
        "magenta"    to 0xFFFF00FF.toInt(),
        "red"        to 0xFFFF0000.toInt(),
        "maroon"     to 0xFF800000.toInt(),
        "purple"     to 0xFF800080.toInt(),
        "dark blue"  to 0xFF00008B.toInt(),
        "blue"       to 0xFF0000FF.toInt(),
        "cyan"       to 0xFF00FFFF.toInt(),
        "teal"       to 0xFF008080.toInt(),
        "green"      to 0xFF00FF00.toInt(),
        "dark green" to 0xFF006400.toInt(),
        "brown"      to 0xFF964B00.toInt(),
    )
}

/**
 * A parsed Cursor-on-Target event. Mirrors the subset of fields OmniTAK
 * renders on the map — uid, geographic position, affiliation, callsign,
 * timestamps. Unknown fields stay raw on [rawXml] for debug inspection.
 */
@Serializable
data class CoTEvent(
    val uid: String,
    val type: String,
    val lat: Double,
    val lon: Double,
    val hae: Double = 0.0,
    val ce: Double = 9_999_999.0,
    val le: Double = 9_999_999.0,
    val timeIso: String? = null,
    val staleIso: String? = null,
    val callsign: String? = null,
    val remarks: String = "",
    val rawXml: String? = null,
    // Optional ATAK `<usericon iconsetpath="...">` value. FEMA / IC
    // markers carry `COT_MAPPING_FEMA/<category>/<kind>` here so peers
    // with the FEMA catalog render the right glyph; receivers without
    // it fall back to the friendly-ground-installation default that
    // [type] declares. See `FemaIconCatalog` and #29 / iOS PR for #13.
    val iconsetPath: String? = null,
    /** Signed ARGB from the CoT `<color argb="…"/>` element. Spot Map markers
     *  carry their swatch here (the colour rides `<color>`, not `type`), so the
     *  TAK icon registry can tint a received spot dot to match the sender. Null
     *  when the event carried no `<color>`. */
    val colorArgb: Int? = null,
    /** TAK team name from `<__group name="Red|Orange|…"/>`. Null when absent. */
    val teamName: String? = null,
    /** Course heading in degrees (0–360) from `<track course="…"/>`. Null when absent. */
    val courseHeading: Double? = null,
    /** TAK team role from `<__group role="Team Member|…"/>`. Null when absent. */
    val teamRole: String? = null,
    /**
     * #178 — wall-clock epoch-millis when this event was last received/ingested
     * (NOT the CoT `time` attribute — that's [timeIso], which is the producer's
     * clock and can lag the link). Stamped by [ContactStore.ingest]. 0 means
     * "never ingested through the store" (e.g. a freshly-built local marker
     * before ingest, or an old persisted marker decoded without this field).
     */
    val receivedAtMs: Long = 0L,
    /**
     * #180 — which transport carried this event (TAK server vs mesh), tagged at
     * the ingest point. Null when unknown (e.g. an old persisted marker, or a
     * locally-built event before the source is attached).
     */
    val source: CoTSource? = null,
    /**
     * #215: the CoT `how` attribute: how the position was produced. `m-…` means a
     * machine reported it (a device sending its own position, `m-g` = GPS); `h-…` means
     * a person entered it (a dropped marker, a report, a drawing). Null when the event
     * did not carry one or came through a path that does not read it. The max-age rule
     * uses it to tell a teammate's own position reports from markers anyone placed.
     */
    val how: String? = null,
) {
    val affiliation: CoTAffiliation
        get() {
            val parts = type.split('-')
            return CoTAffiliation.fromCode(parts.getOrNull(1)?.firstOrNull())
        }

    /**
     * ARGB display color for this contact. When [teamName] is present the TAK
     * team palette takes precedence over the MIL-STD affiliation color so that
     * Red-1, Orange-Chef, etc. match CivTAK / iTAK rendering.
     */
    val displayColor: Int
        get() = TakTeamColor.forName(teamName) ?: affiliationColor

    private val affiliationColor: Int
        get() = when (affiliation) {
            CoTAffiliation.FRIEND  -> 0xFF4ADE80.toInt()
            CoTAffiliation.HOSTILE -> 0xFFF44336.toInt()
            CoTAffiliation.NEUTRAL -> 0xFFFFC107.toInt()
            else                   -> 0xFFB39DDB.toInt()
        }
}
