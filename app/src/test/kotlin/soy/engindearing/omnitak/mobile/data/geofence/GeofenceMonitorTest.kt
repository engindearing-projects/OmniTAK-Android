package soy.engindearing.omnitak.mobile.data.geofence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #155 - the monitor's rules, each as a pure function of its inputs, with the time passed in. The
 * same cases, in the same order, as the iOS app's `GeofenceTests`, so the two platforms can be
 * checked against each other rule for rule:
 *  - the first sighting is a baseline and raises nothing (it used to raise an entry here);
 *  - entry, exit and dwell once per stay, each zone with its own dwell time;
 *  - a zone that is off or deleted forgets; so does a subject that goes.
 */
class GeofenceMonitorTest {

    private val spokaneLat = 47.6588
    private val spokaneLon = -117.4260

    /** Seconds from some fixed start, in ms. */
    private fun at(seconds: Long): Long = 1_800_000_000_000L + seconds * 1000L

    /** A point [meters] north of Spokane (flat maths, good to well under 1 percent here). */
    private fun north(meters: Double) = LatLon(spokaneLat + meters / 111_200.0, spokaneLon)

    private val inside = LatLon(spokaneLat, spokaneLon)
    private val outside = north(1_000.0)

    private fun zone(
        name: String = "North gate",
        radius: Double = 200.0,
        entry: Boolean = true,
        exit: Boolean = true,
        dwellMinutes: Int = 0,
        active: Boolean = true,
        watchSelf: Boolean = true,
        watchContacts: Boolean = true,
        id: String = name,
    ) = Geofence(
        id = id, name = name, type = GeofenceType.CIRCLE, color = GeofenceColor.RED, isActive = active,
        watchSelf = watchSelf, watchContacts = watchContacts,
        alertOnEntry = entry, alertOnExit = exit, dwellMinutes = dwellMinutes,
        centerLat = spokaneLat, centerLon = spokaneLon, radiusMeters = radius,
    )

    private fun square(halfSide: Double = 0.001) = Geofence(
        id = "yard", name = "Yard", type = GeofenceType.POLYGON, color = GeofenceColor.BLUE,
        points = listOf(
            LatLon(spokaneLat - halfSide, spokaneLon - halfSide),
            LatLon(spokaneLat - halfSide, spokaneLon + halfSide),
            LatLon(spokaneLat + halfSide, spokaneLon + halfSide),
            LatLon(spokaneLat + halfSide, spokaneLon - halfSide),
        ),
    )

    private fun GeofenceMonitor.see(
        where: LatLon,
        seconds: Long,
        zones: List<Geofence>,
        subject: String = "me",
        name: String = "ALPHA-1",
        isOperator: Boolean = true,
    ): List<GeofenceTransition> = update(subject, name, where.lat, where.lon, at(seconds), zones, isOperator)

    private fun GeofenceMonitor.seeTeammate(where: LatLon, seconds: Long, zones: List<Geofence>, id: String = "bravo") =
        see(where, seconds, zones, subject = id, name = id.uppercase(), isOperator = false)

    private fun types(list: List<GeofenceTransition>) = list.map { it.type }

    // ------------------------------------------------------------------ the first sighting is a baseline

    @Test fun first_sighting_inside_raises_nothing() {
        // Making a zone around yourself must not announce that you entered it.
        assertTrue(GeofenceMonitor().see(inside, 0, listOf(zone())).isEmpty())
    }

    @Test fun first_sighting_outside_raises_nothing() {
        assertTrue(GeofenceMonitor().see(outside, 0, listOf(zone())).isEmpty())
    }

    @Test fun the_baseline_is_per_subject_and_per_zone() {
        val m = GeofenceMonitor()
        val a = zone("A")
        val b = zone("B")
        // The operator is baselined against both; a teammate seen later gets a baseline of his own.
        assertTrue(m.see(inside, 0, listOf(a, b)).isEmpty())
        assertTrue(m.seeTeammate(inside, 1, listOf(a, b)).isEmpty())
        // A zone added afterwards baselines both of them again, quietly.
        val c = zone("C")
        assertTrue(m.see(inside, 2, listOf(a, b, c)).isEmpty())
        assertTrue(m.seeTeammate(inside, 3, listOf(a, b, c)).isEmpty())
    }

    // ------------------------------------------------------------------ entry and exit

    @Test fun entry_when_outside_then_inside() {
        val m = GeofenceMonitor()
        val z = zone()
        m.see(outside, 0, listOf(z))
        val result = m.see(inside, 10, listOf(z))
        assertEquals(1, result.size)
        val entry = result.single()
        assertEquals(GeofenceEventType.ENTRY, entry.type)
        assertEquals(z.id, entry.geofenceId)
        assertEquals("North gate", entry.geofenceName)
        assertEquals("me", entry.subjectId)
        assertEquals("ALPHA-1", entry.subjectName)
        assertTrue(entry.isOperator)
        assertEquals(at(10), entry.atMs)
        assertEquals(inside.lat, entry.lat, 0.0)
        assertEquals(inside.lon, entry.lon, 0.0)
        assertEquals(null, entry.stayMs)
    }

    @Test fun exit_when_inside_then_outside_and_it_says_how_long_the_stay_was() {
        val m = GeofenceMonitor()
        val z = zone()
        m.see(inside, 0, listOf(z))
        val result = m.see(outside, 90, listOf(z))
        assertEquals(listOf(GeofenceEventType.EXIT), types(result))
        assertEquals(90_000L, result.single().stayMs)
    }

    @Test fun nothing_while_staying_inside_or_outside() {
        val m = GeofenceMonitor()
        val z = zone()
        m.see(inside, 0, listOf(z))
        assertTrue(m.see(inside, 5, listOf(z)).isEmpty())
        assertTrue(m.see(north(50.0), 6, listOf(z)).isEmpty())
        m.see(outside, 20, listOf(z))
        assertTrue(m.see(north(2_000.0), 30, listOf(z)).isEmpty())
    }

    @Test fun reentry_raises_entry_again() {
        val m = GeofenceMonitor()
        val z = zone()
        m.see(inside, 0, listOf(z))
        assertEquals(listOf(GeofenceEventType.EXIT), types(m.see(outside, 10, listOf(z))))
        assertEquals(listOf(GeofenceEventType.ENTRY), types(m.see(inside, 20, listOf(z))))
    }

    @Test fun subjects_are_tracked_independently() {
        val m = GeofenceMonitor()
        val z = listOf(zone())
        m.see(outside, 0, z)
        m.seeTeammate(outside, 0, z)
        assertEquals(listOf(GeofenceEventType.ENTRY), types(m.seeTeammate(inside, 10, z)))
        // The operator has not moved: nothing for him.
        assertTrue(m.see(outside, 11, z).isEmpty())
        assertEquals(listOf(GeofenceEventType.ENTRY), types(m.see(inside, 12, z)))
    }

    @Test fun only_the_zone_that_was_crossed_reports() {
        val m = GeofenceMonitor()
        val a = zone("A")
        val far = zone("B").copy(centerLat = 10.0, centerLon = 10.0)
        m.see(outside, 0, listOf(a, far))
        assertEquals(listOf("A"), m.see(inside, 5, listOf(a, far)).map { it.geofenceName })
    }

    // ------------------------------------------------------------------ dwell, with time passed in

    @Test fun dwell_fires_once_at_the_threshold_and_again_on_the_next_stay() {
        val m = GeofenceMonitor()
        val z = listOf(zone(dwellMinutes = 5))
        m.see(inside, 0, z) // baseline: the stay is counted from here
        assertTrue(m.see(inside, 299, z).isEmpty())
        val dwell = m.see(inside, 300, z)
        assertEquals(listOf(GeofenceEventType.DWELL), types(dwell))
        assertEquals(300_000L, dwell.single().stayMs)
        assertTrue("once per stay", m.see(inside, 600, z).isEmpty())
        assertEquals(listOf(GeofenceEventType.EXIT), types(m.see(outside, 700, z)))
        assertEquals(listOf(GeofenceEventType.ENTRY), types(m.see(inside, 800, z)))
        assertTrue(m.see(inside, 1_099, z).isEmpty())
        assertEquals("a new stay can dwell again", listOf(GeofenceEventType.DWELL), types(m.see(inside, 1_100, z)))
    }

    @Test fun no_dwell_when_the_dwell_time_is_off() {
        val m = GeofenceMonitor()
        val z = listOf(zone(dwellMinutes = 0))
        m.see(inside, 0, z)
        assertTrue(m.see(inside, 86_400, z).isEmpty())
    }

    @Test fun dwell_is_counted_from_entry_not_from_the_first_sighting_when_the_subject_entered() {
        val m = GeofenceMonitor()
        val z = listOf(zone(dwellMinutes = 5))
        m.see(outside, 0, z)
        assertEquals(listOf(GeofenceEventType.ENTRY), types(m.see(inside, 100, z)))
        assertTrue(m.see(inside, 399, z).isEmpty())
        assertEquals(listOf(GeofenceEventType.DWELL), types(m.see(inside, 400, z)))
    }

    @Test fun every_zone_has_its_own_dwell_time() {
        val m = GeofenceMonitor()
        val quick = zone("Quick", dwellMinutes = 1)
        val slow = zone("Slow", dwellMinutes = 5)
        val none = zone("None", dwellMinutes = 0)
        val zones = listOf(quick, slow, none)
        m.see(inside, 0, zones)
        assertEquals(listOf("Quick"), m.see(inside, 60, zones).map { it.geofenceName })
        assertTrue(m.see(inside, 120, zones).isEmpty())
        assertEquals(listOf("Slow"), m.see(inside, 300, zones).map { it.geofenceName })
        assertTrue(m.see(inside, 7_200, zones).isEmpty())
    }

    @Test fun a_stay_that_never_gets_new_positions_dwells_when_its_last_one_is_given_again_later() {
        // The service re-feeds every subject's last position on a tick; the monitor only has to judge it.
        val m = GeofenceMonitor()
        val z = listOf(zone(dwellMinutes = 1))
        m.see(inside, 0, z)
        assertTrue(m.see(inside, 10, z).isEmpty())
        assertTrue(m.see(inside, 50, z).isEmpty())
        assertEquals(listOf(GeofenceEventType.DWELL), types(m.see(inside, 60, z)))
    }

    // ------------------------------------------------------------------ the zone's own wishes

    @Test fun an_entry_alert_switched_off_still_tracks_so_the_exit_knows_the_stay() {
        val m = GeofenceMonitor()
        val z = listOf(zone(entry = false))
        m.see(outside, 0, z)
        assertTrue("no entry alert was asked for", m.see(inside, 10, z).isEmpty())
        val exit = m.see(outside, 70, z).single()
        assertEquals(GeofenceEventType.EXIT, exit.type)
        assertEquals(60_000L, exit.stayMs)
    }

    @Test fun an_exit_alert_switched_off_raises_no_exit_but_still_knows_the_state() {
        val m = GeofenceMonitor()
        val z = listOf(zone(exit = false))
        m.see(inside, 0, z)
        assertTrue(m.see(outside, 10, z).isEmpty())
        assertEquals(listOf(GeofenceEventType.ENTRY), types(m.see(inside, 20, z)))
    }

    @Test fun a_zone_without_a_usable_shape_watches_nothing() {
        val m = GeofenceMonitor()
        val flat = zone(radius = 0.0)
        m.see(outside, 0, listOf(flat))
        assertTrue(m.see(inside, 1, listOf(flat)).isEmpty())
        assertEquals(GeofenceOccupancy(), m.occupancy(flat.id))
    }

    // ------------------------------------------------------------------ switched off, deleted, no longer watching

    @Test fun a_switched_off_zone_forgets_its_subjects_and_switching_it_back_on_starts_from_a_new_baseline() {
        val m = GeofenceMonitor()
        val on = zone()
        val off = on.copy(isActive = false)
        m.see(outside, 0, listOf(on)) // was outside
        assertTrue("off: nothing, and it forgets", m.see(inside, 10, listOf(off)).isEmpty())
        assertTrue(
            "a new baseline: the old 'outside' must not turn this into an entry",
            m.see(inside, 20, listOf(on)).isEmpty(),
        )
        assertEquals(listOf(GeofenceEventType.EXIT), types(m.see(outside, 30, listOf(on))))
    }

    @Test fun a_deleted_zone_forgets_its_subjects() {
        val m = GeofenceMonitor()
        val z = zone()
        m.see(outside, 0, listOf(z))
        m.see(inside, 10, emptyList()) // the zone is gone
        assertTrue("the same zone id coming back is a new baseline", m.see(inside, 20, listOf(z)).isEmpty())
    }

    @Test fun retain_forgets_without_any_update() {
        val m = GeofenceMonitor()
        val z = zone()
        m.see(inside, 0, listOf(z))
        assertTrue(m.occupancy(z.id).operatorInside)
        m.retain(listOf(z.copy(isActive = false)))
        assertEquals(GeofenceOccupancy(), m.occupancy(z.id))
    }

    @Test fun a_zone_not_watching_the_operator_ignores_him_and_forgets_him() {
        val m = GeofenceMonitor()
        val z = zone()
        m.see(outside, 0, listOf(z))
        val blind = z.copy(watchSelf = false)
        assertTrue(m.see(inside, 10, listOf(blind)).isEmpty())
        assertEquals("he is not tracked at all", GeofenceOccupancy(), m.occupancy(z.id))
        assertTrue("watching him again starts from a new baseline", m.see(inside, 20, listOf(z)).isEmpty())
    }

    @Test fun a_zone_not_watching_teammates_ignores_them_but_still_watches_the_operator() {
        val m = GeofenceMonitor()
        val z = listOf(zone(watchContacts = false))
        m.see(outside, 0, z)
        m.seeTeammate(outside, 0, z)
        assertTrue(m.seeTeammate(inside, 10, z).isEmpty())
        assertEquals(listOf(GeofenceEventType.ENTRY), types(m.see(inside, 11, z)))
    }

    @Test fun a_zone_that_only_watches_teammates_does_not_track_the_operator() {
        val m = GeofenceMonitor()
        val z = listOf(zone(watchSelf = false))
        m.see(outside, 0, z)
        assertTrue(m.see(inside, 5, z).isEmpty())
        m.seeTeammate(outside, 6, z)
        assertEquals(listOf(GeofenceEventType.ENTRY), types(m.seeTeammate(inside, 7, z)))
    }

    // ------------------------------------------------------------------ a subject that goes

    @Test fun forgetting_a_subject_raises_nothing_and_its_next_sighting_is_a_baseline() {
        val m = GeofenceMonitor()
        val z = listOf(zone())
        m.seeTeammate(inside, 0, z)
        m.forget("bravo")
        assertEquals(GeofenceOccupancy(), m.occupancy(z.single().id))
        // It comes back outside: it was inside when last seen, but that is forgotten, so no exit.
        assertTrue(m.seeTeammate(outside, 600, z).isEmpty())
    }

    @Test fun forgetting_one_subject_leaves_the_others() {
        val m = GeofenceMonitor()
        val z = listOf(zone())
        m.see(inside, 0, z)
        m.seeTeammate(inside, 0, z)
        m.forget("bravo")
        assertEquals(listOf(GeofenceEventType.EXIT), types(m.see(outside, 5, z)))
    }

    // ------------------------------------------------------------------ who is inside

    @Test fun occupancy_counts_the_operator_and_the_teammates_inside() {
        val m = GeofenceMonitor()
        val z = listOf(zone())
        m.see(inside, 0, z)
        m.seeTeammate(inside, 0, z, "bravo")
        m.seeTeammate(inside, 0, z, "charlie")
        m.seeTeammate(outside, 0, z, "delta")
        assertEquals(GeofenceOccupancy(operatorInside = true, teammates = 2), m.occupancy(z.single().id))
        m.seeTeammate(outside, 5, z, "bravo")
        m.see(outside, 5, z)
        assertEquals(GeofenceOccupancy(operatorInside = false, teammates = 1), m.occupancy(z.single().id))
    }

    // ------------------------------------------------------------------ time goes only where it is told to

    @Test fun time_going_backwards_never_fires_dwell_and_never_gives_a_negative_stay() {
        val m = GeofenceMonitor()
        val z = listOf(zone(dwellMinutes = 1))
        m.see(inside, 100, z)
        assertTrue(m.see(inside, 50, z).isEmpty())
        val exit = m.see(outside, 40, z).single()
        assertEquals(0L, exit.stayMs)
    }

    // ------------------------------------------------------------------ polygons

    @Test fun a_monitor_watches_a_polygon() {
        val m = GeofenceMonitor()
        val yard = listOf(square())
        m.see(north(500.0), 0, yard)
        assertEquals(listOf(GeofenceEventType.ENTRY), types(m.see(inside, 5, yard)))
        assertEquals(listOf(GeofenceEventType.EXIT), types(m.see(north(500.0), 10, yard)))
    }

    @Test fun the_watch_helper_says_who_a_zone_watches() {
        assertTrue(zone().watches(true))
        assertTrue(zone().watches(false))
        assertFalse(zone(watchSelf = false).watches(true))
        assertFalse(zone(watchContacts = false).watches(false))
    }
}
