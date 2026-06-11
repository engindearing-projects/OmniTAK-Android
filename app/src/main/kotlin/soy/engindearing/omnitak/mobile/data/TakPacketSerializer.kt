package soy.engindearing.omnitak.mobile.data

import java.io.ByteArrayOutputStream
import kotlin.math.roundToLong

/**
 * Serializes a [CoTEvent] into a Meshtastic portnum-72 TAKPacket (atak.proto).
 *
 * This is the Phase-2 replacement for [AtakPluginSerializer.serialize] on the TX
 * path. The TAKPacket format is the standard used by:
 *   - Stock Meshtastic ATAK Plugin app
 *   - Meshtastic phone-app with TAK role
 *   - TAK_Meshtastic_Gateway
 *
 * COMPATIBILITY TARGET: Meshtastic firmware PRIOR to PR #10105 (merged 2026-05-26),
 * which used unishox2 (default preset) for portnum-72 TAKPacket strings. #10105 dropped
 * unishox2 for a V2 format. We target the pre-#10105 installed base — the ATAK-plugin /
 * gateway / phone-app population deployed today. A future Phase 3 can add the V2 format.
 *
 * Schema (hand-rolled, no protobuf-javalite — consistent with project conventions):
 *   TAKPacket {
 *     is_compressed=1:varint, contact=2:LEN, group=3:LEN, status=4:LEN,
 *     pli=5:LEN, chat=6:LEN
 *   }
 *   Contact { callsign=1:LEN bytes, device_callsign=2:LEN bytes }
 *   Group   { role=1:varint, team=2:varint }
 *   Status  { battery=1:varint }
 *   PLI     { latitude_i=1:I32 sfixed32, longitude_i=2:I32 sfixed32,
 *             altitude=3:varint, speed=4:varint, course=5:varint }
 *   GeoChat { message=1:LEN, to=2:LEN, to_callsign=3:LEN }
 *
 * PLI packets: is_compressed=false — callsign/device_callsign as raw UTF-8.
 * Chat packets: is_compressed=true — callsign/device_callsign/message compressed
 *               via [Unishox2]; chat.to is always raw (matches gateway behaviour).
 *
 * Wire helpers reuse the same pattern as [AtakPluginSerializer].
 */
object TakPacketSerializer {

    /**
     * Build a TAKPacket payload for a PLI (position) event.
     *
     * @param event     The CoT event (must have valid lat/lon).
     * @param callsign  The human-readable callsign (contact.callsign).
     * @param deviceId  The unique device ID (contact.device_callsign / UID).
     * @param battery   Battery level 0-100.
     */
    fun serializePli(
        event: CoTEvent,
        callsign: String = event.callsign ?: event.uid,
        deviceId: String = event.uid,
        battery: Int = 0,
        speed: Int = 0,
        course: Int = 0,
    ): ByteArray {
        val out = ByteArrayOutputStream()

        // is_compressed=false → proto3 default, omit field 1 entirely so the
        // wire matches the stock Meshtastic ATAK Plugin / gateway output.

        // 2: contact
        val contactBytes = buildContact(callsign, deviceId, compressed = false)
        appendLenField(out, field = 2, bytes = contactBytes)

        // 3: group
        val team = Team.fromCotName(event.teamName)
        val role = MemberRole.fromCotName(event.teamRole).let {
            // Default to TeamMember if unspecified, matching gateway default
            if (it == MemberRole.Unspecifed) MemberRole.TeamMember else it
        }
        val groupBytes = buildGroup(role, team)
        appendLenField(out, field = 3, bytes = groupBytes)

        // 4: status
        val statusBytes = buildStatus(battery)
        appendLenField(out, field = 4, bytes = statusBytes)

        // 5: pli (oneof payload_variant)
        val latI = (event.lat * 1e7).roundToLong().toInt()
        val lonI = (event.lon * 1e7).roundToLong().toInt()
        val altitude = event.hae.toInt()
        val pliBytes = buildPli(latI, lonI, altitude, speed = speed, course = course)
        appendLenField(out, field = 5, bytes = pliBytes)

        return out.toByteArray()
    }

    /**
     * Build a TAKPacket payload for a GeoChat (b-t-f) event.
     *
     * @param event     The CoT event.
     * @param callsign  Sender callsign (will be Unishox2-compressed).
     * @param deviceId  Sender device ID / UID (will be Unishox2-compressed).
     * @param message   The chat message text (will be Unishox2-compressed).
     * @param to        The chat destination (raw — "All Chat Rooms" or UID).
     * @param battery   Battery level 0-100.
     */
    fun serializeChat(
        event: CoTEvent,
        callsign: String = event.callsign ?: event.uid,
        deviceId: String = event.uid,
        message: String = event.remarks,
        to: String = "All Chat Rooms",
        battery: Int = 0,
    ): ByteArray {
        val out = ByteArrayOutputStream()

        // 1: is_compressed = true (1)
        appendVarintField(out, field = 1, value = 1UL)

        // 2: contact (compressed)
        val contactBytes = buildContact(callsign, deviceId, compressed = true)
        appendLenField(out, field = 2, bytes = contactBytes)

        // 3: group
        val team = Team.fromCotName(event.teamName)
        val role = MemberRole.fromCotName(event.teamRole).let {
            if (it == MemberRole.Unspecifed) MemberRole.TeamMember else it
        }
        val groupBytes = buildGroup(role, team)
        appendLenField(out, field = 3, bytes = groupBytes)

        // 4: status
        val statusBytes = buildStatus(battery)
        appendLenField(out, field = 4, bytes = statusBytes)

        // 6: chat (oneof payload_variant) — message compressed, to raw
        val chatBytes = buildChat(message, to, compressed = true)
        appendLenField(out, field = 6, bytes = chatBytes)

        return out.toByteArray()
    }

    // region Sub-message builders -------------------------------------------

    private fun buildContact(callsign: String, deviceCallsign: String, compressed: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        val csBytes = if (compressed) Unishox2.compress(callsign) else callsign.toByteArray(Charsets.UTF_8)
        val dcBytes = if (compressed) Unishox2.compress(deviceCallsign) else deviceCallsign.toByteArray(Charsets.UTF_8)
        // 1: callsign (bytes)
        appendTag(out, field = 1, wire = 2)
        appendVarint(out, csBytes.size.toULong())
        out.write(csBytes)
        // 2: device_callsign (bytes)
        appendTag(out, field = 2, wire = 2)
        appendVarint(out, dcBytes.size.toULong())
        out.write(dcBytes)
        return out.toByteArray()
    }

    private fun buildGroup(role: MemberRole, team: Team): ByteArray {
        val out = ByteArrayOutputStream()
        // 1: role (varint)
        appendVarintField(out, field = 1, value = role.value.toULong())
        // 2: team (varint)
        appendVarintField(out, field = 2, value = team.value.toULong())
        return out.toByteArray()
    }

    private fun buildStatus(battery: Int): ByteArray {
        val out = ByteArrayOutputStream()
        // 1: battery (varint)
        appendVarintField(out, field = 1, value = battery.toULong())
        return out.toByteArray()
    }

    private fun buildPli(latI: Int, lonI: Int, altitude: Int, speed: Int, course: Int): ByteArray {
        val out = ByteArrayOutputStream()
        // 1: latitude_i  (sfixed32 / wire type 5)
        appendTag(out, field = 1, wire = 5)
        appendSFixed32(out, latI)
        // 2: longitude_i (sfixed32 / wire type 5)
        appendTag(out, field = 2, wire = 5)
        appendSFixed32(out, lonI)
        // 3: altitude (varint)
        if (altitude != 0) appendVarintField(out, field = 3, value = altitude.toULong())
        // 4: speed (varint)
        if (speed != 0) appendVarintField(out, field = 4, value = speed.toULong())
        // 5: course (varint)
        if (course != 0) appendVarintField(out, field = 5, value = course.toULong())
        return out.toByteArray()
    }

    private fun buildChat(message: String, to: String, compressed: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        val msgBytes = if (compressed) Unishox2.compress(message) else message.toByteArray(Charsets.UTF_8)
        val toBytes = to.toByteArray(Charsets.UTF_8) // to is always raw
        // 1: message (bytes)
        appendTag(out, field = 1, wire = 2)
        appendVarint(out, msgBytes.size.toULong())
        out.write(msgBytes)
        // 2: to (bytes)
        appendTag(out, field = 2, wire = 2)
        appendVarint(out, toBytes.size.toULong())
        out.write(toBytes)
        return out.toByteArray()
    }

    // endregion

    // region Wire helpers (mirroring AtakPluginSerializer) ------------------

    internal fun appendTag(out: ByteArrayOutputStream, field: Int, wire: Int) {
        appendVarint(out, ((field shl 3) or (wire and 0x7)).toULong())
    }

    internal fun appendVarint(out: ByteArrayOutputStream, value: ULong) {
        var v = value
        while (v >= 0x80uL) {
            out.write(((v and 0x7FuL).toInt()) or 0x80)
            v = v shr 7
        }
        out.write(v.toInt() and 0x7F)
    }

    internal fun appendVarintField(out: ByteArrayOutputStream, field: Int, value: ULong) {
        appendTag(out, field = field, wire = 0)
        appendVarint(out, value)
    }

    private fun appendLenField(out: ByteArrayOutputStream, field: Int, bytes: ByteArray) {
        appendTag(out, field = field, wire = 2)
        appendVarint(out, bytes.size.toULong())
        out.write(bytes)
    }

    /** sfixed32: 4-byte little-endian signed 32-bit integer. */
    private fun appendSFixed32(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }

    // endregion
}
