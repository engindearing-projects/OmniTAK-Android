package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/**
 * The scale bar's rule, the twin of the iOS type: how long the bar is and what it says for a ground
 * scale, and how the scale is taken from a map. The two apps print the same bar for the same map
 * because the rules are copied; these tests are the copy.
 */
class MapScaleTest {

    private val metric = DistanceUnit.METRIC
    private val imperial = DistanceUnit.IMPERIAL

    /** What MapLibre shows at web-mercator [zoom] at [latitude], metres per dp (512 dp tiles). */
    private fun mapLibreMetersPerDp(zoom: Double, latitude: Double = 0.0): Double =
        40_075_016.686 * cos(Math.toRadians(latitude)) / (512.0 * 2.0.pow(zoom))

    // MARK: nothing to draw

    @Test fun no_scale_no_bar() {
        assertNull(MapScale.bar(null, 110f, metric))
    }

    @Test fun a_scale_that_is_not_a_positive_number_gives_no_bar() {
        for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull("$bad", MapScale.bar(bad, 110f, metric))
            assertNull("$bad", MapScale.bar(bad, 110f, imperial))
        }
    }

    @Test fun no_room_gives_no_bar() {
        assertNull(MapScale.bar(2.0, 0f, metric))
        assertNull(MapScale.bar(2.0, -5f, metric))
        assertNull(MapScale.bar(2.0, Float.NaN, metric))
    }

    // MARK: the ground truth

    @Test fun zoom_15_on_the_equator_is_a_200_m_bar_about_84_dp_long() {
        // MapLibre at zoom 15 on the equator: 40075016.686 / (512 * 2^15) = 2.389 m per dp. With 110 dp
        // to use, the longest distance is 262.8 m and the largest 1-2-5 step under it is 200 m.
        val mpd = 40_075_016.686 / (512.0 * 2.0.pow(15))
        val bar = MapScale.bar(mpd, 110f, metric)!!
        assertEquals("200 m", bar.label)
        assertEquals(200.0, bar.meters, 1e-9)
        assertEquals(200.0 / mpd, bar.widthDp.toDouble(), 0.01)
        assertEquals(83.7, bar.widthDp.toDouble(), 0.05)
    }

    // MARK: the steps and the units

    @Test fun metres_below_a_kilometre() {
        assertEquals("5 m", MapScale.bar(0.09, 110f, metric)!!.label)      // D = 9.9 m
        assertEquals("20 m", MapScale.bar(0.2, 110f, metric)!!.label)      // D = 22 m
        assertEquals("50 m", MapScale.bar(0.5, 110f, metric)!!.label)      // D = 55 m
        assertEquals("100 m", MapScale.bar(1.0, 110f, metric)!!.label)     // D = 110 m
        assertEquals("200 m", MapScale.bar(1.9, 110f, metric)!!.label)     // D = 209 m
        assertEquals("200 m", MapScale.bar(2.5, 110f, metric)!!.label)     // D = 275 m
        assertEquals("500 m", MapScale.bar(5.0, 110f, metric)!!.label)     // D = 550 m
    }

    @Test fun kilometres_from_a_kilometre() {
        assertEquals("1 km", MapScale.bar(10.0, 110f, metric)!!.label)     // D = 1100 m
        assertEquals("2 km", MapScale.bar(25.0, 110f, metric)!!.label)     // D = 2750 m
        assertEquals("5 km", MapScale.bar(50.0, 110f, metric)!!.label)     // D = 5500 m
        assertEquals("1000 km", MapScale.bar(10_000.0, 110f, metric)!!.label)
    }

    @Test fun the_switch_between_metres_and_kilometres_is_at_a_kilometre() {
        assertEquals("500 m", MapScale.bar(9.99, 100f, metric)!!.label)    // D = 999 m
        assertEquals("1 km", MapScale.bar(10.0, 100f, metric)!!.label)     // D = 1000 m exactly
    }

    @Test fun feet_below_a_mile() {
        assertEquals("100 ft", MapScale.bar(0.3, 110f, imperial)!!.label)  // D = 33 m = 108 ft
        assertEquals("500 ft", MapScale.bar(1.5, 110f, imperial)!!.label)  // D = 165 m = 541 ft
        assertEquals("5000 ft", MapScale.bar(14.0, 110f, imperial)!!.label) // D = 1540 m = 5053 ft
    }

    @Test fun miles_from_a_mile() {
        assertEquals("1 mi", MapScale.bar(1609.35 / 110.0, 110f, imperial)!!.label)
        assertEquals("2 mi", MapScale.bar(30.0, 110f, imperial)!!.label)   // D = 3300 m = 2.05 mi
        assertEquals("5 mi", MapScale.bar(80.0, 110f, imperial)!!.label)   // D = 8800 m = 5.47 mi
    }

    @Test fun a_distance_just_under_a_mile_is_still_feet() {
        val bar = MapScale.bar((1609.344 - 0.01) / 110.0, 110f, imperial)!!
        assertEquals("5000 ft", bar.label)
        assertEquals(5000 * 0.3048, bar.meters, 1e-9)
    }

    @Test fun below_a_metre_or_a_foot_the_label_has_one_decimal() {
        // 0.01 m per dp: a bar of 110 dp may stand for 1.1 m, and a narrower one for less.
        assertEquals("1 m", MapScale.bar(0.01, 110f, metric)!!.label)
        assertEquals("0.5 m", MapScale.bar(0.01, 60f, metric)!!.label)
        assertEquals("0.2 m", MapScale.bar(0.01, 30f, metric)!!.label)
        assertEquals("0.1 m", MapScale.bar(0.01, 15f, metric)!!.label)
        assertEquals("0.5 ft", MapScale.bar(0.003, 60f, imperial)!!.label) // D = 0.18 m = 0.59 ft
    }

    @Test fun a_bar_for_less_than_a_tenth_has_no_label_to_write() {
        assertNull(MapScale.bar(0.0005, 110f, metric))                      // D = 5.5 cm
    }

    // MARK: size

    @Test fun the_bar_is_always_between_40_and_100_percent_of_the_room() {
        // From 0.01 m per dp to 20,000 m per dp, 50 steps a decade.
        var mpd = 0.01
        var checked = 0
        while (mpd <= 20_000.0) {
            for (unit in listOf(metric, imperial)) {
                for (room in listOf(80f, 110f, 160f)) {
                    val bar = MapScale.bar(mpd, room, unit)
                    if (bar != null) {
                        assertTrue("$mpd $unit $room: ${bar.widthDp}", bar.widthDp >= 0.4f * room - 1e-3f)
                        assertTrue("$mpd $unit $room: ${bar.widthDp}", bar.widthDp <= room + 1e-3f)
                        checked++
                    }
                }
            }
            mpd *= 1.0471285480508996   // 10^(1/50)
        }
        assertTrue("checked $checked", checked > 1000)
    }

    @Test fun the_bar_is_exactly_as_long_as_the_distance_it_says() {
        var mpd = 0.01
        while (mpd <= 20_000.0) {
            for (unit in listOf(metric, imperial)) {
                val bar = MapScale.bar(mpd, 110f, unit) ?: continue
                // Drawn length (dp) x ground per dp = the ground the label stands for.
                assertEquals("$mpd $unit", bar.meters, bar.widthDp.toDouble() * mpd, bar.meters * 1e-5)
            }
            mpd *= 1.0471285480508996
        }
    }

    @Test fun the_label_is_the_distance_in_its_unit() {
        // "5 km" is 5000 m, "2 mi" is 3218.688 m, "500 ft" is 152.4 m.
        assertEquals(5000.0, MapScale.bar(50.0, 110f, metric)!!.meters, 1e-9)
        assertEquals(2 * 1609.344, MapScale.bar(30.0, 110f, imperial)!!.meters, 1e-9)
        assertEquals(500 * 0.3048, MapScale.bar(1.5, 110f, imperial)!!.meters, 1e-9)
    }

    // MARK: too far out to mean anything

    @Test fun a_bar_for_more_than_2000_km_is_not_drawn() {
        assertNull(MapScale.bar(50_000.0, 110f, metric))                   // D = 5500 km would be a 5000 km bar
        val longest = MapScale.bar(20_000.0, 110f, metric)!!               // D = 2200 km: a 2000 km bar is the longest
        assertEquals("2000 km", longest.label)
        assertEquals(2_000_000.0, longest.meters, 1e-6)
    }

    @Test fun the_limit_is_in_ground_distance_for_miles_too() {
        assertNull(MapScale.bar(30_000.0, 110f, imperial))                 // a 2000 mi bar is 3219 km
        assertEquals("1000 mi", MapScale.bar(15_000.0, 110f, imperial)!!.label) // 1609 km
    }

    // MARK: taking the scale from a map, on the bar's own row

    /**
     * A flat web-mercator view [mercatorMetersPerPx] metres of the projection per px, centred on
     * latitude [centerLat], longitude 0, on a [w] x [h] px screen.
     */
    private fun mercator(
        mercatorMetersPerPx: Double,
        w: Float,
        h: Float,
        centerLat: Double = 0.0,
    ): (Float, Float) -> Pair<Double, Double>? = { x, y ->
        val r = 6_378_137.0
        val cy = r * ln(tan(Math.PI / 4 + Math.toRadians(centerLat) / 2))
        val mx = (x - w / 2) * mercatorMetersPerPx
        val my = cy - (y - h / 2) * mercatorMetersPerPx
        atan(sinh(my / r)).let { Math.toDegrees(it) to Math.toDegrees(mx / r) }
    }

    @Test fun on_an_upright_map_the_row_scale_is_the_map_scale() {
        // Zoom 15 at the equator is 2.389 m per dp; on a 2.625 density screen that is 0.91 m per px.
        val density = 2.625f
        val mpd = mapLibreMetersPerDp(15.0)
        val got = MapScale.metersPerDpOnRow(200f, 2000f, density, mercator(mpd / density, 1080f, 2400f))!!
        // The haversine on the mean earth radius is 0.11 % shorter than the mercator sphere's metre.
        assertEquals(mpd, got, mpd * 0.002)
    }

    @Test fun on_an_upright_map_every_row_gives_the_same_scale() {
        val density = 2.625f
        val project = mercator(mapLibreMetersPerDp(15.0) / density, 1080f, 2400f)
        val top = MapScale.metersPerDpOnRow(540f, 300f, density, project)!!
        val bottom = MapScale.metersPerDpOnRow(540f, 2200f, density, project)!!
        assertEquals(top, bottom, top * 1e-4)
    }

    @Test fun the_scale_is_taken_between_points_50_dp_either_side_of_the_anchor() {
        val seen = mutableListOf<Pair<Float, Float>>()
        MapScale.metersPerDpOnRow(300f, 1500f, 2f) { x, y -> seen += x to y; 0.0 to 0.0 }
        assertEquals(listOf(200f to 1500f, 400f to 1500f), seen)           // 300 -/+ 50 dp x 2 px per dp, on one row
    }

    @Test fun the_scale_follows_the_bars_row_not_the_middle_of_the_screen() {
        // A tilted view: the ground is nearer at the bottom of the screen, so a dp is fewer metres there.
        // This made-up view is 3 m per px at the top row and 1 m per px at the bottom one (y = 2000).
        val project: (Float, Float) -> Pair<Double, Double>? = { x, y ->
            val metersPerPx = 1.0 + 2.0 * (1.0 - y / 2000.0)
            0.0 to Math.toDegrees(x * metersPerPx / 6_371_008.8)
        }
        val atTheBar = MapScale.metersPerDpOnRow(150f, 1900f, 1f, project)!!
        val atTheMiddle = MapScale.metersPerDpOnRow(150f, 1000f, 1f, project)!!
        assertEquals(1.1, atTheBar, 0.01)
        assertEquals(2.0, atTheMiddle, 0.01)
    }

    @Test fun a_point_that_is_not_on_the_ground_means_no_scale() {
        // Sky above the horizon, or off the globe: no scale, not a scale from the one point that is there.
        assertNull(MapScale.metersPerDpOnRow(200f, 900f, 1f) { x, _ -> if (x < 150f) null else 0.0 to 1.0 })
        assertNull(MapScale.metersPerDpOnRow(200f, 900f, 1f) { x, _ -> if (x > 150f) null else 0.0 to 1.0 })
    }

    @Test fun a_degenerate_measurement_means_no_scale() {
        assertNull(MapScale.metersPerDpOnRow(200f, 900f, 1f) { _, _ -> 10.0 to 10.0 })   // both on the same spot
        assertNull(MapScale.metersPerDpOnRow(Float.NaN, 900f, 1f) { _, _ -> 0.0 to 0.0 })
        assertNull(MapScale.metersPerDpOnRow(200f, 900f, 0f) { _, _ -> 0.0 to 0.0 })
    }

    @Test fun density_only_turns_dp_into_px() {
        val mpd = 2.0
        for (density in listOf(1f, 2.625f, 3.5f)) {
            val got = MapScale.metersPerDpOnRow(500f, 1500f, density, mercator(mpd / density, 1000f, 2000f))!!
            assertEquals("density $density", mpd, got, mpd * 0.002)
        }
    }

    @Test fun at_60_north_a_dp_is_half_the_ground_it_is_at_the_equator() {
        val density = 2.625f
        val equator = mapLibreMetersPerDp(15.0)
        val got = MapScale.metersPerDpOnRow(540f, 1200f, density, mercator(equator / density, 1080f, 2400f, centerLat = 60.0))!!
        // A mercator metre is cos(60) = 0.5 of a ground metre there.
        assertEquals(equator * 0.5, got, equator * 0.5 * 0.004)
    }
}
