package soy.engindearing.omnitak.mobile.domain

import android.util.Log
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import soy.engindearing.omnitak.mobile.data.AdminMessageSerializer
import soy.engindearing.omnitak.mobile.data.AdminMessageSerializer.AdminWrite
import soy.engindearing.omnitak.mobile.data.AdminReads
import soy.engindearing.omnitak.mobile.data.AdminSetting
import soy.engindearing.omnitak.mobile.data.AdminWriteResult
import soy.engindearing.omnitak.mobile.data.DeviceEdits
import soy.engindearing.omnitak.mobile.data.MeshChannel
import soy.engindearing.omnitak.mobile.data.MeshChannelPreset
import soy.engindearing.omnitak.mobile.data.MeshConnectionType
import soy.engindearing.omnitak.mobile.data.MeshRegion
import soy.engindearing.omnitak.mobile.data.ProtoFields
import soy.engindearing.omnitak.mobile.data.RadioSettingsCache
import soy.engindearing.omnitak.mobile.data.RadioSettingsCache.Key
import soy.engindearing.omnitak.mobile.data.RebroadcastMode
import soy.engindearing.omnitak.mobile.data.RefusalReason
import soy.engindearing.omnitak.mobile.data.SentLedger

/**
 * Writes settings to the attached radio, one setting at a time, each patched
 * onto what the radio holds at that moment.
 *
 * The firmware replaces a whole config (or channel, or owner) with what a
 * write carries, so a write has to be the radio's own message with one field
 * changed ([AdminMessageSerializer]). That message is not taken from memory:
 * another client may have changed it since the app last looked (a rotated
 * channel key would be put back by the next rename). Each step asks the radio
 * for the entry it is about to patch, waits for the answer to that request, and
 * patches that. The request carries its own random packet id ([AdminReads]), and
 * only an answer that quotes it, from the radio itself, ends the wait: not
 * whatever else arrives for the same entry. If no answer comes within
 * [readTimeoutMs] nothing is changed and the result says that the radio did
 * not answer, which is all that is known.
 *
 * A sequence runs inside `begin_edit_settings` / `commit_edit_settings`, a
 * single write included, so a transaction left open by a dropped link is
 * closed by the next write. The transaction is opened only when there is
 * something to write, and once open it is always committed, even after a
 * failure and even if the caller goes away.
 *
 * Steps run in the order given, the role first: when the role changes the
 * firmware installs role defaults (broadcast intervals among them), and a
 * position write patched onto bytes read before that would undo them. Because
 * each entry is read just before it is patched, the one after the role is read
 * after the defaults are in.
 *
 * What was sent is not recorded as what the radio holds. The written entries
 * are dropped from [cache], and the radio's next report (the re-read, or the
 * download after it restarts) says what it kept. The app remembers what it sent
 * in [ledger], so it can tell the operator when the radio did not take a value.
 *
 * Frames go out [frameSpacingMs] apart. The firmware keeps four inbound
 * packets waiting for its router thread and drops the oldest when a fifth
 * arrives, and nothing tells the app. Sent back to back over TCP to a
 * simulated radio, a batch of six frames lost its device config write and a
 * batch of seven lost its position config write: those changes never took
 * effect, and no error was reported anywhere. The same batches with 100 ms
 * between frames applied every write.
 *
 * One sequence at a time, reads of the whole config ([readAll]) included.
 *
 * Transport-free on purpose: [send] hands a framed ToRadio to whatever link is
 * up and says whether it got out, [destination] is the attached radio's node
 * number (null when there is no radio to address). The radio's answers do not
 * come back through [send]: whoever receives frames hands each one to [reads]
 * ([AdminReads.admit]), and an accepted answer completes the read waiting for it.
 */
class MeshSettingsWriter(
    private val cache: RadioSettingsCache,
    private val destination: () -> UInt?,
    private val send: suspend (ByteArray) -> Boolean,
    private val ledger: SentLedger = SentLedger(),
    private val frameSpacingMs: Long = ADMIN_FRAME_SPACING_MS,
    /** How long a read waits for its answer, asked at each read: it depends on the link ([readTimeoutFor]). */
    private val readTimeoutMs: () -> Long = { READ_TIMEOUT_MS },
    private val reads: AdminReads = AdminReads(),
) {
    private val turn = Mutex()

    /** The settings the operator edited, role first. Only these are written, each patched onto a fresh read. */
    suspend fun pushDeviceConfig(edits: DeviceEdits): AdminWriteResult {
        if (edits.isEmpty) return AdminWriteResult.NothingToChange
        return runSequence(edits.settings) {
            edits.role?.let { role ->
                patch(Key.Config(RadioSettingsCache.CONFIG_DEVICE), listOf(AdminSetting.ROLE), mapOf(AdminSetting.ROLE to role)) { dest, current ->
                    AdminMessageSerializer.buildSetDeviceRole(dest, role, current)
                } || return@runSequence
            }
            if (edits.longName != null || edits.shortName != null) {
                val longName = edits.longName.orEmpty()
                val shortName = edits.shortName.orEmpty()
                patchOwner(longName, shortName, isLicensed = null) || return@runSequence
            }
            edits.positionBroadcastSecs?.let { secs ->
                patch(
                    Key.Config(RadioSettingsCache.CONFIG_POSITION), listOf(AdminSetting.POSITION_INTERVAL),
                    mapOf(AdminSetting.POSITION_INTERVAL to secs.coerceIn(0, MAX_INTERVAL_SECS)),
                ) { dest, current ->
                    AdminMessageSerializer.buildSetPositionBroadcastSecs(dest, secs, current)
                } || return@runSequence
            }
            edits.channelName?.let { name ->
                patch(
                    Key.Channel(0), listOf(AdminSetting.CHANNEL_NAME),
                    mapOf(AdminSetting.CHANNEL_NAME to AdminMessageSerializer.clampChannelName(name)),
                ) { dest, current ->
                    AdminMessageSerializer.buildSetChannel0Name(dest, name, current)
                } || return@runSequence
            }
            edits.channelPreset?.let { preset ->
                patch(Key.Config(RadioSettingsCache.CONFIG_LORA), listOf(AdminSetting.MODEM_PRESET), mapOf(AdminSetting.MODEM_PRESET to preset)) { dest, current ->
                    AdminMessageSerializer.buildSetLoraPreset(dest, preset, current)
                } || return@runSequence
            }
        }
    }

    /** `set_config { device { rebroadcast_mode } }`: the radio's other device settings are carried over. */
    suspend fun applyRebroadcastMode(mode: RebroadcastMode): AdminWriteResult =
        runSequence(listOf(AdminSetting.REBROADCAST_MODE)) {
            patch(
                Key.Config(RadioSettingsCache.CONFIG_DEVICE), listOf(AdminSetting.REBROADCAST_MODE),
                mapOf(AdminSetting.REBROADCAST_MODE to mode),
            ) { dest, current ->
                AdminMessageSerializer.buildSetRebroadcastMode(dest, mode, current)
            }
        }

    /**
     * `set_config { lora { use_preset, modem_preset, region } }`. A [region] of [MeshRegion.UNSET] and a null
     * [preset] each leave that setting as the radio has it; every other LoRa setting (hop limit, transmit
     * switch, ...) is carried over either way.
     */
    suspend fun applyLoRaConfig(
        region: MeshRegion,
        preset: MeshChannelPreset?,
        usePreset: Boolean = true,
    ): AdminWriteResult {
        val settings = listOfNotNull(
            AdminSetting.REGION.takeIf { region != MeshRegion.UNSET },
            AdminSetting.MODEM_PRESET.takeIf { preset != null },
        )
        if (settings.isEmpty()) return AdminWriteResult.NothingToChange
        val expected = buildMap<AdminSetting, Any> {
            if (region != MeshRegion.UNSET) put(AdminSetting.REGION, region)
            if (preset != null) put(AdminSetting.MODEM_PRESET, preset)
        }
        return runSequence(settings) {
            patch(Key.Config(RadioSettingsCache.CONFIG_LORA), settings, expected) { dest, current ->
                AdminMessageSerializer.buildSetLoRaConfig(dest, region, preset, current, usePreset)
            }
        }
    }

    /**
     * `set_owner`: the long and short name. A blank name is left as the radio
     * has it. [isLicensed] null keeps the radio's flag, which is what a rename
     * wants.
     */
    suspend fun applyOwner(longName: String, shortName: String, isLicensed: Boolean? = null): AdminWriteResult {
        val settings = ownerSettings(longName, shortName)
        if (settings.isEmpty() && isLicensed == null) return AdminWriteResult.NothingToChange
        return runSequence(settings) { patchOwner(longName, shortName, isLicensed) }
    }

    /**
     * #172: import a channel. A full replacement by design (new name, new key), so the target slot is not read,
     * but which slot to replace is: it goes into the first free secondary slot, found by reading slots 1 to 7
     * from the radio. The primary is replaced only when [replacePrimary] says the operator asked for that.
     */
    suspend fun applyChannel(channel: MeshChannel, replacePrimary: Boolean = false): AdminWriteResult =
        runSequence(listOf(AdminSetting.CHANNEL)) {
            val slot = if (replacePrimary) 0 else firstFreeSecondary() ?: return@runSequence
            val write = AdminMessageSerializer.buildSetChannelWrite(dest, channel, slot)
            emit(write, Key.Channel(slot), listOf(AdminSetting.CHANNEL), emptyMap())
        }

    /**
     * Ask the radio for its owner, device, position and LoRa config and all eight channels. The answers arrive
     * in [cache] and as reports. Takes its turn like a write and spaces the requests the same way: twelve sent
     * back to back got four answers from a simulated radio. Returns how many requests went out.
     */
    suspend fun readAll(): Int = turn.withLock {
        val dest = destination() ?: return@withLock 0
        val keys = listOf(Key.Owner, Key.Config(RadioSettingsCache.CONFIG_DEVICE), Key.Config(RadioSettingsCache.CONFIG_POSITION),
            Key.Config(RadioSettingsCache.CONFIG_LORA)) + (0 until RadioSettingsCache.MAX_CHANNELS).map { Key.Channel(it) }
        var sent = 0
        for (key in keys) {
            if (sent > 0 && frameSpacingMs > 0) delay(frameSpacingMs)
            // Each request is recorded, so the radio's answer to it is recognised when it comes.
            val request = reads.open(key, readTimeoutMs())
            if (!send(readRequest(dest, key, request.id))) {
                reads.cancel(request.id)
                break
            }
            sent++
        }
        sent
    }

    /** The ToRadio frame that asks the radio for [key], with [id] as its packet id. */
    private fun readRequest(dest: UInt, key: Key, id: UInt): ByteArray = when (key) {
        // Config variants are numbered from 1 in the oneof and from 0 in ConfigType.
        is Key.Config -> AdminMessageSerializer.buildGetConfigRequest(dest, key.variant - 1, id)
        is Key.Channel -> AdminMessageSerializer.buildGetChannelRequest(dest, key.index, id)
        Key.Owner -> AdminMessageSerializer.buildGetOwnerRequest(dest, id)
    }

    // region One sequence ---------------------------------------------------------

    private suspend fun runSequence(
        all: List<AdminSetting>,
        body: suspend Run.() -> Unit,
    ): AdminWriteResult = turn.withLock {
        // Once the sequence starts it runs to its commit even if the caller goes away (a screen that is left
        // mid-push): a transaction left open would swallow the next edit from any client.
        withContext(NonCancellable) {
            val dest = destination()
            if (dest == null) {
                Log.w(TAG, "write refused, nothing sent: no radio to address")
                return@withContext AdminWriteResult.Refused(RefusalReason.NO_RADIO)
            }
            val run = Run(dest, all)
            run.body()
            run.finish()
        }
    }

    private enum class Stop { LINK, NO_ANSWER, UNREADABLE, NO_FREE_SLOT }

    private inner class Run(val dest: UInt, private val all: List<AdminSetting>) {
        private var framesSent = 0
        private var begun = false
        private var stop: Stop? = null
        private val written = ArrayList<AdminSetting>()
        private val alreadySet = ArrayList<AdminSetting>()

        /** Read [key] from the radio, patch it with [build], and write the result unless the radio already holds it. False when the sequence must stop. */
        suspend fun patch(
            key: Key,
            settings: List<AdminSetting>,
            expected: Map<AdminSetting, Any>,
            build: (dest: UInt, current: ByteArray) -> AdminWrite?,
        ): Boolean {
            val current = read(key) ?: return false
            val write = build(dest, current)
            if (write == null) {
                stop = Stop.UNREADABLE
                return false
            }
            if (write.message.contentEquals(current)) {
                // Someone else already set it: nothing to send for this one.
                alreadySet += settings
                return true
            }
            return emit(write, key, settings, expected)
        }

        suspend fun patchOwner(longName: String, shortName: String, isLicensed: Boolean?): Boolean {
            val settings = ownerSettings(longName, shortName)
            val expected = buildMap<AdminSetting, Any> {
                if (longName.isNotBlank()) put(AdminSetting.LONG_NAME, AdminMessageSerializer.clampLongName(longName))
                if (shortName.isNotBlank()) put(AdminSetting.SHORT_NAME, AdminMessageSerializer.clampShortName(shortName))
            }
            return patch(Key.Owner, settings, expected) { d, current ->
                AdminMessageSerializer.buildSetOwner(d, longName, shortName, current, isLicensed)
            }
        }

        /** Send one write inside the transaction (opened now if it is not open yet). */
        suspend fun emit(write: AdminWrite, key: Key, settings: List<AdminSetting>, expected: Map<AdminSetting, Any>): Boolean {
            if (!begun) {
                if (!frame(AdminMessageSerializer.buildBeginEditSettings(dest))) {
                    stop = Stop.LINK
                    return false
                }
                begun = true
            }
            if (!frame(write.frame)) {
                stop = Stop.LINK
                return false
            }
            // Not stored as what the radio holds: the next report says what it kept.
            cache.remove(key)
            written += settings
            expected.forEach { (setting, value) -> ledger.expect(dest, setting, value) }
            return true
        }

        /** The first channel slot from 1 to 7 whose role is DISABLED, read from the radio. Null (with the reason recorded) when there is none. */
        suspend fun firstFreeSecondary(): Int? {
            for (slot in 1 until RadioSettingsCache.MAX_CHANNELS) {
                val channel = read(Key.Channel(slot)) ?: return null
                val fields = ProtoFields.parse(channel)
                if (fields == null) {
                    stop = Stop.UNREADABLE
                    return null
                }
                // Channel.role is field 3, and 0 (DISABLED) is not on the wire at all.
                if ((ProtoFields.lastVarint(fields, CHANNEL_ROLE) ?: 0uL) == 0uL) return slot
            }
            stop = Stop.NO_FREE_SLOT
            return null
        }

        /**
         * Ask the radio for [key] and wait for the answer to this request. The request has a packet id of its own,
         * and only an answer that quotes it ([AdminReads.admit]) ends the wait, with that answer's own bytes: an
         * older answer for the same entry, or anything else that arrives meanwhile, does not.
         */
        private suspend fun read(key: Key): ByteArray? {
            pace()
            val timeout = readTimeoutMs()
            val request = reads.open(key, timeout, awaited = true)
            if (!send(readRequest(dest, key, request.id))) {
                reads.cancel(request.id)
                stop = Stop.LINK
                return null
            }
            val answer = withTimeoutOrNull(timeout) { request.answer?.await() }
            reads.cancel(request.id)
            if (answer == null) {
                stop = Stop.NO_ANSWER
                return null
            }
            return answer
        }

        /** Wait out the gap that follows a frame sent before this one, and count this one. */
        private suspend fun pace() {
            if (framesSent > 0 && frameSpacingMs > 0) delay(frameSpacingMs)
            framesSent++
        }

        /** Send one frame, a frame gap after the one before. */
        private suspend fun frame(bytes: ByteArray): Boolean {
            pace()
            return send(bytes)
        }

        suspend fun finish(): AdminWriteResult {
            // Once the radio was told to expect changes, tell it to save them, whatever happened since.
            val committed = begun && frame(AdminMessageSerializer.buildCommitEditSettings(dest))
            val notWritten = all.filter { it !in written && it !in alreadySet }
            val reason = stop
            Log.i(TAG, "sequence: written=$written alreadySet=$alreadySet notWritten=$notWritten stop=$reason committed=$committed")
            if (reason == null) {
                return when {
                    !begun -> AdminWriteResult.NothingToChange
                    committed -> AdminWriteResult.Sent(written.toList())
                    else -> AdminWriteResult.Incomplete(
                        written.toList(), emptyList(), AdminWriteResult.Incomplete.Cause.LINK_LOST, committed = false,
                    )
                }
            }
            if (!begun) {
                Log.w(TAG, "write refused, nothing sent: $reason")
                return AdminWriteResult.Refused(
                    when (reason) {
                        Stop.LINK -> RefusalReason.NO_RADIO
                        Stop.NO_ANSWER -> RefusalReason.NO_ANSWER
                        Stop.UNREADABLE -> RefusalReason.UNREADABLE
                        Stop.NO_FREE_SLOT -> RefusalReason.NO_FREE_SLOT
                    },
                )
            }
            return AdminWriteResult.Incomplete(
                written.toList(), notWritten,
                when (reason) {
                    Stop.LINK -> AdminWriteResult.Incomplete.Cause.LINK_LOST
                    Stop.NO_ANSWER, Stop.NO_FREE_SLOT -> AdminWriteResult.Incomplete.Cause.NO_ANSWER
                    Stop.UNREADABLE -> AdminWriteResult.Incomplete.Cause.UNREADABLE
                },
                committed,
            )
        }
    }

    private fun ownerSettings(longName: String, shortName: String): List<AdminSetting> = listOfNotNull(
        AdminSetting.LONG_NAME.takeIf { longName.isNotBlank() },
        AdminSetting.SHORT_NAME.takeIf { shortName.isNotBlank() },
    )

    // endregion

    companion object {
        private const val TAG = "MeshSettings"

        /**
         * Gap between admin frames sent in a row, for write sequences and for read requests. Measured on a
         * simulated radio: twelve read requests with no gap got four answers, with 20 ms between them eleven,
         * with 50 ms or 100 ms all twelve. Write batches lost writes with no gap and lost none with 100 ms
         * (see the class doc).
         */
        const val ADMIN_FRAME_SPACING_MS = 100L

        /**
         * How long a read waits for the radio's answer before the write is refused, over TCP. Measured on a
         * simulated radio: answers come in well under a second.
         */
        const val READ_TIMEOUT_MS = 3_000L

        /**
         * The same over Bluetooth. An answer waits there for the notification-driven drain or the one second poll
         * of the BLE client, behind whatever else the radio has queued for the phone, and the client itself only
         * calls a link dead after 10 to 15 seconds. Not measured on a real link.
         */
        const val READ_TIMEOUT_BLE_MS = 8_000L

        /** The read timeout for the link in use: [transport] null (no transport yet) or TCP get [READ_TIMEOUT_MS]. */
        fun readTimeoutFor(transport: MeshConnectionType?): Long =
            if (transport == MeshConnectionType.BLUETOOTH) READ_TIMEOUT_BLE_MS else READ_TIMEOUT_MS

        private const val MAX_INTERVAL_SECS = 24 * 60 * 60

        private const val CHANNEL_ROLE = 3 // Channel.role (channel.proto)
    }
}
