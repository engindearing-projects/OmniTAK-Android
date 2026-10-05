package soy.engindearing.omnitak.mobile.data

import java.io.ByteArrayOutputStream

/**
 * Tiny protobuf writer for building test frames.
 *
 * Tests call it with the field numbers from the official .proto files, named
 * at the call site, instead of reusing [MeshWire]. That way a wrong number in
 * production code cannot hide behind the same wrong number in the test.
 *
 * Wire types: [varint] and [bool] are 0, [fixed32] and [float] are 5,
 * [bytes], [string] and [msg] are 2.
 */
internal class ProtoMsg {
    private val out = ByteArrayOutputStream()

    private fun rawVarint(value: ULong) {
        var v = value
        while (v >= 0x80uL) {
            out.write(((v and 0x7FuL).toInt()) or 0x80)
            v = v shr 7
        }
        out.write(v.toInt() and 0x7F)
    }

    private fun tag(field: Int, wire: Int) = rawVarint(((field shl 3) or wire).toULong())

    /** uint32 / int32 / enum. A negative value is written as the 10-byte
     *  sign-extended varint that an int32 field uses. */
    fun varint(field: Int, value: Long): ProtoMsg = apply { tag(field, 0); rawVarint(value.toULong()) }

    fun varint(field: Int, value: Int): ProtoMsg = varint(field, value.toLong())

    fun bool(field: Int, value: Boolean): ProtoMsg = varint(field, if (value) 1L else 0L)

    /** fixed32 / sfixed32: four bytes, little-endian. */
    fun fixed32(field: Int, value: Int): ProtoMsg = apply {
        tag(field, 5)
        for (shift in 0..24 step 8) out.write((value ushr shift) and 0xFF)
    }

    fun float(field: Int, value: Float): ProtoMsg = fixed32(field, value.toRawBits())

    fun bytes(field: Int, value: ByteArray): ProtoMsg = apply {
        tag(field, 2)
        rawVarint(value.size.toULong())
        out.write(value)
    }

    fun string(field: Int, value: String): ProtoMsg = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun msg(field: Int, value: ProtoMsg): ProtoMsg = bytes(field, value.build())

    fun build(): ByteArray = out.toByteArray()
}
