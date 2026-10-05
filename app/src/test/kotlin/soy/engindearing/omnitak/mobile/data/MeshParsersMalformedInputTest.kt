package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * The mesh parsers read bytes that arrive from other radios. Whatever those
 * bytes are, a parser has to come back: no endless loop, no exception.
 */
class MeshParsersMalformedInputTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun varint(value: Long): ByteArray {
        var v = value
        val out = ByteArrayOutputStream()
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
        return out.toByteArray()
    }

    /** Field 1 = 0, then field 15 as length-delimited with a declared length of 4,294,967,290 and no body. */
    private val hugeLengthField: ByteArray =
        byteArrayOf(0x08, 0x00) + varint((15L shl 3) or 2) + varint(0xFFFF_FFFAL)

    // MARK: the length that runs past the buffer

    @Test
    fun skipFieldNeverMovesBackwards() {
        val buf = hugeLengthField
        val lengthAt = 3 // just after the tag of field 15
        val next = MeshtasticProtoParser.skipField(buf, lengthAt, wireType = 2)
        assertEquals("a length that runs past the end ends the message", buf.size, next)

        // Every declared length, small or absurd, lands at or after where it started and inside the buffer.
        for (declared in listOf(0L, 1L, 4L, 5L, 127L, 128L, 0x7FFF_FFFFL, 0x8000_0000L, 0xFFFF_FFFAL, 0xFFFF_FFFFL, Long.MAX_VALUE, -1L)) {
            val b = byteArrayOf(0x7A) + varint(declared) + byteArrayOf(1, 2, 3, 4)
            val after = MeshtasticProtoParser.skipField(b, 1, wireType = 2)
            assertTrue("declared length $declared moved to $after", after in 2..b.size)
        }
    }

    @Test(timeout = 10_000)
    fun aFieldWithAHugeLengthDoesNotHangTheTakPacketParser() {
        TakPacketParser.parse(hugeLengthField, fromNodeId = 0x1234u)
    }

    @Test(timeout = 10_000)
    fun aFieldWithAHugeLengthDoesNotHangTheOtherParsers() {
        MeshtasticProtoParser.parseFromRadio(hugeLengthField)
        MeshtasticProtoParser.parsePosition(hugeLengthField)
        AtakPluginParser.parse(hugeLengthField)
        AdminMessageParser.parse(hugeLengthField)
        TakPacketV2Codec.decode(hugeLengthField)
        // The same field nested one level down, where FromRadio hands a sub-message to another parser.
        for (outerField in listOf(2, 3, 4, 5, 10)) {
            val nested = varint((outerField.toLong() shl 3) or 2) + varint(hugeLengthField.size.toLong()) + hugeLengthField
            MeshtasticProtoParser.parseFromRadio(nested)
        }
    }

    // MARK: random bytes

    private fun fuzz(name: String, iterations: Int, seed: Int, parse: (ByteArray) -> Unit) {
        val random = Random(seed)
        repeat(iterations) { i ->
            val input = when (i % 3) {
                0 -> random.nextBytes(1 + random.nextInt(240))
                1 -> structured(random)
                else -> hugeLengthField + random.nextBytes(random.nextInt(40))
            }
            try {
                parse(input)
            } catch (t: Throwable) {
                fail("$name threw ${t.javaClass.simpleName} on iteration $i, input ${hex(input)}")
            }
        }
    }

    /** Plausible protobuf: real tags and wire types, with lengths that are sometimes wrong or enormous. */
    private fun structured(random: Random): ByteArray {
        val out = ByteArrayOutputStream()
        repeat(1 + random.nextInt(8)) {
            val field = 1 + random.nextInt(20)
            when (val wire = listOf(0, 1, 2, 2, 2, 5, 3, 7).random(random)) {
                0 -> { out.write(varint((field.toLong() shl 3) or 0)); out.write(varint(random.nextLong())) }
                1 -> { out.write(varint((field.toLong() shl 3) or 1)); out.write(random.nextBytes(random.nextInt(9))) }
                2 -> {
                    val body = random.nextBytes(random.nextInt(24))
                    val declared = when (random.nextInt(5)) {
                        0 -> 0xFFFF_FFF0L + random.nextInt(16)
                        1 -> body.size + 1L + random.nextInt(300)
                        2 -> random.nextLong()
                        else -> body.size.toLong()
                    }
                    out.write(varint((field.toLong() shl 3) or 2)); out.write(varint(declared)); out.write(body)
                }
                5 -> { out.write(varint((field.toLong() shl 3) or 5)); out.write(random.nextBytes(random.nextInt(5))) }
                else -> out.write(varint((field.toLong() shl 3) or wire.toLong()))
            }
        }
        return out.toByteArray()
    }

    @Test(timeout = 120_000)
    fun randomBytesNeverHangOrCrashTheTakPacketParser() =
        fuzz("TakPacketParser", 200_000, seed = 11) { TakPacketParser.parse(it, fromNodeId = 0x1234u) }

    /** TAKPacket { is_compressed = true, contact { callsign, device_callsign }, chat { message, to, to_callsign } } with garbage in every text field. */
    @Test(timeout = 120_000)
    fun garbageInCompressedTextFieldsNeverHangsOrCrashesTheTakPacketParser() {
        val random = Random(21)
        fun field(number: Int, body: ByteArray) = varint((number.toLong() shl 3) or 2) + varint(body.size.toLong()) + body
        repeat(100_000) { i ->
            val contact = field(1, random.nextBytes(1 + random.nextInt(60))) + field(2, random.nextBytes(1 + random.nextInt(60)))
            val chat = field(1, random.nextBytes(1 + random.nextInt(200))) +
                field(2, random.nextBytes(1 + random.nextInt(60))) +
                field(3, random.nextBytes(1 + random.nextInt(60)))
            val packet = byteArrayOf(0x08, 0x01) + field(2, contact) + field(6, chat)
            try {
                TakPacketParser.parse(packet, fromNodeId = 0x1234u)
            } catch (t: Throwable) {
                fail("TakPacketParser threw ${t.javaClass.simpleName} on iteration $i, input ${hex(packet)}")
            }
        }
    }

    @Test(timeout = 120_000)
    fun randomBytesNeverHangOrCrashTheAtakPluginParser() =
        fuzz("AtakPluginParser", 200_000, seed = 12) { AtakPluginParser.parse(it) }

    @Test(timeout = 120_000)
    fun randomBytesNeverHangOrCrashTheMarkerDecoder() =
        fuzz("TakPacketV2Codec", 200_000, seed = 13) { TakPacketV2Codec.decode(it) }

    @Test(timeout = 120_000)
    fun randomBytesNeverHangOrCrashTheRadioFrameParser() =
        fuzz("MeshtasticProtoParser", 200_000, seed = 14) {
            MeshtasticProtoParser.parseFromRadio(it)
            MeshtasticProtoParser.parsePosition(it)
        }

    @Test(timeout = 120_000)
    fun randomBytesNeverHangOrCrashTheAdminParser() =
        fuzz("AdminMessageParser", 200_000, seed = 15) { AdminMessageParser.parse(it) }
}
