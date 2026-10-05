package soy.engindearing.omnitak.mobile.data

/**
 * What the last push sent to one radio ([node], by the number it reported in `my_info`), kept until the operator
 * edits or pushes again, or a different radio connects.
 *
 * A push ends with the radio restarting to save what it received, and some of what it received is changed at
 * that restart: firmware 2.7.26 raises a short position interval to one hour when the radio sends its position on
 * its default channel ([PositionFloor]). The radio reports the value it received until then, so the read-back
 * right after the push shows nothing wrong. The download after the restart does, and [check] compares it with
 * what was sent.
 *
 * [values] are the values as they went out (the interval in range, the names cut to the firmware's byte limits),
 * for the settings that were actually written.
 *
 * Pure Kotlin.
 */
class SentSettings(val node: UInt, val values: Map<AdminSetting, Any>) {

    /**
     * The note for the operator, or null when the radio kept every value that was sent. [radio] is what the radio
     * reported in its download, setting by setting; a setting it has not reported, or reports as a value this app
     * has no name for, is not judged. [facts] is what it reported about its channels, LoRa config and role: the
     * reason for a short interval is added when they explain it.
     *
     * One sentence for each value the radio did not keep, with the value it reports and the one that was sent. A
     * value it kept says nothing.
     */
    fun check(radio: RadioSettings, facts: PositionFacts?): String? {
        val notes = ArrayList<String>()
        var reason: String? = null
        for ((setting, sent) in values.entries.sortedBy { it.key.ordinal }) {
            val reported = reportedValue(radio, setting) ?: continue
            if (reported == sent) continue
            notes += "The radio reports ${show(setting, reported)} for the ${setting.label}. ${show(setting, sent)} was sent."
            if (setting == AdminSetting.POSITION_INTERVAL) reason = intervalReason(sent, reported, facts)
        }
        if (notes.isEmpty()) return null
        return (notes + listOfNotNull(reason)).joinToString(" ")
    }

    /**
     * The firmware's floor explains a short interval that came back raised: the radio's position channel is its
     * default channel, the interval that was sent was above 0 and under the floor for the radio's role, and the
     * radio now reports that floor.
     */
    private fun intervalReason(sent: Any, reported: Any, facts: PositionFacts?): String? {
        if (sent !is Int || reported !is Int || facts == null) return null
        val floor = PositionFloor.floorSecs(facts.role ?: return null)
        val explained = facts.onDefaultChannel() == true && sent > 0 && sent < floor && reported == floor
        return if (explained) PositionFloor.reason(floor) else null
    }

    private fun reportedValue(radio: RadioSettings, setting: AdminSetting): Any? = when (setting) {
        AdminSetting.LONG_NAME -> radio.longName
        AdminSetting.SHORT_NAME -> radio.shortName
        AdminSetting.ROLE -> radio.role
        AdminSetting.POSITION_INTERVAL -> radio.positionBroadcastSecs
        AdminSetting.CHANNEL_NAME -> radio.channelName
        AdminSetting.MODEM_PRESET -> radio.channelPreset
        AdminSetting.REGION -> radio.region
        // Not on the Device settings screen: the app does not keep what the radio reports for them.
        AdminSetting.REBROADCAST_MODE, AdminSetting.CHANNEL -> null
    }

    private fun show(setting: AdminSetting, value: Any): String = when {
        setting == AdminSetting.POSITION_INTERVAL -> "$value s"
        value is String -> if (value.isEmpty()) "no name" else "\"$value\""
        value is MeshRole -> value.label
        value is MeshChannelPreset -> value.label
        value is MeshRegion -> value.label
        else -> value.toString()
    }
}
