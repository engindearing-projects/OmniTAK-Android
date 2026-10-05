package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * [ProtoFields]: read a message as top-level fields, edit some, write it back
 * with everything else untouched.
 *
 * Byte layouts here are written out by hand from the protobuf encoding rules
 * (tag = number << 3 | wire type, varints are base-128 little-endian), not
 * produced by the code under test.
 */
class ProtoFieldsTest {

    private fun hex(s: String): ByteArray =
        s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.hex(): String = joinToString(" ") { "%02x".format(it) }

    private fun assertBytes(expected: String, actual: ByteArray?) {
        assertNotNull("expected bytes, got null", actual)
        assertEquals(expected.replace(" ", "").lowercase(), actual!!.joinToString("") { "%02x".format(it) })
    }

    // 1 varint 150, 2 "testing", 3 fixed32 0x01020304, 4 fixed64 0x0807060504030201,
    // 1000 length-delimited 03 aa bb cc (a number the app has never heard of),
    // 536870911 (the highest field number) varint 1.
    private val everyWireType = hex(
        "08 96 01" +
            "12 07 74 65 73 74 69 6e 67" +
            "1d 04 03 02 01" +
            "21 01 02 03 04 05 06 07 08" +
            "c2 3e 03 aa bb cc" +
            "f8 ff ff ff 0f 01",
    )

    // region parse / serialize ------------------------------------------------

    @Test fun parse_reads_every_wire_type_and_serialize_gives_the_same_bytes() {
        val fields = ProtoFields.parse(everyWireType)!!

        assertEquals(listOf(1, 2, 3, 4, 1000, 536870911), fields.map { it.number })
        assertEquals(
            listOf(
                ProtoFields.WIRE_VARINT, ProtoFields.WIRE_LEN, ProtoFields.WIRE_FIXED32,
                ProtoFields.WIRE_FIXED64, ProtoFields.WIRE_LEN, ProtoFields.WIRE_VARINT,
            ),
            fields.map { it.wireType },
        )
        assertEquals(150uL, fields[0].varint())
        assertEquals("testing", fields[1].bytes()!!.toString(Charsets.UTF_8))
        assertArrayEquals(hex("aa bb cc"), fields[4].bytes())
        assertEquals(1uL, fields[5].varint())
        assertNull("a varint is not length-delimited", fields[0].bytes())
        assertNull("a string is not a varint", fields[1].varint())

        assertArrayEquals(everyWireType, ProtoFields.serialize(fields))
    }

    @Test fun every_field_keeps_its_own_bytes_including_the_tag() {
        val fields = ProtoFields.parse(everyWireType)!!
        assertBytes("08 96 01", fields[0].raw)
        assertBytes("12 07 74 65 73 74 69 6e 67", fields[1].raw)
        assertBytes("1d 04 03 02 01", fields[2].raw)
        assertBytes("21 01 02 03 04 05 06 07 08", fields[3].raw)
        assertBytes("c2 3e 03 aa bb cc", fields[4].raw)
        assertBytes("f8 ff ff ff 0f 01", fields[5].raw)
    }

    @Test fun a_tag_written_with_a_padded_varint_comes_back_unchanged() {
        // Field 1, varint, tag 0x08 written as 88 00. Legal on the wire; a re-encoder would shorten it.
        val padded = hex("88 00 05")
        val fields = ProtoFields.parse(padded)!!
        assertEquals(1, fields.single().number)
        assertEquals(5uL, fields.single().varint())
        assertArrayEquals(padded, ProtoFields.serialize(fields))
    }

    @Test fun an_empty_message_has_no_fields() {
        assertEquals(emptyList<ProtoField>(), ProtoFields.parse(ByteArray(0)))
        assertEquals(0, ProtoFields.serialize(emptyList()).size)
    }

    @Test fun an_empty_length_delimited_field_is_a_field_with_no_bytes() {
        val fields = ProtoFields.parse(hex("0a 00"))!!
        assertEquals(1, fields.single().number)
        assertEquals(0, fields.single().bytes()!!.size)
    }

    @Test fun lookups_take_the_last_occurrence_of_a_repeated_scalar() {
        val fields = ProtoFields.parse(hex("08 01 12 02 61 62 08 07 12 01 63"))!!
        assertEquals(7uL, ProtoFields.lastVarint(fields, 1))
        assertEquals("c", ProtoFields.lastString(fields, 2))
        assertNull(ProtoFields.lastVarint(fields, 9))
        assertNull("field 2 is not a varint", ProtoFields.lastVarint(fields, 2))
        assertNull("field 1 is not length-delimited", ProtoFields.lastBytes(fields, 1))
    }

    // endregion

    // region replace / insert / clear -----------------------------------------

    @Test fun replace_swaps_one_field_and_leaves_every_other_byte_alone() {
        val patched = ProtoFields.patch(everyWireType, mapOf(1 to ProtoFields.varint(1, 7uL)))

        // Field 1 is now 08 07; the rest, including the unknown 1000 and 536870911, is as it was.
        assertBytes(
            "08 07" +
                "12 07 74 65 73 74 69 6e 67" +
                "1d 04 03 02 01" +
                "21 01 02 03 04 05 06 07 08" +
                "c2 3e 03 aa bb cc" +
                "f8 ff ff ff 0f 01",
            patched,
        )
    }

    @Test fun replace_can_change_the_length_of_a_field() {
        val patched = ProtoFields.patch(everyWireType, mapOf(2 to ProtoFields.string(2, "hi")))!!
        val fields = ProtoFields.parse(patched)!!
        assertEquals("hi", ProtoFields.lastString(fields, 2))
        assertEquals(listOf(1, 2, 3, 4, 1000, 536870911), fields.map { it.number })
        assertEquals(150uL, ProtoFields.lastVarint(fields, 1))
    }

    @Test fun replace_takes_the_place_of_the_first_occurrence_and_drops_the_repeats() {
        // 1=1, 2="a", 1=2, 3=3: field 1 appears twice.
        val original = hex("08 01 12 01 61 08 02 18 03")
        val patched = ProtoFields.patch(original, mapOf(1 to ProtoFields.varint(1, 9uL)))
        assertBytes("08 09 12 01 61 18 03", patched)
    }

    @Test fun replace_works_whatever_wire_type_the_old_field_had() {
        // Field 3 was a fixed32; the edit makes it a varint.
        val patched = ProtoFields.patch(hex("1d 04 03 02 01"), mapOf(3 to ProtoFields.varint(3, 1uL)))
        assertBytes("18 01", patched)
    }

    @Test fun insert_puts_a_new_field_before_the_first_higher_number() {
        // Has fields 1 and 7; field 6 goes between them, as the firmware itself would write it.
        val patched = ProtoFields.patch(hex("08 01 38 07"), mapOf(6 to ProtoFields.varint(6, 3uL)))
        assertBytes("08 01 30 03 38 07", patched)
    }

    @Test fun insert_goes_last_when_nothing_has_a_higher_number() {
        val patched = ProtoFields.patch(hex("08 01"), mapOf(2 to ProtoFields.varint(2, 5uL)))
        assertBytes("08 01 10 05", patched)
    }

    @Test fun insert_into_an_empty_message() {
        assertBytes("08 01", ProtoFields.patch(ByteArray(0), mapOf(1 to ProtoFields.varint(1, 1uL))))
    }

    @Test fun several_edits_apply_together() {
        val original = hex("08 01 12 01 61 18 03")
        val patched = ProtoFields.patch(
            original,
            mapOf(
                1 to ProtoFields.varint(1, 2uL), // replace
                2 to null, // clear
                4 to ProtoFields.varint(4, 4uL), // insert
            ),
        )
        assertBytes("08 02 18 03 20 04", patched)
    }

    @Test fun clear_removes_every_occurrence() {
        val original = hex("08 01 12 01 61 08 02 18 03")
        assertBytes("12 01 61 18 03", ProtoFields.patch(original, mapOf(1 to null)))
    }

    @Test fun clearing_a_field_that_is_not_there_changes_nothing() {
        assertArrayEquals(everyWireType, ProtoFields.patch(everyWireType, mapOf(9 to null)))
    }

    @Test fun an_edit_that_is_not_one_field_with_the_matching_number_is_a_bug_and_throws() {
        try {
            ProtoFields.patch(ByteArray(0), mapOf(1 to ProtoFields.varint(2, 1uL)))
            fail("a field 2 edit filed under field 1 must be rejected")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
        try {
            ProtoFields.patch(ByteArray(0), mapOf(1 to hex("08 01 10 02")))
            fail("two fields filed under one edit must be rejected")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    // endregion

    // region nested -----------------------------------------------------------

    // Channel { settings (2) { psk (2), name (3), uplink (5) }, role (3) }. Names and key are made up.
    private val settings = hex("12 04 de ad be ef") + hex("1a 05") + "Alpha".toByteArray() + hex("28 01")
    private val channel = hex("12") + byteArrayOf(settings.size.toByte()) + settings + hex("18 01")

    @Test fun nested_patch_changes_one_field_inside_the_sub_message_and_nothing_else() {
        val patched = ProtoFields.patchNested(channel, 2, mapOf(3 to ProtoFields.string(3, "Bravo")))!!

        val outer = ProtoFields.parse(patched)!!
        assertEquals("the channel keeps its role", 1uL, ProtoFields.lastVarint(outer, 3))
        val inner = ProtoFields.parse(ProtoFields.lastBytes(outer, 2)!!)!!
        assertEquals("Bravo", ProtoFields.lastString(inner, 3))
        assertArrayEquals("the key is untouched", hex("de ad be ef"), ProtoFields.lastBytes(inner, 2))
        assertEquals("uplink is untouched", 1uL, ProtoFields.lastVarint(inner, 5))
        assertEquals(listOf(2, 3, 5), inner.map { it.number })
    }

    @Test fun nested_patch_matches_a_hand_built_message_byte_for_byte() {
        val patched = ProtoFields.patchNested(channel, 2, mapOf(3 to ProtoFields.string(3, "Bravo")))
        val expectedSettings = hex("12 04 de ad be ef") + hex("1a 05") + "Bravo".toByteArray() + hex("28 01")
        val expected = hex("12") + byteArrayOf(expectedSettings.size.toByte()) + expectedSettings + hex("18 01")
        assertArrayEquals(expected, patched)
    }

    @Test fun nested_clear_leaves_the_sub_message_present_without_the_field() {
        val patched = ProtoFields.patchNested(channel, 2, mapOf(3 to null))!!
        val outer = ProtoFields.parse(patched)!!
        val inner = ProtoFields.parse(ProtoFields.lastBytes(outer, 2)!!)!!
        assertEquals(listOf(2, 5), inner.map { it.number })
    }

    @Test fun nested_patch_creates_a_missing_sub_message_when_an_edit_puts_something_in_it() {
        val onlyRole = hex("18 01")
        val patched = ProtoFields.patchNested(onlyRole, 2, mapOf(3 to ProtoFields.string(3, "Hi")))
        // settings { name "Hi" } goes in before field 3, in numeric order.
        assertBytes("12 04 1a 02 48 69 18 01", patched)
    }

    @Test fun nested_patch_with_nothing_to_put_in_a_missing_sub_message_changes_nothing() {
        val onlyRole = hex("18 01")
        assertArrayEquals(onlyRole, ProtoFields.patchNested(onlyRole, 2, mapOf(3 to null)))
    }

    @Test fun nested_patch_refuses_a_repeated_sub_message() {
        // settings appears twice; protobuf would merge them, this does not guess.
        val twice = hex("12 02 1a 00 12 02 1a 00")
        assertNull(ProtoFields.patchNested(twice, 2, mapOf(3 to ProtoFields.string(3, "x"))))
    }

    @Test fun nested_patch_refuses_a_field_that_is_not_length_delimited() {
        assertNull(ProtoFields.patchNested(hex("10 05"), 2, mapOf(3 to ProtoFields.string(3, "x"))))
    }

    @Test fun nested_patch_refuses_a_malformed_sub_message() {
        // settings says it is 5 bytes long and holds a length-delimited field that overruns it.
        assertNull(ProtoFields.patchNested(hex("12 03 1a 09 41"), 2, mapOf(3 to ProtoFields.string(3, "x"))))
    }

    // endregion

    // region malformed input --------------------------------------------------

    @Test fun truncated_and_overrunning_input_is_refused() {
        val bad = mapOf(
            "tag varint cut off" to hex("88"),
            "varint value cut off" to hex("08 96"),
            "length cut off" to hex("12"),
            "length-delimited shorter than its length" to hex("12 05 61 62"),
            "length runs far past the end" to hex("12 ff ff ff ff 0f 61"),
            "length larger than a 32-bit int" to hex("12 ff ff ff ff ff ff ff ff 7f 61"),
            "fixed32 cut off" to hex("1d 04 03 02"),
            "fixed64 cut off" to hex("21 01 02 03 04 05 06 07"),
            "second field truncated" to hex("08 01 12 03 61"),
        )
        for ((name, bytes) in bad) {
            assertNull("$name: ${bytes.hex()}", ProtoFields.parse(bytes))
        }
    }

    @Test fun field_zero_groups_and_unused_wire_types_are_refused() {
        assertNull("field 0", ProtoFields.parse(hex("00 01")))
        assertNull("start group (wire 3)", ProtoFields.parse(hex("0b")))
        assertNull("end group (wire 4)", ProtoFields.parse(hex("0c")))
        assertNull("wire type 6", ProtoFields.parse(hex("0e")))
        assertNull("wire type 7", ProtoFields.parse(hex("0f")))
    }

    @Test fun a_tag_that_does_not_fit_32_bits_is_refused() {
        assertNull(ProtoFields.parse(hex("80 80 80 80 10 01")))
    }

    @Test fun a_varint_longer_than_ten_bytes_is_refused() {
        assertNull(ProtoFields.parse(hex("08 80 80 80 80 80 80 80 80 80 80 01")))
    }

    @Test fun editing_a_malformed_message_gives_null_not_a_guess() {
        val bad = hex("08 01 12 09 61")
        assertNull(ProtoFields.patch(bad, mapOf(1 to ProtoFields.varint(1, 2uL))))
        assertNull(ProtoFields.patchNested(bad, 2, mapOf(3 to null)))
    }

    // endregion

    // region encoders ---------------------------------------------------------

    @Test fun encoders_write_the_standard_layouts() {
        assertBytes("08 96 01", ProtoFields.varint(1, 150uL))
        assertBytes("08 00", ProtoFields.varint(1, 0uL))
        assertBytes("10 01", ProtoFields.bool(2, true))
        assertBytes("10 00", ProtoFields.bool(2, false))
        assertBytes("1a 03 61 62 63", ProtoFields.string(3, "abc"))
        assertBytes("22 02 01 02", ProtoFields.bytes(4, byteArrayOf(1, 2)))
        assertBytes("2a 01 07", ProtoFields.message(5, byteArrayOf(7)))
        assertBytes("80 04 01", ProtoFields.bool(64, true)) // begin_edit_settings
        assertBytes("88 04 01", ProtoFields.bool(65, true)) // commit_edit_settings
    }

    @Test fun a_utf8_string_is_written_as_its_utf8_bytes() {
        assertBytes("12 03 e5 8f b0", ProtoFields.string(2, "台"))
    }

    @Test fun or_clear_encoders_return_null_for_the_proto3_default() {
        assertNull(ProtoFields.varintOrClear(1, 0uL))
        assertBytes("08 01", ProtoFields.varintOrClear(1, 1uL))
        assertNull(ProtoFields.boolOrClear(1, false))
        assertBytes("08 01", ProtoFields.boolOrClear(1, true))
        assertNull(ProtoFields.stringOrClear(1, ""))
        assertBytes("0a 01 61", ProtoFields.stringOrClear(1, "a"))
    }

    @Test fun a_value_that_became_the_default_goes_back_to_not_being_on_the_wire() {
        // role was 7; setting it to CLIENT (0) removes the field instead of writing an explicit 0.
        val patched = ProtoFields.patch(hex("08 07 30 02"), mapOf(1 to ProtoFields.varintOrClear(1, 0uL)))
        assertBytes("30 02", patched)
    }

    // endregion

    @Test fun toString_shows_sizes_and_never_the_bytes() {
        // A field can hold a channel key, and a failed assertion prints it.
        val field = ProtoFields.parse(hex("12 04 de ad be ef"))!!.single()
        assertEquals("ProtoField(number=2, wireType=2, size=6)", field.toString())
    }

    @Test fun fields_with_the_same_bytes_are_equal() {
        val a = ProtoFields.parse(everyWireType)!!
        val b = ProtoFields.parse(everyWireType.copyOf())!!
        assertEquals(a, b)
        assertEquals(a.map { it.hashCode() }, b.map { it.hashCode() })
    }
}
