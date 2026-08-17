package soy.engindearing.omnitak.mobile.data

/**
 * Military echelon / organizational size (#159, ported from the iOS
 * `EchelonLevel`). Declared smallest -> largest, so the enum's natural order
 * (ordinal) is the doctrinal order and `Echelon.TEAM < Echelon.CORPS` holds.
 *
 * [amplifier] is the NATO APP-6 echelon modifier drawn above a unit symbol
 * (the "modifier" rendered on the self-marker / contacts). [code] is the wire
 * value (matches the iOS `rawValue`) carried in PPLI and persisted with the
 * self marker.
 */
enum class Echelon(
    val code: String,
    val displayName: String,
    val amplifier: String,
    val standardStrength: Int,
) {
    TEAM("team", "Team", "•", 4),
    SQUAD("squad", "Squad", "••", 9),
    PLATOON("platoon", "Platoon", "•••", 40),
    COMPANY("company", "Company", "I", 150),
    BATTALION("battalion", "Battalion", "II", 700),
    BRIGADE("brigade", "Brigade", "III", 3500),
    DIVISION("division", "Division", "XX", 15000),
    CORPS("corps", "Corps", "XXX", 45000);

    companion object {
        /** Parse a wire [code] (case-insensitive); null if it isn't a known echelon. */
        fun fromCode(code: String?): Echelon? =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
    }
}
