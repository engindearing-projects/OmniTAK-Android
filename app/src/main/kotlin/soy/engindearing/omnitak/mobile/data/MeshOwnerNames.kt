package soy.engindearing.omnitak.mobile.data

/**
 * Helpers for translating an OmniTAK operator callsign into Meshtastic
 * owner long/short names. Pure so unit tests don't need Android.
 *
 * Meshtastic radios truncate `shortName` to 4 characters in their LCD UI
 * and channel previews. Operators name themselves "ALPHA-1" / "ENGIE-3" —
 * the trailing digits are the identifying part. We keep up to 3 leading
 * letters and append trailing digits (or just take the first 4
 * alphanumerics when there's no letter/digit split). Always uppercase.
 *
 * Examples (the tests pin these):
 *   "ENGIE-1"     → ("ENGIE-1",     "ENG1")
 *   "ALPHA-7"     → ("ALPHA-7",     "ALP7")
 *   "Alphabravo-12" → ("Alphabravo-12", "AL12")
 *   "ops"         → ("ops",         "OPS")
 *   "OMNI"        → ("OMNI",        "OMNI")
 *   ""            → null (no sync)
 */
object MeshOwnerNames {
    /** Returns long/short pair, or null when the input is unusable. */
    fun derive(callsign: String): Pair<String, String>? {
        val trimmed = callsign.trim()
        if (trimmed.isEmpty()) return null
        val cleaned = trimmed.filter { it.isLetterOrDigit() }.uppercase()
        if (cleaned.isBlank()) return null

        val trailingDigits = cleaned.takeLastWhile { it.isDigit() }
        val leadingLetters = cleaned.takeWhile { it.isLetter() }

        val short = when {
            // Letters-then-digits pattern: preserve up to 3 letters and
            // tack on trailing digits, capped at 4 total.
            leadingLetters.isNotEmpty() && trailingDigits.isNotEmpty() -> {
                val keepLetters = leadingLetters.take(4 - trailingDigits.length.coerceAtMost(3))
                val keepDigits = trailingDigits.take(4 - keepLetters.length)
                (keepLetters + keepDigits).take(4)
            }
            // Otherwise fall back to first 4 alphanumeric characters.
            else -> cleaned.take(4)
        }.ifBlank { return null }

        return trimmed to short
    }
}
