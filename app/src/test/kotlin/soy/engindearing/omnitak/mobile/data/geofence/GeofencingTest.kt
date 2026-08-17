package soy.engindearing.omnitak.mobile.data.geofence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #155 — General geofencing (ported from iOS GeofenceService): circle + polygon
 * zones with entry/exit/dwell transitions. Pure, so it unit-tests directly; the
 * Android layer feeds contact position updates in and surfaces the events as
 * notifications + map highlights.
 */
class GeofencingTest {

    private val circle = CircleZone("c", centerLat = 47.0, centerLon = -122.0, radiusMeters = 1000.0)
    private val square = PolygonZone(
        "sq",
        listOf(LatLon(-1.0, -1.0), LatLon(-1.0, 1.0), LatLon(1.0, 1.0), LatLon(1.0, -1.0)),
    )

    @Test fun circle_contains_center_excludes_far() {
        assertTrue(circle.contains(47.0, -122.0))
        assertFalse(circle.contains(48.0, -122.0)) // ~111 km away
    }

    @Test fun circle_radius_boundary() {
        assertTrue(circle.contains(47.0 + 0.008, -122.0))  // ~890 m — inside
        assertFalse(circle.contains(47.0 + 0.010, -122.0)) // ~1113 m — outside
    }

    @Test fun polygon_contains_via_ray_casting() {
        assertTrue(square.contains(0.0, 0.0))    // center
        assertFalse(square.contains(2.0, 2.0))   // outside (NE)
        assertFalse(square.contains(0.0, 5.0))   // outside (E)
        assertTrue(square.contains(0.9, 0.9))    // inside near corner
    }

    @Test fun monitor_emits_entry_then_exit_then_reentry() {
        val m = GeofenceMonitor(listOf(circle))
        assertEquals(listOf(GeofenceEventType.ENTRY), m.update("A", 47.0, -122.0, 1000L).map { it.type })
        assertTrue("staying inside emits nothing", m.update("A", 47.0, -122.0, 2000L).isEmpty())
        assertEquals(listOf(GeofenceEventType.EXIT), m.update("A", 48.0, -122.0, 3000L).map { it.type })
        assertTrue("staying outside emits nothing", m.update("A", 49.0, -122.0, 3500L).isEmpty())
        assertEquals(listOf(GeofenceEventType.ENTRY), m.update("A", 47.0, -122.0, 4000L).map { it.type })
    }

    @Test fun monitor_tracks_contacts_independently() {
        val m = GeofenceMonitor(listOf(circle))
        assertEquals(listOf(GeofenceEventType.ENTRY), m.update("A", 47.0, -122.0, 1L).map { it.type })
        assertEquals(listOf(GeofenceEventType.ENTRY), m.update("B", 47.0, -122.0, 2L).map { it.type })
        assertTrue(m.update("A", 47.0, -122.0, 3L).isEmpty())
    }

    @Test fun monitor_dwell_fires_once_after_threshold() {
        val m = GeofenceMonitor(listOf(circle), dwellThresholdMs = 5000L)
        assertEquals(listOf(GeofenceEventType.ENTRY), m.update("A", 47.0, -122.0, 0L).map { it.type })
        assertTrue("before threshold", m.update("A", 47.0, -122.0, 3000L).isEmpty())
        assertEquals(listOf(GeofenceEventType.DWELL), m.update("A", 47.0, -122.0, 6000L).map { it.type })
        assertTrue("dwell fires only once", m.update("A", 47.0, -122.0, 9000L).isEmpty())
    }

    @Test fun monitor_reports_only_the_zone_entered() {
        val far = CircleZone("c2", 10.0, 10.0, 1000.0)
        val m = GeofenceMonitor(listOf(circle, far))
        val events = m.update("A", 47.0, -122.0, 1L)
        assertEquals(1, events.size)
        assertEquals("c", events[0].zoneId)
    }
}
