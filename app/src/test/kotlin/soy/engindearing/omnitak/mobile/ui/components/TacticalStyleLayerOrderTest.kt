package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #139 follow-up — markers must render on downloaded/imported raster maps.
 *
 * KmlOverlayRenderer inserts every cached/imported raster overlay with
 * `addLayerAbove(layer, "basemap-tiles")`, so the overlay sits just above the
 * basemap but below every operational style overlay (grid, measurements,
 * aircraft) and the self-marker. (Contacts and drawings render via native
 * annotations, which always draw above style layers.) That ordering is only
 * correct if `basemap-tiles` really is the bottom layer with the overlays
 * stacked above it. This pins the buildTacticalStyle layer order so the
 * anchor can't drift.
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
            "measurement-line",
            "measurement-points",
            "measurement-labels",
            "aircraft-circle",
            "aircraft-label",
        )
        for (id in overlaysAboveBasemap) {
            val idx = ids.indexOf(id)
            assertTrue("overlay layer '$id' is missing from the tactical style", idx >= 0)
            assertTrue("overlay layer '$id' must sit ABOVE basemap-tiles", idx > 0)
        }
    }
}
