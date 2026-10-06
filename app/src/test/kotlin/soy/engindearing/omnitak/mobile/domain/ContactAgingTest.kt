package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.ChatConversation
import soy.engindearing.omnitak.mobile.data.ChatMessage
import soy.engindearing.omnitak.mobile.data.ChatParticipant
import soy.engindearing.omnitak.mobile.data.ChatRoom
import soy.engindearing.omnitak.mobile.data.CoTEvent

private const val START = 1_800_000_000_000L
private const val MIN = 60_000L
private const val ME = "ANDROID-me"
private const val BRAVO = "ANDROID-bravo2"

/**
 * #215: the max-age rule running against a live [ContactStore]: the timer, the report trigger
 * and the setting trigger. All on virtual time, so nothing here sleeps; the clock the rule reads
 * is the test scheduler's.
 *
 * The rule's own boundaries are in ContactMaxAgeTest. These are about WHEN it is applied and
 * what it does to the store.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactAgingTest {

    /** A teammate's own position report: a friendly unit, machine reported. */
    private fun report(uid: String = BRAVO) = CoTEvent(
        uid = uid, type = "a-f-G-U-C", lat = 38.8899, lon = -77.034,
        callsign = "BRAVO-2", how = "m-g",
    )

    private class Rig(private val scope: TestScope, maxAge: Flow<Int>) {
        val store = ContactStore()
        val aging = ContactAging(
            store = store,
            maxAgeMinutes = maxAge,
            selfUid = { ME },
            clock = { START + scope.testScheduler.currentTime },
        )

        init {
            scope.backgroundScope.launch { aging.run() }
        }

        fun now(): Long = START + scope.testScheduler.currentTime
        fun hidden(uid: String = BRAVO): Boolean = aging.state.value.isHidden(uid)
        fun inStore(uid: String = BRAVO): Boolean = store.contacts.value.containsKey(uid)
    }

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    // ── the timer ─────────────────────────────────────────────────────────────

    @Test fun a_quiet_contact_is_hidden_by_the_timer_with_nothing_arriving() = runTest {
        val rig = Rig(this, MutableStateFlow(5))
        rig.store.ingest(report(), rig.now())
        runCurrent()
        assertFalse("fresh when it has just reported", rig.hidden())

        advance(5 * MIN - 1)
        assertFalse("still fresh 1 ms before the max age", rig.hidden())

        advance(1)
        assertTrue("hidden at the max age", rig.hidden())
        assertTrue("hidden, not removed: still in the store", rig.inStore())
    }

    @Test fun at_twice_the_max_age_the_contact_leaves_the_store() = runTest {
        val rig = Rig(this, MutableStateFlow(5))
        rig.store.ingest(report(), rig.now())

        advance(10 * MIN - 1)
        assertTrue("still held 1 ms before twice the max age", rig.inStore())
        assertTrue(rig.hidden())

        advance(1)
        assertFalse("removed at twice the max age", rig.inStore())
        assertFalse("a removed contact is not also listed as hidden", rig.hidden())
    }

    @Test fun the_timer_is_every_30_seconds() = runTest {
        // Ticks at 0, 30 s, 60 s ...: a contact is caught at the first tick on or after its boundary.
        val rig = Rig(this, MutableStateFlow(5))
        rig.store.ingest(report(), rig.now() - 4 * MIN - 50_000L) // heard 4 min 50 s ago
        runCurrent()
        assertFalse(rig.hidden())

        advance(9_999) // 4 min 59.999 s old; the next tick is at +30 s
        assertFalse(rig.hidden())
        advance(1) // 5 min old, but between ticks: nothing re-evaluated yet
        assertFalse("not re-evaluated between ticks", rig.hidden())
        advance(30_000 - 10_000) // the tick at +30 s
        assertTrue("caught at the next tick", rig.hidden())
    }

    // ── a new report ──────────────────────────────────────────────────────────

    @Test fun a_new_report_after_hiding_brings_it_back_at_once_not_at_the_next_tick() = runTest {
        val rig = Rig(this, MutableStateFlow(5))
        rig.store.ingest(report(), rig.now())
        advance(6 * MIN + 10_000) // the 6:00 tick has run, the next is at 6:30
        assertTrue(rig.hidden())

        rig.store.ingest(report(), rig.now())
        runCurrent() // no time passes: only the new report can have done this
        assertFalse("back at once", rig.hidden())
        assertTrue(rig.inStore())
    }

    @Test fun a_hidden_contact_that_keeps_reporting_stays_fresh() = runTest {
        val rig = Rig(this, MutableStateFlow(5))
        repeat(40) {
            rig.store.ingest(report(), rig.now())
            advance(MIN)
            assertFalse("heard a minute ago", rig.hidden())
            assertTrue(rig.inStore())
        }
    }

    @Test fun a_report_after_removal_puts_the_contact_back_fresh() = runTest {
        val rig = Rig(this, MutableStateFlow(5))
        rig.store.ingest(report(), rig.now())
        advance(12 * MIN)
        assertFalse(rig.inStore())

        rig.store.ingest(report(), rig.now())
        runCurrent()
        assertTrue(rig.inStore())
        assertFalse(rig.hidden())
    }

    @Test fun only_the_contact_that_went_quiet_goes() = runTest {
        val rig = Rig(this, MutableStateFlow(5))
        val talkative = "ANDROID-alpha1"
        rig.store.ingest(report(), rig.now())
        repeat(12) {
            rig.store.ingest(report(talkative), rig.now())
            advance(MIN)
        }
        assertFalse("the quiet one is removed", rig.inStore(BRAVO))
        assertTrue("the one still reporting stays", rig.inStore(talkative))
        assertFalse(rig.hidden(talkative))
    }

    // ── Never, and the setting ────────────────────────────────────────────────

    @Test fun never_keeps_every_contact_however_long_it_is_quiet() = runTest {
        val rig = Rig(this, MutableStateFlow(0))
        rig.store.ingest(report(), rig.now())
        advance(24 * 60 * MIN)
        assertTrue(rig.inStore())
        assertFalse(rig.hidden())
        assertTrue(rig.aging.state.value.hiddenUids.isEmpty())
    }

    @Test fun changing_the_setting_applies_at_once_not_at_the_next_tick() = runTest {
        val max = MutableStateFlow(0)
        val rig = Rig(this, max)
        rig.store.ingest(report(), rig.now())
        advance(7 * MIN + 10_000) // 7 min 10 s quiet; the 7:00 tick has run
        assertFalse("Never: shown", rig.hidden())

        max.value = 5
        runCurrent()
        assertTrue("5 min: hidden now", rig.hidden())
        assertEquals(5, rig.aging.state.value.maxAgeMinutes)

        max.value = 30
        runCurrent()
        assertFalse("30 min: shown again", rig.hidden())

        max.value = 2
        runCurrent()
        assertFalse("2 min: past twice the max age, removed", rig.inStore())
    }

    @Test fun nothing_is_decided_before_the_saved_setting_has_been_read() = runTest {
        // The preferences flow has not emitted: a default must not hide or remove anything.
        val max = MutableSharedFlow<Int>()
        val rig = Rig(this, max)
        rig.store.ingest(report(), rig.now())
        advance(100 * MIN)
        assertTrue(rig.inStore())
        assertFalse(rig.hidden())

        max.emit(5)
        runCurrent()
        assertFalse("the saved value arrives and is applied: 100 min quiet is past twice 5 min", rig.inStore())
    }

    // ── what the rule leaves alone ────────────────────────────────────────────

    @Test fun markers_drones_mesh_nodes_and_my_own_echo_are_never_hidden_or_removed() = runTest {
        val rig = Rig(this, MutableStateFlow(5))
        val untouched = listOf(
            "local-1716000000000" to CoTEvent("local-1716000000000", "a-f-G-U-C", 1.0, 2.0, how = "h-g-i-g-o"),
            "hostile-uuid" to CoTEvent("hostile-uuid", "a-h-G", 1.0, 2.0, how = "h-g-i-g-o"),
            "friendly-marker" to CoTEvent("friendly-marker", "a-f-G-U-C", 1.0, 2.0, how = "h-g-i-g-o"),
            "fema-uuid" to CoTEvent(
                "fema-uuid", "a-f-G-I-U-T", 1.0, 2.0, how = "h-g-i-g-o",
                iconsetPath = "COT_MAPPING_FEMA/Fire/Hydrant",
            ),
            "casevac-1" to CoTEvent("casevac-1", "b-r-f-h-c", 1.0, 2.0, how = "h-g-i-g-o"),
            "circle-1" to CoTEvent("circle-1", "u-d-c-c", 1.0, 2.0, how = "h-e"),
            "spot-1" to CoTEvent("spot-1", "b-m-p-s-m", 1.0, 2.0, how = "h-g-i-g-o"),
            "RID-FA1" to CoTEvent("RID-FA1", "a-u-A-M-F-Q-r", 1.0, 2.0, how = "m-g"),
            "MESHTASTIC-0A1B2C3D" to CoTEvent("MESHTASTIC-0A1B2C3D", "a-f-G-U-C", 1.0, 2.0, how = "m-g"),
            ME to CoTEvent(ME, "a-f-G-U-C", 1.0, 2.0, how = "m-g"),
            "no-how" to CoTEvent("no-how", "a-f-G-U-C", 1.0, 2.0),
        )
        untouched.forEach { (_, event) -> rig.store.ingest(event, rig.now()) }
        rig.store.ingest(report(), rig.now())

        advance(3 * 24 * 60 * MIN)

        for ((uid, _) in untouched) {
            assertTrue("$uid stays in the store", rig.inStore(uid))
            assertFalse("$uid is never hidden", rig.hidden(uid))
        }
        assertFalse("while the teammate who went quiet is gone", rig.inStore(BRAVO))
    }

    // ── chat ──────────────────────────────────────────────────────────────────

    @Test fun removing_a_contact_does_not_touch_its_chat_conversation() = runTest {
        val rig = Rig(this, MutableStateFlow(5))
        val chat = ChatStore()
        val convoId = ChatRoom.directConversationId(ME, BRAVO)
        chat.upsertConversationIfMissing(
            ChatConversation(
                id = convoId, title = "BRAVO-2", isGroup = false,
                participants = listOf(ChatParticipant(BRAVO, "BRAVO-2")),
            ),
        )
        chat.ingest(
            ChatMessage(
                id = "m1", conversationId = convoId, senderUid = BRAVO, senderCallsign = "BRAVO-2",
                text = "moving to the north gate", timeIso = "2026-10-06T20:00:00.000Z",
            ),
        )
        rig.store.ingest(report(), rig.now())

        advance(11 * MIN)

        assertFalse("the contact is gone", rig.inStore())
        assertNotNull("the conversation is still there", chat.conversations.value[convoId])
        assertEquals(
            "with its messages",
            listOf("moving to the north gate"),
            chat.messagesByConversation.value[convoId].orEmpty().map { it.text },
        )
        assertNull(rig.store.contacts.value[BRAVO])
    }
}
