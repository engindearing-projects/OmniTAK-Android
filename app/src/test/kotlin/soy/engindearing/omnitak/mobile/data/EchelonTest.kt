package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #159 — Echelon / org size. Ported from the iOS `EchelonLevel`. The amplifier
 * is the NATO APP-6 echelon modifier drawn above a unit symbol; the code is the
 * wire value carried in PPLI / persisted with the self marker.
 */
class EchelonTest {

    @Test fun amplifier_glyphs_match_ios_app6_ladder() {
        assertEquals("•", Echelon.TEAM.amplifier)       // •
        assertEquals("••", Echelon.SQUAD.amplifier) // ••
        assertEquals("•••", Echelon.PLATOON.amplifier) // •••
        assertEquals("I", Echelon.COMPANY.amplifier)
        assertEquals("II", Echelon.BATTALION.amplifier)
        assertEquals("III", Echelon.BRIGADE.amplifier)
        assertEquals("XX", Echelon.DIVISION.amplifier)
        assertEquals("XXX", Echelon.CORPS.amplifier)
    }

    @Test fun display_names_and_strengths_match_ios() {
        assertEquals("Team", Echelon.TEAM.displayName)
        assertEquals("Company", Echelon.COMPANY.displayName)
        assertEquals(4, Echelon.TEAM.standardStrength)
        assertEquals(150, Echelon.COMPANY.standardStrength)
        assertEquals(45000, Echelon.CORPS.standardStrength)
    }

    @Test fun ordered_smallest_to_largest() {
        assertTrue(Echelon.TEAM < Echelon.SQUAD)
        assertTrue(Echelon.COMPANY < Echelon.CORPS)
        // strength strictly increases up the ladder
        val strengths = Echelon.entries.map { it.standardStrength }
        assertEquals(strengths.sorted(), strengths)
    }

    @Test fun code_round_trips_and_matches_ios_rawvalue() {
        assertEquals("company", Echelon.COMPANY.code)
        assertEquals(Echelon.COMPANY, Echelon.fromCode("company"))
        assertEquals(Echelon.COMPANY, Echelon.fromCode("COMPANY")) // case-insensitive
        assertNull(Echelon.fromCode("regiment"))                   // not in the ladder
        assertNull(Echelon.fromCode(null))
    }
}
