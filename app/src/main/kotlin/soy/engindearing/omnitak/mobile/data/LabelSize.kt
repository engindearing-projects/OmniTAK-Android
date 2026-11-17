package soy.engindearing.omnitak.mobile.data

import kotlin.math.abs

/**
 * #213 - the "Label size" setting: how big the names under map markers and the
 * position box are drawn, as a whole percent of the size the app has always used.
 *
 * Free of Android types so the rules can be unit tested on the JVM.
 */
object LabelSize {
    /** The choices Settings offers, smallest first. */
    val CHOICES: List<Int> = listOf(80, 100, 120, 140, 160)

    /** Today's size. A fresh install, and a missing stored value, use it. */
    const val DEFAULT_PERCENT = 100

    /**
     * The choice nearest to [percent]. A stored value that is not one of the
     * choices (a hand edit, a bad import) reads as the closest one, so 0 or a
     * negative number reads as 80 and 999 reads as 160: never unreadably small
     * or huge. A value exactly halfway between two choices rounds up.
     */
    fun nearestChoice(percent: Int): Int =
        CHOICES.minWithOrNull(
            compareBy<Int> { abs(it.toLong() - percent.toLong()) }.thenByDescending { it },
        ) ?: DEFAULT_PERCENT

    /** The multiplier for [percent]: 1.0 at 100%. */
    fun scaleOf(percent: Int): Float = nearestChoice(percent) / 100f

    /**
     * The size to draw a label at: the design size x the phone's font size
     * setting x the label size. [phoneFontScale] is `Configuration.fontScale`
     * (1.0 at the default font size); a value that is not positive counts as 1.
     */
    fun size(designSize: Float, phoneFontScale: Float, percent: Int): Float =
        designSize * fontScaleOrOne(phoneFontScale) * scaleOf(percent)

    /**
     * [size] for a design size of 1: the one multiplier that every dimension of
     * a name baked into a marker bitmap follows.
     */
    fun factor(phoneFontScale: Float, percent: Int): Float = size(1f, phoneFontScale, percent)

    private fun fontScaleOrOne(fontScale: Float): Float =
        if (fontScale > 0f && fontScale.isFinite()) fontScale else 1f
}
