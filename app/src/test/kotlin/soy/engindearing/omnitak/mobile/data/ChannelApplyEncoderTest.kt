package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #172 — byte-layout assertions for the channel-apply encoders. These are
 * protocol-defined (admin.proto / channel.proto / config.proto field numbers,
 * and the MeshCore companion "Set Channel" command), so they must match the
 * iOS encoder written in parallel. Asserting tags / lengths / enum values
 * keeps both platforms spec-faithful.
 */
class ChannelApplyEncoderTest {

    /** #185 — admin frames are addressed to the attached radio. Value is
     *  irrelevant to these byte-layout assertions, but must be supplied. */
    private val myNodeNum = 0x12345678u

    // region helpers ------------------------------------------------------

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    /** Does [needle] appear contiguously inside [this]? The admin blobs are
     *  wrapped in a ToRadio frame, so we assert the admin sub-bytes are
     *  present rather than reproducing the random packet id / framing. */
    private fun ByteArray.containsBytes(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (start in 0..size - needle.size) {
            for (i in needle.indices) if (this[start + i] != needle[i]) continue@outer
            return true
        }
        return false
    }

    // region Meshtastic set_channel --------------------------------------

    @Test
    fun `Meshtastic set_channel encodes psk name role with correct tags`() {
        val psk = ByteArray(16) { (it * 7 + 3).toByte() }
        val channel = MeshChannel(name = "OmniTAK", psk = psk)

        val frame = AdminMessageSerializer.buildSetChannel(myNodeNum, channel, index = 0)

        // Ground-truth admin bytes (computed from admin/channel.proto field
        // numbers): set_channel=33 (tag 8a 02), Channel{ settings=2{ psk=2,
        // name=3 }, role=3=PRIMARY(1) }; index 0 omitted (proto3 default).
        val expectedAdmin = "8a021f121b1210030a11181f262d343b424950575e656c1a074f6d6e6954414b1801"
        val expectedBytes = expectedAdmin.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        assertTrue(
            "admin set_channel bytes must appear in frame; got ${frame.hex()}",
            frame.containsBytes(expectedBytes),
        )
    }

    @Test
    fun `Meshtastic set_channel at non-zero index sets SECONDARY role and index`() {
        val psk = ByteArray(16) { it.toByte() }
        val frame = AdminMessageSerializer.buildSetChannel(myNodeNum, MeshChannel(name = "Cmd", psk = psk), index = 2)

        // Channel.index = 1 (varint) = 2, Channel.role = 3 (varint) = SECONDARY(2).
        // index field present: tag 08 02; role field: tag 18 02.
        assertTrue("index field 08 02 present", frame.containsBytes(byteArrayOf(0x08, 0x02)))
        assertTrue("role SECONDARY 18 02 present", frame.containsBytes(byteArrayOf(0x18, 0x02)))
    }

    // region Meshtastic set_config rebroadcast_mode ----------------------

    @Test
    fun `Meshtastic set_config rebroadcast KNOWN_ONLY encodes enum 3`() {
        val frame = AdminMessageSerializer.buildSetRebroadcastMode(myNodeNum, RebroadcastMode.KNOWN_ONLY)

        // set_config=34 (tag 92 02), Config.device=1{ rebroadcast_mode=6 = 3 }.
        val expected = "9202040a023003".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertTrue("rebroadcast KNOWN_ONLY admin bytes present; got ${frame.hex()}", frame.containsBytes(expected))
    }

    @Test
    fun `RebroadcastMode wire ordinals match config_proto`() {
        assertEquals(0, RebroadcastMode.ALL.wire)
        assertEquals(2, RebroadcastMode.LOCAL_ONLY.wire)
        assertEquals(3, RebroadcastMode.KNOWN_ONLY.wire)
        assertEquals(4, RebroadcastMode.NONE.wire)
    }

    // region #181 set_config LoRaConfig (region + modem preset) ----------

    @Test
    fun `set_config LoRa US LONG_FAST encodes use_preset and region with default preset omitted`() {
        val frame = AdminMessageSerializer.buildSetLoRaConfig(myNodeNum, 
            region = MeshRegion.US,
            modemPreset = MeshChannelPreset.LONG_FAST,
        )

        // set_config=34 (tag 92 02), Config.lora=6 (tag 32){ use_preset=1 = true
        // (08 01), region=7 = US(1) (38 01) }. modem_preset LONG_FAST=0 omitted
        // (proto3 skip-default).
        val expected = "920206320408013801".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertTrue("LoRa US/LONG_FAST admin bytes present; got ${frame.hex()}", frame.containsBytes(expected))
    }

    @Test
    fun `set_config LoRa EU868 MEDIUM_FAST encodes preset 4 and region 3`() {
        val frame = AdminMessageSerializer.buildSetLoRaConfig(myNodeNum, 
            region = MeshRegion.EU_868,
            modemPreset = MeshChannelPreset.MEDIUM_FAST,
        )

        // Config.lora{ use_preset=1 (08 01), modem_preset=2 = MEDIUM_FAST(4)
        // (10 04), region=7 = EU_868(3) (38 03) }.
        val expected = "9202083206080110043803".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertTrue("LoRa EU868/MEDIUM_FAST admin bytes present; got ${frame.hex()}", frame.containsBytes(expected))
    }

    @Test
    fun `set_config LoRa with UNSET region omits the region field`() {
        val frame = AdminMessageSerializer.buildSetLoRaConfig(myNodeNum, 
            region = MeshRegion.UNSET,
            modemPreset = MeshChannelPreset.SHORT_FAST,
        )

        // region UNSET(0) omitted; LoRaConfig = use_preset(08 01) +
        // modem_preset SHORT_FAST(6) (10 06). No 38 (region) tag present.
        val lora = "08011006".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertTrue("LoRa preset-only bytes present; got ${frame.hex()}", frame.containsBytes(lora))
        // region tag 0x38 must not appear inside the LoRaConfig submessage.
        assertTrue(
            "region tag should be absent for UNSET",
            !frame.containsBytes(byteArrayOf(0x38, 0x00)),
        )
    }

    @Test
    fun `MeshRegion wire numbers match config_proto RegionCode`() {
        assertEquals(0, MeshRegion.UNSET.wire)
        assertEquals(1, MeshRegion.US.wire)
        assertEquals(2, MeshRegion.EU_433.wire)
        assertEquals(3, MeshRegion.EU_868.wire)
        assertEquals(6, MeshRegion.ANZ.wire)
        assertEquals(26, MeshRegion.BR_902.wire)
    }

    // region #181 set_owner (device name) --------------------------------

    @Test
    fun `set_owner encodes long and short name with correct tags`() {
        val frame = AdminMessageSerializer.buildSetOwner(myNodeNum, longName = "OmniTAK", shortName = "OTK")

        // set_owner=32 (tag 82 02), User{ long_name=2 (12 07 "OmniTAK"),
        // short_name=3 (1a 03 "OTK") }. id blank + is_licensed false omitted.
        val expected = "82020e12074f6d6e6954414b1a034f544b"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertTrue("set_owner admin bytes present; got ${frame.hex()}", frame.containsBytes(expected))
    }

    @Test
    fun `set_owner with is_licensed sets bool field 6`() {
        val frame = AdminMessageSerializer.buildSetOwner(myNodeNum, 
            longName = "W1AW",
            shortName = "W1AW",
            isLicensed = true,
        )

        // User{ long_name=2 (12 04 "W1AW"), short_name=3 (1a 04 "W1AW"),
        // is_licensed=6 = true (30 01) }.
        val expected = "82020e1204573141571a04573141573001"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertTrue("set_owner licensed admin bytes present; got ${frame.hex()}", frame.containsBytes(expected))
    }

    @Test
    fun `set_owner truncates short name to 4 chars`() {
        val frame = AdminMessageSerializer.buildSetOwner(myNodeNum, longName = "Node", shortName = "TOOLONG")

        // short_name field 3 must carry exactly 4 bytes: tag 1a, len 04, "TOOL".
        val shortField = ("1a04" + "TOOL".toByteArray().joinToString("") { "%02x".format(it) })
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertTrue("short name truncated to 4 chars; got ${frame.hex()}", frame.containsBytes(shortField))
    }

    // region MeshCore CMD_SET_CHANNEL (0x20) -----------------------------

    @Test
    fun `MeshCore set_channel is 50 bytes with 0x20 opcode and null-padded name`() {
        val secret = byteArrayOf(
            0x8b.toByte(), 0x33, 0x87.toByte(), 0xe9.toByte(),
            0xc5.toByte(), 0xcd.toByte(), 0xea.toByte(), 0x6a,
            0xc9.toByte(), 0xe5.toByte(), 0xed.toByte(), 0xba.toByte(),
            0xa1.toByte(), 0x15, 0xcd.toByte(), 0x72,
        )
        val cmd = MeshCoreFrameCodec.buildSetChannel(index = 1, name = "Public", secret = secret)

        assertEquals("fixed 50-byte layout", 50, cmd.size)
        assertEquals("opcode 0x20", 0x20, cmd[0].toInt() and 0xFF)
        assertEquals("channel index", 1, cmd[1].toInt() and 0xFF)

        // Name "Public" UTF-8 at bytes 2..7, null padding through byte 33.
        assertEquals("Public", String(cmd, 2, 6, Charsets.UTF_8))
        for (i in 8 until 34) assertEquals("name null-padded at $i", 0, cmd[i].toInt() and 0xFF)

        // Secret verbatim at bytes 34..49.
        val outSecret = cmd.copyOfRange(34, 50)
        assertEquals(secret.hex(), outSecret.hex())
    }

    @Test
    fun `MeshCore public channel encodes all-zero secret`() {
        val cmd = MeshCoreFrameCodec.buildSetChannel(index = 0, name = "Public", secret = ByteArray(0))
        assertEquals(50, cmd.size)
        for (i in 34 until 50) assertEquals("public secret zero at $i", 0, cmd[i].toInt() and 0xFF)
    }

    @Test
    fun `MeshCore set_channel truncates an over-long name to 32 bytes`() {
        val longName = "X".repeat(40)
        val cmd = MeshCoreFrameCodec.buildSetChannel(index = 1, name = longName, secret = ByteArray(16) { 1 })
        assertEquals(50, cmd.size)
        // bytes 2..33 are all 'X' (32 of them); secret still intact after.
        for (i in 2 until 34) assertEquals('X'.code, cmd[i].toInt() and 0xFF)
    }
}
