package soy.engindearing.omnitak.mobile.domain

import android.util.Log
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import soy.engindearing.omnitak.mobile.data.AdminMessageParser
import soy.engindearing.omnitak.mobile.data.AdminMessageSerializer
import soy.engindearing.omnitak.mobile.data.AdminMessageSerializer.AdminWrite
import soy.engindearing.omnitak.mobile.data.AdminWriteResult
import soy.engindearing.omnitak.mobile.data.MeshChannel
import soy.engindearing.omnitak.mobile.data.MeshChannelPreset
import soy.engindearing.omnitak.mobile.data.MeshDeviceConfig
import soy.engindearing.omnitak.mobile.data.MeshRegion
import soy.engindearing.omnitak.mobile.data.ProtoFields
import soy.engindearing.omnitak.mobile.data.RadioSettingsCache
import soy.engindearing.omnitak.mobile.data.RadioSettingsCache.Key
import soy.engindearing.omnitak.mobile.data.RebroadcastMode
import soy.engindearing.omnitak.mobile.data.RefusalReason

/**
 * Writes settings to the attached radio: read, change one field, write the
 * whole thing back.
 *
 * The firmware replaces a whole config (or channel, or owner) with what a
 * write carries, so every write here starts from what the radio last
 * reported ([cache]) and changes only what was asked for
 * ([AdminMessageSerializer]). Without that entry in the cache nothing is sent:
 * a write built from scratch would reset every field it does not mention.
 * After a write goes out, what was sent is stored in the cache, so a second
 * edit builds on the first.
 *
 * Transport-free on purpose: [send] hands a framed ToRadio to whatever link is
 * up and says whether it got out, and [destination] is the attached radio's
 * node number (null when there is no radio to address).
 *
 * The frames of a batch go out [frameSpacingMs] apart. The firmware keeps four
 * inbound packets waiting for its router thread and drops the oldest when a
 * fifth arrives, and nothing tells the app. Sent back to back over TCP to a
 * simulated radio, a batch of six frames lost its device config write and a
 * batch of seven lost its position config write: those changes never took
 * effect, and no error was reported anywhere. The same batches with 100 ms
 * between frames applied every write.
 */
class MeshSettingsWriter(
    private val cache: RadioSettingsCache,
    private val destination: () -> UInt?,
    private val send: suspend (ByteArray) -> Boolean,
    private val frameSpacingMs: Long = ADMIN_FRAME_SPACING_MS,
) {

    /** `set_config { device { rebroadcast_mode } }`: the radio's other device settings are carried over. */
    suspend fun applyRebroadcastMode(mode: RebroadcastMode): AdminWriteResult =
        writeOne("rebroadcast mode", Key.Config(RadioSettingsCache.CONFIG_DEVICE)) { dest, current ->
            AdminMessageSerializer.buildSetRebroadcastMode(dest, mode, current)
        }

    /**
     * `set_config { lora { use_preset, modem_preset, region } }`. A region of
     * [MeshRegion.UNSET] leaves the radio's region as it is; every other LoRa
     * setting (hop limit, transmit switch, ...) is carried over either way.
     */
    suspend fun applyLoRaConfig(
        region: MeshRegion,
        preset: MeshChannelPreset,
        usePreset: Boolean = true,
    ): AdminWriteResult =
        writeOne("LoRa config", Key.Config(RadioSettingsCache.CONFIG_LORA)) { dest, current ->
            AdminMessageSerializer.buildSetLoRaConfig(dest, region, preset, current, usePreset)
        }

    /**
     * `set_owner`: the long and short name. A blank name is left as the radio
     * has it. [isLicensed] null keeps the radio's flag, which is what a rename
     * wants.
     */
    suspend fun applyOwner(longName: String, shortName: String, isLicensed: Boolean? = null): AdminWriteResult {
        if (longName.isBlank() && shortName.isBlank() && isLicensed == null) return AdminWriteResult.NothingToChange
        return writeOne("owner", Key.Owner) { dest, current ->
            AdminMessageSerializer.buildSetOwner(dest, longName, shortName, current, isLicensed)
        }
    }

    /**
     * #172: put an imported channel in slot [index]. A full replacement by
     * design (new name, new key), so it needs nothing from the radio first.
     */
    suspend fun applyChannel(channel: MeshChannel, index: Int = 0): AdminWriteResult {
        val dest = destination() ?: return refuse("channel import", RefusalReason.NO_RADIO)
        val write = AdminMessageSerializer.buildSetChannelWrite(dest, channel, index)
        if (!send(write.frame)) return AdminWriteResult.LinkFailed(sent = 0, total = 1)
        // The slot now holds exactly what was sent: a rename right after the import must not start from the old key.
        cache.put(Key.Channel(index.coerceIn(0, RadioSettingsCache.MAX_CHANNELS - 1)), write.message)
        return AdminWriteResult.Sent(1)
    }

    /**
     * Push the Device Settings draft to the radio: write only what differs
     * from the radio's current values, in one edit transaction, so the radio
     * saves and reboots once.
     *
     * "Differs" is judged against the radio's own settings (decoded from
     * [cache]), not against defaults. A setting the radio holds as something
     * this app has no name for (a role or preset it does not list) is left
     * alone, because the draft cannot hold that value and would overwrite it
     * every time. If any setting that has to change cannot be written (the
     * radio's settings are missing or unreadable) nothing at all is sent.
     */
    suspend fun pushDeviceConfig(draft: MeshDeviceConfig): AdminWriteResult {
        val dest = destination() ?: return refuse("push", RefusalReason.NO_RADIO)

        val steps = listOf(
            planOwner(dest, draft),
            planRole(dest, draft),
            planPositionInterval(dest, draft),
            planChannelName(dest, draft),
            planPreset(dest, draft),
        )
        steps.filterIsInstance<Step.Stop>().firstOrNull()?.let { return refuse("push", it.reason) }
        val plans = steps.filterIsInstance<Step.Write>().map { it.plan }
        if (plans.isEmpty()) {
            Log.i(TAG, "push: the radio already has every setting in the draft, nothing sent")
            return AdminWriteResult.NothingToChange
        }

        // Once begin is out the batch runs to its commit even if the caller goes away (a screen that is left
        // mid-push): a transaction left open would swallow the next edit from any client.
        return withContext(NonCancellable) {
            if (!send(AdminMessageSerializer.buildBeginEditSettings(dest))) {
                return@withContext AdminWriteResult.LinkFailed(sent = 0, total = plans.size)
            }
            var sent = 0
            for (plan in plans) {
                pace()
                // Stop at the first write that does not get out, but still close the transaction below.
                if (!send(plan.write.frame)) break
                cache.put(plan.key, plan.write.message)
                sent++
            }
            // The radio holds its saves, and its reboot, until this arrives. Send it even after a failed write.
            pace()
            val committed = send(AdminMessageSerializer.buildCommitEditSettings(dest))
            Log.i(TAG, "push: ${plans.map { it.setting }} -> sent $sent of ${plans.size}, committed=$committed")
            if (sent == plans.size && committed) {
                AdminWriteResult.Sent(plans.size)
            } else {
                AdminWriteResult.LinkFailed(sent = sent, total = plans.size)
            }
        }
    }

    /** Leave the radio time to take the frame before this one. */
    private suspend fun pace() {
        if (frameSpacingMs > 0) delay(frameSpacingMs)
    }

    // region One write ------------------------------------------------------

    private suspend fun writeOne(
        setting: String,
        key: Key,
        build: (dest: UInt, current: ByteArray) -> AdminWrite?,
    ): AdminWriteResult {
        val dest = destination() ?: return refuse(setting, RefusalReason.NO_RADIO)
        val current = cache.get(key) ?: return refuse(setting, RefusalReason.NOT_LOADED)
        val write = build(dest, current) ?: return refuse(setting, RefusalReason.UNREADABLE)
        if (!send(write.frame)) return AdminWriteResult.LinkFailed(sent = 0, total = 1)
        cache.put(key, write.message)
        return AdminWriteResult.Sent(1)
    }

    private fun refuse(setting: String, reason: RefusalReason): AdminWriteResult {
        Log.w(TAG, "write refused, nothing sent: $setting ($reason)")
        return AdminWriteResult.Refused(reason)
    }

    // endregion

    // region Planning the push ----------------------------------------------

    private class Plan(val setting: String, val key: Key, val write: AdminWrite)

    private sealed interface Step {
        /** The radio already has this one. */
        data object Skip : Step

        class Write(val plan: Plan) : Step

        /** This one has to change and cannot be written, so the push as a whole is refused. */
        class Stop(val reason: RefusalReason) : Step
    }

    private fun planOwner(dest: UInt, draft: MeshDeviceConfig): Step {
        val longName = AdminMessageSerializer.clampUtf8(draft.longName, AdminMessageSerializer.MAX_LONG_NAME_BYTES)
        val shortName = AdminMessageSerializer.clampUtf8(draft.shortName, AdminMessageSerializer.MAX_SHORT_NAME_BYTES)
        // A blank name means "leave it", so a draft with neither has nothing to say.
        if (longName.isBlank() && shortName.isBlank()) return Step.Skip
        val current = cache.get(Key.Owner) ?: return Step.Stop(RefusalReason.NOT_LOADED)
        val fields = ProtoFields.parse(current) ?: return Step.Stop(RefusalReason.UNREADABLE)
        val haveLong = ProtoFields.lastString(fields, USER_LONG_NAME) ?: ""
        val haveShort = ProtoFields.lastString(fields, USER_SHORT_NAME) ?: ""
        val changed = (longName.isNotBlank() && longName != haveLong) || (shortName.isNotBlank() && shortName != haveShort)
        if (!changed) return Step.Skip
        val write = AdminMessageSerializer.buildSetOwner(dest, draft.longName, draft.shortName, current)
            ?: return Step.Stop(RefusalReason.UNREADABLE)
        return Step.Write(Plan("owner", Key.Owner, write))
    }

    private fun planRole(dest: UInt, draft: MeshDeviceConfig): Step {
        val key = Key.Config(RadioSettingsCache.CONFIG_DEVICE)
        val current = cache.get(key) ?: return Step.Stop(RefusalReason.NOT_LOADED)
        val fields = ProtoFields.parse(current) ?: return Step.Stop(RefusalReason.UNREADABLE)
        val haveOrdinal = ProtoFields.lastVarint(fields, DEVICE_ROLE) ?: 0uL
        val have = ordinalOrNull(haveOrdinal)?.let { AdminMessageParser.roleFromOrdinal(it) }
        if (have == null) {
            Log.i(TAG, "push: the radio's role ($haveOrdinal) is not one this app lists, left as it is")
            return Step.Skip
        }
        if (have == draft.role) return Step.Skip
        val write = AdminMessageSerializer.buildSetDeviceRole(dest, draft.role, current)
            ?: return Step.Stop(RefusalReason.UNREADABLE)
        return Step.Write(Plan("role", key, write))
    }

    private fun planPositionInterval(dest: UInt, draft: MeshDeviceConfig): Step {
        val key = Key.Config(RadioSettingsCache.CONFIG_POSITION)
        val current = cache.get(key) ?: return Step.Stop(RefusalReason.NOT_LOADED)
        val fields = ProtoFields.parse(current) ?: return Step.Stop(RefusalReason.UNREADABLE)
        val have = ProtoFields.lastVarint(fields, POSITION_BROADCAST_SECS) ?: 0uL
        val want = draft.positionBroadcastSecs.coerceIn(0, 24 * 60 * 60).toULong()
        if (have == want) return Step.Skip
        val write = AdminMessageSerializer.buildSetPositionBroadcastSecs(dest, draft.positionBroadcastSecs, current)
            ?: return Step.Stop(RefusalReason.UNREADABLE)
        return Step.Write(Plan("position interval", key, write))
    }

    private fun planChannelName(dest: UInt, draft: MeshDeviceConfig): Step {
        val key = Key.Channel(0)
        val current = cache.get(key) ?: return Step.Stop(RefusalReason.NOT_LOADED)
        val fields = ProtoFields.parse(current) ?: return Step.Stop(RefusalReason.UNREADABLE)
        val settings = ProtoFields.lastBytes(fields, CHANNEL_SETTINGS)
            ?.let { ProtoFields.parse(it) ?: return Step.Stop(RefusalReason.UNREADABLE) }
            ?: emptyList()
        val have = ProtoFields.lastString(settings, SETTINGS_NAME) ?: ""
        val want = AdminMessageSerializer.clampUtf8(draft.channelName, AdminMessageSerializer.MAX_CHANNEL_NAME_BYTES)
        if (have == want) return Step.Skip
        val write = AdminMessageSerializer.buildSetChannel0Name(dest, draft.channelName, current)
            ?: return Step.Stop(RefusalReason.UNREADABLE)
        return Step.Write(Plan("channel 0 name", key, write))
    }

    private fun planPreset(dest: UInt, draft: MeshDeviceConfig): Step {
        val key = Key.Config(RadioSettingsCache.CONFIG_LORA)
        val current = cache.get(key) ?: return Step.Stop(RefusalReason.NOT_LOADED)
        val fields = ProtoFields.parse(current) ?: return Step.Stop(RefusalReason.UNREADABLE)
        val haveOrdinal = ProtoFields.lastVarint(fields, LORA_MODEM_PRESET) ?: 0uL
        val have = ordinalOrNull(haveOrdinal)?.let { AdminMessageParser.presetFromOrdinal(it) }
        if (have == null) {
            Log.i(TAG, "push: the radio's modem preset ($haveOrdinal) is not one this app lists, left as it is")
            return Step.Skip
        }
        if (have == draft.channelPreset) return Step.Skip
        val write = AdminMessageSerializer.buildSetLoraPreset(dest, draft.channelPreset, current)
            ?: return Step.Stop(RefusalReason.UNREADABLE)
        return Step.Write(Plan("modem preset", key, write))
    }

    private fun ordinalOrNull(value: ULong): Int? = if (value <= Int.MAX_VALUE.toULong()) value.toInt() else null

    // endregion

    companion object {
        private const val TAG = "MeshSettings"

        /**
         * Gap between admin frames sent in a row, for write batches and for read requests. Measured on a simulated
         * radio: twelve read requests with no gap got four answers, with 20 ms between them eleven, with 50 ms or
         * 100 ms all twelve. Write batches lost writes with no gap and lost none with 100 ms (see the class doc).
         */
        const val ADMIN_FRAME_SPACING_MS = 100L

        // Field numbers read back from the radio's own messages (config.proto, channel.proto, mesh.proto).
        private const val DEVICE_ROLE = 1
        private const val POSITION_BROADCAST_SECS = 1
        private const val LORA_MODEM_PRESET = 2
        private const val CHANNEL_SETTINGS = 2
        private const val SETTINGS_NAME = 3
        private const val USER_LONG_NAME = 2
        private const val USER_SHORT_NAME = 3
    }
}
