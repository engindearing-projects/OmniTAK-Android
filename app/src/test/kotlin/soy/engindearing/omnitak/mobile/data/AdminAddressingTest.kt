package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * #185 — admin messages must be addressed to the local radio.
 *
 * PatoG's field report: settings applied in-app never take effect on the
 * radio. Every admin frame was wrapped with `to = 0xFFFFFFFF` (broadcast) on
 * the theory that "the firmware unwraps admin payloads locally". It does not.
 * A working reference client (meshsat-android) addresses every admin call to
 * `myNodeNum`, and the firmware's AdminModule only acts on packets addressed
 * to the node itself.
 *
 * Broadcasting also puts the payload on the air — for `set_channel` that is
 * the channel PSK, transmitted under whatever key is currently in use.
 */
class AdminAddressingTest {

    /** `MeshPacket.to` — mesh.proto field 2, fixed32, little-endian. */
    private fun meshPacketTo(frame: ByteArray): UInt? {
        // ToRadio { packet = 1 (len-delimited) } → MeshPacket { to = 2 (fixed32) }
        var i = 0
        while (i < frame.size) {
            val tag = frame[i].toInt() and 0xFF
            if (tag == 0x0A) { // field 1, wire 2 — ToRadio.packet
                var len = 0; var shift = 0; var j = i + 1
                while (j < frame.size) {
                    val b = frame[j].toInt() and 0xFF
                    len = len or ((b and 0x7F) shl shift); j++
                    if (b and 0x80 == 0) break
                    shift += 7
                }
                val packet = frame.copyOfRange(j, minOf(j + len, frame.size))
                var k = 0
                while (k + 4 < packet.size) {
                    if ((packet[k].toInt() and 0xFF) == 0x15) { // field 2, wire 5
                        return ((packet[k + 1].toInt() and 0xFF).toUInt()) or
                            ((packet[k + 2].toInt() and 0xFF).toUInt() shl 8) or
                            ((packet[k + 3].toInt() and 0xFF).toUInt() shl 16) or
                            ((packet[k + 4].toInt() and 0xFF).toUInt() shl 24)
                    }
                    k++
                }
                return null
            }
            i++
        }
        return null
    }

    private val myNodeNum = 0x336699AAu
    private val broadcast = 0xFFFFFFFFu

    /** Every admin frame the app can build, keyed by name for failure output. */
    private fun allAdminFrames(): Map<String, ByteArray> {
        val channel = MeshChannel(name = "OmniTAK", psk = ByteArray(16) { it.toByte() })
        return mapOf(
            "set_channel" to AdminMessageSerializer.buildSetChannel(myNodeNum, channel, index = 0),
            "set_channel0_name" to AdminMessageSerializer.buildSetChannel0Name(myNodeNum, "OmniTAK"),
            "set_owner" to AdminMessageSerializer.buildSetOwner(myNodeNum, "Pato Golf", "PATO"),
            "set_device_role" to AdminMessageSerializer.buildSetDeviceRole(myNodeNum, MeshRole.TAK),
            "set_rebroadcast_mode" to AdminMessageSerializer.buildSetRebroadcastMode(myNodeNum, RebroadcastMode.KNOWN_ONLY),
            "set_position_secs" to AdminMessageSerializer.buildSetPositionBroadcastSecs(myNodeNum, 900),
            "set_lora_preset" to AdminMessageSerializer.buildSetLoraPreset(myNodeNum, MeshChannelPreset.LONG_FAST),
            "set_lora_config" to AdminMessageSerializer.buildSetLoRaConfig(myNodeNum, MeshRegion.US, MeshChannelPreset.LONG_FAST),
            "get_owner" to AdminMessageSerializer.buildGetOwnerRequest(myNodeNum),
            "get_config" to AdminMessageSerializer.buildGetConfigRequest(myNodeNum, 0),
            "get_channel" to AdminMessageSerializer.buildGetChannelRequest(myNodeNum, 0),
        )
    }

    @Test
    fun `every admin message is addressed to the local radio`() {
        for ((name, frame) in allAdminFrames()) {
            assertEquals(
                "$name must be addressed to myNodeNum — the firmware's AdminModule " +
                    "only acts on packets addressed to the node itself",
                myNodeNum,
                meshPacketTo(frame),
            )
        }
    }

    @Test
    fun `no admin message goes to the broadcast address`() {
        for ((name, frame) in allAdminFrames()) {
            assertNotEquals(
                "$name must never be broadcast — it goes on the air, and for " +
                    "set_channel that is the channel PSK",
                broadcast,
                meshPacketTo(frame),
            )
        }
    }

    @Test
    fun `destination is carried verbatim, not truncated or byte-swapped`() {
        // A node number with a high bit set and all four bytes distinct catches
        // sign extension and endianness slips in the fixed32 encoding.
        val awkward = 0xFF01A27Bu
        val frame = AdminMessageSerializer.buildGetOwnerRequest(awkward)
        assertEquals(awkward, meshPacketTo(frame))
    }
}
