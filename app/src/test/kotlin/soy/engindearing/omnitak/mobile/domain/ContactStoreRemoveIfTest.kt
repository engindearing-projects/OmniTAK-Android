package soy.engindearing.omnitak.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.CoTEvent

/**
 * #215: [ContactStore.removeIf], the removal the max-age rule uses at twice the max age.
 * The point of it over `remove(uid)` is that the decision is made inside the update loop, so a
 * report that lands while it runs is judged on its new stamp.
 */
class ContactStoreRemoveIfTest {

    private fun event(uid: String) = CoTEvent(uid = uid, type = "a-f-G-U-C", lat = 0.0, lon = 0.0)

    @Test fun removes_exactly_the_contacts_the_predicate_names_and_counts_them() {
        val store = ContactStore()
        store.ingest(event("old-1"), nowMs = 1_000L)
        store.ingest(event("old-2"), nowMs = 2_000L)
        store.ingest(event("new-1"), nowMs = 9_000L)

        val removed = store.removeIf { it.receivedAtMs < 5_000L }

        assertEquals(2, removed)
        assertEquals(setOf("new-1"), store.contacts.value.keys)
    }

    @Test fun removing_nothing_returns_zero_and_leaves_the_same_map() {
        val store = ContactStore()
        store.ingest(event("a"), nowMs = 1L)
        val before = store.contacts.value

        assertEquals(0, store.removeIf { false })

        assertSame("no new map, so nothing is emitted", before, store.contacts.value)
    }

    @Test fun a_report_that_lands_while_deciding_is_judged_on_its_new_stamp() {
        val store = ContactStore()
        store.ingest(event("quiet"), nowMs = 1_000L)

        var landed = false
        val removed = store.removeIf { contact ->
            if (!landed) {
                // A fresh report for the same contact arrives after this pass read the map.
                landed = true
                store.ingest(event("quiet"), nowMs = 9_000_000L)
            }
            contact.receivedAtMs < 5_000L
        }

        assertEquals("the refreshed contact is not removed", 0, removed)
        assertEquals(9_000_000L, store.contacts.value["quiet"]?.receivedAtMs)
    }

    @Test fun it_does_not_disturb_contacts_it_keeps() {
        val store = ContactStore()
        val keep = event("keep").copy(callsign = "KEEP-1", remarks = "unchanged")
        store.ingest(keep, nowMs = 9_000L)
        store.ingest(event("drop"), nowMs = 1L)

        store.removeIf { it.uid == "drop" }

        assertTrue("keep" in store.contacts.value)
        assertEquals("KEEP-1", store.contacts.value["keep"]?.callsign)
        assertEquals(9_000L, store.contacts.value["keep"]?.receivedAtMs)
        assertFalse("drop" in store.contacts.value)
    }
}
