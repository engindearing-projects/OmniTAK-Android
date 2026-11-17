package soy.engindearing.omnitak.mobile.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * The distance readout of the Measure banner. It was always metric whatever Settings > Units said.
 * The imperial side follows the scale bar's rule (feet below one mile, miles from it) so the two
 * readouts on the map change unit at the same distance.
 */
class GeoMathTest {

    private var locale: Locale = Locale.getDefault()

    @Before fun fixLocale() {
        locale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After fun restoreLocale() = Locale.setDefault(locale)

    // MARK: metric: as it always was

    @Test fun metric_is_metres_below_a_kilometre() {
        assertEquals("0 m", GeoMath.formatDistance(0.0, DistanceUnit.METRIC))
        assertEquals("1 m", GeoMath.formatDistance(1.0, DistanceUnit.METRIC))
        assertEquals("999 m", GeoMath.formatDistance(999.0, DistanceUnit.METRIC))
    }

    @Test fun metric_is_kilometres_with_two_decimals_below_ten_and_one_from_ten() {
        assertEquals("1.00 km", GeoMath.formatDistance(1000.0, DistanceUnit.METRIC))
        assertEquals("1.23 km", GeoMath.formatDistance(1234.0, DistanceUnit.METRIC))
        assertEquals("9.50 km", GeoMath.formatDistance(9500.0, DistanceUnit.METRIC))
        assertEquals("12.3 km", GeoMath.formatDistance(12_345.0, DistanceUnit.METRIC))
    }

    @Test fun the_readout_without_a_unit_is_still_metric() {
        for (m in listOf(0.0, 42.0, 999.0, 1000.0, 7500.0, 12_345.0)) {
            assertEquals(GeoMath.formatDistance(m, DistanceUnit.METRIC), GeoMath.formatDistance(m))
        }
    }

    // MARK: imperial

    @Test fun imperial_is_feet_below_a_mile() {
        assertEquals("0 ft", GeoMath.formatDistance(0.0, DistanceUnit.IMPERIAL))
        assertEquals("328 ft", GeoMath.formatDistance(100.0, DistanceUnit.IMPERIAL))
        assertEquals("3281 ft", GeoMath.formatDistance(1000.0, DistanceUnit.IMPERIAL))
        assertEquals("5279 ft", GeoMath.formatDistance(1609.0, DistanceUnit.IMPERIAL))
    }

    @Test fun imperial_is_miles_from_one_mile_with_two_decimals_below_ten_and_one_from_ten() {
        assertEquals("1.00 mi", GeoMath.formatDistance(MapScale.METERS_PER_MILE, DistanceUnit.IMPERIAL))
        assertEquals("3.11 mi", GeoMath.formatDistance(5000.0, DistanceUnit.IMPERIAL))
        assertEquals("10.0 mi", GeoMath.formatDistance(10 * MapScale.METERS_PER_MILE, DistanceUnit.IMPERIAL))
        assertEquals("12.4 mi", GeoMath.formatDistance(20_000.0, DistanceUnit.IMPERIAL))
    }

    @Test fun a_kilometre_is_not_shown_as_a_kilometre_to_an_imperial_operator() {
        for (m in listOf(10.0, 500.0, 1000.0, 1609.0, 2000.0, 50_000.0)) {
            val text = GeoMath.formatDistance(m, DistanceUnit.IMPERIAL)
            assertEquals("$text is in feet or miles", true, text.endsWith(" ft") || text.endsWith(" mi"))
        }
    }

    @Test fun the_two_units_say_the_same_distance() {
        // 5 km both ways: 5.00 km and about 3.11 mi.
        assertEquals("5.00 km", GeoMath.formatDistance(5000.0, DistanceUnit.METRIC))
        assertEquals("3.11 mi", GeoMath.formatDistance(5000.0, DistanceUnit.IMPERIAL))
    }
}
