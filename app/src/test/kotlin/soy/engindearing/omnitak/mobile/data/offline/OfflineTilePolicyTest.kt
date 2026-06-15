package soy.engindearing.omnitak.mobile.data.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Serve from cache when offline" decision logic (#120).
 *
 * Given the set of downloaded regions and whether the network is up, decide
 * whether a downloaded region's cached raster layer should be shown and
 * whether it should sit *above* the live basemap (so it wins) or below.
 *
 * Pure policy — no Android — so it unit-tests directly. The on-map MapLibre
 * wiring that consumes these decisions is device-verified.
 */
class OfflineTilePolicyTest {

    private fun region(
        id: String = "r1",
        n: Double = 47.7, s: Double = 47.5, e: Double = -122.2, w: Double = -122.5,
        minZ: Int = 8, maxZ: Int = 15,
    ) = OfflineRegion(
        id = id, name = id, fileName = "$id.mbtiles",
        north = n, south = s, east = e, west = w,
        minZoom = minZ, maxZoom = maxZ, tileCount = 0, sizeBytes = 0, createdAt = 0,
    )

    @Test fun no_regions_means_nothing_to_serve() {
        val d = OfflineTilePolicy.decide(regions = emptyList(), networkAvailable = true)
        assertTrue(d.activeRegionIds.isEmpty())
        assertFalse(d.cachePreferred)
    }

    @Test fun offline_prefers_cache_over_live_basemap() {
        // Network down + a downloaded region -> cache must win (drawn above
        // the live source, which can't load anyway).
        val d = OfflineTilePolicy.decide(regions = listOf(region()), networkAvailable = false)
        assertTrue(d.cachePreferred)
        assertEquals(listOf("r1"), d.activeRegionIds)
    }

    @Test fun online_still_serves_cache_but_does_not_force_it_on_top() {
        // Online: cached regions stay available (free + fast) but don't
        // override the live basemap globally — the live source renders areas
        // outside any downloaded region.
        val d = OfflineTilePolicy.decide(regions = listOf(region()), networkAvailable = true)
        assertFalse(d.cachePreferred)
        assertEquals(listOf("r1"), d.activeRegionIds)
    }

    @Test fun all_downloaded_regions_are_active() {
        val regions = listOf(region("a"), region("b"), region("c"))
        val d = OfflineTilePolicy.decide(regions = regions, networkAvailable = false)
        assertEquals(listOf("a", "b", "c"), d.activeRegionIds)
        assertTrue(d.cachePreferred)
    }

    @Test fun region_contains_point_inside_its_bbox() {
        val r = region(n = 47.7, s = 47.5, e = -122.2, w = -122.5)
        assertTrue(r.contains(47.6, -122.35))   // inside
        assertFalse(r.contains(48.0, -122.35))  // north of bbox
        assertFalse(r.contains(47.6, -121.0))   // east of bbox
    }

    @Test fun region_covers_zoom_within_its_range() {
        val r = region(minZ = 8, maxZ = 15)
        assertTrue(r.coversZoom(8))
        assertTrue(r.coversZoom(15))
        assertFalse(r.coversZoom(7))
        assertFalse(r.coversZoom(16))
    }
}
