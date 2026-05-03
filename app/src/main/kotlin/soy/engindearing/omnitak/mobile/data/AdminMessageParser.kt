package soy.engindearing.omnitak.mobile.data

import soy.engindearing.omnitak.mobile.data.MeshtasticProtoParser.readLengthDelimited
import soy.engindearing.omnitak.mobile.data.MeshtasticProtoParser.readString
import soy.engindearing.omnitak.mobile.data.MeshtasticProtoParser.readVarint
import soy.engindearing.omnitak.mobile.data.MeshtasticProtoParser.skipField

/**
 * GAP-109a (read-back) — decode the AdminMessage `get_*_response`
 * payloads that the radio sends after we ask it for its current config.
 *
 * Counterpart to [AdminMessageSerializer]. Only decodes the response
 * variants we actually surface in `MeshDeviceSettingsScreen`:
 *
 * - `get_owner_response`   (field 4)  — long_name, short_name
 * - `get_channel_response` (field 2)  — Channel { settings.name }, role
 * - `get_config_response`  (field 6)  — Config { device | position | lora }
 *
 * Field numbers come from canonical Meshtastic firmware `admin.proto`.
 */
sealed interface AdminResponse {
    data class Owner(val longName: String, val shortName: String) : AdminResponse
    data class DeviceConfig(val role: MeshRole?) : AdminResponse
    data class PositionConfig(val broadcastSecs: Int) : AdminResponse
    data class LoraConfig(val preset: MeshChannelPreset?) : AdminResponse
    data class Channel(val index: Int, val name: String, val role: Int) : AdminResponse {
        /** firmware Channel.Role enum: DISABLED=0, PRIMARY=1, SECONDARY=2. */
        val isPrimary: Boolean get() = role == 1
        val isSecondary: Boolean get() = role == 2
        val isDisabled: Boolean get() = role == 0
    }
}

object AdminMessageParser {

    /** Try to parse an AdminMessage byte buffer. Returns the recognised
     *  response type or null when the message isn't one of the four we
     *  care about (eg. `set_*` echoes, future fields). */
    fun parse(bytes: ByteArray): AdminResponse? {
        var idx = 0
        while (idx < bytes.size) {
            val (tag, afterTag) = readVarint(bytes, idx) ?: return null
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x7UL).toInt()
            idx = afterTag
            when (field) {
                4 -> { // get_owner_response (User submessage)
                    if (wire != 2) { idx = skipField(bytes, idx, wire); continue }
                    val sub = readLengthDelimited(bytes, idx) ?: return null
                    val (sn, ln) = parseUser(sub.first)
                    return AdminResponse.Owner(longName = ln, shortName = sn)
                }
                2 -> { // get_channel_response (Channel submessage)
                    if (wire != 2) { idx = skipField(bytes, idx, wire); continue }
                    val sub = readLengthDelimited(bytes, idx) ?: return null
                    return parseChannel(sub.first)
                }
                6 -> { // get_config_response (Config submessage)
                    if (wire != 2) { idx = skipField(bytes, idx, wire); continue }
                    val sub = readLengthDelimited(bytes, idx) ?: return null
                    return parseConfig(sub.first)
                }
                else -> idx = skipField(bytes, idx, wire)
            }
        }
        return null
    }

    /** Public — Config submessage arrives at FromRadio.field=5 (canonical
     *  Meshtastic mesh.proto). Same byte layout as inside an
     *  `AdminMessage.get_config_response`. Reused by [MeshtasticProtoParser]
     *  to decode the post-want_config_id config dump. */
    fun parseConfigPublic(bytes: ByteArray): AdminResponse? = parseConfig(bytes)

    /** Public — Channel submessage arrives at FromRadio.field=10
     *  (canonical Meshtastic mesh.proto). Same byte layout as inside
     *  `AdminMessage.get_channel_response`. */
    fun parseChannelPublic(bytes: ByteArray): AdminResponse.Channel = parseChannel(bytes)

    /** Config oneof:
     *   1 device (DeviceConfig), 2 position (PositionConfig),
     *   3 power, 4 network, 5 display, 6 lora (LoRaConfig), 7 bluetooth */
    private fun parseConfig(bytes: ByteArray): AdminResponse? {
        var idx = 0
        while (idx < bytes.size) {
            val (tag, afterTag) = readVarint(bytes, idx) ?: return null
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x7UL).toInt()
            idx = afterTag
            when (field) {
                1 -> {
                    if (wire != 2) { idx = skipField(bytes, idx, wire); continue }
                    val sub = readLengthDelimited(bytes, idx) ?: return null
                    return parseDeviceConfig(sub.first)
                }
                2 -> {
                    if (wire != 2) { idx = skipField(bytes, idx, wire); continue }
                    val sub = readLengthDelimited(bytes, idx) ?: return null
                    return parsePositionConfig(sub.first)
                }
                6 -> {
                    if (wire != 2) { idx = skipField(bytes, idx, wire); continue }
                    val sub = readLengthDelimited(bytes, idx) ?: return null
                    return parseLoraConfig(sub.first)
                }
                else -> idx = skipField(bytes, idx, wire)
            }
        }
        return null
    }

    /** DeviceConfig.role = field 1 (varint enum). */
    private fun parseDeviceConfig(bytes: ByteArray): AdminResponse.DeviceConfig {
        var idx = 0
        var role: MeshRole? = null
        while (idx < bytes.size) {
            val (tag, afterTag) = readVarint(bytes, idx) ?: break
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x7UL).toInt()
            idx = afterTag
            if (field == 1 && wire == 0) {
                val (v, after) = readVarint(bytes, idx) ?: break
                role = roleFromOrdinal(v.toInt())
                idx = after
            } else {
                idx = skipField(bytes, idx, wire)
            }
        }
        return AdminResponse.DeviceConfig(role = role)
    }

    /** PositionConfig.position_broadcast_secs = field 4 (varint). */
    private fun parsePositionConfig(bytes: ByteArray): AdminResponse.PositionConfig {
        var idx = 0
        var secs = 0
        while (idx < bytes.size) {
            val (tag, afterTag) = readVarint(bytes, idx) ?: break
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x7UL).toInt()
            idx = afterTag
            if (field == 4 && wire == 0) {
                val (v, after) = readVarint(bytes, idx) ?: break
                secs = v.toInt()
                idx = after
            } else {
                idx = skipField(bytes, idx, wire)
            }
        }
        return AdminResponse.PositionConfig(broadcastSecs = secs)
    }

    /** LoRaConfig.modem_preset = field 2 (varint enum). */
    private fun parseLoraConfig(bytes: ByteArray): AdminResponse.LoraConfig {
        var idx = 0
        var preset: MeshChannelPreset? = null
        while (idx < bytes.size) {
            val (tag, afterTag) = readVarint(bytes, idx) ?: break
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x7UL).toInt()
            idx = afterTag
            if (field == 2 && wire == 0) {
                val (v, after) = readVarint(bytes, idx) ?: break
                preset = presetFromOrdinal(v.toInt())
                idx = after
            } else {
                idx = skipField(bytes, idx, wire)
            }
        }
        return AdminResponse.LoraConfig(preset = preset)
    }

    /** Channel { 1 index (varint), 2 settings (ChannelSettings sub), 3 role (varint enum) }. */
    private fun parseChannel(bytes: ByteArray): AdminResponse.Channel {
        var idx = 0
        var index = 0
        var name = ""
        var role = 0
        while (idx < bytes.size) {
            val (tag, afterTag) = readVarint(bytes, idx) ?: break
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x7UL).toInt()
            idx = afterTag
            when (field) {
                1 -> {
                    if (wire != 0) { idx = skipField(bytes, idx, wire); continue }
                    val (v, after) = readVarint(bytes, idx) ?: break
                    index = v.toInt(); idx = after
                }
                2 -> {
                    if (wire != 2) { idx = skipField(bytes, idx, wire); continue }
                    val sub = readLengthDelimited(bytes, idx) ?: break
                    name = parseChannelSettingsName(sub.first)
                    idx = sub.second
                }
                3 -> {
                    if (wire != 0) { idx = skipField(bytes, idx, wire); continue }
                    val (v, after) = readVarint(bytes, idx) ?: break
                    role = v.toInt(); idx = after
                }
                else -> idx = skipField(bytes, idx, wire)
            }
        }
        return AdminResponse.Channel(index = index, name = name, role = role)
    }

    /** ChannelSettings.name = field 3 (string). */
    private fun parseChannelSettingsName(bytes: ByteArray): String {
        var idx = 0
        var name = ""
        while (idx < bytes.size) {
            val (tag, afterTag) = readVarint(bytes, idx) ?: break
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x7UL).toInt()
            idx = afterTag
            if (field == 3 && wire == 2) {
                val (s, after) = readString(bytes, idx) ?: break
                name = s; idx = after
            } else {
                idx = skipField(bytes, idx, wire)
            }
        }
        return name
    }

    /** User submessage: 2 long_name, 3 short_name. Returns (short, long). */
    private fun parseUser(bytes: ByteArray): Pair<String, String> {
        var idx = 0
        var shortName = ""
        var longName = ""
        while (idx < bytes.size) {
            val (tag, afterTag) = readVarint(bytes, idx) ?: break
            val field = (tag shr 3).toInt()
            val wire = (tag and 0x7UL).toInt()
            idx = afterTag
            when (field) {
                2 -> { // long_name
                    if (wire != 2) { idx = skipField(bytes, idx, wire); continue }
                    val (s, after) = readString(bytes, idx) ?: break
                    longName = s; idx = after
                }
                3 -> { // short_name
                    if (wire != 2) { idx = skipField(bytes, idx, wire); continue }
                    val (s, after) = readString(bytes, idx) ?: break
                    shortName = s; idx = after
                }
                else -> idx = skipField(bytes, idx, wire)
            }
        }
        return shortName to longName
    }

    /** Inverse of [AdminMessageSerializer.roleProtoOrdinal]. */
    private fun roleFromOrdinal(ordinal: Int): MeshRole? = when (ordinal) {
        0 -> MeshRole.CLIENT
        1 -> MeshRole.CLIENT_MUTE
        2 -> MeshRole.ROUTER
        3 -> MeshRole.ROUTER_CLIENT
        4 -> MeshRole.REPEATER
        5 -> MeshRole.TRACKER
        6 -> MeshRole.SENSOR
        7 -> MeshRole.TAK
        8 -> MeshRole.CLIENT_HIDDEN
        9 -> MeshRole.LOST_AND_FOUND
        10 -> MeshRole.TAK_TRACKER
        else -> null
    }

    /** Inverse of [AdminMessageSerializer.presetProtoOrdinal]. */
    private fun presetFromOrdinal(ordinal: Int): MeshChannelPreset? = when (ordinal) {
        0 -> MeshChannelPreset.LONG_FAST
        1 -> MeshChannelPreset.LONG_SLOW
        2 -> MeshChannelPreset.VERY_LONG_SLOW
        3 -> MeshChannelPreset.MEDIUM_SLOW
        4 -> MeshChannelPreset.MEDIUM_FAST
        5 -> MeshChannelPreset.SHORT_SLOW
        6 -> MeshChannelPreset.SHORT_FAST
        8 -> MeshChannelPreset.SHORT_TURBO
        else -> null
    }
}
