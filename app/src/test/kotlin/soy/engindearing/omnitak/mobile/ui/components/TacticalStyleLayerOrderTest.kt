package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #139 follow-up — markers must render on downloaded/imported raster maps.
 *
 * KmlOverlayRenderer inserts every cached/imported raster overlay with
 * `addLayerAbove(layer, "basemap-tiles")`, so the overlay sits just above the
 * basemap but below every operational overlay (grid, drawings, measurements,
 * contacts, aircraft) and the self-marker. That ordering is only correct if
 * `basemap-tiles` really is the bottom layer with the overlays stacked above
 * it. This pins the buildTacticalStyle layer order so the anchor can't drift.
 */
class TacticalStyleLayerOrderTest {

    private fun layerIdsInOrder(json: String): List<String> =
        Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.toList()

    @Test fun basemap_is_the_bottom_layer_below_every_overlay() {
        val json = buildTacticalStyle(
            name = "OSM",
            basemapTiles = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
            attribution = "© OpenStreetMap contributors",
        )
        val ids = layerIdsInOrder(json)

        // The raster-overlay anchor must exist and be the very bottom layer.
        assertEquals("basemap-tiles must be the bottom (first) layer", 0, ids.indexOf("basemap-tiles"))

        // Every operational overlay + the marker layers must stack ABOVE it, so
        // a raster inserted just above basemap-tiles renders beneath them.
        val overlaysAboveBasemap = listOf(
            "grid-line",
            "drawings-fill",
            "drawings-outline",
            "measurement-line",
            "contacts-circles",
            "contacts-labels",
            "aircraft-circle",
            "aircraft-label",
        )
        for (id in overlaysAboveBasemap) {
            val idx = ids.indexOf(id)
            assertTrue("overlay layer '$id' is missing from the tactical style", idx >= 0)
            assertTrue("overlay layer '$id' must sit ABOVE basemap-tiles", idx > 0)
        }
    }

    // #206 — a hardcoded "maxzoom": 20 on every basemap source let MapLibre
    // request tiles past what some providers actually serve (OpenTopoMap
    // tops out at 17, OSM standard at 19), which read as "the map goes
    // blank" when the operator zoomed in. Pin each provider's real,
    // probe-measured ceiling so it can't silently drift back to one shared
    // guess — see PR #206 for the HTTP probe table.

    private fun basemapMaxZoom(json: String): Int? =
        Regex("\"basemap\"[\\s\\S]*?\"maxzoom\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull()

    @Test fun buildTacticalStyle_defaults_maxzoom_to_the_conservative_custom_ceiling() {
        val json = buildTacticalStyle("X", "https://example.test/{z}/{x}/{y}.png", "attr")
        assertEquals(BASEMAP_MAXZOOM_CUSTOM_DEFAULT, basemapMaxZoom(json))
    }

    @Test fun buildTacticalStyle_honors_an_explicit_maxzoom() {
        val json = buildTacticalStyle("X", "https://example.test/{z}/{x}/{y}.png", "attr", maxZoom = 11)
        assertEquals(11, basemapMaxZoom(json))
    }

    @Test fun osm_style_maxzoom_is_19_http_400_at_20() {
        assertEquals(BASEMAP_MAXZOOM_OSM, basemapMaxZoom(TACTICAL_STYLE_OSM))
        assertEquals(19, basemapMaxZoom(TACTICAL_STYLE_OSM))
    }

    @Test fun topo_style_maxzoom_is_17_placeholder_tile_beyond_that() {
        assertEquals(BASEMAP_MAXZOOM_TOPO, basemapMaxZoom(TACTICAL_STYLE_TOPO))
        assertEquals(17, basemapMaxZoom(TACTICAL_STYLE_TOPO))
    }

    @Test fun satellite_style_maxzoom_is_22_matching_the_cesium_3d_clamp() {
        assertEquals(BASEMAP_MAXZOOM_SATELLITE, basemapMaxZoom(TACTICAL_STYLE_SATELLITE))
        assertEquals(22, basemapMaxZoom(TACTICAL_STYLE_SATELLITE))
    }

    @Test fun dark_matter_style_maxzoom_stays_conservative() {
        // CARTO returns HTTP 200 at every zoom but it's always the same
        // "API KEY REQUIRED" placeholder (see PR #206) and this style isn't
        // reachable from the app's basemap picker today — kept conservative
        // rather than tuned, since there's no real data at any zoom to tune
        // against.
        assertEquals(BASEMAP_MAXZOOM_DARK_MATTER, basemapMaxZoom(TACTICAL_STYLE_DARK_MATTER))
        assertEquals(19, basemapMaxZoom(TACTICAL_STYLE_DARK_MATTER))
    }

    @Test fun custom_wmts_style_maxzoom_defaults_to_19() {
        val json = soy.engindearing.omnitak.mobile.ui.components.styleJsonForProvider(
            soy.engindearing.omnitak.mobile.data.MapProvider.WMTS_CUSTOM,
            customTileUrl = "https://tiles.example/{z}/{x}/{y}.png",
        )
        assertEquals(BASEMAP_MAXZOOM_CUSTOM_DEFAULT, basemapMaxZoom(json))
    }

    @Test fun basemapMaxZoomFor_matches_each_style_constants_maxzoom() {
        assertEquals(BASEMAP_MAXZOOM_OSM, basemapMaxZoomFor(soy.engindearing.omnitak.mobile.data.MapProvider.OSM_RASTER))
        assertEquals(BASEMAP_MAXZOOM_TOPO, basemapMaxZoomFor(soy.engindearing.omnitak.mobile.data.MapProvider.TOPO_HINT))
        assertEquals(BASEMAP_MAXZOOM_SATELLITE, basemapMaxZoomFor(soy.engindearing.omnitak.mobile.data.MapProvider.SATELLITE_HINT))
        assertEquals(BASEMAP_MAXZOOM_CUSTOM_DEFAULT, basemapMaxZoomFor(soy.engindearing.omnitak.mobile.data.MapProvider.WMTS_CUSTOM))
    }
}
