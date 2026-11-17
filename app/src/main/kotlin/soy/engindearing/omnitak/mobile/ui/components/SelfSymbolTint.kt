package soy.engindearing.omnitak.mobile.ui.components

import kotlin.math.pow

/**
 * How the operator's own marker takes its team colour, as plain arithmetic on ARGB ints (the
 * non-premultiplied ints Bitmap.getPixels gives and Bitmap.createBitmap takes), so it can be tested
 * on int arrays.
 *
 * The marker is the MIL-STD-2525 friendly ground unit symbol (assets/milstd/SFGPUC-----.svg): a
 * black outline round a light blue fill, with a black label inside. It used to be passed through a
 * SRC_IN colour filter, which replaces every opaque pixel with the team colour: the outline and the
 * label disappeared and the operator saw a plain rectangle.
 *
 * [tint] keeps them. Each pixel's brightness, relative to the brightness of the frame's own fill,
 * scales between the symbol's line colour and the team colour: the fill (brightness 1) is the full
 * team colour, the lines (brightness 0) are black, and the anti-aliased edge between them is the shade
 * in between. Alpha is untouched, so the stale (faded) variant and the echelon amplifier, which work on
 * the result, are as they were.
 *
 * Black lines on a dark team colour (Dark Blue is 1.4:1 against black) vanish into the fill, and a
 * dark symbol vanishes on a dark map, so for a dark team colour ([usesLightLines]) the lines are
 * white instead: the same frame and label, in the colour that stands out from the fill.
 */
internal object SelfSymbolTint {

    // The frame's own fill, rgb(128, 224, 255): what the team colour stands in for.
    private const val FILL_R = 128
    private const val FILL_G = 224
    private const val FILL_B = 255
    private const val FILL_LUMA = 0.299f * FILL_R + 0.587f * FILL_G + 0.114f * FILL_B

    /** A team colour darker than this (WCAG relative luminance, 0 black to 1 white) gets light lines. */
    private const val LIGHT_LINES_BELOW_LUMINANCE = 0.15

    private fun luma(r: Int, g: Int, b: Int): Float = 0.299f * r + 0.587f * g + 0.114f * b

    private fun linear(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    /**
     * True for a team colour a black line cannot be seen on, or a dark symbol on a dark map: Dark
     * Blue, Blue, Maroon, Purple, Dark Green and Brown. The others keep black lines, which stand out
     * from the fill and from both a pale and a dark map.
     */
    fun usesLightLines(team: Int): Boolean {
        val l = 0.2126 * linear((team shr 16) and 0xFF) + 0.7152 * linear((team shr 8) and 0xFF) + 0.0722 * linear(team and 0xFF)
        return l < LIGHT_LINES_BELOW_LUMINANCE
    }

    /** [source] in the team colour [team] (alpha ignored): the fill in the full colour, the lines black or, for a dark colour, white. */
    fun tint(source: IntArray, team: Int): IntArray {
        val tr = (team shr 16) and 0xFF
        val tg = (team shr 8) and 0xFF
        val tb = team and 0xFF
        val lineBase = if (usesLightLines(team)) 255 else 0
        fun channel(team: Int, k: Float): Int = (lineBase + (team - lineBase) * k + 0.5f).toInt().coerceIn(0, 255)
        return IntArray(source.size) { i ->
            val p = source[i]
            val a = p ushr 24
            if (a == 0) {
                0
            } else {
                val k = (luma((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF) / FILL_LUMA).coerceIn(0f, 1f)
                (a shl 24) or (channel(tr, k) shl 16) or (channel(tg, k) shl 8) or channel(tb, k)
            }
        }
    }
}
