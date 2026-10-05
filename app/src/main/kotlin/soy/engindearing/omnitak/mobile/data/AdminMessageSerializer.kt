package soy.engindearing.omnitak.mobile.data

import java.io.ByteArrayOutputStream

/**
 * GAP-109a — write Meshtastic device settings via admin-port (portnum 6).
 *
 * Hand-rolled protobuf encoders for the AdminMessage payload types the Device
 * Settings and Mesh Channels screens use:
 *
 * - `set_owner`     (field 32) — long name, short name
 * - `set_config`    (field 34): DeviceConfig role / rebroadcast mode,
 *                                PositionConfig broadcast interval,
 *                                LoRaConfig preset / region
 * - `set_channel`   (field 33): channel 0 name, or an imported channel
 * - `begin_edit_settings` / `commit_edit_settings` (fields 64 / 65)
 *
 * **Every setter patches the radio's own message.** The firmware replaces the
 * whole config (or channel, or owner) with what a write carries, so a message
 * that names only the field being changed resets every other field to its
 * default: a preset change cleared the region, a rename cleared the channel
 * key. Each builder here therefore takes the bytes the radio last reported for
 * that message (see [RadioSettingsCache]), changes the one field, and returns
 * the whole thing, with fields this app does not know about carried over byte
 * for byte ([ProtoFields.patch]). Handed bytes it cannot read, a builder
 * returns null and nothing is sent.
 *
 * The one deliberate exception is [buildSetChannel], the import of a shared
 * channel: that is a full replacement (new name, new key) by design.
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

    /** nanopb `User.long_name` is char[40] — 39 bytes plus the NUL. */
    internal const val MAX_LONG_NAME_BYTES = 39

    /** nanopb `User.short_name` is char[5] — 4 bytes plus the NUL. */
    internal const val MAX_SHORT_NAME_BYTES = 4

    /** nanopb `ChannelSettings.name` is char[12]: 11 bytes plus the NUL. */
    internal const val MAX_CHANNEL_NAME_BYTES = 11

    /** nanopb `Data.payload` is at most 233 bytes. A longer AdminMessage is dropped by the radio, so it is never sent. */
    private const val MAX_ADMIN_PAYLOAD_BYTES = 233

    /** Meshtastic portnum for AdminMessage payloads on the local radio. */
    private const val PORTNUM_ADMIN_APP: ULong = 6UL

    // AdminMessage (admin.proto)
    private const val ADMIN_SET_OWNER = 32
    private const val ADMIN_SET_CHANNEL = 33
    private const val ADMIN_SET_CONFIG = 34
    private const val ADMIN_BEGIN_EDIT_SETTINGS = 64
    private const val ADMIN_COMMIT_EDIT_SETTINGS = 65

    // Config oneof (config.proto)
    private const val CONFIG_DEVICE = RadioSettingsCache.CONFIG_DEVICE
    private const val CONFIG_POSITION = RadioSettingsCache.CONFIG_POSITION
    private const val CONFIG_LORA = RadioSettingsCache.CONFIG_LORA

    // DeviceConfig
    private const val DEVICE_ROLE = 1
    private const val DEVICE_REBROADCAST_MODE = 6

    // PositionConfig. Field 4 is the deprecated `gps_enabled` bool. Writing
    // seconds there sets a boolean and leaves the cadence untouched.
    private const val POSITION_BROADCAST_SECS = 1

    // LoRaConfig
    private const val LORA_USE_PRESET = 1
    private const val LORA_MODEM_PRESET = 2
    private const val LORA_REGION = 7

    // Channel / ChannelSettings (channel.proto)
    private const val CHANNEL_INDEX = 1
    private const val CHANNEL_SETTINGS = 2
    private const val CHANNEL_ROLE = 3
    private const val SETTINGS_PSK = 2
    private const val SETTINGS_NAME = 3
    private const val SETTINGS_UPLINK = 5
    private const val SETTINGS_DOWNLINK = 6

    // User (mesh.proto)
    private const val USER_LONG_NAME = 2
    private const val USER_SHORT_NAME = 3
    private const val USER_IS_LICENSED = 6

    /**
     * One admin write: the [frame] to send, and the [message] it carries (the
     * whole DeviceConfig, Channel, User, ... as the radio will hold it once the
     * write is applied). Remember [message] in the [RadioSettingsCache] after
     * the frame is sent, and the next edit starts from it.
     */
    class AdminWrite(val frame: ByteArray, val message: ByteArray)

    // region Channel apply (#172) ---------------------------------------

    /**
     * Build a ToRadio with `AdminMessage { set_channel { Channel } }` from an
     * imported [MeshChannel] — the channel-apply path for a scanned/pasted
     * `meshtastic.org/e/#…` share.
     *
     * This is a full replacement on purpose: the shared name and key become the
     * channel, whatever it held before.
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
    fun buildSetChannel(myNodeNum: UInt, channel: MeshChannel, index: Int): ByteArray =
        buildSetChannelWrite(myNodeNum, channel, index).frame

    /** [buildSetChannel], with the Channel message it carries, so the app can remember what the radio now holds. */
    fun buildSetChannelWrite(myNodeNum: UInt, channel: MeshChannel, index: Int): AdminWrite {
        // ChannelSettings — PSK first (field 2) then name (field 3), matching
        // the share-URL encoder field order.
        val settings = ByteArrayOutputStream().apply {
            if (channel.psk.isNotEmpty()) {
                MeshWire.appendLenField(this, field = SETTINGS_PSK, bytes = channel.psk)
            }
            // Cut to the firmware's 11 bytes like a rename: a longer name makes the radio drop the message.
            clampChannelName(channel.name).takeIf { it.isNotEmpty() }?.let {
                MeshWire.appendString(this, field = SETTINGS_NAME, value = it)
            }
            if (channel.uplinkEnabled) MeshWire.appendVarintField(this, field = SETTINGS_UPLINK, value = 1UL)
            if (channel.downlinkEnabled) MeshWire.appendVarintField(this, field = SETTINGS_DOWNLINK, value = 1UL)
        }.toByteArray()

        val safeIndex = index.coerceIn(0, RadioSettingsCache.MAX_CHANNELS - 1)
        val role = if (safeIndex == 0) CHANNEL_ROLE_PRIMARY else CHANNEL_ROLE_SECONDARY

        val channelMsg = ByteArrayOutputStream().apply {
            // index — omit when 0 (proto3 default) to match firmware encoding.
            if (safeIndex != 0) {
                MeshWire.appendVarintField(this, field = CHANNEL_INDEX, value = safeIndex.toULong())
            }
            MeshWire.appendLenField(this, field = CHANNEL_SETTINGS, bytes = settings)
            MeshWire.appendVarintField(this, field = CHANNEL_ROLE, value = role.toULong())
        }.toByteArray()

        val admin = ProtoFields.message(ADMIN_SET_CHANNEL, channelMsg)
        return AdminWrite(wrapToRadio(admin, myNodeNum), channelMsg)
    }

    private const val CHANNEL_ROLE_PRIMARY = 1
    private const val CHANNEL_ROLE_SECONDARY = 2

    // endregion

    // region Edit transaction -------------------------------------------

    /**
     * Build `AdminMessage { begin_edit_settings = true }`. The radio holds off
     * saving, and rebooting, until the matching [buildCommitEditSettings]
     * arrives, so a batch of writes costs one save and one reboot instead of
     * one per write.
     */
    fun buildBeginEditSettings(myNodeNum: UInt): ByteArray =
        wrapToRadio(ProtoFields.bool(ADMIN_BEGIN_EDIT_SETTINGS, true), myNodeNum)

    /** Build `AdminMessage { commit_edit_settings = true }`: save everything written since [buildBeginEditSettings]. */
    fun buildCommitEditSettings(myNodeNum: UInt): ByteArray =
        wrapToRadio(ProtoFields.bool(ADMIN_COMMIT_EDIT_SETTINGS, true), myNodeNum)

    // endregion

    // region Public builders --------------------------------------------

    /**
     * Build `AdminMessage { set_config { device { rebroadcast_mode } } }` on top
     * of [currentDevice], the DeviceConfig the radio last reported.
     *
     * PatoG1899's "rebroadcast only known channels" request maps to
     * [RebroadcastMode.KNOWN_ONLY] (or [RebroadcastMode.LOCAL_ONLY]). Field
     * numbers (config.proto):
     *   AdminMessage.set_config       = 34
     *   Config.device                 = 1
     *   DeviceConfig.rebroadcast_mode = 6  (enum)
     */
    fun buildSetRebroadcastMode(myNodeNum: UInt, mode: RebroadcastMode, currentDevice: ByteArray): AdminWrite? =
        setConfig(
            myNodeNum, CONFIG_DEVICE, currentDevice,
            mapOf(DEVICE_REBROADCAST_MODE to ProtoFields.varintOrClear(DEVICE_REBROADCAST_MODE, mode.wire.toULong())),
        )

    /**
     * Build a `set_owner` that changes the long and short name and nothing
     * else, on top of [currentOwner], the User the radio last reported.
     *
     * Everything else in the User is carried over: the firmware assigns
     * `is_licensed` (and `is_unmessagable`) from the message, so an owner
     * built from the names alone would clear a licensed operator's flag.
     *
     * A blank name is left as the radio has it (the firmware ignores an empty
     * name and rejects a whitespace-only one). [isLicensed] changes the
     * licensed flag when given; null, the default, keeps it. Turning it on has
     * side effects on the radio (it can wipe channel keys), so no screen sets it.
     */
    fun buildSetOwner(
        myNodeNum: UInt,
        longName: String,
        shortName: String,
        currentOwner: ByteArray,
        isLicensed: Boolean? = null,
    ): AdminWrite? {
        val edits = LinkedHashMap<Int, ByteArray?>()
        // long_name: nanopb char[40], so 39 usable bytes.
        clampUtf8(longName, MAX_LONG_NAME_BYTES).takeIf { it.isNotBlank() }
            ?.let { edits[USER_LONG_NAME] = ProtoFields.string(USER_LONG_NAME, it) }
        // short_name: nanopb char[5], so 4 usable bytes.
        clampUtf8(shortName, MAX_SHORT_NAME_BYTES).takeIf { it.isNotBlank() }
            ?.let { edits[USER_SHORT_NAME] = ProtoFields.string(USER_SHORT_NAME, it) }
        if (isLicensed != null) edits[USER_IS_LICENSED] = ProtoFields.boolOrClear(USER_IS_LICENSED, isLicensed)

        val owner = ProtoFields.patch(currentOwner, edits) ?: return null
        // AdminMessage.set_owner = field 32, wire type 2 (length-delimited submessage).
        return finish(myNodeNum, ProtoFields.message(ADMIN_SET_OWNER, owner), owner)
    }

    /**
     * Build a ToRadio with `AdminMessage { set_config { device { role = ... } } }`
     * on top of [currentDevice], the DeviceConfig the radio last reported.
     * Only sets the role — the practitioner-headline knob.
     */
    fun buildSetDeviceRole(myNodeNum: UInt, role: MeshRole, currentDevice: ByteArray): AdminWrite? =
        // DeviceConfig.role = field 1, varint of the proto-enum ordinal.
        setConfig(
            myNodeNum, CONFIG_DEVICE, currentDevice,
            mapOf(DEVICE_ROLE to ProtoFields.varintOrClear(DEVICE_ROLE, roleProtoOrdinal(role).toULong())),
        )

    /**
     * Build a ToRadio with `AdminMessage { set_config { position { position_broadcast_secs = N } } }`
     * on top of [currentPosition], the PositionConfig the radio last reported.
     * Headline practitioner ask: operator-controlled PLI cadence. GPS mode,
     * position flags and smart-broadcast settings stay as the radio has them.
     */
    fun buildSetPositionBroadcastSecs(myNodeNum: UInt, secs: Int, currentPosition: ByteArray): AdminWrite? {
        val safe = secs.coerceIn(0, 24 * 60 * 60).toULong()
        // PositionConfig.position_broadcast_secs = field 1, varint.
        return setConfig(
            myNodeNum, CONFIG_POSITION, currentPosition,
            mapOf(POSITION_BROADCAST_SECS to ProtoFields.varintOrClear(POSITION_BROADCAST_SECS, safe)),
        )
    }

    /**
     * Build a ToRadio with `AdminMessage { set_channel { settings { name } } }`
     * for channel index 0, on top of [currentChannel0], the Channel the radio
     * last reported. Only the human-readable name changes: the key, id,
     * uplink/downlink flags and module settings (position precision) stay as
     * the radio has them. The preset goes through
     * `set_config { lora { use_preset = true, modem_preset = ... } }`
     * — see [buildSetLoraPreset]. Two messages because Meshtastic
     * splits channel and modem config across two protobuf submessages.
     *
     * The name is cut to the firmware's 11 bytes on a character boundary: a
     * longer one makes the radio drop the whole message.
     */
    fun buildSetChannel0Name(myNodeNum: UInt, name: String, currentChannel0: ByteArray): AdminWrite? {
        // ChannelSettings.name = field 3, string. Channel.settings = field 2.
        val channel = ProtoFields.patchNested(
            currentChannel0, CHANNEL_SETTINGS,
            mapOf(SETTINGS_NAME to ProtoFields.stringOrClear(SETTINGS_NAME, clampUtf8(name, MAX_CHANNEL_NAME_BYTES))),
        ) ?: return null
        // AdminMessage.set_channel = field 33.
        return finish(myNodeNum, ProtoFields.message(ADMIN_SET_CHANNEL, channel), channel)
    }

    // region Read requests ----------------------------------------------

    /**
     * AdminMessage.get_owner_request = field 3 (bool). [packetId] is the MeshPacket id the radio's answer
     * will quote as its request id; the writer passes the one it recorded for the read.
     */
    fun buildGetOwnerRequest(myNodeNum: UInt, packetId: UInt? = null): ByteArray {
        val admin = ByteArrayOutputStream().apply {
            MeshWire.appendVarintField(this, field = 3, value = 1UL)
        }.toByteArray()
        return wrapToRadio(admin, myNodeNum, packetId)
    }

    /**
     * AdminMessage.get_config_request = field 5 (varint enum, ConfigType).
     * Values: DEVICE=0, POSITION=1, POWER=2, NETWORK=3, DISPLAY=4, LORA=5,
     * BLUETOOTH=6, SECURITY=7, SESSIONKEY=8, DEVICEUI=9.
     */
    fun buildGetConfigRequest(myNodeNum: UInt, configType: Int, packetId: UInt? = null): ByteArray {
        val admin = ByteArrayOutputStream().apply {
            MeshWire.appendVarintField(this, field = 5, value = configType.toULong())
        }.toByteArray()
        return wrapToRadio(admin, myNodeNum, packetId)
    }

    /** AdminMessage.get_channel_request = field 1 (varint, 1-based channel index). */
    fun buildGetChannelRequest(myNodeNum: UInt, channelIndex: Int, packetId: UInt? = null): ByteArray {
        val admin = ByteArrayOutputStream().apply {
            // Index in get_channel_request is 1-based; channel 0 is requested as 1.
            val zeroBased = channelIndex.coerceAtLeast(0)
            MeshWire.appendVarintField(this, field = 1, value = (zeroBased + 1).toULong())
        }.toByteArray()
        return wrapToRadio(admin, myNodeNum, packetId)
    }

    // endregion

    /**
     * Build `set_config { lora { use_preset = true, modem_preset = ... } }` on
     * top of [currentLora], the LoRaConfig the radio last reported. Region, hop
     * limit, transmit switch and the rest stay as the radio has them.
     */
    fun buildSetLoraPreset(myNodeNum: UInt, preset: MeshChannelPreset, currentLora: ByteArray): AdminWrite? =
        buildSetLoRaConfig(myNodeNum, MeshRegion.UNSET, preset, currentLora)

    /**
     * #181: build `set_config { lora { use_preset, modem_preset, region } }` on
     * top of [currentLora], the LoRaConfig the radio last reported.
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
     * [MeshRegion.UNSET] the radio's region is left alone, never cleared: a
     * preset change must not take a radio off its band. A null [modemPreset]
     * leaves the preset (and use_preset) alone the same way, so a region can be
     * set without touching the preset.
     */
    fun buildSetLoRaConfig(
        myNodeNum: UInt,
        region: MeshRegion,
        modemPreset: MeshChannelPreset?,
        currentLora: ByteArray,
        usePreset: Boolean = true,
    ): AdminWrite? {
        val edits = LinkedHashMap<Int, ByteArray?>()
        if (modemPreset != null) {
            // use_preset = field 1 (bool); modem_preset = field 2 (enum). proto3 omits
            // the defaults (false, LONG_FAST = 0), so those fields are removed.
            edits[LORA_USE_PRESET] = ProtoFields.boolOrClear(LORA_USE_PRESET, usePreset)
            edits[LORA_MODEM_PRESET] = ProtoFields.varintOrClear(LORA_MODEM_PRESET, presetProtoOrdinal(modemPreset).toULong())
        }
        // region = field 7 (enum). UNSET is "leave it": it is not an edit.
        if (region != MeshRegion.UNSET) {
            edits[LORA_REGION] = ProtoFields.varint(LORA_REGION, region.wire.toULong())
        }
        return setConfig(myNodeNum, CONFIG_LORA, currentLora, edits)
    }

    // endregion

    // region Private encoders -------------------------------------------

    /** `AdminMessage { set_config { <variant> = current patched with edits } }`. */
    private fun setConfig(myNodeNum: UInt, variant: Int, current: ByteArray, edits: Map<Int, ByteArray?>): AdminWrite? {
        val message = ProtoFields.patch(current, edits) ?: return null
        // Config.<variant> is a oneof submessage; AdminMessage.set_config = field 34.
        val admin = ProtoFields.message(ADMIN_SET_CONFIG, ProtoFields.message(variant, message))
        return finish(myNodeNum, admin, message)
    }

    /** Frame [admin] for the radio, unless it is too big for the radio to accept. */
    private fun finish(myNodeNum: UInt, admin: ByteArray, message: ByteArray): AdminWrite? {
        if (admin.size > MAX_ADMIN_PAYLOAD_BYTES) return null
        return AdminWrite(wrapToRadio(admin, myNodeNum), message)
    }

    /**
     * Map [MeshRole] to the firmware enum ordinal. Order **must**
     * match the canonical `Config_DeviceConfig_Role` enum; this is the
     * wire format. If Meshtastic reshuffles the enum we have to
     * follow them — keep this list in sync with `config.proto`.
     */
    internal fun roleProtoOrdinal(role: MeshRole): Int = when (role) {
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
    internal fun presetProtoOrdinal(preset: MeshChannelPreset): Int = when (preset) {
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
    private fun wrapToRadio(adminBytes: ByteArray, myNodeNum: UInt, packetId: UInt? = null): ByteArray = MeshWire.buildToRadio(
        portnum = PORTNUM_ADMIN_APP,
        payload = adminBytes,
        packetId = packetId,
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

    internal fun clampLongName(value: String): String = clampUtf8(value, MAX_LONG_NAME_BYTES)

    internal fun clampShortName(value: String): String = clampUtf8(value, MAX_SHORT_NAME_BYTES)

    internal fun clampChannelName(value: String): String = clampUtf8(value, MAX_CHANNEL_NAME_BYTES)

    /**
     * Clamp [value] to [maxBytes] of UTF-8, cutting only on a character
     * boundary.
     *
     * The firmware stores these names in fixed nanopb buffers, so the limit is
     * bytes — `String.take(n)` counts UTF-16 units, and 39 Chinese characters
     * is 117 bytes. Over-long or half-a-character input makes nanopb reject the
     * field and drop the entire AdminMessage, so the rename silently does
     * nothing rather than failing loudly.
     *
     * Cuts on code points, which keeps surrogate pairs intact. A ZWJ emoji
     * sequence can still be split into its parts — that stays valid UTF-8 and
     * renders as separate glyphs, which beats dropping the write.
     */
    internal fun clampUtf8(value: String, maxBytes: Int): String {
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
        val out = StringBuilder()
        var used = 0
        var i = 0
        while (i < value.length) {
            val codePoint = value.codePointAt(i)
            val width = Character.charCount(codePoint)
            val piece = value.substring(i, i + width)
            val size = piece.toByteArray(Charsets.UTF_8).size
            if (used + size > maxBytes) break
            out.append(piece)
            used += size
            i += width
        }
        return out.toString()
    }

    // endregion
}
