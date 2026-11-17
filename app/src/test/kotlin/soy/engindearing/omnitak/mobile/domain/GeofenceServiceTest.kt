package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.SelfFix
import soy.engindearing.omnitak.mobile.data.geofence.Geofence
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceAlertText
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceEventType
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceOccupancy
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceType

/**
 * #155 - the service that watches the geofences, driven by a fake operator position and the real
 * contact store, on virtual time. It decides nothing itself (the rules are in GeofenceMonitorTest);
 * these pin what it does with them: baselines on the first sighting, banners one at a time, the
 * history, the notification when the app is behind, a contact that goes quiet, a dwell that needs
 * no new report.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GeofenceServiceTest {

    private val spokaneLat = 47.6588
    private val spokaneLon = -117.4260
    private val t0 = 1_800_000_000_000L

    private val inside = OperatorPosition(spokaneLat, spokaneLon)
    private val outside = OperatorPosition(spokaneLat + 1_000.0 / 111_200.0, spokaneLon)

    private fun zone(
        id: String = "north",
        name: String = "North gate",
        dwellMinutes: Int = 0,
        watchSelf: Boolean = true,
        watchContacts: Boolean = true,
    ) = Geofence(
        id = id, name = name, type = GeofenceType.CIRCLE,
        centerLat = spokaneLat, centerLon = spokaneLon, radiusMeters = 200.0,
        dwellMinutes = dwellMinutes, watchSelf = watchSelf, watchContacts = watchContacts,
    )

    private fun teammate(
        uid: String = "ANDROID-bravo",
        at: OperatorPosition = inside,
        type: String = "a-f-G-U-C",
        how: String? = "m-g",
        callsign: String? = "BRAVO-2",
    ) = CoTEvent(uid = uid, type = type, lat = at.lat, lon = at.lon, callsign = callsign, how = how)

    /** The service over an in-memory store, a fake operator position and a real contact store. */
    private inner class Rig(scope: TestScope) {
        val store = GeofenceStore()
        val operator = MutableStateFlow<OperatorPosition?>(null)
        val contacts = ContactStore()
        val gone = mutableSetOf<String>()
        val ignored = mutableSetOf<String>()
        val notified = mutableListOf<String>()
        var background = false
        var keepLocationOnCalls = 0
        private var ids = 0

        val service = GeofenceService(
            store = store,
            operatorPosition = operator,
            contacts = contacts.contacts,
            goneContacts = { gone },
            ignoredContacts = { ignored },
            selfUid = { "ANDROID-me" },
            selfCallsign = { "ALPHA-1" },
            keepLocationOn = { keepLocationOnCalls++ },
            inBackground = { background },
            notify = { notified += it },
            clock = { t0 + scope.testScheduler.currentTime },
            newId = { "id-${ids++}" },
        )

        init {
            scope.backgroundScope.launch { service.run() }
            scope.runCurrent()
        }

        /** What the history says, newest first, in the words the list shows. */
        val sentences: List<String> get() = store.events.value.map { GeofenceAlertText.sentence(it) }
    }

    private fun TestScope.seconds(n: Long) {
        advanceTimeBy(n * 1000)
        runCurrent()
    }

    private fun Rig.moveTo(position: OperatorPosition, scope: TestScope) {
        operator.value = position
        scope.runCurrent()
    }

    // ------------------------------------------------------------------ the baseline

    @Test fun making_a_geofence_around_yourself_raises_nothing() = runTest {
        val rig = Rig(this)
        rig.operator.value = inside
        runCurrent()
        rig.store.add(zone())
        runCurrent()
        assertTrue(rig.store.events.value.isEmpty())
        assertNull(rig.service.banner.value)
        assertTrue(rig.notified.isEmpty())
        // ...but it knows you are in it.
        assertEquals(GeofenceOccupancy(operatorInside = true, teammates = 0), rig.service.occupancy.value["north"])
    }

    @Test fun a_geofence_made_before_the_first_fix_baselines_on_the_first_fix() = runTest {
        val rig = Rig(this)
        rig.store.add(zone())
        runCurrent()
        rig.moveTo(inside, this)
        assertTrue("the first position is a baseline, not an entry", rig.store.events.value.isEmpty())
        assertTrue(rig.service.occupancy.value.getValue("north").operatorInside)
    }

    // ------------------------------------------------------------------ the operator

    @Test fun leaving_and_coming_back_say_you_left_and_you_entered() = runTest {
        val rig = Rig(this)
        rig.operator.value = inside
        runCurrent()
        rig.store.add(zone())
        runCurrent()

        rig.moveTo(outside, this)
        assertEquals("You left North gate", rig.service.banner.value?.sentence)
        assertEquals(GeofenceEventType.EXIT, rig.service.banner.value?.type)

        seconds(6)
        assertNull("the banner has had its five seconds", rig.service.banner.value)

        rig.moveTo(inside, this)
        assertEquals("You entered North gate", rig.service.banner.value?.sentence)

        assertEquals(listOf("You entered North gate", "You left North gate"), rig.sentences)
        val exit = rig.store.events.value.last()
        assertTrue(exit.byOperator)
        assertEquals("ALPHA-1", exit.subjectName)
        assertEquals("North gate", exit.geofenceName)
    }

    @Test fun a_stay_that_lasted_is_in_the_history_with_how_long() = runTest {
        val rig = Rig(this)
        rig.operator.value = inside
        runCurrent()
        rig.store.add(zone())
        runCurrent()
        seconds(120)
        rig.moveTo(outside, this)
        val exit = rig.store.events.value.first()
        assertEquals(GeofenceEventType.EXIT, exit.type)
        assertEquals(120_000L, exit.stayMs)
    }

    @Test fun every_alert_is_a_notification_too_while_the_app_is_behind() = runTest {
        val rig = Rig(this)
        rig.operator.value = inside
        runCurrent()
        rig.store.add(zone())
        runCurrent()

        rig.background = false
        rig.moveTo(outside, this)
        assertTrue("the banner is the alert while the app is in front", rig.notified.isEmpty())

        seconds(6)
        rig.background = true
        rig.moveTo(inside, this)
        assertEquals(listOf("You entered North gate"), rig.notified)
    }

    @Test fun a_zone_that_only_watches_teammates_says_nothing_about_the_operator() = runTest {
        val rig = Rig(this)
        rig.operator.value = inside
        runCurrent()
        rig.store.add(zone(watchSelf = false))
        runCurrent()
        rig.moveTo(outside, this)
        rig.moveTo(inside, this)
        assertTrue(rig.store.events.value.isEmpty())
    }

    // ------------------------------------------------------------------ teammates

    @Test fun a_teammate_who_crosses_the_edge_is_named() = runTest {
        val rig = Rig(this)
        rig.contacts.ingest(teammate(at = outside))
        runCurrent()
        rig.store.add(zone())
        runCurrent()

        rig.contacts.ingest(teammate(at = inside))
        runCurrent()
        assertEquals("BRAVO-2 entered North gate", rig.service.banner.value?.sentence)
        assertEquals(GeofenceOccupancy(operatorInside = false, teammates = 1), rig.service.occupancy.value["north"])

        seconds(6)
        rig.contacts.ingest(teammate(at = outside))
        runCurrent()
        assertEquals("BRAVO-2 left North gate", rig.service.banner.value?.sentence)
        assertEquals(GeofenceOccupancy(), rig.service.occupancy.value["north"])
        assertFalse(rig.store.events.value.first().byOperator)
    }

    @Test fun a_teammate_already_inside_when_the_geofence_is_made_raises_nothing_and_is_counted() = runTest {
        val rig = Rig(this)
        rig.contacts.ingest(teammate(uid = "a", callsign = "A", at = inside))
        rig.contacts.ingest(teammate(uid = "b", callsign = "B", at = inside))
        runCurrent()
        rig.store.add(zone())
        runCurrent()
        assertTrue(rig.store.events.value.isEmpty())
        assertEquals(2, rig.service.occupancy.value.getValue("north").teammates)
    }

    @Test fun a_teammate_with_no_callsign_is_called_by_his_uid() = runTest {
        val rig = Rig(this)
        rig.contacts.ingest(teammate(uid = "ANDROID-xyz", callsign = null, at = outside))
        runCurrent()
        rig.store.add(zone())
        runCurrent()
        rig.contacts.ingest(teammate(uid = "ANDROID-xyz", callsign = null, at = inside))
        runCurrent()
        assertEquals("ANDROID-xyz entered North gate", rig.service.banner.value?.sentence)
    }

    @Test fun a_mesh_contact_counts_like_any_other() = runTest {
        val rig = Rig(this)
        rig.contacts.ingest(teammate(uid = "MESHTASTIC-7F3A", callsign = "Node 7F3A", at = outside, how = null))
        runCurrent()
        rig.store.add(zone())
        runCurrent()
        rig.contacts.ingest(teammate(uid = "MESHTASTIC-7F3A", callsign = "Node 7F3A", at = inside, how = null))
        runCurrent()
        assertEquals("Node 7F3A entered North gate", rig.service.banner.value?.sentence)
    }

    @Test fun what_is_not_a_teammate_is_not_watched() = runTest {
        val rig = Rig(this)
        val notTeammates = listOf(
            teammate(uid = "hostile-1", type = "a-h-G"),
            teammate(uid = "local-1763337600000", how = null),
            teammate(uid = "RID-9f", type = "a-u-A"),
            teammate(uid = "ANDROID-me"),
            teammate(uid = "hand-placed", how = "h-g-i-g-o"),
            teammate(uid = "radio-paired"),
        )
        rig.ignored += "radio-paired"
        notTeammates.forEach { rig.contacts.ingest(it.copy(lat = outside.lat, lon = outside.lon)) }
        runCurrent()
        rig.store.add(zone())
        runCurrent()
        notTeammates.forEach { rig.contacts.ingest(it.copy(lat = inside.lat, lon = inside.lon)) }
        runCurrent()
        assertTrue(rig.store.events.value.isEmpty())
        assertEquals(GeofenceOccupancy(), rig.service.occupancy.value["north"])
    }

    @Test fun a_zone_that_only_watches_the_operator_says_nothing_about_teammates() = runTest {
        val rig = Rig(this)
        rig.contacts.ingest(teammate(at = outside))
        runCurrent()
        rig.store.add(zone(watchContacts = false))
        runCurrent()
        rig.contacts.ingest(teammate(at = inside))
        runCurrent()
        assertTrue(rig.store.events.value.isEmpty())
    }

    // ------------------------------------------------------------------ dwell and the tick

    @Test fun a_teammate_who_stands_still_dwells_without_any_new_report() = runTest {
        val rig = Rig(this)
        rig.contacts.ingest(teammate(at = inside))
        runCurrent()
        rig.store.add(zone(dwellMinutes = 1))
        runCurrent()
        seconds(50)
        assertTrue("not yet a minute", rig.store.events.value.isEmpty())
        seconds(10) // the pass at one minute
        assertEquals("BRAVO-2 has been in North gate for 1 min", rig.service.banner.value?.sentence)
        assertEquals(1, rig.store.events.value.size)
        seconds(300)
        assertEquals("once per stay", 1, rig.store.events.value.size)
    }

    @Test fun the_operator_who_stands_still_dwells_too() = runTest {
        val rig = Rig(this)
        rig.operator.value = inside
        runCurrent()
        rig.store.add(zone(dwellMinutes = 5))
        runCurrent()
        seconds(305)
        assertEquals(listOf("You have been in North gate for 5 min"), rig.sentences)
    }

    // ------------------------------------------------------------------ a subject that goes

    @Test fun a_contact_removed_from_the_map_raises_nothing_and_comes_back_as_a_new_baseline() = runTest {
        val rig = Rig(this)
        rig.contacts.ingest(teammate(at = inside))
        runCurrent()
        rig.store.add(zone())
        runCurrent()
        assertEquals(1, rig.service.occupancy.value.getValue("north").teammates)

        rig.contacts.remove("ANDROID-bravo")
        seconds(10)
        assertEquals("forgotten at the next pass", 0, rig.service.occupancy.value.getValue("north").teammates)
        assertTrue("it left without a word", rig.store.events.value.isEmpty())

        rig.contacts.ingest(teammate(at = outside))
        runCurrent()
        assertTrue("it was inside when last seen, but that was forgotten: no exit", rig.store.events.value.isEmpty())
    }

    @Test fun a_contact_the_map_has_hidden_as_not_heard_from_is_forgotten_the_same_way() = runTest {
        val rig = Rig(this)
        rig.contacts.ingest(teammate(at = inside))
        runCurrent()
        rig.store.add(zone())
        runCurrent()

        rig.gone += "ANDROID-bravo"
        seconds(10)
        assertEquals(0, rig.service.occupancy.value.getValue("north").teammates)

        rig.gone.clear()
        rig.contacts.ingest(teammate(at = outside))
        runCurrent()
        assertTrue(rig.store.events.value.isEmpty())
    }

    // ------------------------------------------------------------------ switched off, deleted

    @Test fun switching_a_geofence_off_and_on_starts_from_a_new_baseline() = runTest {
        val rig = Rig(this)
        rig.operator.value = outside
        runCurrent()
        rig.store.add(zone())
        runCurrent()

        rig.store.setActive("north", false)
        runCurrent()
        assertNull("off: nothing is said about it", rig.service.occupancy.value["north"])
        rig.moveTo(inside, this)
        assertTrue(rig.store.events.value.isEmpty())

        rig.store.setActive("north", true)
        runCurrent()
        assertTrue("on again: you are already inside, which is the new baseline, not an entry", rig.store.events.value.isEmpty())
        assertTrue(rig.service.occupancy.value.getValue("north").operatorInside)
    }

    @Test fun deleting_a_geofence_forgets_it() = runTest {
        val rig = Rig(this)
        rig.operator.value = inside
        runCurrent()
        rig.store.add(zone())
        runCurrent()
        rig.store.remove("north")
        runCurrent()
        assertTrue(rig.service.occupancy.value.isEmpty())
        rig.moveTo(outside, this)
        assertTrue(rig.store.events.value.isEmpty())
    }

    // ------------------------------------------------------------------ the banners

    @Test fun two_alerts_at_once_are_shown_one_at_a_time_five_seconds_each() = runTest {
        val rig = Rig(this)
        rig.operator.value = inside
        runCurrent()
        rig.store.add(zone("a", "Alpha zone"))
        rig.store.add(zone("b", "Bravo zone"))
        runCurrent()

        rig.moveTo(outside, this)
        assertEquals("You left Alpha zone", rig.service.banner.value?.sentence)
        seconds(4)
        assertEquals("You left Alpha zone", rig.service.banner.value?.sentence)
        seconds(2)
        assertEquals("You left Bravo zone", rig.service.banner.value?.sentence)
        seconds(5)
        assertNull(rig.service.banner.value)
    }

    @Test fun dismissing_the_banner_shows_the_next_one() = runTest {
        val rig = Rig(this)
        rig.operator.value = inside
        runCurrent()
        rig.store.add(zone("a", "Alpha zone"))
        rig.store.add(zone("b", "Bravo zone"))
        runCurrent()
        rig.moveTo(outside, this)
        assertEquals("You left Alpha zone", rig.service.banner.value?.sentence)

        rig.service.dismissBanner()
        runCurrent()
        assertEquals("You left Bravo zone", rig.service.banner.value?.sentence)
        rig.service.dismissBanner()
        runCurrent()
        assertNull(rig.service.banner.value)
        rig.service.dismissBanner()
        runCurrent()
        assertNull("nothing showing: nothing to do", rig.service.banner.value)
    }

    // ------------------------------------------------------------------ the phone's location

    @Test fun the_location_is_kept_on_while_the_operator_is_watched_and_not_otherwise() = runTest {
        val rig = Rig(this)
        runCurrent()
        assertEquals("no geofence: nothing asked of the phone", 0, rig.keepLocationOnCalls)

        rig.store.add(zone(watchSelf = false))
        runCurrent()
        seconds(30)
        assertEquals("teammates only: the phone's own position is not needed", 0, rig.keepLocationOnCalls)

        rig.store.add(zone("b", "Other", watchSelf = true))
        runCurrent()
        assertTrue(rig.keepLocationOnCalls >= 1)
        val before = rig.keepLocationOnCalls
        seconds(30)
        assertTrue("it is checked again on every pass, in case the permission arrived", rig.keepLocationOnCalls > before)
    }

    // ------------------------------------------------------------------ the operator's position

    @Test fun the_operator_is_where_the_position_card_says_never_where_he_was_last_run() {
        fun fix(lat: Double, restored: Boolean = false) =
            SelfFix(lat = lat, lon = 0.0, altitudeM = 0.0, speedKmh = 0.0, accuracyM = 5f, timeMs = 1L, restored = restored)
        assertEquals(OperatorPosition(1.0, 0.0), operatorPositionOf(null, fix(1.0)))
        assertEquals("a position placed by hand wins", OperatorPosition(2.0, 0.0), operatorPositionOf(fix(2.0), fix(1.0)))
        assertNull("a fix kept from the last run is not a position to watch", operatorPositionOf(null, fix(1.0, restored = true)))
        assertEquals(OperatorPosition(2.0, 0.0), operatorPositionOf(fix(2.0), fix(1.0, restored = true)))
        assertNull(operatorPositionOf(null, null))
    }
}
