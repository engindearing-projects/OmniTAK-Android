package soy.engindearing.omnitak.mobile.data.geofence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.rangebearing.RangeBearing
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/**
 * #155 - "Show on map" frames the whole zone. It used to put the camera on the middle of the zone at a
 * fixed zoom 14 (about 3 km across a phone held upright), so a zone with a radius over about 1 km did not
 * fit and its edge could not be seen at all. The camera now comes from the zone's bounds, the size of the
 * view and some padding, the way iOS frames a geofence.
 *
 * Each test puts the ground points of a zone on the screen the camera would give (web mercator, a world
 * 512 dp wide at zoom 0, the way the 2D map measures it) and checks where they land.
 */
class GeofenceFramingTest {

    private val spokane = LatLon(47.6588, -117.4260)

    private fun circle(radiusMeters: Double, at: LatLon = spokane) = Geofence(
        id = "c", name = "Zone", type = GeofenceType.CIRCLE,
        centerLat = at.lat, centerLon = at.lon, radiusMeters = radiusMeters,
    )

    private fun polygon(vararg corners: LatLon) =
        Geofence(id = "p", name = "Yard", type = GeofenceType.POLYGON, points = corners.toList())

    private fun mercatorY(latDeg: Double) = ln(tan(PI / 4 + Math.toRadians(latDeg) / 2))

    /** dp from the middle of the screen (x to the right, y down) where a ground point is drawn for [camera]. */
    private fun onScreen(lat: Double, lon: Double, camera: FramedCamera): Pair<Double, Double> {
        val world = 512.0 * 2.0.pow(camera.zoom)
        return (lon - camera.lon) / 360.0 * world to (mercatorY(camera.lat) - mercatorY(lat)) / (2 * PI) * world
    }

    /** Points all round the edge of [zone], one every [stepsPerCircle]th of a turn. */
    private fun edge(zone: Geofence, stepsPerCircle: Int = 360): List<Pair<Double, Double>> = when (zone.type) {
        GeofenceType.CIRCLE -> RangeBearing.ringPoints(zone.centerLat!!, zone.centerLon!!, zone.radiusMeters!!, stepsPerCircle)
            .map { it[0] to it[1] }
        GeofenceType.POLYGON -> zone.points!!.map { it.lat to it.lon }
    }

    private fun frame(zone: Geofence, widthDp: Double = 411.0, heightDp: Double = 914.0, insets: FrameInsets = FrameInsets.uniform(64.0)): FramedCamera =
        GeofenceFraming.cameraFor(checkNotNull(GeofenceFraming.boundsOf(zone)), widthDp, heightDp, insets)

    /** Where a ground point lands in the view, in dp from its top left corner. */
    private fun inView(lat: Double, lon: Double, camera: FramedCamera, widthDp: Double, heightDp: Double): Pair<Double, Double> {
        val (x, y) = onScreen(lat, lon, camera)
        return widthDp / 2 + x to heightDp / 2 + y
    }

    // -------------------------------------------------------------------------------- bounds

    @Test fun a_circles_bounds_reach_one_radius_north_south_east_and_west() {
        for (radius in listOf(50.0, 1_000.0, 5_000.0, 50_000.0)) {
            val zone = circle(radius)
            val bounds = checkNotNull(GeofenceFraming.boundsOf(zone))
            val ring = edge(zone, stepsPerCircle = 3_600)
            assertEquals("north at $radius m", ring.maxOf { it.first }, bounds.north, 1e-5)
            assertEquals("south at $radius m", ring.minOf { it.first }, bounds.south, 1e-5)
            assertEquals("east at $radius m", ring.maxOf { it.second }, bounds.east, 1e-5)
            assertEquals("west at $radius m", ring.minOf { it.second }, bounds.west, 1e-5)
        }
    }

    @Test fun a_circle_is_wider_in_degrees_of_longitude_the_further_from_the_equator() {
        val atEquator = checkNotNull(GeofenceFraming.boundsOf(circle(5_000.0, LatLon(0.0, 10.0))))
        val atSpokane = checkNotNull(GeofenceFraming.boundsOf(circle(5_000.0)))
        val equatorWidth = atEquator.east - atEquator.west
        val spokaneWidth = atSpokane.east - atSpokane.west
        assertEquals("1 / cos(47.6588 N)", 1.0 / cos(Math.toRadians(47.6588)), spokaneWidth / equatorWidth, 0.01)
    }

    @Test fun a_polygons_bounds_are_the_extremes_of_its_corners() {
        val bounds = checkNotNull(
            GeofenceFraming.boundsOf(polygon(LatLon(47.66, -117.43), LatLon(47.67, -117.41), LatLon(47.65, -117.40))),
        )
        assertEquals(47.65, bounds.south, 0.0)
        assertEquals(47.67, bounds.north, 0.0)
        assertEquals(-117.43, bounds.west, 0.0)
        assertEquals(-117.40, bounds.east, 0.0)
    }

    @Test fun a_geofence_with_no_usable_shape_has_no_bounds() {
        assertNull(GeofenceFraming.boundsOf(circle(0.0)))
        assertNull(GeofenceFraming.boundsOf(circle(Double.NaN)))
        assertNull(GeofenceFraming.boundsOf(polygon(LatLon(1.0, 1.0), LatLon(2.0, 2.0))))
        assertNull(GeofenceFraming.boundsOf(Geofence(id = "x", name = "x", type = GeofenceType.CIRCLE)))
    }

    @Test fun a_circle_that_holds_a_pole_spans_every_longitude() {
        val bounds = checkNotNull(GeofenceFraming.boundsOf(circle(50_000.0, LatLon(89.9, 10.0))))
        assertEquals(90.0, bounds.north, 0.0)
        assertEquals(-180.0, bounds.west, 0.0)
        assertEquals(180.0, bounds.east, 0.0)
    }

    @Test fun a_circle_that_holds_the_south_pole_spans_every_longitude() {
        val bounds = checkNotNull(GeofenceFraming.boundsOf(circle(50_000.0, LatLon(-89.9, 10.0))))
        assertEquals(-90.0, bounds.south, 0.0)
        assertEquals(-180.0, bounds.west, 0.0)
        assertEquals(180.0, bounds.east, 0.0)
        assertTrue("its north edge is a radius from the pole: ${bounds.north}", bounds.north > -89.6 && bounds.north < -89.4)
    }

    @Test fun a_circle_across_the_antimeridian_is_framed_around_the_antimeridian_not_the_far_side_of_the_world() {
        val zone = circle(5_000.0, LatLon(0.0, 179.99))
        val bounds = checkNotNull(GeofenceFraming.boundsOf(zone))
        assertTrue("its east edge is past 180", bounds.east > 180.0)
        val camera = frame(zone)
        assertEquals(179.99, camera.lon, 1e-4)
        assertTrue("the camera longitude is on the map", camera.lon in -180.0..180.0)
        assertTrue("and it is the near zoom, not the whole world", camera.zoom > 8.0)
    }

    @Test fun a_polygon_across_the_antimeridian_is_framed_where_it_is_not_across_the_whole_world() {
        // A box 0.2 degrees wide with its corners at 179.9 and -179.9: raw min and max longitude made it 359.8 wide.
        val zone = polygon(LatLon(0.0, 179.9), LatLon(0.0, -179.9), LatLon(0.1, -179.9), LatLon(0.1, 179.9))
        val bounds = checkNotNull(GeofenceFraming.boundsOf(zone))
        assertEquals("0.2 degrees across", 0.2, bounds.east - bounds.west, 1e-9)
        val camera = frame(zone)
        assertTrue("on the antimeridian, not on the far side of the world", abs(camera.lon) > 179.9)
        assertTrue("and close in, not the whole world (zoom ${camera.zoom})", camera.zoom > 8.0)
    }

    // -------------------------------------------------------------------------------- the room a window leaves

    @Test fun the_room_is_the_safe_area_and_the_padding_with_the_strip_and_the_name_on_top() {
        val insets = GeofenceFraming.insetsFor(safeLeftDp = 8.0, safeTopDp = 24.0, safeRightDp = 8.0, safeBottomDp = 16.0, topChromeDp = 48.0)
        assertEquals(8.0 + GeofenceFraming.PADDING_DP, insets.leftDp, 0.0)
        assertEquals(8.0 + GeofenceFraming.PADDING_DP, insets.rightDp, 0.0)
        assertEquals(16.0 + GeofenceFraming.PADDING_DP, insets.bottomDp, 0.0)
        assertEquals("under the status bar, the strip and the name above the north edge", 24.0 + 48.0 + GeofenceFraming.LABEL_ROOM_DP, insets.topDp, 0.0)
    }

    @Test fun held_sideways_the_zone_and_its_name_are_clear_of_the_status_strip() {
        // 914 x 411 dp: the height limits a circle, so its north edge is as close to the top as the room lets it. It used to be
        // 64 dp down, under the strip (the status bar 24 dp and the 48 dp strip), and its name, written above that edge, under the strip too.
        val room = GeofenceFraming.insetsFor(0.0, 24.0, 0.0, 0.0, topChromeDp = 48.0)
        val zone = circle(5_000.0)
        val camera = frame(zone, widthDp = 914.0, heightDp = 411.0, insets = room)
        val northEdge = edge(zone).minOf { inView(it.first, it.second, camera, 914.0, 411.0).second }
        assertTrue("the north edge is at $northEdge dp, the strip ends at ${24.0 + 48.0}", northEdge >= 24.0 + 48.0 + GeofenceFraming.LABEL_ROOM_DP - 0.5)
    }

    @Test fun held_upright_a_tall_zone_is_clear_of_the_status_strip_too() {
        val room = GeofenceFraming.insetsFor(0.0, 43.0, 0.0, 24.0, topChromeDp = 48.0)
        val zone = polygon(LatLon(47.640, -117.4266), LatLon(47.640, -117.4254), LatLon(47.676, -117.4254), LatLon(47.676, -117.4266))
        val camera = frame(zone, insets = room)
        val top = zone.points!!.minOf { inView(it.lat, it.lon, camera, 411.0, 914.0).second }
        val bottom = zone.points!!.maxOf { inView(it.lat, it.lon, camera, 411.0, 914.0).second }
        assertTrue("top at $top dp", top >= 43.0 + 48.0 + GeofenceFraming.LABEL_ROOM_DP - 0.5)
        assertTrue("bottom at $bottom dp", bottom <= 914.0 - (24.0 + GeofenceFraming.PADDING_DP) + 0.5)
    }

    @Test fun the_zone_is_centred_in_the_room_that_is_left_not_in_the_whole_view() {
        val insets = FrameInsets(leftDp = 10.0, topDp = 120.0, rightDp = 50.0, bottomDp = 30.0)
        val zone = circle(1_000.0)
        val camera = frame(zone, insets = insets)
        val (x, y) = inView(spokane.lat, spokane.lon, camera, 411.0, 914.0)
        assertEquals("middle of the room across: ${10.0 + (411.0 - 10.0 - 50.0) / 2}", 10.0 + (411.0 - 10.0 - 50.0) / 2, x, 0.5)
        assertEquals("middle of the room down: ${120.0 + (914.0 - 120.0 - 30.0) / 2}", 120.0 + (914.0 - 120.0 - 30.0) / 2, y, 0.5)
    }

    @Test fun every_edge_of_the_zone_stays_inside_its_own_inset() {
        val insets = FrameInsets(leftDp = 10.0, topDp = 120.0, rightDp = 50.0, bottomDp = 30.0)
        for (radius in listOf(200.0, 5_000.0)) {
            val zone = circle(radius)
            val camera = frame(zone, insets = insets)
            for ((lat, lon) in edge(zone)) {
                val (x, y) = inView(lat, lon, camera, 411.0, 914.0)
                assertTrue("r=$radius x=$x", x >= 10.0 - 0.5 && x <= 411.0 - 50.0 + 0.5)
                assertTrue("r=$radius y=$y", y >= 120.0 - 0.5 && y <= 914.0 - 30.0 + 0.5)
            }
        }
    }

    @Test fun room_that_takes_more_than_half_the_view_is_scaled_down_and_still_gives_a_camera() {
        val camera = frame(circle(1_000.0), widthDp = 100.0, heightDp = 100.0, insets = FrameInsets(90.0, 90.0, 90.0, 90.0))
        assertTrue(camera.zoom.isFinite() && camera.zoom >= 0.0)
        assertTrue(camera.groundWidthMeters.isFinite())
    }

    // -------------------------------------------------------------------------------- the camera

    @Test fun the_whole_circle_is_inside_the_view_with_its_padding_on_a_phone_held_upright() {
        for (radius in listOf(200.0, 1_000.0, 5_000.0, 30_000.0)) {
            val zone = circle(radius)
            val camera = frame(zone)
            for ((lat, lon) in edge(zone)) {
                val (x, y) = onScreen(lat, lon, camera)
                assertTrue("r=$radius m: x=$x is outside 411 dp less 64 dp of padding", abs(x) <= 411.0 / 2 - 64.0 + 0.5)
                assertTrue("r=$radius m: y=$y is outside 914 dp less 64 dp of padding", abs(y) <= 914.0 / 2 - 64.0 + 0.5)
            }
        }
    }

    @Test fun a_wide_zone_fills_the_view_across_the_width_and_stops_at_its_padding() {
        val zone = circle(5_000.0)
        val camera = frame(zone)
        val widest = edge(zone).maxOf { abs(onScreen(it.first, it.second, camera).first) }
        assertEquals("the zone reaches the padding, not further and not short of it", 411.0 / 2 - 64.0, widest, 0.5)
    }

    @Test fun held_sideways_the_height_is_what_limits_the_zoom() {
        val zone = circle(5_000.0)
        val camera = frame(zone, widthDp = 914.0, heightDp = 411.0)
        val tallest = edge(zone).maxOf { abs(onScreen(it.first, it.second, camera).second) }
        assertEquals(411.0 / 2 - 64.0, tallest, 0.5)
        val widest = edge(zone).maxOf { abs(onScreen(it.first, it.second, camera).first) }
        assertTrue("and it is narrower than the view is wide", widest < 914.0 / 2 - 64.0)
    }

    @Test fun a_tall_narrow_polygon_is_limited_by_the_height_of_an_upright_phone() {
        // 100 m wide, 4 km tall, about the middle of Spokane.
        val zone = polygon(
            LatLon(47.640, -117.4266), LatLon(47.640, -117.4254), LatLon(47.676, -117.4254), LatLon(47.676, -117.4266),
        )
        val camera = frame(zone)
        val tallest = zone.points!!.maxOf { abs(onScreen(it.lat, it.lon, camera).second) }
        assertEquals(914.0 / 2 - 64.0, tallest, 0.5)
        val widest = zone.points!!.maxOf { abs(onScreen(it.lat, it.lon, camera).first) }
        assertTrue("so the width has room to spare", widest < 411.0 / 2 - 64.0)
    }

    @Test fun a_polygon_off_the_middle_of_its_corners_is_still_centred_by_its_bounds() {
        // An L: most corners at the lower left, one far to the upper right. Centring on the mean of the corners
        // would push the far corner off the screen.
        val zone = polygon(
            LatLon(47.650, -117.430), LatLon(47.650, -117.4295), LatLon(47.651, -117.4295),
            LatLon(47.651, -117.4298), LatLon(47.660, -117.410), LatLon(47.650, -117.410),
        )
        val camera = frame(zone)
        for (corner in zone.points!!) {
            val (x, y) = onScreen(corner.lat, corner.lon, camera)
            assertTrue("x=$x", abs(x) <= 411.0 / 2 - 64.0 + 0.5)
            assertTrue("y=$y", abs(y) <= 914.0 / 2 - 64.0 + 0.5)
        }
    }

    @Test fun the_bigger_the_zone_the_further_out_the_camera() {
        val small = frame(circle(200.0)).zoom
        val medium = frame(circle(1_000.0)).zoom
        val large = frame(circle(5_000.0)).zoom
        assertTrue("$large < $medium", large < medium)
        assertTrue("$medium < $small", medium < small)
    }

    @Test fun the_old_fixed_zoom_14_was_far_too_close_for_a_large_zone() {
        // A 5 km zone is 10 km across; at zoom 14 a phone 411 dp wide shows about 1.3 km at Spokane.
        val camera = frame(circle(5_000.0))
        assertTrue("it needs well over three zoom levels less than 14, not ${camera.zoom}", camera.zoom < 11.0)
    }

    @Test fun a_small_zone_still_shows_the_streets_around_it() {
        val camera = frame(circle(50.0))
        assertTrue("the view spans at least 600 m, not ${camera.groundWidthMeters}", camera.groundWidthMeters >= 600.0 - 1e-6)
        for ((lat, lon) in edge(circle(50.0))) {
            val (x, y) = onScreen(lat, lon, camera)
            assertTrue(abs(x) < 411.0 / 2 && abs(y) < 914.0 / 2)
        }
    }

    @Test fun the_ground_width_is_what_the_zoom_shows_across_the_width_of_the_view() {
        val camera = frame(circle(5_000.0))
        val expected = 411.0 * 40_075_016.686 * cos(Math.toRadians(camera.lat)) / (512.0 * 2.0.pow(camera.zoom))
        assertEquals(expected, camera.groundWidthMeters, expected * 1e-9)
    }

    @Test fun the_camera_is_centred_on_a_circles_centre() {
        val camera = frame(circle(5_000.0))
        assertEquals(spokane.lat, camera.lat, 1e-4)
        assertEquals(spokane.lon, camera.lon, 1e-9)
    }

    @Test fun it_never_goes_closer_than_the_cap_whatever_the_zone() {
        val tiny = checkNotNull(GeofenceFraming.boundsOf(circle(1.0)))
        val camera = GeofenceFraming.cameraFor(tiny, 411.0, 914.0, FrameInsets.uniform(64.0), minViewMeters = 0.0, maxZoom = 18.0)
        assertEquals(18.0, camera.zoom, 0.0)
    }

    @Test fun a_view_that_has_no_size_yet_is_framed_as_a_phone_and_gives_a_real_camera() {
        val zone = circle(1_000.0)
        val unsized = frame(zone, widthDp = 0.0, heightDp = 0.0)
        val phone = frame(zone, widthDp = 360.0, heightDp = 640.0)
        assertTrue(unsized.zoom.isFinite() && unsized.groundWidthMeters.isFinite())
        assertEquals(phone.zoom, unsized.zoom, 1e-9)
    }

    @Test fun a_view_smaller_than_its_padding_still_gives_a_real_camera() {
        val camera = frame(circle(1_000.0), widthDp = 100.0, heightDp = 100.0)
        assertTrue(camera.zoom.isFinite() && camera.zoom >= 0.0)
    }

    @Test fun a_zone_of_one_point_does_not_divide_by_zero() {
        val camera = GeofenceFraming.cameraFor(GeoBounds(47.0, -117.0, 47.0, -117.0), 411.0, 914.0)
        assertTrue(camera.zoom.isFinite())
        assertEquals(47.0, camera.lat, 1e-9)
    }
}
