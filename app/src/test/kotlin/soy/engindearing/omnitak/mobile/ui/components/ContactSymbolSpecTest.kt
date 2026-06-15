package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.CoTEvent

/**
 * Issue #77 — contact → [ContactSymbolSpec] mapping for the SymbolManager
 * (annotation/bitmap) render path that replaces the never-painting GeoJSON
 * CircleLayer/SymbolLayer on Adreno/Mali/Immortalis GPUs.
 *
 * These cover the load-bearing, device-free half of the fix: that each contact
 * yields the right icon-image id, a non-empty callsign label, the correct team
 * color, lat/lon, and the right tint decision. The actual on-GPU paint is the
 * only thing left for on-device verification.
 */
class ContactSymbolSpecTest {

    private fun contact(
        uid: String = "uid-1",
        type: String = "a-f-G-U-C",
        lat: Double = 47.6588,
        lon: Double = -117.4260,
        callsign: String? = "Alpha",
        teamName: String? = null,
    ) = CoTEvent(uid = uid, type = type, lat = lat, lon = lon, callsign = callsign, teamName = teamName)

    // ── icon-image id resolution ─────────────────────────────────────────────

    @Test fun `milStd spec uses milstd prefix plus resolved sidc as image id`() {
        val spec = ContactSymbolSpec.milStd(contact(type = "a-f-G-U-C"))
        assertTrue(
            "image id must be the milstd-<sidc> name ContactSymbolLayer registers, was ${spec.imageId}",
            spec.imageId.startsWith(ContactSymbolLayer.ICON_IMAGE_PREFIX),
        )
        // Image id must not be blank past the prefix — a blank SIDC would make
        // SymbolManager reference an unregistered image and silently hide.
        assertTrue(
            "resolved SIDC portion must be non-empty",
            spec.imageId.length > ContactSymbolLayer.ICON_IMAGE_PREFIX.length,
        )
    }

    @Test fun `from carries through the explicit TAK-suite image id verbatim`() {
        val takId = "takicon-spot-${0xFFFFFFFF.toInt()}"
        val spec = ContactSymbolSpec.from(contact(), resolvedImageId = takId)
        assertEquals(takId, spec.imageId)
    }

    // ── callsign label (regression guard: "all blue, NO CALLSIGN") ───────────

    @Test fun `callsign is preserved when present`() {
        val spec = ContactSymbolSpec.milStd(contact(callsign = "Bravo-6"))
        assertEquals("Bravo-6", spec.callsign)
    }

    @Test fun `blank or null callsign falls back to uid so no marker is anonymous`() {
        assertEquals("uid-x", ContactSymbolSpec.milStd(contact(uid = "uid-x", callsign = null)).callsign)
        assertEquals("uid-y", ContactSymbolSpec.milStd(contact(uid = "uid-y", callsign = "")).callsign)
        assertEquals("uid-z", ContactSymbolSpec.milStd(contact(uid = "uid-z", callsign = "   ")).callsign)
    }

    // ── team color (regression guard: "ALL BLUE, no callsign") ───────────────
    // The team color is carried on teamColorArgb and painted onto the callsign
    // label (icon-color only tints SDF icons; our glyphs are full-color rasters —
    // see ContactSymbolSpec). These assert the right color is carried.

    @Test fun `team color overrides affiliation color when a team name is set`() {
        // displayColor: TakTeamColor.forName("Red") = 0xFFFF0000 wins over the
        // friendly-affiliation green — exactly the Red-1 case from the P-E report.
        val spec = ContactSymbolSpec.milStd(contact(type = "a-f-G-U-C", teamName = "Red"))
        assertEquals(0xFFFF0000.toInt(), spec.teamColorArgb)
    }

    @Test fun `hostile affiliation color is carried when no team name`() {
        val spec = ContactSymbolSpec.milStd(contact(type = "a-h-G-U-C", teamName = null))
        assertEquals(0xFFF44336.toInt(), spec.teamColorArgb)
    }

    @Test fun `friendly affiliation color is carried when no team name`() {
        val spec = ContactSymbolSpec.milStd(contact(type = "a-f-G-U-C", teamName = null))
        assertEquals(0xFF4ADE80.toInt(), spec.teamColorArgb)
    }

    @Test fun `team color survives the TAK-suite icon path too`() {
        // Even when the icon is a baked-color TAK-suite glyph, the team color is
        // still carried so the label tint reflects the operator's team.
        val spec = ContactSymbolSpec.from(
            contact(type = "a-f-G-U-C", teamName = "Orange"),
            resolvedImageId = "takicon-spot-x",
        )
        assertEquals(0xFFFF6600.toInt(), spec.teamColorArgb)
    }

    // ── coordinates ─────────────────────────────────────────────────────────

    @Test fun `lat lon are carried through unchanged`() {
        val spec = ContactSymbolSpec.milStd(contact(lat = 12.34, lon = -56.78))
        assertEquals(12.34, spec.lat, 0.0)
        assertEquals(-56.78, spec.lon, 0.0)
    }

    @Test fun `uid is the diff key and is carried through`() {
        assertEquals("contact-42", ContactSymbolSpec.milStd(contact(uid = "contact-42")).uid)
    }
}
