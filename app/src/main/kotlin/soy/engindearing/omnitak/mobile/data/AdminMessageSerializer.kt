package soy.engindearing.omnitak.mobile.data

import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * GAP-109a — write Meshtastic device settings via admin-port (portnum 6).
 *
 * Hand-rolled protobuf encoders for the four AdminMessage payload types
 * the Device Settings screen uses:
 *
 * - `set_owner`     (field 32) — long name, short name
 * - `set_config`    (field 34) — DeviceConfig.role
 * - `set_config`    (field 34) — PositionConfig.position_broadcast_secs
 * - `set_channel`   (field 33) — Channel 0 name + LoRaConfig modem preset
 *
 * Each call returns a fully-framed `ToRadio` byte buffer ready to push
 * over the existing transports
 * ([MeshtasticTcpClient.sendBytes] / [MeshtasticBleClient.sendToRadio]).
 *
 * Field numbers below come from the canonical Meshtastic firmware
 * `protobufs/admin.proto`, `config.proto`, `mesh.proto`, and
 * `channel.proto`. The list of constants we need is small enough to
 * inline; pulling `protobuf-javalite` for this would mean adding a
 * Gradle plugin and regenerating types every time Meshtastic bumps a
 * field, which the rest of the codebase has deliberately avoided
 * (see [MeshtasticProtoParser] / [AtakPluginSerializer]).
 *
 * **Wire helpers are duplicated here on purpose** — extracting them
 * into a shared `ProtoWire` object would be a fine follow-up but
 * touches three call sites and is out of scope for this slice.
 */
object AdminMessageSerializer {

    /** Meshtastic portnum for AdminMessage payloads on the local radio. */
    private const val PORTNUM_ADMIN_APP: ULong = 6UL

    // region Public builders --------------------------------------------

    /**
     * Build a `ToRadio { packet { decoded { portnum = ADMIN_APP, payload = AdminMessage{ set_owner } } } }`.
     * Sets the radio's display name (long + short) so the operator's
     * callsign matches everywhere they look.
     */
    fun buildSetOwner(longName: String, shortName: String): ByteArray {
        val owner = encodeOwner(longName = longName, shortName = shortName)
        // AdminMessage.set_owner = field 32, wire type 2 (length-delimited submessage).
        val admin = ByteArrayOutputStream().apply {
            appendTag(this, field = 32, wire = 2)
            appendVarint(this, owner.size.toULong())
            write(owner)
        }.toByteArray()
        return wrapToRadio(admin)
    }

    /**
     * Build a ToRadio with `AdminMessage { set_config { device { role = ... } } }`.
     * Only sets the role — the practitioner-headline knob.
     */
    fun buildSetDeviceRole(role: MeshRole): ByteArray {
        // DeviceConfig.role = field 1, varint of the proto-enum ordinal.
        val deviceConfig = ByteArrayOutputStream().apply {
            appendVarintField(this, field = 1, value = roleProtoOrdinal(role).toULong())
        }.toByteArray()

        // Config.device = field 1, wire type 2 (oneof submessage).
        val config = ByteArrayOutputStream().apply {
            appendTag(this, field = 1, wire = 2)
            appendVarint(this, deviceConfig.size.toULong())
            write(deviceConfig)
        }.toByteArray()

        // AdminMessage.set_config = field 34, wire type 2.
        val admin = ByteArrayOutputStream().apply {
            appendTag(this, field = 34, wire = 2)
            appendVarint(this, config.size.toULong())
            write(config)
        }.toByteArray()
        return wrapToRadio(admin)
    }

    /**
     * Build a ToRadio with `AdminMessage { set_config { position { position_broadcast_secs = N } } }`.
     * Headline practitioner ask — operator-controlled PLI cadence.
     */
    fun buildSetPositionBroadcastSecs(secs: Int): ByteArray {
        val safe = secs.coerceIn(0, 24 * 60 * 60).toULong()
        // PositionConfig.position_broadcast_secs = field 4, varint.
        val positionConfig = ByteArrayOutputStream().apply {
            appendVarintField(this, field = 4, value = safe)
        }.toByteArray()
        // Config.position = field 2, wire type 2.
        val config = ByteArrayOutputStream().apply {
            appendTag(this, field = 2, wire = 2)
            appendVarint(this, positionConfig.size.toULong())
            write(positionConfig)
        }.toByteArray()
        // AdminMessage.set_config = field 34.
        val admin = ByteArrayOutputStream().apply {
            appendTag(this, field = 34, wire = 2)
            appendVarint(this, config.size.toULong())
            write(config)
        }.toByteArray()
        return wrapToRadio(admin)
    }

    /**
     * Build a ToRadio with `AdminMessage { set_channel { settings { name, ... } } }`
     * for channel index 0. PSK is left at the firmware-default for the
     * preset; we only set the human-readable name. Preset goes through
     * `set_config { lora { use_preset = true, modem_preset = ... } }`
     * — see [buildSetLoraPreset]. Two messages because Meshtastic
     * splits channel and modem config across two protobuf submessages.
     */
    fun buildSetChannel0Name(name: String): ByteArray {
        // ChannelSettings.name = field 3, string.
        val settings = ByteArrayOutputStream().apply {
            appendString(this, field = 3, value = name)
        }.toByteArray()
        // Channel.index = 1 (varint, default 0 means primary), settings = field 2.
        val channel = ByteArrayOutputStream().apply {
            // Index 0 — the primary channel.
            appendVarintField(this, field = 1, value = 0UL)
            // Settings submessage at field 2.
            appendTag(this, field = 2, wire = 2)
            appendVarint(this, settings.size.toULong())
            write(settings)
            // Channel.role = field 3, varint. PRIMARY = 1.
            appendVarintField(this, field = 3, value = 1UL)
        }.toByteArray()
        // AdminMessage.set_channel = field 33.
        val admin = ByteArrayOutputStream().apply {
            appendTag(this, field = 33, wire = 2)
            appendVarint(this, channel.size.toULong())
            write(channel)
        }.toByteArray()
        return wrapToRadio(admin)
    }

    // region Read requests ----------------------------------------------

    /** AdminMessage.get_owner_request = field 3 (bool). */
    fun buildGetOwnerRequest(): ByteArray {
        val admin = ByteArrayOutputStream().apply {
            appendVarintField(this, field = 3, value = 1UL)
        }.toByteArray()
        return wrapToRadio(admin)
    }

    /**
     * AdminMessage.get_config_request = field 5 (varint enum, ConfigType).
     * Values: DEVICE=0, POSITION=1, POWER=2, NETWORK=3, DISPLAY=4, LORA=5,
     * BLUETOOTH=6, SECURITY=7, SESSIONKEY=8, DEVICEUI=9.
     */
    fun buildGetConfigRequest(configType: Int): ByteArray {
        val admin = ByteArrayOutputStream().apply {
            appendVarintField(this, field = 5, value = configType.toULong())
        }.toByteArray()
        return wrapToRadio(admin)
    }

    /** AdminMessage.get_channel_request = field 1 (varint, 1-based channel index). */
    fun buildGetChannelRequest(channelIndex: Int): ByteArray {
        val admin = ByteArrayOutputStream().apply {
            // Index in get_channel_request is 1-based; channel 0 is requested as 1.
            val zeroBased = channelIndex.coerceAtLeast(0)
            appendVarintField(this, field = 1, value = (zeroBased + 1).toULong())
        }.toByteArray()
        return wrapToRadio(admin)
    }

    // endregion

    /** Build `set_config { lora { use_preset = true, modem_preset = ... } }`. */
    fun buildSetLoraPreset(preset: MeshChannelPreset): ByteArray {
        // LoRaConfig.use_preset = field 1 (bool), modem_preset = field 2 (enum).
        val loraConfig = ByteArrayOutputStream().apply {
            appendVarintField(this, field = 1, value = 1UL)
            appendVarintField(this, field = 2, value = presetProtoOrdinal(preset).toULong())
        }.toByteArray()
        // Config.lora = field 6.
        val config = ByteArrayOutputStream().apply {
            appendTag(this, field = 6, wire = 2)
            appendVarint(this, loraConfig.size.toULong())
            write(loraConfig)
        }.toByteArray()
        // AdminMessage.set_config = field 34.
        val admin = ByteArrayOutputStream().apply {
            appendTag(this, field = 34, wire = 2)
            appendVarint(this, config.size.toULong())
            write(config)
        }.toByteArray()
        return wrapToRadio(admin)
    }

    // endregion

    // region Private encoders -------------------------------------------

    /** Owner / User submessage. id is left blank — the firmware keeps
     *  whatever the radio already has. macaddr / hw_model also untouched. */
    private fun encodeOwner(longName: String, shortName: String): ByteArray {
        val out = ByteArrayOutputStream()
        // 2: long_name (string, max ~40 chars)
        appendString(out, field = 2, value = longName.take(39))
        // 3: short_name (string, max 4 chars per firmware constraint)
        appendString(out, field = 3, value = shortName.take(4))
        return out.toByteArray()
    }

    /**
     * Map [MeshRole] to the firmware enum ordinal. Order **must**
     * match the canonical `Config_DeviceConfig_Role` enum; this is the
     * wire format. If Meshtastic reshuffles the enum we have to
     * follow them — keep this list in sync with `config.proto`.
     */
    private fun roleProtoOrdinal(role: MeshRole): Int = when (role) {
        MeshRole.CLIENT -> 0
        MeshRole.CLIENT_MUTE -> 1
        MeshRole.ROUTER -> 2
        MeshRole.ROUTER_CLIENT -> 3 // marked deprecated in newer firmware, still accepted
        MeshRole.REPEATER -> 4
        MeshRole.TRACKER -> 5
        MeshRole.SENSOR -> 6
        MeshRole.TAK -> 7
        MeshRole.CLIENT_HIDDEN -> 8
        MeshRole.LOST_AND_FOUND -> 9
        MeshRole.TAK_TRACKER -> 10
    }

    /** Map [MeshChannelPreset] to firmware `Config_LoRaConfig_ModemPreset` ordinal. */
    private fun presetProtoOrdinal(preset: MeshChannelPreset): Int = when (preset) {
        MeshChannelPreset.LONG_FAST -> 0
        MeshChannelPreset.LONG_SLOW -> 1
        MeshChannelPreset.VERY_LONG_SLOW -> 2 // deprecated in newer firmware; still tolerated
        MeshChannelPreset.MEDIUM_SLOW -> 3
        MeshChannelPreset.MEDIUM_FAST -> 4
        MeshChannelPreset.SHORT_SLOW -> 5
        MeshChannelPreset.SHORT_FAST -> 6
        MeshChannelPreset.SHORT_TURBO -> 8 // skip 7 = LONG_MODERATE per recent firmware
    }

    /**
     * Wrap an AdminMessage byte blob into a fully-framed ToRadio.
     * Mirror of [AtakPluginSerializer.buildToRadio] but with portnum
     * `ADMIN_APP` and `to = 0xFFFFFFFF` (broadcast) — the firmware
     * routes admin payloads to the local radio when delivered on the
     * admin channel. `wantAck` defaults to true so the operator gets
     * a delivery signal we can surface in the UI later.
     */
    private fun wrapToRadio(adminBytes: ByteArray): ByteArray {
        val packetId = Random.nextInt().toUInt().let { if (it == 0u) 1u else it }

        // Data submessage — portnum + payload.
        val decoded = ByteArrayOutputStream().apply {
            appendVarintField(this, field = 1, value = PORTNUM_ADMIN_APP)
            appendTag(this, field = 2, wire = 2)
            appendVarint(this, adminBytes.size.toULong())
            write(adminBytes)
            // 5: want_response = true so the radio sends an ack.
            appendVarintField(this, field = 5, value = 1UL)
        }.toByteArray()

        // MeshPacket.
        val meshPacket = ByteArrayOutputStream().apply {
            // 2: to (fixed32). Broadcast addr — firmware unwraps locally.
            appendTag(this, field = 2, wire = 5)
            appendFixed32(this, 0xFFFFFFFFu)
            // 4: decoded
            appendTag(this, field = 4, wire = 2)
            appendVarint(this, decoded.size.toULong())
            write(decoded)
            // 6: id (fixed32)
            appendTag(this, field = 6, wire = 5)
            appendFixed32(this, packetId)
            // 10: want_ack
            appendVarintField(this, field = 10, value = 1UL)
        }.toByteArray()

        // ToRadio.packet = field 1.
        val toRadio = ByteArrayOutputStream().apply {
            appendTag(this, field = 1, wire = 2)
            appendVarint(this, meshPacket.size.toULong())
            write(meshPacket)
        }.toByteArray()
        return toRadio
    }

    // endregion

    // region Wire helpers — duplicated from AtakPluginSerializer ---------

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
            out.write(((value shr (i * 8)) and 0xFFu).toInt())
        }
    }

    private fun appendString(out: ByteArrayOutputStream, field: Int, value: String) {
        if (value.isEmpty()) return
        val bytes = value.toByteArray(Charsets.UTF_8)
        appendTag(out, field = field, wire = 2)
        appendVarint(out, bytes.size.toULong())
        out.write(bytes)
    }

    // endregion
}
