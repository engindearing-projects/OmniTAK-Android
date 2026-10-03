package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.maplibre.android.location.modes.RenderMode

/**
 * #204 — pins the self-marker puck spec so the "same bitmap on two
 * independently-drawn layers" double-draw bug can't silently come back.
 * MapLibre's LocationComponent draws the foreground and bearing layers as
 * two independent images at the same screen position under
 * RenderMode.COMPASS/GPS, so a style whose bearing image exists must never
 * equal its foreground image.
 */
class SelfPuckSpecTest {

    @Test fun milstd_has_no_bearing_image_and_uses_normal_render_mode() {
        val spec = selfPuckSpecFor(SELF_MARKER_STYLE_MIL_STD)

        assertEquals(RenderMode.NORMAL, spec.renderMode)
        assertNull("MIL-STD must not set a bearing image — NORMAL never renders one", spec.bearingImage)
        assertNull(spec.bearingStaleImage)
        assertNotNull(spec.foregroundImage)
        assertNotNull(spec.foregroundStaleImage)
    }

    @Test fun triangle_keeps_compass_with_a_transparent_foreground() {
        val spec = selfPuckSpecFor(SELF_MARKER_STYLE_TRIANGLE)

        assertEquals(RenderMode.COMPASS, spec.renderMode)
        assertEquals(SELF_TRIANGLE_TRANSPARENT_IMAGE, spec.foregroundImage)
        assertEquals(SELF_TRIANGLE_IMAGE, spec.bearingImage)
        // Foreground and bearing must be DIFFERENT registered images, or
        // COMPASS draws the same bitmap twice — issue #204's root cause.
        assertNotEquals(spec.foregroundImage, spec.bearingImage)
    }

    @Test fun triangle_has_stale_names_present_for_both_layers() {
        val spec = selfPuckSpecFor(SELF_MARKER_STYLE_TRIANGLE)

        assertNotNull(spec.foregroundStaleImage)
        assertNotNull(spec.bearingStaleImage)
        assertNotEquals(spec.foregroundStaleImage, spec.bearingStaleImage)
        // Stale variants must differ from their live counterparts too, or
        // "dimming" would be a visual no-op.
        assertNotEquals(spec.foregroundImage, spec.foregroundStaleImage)
        assertNotEquals(spec.bearingImage, spec.bearingStaleImage)
    }

    @Test fun no_style_ever_points_foreground_and_bearing_at_the_same_image() {
        // Regression guard for #204's exact bug, across every style
        // (including anything unrecognized, which falls back to legacy):
        // whenever a style DOES define a bearing image, it must not be the
        // identical string as the foreground image.
        for (style in listOf(SELF_MARKER_STYLE_MIL_STD, SELF_MARKER_STYLE_TRIANGLE, SELF_MARKER_STYLE_LEGACY, "unrecognized-style")) {
            val spec = selfPuckSpecFor(style)
            val bearing = spec.bearingImage
            if (bearing != null) {
                assertNotEquals(
                    "style '$style' must not point foreground and bearing at the same image",
                    spec.foregroundImage,
                    bearing,
                )
            }
        }
    }

    @Test fun legacy_and_unrecognized_styles_fall_back_to_compass_with_no_named_images() {
        // Legacy disc renders via foregroundDrawable/bearingDrawable resource
        // ids (see buildPuckOptions' else-branch), not named style images —
        // it is unchanged by #204, so every named-image field here is null
        // and only renderMode applies.
        for (style in listOf(SELF_MARKER_STYLE_LEGACY, "", "unrecognized-style")) {
            val spec = selfPuckSpecFor(style)
            assertEquals(RenderMode.COMPASS, spec.renderMode)
            assertNull(spec.foregroundImage)
            assertNull(spec.bearingImage)
            assertNull(spec.foregroundStaleImage)
            assertNull(spec.bearingStaleImage)
        }
    }

    @Test fun selfMarkerStyleFor_precedence_triangle_overrides_milstd_overrides_legacy() {
        assertEquals(SELF_MARKER_STYLE_TRIANGLE, selfMarkerStyleFor(useMilStdSelfSymbol = true, selfMarkerTriangle = true))
        assertEquals(SELF_MARKER_STYLE_TRIANGLE, selfMarkerStyleFor(useMilStdSelfSymbol = false, selfMarkerTriangle = true))
        assertEquals(SELF_MARKER_STYLE_MIL_STD, selfMarkerStyleFor(useMilStdSelfSymbol = true, selfMarkerTriangle = false))
        assertEquals(SELF_MARKER_STYLE_LEGACY, selfMarkerStyleFor(useMilStdSelfSymbol = false, selfMarkerTriangle = false))
    }

    @Test fun every_registered_image_name_is_unique_across_the_whole_set() {
        // Sanity: no accidental string collisions between MIL-STD's and the
        // triangle's registered image names — they share one MapLibre Style
        // instance, so a collision would make one style silently overwrite
        // another's registered bitmap.
        val names = listOf(
            SELF_FOREGROUND_IMAGE, SELF_FOREGROUND_STALE_IMAGE,
            SELF_TRIANGLE_IMAGE, SELF_TRIANGLE_STALE_IMAGE,
            SELF_TRIANGLE_TRANSPARENT_IMAGE, SELF_TRIANGLE_TRANSPARENT_STALE_IMAGE,
        )
        assertEquals(names.size, names.toSet().size)
    }
}
