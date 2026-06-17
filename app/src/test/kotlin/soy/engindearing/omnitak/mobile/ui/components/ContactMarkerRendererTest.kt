package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
