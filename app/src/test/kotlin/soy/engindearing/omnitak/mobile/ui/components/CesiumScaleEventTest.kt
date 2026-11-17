package soy.engindearing.omnitak.mobile.ui.components

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The 3D globe's half of the scale bar: the page measures the ground scale on the bar's own row and
 * posts it with every camera event (`mpp`), native tells the page where the row is
 * (`setScaleAnchor`). The iOS page has the same function and the same field.
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
        assertTrue(page.contains("mpp:_scaleMpp(v)"))
    }

    @Test fun the_page_measures_between_two_globe_points_50_px_either_side_of_the_row() {
        assertTrue(page.contains("new Cesium.Cartesian2(x-50,y)"))
        assertTrue(page.contains("new Cesium.Cartesian2(x+50,y)"))
        assertTrue("the ground distance, not the straight line", page.contains("Cesium.EllipsoidGeodesic"))
        assertTrue("the distance between the two points, per px", page.contains("surfaceDistance"))
        assertTrue(page.contains("d/100"))
    }

    @Test fun the_page_measures_the_middle_of_the_screen_only_until_it_is_told_the_row() {
        assertTrue(page.contains("x=a?a.x:c.clientWidth/2,y=a?a.y:c.clientHeight/2"))
    }

    @Test fun a_point_off_the_globe_is_no_scale_on_the_page_too() {
        assertTrue(page.contains("if(!l||!r)return null"))
    }
}
