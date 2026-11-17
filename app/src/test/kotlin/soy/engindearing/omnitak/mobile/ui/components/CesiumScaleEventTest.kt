package soy.engindearing.omnitak.mobile.ui.components

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The 3D globe's half of the scale bar: the page measures the ground scale on the bar's own row and
 * posts it with every camera event (`mpp`) and, when the surface under the bar changes without the
 * camera moving (tiles arriving), as an event of its own (`scalechanged`). Native tells the page where
 * the row is (`setScaleAnchor`). The iOS page has the same functions and the same fields.
 *
 * The page's behaviour (the median rule, the change threshold) is run against a stand-in for Cesium by
 * `cesium_scale_harness.js`, which takes the page's own functions out of the file by name; the tests
 * below pin the contract those functions and native rely on.
 */
class CesiumScaleEventTest {

    // MARK: the native side: reading the event

    @Test fun the_scale_is_read_from_a_camera_event() {
        assertEquals(2.389, metersPerDpFromCameraEvent(JSONObject("""{"event":"camerachanged","lat":0,"lon":0,"mpp":2.389}"""))!!, 1e-9)
    }

    @Test fun an_event_without_a_scale_has_none() {
        assertNull(metersPerDpFromCameraEvent(JSONObject("""{"event":"camerachanged","lat":0,"lon":0}""")))
    }

    @Test fun a_point_that_missed_the_globe_is_a_null_scale() {
        assertNull(metersPerDpFromCameraEvent(JSONObject("""{"event":"camerachanged","mpp":null}""")))
    }

    @Test fun a_scale_that_is_not_a_positive_number_is_none() {
        for (bad in listOf("0", "-3.2", "\"abc\"", "\"NaN\"", "1e999")) {
            assertNull(bad, metersPerDpFromCameraEvent(JSONObject("""{"mpp":$bad}""")))
        }
    }

    // MARK: the page side: the contract native relies on

    private val page: String by lazy {
        listOf("src/main/assets/cesium_scene.html", "app/src/main/assets/cesium_scene.html")
            .map(::File).first { it.exists() }.readText()
    }

    @Test fun the_page_lets_native_say_where_the_bar_is() {
        assertTrue(page.contains("setScaleAnchor(x,y)"))
        assertTrue("stored for the camera event", page.contains("_state.scaleAnchor={x:x,y:y}"))
    }

    @Test fun the_page_posts_the_scale_with_every_camera_event() {
        assertTrue(page.contains("const mpp=_scaleMpp(v)"))
        assertTrue(page.contains("zoom:_zoomFromHeight(carto.height,carto.latitude),mpp:mpp"))
    }

    @Test fun the_page_measures_five_points_along_the_row_from_50_px_left_to_50_px_right() {
        assertTrue(page.contains("_SCALE_OFFSETS=[-50,-25,0,25,50]"))
        assertTrue("on the bar's row, one pick per offset", page.contains("new Cesium.Cartesian2(x,y)"))
    }

    @Test fun each_neighbouring_pair_is_a_ground_distance_per_pixel_between_its_points() {
        assertTrue("the ground distance, not the straight line", page.contains("Cesium.EllipsoidGeodesic"))
        assertTrue(page.contains("surfaceDistance"))
        assertTrue("the pixels between the two points, not a fixed 100", page.contains("px:xs[i+1]-xs[i]"))
        assertTrue(page.contains("m.push(p.d/p.px)"))
    }

    @Test fun the_scale_is_the_median_of_the_four_pairs_so_one_roof_cannot_move_the_bar() {
        assertTrue(page.contains("function _median("))
        assertTrue(page.contains("return m.length<3?null:_median(m)"))
    }

    @Test fun a_point_is_clamped_to_the_canvas() {
        assertTrue(page.contains("Math.min(Math.max(x0+o,0),c.clientWidth)"))
    }

    @Test fun the_page_measures_the_middle_of_the_screen_only_until_it_is_told_the_row() {
        assertTrue(page.contains("x0=a?a.x:c.clientWidth/2,y=a?a.y:c.clientHeight/2"))
    }

    @Test fun a_pair_with_a_point_off_the_globe_is_not_used_and_too_few_pairs_are_no_scale() {
        assertTrue(page.contains("ps[i]&&ps[i+1]?"))
        assertTrue(page.contains("m.length<3?null"))
    }

    // MARK: the page measures again when the surface under the bar changes

    @Test fun the_page_measures_again_when_the_tiles_for_the_view_have_loaded() {
        assertTrue(page.contains("t.allTilesLoaded.addEventListener"))
    }

    @Test fun the_page_measures_again_once_a_second_while_it_is_visible() {
        assertTrue(page.contains("setInterval(function(){if(!document.hidden)_scaleRefresh(v)},1000)"))
    }

    @Test fun a_changed_scale_is_posted_as_an_event_of_its_own_not_as_a_camera_event() {
        assertTrue(page.contains("_postMapEvent({event:'scalechanged',mpp:m})"))
    }

    @Test fun the_scale_is_posted_again_only_when_it_moved_by_more_than_one_percent() {
        assertTrue(page.contains("Math.abs(next-prev)/prev>0.01"))
        assertTrue("a camera event counts as posted", page.contains("_state.scalePosted=mpp"))
    }

    // MARK: native: a scale-only event has no position

    @Test fun a_scale_event_is_read_without_a_camera_position() {
        val report = scaleChangeFromEvent(JSONObject("""{"event":"scalechanged","mpp":2.389}"""))!!
        assertEquals(2.389, report.metersPerDp!!, 1e-9)
    }

    @Test fun a_scale_event_with_no_scale_clears_it() {
        assertNull(scaleChangeFromEvent(JSONObject("""{"event":"scalechanged","mpp":null}"""))!!.metersPerDp)
        assertNull(scaleChangeFromEvent(JSONObject("""{"event":"scalechanged"}"""))!!.metersPerDp)
        assertNull(scaleChangeFromEvent(JSONObject("""{"event":"scalechanged","mpp":-1}"""))!!.metersPerDp)
    }

    @Test fun other_events_are_not_scale_events() {
        assertNull(scaleChangeFromEvent(JSONObject("""{"event":"camerachanged","lat":1,"lon":2,"mpp":2.389}""")))
        assertNull(scaleChangeFromEvent(JSONObject("""{"event":"tap","lat":1,"lon":2}""")))
        assertNull(scaleChangeFromEvent(JSONObject("""{"lat":1,"lon":2}""")))
    }
}
