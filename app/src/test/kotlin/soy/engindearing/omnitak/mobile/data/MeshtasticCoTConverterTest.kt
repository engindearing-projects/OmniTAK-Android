package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.domain.ContactStore

/**
 * Node to CoT conversion where "last heard" can be unknown.
 *
 * A node-list entry can be days old, so the contact must carry when the radio
 * last heard the node (the map's age label and fade read it), not the moment
 * the list was read. Node ids, names and coordinates are made up.
 */
class MeshtasticCoTConverterTest {

    private val now = 1_790_003_600_000L // ms
    private val heardAt = 1_790_000_000L // s, one hour before [now]

    private fun node(lastHeard: Long?, battery: Int? = null) = MeshNode(
        id = 0x0A0B0C0DL,
        shortName = "TNA",
        longName = "Test Node Alpha",
        position = MeshPosition(lat = 1.2345678, lon = -2.3456789, altitudeM = 15),
        lastHeardEpoch = lastHeard,
        batteryLevel = battery,
    )

    private fun convert(node: MeshNode): CoTEvent {
        val event = MeshtasticCoTConverter.nodeToCoT(node, nowMs = now)
        assertNotNull(event)
        return event!!
    }

    @Test fun the_contact_carries_when_the_radio_last_heard_the_node() {
        assertEquals(heardAt * 1_000L, convert(node(heardAt)).receivedAtMs)
    }

    @Test fun a_last_heard_in_the_future_is_capped_at_now() {
        assertEquals(now, convert(node(heardAt + 86_400L)).receivedAtMs)
    }

    @Test fun an_unknown_last_heard_leaves_the_receive_time_for_the_store_to_stamp() {
        assertEquals(0L, convert(node(null)).receivedAtMs)
    }

    @Test fun the_contact_store_keeps_the_last_heard_time() {
        val store = ContactStore()
        val event = convert(node(heardAt))
        store.ingest(event, nowMs = now)
        assertEquals(heardAt * 1_000L, store.contacts.value.getValue(event.uid).receivedAtMs)
    }

    @Test fun the_contact_store_stamps_the_receive_time_when_last_heard_is_unknown() {
        val store = ContactStore()
        val event = convert(node(null))
        store.ingest(event, nowMs = now)
        assertEquals(now, store.contacts.value.getValue(event.uid).receivedAtMs)
    }

    @Test fun the_last_heard_element_is_an_iso_time_when_known() {
        val xml = convert(node(heardAt)).rawXml!!
        assertTrue(xml, xml.contains("<last_heard>${CotXml.isoMillis(heardAt * 1_000L)}</last_heard>"))
        assertFalse("a known last heard is not 1970", xml.contains("1970-"))
    }

    @Test fun the_last_heard_element_is_left_out_when_unknown() {
        val xml = convert(node(null)).rawXml!!
        assertFalse(xml, xml.contains("<last_heard>"))
        assertFalse("an unknown last heard must not turn into 1970", xml.contains("1970-"))
    }

    @Test fun remarks_show_a_percentage_for_a_normal_battery_level() {
        assertTrue(convert(node(heardAt, battery = 64)).remarks.contains("Bat: 64%"))
    }

    @Test fun remarks_show_powered_for_the_above_100_battery_level() {
        val remarks = convert(node(heardAt, battery = 101)).remarks
        assertTrue(remarks, remarks.contains("Bat: powered"))
        assertFalse(remarks, remarks.contains("101%"))
    }
}
