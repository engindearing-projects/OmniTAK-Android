package soy.engindearing.omnitak.mobile.data.symbology

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MilStdIconServiceTest {

    // ---- Exact-match lookups ---------------------------------------------

    @Test
    fun `exact match returns the catalogue SIDC for friendly infantry`() {
        assertEquals("SFGPUCI----", MilStdIconService.getSidc("a-f-G-U-C-I"))
    }

    @Test
    fun `exact match returns the catalogue SIDC for unknown air`() {
        assertEquals("SUA---------", MilStdIconService.getSidc("a-u-A"))
    }

    @Test
    fun `exact match returns the catalogue SIDC for hostile armor`() {
        assertEquals("SHGPUCA----", MilStdIconService.getSidc("a-h-G-U-C-A"))
    }

    // ---- Progressive truncation -----------------------------------------

    @Test
    fun `unknown trailing segment truncates to the known parent type`() {
        // a-f-G-U-C-I-X is not in the catalogue but a-f-G-U-C-I is.
        assertEquals("SFGPUCI----", MilStdIconService.getSidc("a-f-G-U-C-I-X"))
    }

    @Test
    fun `multi-segment truncation walks back to a known prefix`() {
        // a-f-G-U-C-I-Z-Q is two segments past a known entry.
        assertEquals("SFGPUCI----", MilStdIconService.getSidc("a-f-G-U-C-I-Z-Q"))
    }

    @Test
    fun `truncation stops at the affiliation prefix and falls back`() {
        // a-f-X is malformed past affiliation — no parent matches.
        // Should fall back to the friendly default SIDC.
        assertEquals("SFGPU------", MilStdIconService.getSidc("a-f-X"))
    }

    // ---- Per-affiliation fallback ---------------------------------------

    @Test
    fun `completely unknown friendly cot type falls back to friendly default`() {
        assertEquals("SFGPU------", MilStdIconService.getSidc("a-f-Z-Z-Z"))
    }

    @Test
    fun `completely unknown hostile cot type falls back to hostile default`() {
        assertEquals("SHGPU------", MilStdIconService.getSidc("a-h-Z-Z-Z"))
    }

    @Test
    fun `completely unknown neutral cot type falls back to neutral default`() {
        assertEquals("SNGPU------", MilStdIconService.getSidc("a-n-Z-Z-Z"))
    }

    @Test
    fun `completely unknown unknown cot type falls back to unknown default`() {
        assertEquals("SUGPU------", MilStdIconService.getSidc("a-u-Z-Z-Z"))
    }

    @Test
    fun `garbage input falls back to the unknown default rather than crashing`() {
        assertEquals("SUGPU------", MilStdIconService.getSidc(""))
        assertEquals("SUGPU------", MilStdIconService.getSidc("not a cot type"))
        assertEquals("SUGPU------", MilStdIconService.getSidc("x"))
    }

    // ---- Asset path helpers ---------------------------------------------

    @Test
    fun `svg filename appends the svg suffix`() {
        assertEquals("SFGPUCI----.svg", MilStdIconService.getSvgFileName("a-f-G-U-C-I"))
    }

    @Test
    fun `asset path lives under the milstd assets dir`() {
        assertEquals("milstd/SFGPUCI----.svg", MilStdIconService.getAssetPath("a-f-G-U-C-I"))
    }

    // ---- Definition lookups ---------------------------------------------

    @Test
    fun `getDefinition returns the full record for a known type`() {
        val def = MilStdIconService.getDefinition("a-f-G-U-C-I")
        assertNotNull(def)
        assertEquals("Friendly Infantry", def!!.label)
        assertEquals("friendly", def.category)
        assertEquals(Affiliation.FRIENDLY, def.affiliation)
    }

    @Test
    fun `getDefinition returns null for an unknown type rather than the fallback`() {
        // The fallback is a SIDC-resolution concern, not a definition
        // lookup concern. Callers asking for a definition want to know
        // if the type is actually catalogued.
        assertNull(MilStdIconService.getDefinition("a-f-Z-Z-Z"))
    }

    @Test
    fun `getDefinitionBySidc resolves the sidcCode back to its definition`() {
        val def = MilStdIconService.getDefinitionBySidc("SFGPUCI----")
        assertNotNull(def)
        assertEquals("a-f-G-U-C-I", def!!.value)
    }

    @Test
    fun `getAllDefinitions returns the full catalogue`() {
        assertTrue(MilStdIconService.getAllDefinitions().isNotEmpty())
        assertTrue(MilStdIconService.getAllDefinitions().size >= 28) // current iOS floor
    }

    @Test
    fun `getDefinitions filters by affiliation`() {
        val friendlies = MilStdIconService.getDefinitions(Affiliation.FRIENDLY)
        assertTrue(friendlies.all { it.affiliation == Affiliation.FRIENDLY })
        assertTrue(friendlies.isNotEmpty())
    }

    // ---- Affiliation enum -----------------------------------------------

    @Test
    fun `affiliation parses each prefix code`() {
        assertEquals(Affiliation.FRIENDLY, Affiliation.fromCotType("a-f-G-U"))
        assertEquals(Affiliation.HOSTILE, Affiliation.fromCotType("a-h-G-U"))
        assertEquals(Affiliation.NEUTRAL, Affiliation.fromCotType("a-n-G-U"))
        assertEquals(Affiliation.UNKNOWN, Affiliation.fromCotType("a-u-G-U"))
    }

    @Test
    fun `affiliation accepts uppercase code as a tolerance hedge`() {
        assertEquals(Affiliation.FRIENDLY, Affiliation.fromCotType("a-F-G-U"))
        assertEquals(Affiliation.HOSTILE, Affiliation.fromCotType("a-H-G-U"))
    }

    @Test
    fun `affiliation defaults to unknown for malformed input`() {
        assertEquals(Affiliation.UNKNOWN, Affiliation.fromCotType(""))
        assertEquals(Affiliation.UNKNOWN, Affiliation.fromCotType("a-?-G-U"))
        assertEquals(Affiliation.UNKNOWN, Affiliation.fromCotType("ax"))
    }

    @Test
    fun `affiliation round-trips via raw category strings`() {
        for (a in Affiliation.values()) {
            assertEquals(a, Affiliation.fromRaw(a.raw))
        }
    }

    @Test
    fun `affiliation sidcChar matches the MIL-STD-2525 single-letter code`() {
        assertEquals('F', Affiliation.FRIENDLY.sidcChar)
        assertEquals('H', Affiliation.HOSTILE.sidcChar)
        assertEquals('N', Affiliation.NEUTRAL.sidcChar)
        assertEquals('U', Affiliation.UNKNOWN.sidcChar)
    }

    // ---- BattleDimension enum -------------------------------------------

    @Test
    fun `battle dimension parses each dimension code`() {
        assertEquals(BattleDimension.GROUND, BattleDimension.fromCotType("a-f-G-U"))
        assertEquals(BattleDimension.AIR, BattleDimension.fromCotType("a-u-A"))
        assertEquals(BattleDimension.SEA, BattleDimension.fromCotType("a-f-S"))
        assertEquals(BattleDimension.SUBSURFACE, BattleDimension.fromCotType("a-f-U"))
        assertEquals(BattleDimension.SPACE, BattleDimension.fromCotType("a-f-P"))
    }

    @Test
    fun `battle dimension defaults to other for malformed input`() {
        assertEquals(BattleDimension.OTHER, BattleDimension.fromCotType(""))
        assertEquals(BattleDimension.OTHER, BattleDimension.fromCotType("a-f"))
        assertEquals(BattleDimension.OTHER, BattleDimension.fromCotType("a-f-?"))
    }

    // ---- Service-level enum convenience ---------------------------------

    @Test
    fun `service exposes affiliation lookup for a cot type`() {
        assertEquals(Affiliation.FRIENDLY, MilStdIconService.getAffiliation("a-f-G-U-C-I"))
        assertEquals(Affiliation.UNKNOWN, MilStdIconService.getAffiliation("a-u-A"))
    }

    @Test
    fun `service exposes battle dimension lookup for a cot type`() {
        assertEquals(BattleDimension.GROUND, MilStdIconService.getBattleDimension("a-f-G-U"))
        assertEquals(BattleDimension.AIR, MilStdIconService.getBattleDimension("a-u-A"))
    }

    // ---- Drone story regression -----------------------------------------

    @Test
    fun `unknown UAS multirotor resolves to the multirotor SIDC in the floor`() {
        // a-u-A-M-H-Q is the Remote ID multirotor classification. It now
        // lives in the hardcoded floor so RID tracks render with the
        // correct multirotor glyph without depending on Phase C's JSON
        // catalogue loading first.
        assertEquals("SUAPMHQ----", MilStdIconService.getSidc("a-u-A-M-H-Q"))
    }

    @Test
    fun `unknown UAS fixed wing resolves to the fixed-wing SIDC in the floor`() {
        assertEquals("SUAPMFQ----", MilStdIconService.getSidc("a-u-A-M-F-Q"))
    }

    @Test
    fun `unknown UAS without a multirotor or fixed-wing suffix truncates to unknown air`() {
        // A future RID classification we don't have an entry for yet
        // should still resolve to something — truncation walks back to
        // a-u-A and the generic unknown-air SIDC.
        assertEquals("SUA---------", MilStdIconService.getSidc("a-u-A-M-X-Q"))
    }

    // ---- Phase C — runtime catalogue replacement ------------------------

    @Test
    fun `load merges over hardcoded floor without losing baseline entries`() {
        val extra = CoTTypeDefinition(
            value = "a-f-G-U-C-E",
            sidc = "SFGPUCE----.svg",
            label = "Friendly Engineer",
            description = "Friendly combat engineer unit",
            category = "friendly",
        )
        try {
            MilStdIconService.load(listOf(extra))

            // New entry takes effect.
            assertEquals("SFGPUCE----", MilStdIconService.getSidc("a-f-G-U-C-E"))
            assertEquals("Friendly Engineer", MilStdIconService.getDefinition("a-f-G-U-C-E")?.label)

            // Hardcoded baseline entry still resolves — load() must not
            // wipe the floor, only layer over it.
            assertEquals("SFGPUCI----", MilStdIconService.getSidc("a-f-G-U-C-I"))
            assertNotNull(MilStdIconService.getDefinition("a-u-A"))
        } finally {
            // Reset the singleton state for other tests in the suite.
            MilStdIconService.load(emptyList())
        }
    }

    @Test
    fun `load with overriding entry replaces the floor mapping for that cot type`() {
        val override = CoTTypeDefinition(
            value = "a-u-A-M-H-Q",
            sidc = "SUAPMHQ----.svg",
            label = "Unknown UAS Multirotor",
            description = "Phase C catalogue entry",
            category = "unknown",
        )
        try {
            MilStdIconService.load(listOf(override))

            // Phase C catalogue entry wins over the truncation fallback.
            assertEquals("SUAPMHQ----", MilStdIconService.getSidc("a-u-A-M-H-Q"))
            assertEquals("Phase C catalogue entry", MilStdIconService.getDefinition("a-u-A-M-H-Q")?.description)
        } finally {
            MilStdIconService.load(emptyList())
        }
    }

    // ---- Re-affiliation (#98 marker icon picker) -------------------------

    @Test
    fun `withAffiliation swaps the affiliation segment and keeps the tail`() {
        // Hostile armor <-> friendly armor: only the 2nd token flips so the
        // affiliation chips can retype a picked symbol without re-opening the
        // picker.
        assertEquals("a-f-G-U-C-A", MilStdIconService.withAffiliation("a-h-G-U-C-A", 'f'))
        assertEquals("a-h-G-U-C-A", MilStdIconService.withAffiliation("a-f-G-U-C-A", 'h'))
        assertEquals("a-n-A-M-F-Q", MilStdIconService.withAffiliation("a-u-A-M-F-Q", 'n'))
    }

    @Test
    fun `withAffiliation falls back to a generic point for a too-short type`() {
        assertEquals("a-u-G-U-C", MilStdIconService.withAffiliation("a", 'u'))
    }

    @Test
    fun `withAffiliation result still resolves to the matching SIDC`() {
        // The re-affiliated type must round-trip through getSidc to the
        // friendly-armor symbol (not the hostile one it started as).
        val reaff = MilStdIconService.withAffiliation("a-h-G-U-C-A", 'f')
        assertEquals("SFGPUCA----", MilStdIconService.getSidc(reaff))
    }
}
