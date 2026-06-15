package soy.engindearing.omnitak.mobile.data.symbology

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.FemaIconCatalog

/**
 * Issue #46 — FEMA glyph resolution contract.
 *
 * These tests assert that:
 *  1. Every FEMA kind maps to a DISTINCT asset path (not a fallback dot,
 *     not a MIL-STD SIDC), so a command_post and a triage_station can
 *     never resolve to the same image.
 *  2. The asset paths follow the `fema/<category>/<kind>.svg` convention
 *     the [FemaIconCache] expects.
 *  3. [TakIconRegistry.styleImageId] produces a per-kind stable image
 *     key (NOT a shared "takicon-fema-…" from the category alone), and
 *     no two kinds share the same key.
 *  4. [TakIconRegistry.handles] is true for FEMA iconset paths so the
 *     ContactSymbolLayer routes them through the TAK-suite path rather
 *     than falling through to MIL-STD art.
 *  5. Non-FEMA paths are rejected cleanly.
 *
 * The bitmap half ([FemaIconCache.bitmapFor]) requires an Android [Context]
 * and is NOT exercised here — that is covered by instrumented tests or
 * a device render. These unit tests pin the resolution + routing logic
 * that runs without Android.
 */
class FemaGlyphResolutionTest {

    // ── Asset path convention ─────────────────────────────────────────────

    @Test
    fun `every kind maps to a distinct fema asset path`() {
        // No two kinds should resolve to the same asset. If the asset path
        // were wrong (e.g. same kind raw OR same category segment), two different
        // markers would render the same glyph, defeating the whole point of #46.
        val paths = FemaIconCatalog.Kind.entries.map { kind ->
            assetPathFor(kind)
        }
        // All 12 paths are distinct.
        assertEquals(
            "Expected 12 distinct asset paths but got: $paths",
            FemaIconCatalog.Kind.entries.size,
            paths.toSet().size,
        )
    }

    @Test
    fun `asset paths follow the fema-category-kind-dot-svg convention`() {
        for (kind in FemaIconCatalog.Kind.entries) {
            val path = assetPathFor(kind)
            assertTrue(
                "Expected path to start with 'fema/' but was: $path",
                path.startsWith("fema/"),
            )
            assertTrue(
                "Expected path to end with '.svg' but was: $path",
                path.endsWith(".svg"),
            )
            // Three segments: fema / <category> / <kind>.svg
            val parts = path.split('/')
            assertEquals(
                "Expected 3 path segments (fema/<cat>/<kind>.svg) but got $parts",
                3,
                parts.size,
            )
            assertEquals("fema", parts[0])
            assertTrue("Middle segment must not be empty", parts[1].isNotEmpty())
            assertTrue("Kind segment must not be empty", parts[2].removeSuffix(".svg").isNotEmpty())
        }
    }

    @Test
    fun `command_post asset is fema-incidentCommand-command_post-svg`() {
        assertEquals("fema/incidentCommand/command_post.svg", assetPathFor(FemaIconCatalog.Kind.COMMAND_POST))
    }

    @Test
    fun `triage_station asset is fema-medical-triage_station-svg`() {
        assertEquals("fema/medical/triage_station.svg", assetPathFor(FemaIconCatalog.Kind.TRIAGE_STATION))
    }

    @Test
    fun `helicopter_lz asset is fema-aviation-helicopter_lz-svg`() {
        assertEquals("fema/aviation/helicopter_lz.svg", assetPathFor(FemaIconCatalog.Kind.HELICOPTER_LZ))
    }

    @Test
    fun `communications_hub asset is fema-support-communications_hub-svg`() {
        assertEquals("fema/support/communications_hub.svg", assetPathFor(FemaIconCatalog.Kind.COMMUNICATIONS_HUB))
    }

    // ── TakIconRegistry routing ───────────────────────────────────────────

    @Test
    fun `handles is true for every FEMA iconset path`() {
        for (icon in FemaIconCatalog.all) {
            assertTrue(
                "handles must be true for FEMA path ${icon.iconsetPath}",
                TakIconRegistry.handles(icon.cotType, icon.iconsetPath),
            )
        }
    }

    @Test
    fun `handles is false for a null iconset path on a FEMA cot type`() {
        // A FEMA-typed event whose iconsetPath was stripped in transit must NOT
        // claim to be a TAK-suite icon — it would render blank. Receivers without
        // the FEMA bundle should fall back to MIL-STD friendly-installation art.
        assertFalse(
            "FEMA cotType without an iconsetPath must fall through to MIL-STD",
            TakIconRegistry.handles("a-f-G-I-U-T", null),
        )
    }

    @Test
    fun `styleImageId is a distinct per-kind key for every FEMA icon`() {
        val ids = FemaIconCatalog.all.map { icon ->
            TakIconRegistry.styleImageId(icon.cotType, icon.iconsetPath, null)
        }
        // Every id is non-null.
        assertTrue("styleImageId returned null for a FEMA icon", ids.none { it == null })
        // Every id starts with the expected prefix.
        assertTrue(
            "All FEMA image ids must start with 'takicon-fema-'",
            ids.all { it!!.startsWith("takicon-fema-") },
        )
        // All 12 ids are DISTINCT — no two kinds share a MapLibre image slot.
        assertEquals(
            "Expected 12 distinct styleImageIds but collisions found: ${ids.groupBy { it }.filter { it.value.size > 1 }.keys}",
            FemaIconCatalog.all.size,
            ids.toSet().size,
        )
    }

    @Test
    fun `styleImageId uses the kind raw not the category so kinds within the same category differ`() {
        // INCIDENT_COMMAND has two kinds: COMMAND_POST and STAGING_AREA.
        // They must produce distinct ids or both command markers render the same glyph.
        val cpId = TakIconRegistry.styleImageId(
            "a-f-G-I-U-T",
            "COT_MAPPING_FEMA/incidentCommand/command_post",
            null,
        )
        val saId = TakIconRegistry.styleImageId(
            "a-f-G-I-B-A",
            "COT_MAPPING_FEMA/incidentCommand/staging_area",
            null,
        )
        assertNotNull(cpId)
        assertNotNull(saId)
        assertTrue("command_post and staging_area must have distinct image ids", cpId != saId)
    }

    @Test
    fun `styleImageId returns null for a non-FEMA path`() {
        // A MIL-STD contact with no iconset path gets null → the MIL-STD path
        // takes over, just like before.
        assertNull(TakIconRegistry.styleImageId("a-f-G-U-C-I", null, null))
    }

    @Test
    fun `resolveFemaIcon identifies every FEMA iconset path`() {
        for (icon in FemaIconCatalog.all) {
            val resolved = TakIconRegistry.resolveFemaIcon(icon.iconsetPath)
            assertNotNull("resolveFemaIcon returned null for ${icon.iconsetPath}", resolved)
            assertEquals(icon.kind, resolved!!.kind)
        }
    }

    @Test
    fun `resolveFemaIcon rejects a non-FEMA path`() {
        assertNull(TakIconRegistry.resolveFemaIcon("COT_MAPPING_SPOTMAP/red"))
        assertNull(TakIconRegistry.resolveFemaIcon("COT_MAPPING_2525C/flag"))
        assertNull(TakIconRegistry.resolveFemaIcon(null))
    }

    // ── FemaIconCache asset path derivation (no context) ─────────────────

    @Test
    fun `FemaIconCache imageKeyFor returns the fema dash kind raw key`() {
        assertEquals(
            FemaIconCache.ICON_IMAGE_PREFIX + FemaIconCatalog.Kind.COMMAND_POST.raw,
            FemaIconCache.imageKeyFor(FemaIconCatalog.Kind.COMMAND_POST),
        )
        // Prefix must be "fema-".
        assertTrue(
            FemaIconCache.imageKeyFor(FemaIconCatalog.Kind.TRIAGE_STATION)
                .startsWith(FemaIconCache.ICON_IMAGE_PREFIX),
        )
    }

    @Test
    fun `FemaIconCache imageKeys are distinct for all 12 kinds`() {
        val keys = FemaIconCatalog.Kind.entries.map { FemaIconCache.imageKeyFor(it) }
        assertEquals(
            "Expected 12 distinct FemaIconCache image keys",
            FemaIconCatalog.Kind.entries.size,
            keys.toSet().size,
        )
    }

    // ── Helper: mirror FemaIconCache.assetPathFor without needing the object ──

    /** Derive the asset path from a Kind's iconsetPath — mirrors FemaIconCache. */
    private fun assetPathFor(kind: FemaIconCatalog.Kind): String {
        val icon = FemaIconCatalog.iconFor(kind)!!
        val segments = icon.iconsetPath.split('/')
        // segments: ["COT_MAPPING_FEMA", "<category>", "<kind>"]
        return "fema/${segments[1]}/${segments[2]}.svg"
    }
}
