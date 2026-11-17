package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.ContactMaxAge.Decision

/**
 * #215: the max-age rule. Pure functions of epoch-millis values and a [CoTEvent], so these run
 * on a fixed clock with no Android dependencies, like [CoTAgeTest].
 *
 * The rule: age = now minus when this device last RECEIVED a report from the contact.
 *  - age < max age: fresh
 *  - max age <= age < 2 x max age: hidden from the map, kept in the store
 *  - age >= 2 x max age: removed from the store
 */
class ContactMaxAgeTest {

    private val MIN = 60_000L
    private val NOW = 1_800_000_000_000L

    // ── the decision, at every boundary ───────────────────────────────────────

    private fun at(ageMs: Long, maxAgeMinutes: Int) =
        ContactMaxAge.decide(nowMs = NOW, lastReceivedMs = NOW - ageMs, maxAgeMinutes = maxAgeMinutes)

    @Test fun just_under_max_age_is_fresh() {
        assertEquals(Decision.FRESH, at(5 * MIN - 1, maxAgeMinutes = 5))
    }

    @Test fun at_max_age_is_hidden() {
        assertEquals(Decision.HIDDEN, at(5 * MIN, maxAgeMinutes = 5))
    }

    @Test fun just_under_twice_max_age_is_still_hidden() {
        assertEquals(Decision.HIDDEN, at(10 * MIN - 1, maxAgeMinutes = 5))
    }

    @Test fun at_twice_max_age_is_removed() {
        assertEquals(Decision.REMOVE, at(10 * MIN, maxAgeMinutes = 5))
    }

    @Test fun long_past_twice_max_age_is_removed() {
        assertEquals(Decision.REMOVE, at(30 * 24 * 60 * MIN, maxAgeMinutes = 5))
    }

    @Test fun a_report_just_received_is_fresh() {
        assertEquals(Decision.FRESH, at(0L, maxAgeMinutes = 5))
    }

    @Test fun every_choice_has_the_same_boundaries() {
        for (minutes in ContactMaxAge.CHOICES.filter { it > 0 }) {
            val max = minutes * MIN
            assertEquals("$minutes min: just under", Decision.FRESH, at(max - 1, minutes))
            assertEquals("$minutes min: at", Decision.HIDDEN, at(max, minutes))
            assertEquals("$minutes min: just under 2x", Decision.HIDDEN, at(2 * max - 1, minutes))
            assertEquals("$minutes min: at 2x", Decision.REMOVE, at(2 * max, minutes))
        }
    }

    // ── Never, and clocks that cannot be measured ─────────────────────────────

    @Test fun never_keeps_everything_however_old() {
        assertEquals(Decision.FRESH, at(10 * MIN, ContactMaxAge.NEVER))
        assertEquals(Decision.FRESH, at(365L * 24 * 60 * MIN, ContactMaxAge.NEVER))
    }

    @Test fun a_negative_setting_is_treated_as_never() {
        assertEquals(Decision.FRESH, at(10_000 * MIN, maxAgeMinutes = -5))
    }

    @Test fun a_clock_earlier_than_the_last_report_is_fresh() {
        // The device clock was set back, or the report is stamped in our future: do not hide.
        assertEquals(Decision.FRESH, at(-1L, maxAgeMinutes = 5))
        assertEquals(Decision.FRESH, at(-100 * MIN, maxAgeMinutes = 5))
    }

    @Test fun a_contact_with_no_received_stamp_is_fresh() {
        // 0 means the store never stamped it; there is nothing to measure.
        assertEquals(Decision.FRESH, ContactMaxAge.decide(NOW, lastReceivedMs = 0L, maxAgeMinutes = 5))
    }

    @Test fun a_new_report_after_hiding_makes_it_fresh_again() {
        val max = 5
        val lastReport = NOW - 7 * MIN
        assertEquals(Decision.HIDDEN, ContactMaxAge.decide(NOW, lastReport, max))
        // The same contact reports again right now.
        assertEquals(Decision.FRESH, ContactMaxAge.decide(NOW, lastReceivedMs = NOW, maxAgeMinutes = max))
    }

    @Test fun the_setting_defaults_to_30_and_offers_the_spec_choices() {
        assertEquals(30, ContactMaxAge.DEFAULT_MINUTES)
        assertEquals(listOf(5, 10, 15, 30, 60, 120, 0), ContactMaxAge.CHOICES)
        assertEquals(0, ContactMaxAge.NEVER)
    }

    // ── which contacts the rule applies to ────────────────────────────────────

    private fun event(
        uid: String = "ANDROID-bravo2",
        type: String = "a-f-G-U-C",
        how: String? = "m-g",
        iconsetPath: String? = null,
    ) = CoTEvent(uid = uid, type = type, lat = 1.0, lon = 2.0, how = how, iconsetPath = iconsetPath)

    @Test fun another_operators_position_report_is_affected() {
        assertTrue(ContactMaxAge.appliesTo(event(), selfUid = "ANDROID-me"))
    }

    @Test fun a_position_report_from_each_friendly_unit_type_is_affected() {
        for (type in listOf("a-f-G-U-C", "a-f-G-U-C-I", "a-f-G-E-V-C", "a-f-A-M-H", "a-f-S-X")) {
            assertTrue(type, ContactMaxAge.appliesTo(event(type = type), selfUid = "ANDROID-me"))
        }
    }

    @Test fun a_local_marker_is_not_affected() {
        // A marker this operator dropped: uid local-<timestamp>.
        assertFalse(ContactMaxAge.appliesTo(event(uid = "local-1716000000000", how = "h-g-i-g-o"), "ANDROID-me"))
        // Even if every other field looked like a position report.
        assertFalse(ContactMaxAge.appliesTo(event(uid = "local-1716000000000"), "ANDROID-me"))
    }

    @Test fun a_bookmark_point_or_spot_is_not_affected() {
        assertFalse(ContactMaxAge.appliesTo(event(uid = "spot-1", type = "b-m-p-s-m", how = "h-g-i-g-o"), "ANDROID-me"))
        assertFalse(ContactMaxAge.appliesTo(event(uid = "wp-1", type = "b-m-p-w", how = "h-g-i-g-o"), "ANDROID-me"))
    }

    @Test fun my_own_marker_is_not_affected() {
        // A server that echoes my position back is still me.
        assertFalse(ContactMaxAge.appliesTo(event(uid = "ANDROID-me"), selfUid = "ANDROID-me"))
    }

    @Test fun without_a_known_own_uid_nobody_is_exempted() {
        assertTrue(ContactMaxAge.appliesTo(event(uid = "ANDROID-bravo2"), selfUid = ""))
        assertTrue(ContactMaxAge.appliesTo(event(uid = "ANDROID-bravo2"), selfUid = null))
    }

    @Test fun a_hostile_marker_another_operator_placed_is_not_affected() {
        assertFalse(ContactMaxAge.appliesTo(event(uid = "6f1c-uuid", type = "a-h-G", how = "h-g-i-g-o"), "ANDROID-me"))
    }

    @Test fun a_neutral_or_unknown_marker_another_operator_placed_is_not_affected() {
        assertFalse(ContactMaxAge.appliesTo(event(uid = "n-uuid", type = "a-n-G", how = "h-g-i-g-o"), "ANDROID-me"))
        assertFalse(ContactMaxAge.appliesTo(event(uid = "u-uuid", type = "a-u-G", how = "h-g-i-g-o"), "ANDROID-me"))
    }

    @Test fun a_friendly_marker_another_operator_placed_is_not_affected() {
        // Same type as a position report, but a person put it there (how h-...).
        assertFalse(ContactMaxAge.appliesTo(event(uid = "f-uuid", type = "a-f-G-U-C", how = "h-g-i-g-o"), "ANDROID-me"))
        assertFalse(ContactMaxAge.appliesTo(event(uid = "f-uuid", type = "a-f-G-U-C", how = "h-e"), "ANDROID-me"))
    }

    @Test fun a_fema_marker_is_not_affected() {
        val fema = event(
            uid = "fema-uuid",
            type = "a-f-G-I-U-T",
            how = "h-g-i-g-o",
            iconsetPath = "COT_MAPPING_FEMA/Fire/Hydrant",
        )
        assertFalse(ContactMaxAge.appliesTo(fema, "ANDROID-me"))
    }

    @Test fun a_report_is_not_affected() {
        assertFalse(ContactMaxAge.appliesTo(event(uid = "casevac-1", type = "b-r-f-h-c", how = "h-g-i-g-o"), "ANDROID-me"))
    }

    @Test fun a_drawing_is_not_affected() {
        assertFalse(ContactMaxAge.appliesTo(event(uid = "circle-1", type = "u-d-c-c", how = "h-e"), "ANDROID-me"))
    }

    @Test fun a_remote_id_drone_and_its_pilot_are_not_affected() {
        assertFalse(ContactMaxAge.appliesTo(event(uid = "RID-FA123456789", type = "a-u-A-M-F-Q-r"), "ANDROID-me"))
        // The pilot marker is a friendly unit reported by a machine: only its uid keeps it out.
        assertFalse(ContactMaxAge.appliesTo(event(uid = "RID-OP-FA123456789", type = "a-f-G-U-C"), "ANDROID-me"))
    }

    @Test fun a_mesh_node_marker_is_not_affected() {
        assertFalse(ContactMaxAge.appliesTo(event(uid = "MESHTASTIC-0A1B2C3D"), "ANDROID-me"))
        assertFalse(ContactMaxAge.appliesTo(event(uid = "MESHCORE-0A1B2C3D4E5F"), "ANDROID-me"))
        assertFalse(ContactMaxAge.appliesTo(event(uid = "mesh-self-0a1b2c3d"), "ANDROID-me"))
        assertFalse(ContactMaxAge.appliesTo(event(uid = "mesh-0a1b2c3d"), "ANDROID-me"))
    }

    @Test fun a_neutral_or_unknown_machine_track_is_not_affected() {
        // An aircraft or vessel feed from a server: machine-reported, but not a friendly unit.
        assertFalse(ContactMaxAge.appliesTo(event(uid = "ICAO-A1B2C3", type = "a-n-A-C-F", how = "m-f"), "ANDROID-me"))
        assertFalse(ContactMaxAge.appliesTo(event(uid = "MMSI-123", type = "a-u-S", how = "m-g"), "ANDROID-me"))
    }

    @Test fun an_event_with_no_how_is_left_alone() {
        // It came through a path that does not read how: when it is unclear, do nothing.
        assertFalse(ContactMaxAge.appliesTo(event(how = null), "ANDROID-me"))
    }

    // ── the decision for a stored contact ─────────────────────────────────────

    @Test fun something_the_rule_does_not_apply_to_is_fresh_at_any_age() {
        val marker = event(uid = "local-1", how = "h-g-i-g-o").copy(receivedAtMs = NOW - 1_000 * MIN)
        assertEquals(Decision.FRESH, ContactMaxAge.decide(marker, NOW, maxAgeMinutes = 5, selfUid = "ANDROID-me"))
    }

    @Test fun an_affected_contact_follows_its_received_time() {
        val report = event()
        assertEquals(
            Decision.FRESH,
            ContactMaxAge.decide(report.copy(receivedAtMs = NOW - 4 * MIN), NOW, 5, "ANDROID-me"),
        )
        assertEquals(
            Decision.HIDDEN,
            ContactMaxAge.decide(report.copy(receivedAtMs = NOW - 5 * MIN), NOW, 5, "ANDROID-me"),
        )
        assertEquals(
            Decision.REMOVE,
            ContactMaxAge.decide(report.copy(receivedAtMs = NOW - 10 * MIN), NOW, 5, "ANDROID-me"),
        )
    }

    @Test fun the_cot_stale_time_does_not_change_the_decision() {
        // Senders often set stale a few minutes ahead. The rule reads when we received the report.
        val staleSoon = event().copy(
            receivedAtMs = NOW - 2 * MIN,
            timeIso = "2026-01-01T00:00:00.000Z",
            staleIso = "2026-01-01T00:02:00.000Z",
        )
        assertEquals(Decision.FRESH, ContactMaxAge.decide(staleSoon, NOW, 30, "ANDROID-me"))
    }

    // ── words ─────────────────────────────────────────────────────────────────

    @Test fun minutes_read_the_way_settings_shows_them() {
        assertEquals("5 min", ContactMaxAge.minutesLabel(5))
        assertEquals("30 min", ContactMaxAge.minutesLabel(30))
        assertEquals("1 h", ContactMaxAge.minutesLabel(60))
        assertEquals("2 h", ContactMaxAge.minutesLabel(120))
        assertEquals("1 h 30 min", ContactMaxAge.minutesLabel(90))
        assertEquals("Never", ContactMaxAge.minutesLabel(0))
    }

    @Test fun the_section_title_names_the_chosen_value() {
        assertEquals("Not heard from for over 30 min", ContactMaxAge.sectionTitle(30))
        assertEquals("Not heard from for over 5 min", ContactMaxAge.sectionTitle(5))
        assertEquals("Not heard from for over 1 h", ContactMaxAge.sectionTitle(60))
    }

    @Test fun a_row_shows_how_long_ago_the_last_report_came() {
        assertEquals("<1 min", ContactMaxAge.ageLabel(0L))
        assertEquals("<1 min", ContactMaxAge.ageLabel(59_999L))
        assertEquals("<1 min", ContactMaxAge.ageLabel(-5_000L))
        assertEquals("1 min", ContactMaxAge.ageLabel(60_000L))
        assertEquals("42 min", ContactMaxAge.ageLabel(42 * MIN + 30_000L))
        assertEquals("1 h", ContactMaxAge.ageLabel(60 * MIN))
        assertEquals("1 h 5 min", ContactMaxAge.ageLabel(65 * MIN))
        assertEquals("3 h 10 min", ContactMaxAge.ageLabel(190 * MIN))
    }
}
