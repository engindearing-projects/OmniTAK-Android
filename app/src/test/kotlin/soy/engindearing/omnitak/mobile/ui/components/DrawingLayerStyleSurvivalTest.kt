package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.MapProvider

/**
 * Issue #80 — verify that the overlay style infrastructure (drawings-src,
 * drawings-fill, drawings-outline) is present in the style JSON produced by
 * [styleJsonForProvider] and [injectTerrain], in both plain-2D and 3D-terrain
 * modes.
 *
 * [DrawingLayer.update] cannot be invoked from a JVM-only test (it calls
 * MapLibre's [org.maplibre.android.maps.MapLibreMap]), and [DrawingLayer.toFeatures]
 * is private. What we CAN assert — in pure JVM with no Android runtime — is
 * that the style JSON always carries the three drawing artefacts
 * (`drawings-src`, `drawings-fill`, `drawings-outline`) regardless of the
 * [MapProvider] or terrain flag. If those keys are ever absent the
 * [addOnDidFinishLoadingStyleListener] re-push registered in [TacticalMap]
 * would write into a nonexistent source and drawings would disappear again.
 */
class DrawingLayerStyleSurvivalTest {

    // ── helpers ──────────────────────────────────────────────────────────

    private fun assertDrawingKeysPresent(styleJson: String, label: String) {
        assertTrue("$label must contain drawings-src", styleJson.contains("drawings-src"))
        assertTrue("$label must contain drawings-fill", styleJson.contains("drawings-fill"))
        assertTrue("$label must contain drawings-outline", styleJson.contains("drawings-outline"))
    }

    // ── plain style (no terrain) ─────────────────────────────────────────

    @Test fun osm_style_contains_drawing_artefacts() {
        val style = styleJsonForProvider(MapProvider.OSM_RASTER, terrain3d = false)
        assertDrawingKeysPresent(style, "OSM plain style")
    }

    @Test fun topo_style_contains_drawing_artefacts() {
        val style = styleJsonForProvider(MapProvider.TOPO_HINT, terrain3d = false)
        assertDrawingKeysPresent(style, "Topo plain style")
    }

    @Test fun satellite_style_contains_drawing_artefacts() {
        val style = styleJsonForProvider(MapProvider.SATELLITE_HINT, terrain3d = false)
        assertDrawingKeysPresent(style, "Satellite plain style")
    }

    @Test fun custom_wmts_valid_url_contains_drawing_artefacts() {
        val style = styleJsonForProvider(
            MapProvider.WMTS_CUSTOM,
            customTileUrl = "https://example.org/{z}/{x}/{y}.png",
            terrain3d = false,
        )
        assertDrawingKeysPresent(style, "Custom WMTS plain style")
    }

    @Test fun custom_wmts_invalid_url_falls_back_to_osm_with_drawing_artefacts() {
        val style = styleJsonForProvider(
            MapProvider.WMTS_CUSTOM,
            customTileUrl = "",
            terrain3d = false,
        )
        assertDrawingKeysPresent(style, "Custom WMTS fallback-OSM plain style")
    }

    // ── terrain-injected style ────────────────────────────────────────────

    @Test fun osm_terrain_style_contains_drawing_artefacts() {
        val style = styleJsonForProvider(MapProvider.OSM_RASTER, terrain3d = true)
        assertDrawingKeysPresent(style, "OSM terrain style")
    }

    @Test fun topo_terrain_style_contains_drawing_artefacts() {
        val style = styleJsonForProvider(MapProvider.TOPO_HINT, terrain3d = true)
        assertDrawingKeysPresent(style, "Topo terrain style")
    }

    @Test fun satellite_terrain_style_contains_drawing_artefacts() {
        val style = styleJsonForProvider(MapProvider.SATELLITE_HINT, terrain3d = true)
        assertDrawingKeysPresent(style, "Satellite terrain style")
    }

    @Test fun terrain_injection_does_not_break_drawing_artefacts() {
        // Verify that injectTerrain specifically doesn't remove the drawing
        // source / layers when called directly on a known-good base style.
        val base = buildTacticalStyle(
            "Test",
            "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
            "© OSM contributors",
        )
        val terrainStyle = injectTerrain(base)
        assertDrawingKeysPresent(terrainStyle, "injectTerrain result")
    }

    // ── terrain-injected style also contains terrain-dem ─────────────────

    @Test fun terrain_injection_adds_terrain_dem_source() {
        val style = styleJsonForProvider(MapProvider.OSM_RASTER, terrain3d = true)
        assertTrue(
            "Terrain style must contain terrain-dem source",
            style.contains("terrain-dem"),
        )
    }

    @Test fun plain_style_does_not_contain_terrain_dem_source() {
        val style = styleJsonForProvider(MapProvider.OSM_RASTER, terrain3d = false)
        assertTrue(
            "Plain style must NOT contain terrain-dem source",
            !style.contains("terrain-dem"),
        )
    }
}
