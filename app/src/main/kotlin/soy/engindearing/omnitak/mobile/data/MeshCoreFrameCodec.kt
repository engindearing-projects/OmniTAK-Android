package soy.engindearing.omnitak.mobile.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Encoder/decoder for the MeshCore **companion** BLE protocol.
 *
 * Ported from the TAK-MeshCore ATAK plugin's `BtConnectionManager.java`
 * (MeshCore companion firmware v1.x). Each companion frame is exactly one
 * BLE characteristic write (to the Nordic-UART RX char) or one notification
 * (from the TX char) — there is **no length prefix on the BLE path**; the
 * GATT layer delimits frames. So this codec works on whole frames: it
 * builds command byte arrays and parses notification byte arrays.
 *
 * v1 scope (companion command codes):
 *  - encode: APP_START 0x01, SEND_SELF_ADVERT 0x07, SET_ADVERT_LATLON 0x0E,
 *    SEND_TXT_MSG 0x02, GET_NEXT_MSG 0x0A, GET_BATTERY 0x14, DEVICE_QUERY 0x16.
 *  - decode (response/push codes): RESP_CONTACT_MSG 0x07, RESP_CHANNEL_MSG 0x08,
 *    RESP_BATTERY 0x0C, RESP_CODE_STATS 0x18, RESP_SELF_INFO 0x05, advert
 *    pushes 0x80 / 0x8A / contact 0x03, MESSAGES_WAITING 0x83, NO_MORE 0x0A.
 *
 * Multi-byte integers are little-endian (firmware native). Lat/Lon are
 * E6 fixed-point ints. All offsets match the reference exactly; where the
 * reference probes multiple firmware layouts we take the documented
 * companion-radio-upstream layout (the v1.16.0 our test rig runs).
 */
object MeshCoreFrameCodec {

    // region command codes ------------------------------------------------
    const val CMD_APP_START: Byte = 0x01
    const val CMD_SEND_TXT_MSG: Byte = 0x02
    const val CMD_SEND_SELF_ADVERT: Byte = 0x07
    const val CMD_SET_ADVERT_LATLON: Byte = 0x0E
    const val CMD_GET_NEXT_MSG: Byte = 0x0A
    const val CMD_GET_BATTERY: Byte = 0x14
    const val CMD_DEVICE_QUERY: Byte = 0x16
    const val CMD_GET_STATS: Byte = 0x38

    private const val TXT_TYPE_PLAIN: Byte = 0x00
    private const val DEVICE_QUERY_ARG: Byte = 0x03
    private const val STATS_TYPE_CORE: Byte = 0x00
    /** Companion app id the firmware expects in APP_START (mirrors the
     *  reference `meshcore-flutter`). The firmware doesn't validate it
     *  beyond presence, so any client identifier works. */
    private const val COMPANION_APP_ID = "meshcore-flutter"
    // endregion

    // region response / push codes ---------------------------------------
    const val RESP_SELF_INFO: Byte = 0x05
    const val RESP_CONTACT_MSG: Byte = 0x07
    const val RESP_CHANNEL_MSG: Byte = 0x08
    const val RESP_CONTACT_MSG_V3: Byte = 0x10
    const val RESP_CHANNEL_MSG_V3: Byte = 0x11
    const val RESP_DEVICE_INFO: Byte = 0x0D
    const val RESP_BATTERY: Byte = 0x0C
    const val RESP_CODE_STATS: Byte = 0x18
    const val RESP_NO_MORE_MSGS: Byte = 0x0A
    const val RESP_CODE_CONTACT: Byte = 0x03
    val PUSH_MESSAGES_WAITING: Byte = 0x83.toByte()
    val PUSH_CODE_ADVERT: Byte = 0x80.toByte()
    val PUSH_CODE_NEW_ADVERT: Byte = 0x8A.toByte()
    private const val ADV_TYPE_REPEATER = 0x02
    // endregion

    // ====================================================================
    // ENCODE
    // ====================================================================

    /**
     * APP_START — the companion handshake. Byte 0 is the code; bytes 1..7
     * are reserved (zero); the app id string follows at offset 8. The node
     * replies with a RESP_SELF_INFO (0x05) frame.
     */
    fun buildAppStart(appId: String = COMPANION_APP_ID): ByteArray {
        val app = appId.toByteArray(StandardCharsets.UTF_8)
        val out = ByteArray(8 + app.size)
        out[0] = CMD_APP_START
        System.arraycopy(app, 0, out, 8, app.size)
        return out
    }

    /** GET_NEXT_MSG — drains one queued inbound frame from the node. */
    fun buildGetNextMessage(): ByteArray = byteArrayOf(CMD_GET_NEXT_MSG)

    /** DEVICE_QUERY — asks the node for its device info (fw/contacts/etc). */
    fun buildDeviceQuery(): ByteArray = byteArrayOf(CMD_DEVICE_QUERY, DEVICE_QUERY_ARG)

    /** GET_BATTERY — node replies RESP_BATTERY (0x0C). */
    fun buildGetBattery(): ByteArray = byteArrayOf(CMD_GET_BATTERY)

    /** GET_STATS(core) — node replies RESP_CODE_STATS (0x18), also carries battery mv. */
    fun buildGetStats(): ByteArray = byteArrayOf(CMD_GET_STATS, STATS_TYPE_CORE)

    /** SEND_SELF_ADVERT — broadcasts this node's advert (incl. its set lat/lon). */
    fun buildSendSelfAdvert(): ByteArray = byteArrayOf(CMD_SEND_SELF_ADVERT)

    /**
     * SET_ADVERT_LATLON — stores the lat/lon/alt the node will broadcast in
     * its next self-advert. E6 fixed-point, little-endian. Returns null for
     * an out-of-range coordinate so the caller can skip the write.
     */
    fun buildSetAdvertLatLon(lat: Double, lon: Double, altMeters: Double = 0.0): ByteArray? {
        if (lat.isNaN() || lon.isNaN() || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) {
            return null
        }
        val latE6 = Math.round(lat * 1_000_000.0).toInt()
        val lonE6 = Math.round(lon * 1_000_000.0).toInt()
        val alt = if (altMeters.isNaN()) 0 else Math.round(altMeters).toInt()
        val out = ByteArray(13)
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(CMD_SET_ADVERT_LATLON)
            putInt(latE6)
            putInt(lonE6)
            putInt(alt)
        }
        return out
    }

    /**
     * SEND_TXT_MSG — a native pubkey-to-pubkey direct message. Layout:
     * `[0x02][txtType=plain][attempt=0][ts LE int][pubKeyPrefix(6)][utf8 text]`.
     * The recipient must be a contact on the node (firmware matches the first
     * 6 bytes of the pubkey). Returns null on an invalid pubkey prefix.
     */
    fun buildSendTxtMsg(
        pubKeyHex: String,
        text: String,
        nowSec: Int = (System.currentTimeMillis() / 1000L).toInt(),
    ): ByteArray? {
        val prefix = pubKeyPrefixBytes(pubKeyHex, 6) ?: return null
        val msg = text.toByteArray(StandardCharsets.UTF_8)
        val buf = ByteBuffer.allocate(13 + msg.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(CMD_SEND_TXT_MSG)
        buf.put(TXT_TYPE_PLAIN)
        buf.put(0x00) // attempt
        buf.putInt(nowSec)
        buf.put(prefix)
        buf.put(msg)
        return buf.array()
    }

    // ====================================================================
    // DECODE
    // ====================================================================

    /** Result of decoding one inbound companion frame. */
    sealed interface Decoded {
        /** A contact/advert with (optionally) an advertised position. */
        data class Contact(
            val node: MeshNode,
            val isRepeater: Boolean,
        ) : Decoded

        /** This node's own self-info (after APP_START). */
        data class SelfInfo(
            val pubKeyHex: String,
            val nodeName: String,
            val lat: Double?,
            val lon: Double?,
        ) : Decoded

        /** A native pubkey-to-pubkey direct message. */
        data class ContactMessage(
            val senderPubKeyPrefixHex: String,
            val text: String,
        ) : Decoded

        /** A channel (group) message. */
        data class ChannelMessage(
            val channelIndex: Int,
            val text: String,
        ) : Decoded

        /** Battery reading in millivolts (+ derived percent). */
        data class Battery(
            val milliVolts: Int,
            val percent: Int,
        ) : Decoded

        /** The node has queued messages — caller should GET_NEXT_MSG. */
        data object MessagesWaiting : Decoded

        /** No more queued messages. */
        data object NoMoreMessages : Decoded

        /**
         * RESP_DEVICE_INFO (0x0D) — build/firmware metadata from the companion
         * radio. Parsed from [MeshCoreFrameCodec.parseDeviceInfo].
         */
        data class DeviceInfo(
            val fwCode: Int,
            val maxContacts: Int,
            val maxChannels: Int,
            val manufacturer: String,
            val version: String,
        ) : Decoded

        /** A frame we recognise the code of but don't model in v1. */
        data class Unhandled(val code: Int) : Decoded
    }

    /**
     * Decode one inbound companion frame (a single BLE TX notification).
     * Returns null for an empty/degenerate frame.
     */
    fun decode(pkt: ByteArray?, nowMs: Long = System.currentTimeMillis()): Decoded? {
        if (pkt == null || pkt.isEmpty()) return null
        return when (val code = pkt[0]) {
            PUSH_MESSAGES_WAITING -> Decoded.MessagesWaiting
            RESP_NO_MORE_MSGS -> Decoded.NoMoreMessages
            RESP_SELF_INFO -> parseSelfInfo(pkt) ?: Decoded.Unhandled(code.toInt() and 0xFF)
            RESP_DEVICE_INFO -> parseDeviceInfo(pkt) ?: Decoded.Unhandled(code.toInt() and 0xFF)
            RESP_BATTERY -> parseBattery(pkt)
            RESP_CODE_STATS -> parseStatsBattery(pkt)
            PUSH_CODE_NEW_ADVERT, PUSH_CODE_ADVERT, RESP_CODE_CONTACT ->
                parseAdvertContact(pkt, nowMs) ?: Decoded.Unhandled(code.toInt() and 0xFF)
            RESP_CONTACT_MSG -> parseContactMessage(pkt, v3 = false)
            RESP_CONTACT_MSG_V3 -> parseContactMessage(pkt, v3 = true)
            RESP_CHANNEL_MSG -> parseChannelMessage(pkt, v3 = false)
            RESP_CHANNEL_MSG_V3 -> parseChannelMessage(pkt, v3 = true)
            else -> Decoded.Unhandled(code.toInt() and 0xFF)
        }
    }

    /**
     * Advert/contact frame. Layout (companion-radio upstream, ≥144 bytes):
     *  [0]=code [1..32]=pubkey [33]=advType [100..131]=name(null-padded)
     *  [132..135]=advert ts (LE) [136..139]=latE6 (LE) [140..143]=lonE6 (LE)
     */
    private fun parseAdvertContact(pkt: ByteArray, nowMs: Long): Decoded.Contact? {
        if (pkt.size < 144) return null
        val type = pkt[33].toInt() and 0xFF
        val pubKeyHex = bytesToHex(pkt, 1, 32).ifEmpty { return null }

        val rawName = String(pkt, 100, 32, StandardCharsets.UTF_8)
        val nul = rawName.indexOf('\u0000')
        var name = (if (nul >= 0) rawName.substring(0, nul) else rawName).trim()
        if (name.isEmpty()) name = "MeshCore " + "%08X".format(nodeIdFromPubKey(pubKeyHex).toInt())

        val bb = ByteBuffer.wrap(pkt).order(ByteOrder.LITTLE_ENDIAN)
        val tsSec = bb.getInt(132).toLong() and 0xFFFFFFFFL
        val latE6 = bb.getInt(136)
        val lonE6 = bb.getInt(140)
        val hasPosition = !(latE6 == 0 && lonE6 == 0)
        val lat = latE6 / 1_000_000.0
        val lon = lonE6 / 1_000_000.0

        val nodeId = nodeIdFromPubKey(pubKeyHex)
        val position = if (hasPosition && validLatLon(lat, lon)) {
            MeshPosition(lat = lat, lon = lon, altitudeM = null)
        } else {
            null
        }
        // Prefer the advert timestamp (node clock) when present; else now.
        val lastHeard = if (tsSec in 1..4_294_967_295L) tsSec else nowMs / 1000L
        val node = MeshNode(
            id = nodeId,
            shortName = "%08X".format(nodeId.toInt()),
            longName = name,
            position = position,
            lastHeardEpoch = lastHeard,
        )
        return Decoded.Contact(node, isRepeater = type == ADV_TYPE_REPEATER)
    }

    /**
     * RESP_SELF_INFO. Layout (companion-radio upstream, ≥58 bytes):
     *  [0]=code [1]=advType [2]=txPower [3]=maxTx [4..35]=pubkey
     *  [36..39]=latE6 [40..43]=lonE6 ... [58..]=node name
     */
    private fun parseSelfInfo(pkt: ByteArray): Decoded.SelfInfo? {
        if (pkt.size < 58) return null
        return runCatching {
            val bb = ByteBuffer.wrap(pkt).order(ByteOrder.LITTLE_ENDIAN)
            val latE6 = bb.getInt(36)
            val lonE6 = bb.getInt(40)
            val pubKeyHex = bytesToHex(pkt, 4, 32)
            val name = if (pkt.size > 58) {
                String(pkt, 58, pkt.size - 58, StandardCharsets.UTF_8).trim()
            } else {
                ""
            }
            val lat = latE6 / 1_000_000.0
            val lon = lonE6 / 1_000_000.0
            val valid = validLatLon(lat, lon) && !(latE6 == 0 && lonE6 == 0)
            Decoded.SelfInfo(
                pubKeyHex = pubKeyHex,
                nodeName = name,
                lat = if (valid) lat else null,
                lon = if (valid) lon else null,
            )
        }.getOrNull()
    }

    /**
     * RESP_DEVICE_INFO (0x0D) — firmware/build metadata.
     * Layout (≥4 bytes, full form ≥77 bytes):
     *  [0]=0x0D [1]=fwCode [2]=maxContacts/2 [3]=maxChannels
     *  [20..59]=manufacturer (c-string, 40 bytes) [60..79]=version (c-string, 20 bytes)
     * Matches the reference `logDeviceInfo` in BtConnectionManager.java.
     */
    private fun parseDeviceInfo(pkt: ByteArray): Decoded.DeviceInfo? {
        if (pkt.size < 4) return null
        return runCatching {
            val fwCode = pkt[1].toInt() and 0xFF
            val maxContactsHalf = pkt[2].toInt() and 0xFF
            val maxChannels = pkt[3].toInt() and 0xFF
            val manufacturer = if (pkt.size >= 77) decodeCString(pkt, 20, 40) else ""
            val version = if (pkt.size >= 77) decodeCString(pkt, 60, 20) else ""
            Decoded.DeviceInfo(
                fwCode = fwCode,
                maxContacts = maxContactsHalf * 2,
                maxChannels = maxChannels,
                manufacturer = manufacturer,
                version = version,
            )
        }.getOrNull()
    }

    /** Decode a null-terminated C-string from [src] at [off] for up to [maxLen] bytes. */
    private fun decodeCString(src: ByteArray, off: Int, maxLen: Int): String {
        if (off < 0 || maxLen <= 0 || src.size <= off) return ""
        val end = minOf(src.size, off + maxLen)
        var n = off
        while (n < end && src[n] != 0.toByte()) n++
        return String(src, off, n - off, StandardCharsets.UTF_8).trim()
    }

    /** RESP_BATTERY: `[0x0C][mv lo][mv hi]`. */
    private fun parseBattery(pkt: ByteArray): Decoded? {
        if (pkt.size < 3) return Decoded.Unhandled(RESP_BATTERY.toInt() and 0xFF)
        val mv = (pkt[1].toInt() and 0xFF) or ((pkt[2].toInt() and 0xFF) shl 8)
        return batteryOrNull(mv) ?: Decoded.Unhandled(RESP_BATTERY.toInt() and 0xFF)
    }

    /** RESP_CODE_STATS(core): `[0x18][type=0x00][mv lo][mv hi]...`. */
    private fun parseStatsBattery(pkt: ByteArray): Decoded? {
        if (pkt.size < 4 || (pkt[1].toInt() and 0xFF) != (STATS_TYPE_CORE.toInt() and 0xFF)) {
            return Decoded.Unhandled(RESP_CODE_STATS.toInt() and 0xFF)
        }
        val mv = (pkt[2].toInt() and 0xFF) or ((pkt[3].toInt() and 0xFF) shl 8)
        return batteryOrNull(mv) ?: Decoded.Unhandled(RESP_CODE_STATS.toInt() and 0xFF)
    }

    private fun batteryOrNull(mv: Int): Decoded.Battery? {
        val pct = batteryMvToPercent(mv)
        if (pct < 0) return null
        return Decoded.Battery(mv, pct)
    }

    /**
     * RESP_CONTACT_MSG. Sender pubkey prefix (6B) at offset 1 (v1) or 4 (v3).
     * txtType byte at 8 (v1) / 11 (v3); text at 13 (v1) / 16 (v3), +4 when
     * txtType == 2 (timestamped). Plain UTF-8 body.
     */
    private fun parseContactMessage(pkt: ByteArray, v3: Boolean): Decoded? {
        val prefixOff = if (v3) 4 else 1
        val txtTypeIndex = if (v3) 11 else 8
        var off = if (v3) 16 else 13
        if (pkt.size < prefixOff + 6 || pkt.size <= txtTypeIndex) {
            return Decoded.Unhandled(pkt[0].toInt() and 0xFF)
        }
        val senderPrefix = bytesToHex(pkt, prefixOff, 6)
        if (pkt[txtTypeIndex].toInt() == 2) off += 4
        if (pkt.size < off) return Decoded.Unhandled(pkt[0].toInt() and 0xFF)
        val text = String(pkt, off, pkt.size - off, StandardCharsets.UTF_8)
        return Decoded.ContactMessage(senderPrefix, text)
    }

    /**
     * RESP_CHANNEL_MSG. Channel index at offset 1 (v1) / 4 (v3); text at
     * offset 8 (v1) / 11 (v3).
     */
    private fun parseChannelMessage(pkt: ByteArray, v3: Boolean): Decoded? {
        val idxOff = if (v3) 4 else 1
        val textOff = if (v3) 11 else 8
        if (pkt.size <= textOff) return Decoded.Unhandled(pkt[0].toInt() and 0xFF)
        val idx = (pkt[idxOff].toInt() and 0xFF).let { if (it in 0..7) it else 0 }
        val text = String(pkt, textOff, pkt.size - textOff, StandardCharsets.UTF_8)
        return Decoded.ChannelMessage(idx, text)
    }

    // ====================================================================
    // HELPERS
    // ====================================================================

    /**
     * MeshCore identifies a node by its 32-byte public key. We collapse that
     * to a 48-bit id (the first **6** bytes, big-endian) so it slots into the
     * framework-neutral [MeshNode.id]/[CoTEvent] machinery while matching the
     * 6-byte prefix the firmware uses to key contacts. A [Long] holds 48 bits
     * without overflow. The full pubkey hex is kept in the contact name/remarks.
     *
     * CoT UID format: `MESHCORE-<12 uppercase hex>` (6 bytes = 12 hex chars).
     */
    fun nodeIdFromPubKey(pubKeyHex: String): Long {
        val hex = pubKeyHex.trim()
        if (hex.length < 12) return 0L
        return runCatching {
            ((hex.substring(0, 2).toLong(16) shl 40) or
                (hex.substring(2, 4).toLong(16) shl 32) or
                (hex.substring(4, 6).toLong(16) shl 24) or
                (hex.substring(6, 8).toLong(16) shl 16) or
                (hex.substring(8, 10).toLong(16) shl 8) or
                hex.substring(10, 12).toLong(16))
        }.getOrDefault(0L)
    }

    /** Convert battery millivolts to a 0..100 percent (LiPo 3.3–4.2 V), or -1. */
    fun batteryMvToPercent(mv: Int): Int {
        if (mv <= 0) return -1
        val minMv = 3300
        val maxMv = 4200
        if (mv <= minMv) return 0
        if (mv >= maxMv) return 100
        return Math.round(100f * (mv - minMv) / (maxMv - minMv))
    }

    /** First [byteCount] bytes of a hex pubkey as raw bytes, or null. */
    fun pubKeyPrefixBytes(pubKeyHex: String?, byteCount: Int): ByteArray? {
        if (pubKeyHex == null) return null
        val hex = pubKeyHex.trim()
        if (hex.length < byteCount * 2) return null
        return runCatching {
            ByteArray(byteCount) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }.getOrNull()
    }

    private fun validLatLon(lat: Double, lon: Double): Boolean =
        !lat.isNaN() && !lon.isNaN() &&
            lat in -90.0..90.0 && lon in -180.0..180.0 &&
            !(Math.abs(lat) < 0.000001 && Math.abs(lon) < 0.000001)

    private fun bytesToHex(src: ByteArray, off: Int, len: Int): String {
        if (off < 0 || len <= 0 || src.size < off + len) return ""
        val sb = StringBuilder(len * 2)
        for (i in off until off + len) {
            val b = src[i].toInt() and 0xFF
            if (b < 0x10) sb.append('0')
            sb.append(Integer.toHexString(b))
        }
        return sb.toString()
    }
}
