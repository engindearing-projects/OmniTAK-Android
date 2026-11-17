package soy.engindearing.omnitak.mobile.data

import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * The rule that turns a ground scale into a scale bar: how long the bar is and what it says.
 * It is the twin of the iOS type of the same name, with the same rules, so the two apps print
 * the same bar for the same map and cannot drift.
 *
 * Pure: no Android and no map. The map engines supply [metersPerDp] (see [metersPerDpOnRow]),
 * and the bar is drawn exactly [Bar.widthDp] wide.
 */
object MapScale {

    /** The longest the bar may be drawn, dp. */
    const val MAX_WIDTH_DP = 110f

    /** The scale is measured between two points this far either side of the bar's centre, on the bar's own row, dp. */
    const val SAMPLE_HALF_SPAN_DP = 50f

    /** A bar for more than this (metres) means little: the scale changes too much across the screen at that zoom. */
    const val MAX_GROUND_METERS = 2_000_000.0

    const val METERS_PER_MILE = 1609.344
    const val METERS_PER_FOOT = 0.3048

    /** [widthDp] is the distance between the centres of the two end ticks; [meters] is the ground it stands for. */
    data class Bar(val widthDp: Float, val label: String, val meters: Double)

    /**
     * The bar for [metersPerDp] of ground per dp, at most [maxWidthDp] wide, in [unit]; null when
     * there is nothing sensible to draw: no scale, a scale that is not a positive number, or a bar
     * for more than [MAX_GROUND_METERS].
     *
     * The longest distance the bar may show is D = metersPerDp x maxWidthDp. Metric: kilometres
     * from D = 1000 m up, metres below. Imperial: miles from one mile up, feet below. The bar
     * shows the largest of {1, 2, 5} x 10^n of that unit that is at most D, so it is always
     * between 40 % and 100 % of [maxWidthDp] wide.
     */
    fun bar(metersPerDp: Double?, maxWidthDp: Float, unit: DistanceUnit): Bar? {
        val mpd = metersPerDp ?: return null
        if (!mpd.isFinite() || mpd <= 0.0 || !maxWidthDp.isFinite() || maxWidthDp <= 0f) return null
        val longest = mpd * maxWidthDp
        val value: Double
        val unitMeters: Double
        val name: String
        when (unit) {
            DistanceUnit.METRIC ->
                if (longest >= 1000.0) { unitMeters = 1000.0; name = "km" } else { unitMeters = 1.0; name = "m" }
            DistanceUnit.IMPERIAL ->
                if (longest >= METERS_PER_MILE) { unitMeters = METERS_PER_MILE; name = "mi" } else { unitMeters = METERS_PER_FOOT; name = "ft" }
        }
        value = niceStepAtMost(longest / unitMeters)
        // A label has one decimal below 1 m or 1 ft, so nothing under a tenth can be written.
        if (value < 0.1 - 1e-12) return null
        val meters = value * unitMeters
        if (meters > MAX_GROUND_METERS) return null
        return Bar((meters / mpd).toFloat(), label(value, name), meters)
    }

    /** "200 m", "5 km", "500 ft", "2 mi": whole numbers; below 1 m or 1 ft, one decimal. */
    private fun label(value: Double, unitName: String): String =
        if (value < 1.0) String.format(Locale.ROOT, "%.1f %s", value, unitName)
        else String.format(Locale.ROOT, "%d %s", Math.round(value), unitName)

    /** The largest of {1, 2, 5} x 10^n that is at most [x] (for x > 0). */
    internal fun niceStepAtMost(x: Double): Double {
        var e = floor(log10(x)).toInt()
        // log10 is not exact at the edges of a decade.
        while (10.0.pow(e + 1) <= x) e++
        while (10.0.pow(e) > x) e--
        val base = 10.0.pow(e)
        val m = x / base
        return when {
            m >= 5.0 -> 5.0 * base
            m >= 2.0 -> 2.0 * base
            else -> base
        }
    }

    /**
     * Metres of ground per dp along the screen row [anchorYpx], from the ground distance between the
     * two points [SAMPLE_HALF_SPAN_DP] dp either side of [anchorXpx], divided by the span between
     * them. That is the scale of the ground the bar is drawn over: on a tilted view it differs from the
     * scale at the middle of the screen, which is farther away. [project] turns a screen point in px
     * into (latitude, longitude), or null when it is not on the ground (sky above the horizon, off
     * the globe); then there is no scale.
     */
    fun metersPerDpOnRow(
        anchorXpx: Float,
        anchorYpx: Float,
        density: Float,
        project: (xPx: Float, yPx: Float) -> Pair<Double, Double>?,
    ): Double? {
        if (!anchorXpx.isFinite() || !anchorYpx.isFinite() || !(density > 0f)) return null
        val half = SAMPLE_HALF_SPAN_DP * density
        val left = project(anchorXpx - half, anchorYpx) ?: return null
        val right = project(anchorXpx + half, anchorYpx) ?: return null
        val meters = GeoMath.haversineMeters(left.first, left.second, right.first, right.second)
        val perDp = meters / (2.0 * SAMPLE_HALF_SPAN_DP)
        return if (perDp.isFinite() && perDp > 0.0) perDp else null
    }
}
