package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeshOwnerNamesTest {

    @Test fun callsign_with_hyphen_strips_punct_for_short() {
        assertEquals("ENGIE-1" to "ENG1", MeshOwnerNames.derive("ENGIE-1"))
    }

    @Test fun lowercase_callsign_uppercases_short_but_preserves_long() {
        assertEquals("ops" to "OPS", MeshOwnerNames.derive("ops"))
    }

    @Test fun longer_callsign_preserves_2_letters_plus_2_digits() {
        assertEquals("Alphabravo-12" to "AL12", MeshOwnerNames.derive("Alphabravo-12"))
    }

    @Test fun all_letter_callsign_falls_back_to_first_4_chars() {
        assertEquals("OMNI" to "OMNI", MeshOwnerNames.derive("OMNI"))
    }

    @Test fun blank_and_punct_only_callsigns_return_null() {
        assertNull(MeshOwnerNames.derive(""))
        assertNull(MeshOwnerNames.derive("   "))
        assertNull(MeshOwnerNames.derive("---"))
    }
}
