package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "Center on me" on the 3D globe. On the 2D map it zooms to street level from far out; on the globe it
 * only turned the globe to the operator and stayed at whole-earth distance. The rule is the 2D map's
 * ([RetainedMapRules.zoomForOperator]), turned into a camera height by [CesiumCameraMath] and handed to
 * the page, which keeps the camera's heading and tilt and puts the operator at the middle of the screen.
 * The rule itself is in CesiumCameraMathTest; the page's arithmetic is run by `cesium_center_harness.js`.
 */
class CesiumCenterOnMeTest {

    private fun read(vararg paths: String): String = paths.map(::File).first { it.exists() }.readText()

    private val page: String by lazy {
        read("src/main/assets/cesium_scene.html", "app/src/main/assets/cesium_scene.html")
    }

    private val view: String by lazy {
        read(
            "src/main/kotlin/soy/engindearing/omnitak/mobile/ui/components/CesiumMapView.kt",
            "app/src/main/kotlin/soy/engindearing/omnitak/mobile/ui/components/CesiumMapView.kt",
        )
    }

    @Test fun the_page_takes_a_camera_height_for_center_on_me() {
        assertTrue(page.contains("function _centerRange("))
        assertTrue(page.contains("range=_centerRange(range,e.height,pitch)"))
    }

    @Test fun the_page_keeps_the_range_when_it_is_given_no_height() {
        assertTrue(page.contains(":range}"))
    }

    @Test fun the_height_is_along_the_view_not_straight_down() {
        assertTrue("height over the sine of the pitch below the horizon", page.contains("height/Math.max(Math.sin(-pitch),0.1)"))
    }

    @Test fun native_remembers_the_zoom_of_the_last_camera_event() {
        assertTrue(view.contains("cameraZoom.doubleValue = zoom"))
    }

    @Test fun native_asks_the_rule_for_a_height_when_center_on_me_is_pressed() {
        assertTrue(view.contains("CesiumCameraMath.heightForOperator(cameraZoom.doubleValue, lat)"))
        assertTrue(view.contains("{lat:\$lat,lon:\$lon,height:\$height}"))
        assertTrue(view.contains("window.OmniBridge.centerOnSelf(\$args);"))
    }
}
