package soy.engindearing.omnitak.mobile.domain.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TileMathTest {

    @Test fun tile_xy_san_francisco_at_z16_is_inside_known_tile_block() {
        // Pins the SF (37.7749, -122.4194) → tile XY at z16 to guard
        // against an accidental sign or unit regression. Computed
        // values, not externally sourced, but the round-trip
        // (tileNorthWest → tileXY) test below independently validates
        // the math.
        val (x, y) = TileMath.tileXY(37.7749, -122.4194, 16)
        assertEquals(10482, x)
        assertEquals(25331, y)
    }

    @Test fun tile_xy_z0_collapses_globe_into_single_tile() {
        // At zoom 0 the whole world is tile (0,0).
        val (x, y) = TileMath.tileXY(0.0, 0.0, 0)
        assertEquals(0, x)
        assertEquals(0, y)
    }

    @Test fun tile_xy_round_trip_through_north_west_returns_same_tile() {
        // Pick the NW corner of an arbitrary tile and run it back through
        // tileXY — should land on the same tile.
        val (lat, lon) = TileMath.tileNorthWest(x = 100, y = 200, zoom = 10)
        val (x2, y2) = TileMath.tileXY(lat, lon, 10)
        assertEquals(100, x2)
        assertEquals(200, y2)
    }

    @Test fun tiles_in_bbox_covers_full_rectangle() {
        // 1×1 degree bbox around the equator at z6 should be a small grid
        // of tiles. Just verify the count is positive and the result is
        // a rectangle (xCount × yCount).
        val tiles = TileMath.tilesInBbox(
            north = 1.0, south = 0.0,
            east = 1.0, west = 0.0,
            zoom = 6,
        )
        assertTrue("expected ≥1 tile, got ${tiles.size}", tiles.isNotEmpty())
        // Should be a perfect rectangular grid.
        val xs = tiles.map { it.first }.toSet()
        val ys = tiles.map { it.second }.toSet()
        assertEquals(xs.size * ys.size, tiles.size)
    }

    @Test fun count_tiles_matches_enumeration() {
        val count = TileMath.countTiles(
            north = 47.6, south = 47.5,
            east = -122.3, west = -122.4,
            zoomMin = 12, zoomMax = 14,
        )
        var enumerated = 0
        for (z in 12..14) {
            enumerated += TileMath.tilesInBbox(47.6, 47.5, -122.3, -122.4, z).size
        }
        assertEquals(enumerated, count)
    }

    @Test fun expand_template_substitutes_z_x_y() {
        val url = TileMath.expandTemplate(
            template = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
            x = 100, y = 200, z = 10,
        )
        assertEquals("https://tile.openstreetmap.org/10/100/200.png", url)
    }

    @Test fun expand_template_supports_atak_style_placeholders() {
        val url = TileMath.expandTemplate(
            template = "https://host/{\$z}/{\$x}/{\$y}.png",
            x = 100, y = 200, z = 10,
        )
        assertEquals("https://host/10/100/200.png", url)
    }

    @Test fun expand_template_rejects_template_with_leftover_braces() {
        // Missing placeholder substitution = not a usable tile URL.
        val url = TileMath.expandTemplate(
            template = "https://host/{z}/{x}/{y}/{layer}.png",
            x = 100, y = 200, z = 10,
        )
        assertNull(url)
    }

    @Test fun parse_tile_extracts_zxy_from_osm_url() {
        val tile = TileMath.parseTileFromUrl("https://tile.openstreetmap.org/10/100/200.png")
        assertNotNull(tile)
        assertEquals(Triple(10, 100, 200), tile)
    }

    @Test fun parse_tile_extracts_zxy_from_url_with_query_string() {
        val tile = TileMath.parseTileFromUrl(
            "https://host/maptile/12/345/678.png?api_key=abc&style=topo",
        )
        assertNotNull(tile)
        assertEquals(Triple(12, 345, 678), tile)
    }

    @Test fun parse_tile_returns_null_for_non_tile_url() {
        assertNull(TileMath.parseTileFromUrl("https://example.com/about"))
    }

    @Test fun bbox_around_point_brackets_the_centre() {
        val bbox = TileMath.bboxAroundPoint(lat = 47.6, lon = -122.3, radiusM = 1_000.0)
        assertTrue(bbox.contains(47.6, -122.3))
        assertFalse(bbox.contains(47.65, -122.3))
    }
}
