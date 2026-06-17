package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the #128 declutter contract for the Android native-marker path.
 * A 1286-point Taiwan webcam KML used to drop every placemark on the map at
 * once — an unreadable pin-mass that also janked the main thread. The renderer
 * now viewport-culls and bins survivors into a screen-pixel grid: points within
 * one [KmlMarkerRenderer.cellKey] cell collapse to a single count badge, and
 * the badge bitmap is shared across same-magnitude cells via
 * [KmlMarkerRenderer.bucket]. These are the pure pieces of that logic.
 */
class KmlMarkerClusteringTest {

    private val cell = 120

    @Test fun points_within_one_cell_share_a_key_and_cluster() {
        // Two points 10px apart land in the same 120px bin -> one cluster badge.
        val a = KmlMarkerRenderer.cellKey(300f, 300f, cell)
        val b = KmlMarkerRenderer.cellKey(310f, 295f, cell)
        assertEquals("points <cell apart must collapse to one cluster", a, b)
    }

    @Test fun points_in_adjacent_cells_stay_separate() {
        // A point in cell column 2 (240..359) vs column 3 (360..479) -> distinct.
        val left = KmlMarkerRenderer.cellKey(300f, 300f, cell)
        val right = KmlMarkerRenderer.cellKey(370f, 300f, cell)
        assertNotEquals("points in different cells must not merge", left, right)
    }

    @Test fun cell_key_is_distinct_per_axis() {
        // Same column, different rows must differ (guards the x<<32 | y packing).
        val top = KmlMarkerRenderer.cellKey(50f, 50f, cell)
        val bottom = KmlMarkerRenderer.cellKey(50f, 400f, cell)
        assertNotEquals(top, bottom)
    }

    @Test fun off_screen_negative_coords_do_not_alias_to_origin() {
        // floor (not int-truncation) keeps -1px in a different cell than +1px,
        // so points just off the top-left edge can't pile onto the (0,0) cell.
        val justOff = KmlMarkerRenderer.cellKey(-5f, -5f, cell)
        val origin = KmlMarkerRenderer.cellKey(5f, 5f, cell)
        assertNotEquals("negative coords must not alias to the origin cell", justOff, origin)
    }

    @Test fun bucket_collapses_counts_to_shared_badge_tiers() {
        // <10 is exact (every small count gets its own readable badge)...
        for (n in 1..9) assertEquals(n, KmlMarkerRenderer.bucket(n))
        // ...then tens, hundreds, and a single 1000+ cap, so a handful of
        // bitmaps cover any cluster size.
        assertEquals(40, KmlMarkerRenderer.bucket(47))
        assertEquals(20, KmlMarkerRenderer.bucket(29))
        assertEquals(500, KmlMarkerRenderer.bucket(523))
        assertEquals(1000, KmlMarkerRenderer.bucket(1286)) // the Taiwan file, fully zoomed out
    }

    @Test fun bucket_count_is_bounded() {
        // The whole point of bucketing: distinct badge bitmaps stay small no
        // matter how many points. Across 1..5000 there must be few buckets.
        val buckets = (1..5000).map { KmlMarkerRenderer.bucket(it) }.toSet()
        assertTrue("bucket tiers must stay small, got ${buckets.size}", buckets.size <= 30)
    }
}
