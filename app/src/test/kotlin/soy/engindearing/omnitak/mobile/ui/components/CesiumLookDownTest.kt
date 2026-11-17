package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #155 - what the globe's page has to do for "Show on map" on a geofence, pinned as text the way the other
 * page tests pin the rest of it. The page is told where to look and how many metres to see across the screen
 * (`GeofenceFraming.cameraFor(...).groundWidthMeters`); it works the camera height out from its own frustum
 * and flies there looking straight down, north up. Before, the globe was flown to the middle of the zone
 * 3 km up and looking 30 degrees below the horizon, which put the zone under the camera and off the bottom
 * of the screen. The names are the ones the iOS page uses, so the two pages stay interchangeable.
 */
class CesiumLookDownTest {

    private val page: String by lazy {
        listOf("src/main/assets/cesium_scene.html", "app/src/main/assets/cesium_scene.html")
            .map(::File).first { it.exists() }.readText()
    }

    private val lookDown: String by lazy {
        page.substring(page.indexOf("lookDown(arg){"), page.indexOf("zoomIn(){"))
    }

    @Test fun the_page_has_a_call_that_looks_straight_down_on_a_place() {
        assertTrue(page.contains("lookDown(arg){"))
        assertTrue(lookDown.contains("typeof e.lat!=='number'||typeof e.lon!=='number'"))
        assertTrue("north up and straight down", lookDown.contains("orientation:{heading:0,pitch:-Math.PI/2,roll:0}"))
    }

    @Test fun the_height_comes_from_the_width_to_see_and_the_frustums_own_field_of_view() {
        assertTrue(lookDown.contains("_lookDownAgl(typeof e.width==='number'?e.width:600,f.aspectRatio,f.fovy)"))
        // half the width the frustum sees is aspect * tan(fovy / 2), at any height, so the height is the width over twice that
        assertTrue(page.contains("function _lookDownAgl(width,aspect,fovy){const half=aspect*Math.tan(fovy/2);return(width>0&&half>0)?width/2/half:null}"))
    }

    @Test fun the_height_is_measured_from_the_ground_there_and_a_nonsense_ground_is_taken_as_sea_level() {
        assertTrue(lookDown.contains("_lookDownGround(ground)+agl"))
        assertTrue(page.contains("function _lookDownGround(h){return(typeof h==='number'&&isFinite(h)&&h>-1000&&h<10000)?h:0}"))
    }

    @Test fun the_ground_is_asked_of_the_terrain_service_which_gets_two_seconds() {
        assertTrue(lookDown.contains("Cesium.sampleTerrainMostDetailed(v.terrainProvider,"))
        assertTrue(lookDown.contains("setTimeout(function(){go(null)},2000)"))
        assertTrue("an answer after the time is up flies nowhere twice", lookDown.contains("if(flown)return;flown=true;"))
    }
}
