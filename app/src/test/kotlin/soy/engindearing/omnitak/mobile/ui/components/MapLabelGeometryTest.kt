package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.LabelSize
import soy.engindearing.omnitak.mobile.data.UserPrefs

/**
 * #213 - how big the name baked into a marker bitmap is drawn, and the one rule
 * that keeps a larger name off its icon: every dimension of the name (text,
 * outline, strip, gap) follows the same factor. The icon is not in the geometry
 * and keeps its size.
 */
class MapLabelGeometryTest {

    private val eps = 0.001f

    // Every factor the setting can produce: label size x a range of phone font sizes.
    private val factors: List<Float> =
        LabelSize.CHOICES.flatMap { pct ->
            listOf(0.85f, 1.0f, 1.3f, 1.5f, 2.0f).map { fs -> LabelSize.factor(fs, pct) }
        }

    @Test fun at_a_factor_of_one_a_contact_label_has_the_sizes_the_app_always_drew() {
        val g = MapLabelGeometry.forContact(1f)
        assertEquals(30f, g.textSize, eps)
        assertEquals(6f, g.haloStroke, eps)
        assertEquals(12f, g.padding, eps)
        assertEquals(40f, g.band, eps)
        assertEquals(30f, g.baseline, eps)
        assertEquals("no padding at the default size", 0, g.topPad)
        assertEquals("no padding at the default size", 0, g.bottomPad)
    }

    @Test fun at_a_factor_of_one_a_kml_label_has_the_sizes_the_app_always_drew() {
        val g = MapLabelGeometry.forKmlPin(1f)
        assertEquals(30f, g.textSize, eps)
        assertEquals(6f, g.haloStroke, eps)
        assertEquals(12f, g.padding, eps)
        assertEquals(46f, g.band, eps)
        assertEquals(34f, g.baseline, eps)
        assertEquals("no padding at the default size", 0, g.topPad)
        assertEquals("no padding at the default size", 0, g.bottomPad)
    }

    @Test fun the_default_setting_at_the_default_phone_font_size_changes_nothing() {
        assertEquals(1f, LabelSize.factor(1f, UserPrefs().labelScalePercent), eps)
    }

    @Test fun at_160_percent_the_contact_text_is_48_and_its_strip_is_64() {
        val g = MapLabelGeometry.forContact(LabelSize.factor(1f, 160))
        assertEquals(48f, g.textSize, eps)
        assertEquals(64f, g.band, eps)
        assertEquals(48f, g.baseline, eps)
    }

    @Test fun every_dimension_of_a_contact_label_follows_the_factor() {
        val one = MapLabelGeometry.forContact(1f)
        for (k in factors) {
            val g = MapLabelGeometry.forContact(k)
            assertEquals("text size at $k", one.textSize * k, g.textSize, eps)
            assertEquals("outline at $k", one.haloStroke * k, g.haloStroke, eps)
            assertEquals("padding at $k", one.padding * k, g.padding, eps)
            assertEquals("strip height at $k", one.band * k, g.band, eps)
            assertEquals("baseline at $k", one.baseline * k, g.baseline, eps)
        }
    }

    @Test fun every_dimension_of_a_kml_label_follows_the_factor() {
        val one = MapLabelGeometry.forKmlPin(1f)
        for (k in factors) {
            val g = MapLabelGeometry.forKmlPin(k)
            assertEquals("text size at $k", one.textSize * k, g.textSize, eps)
            assertEquals("outline at $k", one.haloStroke * k, g.haloStroke, eps)
            assertEquals("padding at $k", one.padding * k, g.padding, eps)
            assertEquals("strip height at $k", one.band * k, g.band, eps)
            assertEquals("baseline at $k", one.baseline * k, g.baseline, eps)
        }
    }

    @Test fun the_text_baseline_stays_inside_its_strip_with_room_for_the_outline() {
        // If the strip did not grow with the text, the baseline would fall below
        // it and the name would be drawn over the icon.
        for (k in factors) {
            for (g in listOf(MapLabelGeometry.forContact(k), MapLabelGeometry.forKmlPin(k))) {
                assertTrue(
                    "baseline ${g.baseline} + half the outline ${g.haloStroke / 2} must fit in the strip ${g.band} at $k",
                    g.baseline + g.haloStroke / 2 <= g.band,
                )
            }
        }
    }

    @Test fun a_larger_label_size_gives_a_larger_name() {
        val sizes = LabelSize.CHOICES.map { MapLabelGeometry.forContact(LabelSize.factor(1f, it)).textSize }
        assertEquals("one size per choice, smallest first", sizes.sorted(), sizes)
        assertEquals("all different", sizes.size, sizes.toSet().size)
    }

    // --- the icon must not move when the name does -------------------------
    // MapLibre anchors a marker bitmap at its centre, so the distance from the bitmap's
    // centre to the icon is the distance from the icon to the point it marks. A name that
    // grew or shrank must leave that distance exactly as it is at the default size.

    /** Contact pin: dot centre minus bitmap centre, in px (dot is 38 px across, strip above). */
    private fun contactDotOffset(g: MapLabelGeometry): Float {
        val height = g.topPad + g.band + 38f + g.bottomPad
        val dotCentre = g.topPad + g.band + 19f
        return dotCentre - height / 2f
    }

    /** KML pin: pushpin centre minus bitmap centre, in px (strip below the pushpin). */
    private fun kmlPinOffset(g: MapLabelGeometry, pinHeight: Float): Float {
        val height = g.topPad + pinHeight + g.band + g.bottomPad
        val pinCentre = g.topPad + pinHeight / 2f
        return pinCentre - height / 2f
    }

    @Test fun the_contact_dot_stays_where_it_is_at_every_label_size() {
        val atDefault = contactDotOffset(MapLabelGeometry.forContact(1f))
        assertEquals("today the dot is 20 px below its coordinate", 20f, atDefault, eps)
        for (k in factors) {
            assertEquals(
                "dot offset at factor $k",
                atDefault, contactDotOffset(MapLabelGeometry.forContact(k)), 1f, // +-1 px: whole-pixel padding
            )
        }
    }

    @Test fun the_kml_pushpin_stays_where_it_is_at_every_label_size() {
        for (pinHeight in listOf(48f, 64f, 90f)) {
            val atDefault = kmlPinOffset(MapLabelGeometry.forKmlPin(1f), pinHeight)
            for (k in factors) {
                assertEquals(
                    "pushpin offset at factor $k, pin $pinHeight px",
                    atDefault, kmlPinOffset(MapLabelGeometry.forKmlPin(k), pinHeight), 1f,
                )
            }
        }
    }

    @Test fun padding_goes_on_the_side_away_from_the_name() {
        // Contact: name above the dot. Larger name -> pad below; smaller -> pad above.
        val big = MapLabelGeometry.forContact(1.6f)
        assertEquals(0, big.topPad); assertEquals(24, big.bottomPad)
        val small = MapLabelGeometry.forContact(0.8f)
        assertEquals(8, small.topPad); assertEquals(0, small.bottomPad)
        // KML: name below the pushpin. Larger name -> pad above; smaller -> pad below.
        val bigKml = MapLabelGeometry.forKmlPin(1.6f)
        assertEquals(28, bigKml.topPad); assertEquals(0, bigKml.bottomPad)
        val smallKml = MapLabelGeometry.forKmlPin(0.8f)
        assertEquals(0, smallKml.topPad); assertEquals(9, smallKml.bottomPad)
    }

    // --- the bitmap caches must not hand back a pin drawn at another size ----

    @Test fun a_contact_pin_cache_key_differs_when_only_the_label_size_differs() {
        val small = ContactMarkerRenderer.cacheKey(0xFF4ADE80.toInt(), "ALPHA-1", labelFactor = 0.8f)
        val normal = ContactMarkerRenderer.cacheKey(0xFF4ADE80.toInt(), "ALPHA-1", labelFactor = 1.0f)
        val large = ContactMarkerRenderer.cacheKey(0xFF4ADE80.toInt(), "ALPHA-1", labelFactor = 1.6f)
        assertEquals("three sizes, three keys", 3, setOf(small, normal, large).size)
    }

    @Test fun a_contact_pin_cache_key_is_stable_for_the_same_size() {
        assertEquals(
            ContactMarkerRenderer.cacheKey(0xFF4ADE80.toInt(), "ALPHA-1", "3m", 0.6f, 1.4f),
            ContactMarkerRenderer.cacheKey(0xFF4ADE80.toInt(), "ALPHA-1", "3m", 0.6f, 1.4f),
        )
    }

    @Test fun a_contact_pin_cache_key_without_a_size_means_the_default_size() {
        assertEquals(
            ContactMarkerRenderer.cacheKey(0xFF4ADE80.toInt(), "ALPHA-1"),
            ContactMarkerRenderer.cacheKey(0xFF4ADE80.toInt(), "ALPHA-1", labelFactor = 1.0f),
        )
    }

    @Test fun a_contact_pin_cache_key_still_differs_by_age_so_the_point_age_label_updates() {
        assertNotEquals(
            ContactMarkerRenderer.cacheKey(0xFF4ADE80.toInt(), "ALPHA-1", "3m", 1f, 1.2f),
            ContactMarkerRenderer.cacheKey(0xFF4ADE80.toInt(), "ALPHA-1", "4m", 1f, 1.2f),
        )
    }

    @Test fun a_kml_pin_cache_key_differs_when_only_the_label_size_differs() {
        val small = KmlMarkerRenderer.labelKey("Objective", 0.8f)
        val normal = KmlMarkerRenderer.labelKey("Objective", 1.0f)
        val large = KmlMarkerRenderer.labelKey("Objective", 1.6f)
        assertEquals("three sizes, three keys", 3, setOf(small, normal, large).size)
    }

    @Test fun a_kml_pin_cache_key_differs_by_name_and_is_stable() {
        assertNotEquals(KmlMarkerRenderer.labelKey("A", 1f), KmlMarkerRenderer.labelKey("B", 1f))
        assertEquals(KmlMarkerRenderer.labelKey("A", 1.2f), KmlMarkerRenderer.labelKey("A", 1.2f))
    }
}
