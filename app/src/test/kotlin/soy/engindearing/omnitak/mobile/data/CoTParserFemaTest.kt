package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #47 — Inbound CoT carrying a FEMA `<usericon iconsetpath="COT_MAPPING_FEMA/…">`
 * must parse into a [CoTEvent] with a non-null [CoTEvent.iconsetPath] that
 * round-trips through [FemaIconCatalog.iconByIconsetPath] to the correct
 * [FemaIconCatalog.FemaIcon].
 *
 * Context: #29 added outbound FEMA CoT authoring. Inbound parse of FEMA
 * iconsetpath was deferred — [CoTParser] already reads `<usericon>` for Spot
 * Map markers, but the acceptance test for the FEMA catalog lookup
 * (FemaIconCatalog.iconByIconsetPath) on a received event was missing. These
 * tests lock that contract so a peer (iOS or another Android operator) dropping
 * a FEMA marker renders the correct FEMA kind on the receiver.
 *
 * The python listener fixture shape used in the issue description:
 *
 *   <event version="2.0" uid="…" type="a-f-G-I-U-T" …>
 *     <point lat="…" lon="…" hae="0" ce="9999999" le="9999999"/>
 *     <detail>
 *       <contact callsign="CP-1"/>
 *       <usericon iconsetpath="COT_MAPPING_FEMA/incidentCommand/command_post"/>
 *     </detail>
 *   </event>
 */
class CoTParserFemaTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun femaCoT(
        uid: String,
        cotType: String,
        iconsetPath: String,
        callsign: String = "TEST",
    ): String = """
        <event version="2.0" uid="$uid" type="$cotType"
               time="2026-01-01T00:00:00Z" start="2026-01-01T00:00:00Z"
               stale="2026-01-02T00:00:00Z" how="h-g-i-g-o">
          <point lat="47.6588" lon="-117.4260" hae="0" ce="9999999" le="9999999"/>
          <detail>
            <contact callsign="$callsign"/>
            <usericon iconsetpath="$iconsetPath"/>
          </detail>
        </event>
    """.trimIndent()

    // ── #47: parser surfaces iconsetPath on inbound FEMA CoT ─────────────────

    @Test
    fun `inbound FEMA command-post CoT parses to non-null iconsetPath`() {
        val icon = FemaIconCatalog.iconFor(FemaIconCatalog.Kind.COMMAND_POST)!!
        val xml = femaCoT("fema-cp-1", icon.cotType, icon.iconsetPath, "CP-1")

        val event = CoTParser.parse(xml)

        assertNotNull("parser returned null for well-formed FEMA CoT", event)
        assertNotNull(
            "iconsetPath must be non-null on a FEMA inbound event",
            event!!.iconsetPath,
        )
        assertEquals(
            "parsed iconsetPath must match the emitted path byte-for-byte",
            icon.iconsetPath,
            event.iconsetPath,
        )
    }

    @Test
    fun `inbound FEMA iconsetPath round-trips through FemaIconCatalog to the correct kind`() {
        val icon = FemaIconCatalog.iconFor(FemaIconCatalog.Kind.COMMAND_POST)!!
        val xml = femaCoT("fema-cp-2", icon.cotType, icon.iconsetPath)

        val event = CoTParser.parse(xml)!!

        val resolved = FemaIconCatalog.iconByIconsetPath(event.iconsetPath!!)
        assertNotNull(
            "iconByIconsetPath must resolve a received FEMA iconsetpath to a FemaIcon",
            resolved,
        )
        assertEquals(
            "resolved kind must match the emitted kind",
            FemaIconCatalog.Kind.COMMAND_POST,
            resolved!!.kind,
        )
    }

    @Test
    fun `every FEMA kind round-trips from inbound CoT through the catalog`() {
        // All 12 FEMA kinds emitted by a peer must parse back to the same kind.
        // This covers the full iOS ↔ Android matrix.
        for (kind in FemaIconCatalog.Kind.entries) {
            val icon = FemaIconCatalog.iconFor(kind)!!
            val xml = femaCoT("fema-${kind.raw}", icon.cotType, icon.iconsetPath, kind.raw)

            val event = CoTParser.parse(xml)
            assertNotNull("parser returned null for FEMA kind $kind", event)

            val resolved = FemaIconCatalog.iconByIconsetPath(event!!.iconsetPath!!)
            assertNotNull(
                "iconByIconsetPath returned null for kind $kind (path=${icon.iconsetPath})",
                resolved,
            )
            assertEquals(
                "wrong kind resolved for $kind",
                kind,
                resolved!!.kind,
            )
        }
    }

    @Test
    fun `inbound CoT without usericon still parses with null iconsetPath`() {
        // A peer without the FEMA bundle omits the usericon element; the event
        // must still parse. This preserves the fallback path.
        val xml = """
            <event version="2.0" uid="no-icon-1" type="a-f-G-I-U-T"
                   time="2026-01-01T00:00:00Z" start="2026-01-01T00:00:00Z"
                   stale="2026-01-02T00:00:00Z" how="h-g-i-g-o">
              <point lat="47.0" lon="-117.0" hae="0" ce="9999999" le="9999999"/>
              <detail><contact callsign="PLAIN"/></detail>
            </event>
        """.trimIndent()

        val event = CoTParser.parse(xml)
        assertNotNull(event)
        assertNull("iconsetPath must be null when <usericon> is absent", event!!.iconsetPath)
    }

    @Test
    fun `inbound FEMA triage-station CoT resolves to MEDICAL category`() {
        val icon = FemaIconCatalog.iconFor(FemaIconCatalog.Kind.TRIAGE_STATION)!!
        val xml = femaCoT("fema-triage-1", icon.cotType, icon.iconsetPath, "Triage-1")

        val event = CoTParser.parse(xml)!!
        val resolved = FemaIconCatalog.iconByIconsetPath(event.iconsetPath!!)

        assertNotNull(resolved)
        assertEquals(FemaIconCatalog.Category.MEDICAL, resolved!!.category)
        assertEquals(FemaIconCatalog.Kind.TRIAGE_STATION, resolved.kind)
    }

    @Test
    fun `inbound FEMA helicopter-LZ CoT resolves to AVIATION category`() {
        val icon = FemaIconCatalog.iconFor(FemaIconCatalog.Kind.HELICOPTER_LZ)!!
        val xml = femaCoT("fema-lz-1", icon.cotType, icon.iconsetPath, "LZ-1")

        val event = CoTParser.parse(xml)!!
        val resolved = FemaIconCatalog.iconByIconsetPath(event.iconsetPath!!)

        assertNotNull(resolved)
        assertEquals(FemaIconCatalog.Category.AVIATION, resolved!!.category)
    }

    @Test
    fun `inbound FEMA CoT callsign and position are preserved alongside iconsetPath`() {
        // Parser must not lose existing fields when reading usericon.
        val icon = FemaIconCatalog.iconFor(FemaIconCatalog.Kind.BASE_CAMP)!!
        val xml = femaCoT("fema-bc-1", icon.cotType, icon.iconsetPath, "BaseCamp-Alpha")

        val event = CoTParser.parse(xml)!!

        assertEquals("BaseCamp-Alpha", event.callsign)
        assertEquals(47.6588, event.lat, 0.0001)
        assertEquals(-117.4260, event.lon, 0.0001)
        assertEquals(icon.iconsetPath, event.iconsetPath)
    }

    @Test
    fun `non-FEMA iconsetPath on inbound CoT returns null from FemaIconCatalog`() {
        // A Spot Map path or Google path must not accidentally match the FEMA
        // catalog — keeps the two resolution paths independent.
        val xml = femaCoT(
            uid = "spot-not-fema-1",
            cotType = "b-m-p-s-m",
            iconsetPath = "COT_MAPPING_SPOTMAP/red",
            callsign = "SPOT",
        )
        val event = CoTParser.parse(xml)!!
        assertNotNull(event.iconsetPath)
        // Must NOT resolve as a FEMA icon.
        assertNull(
            "A Spot Map iconsetPath must not resolve via FemaIconCatalog",
            FemaIconCatalog.iconByIconsetPath(event.iconsetPath!!),
        )
    }

    // ── #46: TakIconRegistry.handles() covers FEMA iconset paths ─────────────
    // These verify the ContactSymbolLayer routing gate: a received FEMA marker
    // with a parsed iconsetPath must not fall through to the MIL-STD path.

    @Test
    fun `TakIconRegistry handles FEMA command-post iconsetPath`() {
        val icon = FemaIconCatalog.iconFor(FemaIconCatalog.Kind.COMMAND_POST)!!
        assertTrue(
            "TakIconRegistry.handles() must return true for a FEMA iconsetPath so " +
                "ContactSymbolLayer does not fall through to MIL-STD",
            soy.engindearing.omnitak.mobile.data.symbology.TakIconRegistry.handles(
                icon.cotType, icon.iconsetPath,
            ),
        )
    }

    @Test
    fun `TakIconRegistry handles every FEMA kind iconsetPath`() {
        for (kind in FemaIconCatalog.Kind.entries) {
            val icon = FemaIconCatalog.iconFor(kind)!!
            assertTrue(
                "TakIconRegistry.handles() must return true for FEMA kind $kind",
                soy.engindearing.omnitak.mobile.data.symbology.TakIconRegistry.handles(
                    icon.cotType, icon.iconsetPath,
                ),
            )
        }
    }

    @Test
    fun `TakIconRegistry styleImageId returns non-null for FEMA iconsetPath`() {
        val icon = FemaIconCatalog.iconFor(FemaIconCatalog.Kind.COMMAND_POST)!!
        val id = soy.engindearing.omnitak.mobile.data.symbology.TakIconRegistry.styleImageId(
            icon.cotType, icon.iconsetPath, null,
        )
        assertNotNull(
            "styleImageId must be non-null for FEMA kind ${FemaIconCatalog.Kind.COMMAND_POST}",
            id,
        )
        assertTrue(
            "FEMA style image id must be prefixed with 'takicon-fema-' to avoid colliding with MIL-STD",
            id!!.startsWith("takicon-fema-"),
        )
    }

    @Test
    fun `TakIconRegistry styleImageId is unique per FEMA kind`() {
        val ids = FemaIconCatalog.Kind.entries.map { kind ->
            val icon = FemaIconCatalog.iconFor(kind)!!
            soy.engindearing.omnitak.mobile.data.symbology.TakIconRegistry.styleImageId(
                icon.cotType, icon.iconsetPath, null,
            )
        }
        assertEquals(
            "each FEMA kind must produce a distinct style image id",
            ids.size,
            ids.toSet().size,
        )
    }
}
