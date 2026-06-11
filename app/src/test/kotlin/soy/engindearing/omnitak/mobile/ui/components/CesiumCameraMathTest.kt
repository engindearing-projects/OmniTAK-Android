package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the zoom ↔ Cesium-camera-height conversion used by the
 * 2D ↔ 3D engine-switch viewport handoff (issue #78).
 *
 * [CesiumCameraMath.zoomForHeight] must stay an exact Kotlin mirror of
 * `_zoomFromHeight` in `cesium_scene.html` (the 3D → 2D direction), and
 * [CesiumCameraMath.heightForZoom] must be its algebraic inverse so
 * repeated engine toggles don't zoom-creep.
 */
class CesiumCameraMathTest {

    /** height = C·cos(lat)/2^zoom with C = 40_075_017·1000/256. */
    @Test fun heightForZoom_zoom15_equator_is_about_4_8km() {
        val h = CesiumCameraMath.heightForZoom(15.0, 0.0)
        // 40_075_017_000 / 256 / 2^15 = 4777.19…
        assertEquals(4777.19, h, 0.5)
    }

    @Test fun heightForZoom_scales_with_cos_latitude() {
        val equator = CesiumCameraMath.heightForZoom(15.0, 0.0)
        val at60 = CesiumCameraMath.heightForZoom(15.0, 60.0)
        // cos(60°) = 0.5 — same zoom frames half the ground width, so
        // the camera sits at half the altitude.
        assertEquals(equator * 0.5, at60, 0.5)
    }

    /** The seeded camera must report back the zoom it was seeded from. */
    @Test fun roundTrip_zoom_to_height_to_zoom_is_stable() {
        for (zoom in listOf(3.0, 5.5, 10.0, 12.25, 15.0, 18.0, 22.0)) {
            for (lat in listOf(0.0, 47.65, -33.86, 60.0, -60.0)) {
                val h = CesiumCameraMath.heightForZoom(zoom, lat)
                val back = CesiumCameraMath.zoomForHeight(h, lat)
                assertEquals("zoom=$zoom lat=$lat", zoom, back, 1e-6)
            }
        }
    }

    /** Mirror of the JS `_zoomFromHeight`: the 20,000 km bootstrap world
     *  view maps to a whole-world web-mercator zoom (~3). */
    @Test fun zoomForHeight_worldViewHeight_is_whole_world_zoom() {
        val z = CesiumCameraMath.zoomForHeight(CesiumCameraMath.WORLD_VIEW_HEIGHT_M, 0.0)
        // log2(40075017 / (20_000_000·256/1000)) = 2.9685…
        assertEquals(2.9685, z, 0.001)
    }

    /** A whole-world 2D zoom must not seed the camera farther out than
     *  the legacy (0,0) intro flight. */
    @Test fun heightForZoom_clamps_to_world_view_ceiling() {
        assertEquals(
            CesiumCameraMath.WORLD_VIEW_HEIGHT_M,
            CesiumCameraMath.heightForZoom(0.0, 0.0),
            0.0,
        )
    }

    @Test fun heightForZoom_clamps_out_of_range_zoom() {
        assertEquals(
            CesiumCameraMath.heightForZoom(22.0, 0.0),
            CesiumCameraMath.heightForZoom(30.0, 0.0),
            1e-9,
        )
    }

    /** cos(lat) floor (0.01) keeps polar cameras finite and mirrored. */
    @Test fun polar_latitude_uses_cos_floor_and_still_round_trips() {
        val h = CesiumCameraMath.heightForZoom(10.0, 89.9)
        assertTrue(h.isFinite() && h > 0)
        assertEquals(10.0, CesiumCameraMath.zoomForHeight(h, 89.9), 1e-6)
    }

    @Test fun zoomForHeight_clamps_into_0_22() {
        assertEquals(22.0, CesiumCameraMath.zoomForHeight(0.5, 0.0), 0.0)
        assertEquals(0.0, CesiumCameraMath.zoomForHeight(1.0e12, 0.0), 0.0)
    }

    // --- CesiumCameraSeed validity (values are interpolated into JS) ---

    @Test fun seed_with_finite_fields_is_valid() {
        assertTrue(CesiumCameraSeed(47.65, -117.42, 12.0, 35.0).isValid)
    }

    @Test fun seed_with_nan_or_infinite_field_is_invalid() {
        assertFalse(CesiumCameraSeed(Double.NaN, -117.42, 12.0, 0.0).isValid)
        assertFalse(CesiumCameraSeed(47.65, Double.NEGATIVE_INFINITY, 12.0, 0.0).isValid)
        assertFalse(CesiumCameraSeed(47.65, -117.42, Double.NaN, 0.0).isValid)
        assertFalse(CesiumCameraSeed(47.65, -117.42, 12.0, Double.POSITIVE_INFINITY).isValid)
    }
}
