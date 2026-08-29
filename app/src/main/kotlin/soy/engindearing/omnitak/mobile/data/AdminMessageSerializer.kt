package soy.engindearing.omnitak.mobile.data

import java.io.ByteArrayOutputStream

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
 * Wire primitives + ToRadio framing live in [MeshWire].
 */
object AdminMessageSerializer {

    /** Meshtastic portnum for AdminMessage payloads on the local radio. */
    private const val PORTNUM_ADMIN_APP: ULong = 6UL

    // region Channel apply (#172) ---------------------------------------

    /**
     * Build a ToRadio with `AdminMessage { set_channel { Channel } }` from an
     * imported [MeshChannel] — the channel-apply path for a scanned/pasted
     * `meshtastic.org/e/#…` share.
     *
     * Channel.index = [index]; settings carries the name + PSK verbatim;
     * Channel.role = PRIMARY (1) for index 0, SECONDARY (2) otherwise, so a
     * shared channel slots in as a secondary without stealing the primary
     * frequency.
     *
     * Field numbers (channel.proto / admin.proto):
     *   AdminMessage.set_channel  = 33  (Channel submessage)
     *   Channel.index             = 1   (int32)
     *   Channel.settings          = 2   (ChannelSettings submessage)
     *   Channel.role              = 3   (enum: DISABLED=0, PRIMARY=1, SECONDARY=2)
     *   ChannelSettings.psk       = 2   (bytes)
     *   ChannelSettings.name      = 3   (string)
     *   ChannelSettings.uplink_enabled   = 5 (bool)
     *   ChannelSettings.downlink_enabled = 6 (bool)
     */
    fun buildSetChannel(myNodeNum: UInt, channel: MeshChannel, index: Int): ByteArray {
        // ChannelSettings — PSK first (field 2) then name (field 3), matching
        // the share-URL encoder field order.
        val settings = ByteArrayOutputStream().apply {
            if (channel.psk.isNotEmpty()) {
                MeshWire.appendLenField(this, field = 2, bytes = channel.psk)
            }
            if (channel.name.isNotEmpty()) {
                MeshWire.appendString(this, field = 3, value = channel.name)
            }
            if (channel.uplinkEnabled) MeshWire.appendVarintField(this, field = 5, value = 1UL)
            if (channel.downlinkEnabled) MeshWire.appendVarintField(this, field = 6, value = 1UL)
        }.toByteArray()

        val safeIndex = index.coerceIn(0, 7)
        val role = if (safeIndex == 0) CHANNEL_ROLE_PRIMARY else CHANNEL_ROLE_SECONDARY

        val channelMsg = ByteArrayOutputStream().apply {
            // index — omit when 0 (proto3 default) to match firmware encoding.
            if (safeIndex != 0) {
                MeshWire.appendVarintField(this, field = 1, value = safeIndex.toULong())
            }
            MeshWire.appendTag(this, field = 2, wire = 2)
            MeshWire.appendVarint(this, settings.size.toULong())
            write(settings)
            MeshWire.appendVarintField(this, field = 3, value = role.toULong())
        }.toByteArray()

        val admin = ByteArrayOutputStream().apply {
            MeshWire.appendTag(this, field = 33, wire = 2)
            MeshWire.appendVarint(this, channelMsg.size.toULong())
            write(channelMsg)
        }.toByteArray()
        return wrapToRadio(admin, myNodeNum)
    }

    /**
     * Build a ToRadio with `AdminMessage { set_config { device { rebroadcast_mode } } }`.
     *
     * PatoG1899's "rebroadcast only known channels" request maps to
     * [RebroadcastMode.KNOWN_ONLY] (or [RebroadcastMode.LOCAL_ONLY]). Field
     * numbers (config.proto):
     *   AdminMessage.set_config       = 34
     *   Config.device                 = 1
     *   DeviceConfig.rebroadcast_mode = 6  (enum)
     */
    fun buildSetRebroadcastMode(myNodeNum: UInt, mode: RebroadcastMode): ByteArray {
        val deviceConfig = ByteArrayOutputStream().apply {
            MeshWire.appendVarintField(this, field = 6, value = mode.wire.toULong())
        }.toByteArray()
        val config = ByteArrayOutputStream().apply {
            MeshWire.appendTag(this, field = 1, wire = 2)
            MeshWire.appendVarint(this, deviceConfig.size.toULong())
            write(deviceConfig)
        }.toByteArray()
        val admin = ByteArrayOutputStream().apply {
            MeshWire.appendTag(this, field = 34, wire = 2)
            MeshWire.appendVarint(this, config.size.toULong())
            write(config)
        }.toByteArray()
        return wrapToRadio(admin, myNodeNum)
    }

    private const val CHANNEL_ROLE_PRIMARY = 1
    private const val CHANNEL_ROLE_SECONDARY = 2

    // endregion

    // region Public builders --------------------------------------------

    /**
     * Build a `ToRadio { packet { decoded { portnum = ADMIN_APP, payload = AdminMessage{ set_owner } } } }`.
     * Sets the radio's display name (long + short) so the operator's
     * callsign matches everywhere they look.
     *
     * #181 — optional [id] (User.id, field 1) and [isLicensed] (field 6) let
     * the device-name editor set a node id / amateur-radio licensed flag in
     * the same admin write. Both default to "leave alone": a blank id and
     * `isLicensed = false` are omitted so the firmware keeps whatever it has.
     */
    fun buildSetOwner(
        myNodeNum: UInt,
        longName: String,
        shortName: String,
        id: String = "",
        isLicensed: Boolean = false,
    ): ByteArray {
        val owner = encodeOwner(longName = longName, shortName = shortName, id = id, isLicensed = isLicensed)
        // AdminMessage.set_owner = field 32, wire type 2 (length-delimited submessage).
        val admin = ByteArrayOutputStream().apply {
            MeshWire.appendTag(this, field = 32, wire = 2)
            MeshWire.appendVarint(this, owner.size.toULong())
            write(owner)
        }.toByteArray()
        return wrapToRadio(admin, myNodeNum)
    }

    /**
     * Build a ToRadio with `AdminMessage { set_config { device { role = ... } } }`.
     * Only sets the role — the practitioner-headline knob.
     */
    fun buildSetDeviceRole(myNodeNum: UInt, role: MeshRole): ByteArray {
        // DeviceConfig.role = field 1, varint of the proto-enum ordinal.
        val deviceConfig = ByteArrayOutputStream().apply {
            MeshWire.appendVarintField(this, field = 1, value = roleProtoOrdinal(role).toULong())
        }.toByteArray()

        // Config.device = field 1, wire type 2 (oneof submessage).
        val config = ByteArrayOutputStream().apply {
            MeshWire.appendTag(this, field = 1, wire = 2)
            MeshWire.appendVarint(this, deviceConfig.size.toULong())
            write(deviceConfig)
        }.toByteArray()

        // AdminMessage.set_config = field 34, wire type 2.
        val admin = ByteArrayOutputStream().apply {
            MeshWire.appendTag(this, field = 34, wire = 2)
            MeshWire.appendVarint(this, config.size.toULong())
            write(config)
        }.toByteArray()
        return wrapToRadio(admin, myNodeNum)
    }

    /**
     * Build a ToRadio with `AdminMessage { set_config { position { position_broadcast_secs = N } } }`.
     * Headline practitioner ask — operator-controlled PLI cadence.
     */
    fun buildSetPositionBroadcastSecs(myNodeNum: UInt, secs: Int): ByteArray {
        val safe = secs.coerceIn(0, 24 * 60 * 60).toULong()
        // PositionConfig.position_broadcast_secs = field 4, varint.
        val positionConfig = ByteArrayOutputStream().apply {
            MeshWire.appendVarintField(this, field = 4, value = safe)
        }.toByteArray()
        // Config.position = field 2, wire type 2.
        val config = ByteArrayOutputStream().apply {
            MeshWire.appendTag(this, field = 2, wire = 2)
            MeshWire.appendVarint(this, positionConfig.size.toULong())
            write(positionConfig)
        }.toByteArray()
        // AdminMessage.set_config = field 34.
        val admin = ByteArrayOutputStream().apply {
            MeshWire.appendTag(this, field = 34, wire = 2)
            MeshWire.appendVarint(this, config.size.toULong())
            write(config)
        }.toByteArray()
        return wrapToRadio(admin, myNodeNum)
    }

    /**
     * Build a ToRadio with `AdminMessage { set_channel { settings { name, ... } } }`
     * for channel index 0. PSK is left at the firmware-default for the
     * preset; we only set the human-readable name. Preset goes through
     * `set_config { lora { use_preset = true, modem_preset = ... } }`
     * — see [buildSetLoraPreset]. Two messages because Meshtastic
     * splits channel and modem config across two protobuf submessages.
     */
    fun buildSetChannel0Name(myNodeNum: UInt, name: String): ByteArray {
        // ChannelSettings.name = field 3, string.
        val settings = ByteArrayOutputStream().apply {
            appendString(this, field = 3, value = name)
        }.toByteArray()
        // Channel.index = 1 (varint, default 0 means primary), settings = field 2.
        val channel = ByteArrayOutputStream().apply {
            // Index 0 — the primary channel.
            MeshWire.appendVarintField(this, field = 1, value = 0UL)
            // Settings submessage at field 2.
            MeshWire.appendTag(this, field = 2, wire = 2)
            MeshWire.appendVarint(this, settings.size.toULong())
            write(settings)
            // Channel.role = field 3, varint. PRIMARY = 1.
            MeshWire.appendVarintField(this, field = 3, value = 1UL)
        }.toByteArray()
        // AdminMessage.set_channel = field 33.
        val admin = ByteArrayOutputStream().apply {
            MeshWire.appendTag(this, field = 33, wire = 2)
            MeshWire.appendVarint(this, channel.size.toULong())
            write(channel)
        }.toByteArray()
        return wrapToRadio(admin, myNodeNum)
    }

    // region Read requests ----------------------------------------------

    /** AdminMessage.get_owner_request = field 3 (bool). */
    fun buildGetOwnerRequest(myNodeNum: UInt): ByteArray {
        val admin = ByteArrayOutputStream().apply {
            MeshWire.appendVarintField(this, field = 3, value = 1UL)
        }.toByteArray()
        return wrapToRadio(admin, myNodeNum)
    }

    /**
     * AdminMessage.get_config_request = field 5 (varint enum, ConfigType).
     * Values: DEVICE=0, POSITION=1, POWER=2, NETWORK=3, DISPLAY=4, LORA=5,
     * BLUETOOTH=6, SECURITY=7, SESSIONKEY=8, DEVICEUI=9.
     */
    fun buildGetConfigRequest(myNodeNum: UInt, configType: Int): ByteArray {
        val admin = ByteArrayOutputStream().apply {
            MeshWire.appendVarintField(this, field = 5, value = configType.toULong())
        }.toByteArray()
        return wrapToRadio(admin, myNodeNum)
    }

    /** AdminMessage.get_channel_request = field 1 (varint, 1-based channel index). */
    fun buildGetChannelRequest(myNodeNum: UInt, channelIndex: Int): ByteArray {
        val admin = ByteArrayOutputStream().apply {
            // Index in get_channel_request is 1-based; channel 0 is requested as 1.
            val zeroBased = channelIndex.coerceAtLeast(0)
            MeshWire.appendVarintField(this, field = 1, value = (zeroBased + 1).toULong())
        }.toByteArray()
        return wrapToRadio(admin, myNodeNum)
    }

    // endregion

    /** Build `set_config { lora { use_preset = true, modem_preset = ... } }`. */
    fun buildSetLoraPreset(myNodeNum: UInt, preset: MeshChannelPreset): ByteArray {
        // LoRaConfig.use_preset = field 1 (bool), modem_preset = field 2 (enum).
        val loraConfig = ByteArrayOutputStream().apply {
            MeshWire.appendVarintField(this, field = 1, value = 1UL)
            MeshWire.appendVarintField(this, field = 2, value = presetProtoOrdinal(preset).toULong())
        }.toByteArray()
        // Config.lora = field 6.
        val config = ByteArrayOutputStream().apply {
            MeshWire.appendTag(this, field = 6, wire = 2)
            MeshWire.appendVarint(this, loraConfig.size.toULong())
            write(loraConfig)
        }.toByteArray()
        // AdminMessage.set_config = field 34.
        val admin = ByteArrayOutputStream().apply {
            MeshWire.appendTag(this, field = 34, wire = 2)
            MeshWire.appendVarint(this, config.size.toULong())
            write(config)
        }.toByteArray()
        return wrapToRadio(admin, myNodeNum)
    }

    /**
     * #181 — build `set_config { lora { use_preset, modem_preset, region } }`.
     *
     * The one-stop "make the stock app obsolete" knob: region picks the legal
     * frequency band (a fresh radio won't transmit until this is set) and the
     * modem preset picks the range/throughput profile. Both ride a single
     * LoRaConfig submessage so the firmware applies them atomically.
     *
     * Field numbers (config.proto LoRaConfig):
     *   use_preset   = 1 (bool)   — true: honour modem_preset, ignore raw BW/SF/CR
     *   modem_preset = 2 (enum ModemPreset)
     *   region       = 7 (enum RegionCode)
     * wrapped in Config.lora = 6, AdminMessage.set_config = 34.
     *
     * [usePreset] defaults true (the only mode OmniTAK exposes — raw
     * bandwidth/spread-factor tuning is out of scope). When [region] is
     * [MeshRegion.UNSET] (proto3 default 0) the field is omitted, leaving the
     * radio's current region untouched.
     */
    fun buildSetLoRaConfig(
        myNodeNum: UInt,
        region: MeshRegion,
        modemPreset: MeshChannelPreset,
        usePreset: Boolean = true,
    ): ByteArray {
        val loraConfig = ByteArrayOutputStream().apply {
            // use_preset = field 1 (bool). proto3 omits the default (false);
            // we only emit it when true so the wire matches firmware encoding.
            if (usePreset) MeshWire.appendVarintField(this, field = 1, value = 1UL)
            // modem_preset = field 2 (enum). Omitted when LONG_FAST (0 = default)
            // to match proto3 skip-default semantics.
            val presetWire = presetProtoOrdinal(modemPreset)
            if (presetWire != 0) {
                MeshWire.appendVarintField(this, field = 2, value = presetWire.toULong())
            }
            // region = field 7 (enum). Omit UNSET (0) so we don't clobber a
            // region the radio already has set.
            if (region != MeshRegion.UNSET) {
                MeshWire.appendVarintField(this, field = 7, value = region.wire.toULong())
            }
        }.toByteArray()
        // Config.lora = field 6.
        val config = ByteArrayOutputStream().apply {
            MeshWire.appendTag(this, field = 6, wire = 2)
            MeshWire.appendVarint(this, loraConfig.size.toULong())
            write(loraConfig)
        }.toByteArray()
        // AdminMessage.set_config = field 34.
        val admin = ByteArrayOutputStream().apply {
            MeshWire.appendTag(this, field = 34, wire = 2)
            MeshWire.appendVarint(this, config.size.toULong())
            write(config)
        }.toByteArray()
        return wrapToRadio(admin, myNodeNum)
    }

    // endregion

    // region Private encoders -------------------------------------------

    /** Owner / User submessage (mesh.proto). macaddr / hw_model / role /
     *  public_key are left untouched — the firmware keeps whatever the radio
     *  already has. When [id] is blank it is omitted (proto3 default), so the
     *  radio keeps its existing node id; when [isLicensed] is false the flag
     *  is omitted too. Field numbers: id=1, long_name=2, short_name=3,
     *  is_licensed=6. */
    private fun encodeOwner(
        longName: String,
        shortName: String,
        id: String = "",
        isLicensed: Boolean = false,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        // 1: id (string) — only when the caller supplied one.
        appendString(out, field = 1, value = id)
        // 2: long_name (string, max ~40 chars)
        appendString(out, field = 2, value = longName.take(39))
        // 3: short_name (string, max 4 chars per firmware constraint)
        appendString(out, field = 3, value = shortName.take(4))
        // 6: is_licensed (bool) — omit the proto3 default (false).
        if (isLicensed) MeshWire.appendVarintField(out, field = 6, value = 1UL)
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
     * `ADMIN_APP`, addressed to [myNodeNum] — the radio we are physically
     * attached to. `wantAck` defaults to true so the operator gets a
     * delivery signal we can surface in the UI later.
     */
    private fun wrapToRadio(adminBytes: ByteArray, myNodeNum: UInt): ByteArray = MeshWire.buildToRadio(
        portnum = PORTNUM_ADMIN_APP,
        payload = adminBytes,
        // #185 — addressed to the local radio, never broadcast. The firmware's
        // AdminModule only acts on packets addressed to the node itself, so a
        // broadcast admin frame is silently ignored *and* put on the air —
        // which for set_channel means transmitting the channel PSK under
        // whatever key is currently in use. Every reference client addresses
        // admin to myNodeNum.
        to = myNodeNum,
        // want_response + want_ack so the radio sends a delivery signal
        // we can surface in the UI later.
        wantAck = true,
        wantResponse = true,
    )

    // endregion

    // region Wire helpers — see [MeshWire] ------------------------------

    /** proto3 skip-default semantics: omit empty strings entirely. */
    private fun appendString(out: ByteArrayOutputStream, field: Int, value: String) {
        if (value.isEmpty()) return
        MeshWire.appendString(out, field = field, value = value)
    }

    // endregion
}
