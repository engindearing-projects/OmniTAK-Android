package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import soy.engindearing.omnitak.mobile.ui.components.MapScaleSource.Engine

/**
 * What the scale bar knows: the ground scale on the bar's own row, written by whichever map engine
 * is showing, and the bar's row, written by the bar. The holder is what the retained map view's
 * camera listeners write to, so it must be right after a tab switch and when the engine changes.
 */
class MapScaleSourceTest {

    private val density = 2f

    /** A view where a dp is [metersPerDp] of the row's y metres of ground at screen row y (px): 1 m below y = 1000, 3 m above. */
    private fun view(metersPerDpAt: (Float) -> Double): (Float, Float) -> Pair<Double, Double>? = { x, y ->
        0.0 to Math.toDegrees(x / density * metersPerDpAt(y) / 6_371_008.8)
    }

    private val tilted = view { y -> if (y > 1000f) 1.0 else 3.0 }

    private fun ready(source: MapScaleSource = MapScaleSource(), project: (Float, Float) -> Pair<Double, Double>? = tilted) =
        source.apply {
            useEngine(Engine.MAPLIBRE)
            bindProjection(project)
        }

    @Test fun nothing_is_known_to_begin_with() {
        assertNull(MapScaleSource().metersPerDp.value)
        assertNull(MapScaleSource().anchorDp.value)
    }

    @Test fun the_map_is_measured_on_the_bars_row() {
        val source = ready()
        source.setAnchor(300f, 1500f, density)
        assertEquals(1.0, source.metersPerDp.value!!, 1e-6)
        source.setAnchor(300f, 500f, density)
        assertEquals(3.0, source.metersPerDp.value!!, 1e-6)
    }

    @Test fun a_camera_move_measures_again() {
        var metersPerDp = 2.0
        val source = ready(project = view { metersPerDp })
        source.setAnchor(300f, 1500f, density)
        assertEquals(2.0, source.metersPerDp.value!!, 1e-6)
        metersPerDp = 0.5       // the operator pinched in
        source.refresh()
        assertEquals(0.5, source.metersPerDp.value!!, 1e-6)
    }

    @Test fun without_the_bar_there_is_nothing_to_measure() {
        assertNull(ready().metersPerDp.value)
    }

    @Test fun without_the_map_there_is_nothing_to_measure() {
        val source = MapScaleSource().apply { useEngine(Engine.MAPLIBRE) }
        source.setAnchor(300f, 1500f, density)
        assertNull(source.metersPerDp.value)
    }

    @Test fun a_map_that_becomes_ready_after_the_bar_is_measured_at_once() {
        val source = MapScaleSource().apply { useEngine(Engine.MAPLIBRE) }
        source.setAnchor(300f, 1500f, density)
        source.bindProjection(tilted)
        assertEquals(1.0, source.metersPerDp.value!!, 1e-6)
    }

    @Test fun a_point_that_is_not_on_the_ground_means_no_scale() {
        val source = ready(project = { _, _ -> null })
        source.setAnchor(300f, 1500f, density)
        assertNull(source.metersPerDp.value)
    }

    @Test fun a_projection_that_fails_means_no_scale_not_a_crash() {
        val source = ready(project = { _, _ -> error("no map") })
        source.setAnchor(300f, 1500f, density)
        assertNull(source.metersPerDp.value)
    }

    // MARK: switching engines

    @Test fun switching_engine_drops_the_old_value_until_the_new_one_reports() {
        val source = ready()
        source.setAnchor(300f, 1500f, density)
        assertEquals(1.0, source.metersPerDp.value!!, 1e-6)

        source.useEngine(Engine.GLOBE)
        assertNull("the 2D value means nothing to the globe", source.metersPerDp.value)
        source.reportGlobe(7.5)
        assertEquals(7.5, source.metersPerDp.value!!, 0.0)

        source.useEngine(Engine.MAPLIBRE)
        assertEquals("the 2D map can be asked at once", 1.0, source.metersPerDp.value!!, 1e-6)
    }

    @Test fun the_globe_cannot_write_while_the_2d_map_shows() {
        // A camera event still on its way from a globe that has just been closed.
        val source = ready()
        source.setAnchor(300f, 1500f, density)
        source.reportGlobe(99.0)
        assertEquals(1.0, source.metersPerDp.value!!, 1e-6)
    }

    @Test fun the_2d_map_cannot_write_while_the_globe_shows() {
        val source = ready()
        source.setAnchor(300f, 1500f, density)
        source.useEngine(Engine.GLOBE)
        source.refresh()
        assertNull(source.metersPerDp.value)
    }

    @Test fun the_globe_can_report_that_it_has_no_scale() {
        val source = MapScaleSource().apply { useEngine(Engine.GLOBE) }
        source.reportGlobe(4.0)
        source.reportGlobe(null)     // looking at the sky, or off the globe
        assertNull(source.metersPerDp.value)
    }

    // MARK: the bar's row

    @Test fun the_anchor_is_told_in_dp() {
        val source = MapScaleSource()
        source.setAnchor(300f, 1500f, 3f)
        assertEquals(100f, source.anchorDp.value!!.x, 0f)
        assertEquals(500f, source.anchorDp.value!!.y, 0f)
    }

    @Test fun a_row_that_is_not_a_place_is_ignored() {
        val source = MapScaleSource()
        source.setAnchor(Float.NaN, 100f, 2f)
        source.setAnchor(100f, Float.POSITIVE_INFINITY, 2f)
        source.setAnchor(100f, 100f, 0f)
        assertNull(source.anchorDp.value)
    }
}
