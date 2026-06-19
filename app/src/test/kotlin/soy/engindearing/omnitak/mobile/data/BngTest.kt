package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * #157 — British National Grid (OSGB36). Ported from iOS BNGConverter. The
 * Transverse Mercator projection is checked against the worked example in the
 * Ordnance Survey "Guide to coordinate systems in Great Britain".
 */
class BngTest {

    @Test fun tm_projection_matches_OS_worked_example() {
        // OSGB36 lat 52.6575703 N, lon 1.7179215 E -> E 651409.903, N 313177.270
        val en = Bng.osgb36ToEastingNorthing(52.6575703, 1.7179215)
        assertEquals(651409.903, en[0], 0.5)
        assertEquals(313177.270, en[1], 0.5)
    }

    @Test fun grid_square_lookup() {
        assertEquals("TG", Bng.gridSquare(651409.0, 313177.0)) // OS example square
        assertEquals("SV", Bng.gridSquare(0.0, 0.0))           // false-origin square
        assertEquals("TQ", Bng.gridSquare(530000.0, 180000.0)) // London
        assertEquals("NT", Bng.gridSquare(325000.0, 674000.0)) // Edinburgh
        assertNull("off the east edge of the grid", Bng.gridSquare(800000.0, 0.0))
    }

    @Test fun wgs84_to_gridref_london_is_TQ() {
        val ref = Bng.wgs84ToGridRef(51.5074, -0.1278, digits = 2) // Trafalgar Square area
        assertNotNull(ref)
        assertTrue("expected a TQ ref, got $ref", ref!!.startsWith("TQ"))
    }

    @Test fun wgs84_to_gridref_full_precision_format() {
        val ref = Bng.wgs84ToGridRef(52.6575703, 1.7179215, digits = 5)
        assertNotNull(ref)
        // "<2-letter sq> <5 digits> <5 digits>"
        assertTrue("malformed ref: $ref", Regex("^[A-Z]{2} \\d{5} \\d{5}$").matches(ref!!))
        assertTrue("should land in TG, got $ref", ref.startsWith("TG"))
    }

    @Test fun out_of_uk_bounds_returns_null() {
        assertNull(Bng.wgs84ToGridRef(40.4168, -3.7038)) // Madrid
        assertNull(Bng.wgs84ToGridRef(48.8566, 2.3522))  // Paris (lon > 2)
    }

    @Test fun datum_shift_moves_the_point_slightly() {
        val osgb = Bng.wgs84ToOsgb36(52.6575703, 1.7179215)
        assertNotEquals(52.6575703, osgb[0], 1e-6)        // it actually shifts
        assertTrue("shift should be < ~1 km", abs(osgb[0] - 52.6575703) < 0.01)
    }

    @Test fun coordformatter_bng_branch_formats_in_gb_and_falls_back_outside() {
        assertTrue(CoordFormatter.position(52.6575703, 1.7179215, CoordFormat.BNG).startsWith("BNG TG"))
        // Outside GB falls back to plain lat/lon, not a BNG ref.
        assertFalse(CoordFormatter.position(40.4168, -3.7038, CoordFormat.BNG).startsWith("BNG"))
    }
}
