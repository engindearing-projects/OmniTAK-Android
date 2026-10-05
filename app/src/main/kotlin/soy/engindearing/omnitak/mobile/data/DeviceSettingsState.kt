package soy.engindearing.omnitak.mobile.data

/** One setting an admin write can change, named the way the operator reads it. */
enum class AdminSetting(val label: String) {
    LONG_NAME("long name"),
    SHORT_NAME("short name"),
    ROLE("role"),
    POSITION_INTERVAL("position interval"),
    CHANNEL_NAME("channel name"),
    MODEM_PRESET("modem preset"),
    REGION("region"),
    REBROADCAST_MODE("rebroadcast mode"),
    CHANNEL("imported channel"),
}

/**
 * What the connected radio last reported for the settings the Device Settings
 * screen edits. A null field is not known: the radio has not reported it yet,
 * or it holds a value this app has no name for.
 */
data class RadioSettings(
    val longName: String? = null,
    val shortName: String? = null,
    val role: MeshRole? = null,
    val positionBroadcastSecs: Int? = null,
    val channelName: String? = null,
    val channelPreset: MeshChannelPreset? = null,
    val region: MeshRegion? = null,
    /** A LoRa config has been reported (its region and preset may still be values with no name here). */
    val loraLoaded: Boolean = false,
)

/**
 * The settings the operator changed, with the values to write. A null field
 * was not edited and is never written, whatever the draft holds.
 */
data class DeviceEdits(
    val longName: String? = null,
    val shortName: String? = null,
    val role: MeshRole? = null,
    val positionBroadcastSecs: Int? = null,
    val channelName: String? = null,
    val channelPreset: MeshChannelPreset? = null,
) {
    val isEmpty: Boolean
        get() = longName == null && shortName == null && role == null &&
            positionBroadcastSecs == null && channelName == null && channelPreset == null

    /**
     * What will be written, in the order it is written: the role first, because the firmware installs
     * role defaults (broadcast intervals among them) when the role changes, and the settings after it
     * are read back from the radio only then.
     */
    val settings: List<AdminSetting>
        get() = listOfNotNull(
            AdminSetting.ROLE.takeIf { role != null },
            AdminSetting.LONG_NAME.takeIf { longName != null },
            AdminSetting.SHORT_NAME.takeIf { shortName != null },
            AdminSetting.POSITION_INTERVAL.takeIf { positionBroadcastSecs != null },
            AdminSetting.CHANNEL_NAME.takeIf { channelName != null },
            AdminSetting.MODEM_PRESET.takeIf { channelPreset != null },
        )
}

/**
 * The Device Settings screen's two sources of truth: the draft the operator
 * edits and what the connected radio last reported ([radio], null when nothing
 * is loaded or the link is down).
 *
 * A setting counts as edited only when the radio has reported it and the draft
 * differs from that report. Nothing the radio did not say is ever written: a
 * fresh install's defaults, a draft left behind by another radio, or a value
 * that merely differs because another client changed the radio.
 *
 * A report from the radio moves [radio], and moves the draft for every setting
 * that is not edited. When [radio] was null (a new link, another radio) the
 * report overwrites the draft, so nothing carries over from an earlier session.
 *
 * Pure Kotlin. [MeshDeviceConfigStore] holds one and persists the draft.
 */
data class DeviceSettingsState(
    val draft: MeshDeviceConfig = MeshDeviceConfig(),
    val radio: RadioSettings? = null,
) {

    /** What [candidate] (the draft by default, or the screen's unsaved copy of it) changes on the radio. */
    fun edits(candidate: MeshDeviceConfig = draft): DeviceEdits {
        val r = radio ?: return DeviceEdits()
        val c = candidate.forWire()
        return DeviceEdits(
            // A blank name means "leave it": the firmware ignores an empty one.
            longName = c.longName.takeIf { it.isNotBlank() && r.longName != null && it != r.longName },
            shortName = c.shortName.takeIf { it.isNotBlank() && r.shortName != null && it != r.shortName },
            role = c.role.takeIf { r.role != null && it != r.role },
            // Compared as it is, not clamped: another client can set the interval above the app's limit, the screen
            // shows the radio's real value, and that is not an edit. What is sent once it does change is in range.
            positionBroadcastSecs = candidate.positionBroadcastSecs
                .takeIf { r.positionBroadcastSecs != null && it != r.positionBroadcastSecs }
                ?.coerceIn(0, MAX_INTERVAL_SECS),
            channelName = c.channelName.takeIf { r.channelName != null && it != r.channelName },
            channelPreset = c.channelPreset.takeIf { r.channelPreset != null && it != r.channelPreset },
        )
    }

    /**
     * The line shown next to the interval control, or null for none: the radio's position channel is its default
     * channel, and the interval the screen shows (the radio's own, or the one the operator chose) is above 0 and
     * under the floor the radio will hold after the push ([PositionFloor]).
     *
     * Decided from what the radio reported ([facts]) and from the edits on this screen that change the answer: the
     * role (the floor is twelve hours for a router), a rename of the primary channel and a preset (the firmware
     * decides when it restarts, from the radio as it will then be). Nothing is said while the radio's interval, role,
     * LoRa config or the channels the rule has to look at are not loaded.
     */
    fun positionIntervalHint(facts: PositionFacts?, candidate: MeshDeviceConfig = draft): String? {
        val r = radio ?: return null
        if (facts == null || r.positionBroadcastSecs == null) return null
        val edits = edits(candidate)
        val role = edits.role?.let { AdminMessageSerializer.roleProtoOrdinal(it) } ?: facts.role ?: return null
        val onDefaultChannel = facts.onDefaultChannel(
            channelName = edits.channelName,
            modemPreset = edits.channelPreset?.let { AdminMessageSerializer.presetProtoOrdinal(it) },
        ) ?: return null
        val floor = PositionFloor.floorSecs(role)
        val shown = candidate.positionBroadcastSecs
        return PositionFloor.reason(floor).takeIf { onDefaultChannel && shown > 0 && shown < floor }
    }

    /** Fold in one report from the connected radio. */
    fun withReport(report: AdminResponse): DeviceSettingsState {
        val old = radio ?: RadioSettings()
        return when (report) {
            is AdminResponse.Owner -> copy(
                draft = draft.copy(
                    longName = sync(draft.longName, old.longName, report.longName) { AdminMessageSerializer.clampLongName(it) },
                    shortName = sync(draft.shortName, old.shortName, report.shortName) { AdminMessageSerializer.clampShortName(it) },
                ),
                radio = old.copy(longName = report.longName, shortName = report.shortName),
            )
            is AdminResponse.DeviceConfig -> copy(
                draft = draft.copy(role = sync(draft.role, old.role, report.role)),
                radio = old.copy(role = report.role),
            )
            is AdminResponse.PositionConfig -> copy(
                draft = draft.copy(
                    // As the radio has it, even above the limit the screen lets the operator type.
                    positionBroadcastSecs = sync(draft.positionBroadcastSecs, old.positionBroadcastSecs, report.broadcastSecs),
                ),
                radio = old.copy(positionBroadcastSecs = report.broadcastSecs),
            )
            is AdminResponse.LoraConfig -> copy(
                draft = draft.copy(channelPreset = sync(draft.channelPreset, old.channelPreset, report.preset)),
                radio = old.copy(channelPreset = report.preset, region = report.region, loraLoaded = true),
            )
            is AdminResponse.Channel ->
                // Only the primary channel is on the screen.
                if (report.index != 0) {
                    this
                } else {
                    copy(
                        draft = draft.copy(
                            channelName = sync(draft.channelName, old.channelName, report.name) {
                                AdminMessageSerializer.clampChannelName(it)
                            },
                        ),
                        radio = old.copy(channelName = report.name),
                    )
                }
        }
    }

    /** The link dropped: what the radio reported is no longer what it holds. The draft stays. */
    fun withLinkDown(): DeviceSettingsState = copy(radio = null)

    private fun <T> sync(draftValue: T, oldRadio: T?, reported: T?, norm: (T) -> T = { it }): T = when {
        // A value this app has no name for: the draft cannot show it, and it is never written.
        reported == null -> draftValue
        // Edited: the operator's value stays, whatever the radio now says.
        oldRadio != null && norm(draftValue) != oldRadio -> draftValue
        else -> reported
    }

    private companion object {
        const val MAX_INTERVAL_SECS = 24 * 60 * 60
    }
}

/**
 * The draft's names as they would be sent, cut to the firmware's byte limits. The interval is left as it is: it is
 * compared with the radio's own value, and clamped only when it is sent.
 */
internal fun MeshDeviceConfig.forWire(): MeshDeviceConfig = copy(
    longName = AdminMessageSerializer.clampLongName(longName),
    shortName = AdminMessageSerializer.clampShortName(shortName),
    channelName = AdminMessageSerializer.clampChannelName(channelName),
)

/**
 * This draft with every setting the operator has not touched here (still equal to [from]) taken from [to].
 * Used when the saved draft moves under an unsaved copy: the typed-in edits stay, the rest follows the radio.
 */
fun MeshDeviceConfig.rebased(from: MeshDeviceConfig, to: MeshDeviceConfig): MeshDeviceConfig = MeshDeviceConfig(
    longName = if (longName == from.longName) to.longName else longName,
    shortName = if (shortName == from.shortName) to.shortName else shortName,
    role = if (role == from.role) to.role else role,
    positionBroadcastSecs = if (positionBroadcastSecs == from.positionBroadcastSecs) to.positionBroadcastSecs else positionBroadcastSecs,
    channelName = if (channelName == from.channelName) to.channelName else channelName,
    channelPreset = if (channelPreset == from.channelPreset) to.channelPreset else channelPreset,
)

/** What a LoRa apply will send: [region] [MeshRegion.UNSET] and a null [preset] each mean "leave it as the radio has it". */
data class LoraSend(val region: MeshRegion, val preset: MeshChannelPreset?)

/**
 * The Mesh Channels screen's LoRa controls: what the operator picked, if anything.
 *
 * The controls show what the radio reported until the operator picks something,
 * and only a pick that differs from the radio is sent. A region the operator did
 * not pick is never written: an apply for a preset on a radio in Europe must not
 * move it to US. With no LoRa config reported there is nothing to start from, so
 * there is nothing to apply.
 */
data class LoraPicks(val region: MeshRegion? = null, val preset: MeshChannelPreset? = null) {

    /** The region control's value: the pick, else the radio's own (null when it is not set or has no name here). */
    fun shownRegion(radio: RadioSettings?): MeshRegion? = region ?: radio?.region?.takeIf { it != MeshRegion.UNSET }

    /** The preset control's value: the pick, else the radio's own (null when it has no name here). */
    fun shownPreset(radio: RadioSettings?): MeshChannelPreset? = preset ?: radio?.channelPreset

    /** What to send, or null when no LoRa config is loaded or nothing was picked that differs from the radio. */
    fun toSend(radio: RadioSettings?): LoraSend? {
        if (radio?.loraLoaded != true) return null
        val newRegion = region?.takeIf { it != radio.region }
        val newPreset = preset?.takeIf { it != radio.channelPreset }
        if (newRegion == null && newPreset == null) return null
        return LoraSend(newRegion ?: MeshRegion.UNSET, newPreset)
    }
}
