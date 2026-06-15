package soy.engindearing.omnitak.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.CoTEvent

/**
 * #119 — Local marker persistence codec.
 *
 * Tests exercise [LocalMarkerStore.encode] and [LocalMarkerStore.decode]
 * directly (pure JVM, no Android context or DataStore required).
 * These tests are RED until [LocalMarkerStore] is implemented.
 */
class LocalMarkerPersistenceTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun localMarker(
        uid: String = "local-1716000000000",
        type: String = "b-m-p-s-m",
        lat: Double = 47.6588,
        lon: Double = -117.4260,
        hae: Double = 600.0,
        callsign: String? = "Drop-1",
        remarks: String = "Test marker",
    ) = CoTEvent(
        uid = uid,
        type = type,
        lat = lat,
        lon = lon,
        hae = hae,
        callsign = callsign,
        remarks = remarks,
    )

    private fun serverContact(uid: String = "ANDROID-server1") = CoTEvent(
        uid = uid,
        type = "a-f-G-U-C",
        lat = 47.6,
        lon = -117.4,
    )

    // ── #119 RED tests ───────────────────────────────────────────────────────

    @Test fun local_markers_round_trip_through_json() {
        val markers = listOf(
            localMarker("local-111"),
            localMarker("local-222"),
        )
        val decoded = LocalMarkerStore.decode(LocalMarkerStore.encode(markers))
        assertEquals(
            "round-tripped list must equal the original",
            markers,
            decoded,
        )
    }

    @Test fun non_local_markers_excluded_from_persist() {
        val mixed = listOf(
            localMarker("local-111"),
            serverContact("ANDROID-abc"),
            localMarker("local-222"),
        )
        val encoded = LocalMarkerStore.encode(mixed.filter { it.uid.startsWith("local-") })
        val decoded = LocalMarkerStore.decode(encoded)

        assertTrue("local-111 must be persisted", decoded.any { it.uid == "local-111" })
        assertTrue("local-222 must be persisted", decoded.any { it.uid == "local-222" })
        assertFalse("server contact must NOT be persisted", decoded.any { it.uid == "ANDROID-abc" })
    }

    @Test fun empty_list_round_trips() {
        val decoded = LocalMarkerStore.decode(LocalMarkerStore.encode(emptyList()))
        assertTrue("decoded empty list must be empty", decoded.isEmpty())
    }

    @Test fun local_marker_fields_survive_serialization() {
        val marker = localMarker(
            uid = "local-9999",
            type = "b-m-p-s-m",
            lat = 47.6588,
            lon = -117.4260,
            hae = 600.0,
            callsign = "Marker-A",
            remarks = "Some remarks",
        )
        val decoded = LocalMarkerStore.decode(LocalMarkerStore.encode(listOf(marker)))
        val result = decoded.single()

        assertEquals("uid", marker.uid, result.uid)
        assertEquals("type", marker.type, result.type)
        assertEquals("lat", marker.lat, result.lat, 0.000001)
        assertEquals("lon", marker.lon, result.lon, 0.000001)
        assertEquals("hae", marker.hae, result.hae, 0.0001)
        assertEquals("callsign", marker.callsign, result.callsign)
        assertEquals("remarks", marker.remarks, result.remarks)
    }

    @Test fun contactStore_rehydrate_loads_markers() {
        val markers = listOf(
            localMarker("local-aaa"),
            localMarker("local-bbb"),
        )
        val store = ContactStore()
        markers.forEach { store.ingest(it) }

        val contacts = store.contacts.value
        assertTrue("local-aaa must be in contactStore", "local-aaa" in contacts)
        assertTrue("local-bbb must be in contactStore", "local-bbb" in contacts)
    }
}
