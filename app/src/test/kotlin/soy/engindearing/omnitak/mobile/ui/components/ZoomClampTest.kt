package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.MapProvider

/**
 * #206 — the pure math behind the camera zoom clamp. A style-source
 * `maxzoom` only controls MapLibre's own overzoom (stretching), it does not
 * stop the camera from zooming further; [zoomClampFor] is the ceiling
 * that's actually applied via `MapLibreMap.setMaxZoomPreference` (see
 * [TacticalMap] and MapScreen's `LaunchedEffect(mbtilesOverlays, ...)`).
 */
class ZoomClampTest {

    @Test fun clamp_is_basemap_max_plus_the_overzoom_allowance_with_no_overlays() {
        assertEquals(19.0 + OVERZOOM_ALLOWANCE, zoomClampFor(19.0, emptyList()), 0.0001)
        assertEquals(17.0 + OVERZOOM_ALLOWANCE, zoomClampFor(17.0, emptyList()), 0.0001)
    }

    @Test fun clamp_widens_to_a_higher_visible_mbtiles_overlay_maxzoom() {
        // Basemap (Topo, 17) is lower than an imported MBTiles overlay (24)
        // — the overlay must win, or its own high-res tiles get clamped out.
        val clamp = zoomClampFor(BASEMAP_MAXZOOM_TOPO.toDouble(), listOf(24.0))
        assertEquals(24.0 + OVERZOOM_ALLOWANCE, clamp, 0.0001)
    }

    @Test fun clamp_ignores_a_lower_mbtiles_overlay_maxzoom_than_the_basemap() {
        val clamp = zoomClampFor(BASEMAP_MAXZOOM_SATELLITE.toDouble(), listOf(10.0, 12.0))
        assertEquals(BASEMAP_MAXZOOM_SATELLITE + OVERZOOM_ALLOWANCE, clamp, 0.0001)
    }

    @Test fun clamp_picks_the_highest_of_several_mbtiles_overlays() {
        val clamp = zoomClampFor(15.0, listOf(18.0, 24.0, 20.0))
        assertEquals(24.0 + OVERZOOM_ALLOWANCE, clamp, 0.0001)
    }

    @Test fun basemapMaxZoomFor_every_provider_is_covered() {
        for (provider in MapProvider.entries) {
            // Must not throw — every enum case is handled — and must be a
            // sane, positive zoom level.
            val max = basemapMaxZoomFor(provider)
            assert(max in 1..24) { "basemapMaxZoomFor($provider) = $max is out of a sane raster zoom range" }
        }
    }

    @Test fun extractBasemapMaxZoom_reads_the_basemap_sources_own_maxzoom() {
        val json = buildTacticalStyle("X", "https://example.test/{z}/{x}/{y}.png", "attr", maxZoom = 17)
        assertEquals(17.0, extractBasemapMaxZoom(json))
    }

    @Test fun extractBasemapMaxZoom_is_not_fooled_by_the_terrain_dem_sources_maxzoom() {
        // injectTerrain splices a "terrain-dem" source (maxzoom 14) in
        // ahead of "basemap" in the JSON text — the basemap's OWN maxzoom
        // (22, here) must still be the one that comes back, not the DEM's.
        val plain = buildTacticalStyle("X", "https://example.test/{z}/{x}/{y}.png", "attr", maxZoom = 22)
        val withTerrain = injectTerrain(plain)

        assert(withTerrain.contains("terrain-dem")) { "sanity: injectTerrain should have added the DEM source" }
        assertEquals(22.0, extractBasemapMaxZoom(withTerrain))
    }

    @Test fun extractBasemapMaxZoom_returns_null_for_json_with_no_basemap_source() {
        assertNull(extractBasemapMaxZoom("""{"version":8,"sources":{},"layers":[]}"""))
        assertNull(extractBasemapMaxZoom(""))
    }

    @Test fun extractBasemapMaxZoom_matches_basemapMaxZoomFor_for_every_real_provider_style() {
        // The two derivation paths (JSON round-trip vs direct enum lookup)
        // must agree, or TacticalMap's own style-loaded clamp (JSON-based)
        // and MapScreen's clamp (enum-based) would fight each other.
        assertEquals(BASEMAP_MAXZOOM_OSM.toDouble(), extractBasemapMaxZoom(TACTICAL_STYLE_OSM))
        assertEquals(BASEMAP_MAXZOOM_TOPO.toDouble(), extractBasemapMaxZoom(TACTICAL_STYLE_TOPO))
        assertEquals(BASEMAP_MAXZOOM_SATELLITE.toDouble(), extractBasemapMaxZoom(TACTICAL_STYLE_SATELLITE))
        assertEquals(BASEMAP_MAXZOOM_DARK_MATTER.toDouble(), extractBasemapMaxZoom(TACTICAL_STYLE_DARK_MATTER))
    }
}
