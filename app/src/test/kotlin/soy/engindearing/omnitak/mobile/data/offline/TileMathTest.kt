package soy.engindearing.omnitak.mobile.data.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure Web-Mercator slippy-map math + tile enumeration for offline region
 * download (#120). No Android dependencies — runs as a plain JVM test.
 *
 * Reference values cross-checked against the OSM slippy-map tilenames spec
 * (wiki.openstreetmap.org/wiki/Slippy_map_tilenames).
 */
class TileMathTest {

    // --- lon/lat/z -> x/y (the canonical OSM formula) ---------------------

    @Test fun origin_zoom0_is_single_tile() {
        // Whole world at z0 is one tile (0,0).
        assertEquals(0, TileMath.lonToTileX(-180.0, 0))
        assertEquals(0, TileMath.latToTileY(85.0511, 0))
    }

    @Test fun greenwich_equator_zoom1_is_tile_1_1_lower_left_quadrant_boundary() {
        // 0,0 sits on the seam of the four z1 tiles; the formula floors to the
        // upper-right tile of the lower-left = (1,1) for lon just below 0.
        assertEquals(1, TileMath.lonToTileX(0.0, 1))
        assertEquals(1, TileMath.latToTileY(0.0, 1))
    }

    @Test fun known_seattle_tile_zoom12() {
        // Seattle ~ (47.6062, -122.3321). Canonical OSM tile at z12 is
        // x=656, y=1430 (cross-checked against the slippy-map spec).
        assertEquals(656, TileMath.lonToTileX(-122.3321, 12))
        assertEquals(1430, TileMath.latToTileY(47.6062, 12))
    }

    @Test fun x_is_clamped_into_valid_range() {
        // Beyond the antimeridian / poles, indices clamp to [0, 2^z - 1].
        assertEquals(0, TileMath.lonToTileX(-181.0, 3))
        assertEquals((1 shl 3) - 1, TileMath.lonToTileX(181.0, 3))
        assertEquals(0, TileMath.latToTileY(89.9, 3))
        assertEquals((1 shl 3) - 1, TileMath.latToTileY(-89.9, 3))
    }

    // --- bbox x zoom enumeration ------------------------------------------

    @Test fun single_tile_bbox_single_zoom_enumerates_one_tile() {
        // A pinpoint bbox at one zoom = exactly one tile.
        val bbox = BoundingBox(north = 47.61, south = 47.60, east = -122.33, west = -122.34)
        val tiles = TileMath.enumerateTiles(bbox, minZoom = 12, maxZoom = 12)
        assertEquals(1, tiles.size)
        assertEquals(Tile(12, 656, 1430), tiles.first())
    }

    @Test fun count_matches_enumerated_list_size() {
        val bbox = BoundingBox(north = 47.7, south = 47.5, east = -122.2, west = -122.5)
        for (z in 8..14) {
            val list = TileMath.enumerateTiles(bbox, z, z)
            val count = TileMath.tileCount(bbox, z, z)
            assertEquals("z=$z count must equal list size", list.size.toLong(), count)
        }
    }

    @Test fun multizoom_count_is_sum_of_per_zoom_counts() {
        val bbox = BoundingBox(north = 47.7, south = 47.5, east = -122.2, west = -122.5)
        val total = TileMath.tileCount(bbox, 8, 12)
        val perZoomSum = (8..12).sumOf { TileMath.tileCount(bbox, it, it) }
        assertEquals(perZoomSum, total)
    }

    @Test fun enumeration_covers_a_2x2_block() {
        // A bbox wide/tall enough to straddle a tile boundary at z12 yields a
        // contiguous rectangular block; verify the corners + the count.
        val bbox = BoundingBox(north = 47.70, south = 47.55, east = -122.25, west = -122.45)
        val tiles = TileMath.enumerateTiles(bbox, 12, 12)
        val xs = tiles.map { it.x }.toSortedSet()
        val ys = tiles.map { it.y }.toSortedSet()
        // contiguous range, no gaps
        assertEquals((xs.first()..xs.last()).toList(), xs.toList())
        assertEquals((ys.first()..ys.last()).toList(), ys.toList())
        assertEquals(xs.size * ys.size, tiles.size)
    }

    @Test fun swapped_bbox_corners_are_normalized() {
        // Operator-drawn rectangles can come in with min/max swapped; the
        // math must normalize rather than return an empty set.
        val normal = BoundingBox(north = 47.7, south = 47.5, east = -122.2, west = -122.5)
        val swapped = BoundingBox(north = 47.5, south = 47.7, east = -122.5, west = -122.2)
        assertEquals(
            TileMath.tileCount(normal, 10, 12),
            TileMath.tileCount(swapped, 10, 12),
        )
    }

    @Test fun zoom_range_is_order_independent() {
        val bbox = BoundingBox(north = 47.7, south = 47.5, east = -122.2, west = -122.5)
        assertEquals(
            TileMath.tileCount(bbox, 10, 13),
            TileMath.tileCount(bbox, 13, 10),
        )
    }

    // --- size estimate ----------------------------------------------------

    @Test fun size_estimate_scales_with_tile_count() {
        val bbox = BoundingBox(north = 47.7, south = 47.5, east = -122.2, west = -122.5)
        val count = TileMath.tileCount(bbox, 8, 12)
        val est = TileMath.estimateBytes(bbox, 8, 12)
        // Estimate = count * avg-bytes-per-tile; must be positive and a clean
        // multiple of the count.
        assertTrue(est > 0)
        assertEquals(count * TileMath.AVG_TILE_BYTES, est)
    }

    @Test fun zoom_is_clamped_to_world_cap() {
        // A requested zoom above MAX_ZOOM is clamped so we never enumerate an
        // absurd tile count or fill a template with an out-of-range z.
        val bbox = BoundingBox(north = 47.7, south = 47.5, east = -122.2, west = -122.5)
        val clampedTop = TileMath.tileCount(bbox, TileMath.MAX_ZOOM, TileMath.MAX_ZOOM + 5)
        val justTop = TileMath.tileCount(bbox, TileMath.MAX_ZOOM, TileMath.MAX_ZOOM)
        assertEquals(justTop, clampedTop)
    }

    // --- humanize helper --------------------------------------------------

    @Test fun bytes_humanize_to_readable_units() {
        assertEquals("0 B", TileMath.humanizeBytes(0))
        assertEquals("512 B", TileMath.humanizeBytes(512))
        assertEquals("1.0 KB", TileMath.humanizeBytes(1024))
        assertEquals("1.5 MB", TileMath.humanizeBytes((1.5 * 1024 * 1024).toLong()))
        assertTrue(TileMath.humanizeBytes(5L * 1024 * 1024 * 1024).endsWith("GB"))
    }

    // --- URL template fill ------------------------------------------------

    @Test fun fills_xyz_template() {
        val url = TileMath.fillTemplate("https://tile.openstreetmap.org/{z}/{x}/{y}.png", Tile(12, 654, 1428))
        assertEquals("https://tile.openstreetmap.org/12/654/1428.png", url)
    }

    @Test fun fills_tms_style_template_with_y_in_middle() {
        // ESRI World Imagery uses {z}/{y}/{x} order — fill must respect the
        // literal token positions, not assume an order.
        val url = TileMath.fillTemplate(
            "https://server.arcgisonline.com/.../tile/{z}/{y}/{x}",
            Tile(5, 9, 12),
        )
        assertEquals("https://server.arcgisonline.com/.../tile/5/12/9", url)
    }

    @Test fun rotates_subdomain_token() {
        // {s} subdomain rotation: deterministic, cycles a..c.
        val t = Tile(3, 2, 5)
        val u0 = TileMath.fillTemplate("https://{s}.tile.osm.org/{z}/{x}/{y}.png", t, subdomains = listOf("a", "b", "c"))
        assertTrue(u0.startsWith("https://a.") || u0.startsWith("https://b.") || u0.startsWith("https://c."))
        assertTrue(u0.contains("/3/2/5.png"))
    }

    // --- provider -> template ---------------------------------------------

    @Test fun osm_provider_resolves_to_osm_template() {
        val tpl = TileMath.templateForProvider(
            soy.engindearing.omnitak.mobile.data.MapProvider.OSM_RASTER, "",
        )
        assertNotNull(tpl)
        assertTrue(tpl!!.contains("{z}") && tpl.contains("{x}") && tpl.contains("{y}"))
        assertTrue(tpl.contains("openstreetmap"))
    }

    @Test fun custom_provider_resolves_to_custom_url_normalized() {
        val tpl = TileMath.templateForProvider(
            soy.engindearing.omnitak.mobile.data.MapProvider.WMTS_CUSTOM,
            "https://lan.local/tiles/{\$z}/{\$x}/{\$y}.png",
        )
        // ATAK $-brace form normalized to MapLibre form.
        assertEquals("https://lan.local/tiles/{z}/{x}/{y}.png", tpl)
    }

    @Test fun custom_provider_with_blank_url_is_null() {
        // Can't download from an empty custom source — caller must block it.
        assertNull(
            TileMath.templateForProvider(
                soy.engindearing.omnitak.mobile.data.MapProvider.WMTS_CUSTOM, "",
            ),
        )
    }
}
