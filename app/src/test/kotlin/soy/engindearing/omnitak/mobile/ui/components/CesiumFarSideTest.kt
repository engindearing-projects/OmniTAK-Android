package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Markers on the 3D globe skip the depth test at any distance (`disableDepthTestDistance` infinite), so
 * one never sinks into terrain or a building. The price was that a marker on the far side of the Earth
 * was drawn through the planet: at whole-earth zoom with the camera over Africa, the operator's marker
 * at 117 W showed over the Atlantic.
 *
 * The page now hides what the planet itself hides. Before each frame a marker is shown only while it is
 * above the horizon as the camera sees it (Cesium.EllipsoidalOccluder). The depth test stays off, so
 * nothing near changes. A depth test limited by distance cannot do this: how far away "behind the
 * planet" starts depends on how high the camera is (about 100 km from the ground at street level,
 * about 25,000 km from the whole-earth view), and a distance short enough for the far side lets the
 * photoreal tiles hide near-side markers at long range.
 *
 * The page's behaviour is run by `cesium_horizon_harness.js` against a stand-in camera and a
 * transliteration of Cesium's horizon test; these pin the contract.
 */
class CesiumFarSideTest {

    private val page: String by lazy {
        listOf("src/main/assets/cesium_scene.html", "app/src/main/assets/cesium_scene.html")
            .map(::File).first { it.exists() }.readText()
    }

    @Test fun the_page_hides_a_marker_that_is_behind_the_horizon() {
        assertTrue(page.contains("function _cullFarSide("))
        assertTrue(page.contains("new Cesium.EllipsoidalOccluder(Cesium.Ellipsoid.WGS84,cam)"))
        assertTrue(page.contains("o.isPointVisible(p)"))
    }

    @Test fun it_decides_for_every_marker_in_the_scene_with_the_camera_where_it_is() {
        assertTrue(page.contains("o.cameraPosition=cam"))
        assertTrue(page.contains("_state.entities.forEach("))
    }

    @Test fun it_runs_before_each_frame_is_drawn() {
        assertTrue(page.contains("v.scene.preRender.addEventListener(function(){_cullFarSide(v)})"))
    }

    @Test fun it_does_nothing_while_the_camera_and_the_markers_are_still() {
        assertTrue(page.contains("Cesium.Cartesian3.equals(_state.horizonCam,cam)&&!_state.horizonDirty"))
    }

    @Test fun a_marker_that_is_added_or_moved_is_checked_even_if_the_camera_is_still() {
        val upsert = page.substring(page.indexOf("upsertEntity(arg){"), page.indexOf("setEntities(arg){"))
        assertTrue(upsert.contains("_state.horizonDirty=true"))
    }

    @Test fun the_visibility_is_set_only_when_it_changes() {
        assertTrue(page.contains("if(e.show!==vis)e.show=vis"))
    }

    @Test fun markers_still_skip_the_depth_test_so_nothing_near_sinks_into_terrain_or_buildings() {
        // The billboard and the label of an entity, and the name a shape carries (a geofence's, #155), which
        // is hidden with the horizon like the others (CesiumGeofencePageTest).
        assertEquals(3, Regex(Regex.escape("disableDepthTestDistance:Number.POSITIVE_INFINITY")).findAll(page).count())
    }
}
