package soy.engindearing.omnitak.mobile.data

import soy.engindearing.omnitak.mobile.data.RadioSettingsCache.Key

/**
 * The firmware's floor on the position interval, and what the app needs to see it coming.
 *
 * Firmware 2.7.26 (tag v2.7.26.54e0d8d) does this when it loads its config at start, in `NodeDB::NodeDB()`
 * (src/mesh/NodeDB.cpp, "Enforce position broadcast minimums if we would send positions over a default channel",
 * lines 349 to 365):
 *
 *  - the position channel is the first channel, in index order, whose module settings have a
 *    `position_precision` other than 0 (a channel's role is not looked at);
 *  - when that channel is the default channel (`Channels::isDefaultChannel`, src/mesh/Channels.cpp lines 376 to
 *    388: a key of exactly one byte with the value 1, and a name equal to the display name of the modem preset,
 *    an empty name standing for that name, `Channels::getName` lines 358 to 374), `position_broadcast_secs` is
 *    raised to at least [DEFAULT_FLOOR_SECS], or [ROUTER_FLOOR_SECS] for the roles ROUTER and ROUTER_LATE
 *    (`min_default_broadcast_interval_secs` and `IF_ROUTER`, src/mesh/Default.h lines 21 and 47 to 51). A value of 0
 *    is left alone (`Default::getConfiguredOrMinimumValue`, src/mesh/Default.cpp lines 63 to 70).
 *
 * That runs at start and the raised value is saved, so a write of a shorter interval is held by the radio until it
 * restarts, and the radio reports the floor after it. The preset display names are those of
 * `DisplayFormatters::getModemPresetDisplayName` (src/DisplayFormatters.cpp, long names). Nothing here has been
 * checked against another firmware version.
 *
 * Pure Kotlin.
 */
object PositionFloor {

    /** The smallest position interval the firmware leaves on a default channel: one hour. */
    const val DEFAULT_FLOOR_SECS = 60 * 60

    /** The same for a router (ROUTER, ROUTER_LATE): twelve hours. */
    const val ROUTER_FLOOR_SECS = 12 * 60 * 60

    /** Device roles (config.proto `Role`) the firmware treats as routers for the floor: ROUTER and ROUTER_LATE. */
    private val ROUTER_ROLES = setOf(2, 11)

    /** The floor for a radio holding the device role [role] (its number in config.proto). */
    fun floorSecs(role: Int): Int = if (role in ROUTER_ROLES) ROUTER_FLOOR_SECS else DEFAULT_FLOOR_SECS

    /**
     * Why a short interval does not hold on a default channel. The same words go next to the interval control
     * before a push and in the note after the radio has restarted. Statements about firmware 2.7.26, the one
     * this was read from and measured on.
     */
    fun reason(floorSecs: Int): String {
        val floor = if (floorSecs >= ROUTER_FLOOR_SECS) "twelve hours" else "one hour"
        return "This radio sends positions on its public default channel. " +
            "Meshtastic firmware 2.7.26 raises a position interval under $floor to $floor when the radio restarts. " +
            "A private channel is needed for faster position updates."
    }

    /** `DisplayFormatters::getModemPresetDisplayName(preset, false, usePreset)`: the name a default channel carries. */
    internal fun presetDisplayName(modemPreset: Long, usePreset: Boolean): String {
        if (!usePreset) return "Custom"
        return when (modemPreset) {
            8L -> "ShortTurbo"
            5L -> "ShortSlow"
            6L -> "ShortFast"
            3L -> "MediumSlow"
            4L -> "MediumFast"
            1L -> "LongSlow"
            0L -> "LongFast"
            9L -> "LongTurbo"
            7L -> "LongMod"
            else -> "Invalid"
        }
    }
}

/**
 * What a radio reported about the settings that decide the position floor: its channels, its LoRa config and its
 * role, read from the raw messages in [RadioSettingsCache]. A part that is not loaded is null, and nothing is
 * decided from a part that is missing.
 *
 * Built from what the radio reported and from nothing else: never from the app's own list of saved channels.
 */
data class PositionFacts(
    /** Slots 0 to 7, null where the radio has not reported that slot. */
    val channels: List<ChannelFacts?>,
    val lora: LoraFacts?,
    /** The device role as its number in config.proto (an absent field is CLIENT, 0). */
    val role: Int?,
) {

    /** The parts of one channel the firmware's rule looks at. */
    data class ChannelFacts(
        /** The key is exactly one byte with the value 1. */
        val defaultKey: Boolean,
        val name: String,
        /** `module_settings.position_precision`, 0 when it is not there. */
        val positionPrecision: Long,
    )

    /** The parts of the LoRa config the rule looks at. An absent field is the proto3 default: no preset, `LONG_FAST`. */
    data class LoraFacts(val usePreset: Boolean, val modemPreset: Long)

    /**
     * Whether the radio's position channel is its default channel, the way the firmware decides it. Null when the
     * answer would need something the radio has not reported (the LoRa config, or a channel the search has to look
     * at). False when no channel sends positions at all.
     *
     * [channelName] and [modemPreset] stand for a rename of the primary channel and a preset change that are about
     * to be pushed: the firmware decides when it restarts, from the radio as it will then be. A preset that is set
     * through the app turns `use_preset` on.
     */
    fun onDefaultChannel(channelName: String? = null, modemPreset: Int? = null): Boolean? {
        val lora = lora ?: return null
        val usePreset = if (modemPreset != null) true else lora.usePreset
        val presetName = PositionFloor.presetDisplayName(modemPreset?.toLong() ?: lora.modemPreset, usePreset)
        for (index in 0 until RadioSettingsCache.MAX_CHANNELS) {
            val channel = channels.getOrNull(index) ?: return null
            if (channel.positionPrecision == 0L) continue
            val name = if (index == 0 && channelName != null) channelName else channel.name
            // An empty name stands for the preset's name.
            return channel.defaultKey && (name.isEmpty() || name == presetName)
        }
        return false
    }

    companion object {
        /** The facts in [cache]: whatever the radio has reported so far. */
        fun read(cache: RadioSettingsCache): PositionFacts = PositionFacts(
            channels = (0 until RadioSettingsCache.MAX_CHANNELS).map { index ->
                cache.get(Key.Channel(index))?.let { channelFacts(it) }
            },
            lora = cache.get(Key.Config(RadioSettingsCache.CONFIG_LORA))?.let { loraFacts(it) },
            role = cache.get(Key.Config(RadioSettingsCache.CONFIG_DEVICE))?.let { roleOf(it) },
        )

        /** Channel { 2 settings { 2 psk, 3 name, 7 module_settings { 1 position_precision } } }. */
        private fun channelFacts(channel: ByteArray): ChannelFacts? {
            val fields = ProtoFields.parse(channel) ?: return null
            val settings = ProtoFields.lastBytes(fields, 2)?.let { ProtoFields.parse(it) ?: return null }.orEmpty()
            val psk = ProtoFields.lastBytes(settings, 2)
            val module = ProtoFields.lastBytes(settings, 7)?.let { ProtoFields.parse(it) ?: return null }.orEmpty()
            return ChannelFacts(
                defaultKey = psk != null && psk.size == 1 && psk[0] == 1.toByte(),
                name = ProtoFields.lastString(settings, 3).orEmpty(),
                positionPrecision = (ProtoFields.lastVarint(module, 1) ?: 0uL).toLong(),
            )
        }

        /** LoRaConfig { 1 use_preset, 2 modem_preset }. */
        private fun loraFacts(lora: ByteArray): LoraFacts? {
            val fields = ProtoFields.parse(lora) ?: return null
            return LoraFacts(
                usePreset = (ProtoFields.lastVarint(fields, 1) ?: 0uL) != 0uL,
                modemPreset = (ProtoFields.lastVarint(fields, 2) ?: 0uL).toLong(),
            )
        }

        /** DeviceConfig { 1 role }. */
        private fun roleOf(device: ByteArray): Int? {
            val fields = ProtoFields.parse(device) ?: return null
            return (ProtoFields.lastVarint(fields, 1) ?: 0uL).coerceAtMost(Int.MAX_VALUE.toULong()).toInt()
        }
    }
}
