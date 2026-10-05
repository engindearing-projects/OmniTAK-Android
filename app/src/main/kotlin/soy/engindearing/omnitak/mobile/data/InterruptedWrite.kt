package soy.engindearing.omnitak.mobile.data

import soy.engindearing.omnitak.mobile.data.RadioSettingsCache.Key

/**
 * A settings sequence that opened an edit transaction on a radio and did not get its commit out: the link dropped
 * between `begin_edit_settings` and `commit_edit_settings`.
 *
 * Firmware 2.7.26 (src/modules/AdminModule.cpp): `begin_edit_settings` sets `hasOpenEditTransaction` (line 332), and
 * only `commit_edit_settings` (line 338) or a restart clears it. A client that disconnects does not. While it is set,
 * `saveChanges` neither saves nor restarts (lines 1364 to 1375), so what the sequence wrote stays in the radio's
 * memory and is reported as the radio's own values; a power cycle brings the old ones back, and a write from any
 * other client is not saved either, until a commit arrives. A reconnect has to close it, or say that the change is
 * gone.
 *
 * What the app knows, to decide that on the next link-up:
 *  - [node] and [rebootCount], the radio and `my_info.reboot_count` of the connection the sequence ran on. The count
 *    is kept on ESP32 only (NodeDB.cpp loads it under ARCH_ESP32): any other radio, the simulator included, reports
 *    0 every time, and a restart cannot be told from none by it;
 *  - [settings], everything the sequence had written when the link dropped, and [values], the ones among them that
 *    can be compared with what the radio reports, each with the value the radio had before the sequence and the one
 *    that was sent.
 *
 * Pure Kotlin.
 */
class InterruptedWrite(
    val node: UInt,
    val rebootCount: UInt,
    val settings: List<AdminSetting>,
    val values: Map<AdminSetting, WrittenValue>,
) {

    /** One value the sequence wrote: what the radio held just before it was written, and what was sent. */
    class WrittenValue(val before: Any?, val sent: Any)

    /** What the settings the radio reports say about the written ones. */
    enum class Verdict {
        /** The radio reports a written value that differs from what it had before: the changes are still in its memory. */
        HOLDS,

        /** It reports the old values for all it can be judged on: it restarted, or the writes never arrived. */
        GONE,

        /** Nothing written can be compared with what the radio reports. */
        UNKNOWN,
    }

    /**
     * For a radio that does not count its restarts: judge from what it reports in its download, [radio].
     *
     * A setting the radio held already (the value before is the value sent) says nothing either way, and a setting it
     * has not reported, or reports as a value this app has no name for, cannot be judged.
     */
    fun verdict(radio: RadioSettings): Verdict {
        var judged = false
        for ((setting, written) in values) {
            if (written.sent == written.before) continue
            val reported = radio.valueOf(setting) ?: continue
            judged = true
            if (reported == written.sent) return Verdict.HOLDS
        }
        return if (judged) Verdict.GONE else Verdict.UNKNOWN
    }

    companion object {
        /** The result line when the changes were still in the radio's memory and the app saved them. */
        const val SAVED =
            "The link dropped while settings were being sent. The radio still held them unsaved, " +
                "so they were saved now and the radio restarts."

        /** The result line when the radio no longer holds them. */
        const val NOT_SAVED =
            "The link dropped while settings were being sent, and the radio does not hold them. " +
                "That change was not saved."

        /** The result line when the app cannot tell what the radio holds. */
        const val CHECK =
            "The link dropped while settings were being sent. " +
                "Check what the radio has, and push again if something is missing."
    }
}

/** What the radio reported for [setting], or null when it has not reported it or holds a value this app has no name for. */
internal fun RadioSettings.valueOf(setting: AdminSetting): Any? = when (setting) {
    AdminSetting.LONG_NAME -> longName
    AdminSetting.SHORT_NAME -> shortName
    AdminSetting.ROLE -> role
    AdminSetting.POSITION_INTERVAL -> positionBroadcastSecs
    AdminSetting.CHANNEL_NAME -> channelName
    AdminSetting.MODEM_PRESET -> channelPreset
    AdminSetting.REGION -> region
    AdminSetting.REBROADCAST_MODE -> rebroadcastMode
    // An imported channel is a whole slot, not a value the app keeps.
    AdminSetting.CHANNEL -> null
}

/** The values of the settings this app writes that [report] carries, by setting. A value with no name here is left out. */
internal fun AdminResponse.settingValues(): Map<AdminSetting, Any> = when (this) {
    is AdminResponse.Owner -> mapOf(AdminSetting.LONG_NAME to longName, AdminSetting.SHORT_NAME to shortName)
    is AdminResponse.DeviceConfig -> buildMap {
        role?.let { put(AdminSetting.ROLE, it) }
        rebroadcastMode?.let { put(AdminSetting.REBROADCAST_MODE, it) }
    }
    is AdminResponse.PositionConfig -> mapOf(AdminSetting.POSITION_INTERVAL to broadcastSecs)
    is AdminResponse.LoraConfig -> buildMap {
        preset?.let { put(AdminSetting.MODEM_PRESET, it) }
        region?.let { put(AdminSetting.REGION, it) }
    }
    // Only the primary channel's name is a setting of the app.
    is AdminResponse.Channel -> if (index == 0) mapOf(AdminSetting.CHANNEL_NAME to name) else emptyMap()
}

/** The settings of the entry [key] holds in [bytes] (the radio's own message, as the cache keeps it), by setting. */
internal fun settingValuesOf(key: Key, bytes: ByteArray): Map<AdminSetting, Any> {
    val response = when (key) {
        Key.Owner -> AdminMessageParser.parse(ProtoFields.message(4, bytes))
        is Key.Config -> AdminMessageParser.parse(ProtoFields.message(6, ProtoFields.message(key.variant, bytes)))
        is Key.Channel -> AdminMessageParser.parse(ProtoFields.message(2, bytes))
    }
    return response?.settingValues().orEmpty()
}
