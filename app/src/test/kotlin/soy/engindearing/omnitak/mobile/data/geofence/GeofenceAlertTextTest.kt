package soy.engindearing.omnitak.mobile.data.geofence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.i18n.Loc
import soy.engindearing.omnitak.mobile.i18n.LocStrings

/**
 * #155 - the words of a geofence alert, word for word the iOS sentences; the line on a card; the
 * order alerts are shown in (five seconds each, with the time passed in).
 */
class GeofenceAlertTextTest {

    private val english: GeofenceLocalize = { key -> LocStrings.catalogue(Loc.Language.ENGLISH).getValue(key) }

    private fun sentence(type: GeofenceEventType, byOperator: Boolean, stayMs: Long? = null) =
        GeofenceAlertText.sentence(type, "North gate", "ALPHA-2", byOperator, stayMs, english)

    @Test fun sentences_for_the_operator() {
        assertEquals("You entered North gate", sentence(GeofenceEventType.ENTRY, true))
        assertEquals("You left North gate", sentence(GeofenceEventType.EXIT, true))
        assertEquals("You have been in North gate for 5 min", sentence(GeofenceEventType.DWELL, true, 300_000))
    }

    @Test fun sentences_for_a_teammate_use_his_name() {
        assertEquals("ALPHA-2 entered North gate", sentence(GeofenceEventType.ENTRY, false))
        assertEquals("ALPHA-2 left North gate", sentence(GeofenceEventType.EXIT, false))
        assertEquals("ALPHA-2 has been in North gate for 5 min", sentence(GeofenceEventType.DWELL, false, 300_000))
    }

    @Test fun a_sentence_from_a_transition() {
        val t = GeofenceTransition(
            geofenceId = "g", geofenceName = "Yard", subjectId = "x", subjectName = "BRAVO", isOperator = false,
            type = GeofenceEventType.ENTRY, lat = 0.0, lon = 0.0, atMs = 0L, stayMs = null,
        )
        assertEquals("BRAVO entered Yard", GeofenceAlertText.sentence(t, english))
    }

    @Test fun a_sentence_from_an_event_in_the_history_and_one_that_did_not_say_who() {
        val named = GeofenceEvent(
            id = "e", geofenceId = "g", geofenceName = "North gate", type = GeofenceEventType.EXIT, atMs = 0L,
            lat = 0.0, lon = 0.0, subjectId = "bravo", subjectName = "BRAVO", byOperator = false, stayMs = 90_000,
        )
        assertEquals("BRAVO left North gate", GeofenceAlertText.sentence(named, english))
        assertEquals("You left North gate", GeofenceAlertText.sentence(named.copy(byOperator = true), english))
        assertEquals(
            "Someone entered North gate",
            GeofenceAlertText.sentence(named.copy(type = GeofenceEventType.ENTRY, subjectName = null), english),
        )
    }

    @Test fun durations() {
        fun d(ms: Long) = GeofenceAlertText.duration(ms, english)
        assertEquals("0 s", d(-5_000))
        assertEquals("0 s", d(0))
        assertEquals("45 s", d(45_000))
        assertEquals("1 min", d(60_000))
        assertEquals("4 min", d(299_000))
        assertEquals("5 min", d(300_000))
        assertEquals("59 min", d(3_599_000))
        assertEquals("1 h", d(3_600_000))
        assertEquals("1 h 5 min", d(3_900_000))
        assertEquals("2 h 1 min", d(7_261_000))
    }

    @Test fun the_state_line_on_a_card() {
        fun line(active: Boolean, you: Boolean, mates: Int) =
            GeofenceAlertText.stateLine(active, GeofenceOccupancy(you, mates), english)
        assertEquals("Switched off", line(false, true, 3))
        assertEquals("Nobody inside", line(true, false, 0))
        assertEquals("You are inside", line(true, true, 0))
        assertEquals("1 teammate inside", line(true, false, 1))
        assertEquals("2 teammates inside", line(true, false, 2))
        assertEquals("You and 1 teammate are inside", line(true, true, 1))
        assertEquals("You and 3 teammates are inside", line(true, true, 3))
    }

    @Test fun every_string_the_geofences_use_is_in_english_and_in_traditional_chinese() {
        val en = LocStrings.catalogue(Loc.Language.ENGLISH)
        val zh = LocStrings.catalogue(Loc.Language.TRADITIONAL_CHINESE)
        val geofenceKeys = en.keys.filter { it.startsWith("geofence.") || it.startsWith("tools.geofence") }
        assertTrue("the geofence strings are missing from English", geofenceKeys.size >= 60)
        for (key in geofenceKeys) {
            assertTrue("zh-Hant lacks $key", zh.containsKey(key))
            assertNotEquals("zh-Hant $key is the English text", en.getValue(key), zh.getValue(key))
        }
        for (key in zh.keys.filter { it.startsWith("geofence.") || it.startsWith("tools.geofence") }) {
            assertTrue("English lacks $key", en.containsKey(key))
        }
    }

    @Test fun the_chinese_alert_sentences_take_the_same_arguments_as_the_english_ones() {
        val zh: GeofenceLocalize = { key -> LocStrings.catalogue(Loc.Language.TRADITIONAL_CHINESE).getValue(key) }
        assertEquals("你進入了 North gate", GeofenceAlertText.sentence(GeofenceEventType.ENTRY, "North gate", "ALPHA-2", true, null, zh))
        assertEquals("ALPHA-2 離開了 North gate", GeofenceAlertText.sentence(GeofenceEventType.EXIT, "North gate", "ALPHA-2", false, null, zh))
        assertEquals(
            "ALPHA-2 已在 North gate 停留 5 分鐘",
            GeofenceAlertText.sentence(GeofenceEventType.DWELL, "North gate", "ALPHA-2", false, 300_000, zh),
        )
    }

    // ------------------------------------------------------------------ banners, one at a time

    private fun banner(text: String) = GeofenceBanner(id = text, sentence = text, type = GeofenceEventType.ENTRY, geofenceId = "g")

    private fun at(seconds: Double): Long = (seconds * 1000).toLong()

    @Test fun banners_are_shown_one_at_a_time_for_five_seconds_each() {
        val q = GeofenceBannerQueue()
        val a = banner("A")
        val b = banner("B")
        val c = banner("C")
        q.add(a, at(0.0))
        q.add(b, at(1.0))
        q.add(c, at(2.0))
        assertEquals(a, q.current)
        assertEquals(at(5.0), q.showUntilMs)
        q.tick(at(4.9))
        assertEquals(a, q.current)
        q.tick(at(5.0))
        assertEquals(b, q.current)
        assertEquals("the next one gets its own five seconds", at(10.0), q.showUntilMs)
        q.tick(at(9.99))
        assertEquals(b, q.current)
        q.tick(at(10.0))
        assertEquals(c, q.current)
        q.tick(at(15.0))
        assertNull(q.current)
        assertNull(q.showUntilMs)
    }

    @Test fun dismissing_a_banner_shows_the_next() {
        val q = GeofenceBannerQueue()
        val a = banner("A")
        val b = banner("B")
        q.add(a, at(0.0))
        q.add(b, at(0.0))
        q.dismissCurrent(at(1.0))
        assertEquals(b, q.current)
        assertEquals(at(6.0), q.showUntilMs)
        q.dismissCurrent(at(2.0))
        assertNull(q.current)
        q.dismissCurrent(at(3.0)) // nothing showing: nothing to do
        assertNull(q.current)
    }

    @Test fun a_banner_added_to_an_empty_queue_shows_at_once() {
        val q = GeofenceBannerQueue()
        q.tick(at(100.0))
        q.add(banner("A"), at(100.0))
        assertEquals("A", q.current?.sentence)
        assertEquals(at(105.0), q.showUntilMs)
    }

    @Test fun too_many_waiting_banners_drop_the_oldest() {
        val q = GeofenceBannerQueue()
        for (i in 0 until 30) q.add(banner("$i"), at(0.0))
        assertEquals("0", q.current?.sentence)
        assertEquals(GeofenceBannerQueue.MOST_WAITING, q.waiting.size)
        assertEquals("the newest are kept", "29", q.waiting.last().sentence)
        assertEquals("20", q.waiting.first().sentence)
    }
}
