package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.MapProvider

/**
 * #266 - the credits of the built-in basemaps.
 *
 * MapLibre's attribution dialog lists only credits that are HTML links: it
 * reads the URL spans of each source's `attribution`. Plain text gave a dialog
 * with no credits at all, so the OpenStreetMap, OpenTopoMap, Esri and CARTO
 * credits were never shown anywhere in the app.
 */
class BasemapAttributionTest {

    private val attributionField = Regex("\"attribution\":\\s*\"([^\"]*)\"")

    private fun attributionsOf(styleJson: String): List<String> =
        attributionField.findAll(styleJson).map { it.groupValues[1] }.toList()

    private val builtIn = listOf(
        MapProvider.OSM_RASTER,
        MapProvider.TOPO_HINT,
        MapProvider.SATELLITE_HINT,
    )

    @Test fun `every built-in basemap credits its source with a link`() {
        for (provider in builtIn) {
            val credits = attributionsOf(styleJsonForProvider(provider))
            assertTrue("$provider has an attribution", credits.isNotEmpty())
            for (credit in credits) {
                assertTrue("$provider: \"$credit\" is a link", credit.contains("<a href='https://"))
                assertTrue("$provider: \"$credit\" closes its link", credit.contains("</a>"))
            }
        }
        val dark = attributionsOf(TACTICAL_STYLE_DARK_MATTER)
        assertTrue(dark.single().contains("<a href='https://carto.com/attributions'>"))
    }

    @Test fun `OpenStreetMap is credited wherever its data is used`() {
        // OSM tiles, OpenTopoMap (built on OSM) and CARTO (OSM data) all owe the credit.
        for (style in listOf(TACTICAL_STYLE_OSM, TACTICAL_STYLE_TOPO, TACTICAL_STYLE_DARK_MATTER)) {
            assertTrue(attributionsOf(style).single().contains("openstreetmap.org/copyright"))
        }
    }

    @Test fun `the links use single quotes so the style JSON stays valid`() {
        for (provider in builtIn) {
            for (credit in attributionsOf(styleJsonForProvider(provider))) {
                assertFalse("$provider: a double quote inside \"$credit\" would end the JSON string", credit.contains('"'))
            }
        }
    }

    @Test fun `the 3D terrain source is credited too`() {
        val credits = attributionsOf(styleJsonForProvider(MapProvider.OSM_RASTER, terrain3d = true))
        assertEquals("the basemap and the terrain", 2, credits.size)
        assertTrue(credits.any { it.contains("registry.opendata.aws/terrain-tiles") })
    }
}
