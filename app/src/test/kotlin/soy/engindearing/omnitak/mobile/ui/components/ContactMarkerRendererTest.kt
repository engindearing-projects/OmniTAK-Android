package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.CoTEvent

/**
 * Locks the pure pieces of the #77 native-contact-marker fix. Dropped markers
 * (and received tracks) were invisible on the 2D map because the contacts-src
 * GeoJsonSource circle/symbol layers do not paint on Adreno/Mali/emulator GL.
 * ContactMarkerRenderer renders them as native annotations instead; these are
 * the viewport-cull and bitmap-cache helpers it relies on.
 */
class ContactMarkerRendererTest {

    @Test fun in_bounds_accepts_points_inside_the_viewport() {
        // Taipei-ish box; a point inside renders.
        assertTrue(ContactMarkerRenderer.inBounds(25.04, 121.56, 24.9, 121.4, 25.2, 121.7))
    }

    @Test fun in_bounds_rejects_points_outside_the_viewport() {
        // Off-screen contact must be culled (the #77 markers were near 23.7,121.0).
        assertFalse(ContactMarkerRenderer.inBounds(23.71, 121.00, 24.9, 121.4, 25.2, 121.7))
    }

    @Test fun in_bounds_is_inclusive_on_the_edges() {
        assertTrue(ContactMarkerRenderer.inBounds(25.2, 121.7, 24.9, 121.4, 25.2, 121.7))
        assertTrue(ContactMarkerRenderer.inBounds(24.9, 121.4, 24.9, 121.4, 25.2, 121.7))
    }

    @Test fun cache_key_differs_by_color_so_team_recolor_gets_a_fresh_bitmap() {
        val hostile = ContactMarkerRenderer.cacheKey(0xFFF44336.toInt(), "CR1")
        val friend = ContactMarkerRenderer.cacheKey(0xFF4ADE80.toInt(), "CR1")
        assertNotEquals("same callsign, different color must not share a bitmap", hostile, friend)
    }

    @Test fun cache_key_differs_by_callsign() {
        val a = ContactMarkerRenderer.cacheKey(0xFFF44336.toInt(), "Marker 1")
        val b = ContactMarkerRenderer.cacheKey(0xFFF44336.toInt(), "Marker 2")
        assertNotEquals(a, b)
    }

    @Test fun cache_key_is_stable_for_the_same_inputs() {
        // Same contact across PPLI updates reuses one cached bitmap.
        assertEquals(
            ContactMarkerRenderer.cacheKey(0xFF4ADE80.toInt(), "ALPHA-1"),
            ContactMarkerRenderer.cacheKey(0xFF4ADE80.toInt(), "ALPHA-1"),
        )
    }

    // --- #150: tapping a 2D contact pin must open the edit sheet, not the
    // native MapLibre InfoWindow. The pin is an annotation Marker whose default
    // tap handler shows `.title()` and eats the touch, so the map-click hit-test
    // (which opens the sheet) never runs. The fix routes the marker click back
    // to the contact via this id→CoTEvent registry, then decides what to do.

    private fun contact(uid: String) = CoTEvent(uid = uid, type = "a-f-G-U-C", lat = 0.0, lon = 0.0)

    @Test fun marker_id_resolves_to_the_contact_rendered_for_it() {
        ContactMarkerRenderer.markerContacts.clear()
        val alpha = contact("ALPHA-1")
        ContactMarkerRenderer.markerContacts[7L] = alpha
        assertSame(alpha, ContactMarkerRenderer.contactForMarkerId(7L))
    }

    @Test fun unknown_marker_id_resolves_to_null_so_kml_pins_keep_their_label() {
        // KML placemarks are also addMarker annotations but carry no contact —
        // the listener must pass those through (return null) so their InfoWindow
        // label still works.
        ContactMarkerRenderer.markerContacts.clear()
        ContactMarkerRenderer.markerContacts[7L] = contact("ALPHA-1")
        assertNull(ContactMarkerRenderer.contactForMarkerId(404L))
    }

    @Test fun decide_opens_edit_when_a_contact_is_tapped_in_normal_mode() {
        assertEquals(
            MarkerTapAction.OPEN_CONTACT_EDIT,
            decideMarkerTap(contact = contact("ALPHA-1"), modeHandled = false),
        )
    }

    @Test fun decide_defers_to_an_active_mode_handler_over_editing() {
        // Measurement / drawing / mission modes that consumed the tap win —
        // tapping a pin while measuring drops a vertex, it doesn't open editing.
        assertEquals(
            MarkerTapAction.CONSUMED_BY_MODE,
            decideMarkerTap(contact = contact("ALPHA-1"), modeHandled = true),
        )
    }

    @Test fun decide_passes_through_non_contact_markers() {
        // A null contact (KML placemark, unknown annotation) is left to MapLibre's
        // default behaviour regardless of mode.
        assertEquals(MarkerTapAction.PASS_THROUGH, decideMarkerTap(contact = null, modeHandled = false))
        assertEquals(MarkerTapAction.PASS_THROUGH, decideMarkerTap(contact = null, modeHandled = true))
    }
}
