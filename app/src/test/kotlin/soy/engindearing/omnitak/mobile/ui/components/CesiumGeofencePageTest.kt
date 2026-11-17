package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #155 - what the globe's page has to do for a geofence, pinned as text the way the far-side and
 * scale tests pin the rest of it: it reads the fill the bridge sends, writes the name the bridge
 * sends over the ground, and hides that name with the planet's horizon like every other marker.
 * The names of the fields are the ones the iOS page reads, so the two pages stay interchangeable.
 */
class CesiumGeofencePageTest {

    private val page: String by lazy {
        listOf("src/main/assets/cesium_scene.html", "app/src/main/assets/cesium_scene.html")
            .map(::File).first { it.exists() }.readText()
    }

    private val upsertDrawing: String by lazy {
        page.substring(page.indexOf("upsertDrawing(arg){"), page.indexOf("setDrawings(arg){"))
    }

    @Test fun the_fill_comes_from_the_bridge_when_it_sends_one_and_is_a_quarter_otherwise() {
        assertTrue(upsertDrawing.contains("typeof d.fillAlpha==='number'?d.fillAlpha:0.25"))
    }

    @Test fun a_shape_that_carries_a_name_writes_it_at_the_place_the_bridge_gives() {
        assertTrue(upsertDrawing.contains("typeof d.label==='string'&&d.label&&Array.isArray(d.labelAt)&&d.labelAt.length>=2"))
        assertTrue(upsertDrawing.contains("Cesium.Cartesian3.fromDegrees(d.labelAt[0],d.labelAt[1],0)"))
        assertTrue(upsertDrawing.contains("opts.label={text:d.label"))
        assertTrue("the name sits on the ground", upsertDrawing.contains("heightReference:Cesium.HeightReference.CLAMP_TO_GROUND,disableDepthTestDistance"))
    }

    @Test fun the_name_sits_under_the_middle_of_the_zone_clear_of_the_operators_marker_and_its_callsign() {
        assertTrue(upsertDrawing.contains("verticalOrigin:Cesium.VerticalOrigin.TOP,pixelOffset:new Cesium.Cartesian2(0,24)"))
    }

    @Test fun a_name_on_the_far_side_of_the_planet_is_hidden_with_the_markers_there() {
        val cull = page.substring(page.indexOf("function _cullFarSide("), page.indexOf("function _installInputHandlers("))
        assertTrue(cull.contains("_state.entities.forEach("))
        assertTrue(cull.contains("_state.drawings.forEach("))
    }

    @Test fun adding_a_shape_makes_the_horizon_check_run_again_even_if_the_camera_is_still() {
        assertTrue(upsertDrawing.contains("_state.horizonDirty=true"))
    }
}
