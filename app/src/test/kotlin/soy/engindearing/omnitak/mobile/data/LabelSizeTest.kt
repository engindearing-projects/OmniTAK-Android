package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #213 - the rules behind the "Label size" setting: which sizes are offered, how
 * a stored value that is not one of them is read, and how the final size is
 * worked out from the design size, the phone's font size and the setting.
 */
class LabelSizeTest {

    private val eps = 0.001f

    // --- the choices ---------------------------------------------------------

    @Test fun the_choices_are_80_100_120_140_160() {
        assertEquals(listOf(80, 100, 120, 140, 160), LabelSize.CHOICES)
    }

    @Test fun the_default_is_100_which_is_one_of_the_choices() {
        assertEquals(100, LabelSize.DEFAULT_PERCENT)
        assertTrue(LabelSize.DEFAULT_PERCENT in LabelSize.CHOICES)
    }

    // --- reading a stored value ----------------------------------------------

    @Test fun every_choice_reads_as_itself() {
        for (c in LabelSize.CHOICES) assertEquals("choice $c", c, LabelSize.nearestChoice(c))
    }

    @Test fun a_value_between_two_choices_reads_as_the_nearer_one() {
        val expected = mapOf(
            81 to 80, 85 to 80, 89 to 80,
            91 to 100, 99 to 100, 101 to 100, 109 to 100,
            111 to 120, 119 to 120, 121 to 120, 129 to 120,
            131 to 140, 139 to 140, 141 to 140, 149 to 140,
            151 to 160, 159 to 160,
        )
        for ((stored, want) in expected) {
            assertEquals("stored $stored", want, LabelSize.nearestChoice(stored))
        }
    }

    @Test fun a_value_exactly_halfway_between_two_choices_rounds_up() {
        assertEquals(100, LabelSize.nearestChoice(90))
        assertEquals(120, LabelSize.nearestChoice(110))
        assertEquals(140, LabelSize.nearestChoice(130))
        assertEquals(160, LabelSize.nearestChoice(150))
    }

    @Test fun zero_and_negative_values_read_as_80_never_as_a_tiny_size() {
        for (stored in listOf(0, -1, -100, 1, 40, Int.MIN_VALUE)) {
            assertEquals("stored $stored", 80, LabelSize.nearestChoice(stored))
        }
    }

    @Test fun values_above_the_range_read_as_160_never_as_a_huge_size() {
        for (stored in listOf(161, 200, 1000, Int.MAX_VALUE)) {
            assertEquals("stored $stored", 160, LabelSize.nearestChoice(stored))
        }
    }

    @Test fun scaleOf_is_the_nearest_choice_as_a_multiplier() {
        assertEquals(0.8f, LabelSize.scaleOf(80), eps)
        assertEquals(1.0f, LabelSize.scaleOf(100), eps)
        assertEquals(1.6f, LabelSize.scaleOf(160), eps)
        assertEquals("invalid reads as its nearest choice", 0.8f, LabelSize.scaleOf(0), eps)
        assertEquals("invalid reads as its nearest choice", 1.6f, LabelSize.scaleOf(5000), eps)
    }

    // --- the size: design x phone font size x label size ---------------------

    @Test fun at_the_defaults_the_size_is_the_design_size() {
        assertEquals(30f, LabelSize.size(30f, 1.0f, 100), eps)
    }

    @Test fun the_label_size_alone_scales_the_design_size() {
        assertEquals(24f, LabelSize.size(30f, 1.0f, 80), eps)
        assertEquals(36f, LabelSize.size(30f, 1.0f, 120), eps)
        assertEquals(48f, LabelSize.size(30f, 1.0f, 160), eps)
    }

    @Test fun the_phone_font_size_alone_scales_the_design_size() {
        assertEquals(45f, LabelSize.size(30f, 1.5f, 100), eps)
        assertEquals(60f, LabelSize.size(30f, 2.0f, 100), eps)
        assertEquals(25.5f, LabelSize.size(30f, 0.85f, 100), eps)
    }

    @Test fun the_phone_font_size_and_the_label_size_multiply() {
        assertEquals(96f, LabelSize.size(30f, 2.0f, 160), eps)
        assertEquals(46.8f, LabelSize.size(30f, 1.3f, 120), eps)
        assertEquals(20.4f, LabelSize.size(30f, 0.85f, 80), eps)
    }

    @Test fun an_invalid_label_size_is_read_as_its_nearest_choice_before_it_scales() {
        assertEquals(24f, LabelSize.size(30f, 1.0f, -7), eps)
        assertEquals(48f, LabelSize.size(30f, 1.0f, 9999), eps)
    }

    @Test fun a_phone_font_size_that_is_not_positive_counts_as_one() {
        for (bad in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals("font scale $bad", 48f, LabelSize.size(30f, bad, 160), eps)
        }
    }

    @Test fun factor_is_the_size_of_a_design_size_of_one() {
        assertEquals(1.0f, LabelSize.factor(1.0f, 100), eps)
        assertEquals(1.6f, LabelSize.factor(1.0f, 160), eps)
        assertEquals(3.2f, LabelSize.factor(2.0f, 160), eps)
        assertEquals(LabelSize.size(1f, 1.3f, 140), LabelSize.factor(1.3f, 140), eps)
    }
}
