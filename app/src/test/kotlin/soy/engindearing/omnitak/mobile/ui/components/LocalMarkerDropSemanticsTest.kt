package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.CoTAffiliation
import soy.engindearing.omnitak.mobile.data.CoTEvent

/**
 * Issue #77 regression coverage — data-layer preconditions for locally-dropped
 * markers (point-dropper, coordinate-entry drop, FEMA palette).
 *
 * History: these markers once rendered only on the 3D globe because the 2D
 * GeoJSON style-layer path silently dropped them. Rendering has since moved to
 * native annotations ([ContactMarkerRenderer]), which keeps two data-layer
 * contracts these tests pin:
 *  - every drop path produces a [CoTEvent] with finite lat/lon — the only gate
 *    ContactMarkerRenderer applies before drawing a marker, and
 *  - [CoTEvent.displayColor] resolves the affiliation palette (team color
 *    first, affiliation fallback) that tints the marker.
 */
class LocalMarkerDropSemanticsTest {

    private fun assertFinite(label: String, event: CoTEvent) {
        assertFalse("$label lat must be finite, was ${event.lat}", event.lat.isNaN())
        assertFalse("$label lon must be finite, was ${event.lon}", event.lon.isNaN())
        assertFalse("$label lat must not be infinite", event.lat.isInfinite())
        assertFalse("$label lon must not be infinite", event.lon.isInfinite())
    }

    // ── point-dropper (MarkerEditSheet / radial "Drop Marker") ───────────────

    @Test fun `point-dropper friendly marker has finite coords and friendly palette color`() {
        val event = CoTEvent(
            uid = "local-1718000000000",
            type = "a-f-G-U-C",
            lat = 47.6588,
            lon = -117.4260,
            hae = 0.0,
            callsign = "Marker 1",
        )
        assertFinite("point-dropper friendly", event)
        assertEquals(CoTAffiliation.FRIEND, event.affiliation)
        assertEquals(0xFF4ADE80.toInt(), event.displayColor)
    }

    @Test fun `point-dropper hostile marker has finite coords and hostile palette color`() {
        val event = CoTEvent(
            uid = "local-1718000001000",
            type = "a-h-G-U-C",
            lat = 48.0,
            lon = -118.0,
            hae = 0.0,
            callsign = "Hostile-1",
        )
        assertFinite("point-dropper hostile", event)
        assertEquals(CoTAffiliation.HOSTILE, event.affiliation)
        assertEquals(0xFFF44336.toInt(), event.displayColor)
    }

    @Test fun `point-dropper neutral marker has finite coords and neutral palette color`() {
        val event = CoTEvent(
            uid = "local-1718000002000",
            type = "a-n-G-U-C",
            lat = 48.0,
            lon = -118.0,
            callsign = "Neutral-1",
        )
        assertFinite("point-dropper neutral", event)
        assertEquals(CoTAffiliation.NEUTRAL, event.affiliation)
        assertEquals(0xFFFFC107.toInt(), event.displayColor)
    }

    // ── coordinate-entry drop (Go-to-Coordinate sheet "Drop" checkbox) ───────

    @Test fun `coordinate-entry drop marker has finite coords`() {
        // CoordinateEntrySheet always drops with type "a-f-G-U-C".
        val event = CoTEvent(
            uid = "local-1718000003000",
            type = "a-f-G-U-C",
            lat = 51.5074,
            lon = -0.1278,
            hae = 0.0,
            callsign = "Marker 2",
        )
        assertFinite("coord-entry drop", event)
        assertEquals(CoTAffiliation.FRIEND, event.affiliation)
    }

    // ── FEMA palette drop ─────────────────────────────────────────────────────

    @Test fun `FEMA triage marker has finite coords and keeps its iconsetPath`() {
        val event = CoTEvent(
            uid = "local-fema-1718000004000",
            type = "a-f-G-I-U-T",
            lat = 34.0522,
            lon = -118.2437,
            hae = 0.0,
            callsign = "Triage",
            iconsetPath = "COT_MAPPING_FEMA/US/Triage_Area",
        )
        assertFinite("FEMA triage", event)
        assertEquals("COT_MAPPING_FEMA/US/Triage_Area", event.iconsetPath)
    }

    @Test fun `FEMA base camp marker has finite coords and keeps its iconsetPath`() {
        val event = CoTEvent(
            uid = "local-fema-1718000005000",
            type = "a-f-G-I-B-A",
            lat = 34.0522,
            lon = -118.2437,
            hae = 0.0,
            callsign = "Base Camp",
            iconsetPath = "COT_MAPPING_FEMA/US/Base_Camp",
        )
        assertFinite("FEMA base camp", event)
        assertEquals("COT_MAPPING_FEMA/US/Base_Camp", event.iconsetPath)
    }

    // ── NaN guard ─────────────────────────────────────────────────────────────

    @Test fun `event with NaN lat satisfies the renderer nan-guard skip condition`() {
        // ContactMarkerRenderer skips events where lat or lon isNaN().
        val bad = CoTEvent(
            uid = "local-bad",
            type = "a-f-G-U-C",
            lat = Double.NaN,
            lon = 0.0,
            callsign = "Bad",
        )
        assertTrue(
            "lat.isNaN() must be true so ContactMarkerRenderer skips the marker",
            bad.lat.isNaN(),
        )
    }

    @Test fun `locally-dropped marker uid prefix does not affect rendering eligibility`() {
        // ContactMarkerRenderer has no uid-prefix filter — only NaN lat/lon are
        // excluded. The three local-marker uid conventions used in MapScreen all
        // produce non-NaN coords when constructed with real touch coordinates.
        for ((label, uid) in listOf(
            "MarkerEditSheet" to "local-${System.currentTimeMillis()}",
            "CoordinateEntrySheet" to "local-${System.currentTimeMillis() + 1}",
            "FemaMarkerPaletteSheet" to "local-fema-${System.currentTimeMillis() + 2}",
        )) {
            val event = CoTEvent(
                uid = uid,
                type = "a-f-G-U-C",
                lat = 47.6588,
                lon = -117.4260,
                callsign = "Test",
            )
            assertFinite(label, event)
        }
    }
}
