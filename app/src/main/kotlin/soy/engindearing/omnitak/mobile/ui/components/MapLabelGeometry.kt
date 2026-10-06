package soy.engindearing.omnitak.mobile.ui.components

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * #213 - the dimensions of a name baked into a marker bitmap, in bitmap pixels.
 *
 * Every dimension follows one factor (the phone's font size x the Label size
 * setting), so the proportions between the text, its outline, the strip it sits
 * in and the gap to the icon are the same at every size: a larger name stays
 * inside its strip and does not reach the icon. At a factor of 1 these are the
 * sizes the app drew before the setting existed, with no padding.
 *
 * The icon itself is not part of this and keeps its size.
 *
 * Position: MapLibre (11.8.0, measured on a device) anchors a marker bitmap at
 * its CENTRE, not at its bottom edge as the old comments said. A strip that grew
 * would therefore pull the icon away from the point it marks (and shrinking it
 * would push it the other way). [topPad] and [bottomPad] are transparent rows on
 * the side away from the strip that keep the bitmap's centre at the same place
 * relative to the icon as at a factor of 1, so the icon stays where it always
 * was at every size.
 *
 * No Android types, so the rules can be unit tested on the JVM.
 */
internal data class MapLabelGeometry(
    /** Paint text size. */
    val textSize: Float,
    /** Stroke width of the dark outline drawn behind the white text. */
    val haloStroke: Float,
    /** Space left and right of the text. */
    val padding: Float,
    /** Height of the strip the text sits in; the icon starts where it ends. */
    val band: Float,
    /** Text baseline, measured down from the top of the strip. */
    val baseline: Float,
    /** Transparent rows above everything else in the bitmap. */
    val topPad: Int,
    /** Transparent rows below everything else in the bitmap. */
    val bottomPad: Int,
) {
    companion object {
        private const val CONTACT_BAND = 40f
        private const val KML_BAND = 46f

        /** A contact pin: the name sits in a strip above the dot. */
        fun forContact(factor: Float): MapLabelGeometry {
            val band = CONTACT_BAND * factor
            // Strip taller than today: pad below the dot. Shorter: pad above the strip.
            val grow = (band - CONTACT_BAND).roundToInt()
            return MapLabelGeometry(
                textSize = 30f * factor,
                haloStroke = 6f * factor,
                padding = 12f * factor,
                band = band,
                baseline = 30f * factor,
                topPad = max(0, -grow),
                bottomPad = max(0, grow),
            )
        }

        /** A KML pin: the name sits in a strip below the pushpin. */
        fun forKmlPin(factor: Float): MapLabelGeometry {
            val band = KML_BAND * factor
            // Strip taller than today: pad above the pushpin. Shorter: pad below the strip.
            val grow = (band - KML_BAND).roundToInt()
            return MapLabelGeometry(
                textSize = 30f * factor,
                haloStroke = 6f * factor,
                padding = 12f * factor,
                band = band,
                baseline = 34f * factor,
                topPad = max(0, grow),
                bottomPad = max(0, -grow),
            )
        }
    }
}
