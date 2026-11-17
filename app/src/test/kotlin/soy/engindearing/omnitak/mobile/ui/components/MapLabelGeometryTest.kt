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
        assertEquals("nothing above the name", 0, g.topPad)
        assertEquals("a strip as tall as the name strip under the dot", 40, g.bottomPad)
    }

    @Test fun at_a_factor_of_one_a_kml_label_has_the_sizes_the_app_always_drew() {
        val g = MapLabelGeometry.forKmlPin(1f)
        assertEquals(30f, g.textSize, eps)
        assertEquals(6f, g.haloStroke, eps)
        assertEquals(12f, g.padding, eps)
        assertEquals(46f, g.band, eps)
        assertEquals(34f, g.baseline, eps)
        assertEquals("nothing above the pushpin", 0, g.topPad)
        assertEquals("rows under the strip that put the tip at the centre", 42, g.bottomPad)
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

    // --- the point that marks the coordinate is the bitmap's centre (#273) -------
    // MapLibre anchors a marker bitmap at its centre. So a contact's dot centre and a
    // pushpin's tip must be exactly the bitmap's vertical centre, at every label size and
    // with no name. (Before this a contact's dot was drawn about 20 px below its coordinate.)

    @Test fun the_contact_dot_centre_is_the_bitmap_centre_at_every_label_size() {
        for (k in factors) {
            val layout = MapLabelGeometry.forContact(k).contactLayout(hasName = true)
            assertEquals("the height at factor $k is even, so its centre is a pixel line", 0, layout.height % 2)
            assertEquals("dot centre at factor $k", layout.height / 2, layout.dotCentreY)
        }
    }

    @Test fun the_contact_dot_centre_is_the_bitmap_centre_with_no_name() {
        for (k in factors) {
            val layout = MapLabelGeometry.forContact(k).contactLayout(hasName = false)
            assertEquals("a bitmap of the dot alone, at factor $k", 38, layout.height)
            assertEquals(19, layout.dotCentreY)
            assertEquals(0, layout.stripHeight)
            assertEquals(0, layout.nameTop)
        }
    }

    @Test fun a_contact_pin_has_a_transparent_strip_below_the_dot_as_tall_as_the_name_strip() {
        for (k in factors) {
            val g = MapLabelGeometry.forContact(k)
            assertEquals("strip below the dot at factor $k", g.bandPx, g.bottomPad)
            assertEquals("nothing above the name at factor $k", 0, g.topPad)
        }
    }

    @Test fun a_contact_pin_at_the_default_size_is_118_px_tall_with_the_dot_at_59() {
        val layout = MapLabelGeometry.forContact(1f).contactLayout(hasName = true)
        assertEquals(40, layout.stripHeight)
        assertEquals(118, layout.height)
        assertEquals(59, layout.dotCentreY)
    }

    @Test fun a_contact_layout_counts_the_padding_it_is_given_above_and_below() {
        // forContact never asks for padding above (the name is above the dot, so the padding
        // is always below), but the arithmetic must not depend on that: a geometry with 6 rows
        // above and 2 below has the name 6 down, the dot 6 + 40 + 19 down and 6 + 40 + 38 + 2 rows.
        val g = MapLabelGeometry(
            textSize = 30f, haloStroke = 6f, padding = 12f, band = 40f, baseline = 30f,
            topPad = 6, bottomPad = 2,
        )
        val named = g.contactLayout(hasName = true)
        assertEquals(86, named.height)
        assertEquals(65, named.dotCentreY)
        assertEquals(6, named.nameTop)
        assertEquals(40, named.stripHeight)
        // with no name there is no strip and no padding, whatever the geometry says
        val unnamed = g.contactLayout(hasName = false)
        assertEquals(38, unnamed.height)
        assertEquals(19, unnamed.dotCentreY)
    }

    @Test fun the_pushpin_tip_is_the_bitmap_centre_at_every_label_size() {
        for (k in factors) {
            val g = MapLabelGeometry.forKmlPin(k)
            val height = g.topPad + PUSHPIN_HEIGHT + g.bandPx + g.bottomPad
            assertEquals("the height at factor $k is even", 0, height % 2)
            assertEquals("tip at factor $k", height / 2, g.topPad + PUSHPIN_TIP_Y)
        }
    }

    @Test fun the_bare_pushpin_tip_is_the_bitmap_centre() {
        val pads = MapLabelGeometry.forBareKmlPin()
        val height = pads.top + PUSHPIN_HEIGHT + pads.bottom
        assertEquals(height / 2, pads.top + PUSHPIN_TIP_Y)
        assertEquals("a 96 px picture with its tip 92 down needs 88 rows underneath", 88, pads.bottom)
        assertEquals(0, pads.top)
    }

    @Test fun the_pushpin_picture_is_96_px_tall_with_its_tip_4_px_above_the_bottom() {
        assertEquals(96, PUSHPIN_HEIGHT)
        assertEquals(92, PUSHPIN_TIP_Y)
        assertEquals(64, PUSHPIN_WIDTH)
    }

    @Test fun a_kml_pin_takes_its_padding_below_when_the_name_is_short_and_above_when_it_is_tall() {
        val short = MapLabelGeometry.forKmlPin(1f)           // strip 46: less than the 88 the tip needs
        assertEquals(0, short.topPad); assertEquals(42, short.bottomPad)
        val tall = MapLabelGeometry.forKmlPin(3.2f)          // strip 147: more than 88
        assertEquals(59, tall.topPad); assertEquals(0, tall.bottomPad)
    }

    @Test fun padsToCentre_puts_the_anchor_at_the_centre_for_any_shape() {
        for (height in 1..200) {
            for (anchor in 0..height) {
                val p = MapLabelGeometry.padsToCentre(height, anchor)
                assertEquals(
                    "height $height anchor $anchor: the anchor is at the middle of the padded bitmap",
                    p.top + height + p.bottom, 2 * (p.top + anchor),
                )
                assertTrue("only one side is padded", p.top == 0 || p.bottom == 0)
                assertTrue(p.top >= 0 && p.bottom >= 0)
            }
        }
    }

    @Test fun padsToCentre_adds_nothing_when_the_anchor_is_already_central() {
        assertEquals(MapLabelGeometry.Pads(0, 0), MapLabelGeometry.padsToCentre(38, 19))
        assertEquals(MapLabelGeometry.Pads(0, 0), MapLabelGeometry.padsToCentre(100, 50))
    }

    @Test fun padsToCentre_pads_underneath_for_a_low_anchor_and_on_top_for_a_high_one() {
        assertEquals(MapLabelGeometry.Pads(0, 40), MapLabelGeometry.padsToCentre(78, 59))   // contact: dot low in the bitmap
        assertEquals(MapLabelGeometry.Pads(30, 0), MapLabelGeometry.padsToCentre(100, 35))  // anchor above the middle
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
