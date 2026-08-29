package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `set_owner` names are clamped in **bytes**, not UTF-16 units.
 *
 * The firmware stores `long_name` in a nanopb `char[40]` and `short_name` in a
 * `char[5]` — 39 and 4 usable bytes. `String.take(39)` counts UTF-16 code
 * units, so 39 Chinese characters is ~117 bytes; nanopb rejects the field and
 * drops the whole AdminMessage, and the rename silently does nothing. That
 * lands on the zh-Hant users first.
 *
 * Clamping must also land on a character boundary — half a multi-byte
 * character is invalid UTF-8 and nanopb rejects that too.
 */
class OwnerNameClampTest {

    private val myNodeNum = 0x12345678u

    /** UTF-8 byte length of the length-delimited string at [field] inside the
     *  User submessage of a set_owner frame. */
    private fun ownerFieldBytes(frame: ByteArray, field: Int): ByteArray? {
        // AdminMessage.set_owner = field 32, wire 2 → tag varint 0x102 = "82 02".
        var i = 0
        while (i + 1 < frame.size) {
            if ((frame[i].toInt() and 0xFF) == 0x82 && (frame[i + 1].toInt() and 0xFF) == 0x02) {
                var j = i + 2
                var len = 0; var shift = 0
                while (j < frame.size) {
                    val b = frame[j].toInt() and 0xFF; j++
                    len = len or ((b and 0x7F) shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                }
                val owner = frame.copyOfRange(j, minOf(j + len, frame.size))
                var k = 0
                while (k < owner.size) {
                    val tag = owner[k].toInt() and 0xFF
                    val f = tag shr 3
                    k++
                    var l = 0; var sh = 0
                    while (k < owner.size) {
                        val b = owner[k].toInt() and 0xFF; k++
                        l = l or ((b and 0x7F) shl sh)
                        if (b and 0x80 == 0) break
                        sh += 7
                    }
                    if ((tag and 0x07) != 2) return null
                    val value = owner.copyOfRange(k, minOf(k + l, owner.size))
                    if (f == field) return value
                    k += l
                }
                return null
            }
            i++
        }
        return null
    }

    @Test
    fun `a long CJK name is clamped to 39 bytes, not 39 characters`() {
        val cjk = "台".repeat(39) // 39 chars, 117 UTF-8 bytes
        val frame = AdminMessageSerializer.buildSetOwner(myNodeNum, cjk, "TW")

        val encoded = ownerFieldBytes(frame, field = 2)
        requireNotNull(encoded) { "long_name not found in frame" }

        assertTrue(
            "long_name must fit the firmware's 39 usable bytes; got ${encoded.size}",
            encoded.size <= 39,
        )
    }

    @Test
    fun `clamping lands on a character boundary`() {
        val cjk = "台".repeat(39)
        val frame = AdminMessageSerializer.buildSetOwner(myNodeNum, cjk, "TW")
        val encoded = ownerFieldBytes(frame, field = 2)!!

        // 3 bytes each — 13 whole characters is exactly 39 bytes.
        assertEquals("台".repeat(13), String(encoded, Charsets.UTF_8))
        assertEquals(
            "a mid-character cut is invalid UTF-8 and nanopb drops the message",
            encoded.toList(),
            String(encoded, Charsets.UTF_8).toByteArray(Charsets.UTF_8).toList(),
        )
    }

    @Test
    fun `an emoji short name is not split across its surrogate pair`() {
        val frame = AdminMessageSerializer.buildSetOwner(myNodeNum, "Drone Team", "🛩️X")
        val encoded = ownerFieldBytes(frame, field = 3)!!

        assertTrue("short_name must fit 4 bytes; got ${encoded.size}", encoded.size <= 4)
        assertEquals(
            encoded.toList(),
            String(encoded, Charsets.UTF_8).toByteArray(Charsets.UTF_8).toList(),
        )
    }

    @Test
    fun `ascii names are unchanged`() {
        val frame = AdminMessageSerializer.buildSetOwner(myNodeNum, "Pato Golf", "PATO")
        assertEquals("Pato Golf", String(ownerFieldBytes(frame, 2)!!, Charsets.UTF_8))
        assertEquals("PATO", String(ownerFieldBytes(frame, 3)!!, Charsets.UTF_8))
    }
}
