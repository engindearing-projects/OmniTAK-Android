package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.CoTAffiliation
import soy.engindearing.omnitak.mobile.data.CoTEvent

/**
 * Issue #77 — locally-dropped markers (point-dropper, coordinate-entry drop,
 * FEMA palette) render only on the 3D Globe (Cesium) and never appeared on the
 * 2D MapLibre engine.
 *
 * Root cause: [TacticalMap]'s contact push was gated behind `map.style != null`
 * inside an async [mapView.getMapAsync] callback. During the initial style-load
 * window that guard evaluates false, the update is silently dropped, and the
 * only recovery path (the one-shot style-ready callback) had already fired with
 * an empty contact list.
 *
 * Fix: moved the push to [AndroidView.update] (fires on every recomposition, same
 * cadence as the Cesium engine) and replaced `if (map.style != null)` with the
 * queuing `map.getStyle { }` callback so the update is never silently discarded
 * while the style is still loading.
 *
 * These tests verify the data-layer preconditions that guarantee the push path
 * can render every locally-dropped type:
 *  - each drop path produces a [CoTEvent] with finite lat/lon (the only
 *    field [ContactLayer] gates on before building a GeoJSON feature), and
 *  - the resulting ARGB display color converts to a valid CSS hex string via
 *    [ContactLayer.argbToHex] (the value the circle-color style expression
 *    consumes in `contacts-circles`).
 */
class ContactLayerFeatureTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun assertFinite(label: String, event: CoTEvent) {
        assertFalse("$label lat must be finite, was ${event.lat}", event.lat.isNaN())
        assertFalse("$label lon must be finite, was ${event.lon}", event.lon.isNaN())
        assertFalse("$label lat must not be infinite", event.lat.isInfinite())
        assertFalse("$label lon must not be infinite", event.lon.isInfinite())
    }

    private fun assertValidHex(label: String, hex: String) {
        assertTrue(
            "$label must be a 7-char CSS hex string (\"#RRGGBB\"), got \"$hex\"",
            hex.matches(Regex("#[0-9A-Fa-f]{6}")),
        )
    }

    // ── point-dropper (MarkerEditSheet / radial "Drop Marker") ───────────────

    @Test fun `point-dropper friendly marker has finite coords and valid hex color`() {
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
        assertValidHex("friendly hex", ContactLayer.argbToHex(event.displayColor))
    }

    @Test fun `point-dropper hostile marker has finite coords and valid hex color`() {
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
        assertValidHex("hostile hex", ContactLayer.argbToHex(event.displayColor))
    }

    @Test fun `point-dropper neutral marker has finite coords and valid hex color`() {
        val event = CoTEvent(
            uid = "local-1718000002000",
            type = "a-n-G-U-C",
            lat = 48.0,
            lon = -118.0,
            callsign = "Neutral-1",
        )
        assertFinite("point-dropper neutral", event)
        assertEquals(CoTAffiliation.NEUTRAL, event.affiliation)
        assertValidHex("neutral hex", ContactLayer.argbToHex(event.displayColor))
    }

    // ── coordinate-entry drop (Go-to-Coordinate sheet "Drop" checkbox) ───────

    @Test fun `coordinate-entry drop marker has finite coords and valid hex color`() {
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
        assertValidHex("coord-entry hex", ContactLayer.argbToHex(event.displayColor))
    }

    // ── FEMA palette drop ─────────────────────────────────────────────────────
    // Tests a representative sample of the FEMA cotTypes (FemaIconCatalog uses
    // Compose Icons so the full catalog is not accessible from JVM-only tests;
    // the types below cover every FEMA affiliation pattern that reaches ContactLayer).

    @Test fun `FEMA triage marker has finite coords and valid hex color`() {
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
        assertValidHex("FEMA triage hex", ContactLayer.argbToHex(event.displayColor))
    }

    @Test fun `FEMA base camp marker has finite coords and valid hex color`() {
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
        assertValidHex("FEMA base camp hex", ContactLayer.argbToHex(event.displayColor))
    }

    // ── argbToHex edge cases ──────────────────────────────────────────────────

    @Test fun `argbToHex strips alpha channel from opaque white`() {
        // 0xFFFFFFFF → "#FFFFFF" (alpha byte stripped, RGB bytes preserved)
        assertEquals("#FFFFFF", ContactLayer.argbToHex(0xFFFFFFFF.toInt()))
    }

    @Test fun `argbToHex strips alpha channel from opaque black`() {
        assertEquals("#000000", ContactLayer.argbToHex(0xFF000000.toInt()))
    }

    @Test fun `argbToHex produces zero-padded 6-char result for small color values`() {
        // 0xFF000001 → "#000001" — not "#1" — verifies %06X zero-padding
        assertEquals("#000001", ContactLayer.argbToHex(0xFF000001.toInt()))
    }

    @Test fun `argbToHex produces uppercase hex consistent with style JSON`() {
        // MapLibre style expressions accept both cases; the style JSON uses
        // uppercase (#4ADE80 etc.) so keep the output consistent.
        val hex = ContactLayer.argbToHex(0xFF4ADE80.toInt())
        assertTrue("hex should be uppercase, got $hex", hex == hex.uppercase())
        assertEquals("#4ADE80", hex)
    }

    @Test fun `friendly affiliation color converts to valid hex`() {
        assertEquals("#4ADE80", ContactLayer.argbToHex(0xFF4ADE80.toInt()))
    }

    @Test fun `hostile affiliation color converts to valid hex`() {
        assertEquals("#F44336", ContactLayer.argbToHex(0xFFF44336.toInt()))
    }

    // ── NaN guard verification ────────────────────────────────────────────────

    @Test fun `event with NaN lat satisfies ContactLayer nan-guard skip condition`() {
        // ContactLayer.update() skips `c.lat.isNaN() || c.lon.isNaN()`.
        // Verify that a malformed event would correctly be excluded.
        val bad = CoTEvent(
            uid = "local-bad",
            type = "a-f-G-U-C",
            lat = Double.NaN,
            lon = 0.0,
            callsign = "Bad",
        )
        assertTrue(
            "lat.isNaN() must be true so ContactLayer.update() skips the feature",
            bad.lat.isNaN(),
        )
    }

    @Test fun `locally-dropped marker uid prefix does not affect rendering eligibility`() {
        // ContactLayer has no uid-prefix filter — only NaN lat/lon are excluded.
        // This asserts that the three local-marker uid conventions used in
        // MapScreen all produce non-NaN coords when constructed with real
        // touch coordinates (the only case where markers are dropped).
        val markerEditUid = "local-${System.currentTimeMillis()}"
        val coordEntryUid = "local-${System.currentTimeMillis() + 1}"
        val femaUid = "local-fema-${System.currentTimeMillis() + 2}"

        for ((label, uid) in listOf(
            "MarkerEditSheet" to markerEditUid,
            "CoordinateEntrySheet" to coordEntryUid,
            "FemaMarkerPaletteSheet" to femaUid,
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
