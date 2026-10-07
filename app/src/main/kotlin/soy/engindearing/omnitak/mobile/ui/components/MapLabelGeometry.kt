package soy.engindearing.omnitak.mobile.ui.components

import kotlin.math.roundToInt

/**
 * #213 - the dimensions of a name baked into a marker bitmap, in bitmap pixels.
 *
 * Every dimension follows one factor (the phone's font size x the Label size
 * setting), so the proportions between the text, its outline, the strip it sits
 * in and the gap to the icon are the same at every size: a larger name stays
 * inside its strip and does not reach the icon. At a factor of 1 the text, outline,
 * strip and gap are the sizes the app drew before the setting existed.
 *
 * The icon itself is not part of this and keeps its size.
 *
 * #273 - where the icon sits. MapLibre (11.8.0, measured on a device) anchors a
 * marker bitmap at its CENTRE, not at its bottom edge as the old comments said. The
 * point that marks the coordinate (a contact's dot centre, a pushpin's tip) must
 * therefore be the bitmap's centre, or it is drawn off its coordinate. [padsToCentre]
 * works out the transparent rows to add above or below to make that so; [topPad] and
 * [bottomPad] are its answer for each shape, at each size. A bitmap with a name
 * strip on one side of the icon needs a transparent strip of the same height on the
 * other side. That strip is part of the marker, so it takes taps like the rest of it.
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
    /** Height of the strip the text sits in; the icon starts where it ends. Exact, scaled. */
    val band: Float,
    /** Text baseline, measured down from the top of the strip. */
    val baseline: Float,
    /** Transparent rows above everything else in the bitmap. */
    val topPad: Int,
    /** Transparent rows below everything else in the bitmap. */
    val bottomPad: Int,
) {
    /** The strip height actually drawn: a whole number of pixels, so the centre of the bitmap lands on a pixel line. */
    val bandPx: Int get() = band.roundToInt()

    /** Transparent rows to add above and below a bitmap. At most one of the two is not zero. */
    data class Pads(val top: Int, val bottom: Int)

    /** How a contact pin's bitmap is laid out, top to bottom: [nameTop], the name strip, the dot, then any padding. */
    data class ContactLayout(
        val height: Int,
        /** Where the dot's centre is, from the top of the bitmap. It is always [height] / 2. */
        val dotCentreY: Int,
        /** Height of the name strip: 0 with no name. */
        val stripHeight: Int,
        /** Rows above the name strip. */
        val nameTop: Int,
    )

    /**
     * The layout of a contact pin's bitmap. With no name there is no strip and nothing to
     * pad: the bitmap is the dot, already centred on the coordinate.
     */
    fun contactLayout(hasName: Boolean): ContactLayout {
        val strip = if (hasName) bandPx else 0
        val pads = if (hasName) Pads(topPad, bottomPad) else Pads(0, 0)
        val height = pads.top + strip + CONTACT_DOT_DIAMETER + pads.bottom
        return ContactLayout(
            height = height,
            dotCentreY = pads.top + strip + CONTACT_DOT_DIAMETER / 2,
            stripHeight = strip,
            nameTop = pads.top,
        )
    }

    companion object {
        private const val CONTACT_BAND = 40f
        private const val KML_BAND = 46f

        /** A contact's dot: the circle's radius, and the ring drawn around it, in px. They do not scale. */
        const val CONTACT_DOT_RADIUS = 16
        const val CONTACT_DOT_RING = 3

        /** The dot with its ring: the part of a contact pin below the name strip. */
        const val CONTACT_DOT_DIAMETER = (CONTACT_DOT_RADIUS + CONTACT_DOT_RING) * 2

        /**
         * The transparent rows to add so that the point [anchorFromTop] rows down a bitmap that is
         * [contentHeight] rows tall ends up exactly at its vertical centre, which is where MapLibre
         * puts the coordinate. If the anchor is above the middle the padding goes on top, if it is
         * below the middle it goes underneath, and nothing is added when it is already central.
         */
        fun padsToCentre(contentHeight: Int, anchorFromTop: Int): Pads {
            val excess = contentHeight - 2 * anchorFromTop
            return if (excess >= 0) Pads(top = excess, bottom = 0) else Pads(top = 0, bottom = -excess)
        }

        /** A contact pin: the name sits in a strip above the dot, and the dot's centre is the point on the coordinate. */
        fun forContact(factor: Float): MapLabelGeometry {
            val band = CONTACT_BAND * factor
            val bandPx = band.roundToInt()
            val pads = padsToCentre(
                contentHeight = bandPx + CONTACT_DOT_DIAMETER,
                anchorFromTop = bandPx + CONTACT_DOT_DIAMETER / 2,
            )
            return MapLabelGeometry(
                textSize = 30f * factor,
                haloStroke = 6f * factor,
                padding = 12f * factor,
                band = band,
                baseline = 30f * factor,
                topPad = pads.top,
                bottomPad = pads.bottom,
            )
        }

        /** A KML pin: the name sits in a strip below the pushpin, and the pushpin's tip is the point on the coordinate. */
        fun forKmlPin(factor: Float): MapLabelGeometry {
            val band = KML_BAND * factor
            val pads = padsToCentre(
                contentHeight = PUSHPIN_HEIGHT + band.roundToInt(),
                anchorFromTop = PUSHPIN_TIP_Y,
            )
            return MapLabelGeometry(
                textSize = 30f * factor,
                haloStroke = 6f * factor,
                padding = 12f * factor,
                band = band,
                baseline = 34f * factor,
                topPad = pads.top,
                bottomPad = pads.bottom,
            )
        }

        /** The bare KML pushpin, with no name: only its tip matters. */
        fun forBareKmlPin(): Pads = padsToCentre(contentHeight = PUSHPIN_HEIGHT, anchorFromTop = PUSHPIN_TIP_Y)
    }
}
