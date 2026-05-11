package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class TileUrlPlaceholderTest {

    @Test fun maplibre_xyz_url_is_unchanged() {
        val url = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        assertEquals(url, normalizeTileUrlPlaceholders(url))
    }

    @Test fun atak_brace_dollar_form_is_normalized() {
        val input = "https://server.arcgisonline.com/ArcGIS/rest/services/USA_Topo_Maps/MapServer/tile/{\$z}/{\$y}/{\$x}"
        val expected = "https://server.arcgisonline.com/ArcGIS/rest/services/USA_Topo_Maps/MapServer/tile/{z}/{y}/{x}"
        assertEquals(expected, normalizeTileUrlPlaceholders(input))
    }

    @Test fun shell_dollar_brace_form_is_normalized() {
        val input = "https://example.org/\${z}/\${x}/\${y}.png"
        val expected = "https://example.org/{z}/{x}/{y}.png"
        assertEquals(expected, normalizeTileUrlPlaceholders(input))
    }

    @Test fun wmts_query_string_with_dollar_braces_is_normalized() {
        val input = "https://data.geopf.fr/wmts?SERVICE=WMTS&request=GetTile&layer=L&TileMatrix={\$z}&TileRow={\$y}&TileCol={\$x}"
        val expected = "https://data.geopf.fr/wmts?SERVICE=WMTS&request=GetTile&layer=L&TileMatrix={z}&TileRow={y}&TileCol={x}"
        assertEquals(expected, normalizeTileUrlPlaceholders(input))
    }

    @Test fun whitespace_is_trimmed() {
        val input = "  https://t.example/{\$z}/{\$x}/{\$y}.png\n"
        val expected = "https://t.example/{z}/{x}/{y}.png"
        assertEquals(expected, normalizeTileUrlPlaceholders(input))
    }

    @Test fun empty_string_passes_through() {
        assertEquals("", normalizeTileUrlPlaceholders(""))
    }

    @Test fun html_entity_amp_is_decoded() {
        // P-E pasted the French IGN WMTS URL out of Discord; ampersands
        // came in as `&amp;`. The literal entity reached MapLibre and no
        // tile request ever matched.
        val input = "https://data.geopf.fr/wmts?SERVICE=WMTS&amp;request=GetTile&amp;TileMatrix={\$z}&amp;TileRow={\$y}&amp;TileCol={\$x}"
        val expected = "https://data.geopf.fr/wmts?SERVICE=WMTS&request=GetTile&TileMatrix={z}&TileRow={y}&TileCol={x}"
        assertEquals(expected, normalizeTileUrlPlaceholders(input))
    }

    @Test fun double_encoded_html_entities_collapse() {
        // Some pastes (Discord, then re-pasted from Discord) end up
        // double-encoded as `&amp;amp;`. Three sweeps over the entity
        // table should collapse them.
        val input = "https://ex/?a=1&amp;amp;b=2&amp;amp;TileMatrix={\$z}&amp;amp;TileRow={\$y}&amp;amp;TileCol={\$x}"
        val expected = "https://ex/?a=1&b=2&TileMatrix={z}&TileRow={y}&TileCol={x}"
        assertEquals(expected, normalizeTileUrlPlaceholders(input))
    }

    @Test fun mixed_token_set_is_normalized() {
        val input = "https://t.example/{\$s}/{\$z}/{\$q}/{\$x}/{\$y}.png"
        val expected = "https://t.example/{s}/{z}/{q}/{x}/{y}.png"
        assertEquals(expected, normalizeTileUrlPlaceholders(input))
    }
}
