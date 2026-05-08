package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the closed-test fix for the May-8 coord-format report (#13):
 * the SelfPositionCard and "Add @ …" toast both show MGRS-style numbers
 * even after the operator picked UTM in Settings. The formatter now
 * emits the canonical `<zone><band> <easting>mE <northing>mN` shape so
 * MGRS and UTM are visibly different to the operator.
 */
class CoordFormatterTest {

    @Test fun utm_includes_band_letter_and_meter_suffix() {
        // Spokane WA — reporter's location.
        val s = CoordFormatter.position(47.57662, -117.37438, CoordFormat.UTM)
        // "<zone><band> <easting>mE <northing>mN" e.g. "11T 471845mE 5269313mN"
        assertTrue("UTM should start with `11T`, got: $s", s.startsWith("11T "))
        assertTrue("UTM should carry mE/mN suffixes, got: $s",
            s.contains("mE") && s.contains("mN"))
        // Sanity: must NOT look like MGRS (no 100km square letters glued in).
        assertTrue("UTM must not look like MGRS, got: $s",
            !s.matches(Regex("\\d+[A-Z] ?[A-Z]{2}.*")))
    }

    @Test fun mgrs_round_trip_keeps_known_format() {
        val s = CoordFormatter.position(47.57662, -117.37438, CoordFormat.MGRS)
        // mil.nga.mgrs returns e.g. "11TMN7184569312"
        assertTrue("MGRS should be `11TMN…`, got: $s", s.startsWith("11TMN"))
    }

    @Test fun latlon_decimal_keeps_five_places() {
        val s = CoordFormatter.position(47.57662, -117.37438, CoordFormat.LATLON_DECIMAL)
        assertEquals("47.57662, -117.37438", s)
    }

    @Test fun utm_southern_hemisphere_zone_boundary() {
        // Just south of the equator, near zone 30/31 boundary at 0° longitude.
        val s = CoordFormatter.position(-0.5, -0.5, CoordFormat.UTM)
        assertTrue("UTM south should start `30M`, got: $s", s.startsWith("30M "))
    }
}
