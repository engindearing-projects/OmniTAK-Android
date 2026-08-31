package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `PositionConfig.position_broadcast_secs` is field **1**, not field 4.
 *
 * Field 4 is the deprecated `gps_enabled` bool. Writing a seconds value there
 * sets a boolean and leaves the broadcast interval untouched, so the operator
 * changes the PLI cadence and nothing happens. iOS has always used field 1;
 * Android's serializer and parser both used 4, which is why the two platforms
 * disagreed. Canonical config.proto:
 *
 *   uint32 position_broadcast_secs = 1;
 *   bool   position_broadcast_smart_enabled = 2;
 *   bool   fixed_position = 3;
 *   bool   gps_enabled = 4;
 */
class PositionBroadcastFieldTest {

    private val myNodeNum = 0x12345678u

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private fun ByteArray.containsBytes(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (start in 0..size - needle.size) {
            for (i in needle.indices) if (this[start + i] != needle[i]) continue@outer
            return true
        }
        return false
    }

    private fun hexBytes(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `position broadcast interval is written to field 1`() {
        val frame = AdminMessageSerializer.buildSetPositionBroadcastSecs(myNodeNum, 900)

        // set_config=34 (tag 92 02) { position=2 (tag 12) { field 1 varint 900 = 08 84 07 } }
        val expected = hexBytes("9202051203088407")

        assertTrue(
            "position_broadcast_secs must be field 1; got ${frame.hex()}",
            frame.containsBytes(expected),
        )
    }

    @Test
    fun `nothing is written to deprecated gps_enabled at field 4`() {
        val frame = AdminMessageSerializer.buildSetPositionBroadcastSecs(myNodeNum, 900)

        // The same envelope but with the inner tag 0x20 (field 4, varint).
        val deprecated = hexBytes("9202051203208407")

        assertFalse(
            "writing seconds into gps_enabled (field 4) silently does nothing; got ${frame.hex()}",
            frame.containsBytes(deprecated),
        )
    }
}
