package soy.engindearing.omnitak.mobile.data

import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * Serializes a [CoTEvent] into a Meshtastic portnum-72 (ATAK_PLUGIN)
 * payload. Inverse of [AtakPluginParser]. Mirrors
 * `ATAKPluginSerializer.swift` on iOS.
 *
 * Produces a TAK-protobuf TAKMessage with a CoTEvent submessage
 * populated from the OmniTAK [CoTEvent] model. Like the parser this
 * hand-rolls the wire format to avoid pulling in protobuf-javalite and
 * to stay consistent with the existing manual encoders in
 * [MeshtasticTcpClient] / [MeshtasticProtoParser].
 */
object AtakPluginSerializer {

    private const val PORTNUM_ATAK_PLUGIN: ULong = 72UL

    /** Serialize a [CoTEvent] into the bytes of a TAKMessage protobuf,
     *  suitable for use as the payload of a Meshtastic Data message at
     *  portnum 72. */
    fun serialize(
        event: CoTEvent,
        how: String = "m-g",
        sendTimeMs: Long = System.currentTimeMillis(),
        startTimeMs: Long? = null,
        staleTimeMs: Long? = null,
    ): ByteArray {
        val cotEvent = encodeCoTEvent(
            event = event,
            how = how,
            sendTimeMs = sendTimeMs,
            startTimeMs = startTimeMs ?: sendTimeMs,
            staleTimeMs = staleTimeMs ?: (sendTimeMs + 60_000L),
        )
        // Wrap in TAKMessage (field 2 = cotEvent, wire type 2).
        val out = ByteArrayOutputStream()
        appendTag(out, field = 2, wire = 2)
        appendVarint(out, cotEvent.size.toULong())
        out.write(cotEvent)
        return out.toByteArray()
    }

    /** Build a Meshtastic ToRadio bytes blob with a MeshPacket carrying
     *  a portnum-72 payload. Caller is responsible for any TCP framing
     *  (see [MeshtasticTcpClient.sendBytes] / `sendFrame`). */
    fun buildToRadio(
        payloadBytes: ByteArray,
        to: UInt = 0xFFFFFFFFu,
        channelIndex: UInt = 0u,
        packetId: UInt? = null,
        wantAck: Boolean = false,
    ): ByteArray {
        val resolvedId = packetId ?: Random.nextInt().toUInt().let { if (it == 0u) 1u else it }

        // Data submessage.
        val decoded = ByteArrayOutputStream().apply {
            // 1: portnum (varint)
            appendVarintField(this, field = 1, value = PORTNUM_ATAK_PLUGIN)
            // 2: payload (bytes)
            appendTag(this, field = 2, wire = 2)
            appendVarint(this, payloadBytes.size.toULong())
            write(payloadBytes)
        }.toByteArray()

        // MeshPacket.
        val meshPacket = ByteArrayOutputStream().apply {
            // 2: to (fixed32) — always emit so radio knows the destination.
            appendTag(this, field = 2, wire = 5)
            appendFixed32(this, to)
            // 3: channel (varint) — only emit non-default to keep payload tight.
            if (channelIndex != 0u) {
                appendVarintField(this, field = 3, value = channelIndex.toULong())
            }
            // 4: decoded (sub-message)
            appendTag(this, field = 4, wire = 2)
            appendVarint(this, decoded.size.toULong())
            write(decoded)
            // 6: id (fixed32)
            appendTag(this, field = 6, wire = 5)
            appendFixed32(this, resolvedId)
            // 10: want_ack (bool varint) — per canonical Meshtastic
            // mesh.proto. (Field 9 is hop_limit, NOT want_ack.) iOS uses
            // field 10 also; using 9 here would silently confuse hop_limit
            // and want_ack across the iOS↔Android wire.
            if (wantAck) {
                appendVarintField(this, field = 10, value = 1UL)
            }
        }.toByteArray()

        // ToRadio with field 1 = packet (length-delimited).
        val toRadio = ByteArrayOutputStream().apply {
            appendTag(this, field = 1, wire = 2)
            appendVarint(this, meshPacket.size.toULong())
            write(meshPacket)
        }.toByteArray()

        return toRadio
    }

    // region CoTEvent encoder --------------------------------------------

    private fun encodeCoTEvent(
        event: CoTEvent,
        how: String,
        sendTimeMs: Long,
        startTimeMs: Long,
        staleTimeMs: Long,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        // 1: type
        appendString(out, field = 1, value = event.type)
        // 5: uid
        appendString(out, field = 5, value = event.uid)
        // 6: sendTime (uint64 ms)
        appendVarintField(out, field = 6, value = millis(sendTimeMs))
        // 7: startTime
        appendVarintField(out, field = 7, value = millis(startTimeMs))
        // 8: staleTime
        appendVarintField(out, field = 8, value = millis(staleTimeMs))
        // 9: how
        appendString(out, field = 9, value = how)
        // 10..14: doubles via fixed64 bit pattern
        appendDouble(out, field = 10, value = event.lat)
        appendDouble(out, field = 11, value = event.lon)
        appendDouble(out, field = 12, value = event.hae)
        appendDouble(out, field = 13, value = event.ce)
        appendDouble(out, field = 14, value = event.le)
        // 15: detail
        val detailBytes = encodeDetail(event)
        if (detailBytes.isNotEmpty()) {
            appendTag(out, field = 15, wire = 2)
            appendVarint(out, detailBytes.size.toULong())
            out.write(detailBytes)
        }
        return out.toByteArray()
    }

    private fun encodeDetail(event: CoTEvent): ByteArray {
        val out = ByteArrayOutputStream()
        // 6: contact (callsign)
        event.callsign?.takeIf { it.isNotEmpty() }?.let { cs ->
            val contact = ByteArrayOutputStream().apply {
                appendString(this, field = 1, value = cs)
            }.toByteArray()
            appendTag(out, field = 6, wire = 2)
            appendVarint(out, contact.size.toULong())
            out.write(contact)
        }
        // 1: xmlDetail — stash remarks here so parsers that ignore
        // structured submessages still see them.
        if (event.remarks.isNotEmpty()) {
            val xml = "<remarks>${escape(event.remarks)}</remarks>"
            appendString(out, field = 1, value = xml)
        }
        return out.toByteArray()
    }

    // endregion

    // region Wire helpers ------------------------------------------------

    private fun appendTag(out: ByteArrayOutputStream, field: Int, wire: Int) {
        appendVarint(out, ((field shl 3) or (wire and 0x7)).toULong())
    }

    private fun appendVarint(out: ByteArrayOutputStream, value: ULong) {
        var v = value
        while (v >= 0x80uL) {
            out.write(((v and 0x7FuL).toInt()) or 0x80)
            v = v shr 7
        }
        out.write(v.toInt() and 0x7F)
    }

    private fun appendVarintField(out: ByteArrayOutputStream, field: Int, value: ULong) {
        appendTag(out, field = field, wire = 0)
        appendVarint(out, value)
    }

    private fun appendFixed32(out: ByteArrayOutputStream, value: UInt) {
        for (i in 0 until 4) {
            out.write(((value shr (8 * i)) and 0xFFu).toInt())
        }
    }

    private fun appendFixed64(out: ByteArrayOutputStream, value: ULong) {
        for (i in 0 until 8) {
            out.write(((value shr (8 * i)) and 0xFFuL).toInt())
        }
    }

    private fun appendDouble(out: ByteArrayOutputStream, field: Int, value: Double) {
        appendTag(out, field = field, wire = 1)
        appendFixed64(out, value.toRawBits().toULong())
    }

    private fun appendString(out: ByteArrayOutputStream, field: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        appendTag(out, field = field, wire = 2)
        appendVarint(out, bytes.size.toULong())
        out.write(bytes)
    }

    private fun millis(ms: Long): ULong = if (ms < 0) 0uL else ms.toULong()

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    // endregion
}
