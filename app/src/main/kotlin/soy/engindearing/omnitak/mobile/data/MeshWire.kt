package soy.engindearing.omnitak.mobile.data

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.random.Random

/**
 * The single protobuf wire-format library for the hand-rolled Meshtastic
 * encoders. The project deliberately avoids protobuf-javalite (a Gradle
 * plugin + regenerated types per Meshtastic field bump); these primitives
 * plus [MeshtasticProtoParser] on the read side are the whole wire layer.
 *
 * Before this object, appendVarint/appendTag lived in four self-described
 * "duplicated from AtakPluginSerializer" copies and the
 * ToRadio{MeshPacket{Data{portnum,payload}}} framing was built three
 * times — a MeshPacket schema fix (like the documented field-9-vs-10
 * hop_limit/want_ack confusion) had to be re-applied per copy.
 */
object MeshWire {

    /** Meshtastic broadcast address — channel-wide chat / position / etc. */
    const val BROADCAST_ADDR: UInt = 0xFFFFFFFFu

    // region Primitive writers --------------------------------------------

    fun appendVarint(out: OutputStream, value: ULong) {
        var v = value
        while (v >= 0x80uL) {
            out.write(((v and 0x7FuL).toInt()) or 0x80)
            v = v shr 7
        }
        out.write(v.toInt() and 0x7F)
    }

    fun appendTag(out: OutputStream, field: Int, wire: Int) {
        appendVarint(out, ((field shl 3) or (wire and 0x7)).toULong())
    }

    fun appendVarintField(out: OutputStream, field: Int, value: ULong) {
        appendTag(out, field = field, wire = 0)
        appendVarint(out, value)
    }

    /** fixed32: 4-byte little-endian unsigned. */
    fun appendFixed32(out: OutputStream, value: UInt) {
        for (i in 0 until 4) {
            out.write(((value shr (8 * i)) and 0xFFu).toInt())
        }
    }

    /** sfixed32: 4-byte little-endian signed 32-bit integer. */
    fun appendSFixed32(out: OutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }

    fun appendFixed64(out: OutputStream, value: ULong) {
        for (i in 0 until 8) {
            out.write(((value shr (8 * i)) and 0xFFuL).toInt())
        }
    }

    /** double: fixed64 of the IEEE-754 bit pattern (wire type 1). */
    fun appendDouble(out: OutputStream, field: Int, value: Double) {
        appendTag(out, field = field, wire = 1)
        appendFixed64(out, value.toRawBits().toULong())
    }

    /** Length-delimited string field. Writes even when empty — guard at
     *  the call site when proto3 skip-default semantics are wanted. */
    fun appendString(out: OutputStream, field: Int, value: String) {
        appendLenField(out, field, value.toByteArray(Charsets.UTF_8))
    }

    /** Length-delimited bytes / sub-message field. */
    fun appendLenField(out: OutputStream, field: Int, bytes: ByteArray) {
        appendTag(out, field = field, wire = 2)
        appendVarint(out, bytes.size.toULong())
        out.write(bytes)
    }

    // endregion

    // region ToRadio framing -----------------------------------------------

    /**
     * Frame a payload as `ToRadio { MeshPacket { Data { portnum, payload } } }`
     * — the one MeshPacket writer for every portnum (1 text, 6 admin,
     * 72 ATAK plugin). Caller is responsible for any TCP 0x94C3 framing
     * (see [MeshtasticTcpClient]); BLE writes the bytes as-is.
     *
     * Field numbers per canonical Meshtastic mesh.proto. Note want_ack is
     * MeshPacket field 10 — field 9 is hop_limit; using 9 would silently
     * confuse hop_limit and want_ack across the iOS↔Android wire.
     *
     * [hopLimit] is emitted on field 9. It was never written before, which
     * left it at the proto default of 0 — a 0-hop packet the radio silently
     * drops instead of forwarding past the first node. Default 3 matches the
     * Meshtastic firmware default and iOS.
     */
    fun buildToRadio(
        portnum: ULong = 72UL,
        payload: ByteArray,
        to: UInt = BROADCAST_ADDR,
        channelIndex: UInt = 0u,
        packetId: UInt? = null,
        hopLimit: UInt = 3u,
        wantAck: Boolean = false,
        wantResponse: Boolean = false,
    ): ByteArray {
        val resolvedId = packetId ?: Random.nextInt().toUInt().let { if (it == 0u) 1u else it }

        // Data submessage.
        val decoded = ByteArrayOutputStream().apply {
            // 1: portnum (varint)
            appendVarintField(this, field = 1, value = portnum)
            // 2: payload (bytes)
            appendLenField(this, field = 2, bytes = payload)
            // 3: want_response (bool varint) — admin reads want an ack. Field 5
            // is `source` (fixed32), and a varint there is a wire-type mismatch
            // that the firmware's nanopb decoder rejects.
            if (wantResponse) {
                appendVarintField(this, field = 3, value = 1UL)
            }
        }.toByteArray()

        // MeshPacket.
        val meshPacket = ByteArrayOutputStream().apply {
            // 2: to (fixed32) — always emit so the radio knows the destination.
            appendTag(this, field = 2, wire = 5)
            appendFixed32(this, to)
            // 3: channel (varint) — only emit non-default to keep payload tight.
            if (channelIndex != 0u) {
                appendVarintField(this, field = 3, value = channelIndex.toULong())
            }
            // 4: decoded (sub-message)
            appendLenField(this, field = 4, bytes = decoded)
            // 6: id (fixed32)
            appendTag(this, field = 6, wire = 5)
            appendFixed32(this, resolvedId)
            // 9: hop_limit (varint) — emitted BEFORE want_ack. Skipping it
            // left hop_limit at 0 and the radio dropped the packet at hop 1.
            if (hopLimit > 0u) {
                appendVarintField(this, field = 9, value = hopLimit.toULong())
            }
            // 10: want_ack (bool varint) — see the field-9-vs-10 note above.
            if (wantAck) {
                appendVarintField(this, field = 10, value = 1UL)
            }
        }.toByteArray()

        // ToRadio with field 1 = packet (length-delimited).
        return ByteArrayOutputStream().apply {
            appendLenField(this, field = 1, bytes = meshPacket)
        }.toByteArray()
    }

    // endregion
}
