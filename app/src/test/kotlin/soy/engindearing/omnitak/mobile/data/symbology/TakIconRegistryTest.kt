package soy.engindearing.omnitak.mobile.data.symbology

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #98 — the standard TAK icon suite must resolve an `iconsetpath` to a
 * bundled icon for BOTH a received marker (inbound CoT carrying a `<usericon
 * iconsetpath="COT_MAPPING_SPOTMAP/…">`) and a placed marker (the picker emits
 * the same path back onto the wire). These assertions exercise the resolution +
 * selection framework that drives both directions. (Bitmap rasterisation is the
 * Android-framework half and is covered implicitly on-device; here we pin the
 * path/colour logic that decides WHICH bundled icon a marker resolves to.)
 */
class TakIconRegistryTest {

    // ---- Received-marker resolution (inbound iconsetpath) ----------------

    @Test
    fun `spot map iconset path resolves to the matching swatch`() {
        // kymyura's exact use case: a script pushes CoT with
        // <usericon iconsetpath="COT_MAPPING_SPOTMAP/red">. It must resolve.
        val spot = TakIconRegistry.SpotIcon.fromIconsetPath("COT_MAPPING_SPOTMAP/red")
        assertNotNull(spot)
        assertEquals(TakIconRegistry.SpotIcon.RED, spot)
    }

    @Test
    fun `every canonical spot color resolves from its iconset path`() {
        for (spot in TakIconRegistry.SpotIcon.entries) {
            val resolved = TakIconRegistry.SpotIcon.fromIconsetPath("COT_MAPPING_SPOTMAP/${spot.key}")
            assertEquals(spot, resolved)
        }
    }

    @Test
    fun `spot map path match is case insensitive on the prefix and token`() {
        assertEquals(
            TakIconRegistry.SpotIcon.GREEN,
            TakIconRegistry.SpotIcon.fromIconsetPath("cot_mapping_spotmap/GREEN"),
        )
    }

    @Test
    fun `unknown spot token falls back to white rather than null`() {
        // A spot marker must never render blank; an unrecognised colour token
        // resolves to white (ATAK's behaviour) so the dot still draws.
        assertEquals(
            TakIconRegistry.SpotIcon.WHITE,
            TakIconRegistry.SpotIcon.fromIconsetPath("COT_MAPPING_SPOTMAP/chartreuse"),
        )
    }

    @Test
    fun `label-only spot path resolves to null so the caller keeps its fallback`() {
        // A trailing empty token (`…SPOTMAP/`) is a label-only spot, not a
        // colour — return null so the caller doesn't invent a white dot.
        assertNull(TakIconRegistry.SpotIcon.fromIconsetPath("COT_MAPPING_SPOTMAP/"))
    }

    @Test
    fun `non-spot iconset path does not resolve to a spot`() {
        // A FEMA or Google path must NOT be mistaken for a Spot Map icon.
        assertNull(TakIconRegistry.SpotIcon.fromIconsetPath("COT_MAPPING_FEMA/medical/triage_station"))
        assertNull(TakIconRegistry.SpotIcon.fromIconsetPath("f7f71666-8b28-4b57-9fbb-e38e61d33b79/Google/airports.png"))
    }

    @Test
    fun `handles is true for a spot iconset path and for the spot cot type`() {
        assertTrue(TakIconRegistry.handles("b-m-p-s-m", "COT_MAPPING_SPOTMAP/blue"))
        // Colour rides <color>, so the bare spot CoT type is enough on its own.
        assertTrue(TakIconRegistry.handles("b-m-p-s-m", null))
    }

    @Test
    fun `handles is false for a plain MIL-STD contact`() {
        // A regular friendly-infantry contact must fall through to MIL-STD, not
        // the TAK-suite path — otherwise we'd stop drawing milsymbol glyphs.
        assertFalse(TakIconRegistry.handles("a-f-G-U-C-I", null))
        assertFalse(TakIconRegistry.handles("a-u-A-M-H-Q", null))
    }

    // ---- Placed-marker emission (outbound iconsetpath) ------------------

    @Test
    fun `placing a spot icon emits the canonical COT_MAPPING_SPOTMAP path`() {
        // The picker hands this path back; it goes out on the wire verbatim so
        // ATAK / iTAK peers render the identical dot. Round-trips with the
        // resolver above.
        assertEquals("COT_MAPPING_SPOTMAP/red", TakIconRegistry.SpotIcon.RED.iconsetPath)
        assertEquals("COT_MAPPING_SPOTMAP/cyan", TakIconRegistry.SpotIcon.CYAN.iconsetPath)
    }

    @Test
    fun `placed spot path round-trips back through the resolver`() {
        for (spot in TakIconRegistry.SpotIcon.entries) {
            assertEquals(spot, TakIconRegistry.SpotIcon.fromIconsetPath(spot.iconsetPath))
        }
    }

    @Test
    fun `spot cot type is the canonical ATAK spot-map point type`() {
        assertEquals("b-m-p-s-m", TakIconRegistry.SpotIcon.COT_TYPE)
    }

    @Test
    fun `spot argb hex is an opaque 8-char ARGB string`() {
        val hex = TakIconRegistry.SpotIcon.RED.argbHex
        assertEquals(8, hex.length)
        assertTrue(hex.startsWith("FF"))
        // Round-trips to the swatch RGB.
        assertEquals(0x00FFFFFF and TakIconRegistry.SpotIcon.RED.argb, hex.substring(2).toInt(16))
    }

    // ---- Style image-id keying (received-marker render path) ------------

    @Test
    fun `style image id is stable per spot colour and distinct from MIL-STD`() {
        val id = TakIconRegistry.styleImageId("b-m-p-s-m", "COT_MAPPING_SPOTMAP/red", null)
        assertNotNull(id)
        assertTrue(id!!.startsWith("takicon-"))
        // Two markers of the same resolved colour share one registered image.
        assertEquals(
            id,
            TakIconRegistry.styleImageId("b-m-p-s-m", "COT_MAPPING_SPOTMAP/red", null),
        )
        // A MIL-STD contact yields no TAK-suite image id (it uses milstd-<sidc>).
        assertNull(TakIconRegistry.styleImageId("a-f-G-U-C-I", null, null))
    }

    @Test
    fun `explicit color argb overrides the swatch in the image id`() {
        // A received spot whose <color argb> differs from its named swatch keys
        // a distinct image so the dot is tinted to the sender's actual colour.
        val named = TakIconRegistry.styleImageId("b-m-p-s-m", "COT_MAPPING_SPOTMAP/red", null)
        val custom = TakIconRegistry.styleImageId("b-m-p-s-m", "COT_MAPPING_SPOTMAP/red", 0xFF00FF00.toInt())
        assertNotNull(custom)
        assertTrue(named != custom)
    }

    // ---- Colour normalisation -------------------------------------------

    @Test
    fun `normalizeArgb treats a zero alpha as opaque`() {
        // CoT <color> often omits alpha (0x00RRGGBB); a 0 alpha must render as
        // opaque or the dot would be invisible.
        val opaque = TakIconRegistry.normalizeArgb(0x0000FF00)
        assertEquals(0xFF.toLong(), ((opaque.toLong() shr 24) and 0xFF))
    }

    @Test
    fun `normalizeArgb leaves an explicit alpha untouched`() {
        assertEquals(0xFF112233.toInt(), TakIconRegistry.normalizeArgb(0xFF112233.toInt()))
    }

    // ---- Pack catalogue (selection framework) ---------------------------

    @Test
    fun `spot map pack is bundled and offers every swatch for selection`() {
        val spotPack = TakIconRegistry.availablePacks.first { it == TakIconRegistry.TakIconPack.SPOT_MAP }
        assertTrue(spotPack.bundled)
        assertEquals(TakIconRegistry.SpotIcon.entries.size, TakIconRegistry.selectableSpotIcons.size)
    }

    @Test
    fun `markers and google packs are modeled but flagged unbundled`() {
        // Phase-1 asset blocker: the bitmap packs are known to the framework so
        // they slot in behind the same API later, but must report unbundled so
        // we never claim an icon we can't actually draw.
        assertFalse(TakIconRegistry.TakIconPack.MARKERS.bundled)
        assertFalse(TakIconRegistry.TakIconPack.GOOGLE.bundled)
        // The Google UID matches ATAK's canonical iconset UID so an inbound
        // Google iconsetpath would key to the right (future) pack.
        assertEquals("f7f71666-8b28-4b57-9fbb-e38e61d33b79", TakIconRegistry.TakIconPack.GOOGLE.uid)
    }
}
