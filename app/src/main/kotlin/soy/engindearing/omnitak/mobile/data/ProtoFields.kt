package soy.engindearing.omnitak.mobile.data

import java.io.ByteArrayOutputStream

/**
 * One top-level field of a protobuf message, kept as the exact bytes it
 * occupied on the wire.
 *
 * [raw] is the whole field: the tag, then for a length-delimited field the
 * length, then the value. Keeping the original bytes is what lets a message be
 * edited without losing what the editor does not understand: a field that
 * this app has never heard of comes back out byte for byte.
 */
class ProtoField internal constructor(
    val number: Int,
    val wireType: Int,
    val raw: ByteArray,
    /** Offset inside [raw] where the value starts (after the tag, and after the length for wire type 2). */
    private val valueOffset: Int,
) {
    /** The value of a varint field, or null for any other wire type. */
    fun varint(): ULong? =
        if (wireType == ProtoFields.WIRE_VARINT) ProtoFields.readVarint(raw, valueOffset)?.first else null

    /** The payload of a length-delimited field (no tag, no length), or null for any other wire type. */
    fun bytes(): ByteArray? =
        if (wireType == ProtoFields.WIRE_LEN) raw.copyOfRange(valueOffset, raw.size) else null

    override fun equals(other: Any?): Boolean =
        other is ProtoField && number == other.number && wireType == other.wireType && raw.contentEquals(other.raw)

    override fun hashCode(): Int = 31 * (31 * number + wireType) + raw.contentHashCode()

    /** Size only. A field can hold a channel key, and this ends up in test and log output. */
    override fun toString(): String = "ProtoField(number=$number, wireType=$wireType, size=${raw.size})"
}

/**
 * Edit a protobuf message without a schema: read it as a list of top-level
 * fields, change the ones you mean to, write the rest back untouched.
 *
 * Meshtastic's firmware replaces a whole config (or channel, or owner) with
 * whatever an admin write carries, so a client that sends only the field it
 * changed resets every other field to its default. The safe write is the one
 * the radio last reported, with one field different. This is the piece that
 * makes that possible without generated message classes (the project
 * hand-rolls its protobuf on purpose, see [MeshWire]).
 *
 * Pure Kotlin, no Android types.
 */
object ProtoFields {

    const val WIRE_VARINT = 0
    const val WIRE_FIXED64 = 1
    const val WIRE_LEN = 2
    const val WIRE_FIXED32 = 5

    /** A field number is 29 bits, so a tag always fits 32. */
    private const val MAX_TAG = 0xFFFFFFFFuL

    // region Reading --------------------------------------------------------

    /**
     * Split [bytes] into its top-level fields, in order. Null when the bytes
     * are not a well-formed message: a truncated tag or value, a length that
     * runs past the end, field number 0, or a wire type this format does not
     * use here (3 and 4 are groups, 6 and 7 do not exist). Nothing is
     * guessed: the editor refuses to write when it cannot read.
     */
    fun parse(bytes: ByteArray): List<ProtoField>? {
        val out = ArrayList<ProtoField>()
        var i = 0
        while (i < bytes.size) {
            val start = i
            val (tag, afterTag) = readVarint(bytes, i) ?: return null
            if (tag > MAX_TAG) return null
            val number = (tag shr 3).toInt()
            val wire = (tag and 7uL).toInt()
            if (number < 1) return null
            i = afterTag
            var valueOffset = i
            when (wire) {
                WIRE_VARINT -> i = (readVarint(bytes, i) ?: return null).second
                WIRE_FIXED64 -> {
                    if (bytes.size - i < 8) return null
                    i += 8
                }
                WIRE_LEN -> {
                    val (len, afterLen) = readVarint(bytes, i) ?: return null
                    if (len > (bytes.size - afterLen).toULong()) return null
                    valueOffset = afterLen
                    i = afterLen + len.toInt()
                }
                WIRE_FIXED32 -> {
                    if (bytes.size - i < 4) return null
                    i += 4
                }
                else -> return null
            }
            out += ProtoField(number, wire, bytes.copyOfRange(start, i), valueOffset - start)
        }
        return out
    }

    /** The inverse of [parse]: the fields' original bytes, back to back. */
    fun serialize(fields: List<ProtoField>): ByteArray {
        val out = ByteArrayOutputStream()
        for (f in fields) out.write(f.raw)
        return out.toByteArray()
    }

    /** Value of the last varint field [number] (a repeat of a scalar field: the last one wins), or null. */
    fun lastVarint(fields: List<ProtoField>, number: Int): ULong? =
        fields.lastOrNull { it.number == number && it.wireType == WIRE_VARINT }?.varint()

    /** Payload of the last length-delimited field [number], or null. */
    fun lastBytes(fields: List<ProtoField>, number: Int): ByteArray? =
        fields.lastOrNull { it.number == number && it.wireType == WIRE_LEN }?.bytes()

    /** The last length-delimited field [number] as UTF-8 text, or null. */
    fun lastString(fields: List<ProtoField>, number: Int): String? =
        lastBytes(fields, number)?.toString(Charsets.UTF_8)

    // endregion

    // region Editing --------------------------------------------------------

    /**
     * Apply [edits] to [original] and return the new message, or null when
     * [original] is not a well-formed message.
     *
     * An edit maps a field number to the new, fully encoded field (use the
     * encoders below), or to null to remove the field, which is how a value
     * goes back to its proto3 default. Every occurrence of an edited field is
     * replaced: the new field takes the place of the first occurrence and any
     * later ones are dropped. A field that is not there yet is inserted before
     * the first field with a higher number, or at the end. Everything not
     * edited, including fields this app has never heard of, is kept byte for
     * byte in its original order.
     */
    fun patch(original: ByteArray, edits: Map<Int, ByteArray?>): ByteArray? {
        for ((number, encoded) in edits) {
            if (encoded != null) {
                val single = parse(encoded)
                require(single != null && single.size == 1 && single[0].number == number) {
                    "edit for field $number is not exactly one encoded field $number"
                }
            }
        }
        val fields = parse(original) ?: return null

        val kept = ArrayList<Pair<Int, ByteArray>>(fields.size + edits.size)
        val seen = HashSet<Int>()
        for (f in fields) {
            if (f.number !in edits) {
                kept += f.number to f.raw
            } else if (seen.add(f.number)) {
                edits[f.number]?.let { kept += f.number to it }
            }
        }
        for (number in edits.keys.sorted()) {
            if (number in seen) continue
            val encoded = edits[number] ?: continue
            val at = kept.indexOfFirst { it.first > number }.let { if (it < 0) kept.size else it }
            kept.add(at, number to encoded)
        }

        val out = ByteArrayOutputStream(original.size + 16)
        for ((_, raw) in kept) out.write(raw)
        return out.toByteArray()
    }

    /**
     * [patch] inside the sub-message at [field]: the nested message is
     * unpacked, [edits] are applied to it, and it is packed back in place.
     * `Channel.settings.name` is `patchNested(channel, 2, mapOf(3 to ...))`.
     *
     * A missing sub-message is created when an edit puts something in it.
     * Null when [original] or the sub-message is malformed, when [field] is
     * not length-delimited, or when it occurs more than once (protobuf merges
     * repeats of a message field; this does not guess at the merge).
     */
    fun patchNested(original: ByteArray, field: Int, edits: Map<Int, ByteArray?>): ByteArray? {
        val fields = parse(original) ?: return null
        val matches = fields.filter { it.number == field }
        if (matches.size > 1 || matches.any { it.wireType != WIRE_LEN }) return null
        val inner = matches.firstOrNull()?.bytes() ?: ByteArray(0)
        val patchedInner = patch(inner, edits) ?: return null
        if (matches.isEmpty() && patchedInner.isEmpty()) return original.copyOf()
        return patch(original, mapOf(field to message(field, patchedInner)))
    }

    // endregion

    // region Encoders -------------------------------------------------------

    /** `uint32` / `int32` / enum field. */
    fun varint(field: Int, value: ULong): ByteArray = encode { MeshWire.appendVarintField(it, field, value) }

    fun bool(field: Int, value: Boolean): ByteArray = varint(field, if (value) 1uL else 0uL)

    fun string(field: Int, value: String): ByteArray = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun bytes(field: Int, value: ByteArray): ByteArray = encode { MeshWire.appendLenField(it, field, value) }

    /** A sub-message field: the same bytes on the wire as [bytes]. */
    fun message(field: Int, value: ByteArray): ByteArray = bytes(field, value)

    /** As [varint], but null for 0, the proto3 default: pass it to [patch] to remove the field. */
    fun varintOrClear(field: Int, value: ULong): ByteArray? = if (value == 0uL) null else varint(field, value)

    /** As [bool], but null for false. */
    fun boolOrClear(field: Int, value: Boolean): ByteArray? = if (value) bool(field, true) else null

    /** As [string], but null for the empty string. */
    fun stringOrClear(field: Int, value: String): ByteArray? = if (value.isEmpty()) null else string(field, value)

    private inline fun encode(write: (ByteArrayOutputStream) -> Unit): ByteArray =
        ByteArrayOutputStream().also(write).toByteArray()

    // endregion

    /** A base-128 varint at [offset]: (value, offset after it), or null if truncated or longer than 10 bytes. */
    internal fun readVarint(buf: ByteArray, offset: Int): Pair<ULong, Int>? {
        var result = 0uL
        var shift = 0
        var i = offset
        while (i < buf.size) {
            val b = buf[i].toInt() and 0xFF
            i++
            result = result or ((b and 0x7F).toULong() shl shift)
            if (b and 0x80 == 0) return result to i
            shift += 7
            if (shift >= 64) return null
        }
        return null
    }
}
