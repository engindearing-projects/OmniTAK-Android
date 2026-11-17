package soy.engindearing.omnitak.mobile.data.geofence

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #155 - what a geofence is: its fields, its defaults, and what they mean. */
class GeofenceModelTest {

    private val json = Json { encodeDefaults = true }
    private val lenientJson = Json { ignoreUnknownKeys = true }

    private fun circle(id: String = "g") = Geofence(
        id = id, name = "North gate", type = GeofenceType.CIRCLE,
        centerLat = 47.6588, centerLon = -117.4260, radiusMeters = 200.0,
    )

    @Test fun a_new_geofence_watches_both_and_alerts_on_entry_and_exit_with_no_dwell() {
        val g = circle()
        assertTrue(g.isActive)
        assertTrue(g.watchSelf)
        assertTrue(g.watchContacts)
        assertTrue(g.alertOnEntry)
        assertTrue(g.alertOnExit)
        assertEquals(0, g.dwellMinutes)
        assertEquals(0L, g.dwellMs)
    }

    @Test fun the_dwell_time_is_in_minutes_and_read_in_ms() {
        assertEquals(60_000L, circle().copy(dwellMinutes = 1).dwellMs)
        assertEquals(3_600_000L, circle().copy(dwellMinutes = 60).dwellMs)
        assertEquals("a negative time is off", 0L, circle().copy(dwellMinutes = -3).dwellMs)
    }

    @Test fun a_circle_needs_a_centre_on_the_earth_and_a_radius_above_zero() {
        assertTrue(circle().hasUsableShape)
        assertFalse(circle().copy(radiusMeters = 0.0).hasUsableShape)
        assertFalse(circle().copy(radiusMeters = -5.0).hasUsableShape)
        assertFalse(circle().copy(radiusMeters = Double.NaN).hasUsableShape)
        assertFalse(circle().copy(radiusMeters = null).hasUsableShape)
        assertFalse(circle().copy(centerLat = null).hasUsableShape)
        assertFalse(circle().copy(centerLat = 91.0).hasUsableShape)
        assertFalse(circle().copy(centerLon = 181.0).hasUsableShape)
        assertFalse(circle().copy(centerLon = Double.NaN).hasUsableShape)
    }

    @Test fun a_polygon_needs_three_corners_on_the_earth() {
        val three = listOf(LatLon(0.0, 0.0), LatLon(0.0, 1.0), LatLon(1.0, 1.0))
        val poly = Geofence(id = "p", name = "Yard", type = GeofenceType.POLYGON, points = three)
        assertTrue(poly.hasUsableShape)
        assertFalse(poly.copy(points = three.take(2)).hasUsableShape)
        assertFalse(poly.copy(points = null).hasUsableShape)
        assertFalse(poly.copy(points = three + LatLon(Double.NaN, 0.0)).hasUsableShape)
    }

    @Test fun the_name_is_written_at_the_centre_of_a_circle_and_the_mean_of_a_polygons_corners() {
        val at = circle().labelPoint!!
        assertEquals(47.6588, at.lat, 0.0)
        assertEquals(-117.4260, at.lon, 0.0)

        val square = Geofence(
            id = "p", name = "Yard", type = GeofenceType.POLYGON,
            points = listOf(LatLon(0.0, 0.0), LatLon(0.0, 2.0), LatLon(2.0, 2.0), LatLon(2.0, 0.0)),
        )
        val mean = square.labelPoint!!
        assertEquals(1.0, mean.lat, 1e-12)
        assertEquals(1.0, mean.lon, 1e-12)

        assertNull(Geofence(id = "n", name = "None", type = GeofenceType.POLYGON).labelPoint)
        assertNull(Geofence(id = "n", name = "None", type = GeofenceType.CIRCLE).labelPoint)
    }

    @Test fun it_round_trips_through_json_with_every_field() {
        val original = circle().copy(
            color = GeofenceColor.PURPLE, isActive = false, watchSelf = false, watchContacts = true,
            alertOnEntry = false, alertOnExit = true, dwellMinutes = 30, createdAtMs = 1_800_000_000_000L,
        )
        assertEquals(original, json.decodeFromString<Geofence>(json.encodeToString(original)))

        val poly = Geofence(
            id = "p", name = "Yard", type = GeofenceType.POLYGON,
            points = listOf(LatLon(1.0, 2.0), LatLon(3.0, 4.0), LatLon(5.0, 6.0)),
        )
        assertEquals(poly, json.decodeFromString<Geofence>(json.encodeToString(poly)))
    }

    @Test fun json_that_leaves_out_what_has_a_default_reads_with_the_defaults() {
        val g = lenientJson.decodeFromString<Geofence>(
            """{"id":"x","name":"Old","type":"CIRCLE","centerLat":47.0,"centerLon":-122.0,"radiusMeters":250.0,"somethingNew":1}""",
        )
        assertEquals("Old", g.name)
        assertTrue(g.isActive)
        assertTrue(g.watchSelf)
        assertTrue(g.watchContacts)
        assertTrue(g.alertOnEntry)
        assertTrue(g.alertOnExit)
        assertEquals(0, g.dwellMinutes)
        assertEquals(GeofenceColor.YELLOW, g.color)
    }

    @Test fun the_colours_are_the_ones_ios_uses() {
        assertEquals("#FF3B30", GeofenceColor.RED.hex)
        assertEquals("#5AC8FA", GeofenceColor.CYAN.hex)
        assertEquals("#FFCC00", GeofenceColor.YELLOW.hex)
        assertEquals(GeofenceColor.entries.size, GeofenceColor.entries.map { it.hex }.toSet().size)
    }
}
