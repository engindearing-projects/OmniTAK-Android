package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.symbology.MilStdIconService

/**
 * Issue #77 — candidate fix for "dropped markers don't paint on the 2D
 * MapLibre map until a full style reload" on Mali/Adreno (Pixel 9 Pro).
 *
 * The render fix itself (re-assert `icon-image` + [org.maplibre.android.maps.MapLibreMap.triggerRepaint]
 * after a runtime [org.maplibre.android.maps.Style.addImage]) can only be
 * confirmed on a real GPU — it is a device-only driver-timing bug and does NOT
 * reproduce on SwiftShader / the CI emulator. What IS pinnable in pure JVM is
 * the invariant the fix leans on: every contact feature's `icon-image` id MUST
 * equal an id the layer actually registered via `addImage`. If those two ever
 * drift, the SymbolLayer resolves `icon-image` to nothing and the marker draws
 * blank — the same visible symptom #77 reports — regardless of any repaint.
 *
 * These tests lock [ContactSymbolLayer.resolveImageName] /
 * [ContactSymbolLayer.milStdImageId] so a future refactor of either the
 * `addImage` id or the feature-property id can't silently re-open #77.
 */
class ContactSymbolLayerImageIdTest {

    // ── MIL-STD id: feature id == addImage id ────────────────────────────────

    @Test fun `milStd image id is the prefixed sidc`() {
        // This is the exact string passed to BOTH Style.addImage (registerSidcImage)
        // and the feature's icon-image property (resolveImageName). They must match.
        val sidc = MilStdIconService.getSidc("a-f-G-U-C") // friendly ground
        assertEquals(ContactSymbolLayer.ICON_IMAGE_PREFIX + sidc, ContactSymbolLayer.milStdImageId(sidc))
    }

    @Test fun `milStd image id is stable and non-empty for every prewarmed cot type`() {
        // Every pre-warmed contact type must yield a usable, prefixed image id —
        // these are the icons registered at install time, so a blank/duplicate id
        // here would mean a pre-warmed symbol never resolves.
        val prewarm = listOf(
            "a-f-G-U", "a-h-G-U", "a-n-G-U", "a-u-G-U",
            "a-f-G-U-C-I", "a-u-A", "a-u-A-M-H-Q", "a-u-A-M-F-Q", "a-u-G",
        )
        val ids = prewarm.map { ContactSymbolLayer.milStdImageId(MilStdIconService.getSidc(it)) }
        for (id in ids) {
            assertTrue("image id must carry the milstd- prefix, got '$id'", id.startsWith(ContactSymbolLayer.ICON_IMAGE_PREFIX))
            assertTrue("image id must have a sidc after the prefix, got '$id'", id.length > ContactSymbolLayer.ICON_IMAGE_PREFIX.length)
        }
    }

    // ── resolveImageName: TAK-suite id wins, else MIL-STD ────────────────────

    @Test fun `resolveImageName falls back to milStd id when no tak icon`() {
        // takImageId == null is the common case (point-dropper friendly/hostile/neutral):
        // the contact must reference the MIL-STD id that registerSidcImage added.
        val sidc = MilStdIconService.getSidc("a-h-G-U-C")
        assertEquals(
            ContactSymbolLayer.milStdImageId(sidc),
            ContactSymbolLayer.resolveImageName(sidc, takImageId = null),
        )
    }

    @Test fun `resolveImageName prefers the tak-suite id when one was registered`() {
        // Issue #98 path: a contact carrying a bundled/imported iconset registers a
        // TAK-suite image and the feature must reference THAT id, not the milstd one.
        val sidc = MilStdIconService.getSidc("a-f-G-U-C")
        val takId = "takicon-spot-red"
        assertEquals(takId, ContactSymbolLayer.resolveImageName(sidc, takImageId = takId))
        assertNotEquals(ContactSymbolLayer.milStdImageId(sidc), ContactSymbolLayer.resolveImageName(sidc, takId))
    }

    @Test fun `distinct sidcs map to distinct image ids`() {
        // No two different symbols may collide on one atlas id (a collision would
        // make one symbol render as the other's bitmap).
        val friendly = ContactSymbolLayer.milStdImageId(MilStdIconService.getSidc("a-f-G-U-C"))
        val hostile = ContactSymbolLayer.milStdImageId(MilStdIconService.getSidc("a-h-G-U-C"))
        assertNotEquals("friendly and hostile must not share an image id", friendly, hostile)
    }
}
