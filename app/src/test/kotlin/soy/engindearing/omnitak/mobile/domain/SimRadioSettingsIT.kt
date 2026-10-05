package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.AdminResponse
import soy.engindearing.omnitak.mobile.data.AdminSetting
import soy.engindearing.omnitak.mobile.data.AdminTestFrames
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.Field
import soy.engindearing.omnitak.mobile.data.AdminWriteResult
import soy.engindearing.omnitak.mobile.data.DeviceEdits
import soy.engindearing.omnitak.mobile.data.DeviceSettingsState
import soy.engindearing.omnitak.mobile.data.InterruptedWrite
import soy.engindearing.omnitak.mobile.data.MeshChannel
import soy.engindearing.omnitak.mobile.data.MeshChannelPreset
import soy.engindearing.omnitak.mobile.data.MeshDeviceConfig
import soy.engindearing.omnitak.mobile.data.MeshRole
import soy.engindearing.omnitak.mobile.data.MeshWire
import soy.engindearing.omnitak.mobile.data.PositionFloor
import soy.engindearing.omnitak.mobile.data.ProtoMsg
import soy.engindearing.omnitak.mobile.data.RadioSettingsCache
import soy.engindearing.omnitak.mobile.data.RebroadcastMode
import java.util.Random

/**
 * Settings writes against a real firmware: the app's own transport, parser,
 * cache, state, builders and writer talking to a simulated radio (meshtasticd).
 *
 * Opt in with `MESHSIM_HOST=<host>` (the radio's TCP API is on port 4403).
 * Without it this test is skipped, so CI and a plain `testDebugUnitTest` never
 * touch a radio:
 *
 *     MESHSIM_HOST=127.0.0.1 ./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest \
 *         --tests '*SimRadioSettingsIT*'
 *
 * It takes several minutes: every write ends in a reboot of the radio (about
 * 15 s) and the test reconnects and downloads the config again each time.
 *
 * The test refuses to write to anything that is not a simulator: before its
 * first write it checks that the radio's hardware model is PORTDUINO and skips
 * otherwise.
 *
 * What it does, in order:
 *  1. connect, run the config download through the app's parser and cache, and
 *     check the peer is a simulator;
 *  2. the case that was reported from the app: the settings state starts at a
 *     fresh install's defaults (TAK, "OmniTAK") like the app's does and is
 *     synced from the radio; on a factory-like radio (role CLIENT, unnamed
 *     primary channel) change only the position interval, and assert the role
 *     and the channel name are untouched;
 *  3. ask for the settings with get_*_request and check the answers reach the
 *     cache (so the read-back rule accepts the radio's own answers: each read
 *     has its own packet id, and the radio's answer quotes it);
 *  3b. an interval above the app's limit, as another client can leave it: the
 *     screen shows the radio's own value, it is not an edit, and a push of the
 *     role alone leaves it as it is;
 *  4. arrange a radio that has something to lose: write values into the fields
 *     the old partial writes used to reset (region, hop limit, transmit switch,
 *     GPS mode, position flags, channel key and precision, time zone, ...). The
 *     arranging writes are built here from the official field numbers, not by
 *     the app's builders;
 *  5. make each change through the app, deciding what to send from the state
 *     the way the screen does (a position interval, a LoRa preset, a channel 0
 *     rename, a rebroadcast mode, an owner rename, one push of four settings,
 *     an imported channel), let the radio reboot, download again, and assert that
 *     exactly the intended fields changed and every other field read the same as
 *     before. Right after each write the app reads the radio again and must have
 *     nothing to report about a value the radio kept;
 *  6. put back what the test changed, and assert the radio reads as it did when
 *     the test started. One thing cannot be put back through the settings the app
 *     writes: a radio that had no region generates its key pair the first time it
 *     boots with one (step 4 sets a region), and the owner then carries a public
 *     key. The final comparison leaves that one field out in that case, and the
 *     radio keeps the key pair.
 *
 * A second test, [a_short_position_interval_on_the_default_channel_is_raised_and_the_app_says_so], runs the position
 * floor against the real firmware: on the stock radio's default channel a 120 s interval is put back to one hour
 * when the radio restarts, the app says so before the push and after the restart (one manager across the
 * restart, as in the app), and with a private key on the primary channel, or a name that is not the preset's, or no
 * position precision, the radio keeps the interval and the app says nothing.
 *
 * A third test, [an_interrupted_sequence_is_closed_or_given_up_on_at_the_next_link_up], drops a sequence's commit
 * and runs what the app does at the next link-up on a radio that does not count its restarts (the simulator's
 * my_info has no reboot_count): while the radio still holds the transaction in memory the app finds its change in
 * the download and commits, and after a restart that lost it the app sends nothing and says it was not saved.
 *
 * Never prints or logs key bytes: channel keys are only ever compared by length.
 */
class SimRadioSettingsIT {

    private val host: String? = System.getenv("MESHSIM_HOST")?.takeIf { it.isNotBlank() }

    // region Reading the radio's messages (test side, ProtoReader based) ------------------

    /** One message read as fields. Values are looked up by field number; the last occurrence wins. */
    private class Msg(val bytes: ByteArray) {
        val fields: List<Field> = AdminTestFrames.fields(bytes)
        fun varint(n: Int): Long? = fields.lastOrNull { it.number == n && it.wire == 0 }?.varint?.toLong()
        fun fixed32(n: Int): Long? = fields.lastOrNull { it.number == n && it.wire == 5 }?.fixed32?.toLong()
        fun raw(n: Int): ByteArray? = fields.lastOrNull { it.number == n && it.wire == 2 }?.bytes
        fun string(n: Int): String? = raw(n)?.toString(Charsets.UTF_8)
        fun sub(n: Int): Msg? = raw(n)?.let { Msg(it) }
    }

    /**
     * Everything the test watches, read from a fresh download. Keys appear as
     * lengths only. Each entry is a named, printable value.
     */
    private fun view(cache: RadioSettingsCache): LinkedHashMap<String, Any?> {
        val device = Msg(cache.config(RadioSettingsCache.CONFIG_DEVICE)!!)
        val position = Msg(cache.config(RadioSettingsCache.CONFIG_POSITION)!!)
        val lora = Msg(cache.config(RadioSettingsCache.CONFIG_LORA)!!)
        val channel = Msg(cache.channel(0)!!)
        val settings = channel.sub(2)
        val module = settings?.sub(7)
        val owner = Msg(cache.owner()!!)
        val slot1 = cache.channel(1)?.let { Msg(it) }
        return linkedMapOf(
            "device.role" to device.varint(1),
            "device.rebroadcast_mode" to device.varint(6),
            "device.node_info_broadcast_secs" to device.varint(7),
            "device.tzdef" to device.string(11),
            "device.led_heartbeat_disabled" to device.varint(12),
            "position.position_broadcast_secs" to position.varint(1),
            "position.smart_enabled" to position.varint(2),
            "position.gps_update_interval" to position.varint(5),
            "position.position_flags" to position.varint(7),
            "position.smart_min_distance" to position.varint(10),
            "position.smart_min_interval_secs" to position.varint(11),
            "position.gps_mode" to position.varint(13),
            "lora.use_preset" to lora.varint(1),
            "lora.modem_preset" to lora.varint(2),
            "lora.region" to lora.varint(7),
            "lora.hop_limit" to lora.varint(8),
            "lora.tx_enabled" to lora.varint(9),
            "lora.tx_power" to lora.varint(10),
            "lora.sx126x_rx_boosted_gain" to lora.varint(13),
            "channel0.role" to channel.varint(3),
            "channel0.name" to settings?.string(3),
            "channel0.key_length" to settings?.raw(2)?.size,
            "channel0.position_precision" to module?.varint(1),
            "channel0.uplink_enabled" to settings?.varint(5),
            "channel0.id" to settings?.fixed32(4),
            "channel1.role" to slot1?.varint(3),
            "channel1.name" to slot1?.sub(2)?.string(3),
            "owner.long_name" to owner.string(2),
            "owner.short_name" to owner.string(3),
            "owner.is_licensed" to owner.varint(6),
            "owner.is_unmessagable" to owner.varint(9),
            "owner.public_key_length" to owner.raw(8)?.size,
            "owner.hw_model" to owner.varint(5),
            "owner.role" to owner.varint(7),
        )
    }

    private fun diff(before: Map<String, Any?>, after: Map<String, Any?>): Map<String, Pair<Any?, Any?>> =
        before.keys.filter { before[it] != after[it] }.associateWith { before[it] to after[it] }

    // endregion

    // region The app's settings state, across connections --------------------------------------

    /**
     * What the app's device settings store holds: a draft that starts at a fresh install's defaults, and what
     * the radio last reported. The sessions below feed it the way the app wires it (reports in, link drops).
     */
    private class AppState {
        private var current = DeviceSettingsState()
        val state: DeviceSettingsState @Synchronized get() = current

        @Synchronized fun report(response: AdminResponse) {
            current = current.withReport(response)
        }

        @Synchronized fun linkDown() {
            current = current.withLinkDown()
        }

        /** What the screen would send if the operator changed the draft as [change] says, judged against the radio. */
        fun edits(change: MeshDeviceConfig.() -> MeshDeviceConfig): DeviceEdits {
            val s = state
            return s.edits(s.draft.change())
        }
    }

    private val app = AppState()

    // endregion

    // region One connection to the radio ------------------------------------------------------

    /** A session: a fresh app-side manager (its own TCP client, parser and cache) connected to the radio. */
    private inner class Session(private val host: String) : AutoCloseable {
        val mgr = MeshtasticManager().also {
            it.adminResponseSink = { response -> app.report(response) }
            it.linkDownSink = { app.linkDown() }
        }

        /**
         * The part of the config download the app uses is in once the three configs it patches, channel 0 and the
         * owner are cached, and the settings state has been told everything the screen shows. The rest of the
         * stream (configs the app does not keep, module configs, node entries) is waited out by [awaitQuiet].
         */
        private fun downloaded(): Boolean = mgr.radioSettings.run {
            config(RadioSettingsCache.CONFIG_DEVICE) != null &&
                config(RadioSettingsCache.CONFIG_POSITION) != null && config(RadioSettingsCache.CONFIG_LORA) != null &&
                channel(0) != null && owner() != null
        } && app.state.radio.let { r ->
            r != null && r.longName != null && r.role != null && r.positionBroadcastSecs != null &&
                r.channelName != null && r.channelPreset != null && r.loraLoaded
        }

        fun open(timeoutMs: Long): Boolean {
            mgr.connectTcp(host, PORT)
            val ok = waitUntil(timeoutMs) { downloaded() || mgr.activeConnectionState.value is ConnectionState.Failed } && downloaded()
            if (ok) awaitQuiet()
            return ok
        }

        /**
         * The radio is still streaming module configs and node entries after the part we use is in. A request sent
         * in the middle of that gets its answer queued behind the download, and the firmware drops the oldest queued
         * packets when the queue fills, so wait until the stream has gone quiet.
         */
        private fun awaitQuiet(quietMs: Long = 1_500, timeoutMs: Long = 20_000) {
            var last = mgr.bytesReceived.value
            var since = System.currentTimeMillis()
            val deadline = since + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
                val now = mgr.bytesReceived.value
                if (now != last) {
                    last = now
                    since = System.currentTimeMillis()
                } else if (System.currentTimeMillis() - since >= quietMs) return
            }
        }

        val cache: RadioSettingsCache get() = mgr.radioSettings
        val node: UInt get() = mgr.myNodeNum!!

        fun send(frame: ByteArray) {
            val ok = runBlocking { mgr.tcpClient.sendBytes(frame) }
            check(ok) { "the radio link refused a write" }
        }

        /** Wait for the radio to drop the link (it reboots after a save), and check the app forgot the old settings. */
        fun awaitRebootAndForget() {
            val dropped = waitUntil(REBOOT_WAIT_MS) { mgr.activeConnectionState.value !is ConnectionState.Connected }
            check(dropped) { "the radio did not drop the link within ${REBOOT_WAIT_MS / 1000}s of a save" }
            assertTrue(
                "the app must forget the radio's settings when the link drops",
                waitUntil(5_000) { cache.size == 0 },
            )
            assertTrue(
                "and the settings state must stop counting the old report as the radio's",
                waitUntil(5_000) { app.state.radio == null },
            )
            assertNull("and the node number, so nothing is addressed to the old link", mgr.myNodeNum)
        }

        /**
         * Connect this same manager again after the radio restarted, the way the app reconnects: one call to
         * [MeshtasticManager.connectTcp], made when the radio is up. A throwaway session finds out when that is
         * (each failed attempt of a manager leaves a waiter behind that asks the radio for its config once it
         * connects), then this manager connects once and the note about the last push, which lives in the manager,
         * is judged on the download.
         */
        fun reconnect(timeoutMs: Long = 150_000) {
            connect(timeoutMs).close()
            // The throwaway session's link-down report reaches the shared settings state a moment after it closes.
            check(waitUntil(5_000) { app.state.radio == null }) { "the throwaway session's link drop was not reported" }
            check(open(60_000)) { "the manager could not connect again to the radio that is up" }
            log("reconnected the same manager, node ${"%08x".format(node.toInt())}")
        }

        override fun close() {
            runCatching { mgr.disconnect() }
        }
    }

    /** Connect, retrying while the radio restarts. A new manager each time, so no stale waiter fires twice. */
    private fun connect(timeoutMs: Long = 150_000): Session {
        val h = host!!
        val deadline = System.currentTimeMillis() + timeoutMs
        var attempt = 0
        while (System.currentTimeMillis() < deadline) {
            attempt++
            val s = Session(h)
            if (s.open(30_000)) {
                log("connected after $attempt attempt(s), node ${"%08x".format(s.node.toInt())}")
                return s
            }
            s.close()
            Thread.sleep(1_000)
        }
        throw AssertionError("the simulated radio at $h:$PORT did not answer within ${timeoutMs / 1000}s")
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    private fun log(message: String) = println("[sim] $message")

    // endregion

    // region Writes the test arranges itself (official field numbers, not the app's builders) ----------

    /** One field of a message to be written: its number and how to encode it. */
    private fun at(number: Int, write: ProtoMsg.() -> Unit): Pair<Int, ProtoMsg.() -> Unit> = number to write

    /** [original] with the fields in [overrides] replaced (or added), everything else re-encoded as it was, in numeric order. */
    private fun rebuild(original: ByteArray, vararg overrides: Pair<Int, ProtoMsg.() -> Unit>): ByteArray {
        val byNumber = overrides.toMap()
        val kept = AdminTestFrames.fields(original).groupBy { it.number }
        val out = ProtoMsg()
        for (n in (kept.keys + byNumber.keys).sorted()) {
            val write = byNumber[n]
            if (write != null) {
                out.write()
                continue
            }
            for (f in kept.getValue(n)) when (f.wire) {
                0 -> out.varint(n, f.varint.toLong())
                5 -> out.fixed32(n, f.fixed32.toInt())
                2 -> out.bytes(n, f.bytes)
                else -> error("unexpected wire type ${f.wire}")
            }
        }
        return out.build()
    }

    private fun adminFrame(dest: UInt, admin: ByteArray) =
        MeshWire.buildToRadio(portnum = 6UL, payload = admin, to = dest, wantAck = true, wantResponse = true)

    private fun beginFrame(dest: UInt) = adminFrame(dest, ProtoMsg().bool(64, true).build())
    private fun commitFrame(dest: UInt) = adminFrame(dest, ProtoMsg().bool(65, true).build())
    private fun setConfigFrame(dest: UInt, variant: Int, message: ByteArray) =
        adminFrame(dest, ProtoMsg().msg(34, ProtoMsg().bytes(variant, message)).build())
    private fun setChannelFrame(dest: UInt, channel: ByteArray) = adminFrame(dest, ProtoMsg().bytes(33, channel).build())
    private fun setOwnerFrame(dest: UInt, user: ByteArray) = adminFrame(dest, ProtoMsg().bytes(32, user).build())

    /** What the radio held when the test started, as the bytes it sent. Used to put everything back. */
    private class AsFound(
        val device: ByteArray, val position: ByteArray, val lora: ByteArray,
        val channel0: ByteArray, val channel1: ByteArray, val owner: ByteArray,
    ) {
        companion object {
            fun of(cache: RadioSettingsCache) = AsFound(
                cache.config(RadioSettingsCache.CONFIG_DEVICE)!!, cache.config(RadioSettingsCache.CONFIG_POSITION)!!,
                cache.config(RadioSettingsCache.CONFIG_LORA)!!, cache.channel(0)!!, cache.channel(1)!!, cache.owner()!!,
            )
        }
    }

    /**
     * Give the radio something to lose in every field the old partial writes reset, keeping what it already
     * has everywhere else. One edit transaction, so one reboot.
     */
    private fun arrange(s: Session, found: AsFound) {
        val device = rebuild(
            found.device,
            at(6) { varint(6, 2) }, // rebroadcast: LOCAL_ONLY
            at(7) { varint(7, 7200) },
            at(11) { string(11, "PST8PDT,M3.2.0,M11.1.0") },
            at(12) { bool(12, true) },
        )
        val position = rebuild(
            found.position,
            at(1) { varint(1, 900) },
            at(2) { bool(2, true) },
            at(5) { varint(5, 120) },
            at(7) { varint(7, 811) },
            at(10) { varint(10, 150) },
            at(11) { varint(11, 30) },
            at(13) { varint(13, 1) }, // GPS mode ENABLED
        )
        val lora = rebuild(
            found.lora,
            at(1) { bool(1, true) },
            at(2) { varint(2, 6) }, // SHORT_FAST
            at(7) { varint(7, 1) }, // US
            at(8) { varint(8, 5) },
            at(9) { bool(9, true) },
            at(13) { bool(13, true) },
        )
        // The channel keeps the key it has when that is a 32 byte key; otherwise it gets a throwaway one.
        // The key is never printed.
        val existing = Msg(found.channel0).sub(2)?.raw(2)
        val key = existing?.takeIf { it.size == 32 } ?: ByteArray(32).also { Random().nextBytes(it) }
        val settings = rebuild(
            Msg(found.channel0).sub(2)?.bytes ?: ByteArray(0),
            at(2) { bytes(2, key) },
            at(3) { string(3, "simtest") },
            at(7) { msg(7, ProtoMsg().varint(1, 13)) }, // module_settings.position_precision
        )
        val channel = rebuild(found.channel0, at(2) { bytes(2, settings) }, at(3) { varint(3, 1) })

        send(s, listOf(
            beginFrame(s.node),
            setConfigFrame(s.node, RadioSettingsCache.CONFIG_DEVICE, device),
            setConfigFrame(s.node, RadioSettingsCache.CONFIG_POSITION, position),
            setConfigFrame(s.node, RadioSettingsCache.CONFIG_LORA, lora),
            setChannelFrame(s.node, channel),
            commitFrame(s.node),
        ))
    }

    /** Put back the as-found messages, in one transaction. */
    private fun restore(s: Session, found: AsFound) {
        send(s, listOf(
            beginFrame(s.node),
            setConfigFrame(s.node, RadioSettingsCache.CONFIG_DEVICE, found.device),
            setConfigFrame(s.node, RadioSettingsCache.CONFIG_POSITION, found.position),
            setConfigFrame(s.node, RadioSettingsCache.CONFIG_LORA, found.lora),
            setChannelFrame(s.node, found.channel0),
            setChannelFrame(s.node, found.channel1),
            setOwnerFrame(s.node, found.owner),
            commitFrame(s.node),
        ))
    }

    /** The test's own frames go out as far apart as the app's do, for the same reason (see [MeshSettingsWriter]). */
    private fun send(s: Session, frames: List<ByteArray>) = frames.forEachIndexed { i, frame ->
        if (i > 0) Thread.sleep(MeshSettingsWriter.ADMIN_FRAME_SPACING_MS)
        s.send(frame)
    }

    // endregion

    /**
     * Make one change through the app, check the app has nothing to report about it, let the radio reboot,
     * download again, and return what the radio now says. [change] runs against the live session and returns
     * the result of the app call.
     */
    private fun stepThroughApp(
        name: String,
        s: Session,
        expect: AdminWriteResult,
        /** Runs on the radio's own report right after the write, before it restarts. */
        live: (Session) -> Unit = {},
        change: (Session) -> AdminWriteResult,
    ): Pair<Session, Map<String, Any?>> {
        s.mgr.clearSettingsNotice()
        val result = change(s)
        log("$name: the app reported $result")
        assertEquals("$name: every write must go out", expect, result)
        // The radio is still up for about five seconds after the commit. Ask it what it holds: the app compares
        // the answers with what it sent and says something only when the radio kept a value of its own.
        runBlocking { s.mgr.requestDeviceConfig() }
        Thread.sleep(1_500)
        assertNull("$name: the radio took what was sent, so there is nothing to report", s.mgr.settingsNotice.value)
        live(s)
        s.awaitRebootAndForget()
        s.close()
        val next = connect()
        return next to view(next.cache)
    }

    /**
     * Wait for the radio's own report of the position interval, asking again now and then (an answer can be lost
     * in a busy moment).
     *
     * The interval is checked here, on what the radio reports right after a write, and not only after its
     * restart. The stock radio sends its position on its default channel, and firmware 2.7.26 raises a position
     * interval under one hour to one hour when it restarts ([PositionFloor]), so a short interval written to it
     * is back at one hour after the restart. Once step 4 has put a private key on the primary channel the
     * interval survives restarts. The radio's report before the restart is where the app's write is always
     * visible.
     */
    private fun awaitInterval(s: Session, secs: Int, what: String) {
        val deadline = System.currentTimeMillis() + 15_000
        var lastAsk = 0L
        while (System.currentTimeMillis() < deadline) {
            if (app.state.radio?.positionBroadcastSecs == secs) return
            if (System.currentTimeMillis() - lastAsk > 3_000) {
                runBlocking { s.mgr.requestDeviceConfig() }
                lastAsk = System.currentTimeMillis()
            }
            Thread.sleep(100)
        }
        throw AssertionError("$what: the radio does not report a position interval of $secs, it says ${app.state.radio?.positionBroadcastSecs}")
    }

    /**
     * The fields that changed between two downloads must be exactly [expected] (old to new). The position
     * interval is the exception, see [awaitInterval]: after a restart it may hold the new value or the one before,
     * and nothing else. [ignoreInterval] leaves it out altogether, for a download compared with one taken while the
     * radio held an interval it would not keep.
     */
    private fun assertChanged(
        why: String,
        expected: Map<String, Pair<Any?, Any?>>,
        before: Map<String, Any?>,
        after: Map<String, Any?>,
        ignoreInterval: Boolean = false,
    ) {
        val changed = diff(before, after)
        assertEquals(why, expected - INTERVAL, changed - INTERVAL)
        if (ignoreInterval) return
        val now = after[INTERVAL]
        val change = expected[INTERVAL]
        if (change == null) {
            assertEquals("$why: the interval must not change", before[INTERVAL], now)
        } else {
            assertTrue("$why: after the restart the interval is the new one or the one before, not $now", now == change.second || now == change.first)
            log("$why: the restart ${if (now == change.second) "kept" else "reset"} the position interval")
        }
    }

    @Test fun settings_writes_change_only_the_edited_field() {
        assumeTrue("MESHSIM_HOST is not set, so the simulated-radio test is skipped", host != null)

        var s = connect()
        val found = AsFound.of(s.cache)
        val asFound = view(s.cache)
        log("as found: $asFound")
        // Nothing is written to a radio that is not a simulator. PORTDUINO is hardware model 37 in mesh.proto.
        assumeTrue("the peer is not a simulator (hardware model ${asFound["owner.hw_model"]}), nothing was written", asFound["owner.hw_model"] == PORTDUINO)
        var readsAfterRestore: Map<String, Pair<Any?, Any?>>? = null

        try {
            // 2. the reported case: a fresh install's draft, a factory-like radio, one edit.
            val factoryLike = asFound["device.role"] == null && asFound["channel0.name"] == null
            if (factoryLike) {
                val draft = app.state.draft
                assertEquals("the draft's TAK is replaced by the radio's role (CLIENT, not on the wire)", MeshRole.CLIENT, draft.role)
                assertEquals("the draft's leftover name is replaced by the radio's unnamed primary", "", draft.channelName)
                assertTrue("a draft synced from the radio edits nothing", app.state.edits().isEmpty)

                val secs = if ((asFound["position.position_broadcast_secs"] as Long) == 400L) 500 else 400
                val before = asFound
                val step = stepThroughApp(
                    "factory radio, interval only", s, AdminWriteResult.Sent(listOf(AdminSetting.POSITION_INTERVAL)),
                    live = { awaitInterval(it, secs, "factory radio, interval only") },
                ) { sess ->
                    val edits = app.edits { copy(positionBroadcastSecs = secs) }
                    assertEquals("one setting is edited", listOf(AdminSetting.POSITION_INTERVAL), edits.settings)
                    runBlocking { sess.mgr.pushDeviceConfig(edits) }
                }
                s = step.first
                log("factory radio, interval only: ${diff(before, step.second)}")
                assertChanged(
                    "only the interval may change: the role must stay CLIENT and the primary channel unnamed",
                    mapOf(INTERVAL to (before[INTERVAL] to secs.toLong())), before, step.second,
                )
                assertEquals("role untouched", asFound["device.role"], step.second["device.role"])
                assertEquals("channel name untouched", asFound["channel0.name"], step.second["channel0.name"])
            } else {
                log("the radio is not factory-like (role ${asFound["device.role"]}, primary '${asFound["channel0.name"]}'): skipping the fresh-install step")
            }

            // 3. admin responses reach the cache
            run {
                val fromDownload = s.cache.config(RadioSettingsCache.CONFIG_LORA)!!
                s.cache.clear()
                val asked = runBlocking { s.mgr.requestDeviceConfig() }
                assertEquals("the app asks for the owner, three configs and eight channels", 12, asked)
                val refilled = waitUntil(10_000) {
                    s.cache.config(RadioSettingsCache.CONFIG_DEVICE) != null &&
                        s.cache.config(RadioSettingsCache.CONFIG_POSITION) != null &&
                        s.cache.config(RadioSettingsCache.CONFIG_LORA) != null &&
                        s.cache.channel(0) != null && s.cache.owner() != null
                }
                assertTrue("get_*_response frames from the radio must refill the cache, it holds ${s.cache}", refilled)
                assertTrue(
                    "the LoRa config from get_config_response is the one the download carried",
                    fromDownload.contentEquals(s.cache.config(RadioSettingsCache.CONFIG_LORA)!!),
                )
                log("admin responses refilled the cache: ${s.cache}")
            }

            // 3b. an interval above the app's limit, as another client can leave it: the screen shows the radio's own
            // value and does not count it as an edit, and a push of another setting leaves it alone. The interval is
            // set in the radio's memory only (a transaction that is not committed: nothing is saved, no restart), so
            // what is checked is what the radio itself reports, before and after the app's push.
            val threeDays = 259_200
            send(s, listOf(
                beginFrame(s.node),
                setConfigFrame(s.node, RadioSettingsCache.CONFIG_POSITION, rebuild(found.position, at(1) { varint(1, threeDays.toLong()) })),
            ))
            runBlocking { s.mgr.requestDeviceConfig() }
            awaitInterval(s, threeDays, "interval above the limit")
            assertEquals("the screen shows the radio's own value", threeDays, app.state.draft.positionBroadcastSecs)
            assertTrue("and it is not an edit", app.state.edits().isEmpty)
            val aboveLimit = view(s.cache)
            val roleOnly = stepThroughApp(
                "role only, interval above the limit", s, AdminWriteResult.Sent(listOf(AdminSetting.ROLE)),
                live = { awaitInterval(it, threeDays, "role only, interval above the limit") },
            ) { sess ->
                val edits = app.edits { copy(role = MeshRole.CLIENT_MUTE) }
                assertEquals("only the role is edited", listOf(AdminSetting.ROLE), edits.settings)
                runBlocking { sess.mgr.pushDeviceConfig(edits) }
            }
            s = roleOnly.first
            log("role only, interval above the limit: ${diff(aboveLimit, roleOnly.second)}")
            assertChanged(
                "only the role may change, and the radio went on reporting the interval it had until it restarted",
                // The firmware mirrors the device role in the owner record, so that one follows.
                mapOf("device.role" to (null to 1L), "owner.role" to (null to 1L)), aboveLimit, roleOnly.second, ignoreInterval = true,
            )

            // 4. arrange a radio that has something to lose
            arrange(s, found)
            s.awaitRebootAndForget()
            s.close()
            s = connect()
            val seeded = view(s.cache)
            log("after arranging: $seeded")
            assertEquals("arranged: region US", 1L, seeded["lora.region"])
            assertEquals("arranged: hop limit", 5L, seeded["lora.hop_limit"])
            assertEquals("arranged: transmit on", 1L, seeded["lora.tx_enabled"])
            assertEquals("arranged: GPS mode ENABLED", 1L, seeded["position.gps_mode"])
            assertEquals("arranged: position flags", 811L, seeded["position.position_flags"])
            assertEquals("arranged: channel key length", 32, seeded["channel0.key_length"])
            assertEquals("arranged: position precision", 13L, seeded["channel0.position_precision"])
            assertEquals("arranged: time zone", "PST8PDT,M3.2.0,M11.1.0", seeded["device.tzdef"])
            assertTrue("the screen's draft follows the radio, so nothing is edited", app.state.edits().isEmpty)

            // 5a. a position interval change, through pushDeviceConfig (one edit transaction)
            var before: Map<String, Any?> = seeded
            var step = stepThroughApp(
                "position interval", s, AdminWriteResult.Sent(listOf(AdminSetting.POSITION_INTERVAL)),
                live = { awaitInterval(it, 321, "position interval") },
            ) { sess ->
                runBlocking { sess.mgr.pushDeviceConfig(app.edits { copy(positionBroadcastSecs = 321) }) }
            }
            s = step.first
            var changed = diff(before, step.second)
            log("position interval changed: $changed")
            assertChanged("only the position interval may change", mapOf(INTERVAL to (before[INTERVAL] to 321L)), before, step.second)

            // 5b. a LoRa preset change
            before = step.second
            step = stepThroughApp("LoRa preset", s, AdminWriteResult.Sent(listOf(AdminSetting.MODEM_PRESET))) { sess ->
                runBlocking { sess.mgr.pushDeviceConfig(app.edits { copy(channelPreset = MeshChannelPreset.MEDIUM_FAST) }) }
            }
            s = step.first
            changed = diff(before, step.second)
            log("LoRa preset changed: $changed")
            assertEquals(
                "only the modem preset may change: region, hop limit and the transmit switch must stay",
                mapOf("lora.modem_preset" to (6L to 4L)),
                changed,
            )

            // 5c. a channel 0 rename
            before = step.second
            step = stepThroughApp("channel 0 rename", s, AdminWriteResult.Sent(listOf(AdminSetting.CHANNEL_NAME))) { sess ->
                runBlocking { sess.mgr.pushDeviceConfig(app.edits { copy(channelName = "simren") }) }
            }
            s = step.first
            changed = diff(before, step.second)
            log("channel 0 renamed: $changed")
            assertEquals(
                "only the channel name may change: the 32 byte key and the position precision must stay",
                mapOf("channel0.name" to ("simtest" to "simren")),
                changed,
            )

            // 5d. a rebroadcast mode change, a single write (inside its own transaction)
            before = step.second
            step = stepThroughApp("rebroadcast mode", s, AdminWriteResult.Sent(listOf(AdminSetting.REBROADCAST_MODE))) { sess ->
                runBlocking { sess.mgr.applyRebroadcastMode(RebroadcastMode.KNOWN_ONLY) }
            }
            s = step.first
            changed = diff(before, step.second)
            log("rebroadcast changed: $changed")
            assertEquals(
                "only the rebroadcast mode may change: time zone, LED switch and the rest of the device config must stay",
                mapOf("device.rebroadcast_mode" to (2L to 3L)),
                changed,
            )

            // 5e. an owner rename, a single write (inside its own transaction)
            before = step.second
            step = stepThroughApp("owner rename", s, AdminWriteResult.Sent(listOf(AdminSetting.LONG_NAME, AdminSetting.SHORT_NAME))) { sess ->
                runBlocking { sess.mgr.applyOwner("Sim Renamed", "SRN") }
            }
            s = step.first
            changed = diff(before, step.second)
            log("owner renamed: $changed")
            assertEquals(
                "only the two names may change: licensed flag, key, hardware model and role must stay",
                setOf("owner.long_name", "owner.short_name"),
                changed.keys,
            )
            assertEquals("Sim Renamed", step.second["owner.long_name"])
            assertEquals("SRN", step.second["owner.short_name"])

            // 5f. one push that changes four settings at once: owner names, interval, channel name, preset
            before = step.second
            val four = listOf(
                AdminSetting.LONG_NAME, AdminSetting.SHORT_NAME, AdminSetting.POSITION_INTERVAL,
                AdminSetting.CHANNEL_NAME, AdminSetting.MODEM_PRESET,
            )
            step = stepThroughApp(
                "four changes in one push", s, AdminWriteResult.Sent(four),
                live = { awaitInterval(it, 123, "four changes in one push") },
            ) { sess ->
                runBlocking {
                    sess.mgr.pushDeviceConfig(
                        app.edits {
                            copy(
                                longName = "Sim Four", shortName = "SF4", positionBroadcastSecs = 123,
                                channelName = "simfour", channelPreset = MeshChannelPreset.SHORT_FAST,
                            )
                        },
                    )
                }
            }
            s = step.first
            changed = diff(before, step.second)
            log("four changes in one push: $changed")
            assertChanged(
                "exactly the settings that were edited may change, and every write of the batch must land",
                mapOf(
                    "owner.long_name" to (before["owner.long_name"] to "Sim Four"),
                    "owner.short_name" to (before["owner.short_name"] to "SF4"),
                    INTERVAL to (before[INTERVAL] to 123L),
                    "channel0.name" to (before["channel0.name"] to "simfour"),
                    "lora.modem_preset" to (before["lora.modem_preset"] to 6L),
                ),
                before, step.second,
            )
            assertEquals("region and hop limit survive the batch", 1L, step.second["lora.region"])
            assertEquals(32, step.second["channel0.key_length"])

            // 5g. an imported channel goes into the first free secondary slot, and the primary is left alone
            before = step.second
            val importedKey = ByteArray(32).also { Random().nextBytes(it) } // never printed
            step = stepThroughApp("channel import", s, AdminWriteResult.Sent(listOf(AdminSetting.CHANNEL))) { sess ->
                runBlocking { sess.mgr.applyChannel(MeshChannel(name = "simimp", psk = importedKey)) }
            }
            s = step.first
            changed = diff(before, step.second)
            log("channel imported: $changed")
            assertEquals(
                "only slot 1 may change: the primary channel (name, key, precision) must stay",
                mapOf("channel1.role" to (null to 2L), "channel1.name" to (null to "simimp")),
                changed,
            )

            // A push with nothing edited sends nothing, and the screen's draft follows the radio.
            assertTrue("the draft follows the radio: nothing is edited", app.state.edits().isEmpty)
            val quiet = runBlocking { s.mgr.pushDeviceConfig(app.state.edits()) }
            assertEquals("nothing edited is a no-op on a real radio too", AdminWriteResult.NothingToChange, quiet)
        } finally {
            // 6. put back what the test changed (best effort, even when an assertion failed above)
            runCatching {
                s.close()
                val back = connect()
                restore(back, found)
                back.awaitRebootAndForget()
                back.close()
                val last = connect()
                val regionAsFound = asFound["lora.region"]
                val keyGeneratedByFirmware = asFound["owner.public_key_length"] == null && (regionAsFound == null || regionAsFound == 0L)
                readsAfterRestore = diff(asFound, view(last.cache))
                    .filterKeys { !(keyGeneratedByFirmware && it == "owner.public_key_length") }
                log("restored: ${readsAfterRestore!!.ifEmpty { "identical to what was found" }}")
                last.close()
            }.onFailure { log("RESTORE FAILED, the radio was left changed: ${it.message}") }
        }

        // Only reached when every step above passed, so a failure there is never hidden by this one.
        assertEquals(
            "after the test puts its changes back, every watched field reads as it did when the test started",
            emptyMap<String, Pair<Any?, Any?>>(),
            readsAfterRestore,
        )
    }

    // region the position floor -------------------------------------------------------------------------------

    /** What the position floor looks at, from a download. Key lengths only. */
    private fun held(v: Map<String, Any?>): String =
        "interval=${v[INTERVAL]} smart_min_interval=${v["position.smart_min_interval_secs"]} " +
            "channel0[key_length=${v["channel0.key_length"]} name='${v["channel0.name"] ?: ""}' precision=${v["channel0.position_precision"]}] " +
            "preset=${v["lora.modem_preset"] ?: 0} role=${v["device.role"] ?: 0}"

    /** Channel 0 as it was found, with its key, name and position precision replaced. Never prints the key. */
    private fun channel0With(found: AsFound, key: ByteArray, name: String, precision: Int): ByteArray {
        val settings = rebuild(
            Msg(found.channel0).sub(2)?.bytes ?: ByteArray(0),
            at(2) { bytes(2, key) },
            at(3) { string(3, name) },
            at(7) { msg(7, ProtoMsg().varint(1, precision)) },
        )
        return rebuild(found.channel0, at(2) { bytes(2, settings) }, at(3) { varint(3, 1) })
    }

    /** Replace channel 0 on the radio, in one transaction (one reboot), and reconnect a fresh session to it. */
    private fun arrangeChannel0(s: Session, channel: ByteArray): Session {
        send(s, listOf(beginFrame(s.node), setChannelFrame(s.node, channel), commitFrame(s.node)))
        s.awaitRebootAndForget()
        s.close()
        return connect()
    }

    /** The settings screen's decision for the interval control, from what the radio reported and the draft with [secs]. */
    private fun hintFor(s: Session, secs: Int): String? =
        app.state.positionIntervalHint(s.mgr.positionFacts.value, app.state.draft.copy(positionBroadcastSecs = secs))

    /**
     * The position floor against the real firmware. Four radios in a row, each the stock radio with something
     * changed on its primary channel, always pushed through the app and judged on what the radio itself reports:
     *
     *  A. the stock radio, default key, unnamed, precision 13, LONG_FAST: 120 s is announced before the push, the
     *     radio reports 120 s until it restarts and one hour after, and the manager that made the push says so
     *     with both numbers and the reason when it connects again;
     *  B. a 32 byte key on the primary channel: no hint, 120 s survives the restart, no note;
     *  C. the default key with a name that is not the preset's: the firmware does not call it the default channel,
     *     so the interval the radio already holds (120 s) survives its next restart and the app says nothing;
     *  D. the default key and the preset's name, but no position precision: no channel sends positions, so the
     *     interval survives again.
     *
     * Then everything is put back and the radio must read as it did when the test started.
     */
    @Test fun a_short_position_interval_on_the_default_channel_is_raised_and_the_app_says_so() {
        assumeTrue("MESHSIM_HOST is not set, so the simulated-radio test is skipped", host != null)

        var s = connect()
        val found = AsFound.of(s.cache)
        val asFound = view(s.cache)
        log("as found: ${held(asFound)}")
        assumeTrue("the peer is not a simulator (hardware model ${asFound["owner.hw_model"]}), nothing was written", asFound["owner.hw_model"] == PORTDUINO)
        assumeTrue(
            "the radio is not as the stock image comes (default key, unnamed primary with a position precision, LONG_FAST, one hour)",
            asFound["channel0.key_length"] == 1 && asFound["channel0.name"] == null && asFound["channel0.position_precision"] == 13L &&
                (asFound["lora.modem_preset"] ?: 0L) == 0L && asFound[INTERVAL] == 3_600L,
        )
        val reasonOneHour = PositionFloor.reason(PositionFloor.DEFAULT_FLOOR_SECS)
        var readsAfterRestore: Map<String, Pair<Any?, Any?>>? = null

        try {
            // A. the default channel
            log("A before: ${held(asFound)}")
            assertNull("A: the stock radio says nothing about a floor at one hour", hintFor(s, 3_600))
            val hintA = hintFor(s, 120)
            log("A hint for 120 s: $hintA")
            assertEquals("A: 120 s is under the floor of a radio on its default channel", reasonOneHour, hintA)

            val editsA = app.edits { copy(positionBroadcastSecs = 120) }
            assertEquals(listOf(AdminSetting.POSITION_INTERVAL), editsA.settings)
            s.mgr.clearSettingsNotice()
            s.mgr.clearLastPushResult()
            assertEquals(AdminWriteResult.Sent(listOf(AdminSetting.POSITION_INTERVAL)), runBlocking { s.mgr.pushDeviceConfig(editsA) })
            runBlocking { s.mgr.requestDeviceConfig() }
            awaitInterval(s, 120, "A")
            val afterWriteA = view(s.cache)
            log("A right after the write, before the restart: ${held(afterWriteA)}")
            assertEquals("the radio holds what it was sent until it restarts", 120L, afterWriteA[INTERVAL])
            assertNull("nothing to say yet: the radio reports what it was sent", s.mgr.restartNote.value)
            assertNull(s.mgr.settingsNotice.value)

            s.awaitRebootAndForget()
            s.reconnect()
            val afterA = view(s.cache)
            log("A after the restart: ${held(afterA)}")
            assertEquals("the firmware put the interval back to one hour", 3_600L, afterA[INTERVAL])
            assertTrue("and the note is there when the download is in", waitUntil(10_000) { s.mgr.restartNote.value != null })
            log("A note: ${s.mgr.restartNote.value}")
            assertEquals(
                "The radio reports 3600 s for the position interval. 120 s was sent. $reasonOneHour",
                s.mgr.restartNote.value,
            )
            assertNull("it is not the managed-radio note", s.mgr.settingsNotice.value)
            assertEquals("nothing else changed", emptyMap<String, Pair<Any?, Any?>>(), diff(asFound, afterA))
            s.close()

            // B. a private key on the primary channel
            val key = ByteArray(32).also { Random().nextBytes(it) } // never printed
            s = arrangeChannel0(connect(), channel0With(found, key, name = "", precision = 13))
            val beforeB = view(s.cache)
            log("B before: ${held(beforeB)}")
            assertEquals("B: a 32 byte key", 32, beforeB["channel0.key_length"])
            assertEquals("B: the interval is as it was", 3_600L, beforeB[INTERVAL])
            assertNull("B: no hint, this is not the default channel", hintFor(s, 120))

            val editsB = app.edits { copy(positionBroadcastSecs = 120) }
            assertEquals(listOf(AdminSetting.POSITION_INTERVAL), editsB.settings)
            s.mgr.clearSettingsNotice()
            s.mgr.clearLastPushResult()
            assertEquals(AdminWriteResult.Sent(listOf(AdminSetting.POSITION_INTERVAL)), runBlocking { s.mgr.pushDeviceConfig(editsB) })
            runBlocking { s.mgr.requestDeviceConfig() }
            awaitInterval(s, 120, "B")
            log("B right after the write, before the restart: ${held(view(s.cache))}")
            s.awaitRebootAndForget()
            s.reconnect()
            val afterB = view(s.cache)
            log("B after the restart: ${held(afterB)}")
            assertEquals("the radio keeps 120 s", 120L, afterB[INTERVAL])
            assertNull("and the app has nothing to say", s.mgr.restartNote.value)
            assertNull(s.mgr.settingsNotice.value)
            s.close()

            // C. the default key, a name that is not the preset's
            s = arrangeChannel0(connect(), channel0With(found, byteArrayOf(1), name = "Alpha", precision = 13))
            val afterC = view(s.cache)
            log("C after the restart that followed the channel change: ${held(afterC)}")
            assertEquals("C: the default key", 1, afterC["channel0.key_length"])
            assertEquals("C: named", "Alpha", afterC["channel0.name"])
            assertEquals("C: the firmware does not raise the interval of a channel it does not call the default", 120L, afterC[INTERVAL])
            assertNull("C: and the app says nothing", hintFor(s, 120))
            s.close()

            // D. the default key and the preset's name, no position precision on any channel
            s = arrangeChannel0(connect(), channel0With(found, byteArrayOf(1), name = "LongFast", precision = 0))
            val afterD = view(s.cache)
            log("D after the restart that followed the channel change: ${held(afterD)}")
            assertEquals("D: the default key", 1, afterD["channel0.key_length"])
            assertEquals("D: the preset's name", "LongFast", afterD["channel0.name"])
            assertEquals("D: no position precision", 0L, afterD["channel0.position_precision"] ?: 0L)
            assertEquals("D: no channel sends positions, so nothing is raised", 120L, afterD[INTERVAL])
            assertNull("D: and the app says nothing", hintFor(s, 120))
        } finally {
            // Put back what the test changed (best effort, even when an assertion failed above).
            runCatching {
                s.close()
                val back = connect()
                restore(back, found)
                back.awaitRebootAndForget()
                back.close()
                val last = connect()
                readsAfterRestore = diff(asFound, view(last.cache))
                log("restored: ${readsAfterRestore!!.ifEmpty { "identical to what was found" }}")
                log("restored: ${held(view(last.cache))}")
                last.close()
            }.onFailure { log("RESTORE FAILED, the radio was left changed: ${it.message}") }
        }

        assertEquals(
            "after the test puts its changes back, every watched field reads as it did when the test started",
            emptyMap<String, Pair<Any?, Any?>>(),
            readsAfterRestore,
        )
    }

    // endregion

    // region an interrupted sequence ---------------------------------------------------------------------------------

    private fun isCommit(frame: ByteArray): Boolean = AdminTestFrames.decode(frame).admin.first().number == 65

    /**
     * An owner rename that goes out as the app sends it, except that the commit never reaches the radio: the link
     * "drops" at the last frame. The radio is left with the new name in memory and the transaction open.
     */
    private fun renameWithoutCommit(s: Session, name: String): AdminWriteResult {
        s.mgr.adminSendOverride = { frame -> if (isCommit(frame)) false else s.mgr.tcpClient.sendBytes(frame) }
        try {
            return runBlocking { s.mgr.pushDeviceConfig(DeviceEdits(longName = name)) }
        } finally {
            s.mgr.adminSendOverride = null
        }
    }

    private fun awaitOwnerName(s: Session, name: String) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (app.state.radio?.longName == name) return
            runBlocking { s.mgr.requestDeviceConfig() }
            Thread.sleep(1_500)
        }
        throw AssertionError("the radio does not report the owner name '$name', it says '${app.state.radio?.longName}'")
    }

    /**
     * The transaction a lost link leaves open, against the real firmware. The simulator does not count its restarts
     * (my_info carries no reboot_count), so the app decides from the download, with the same manager across the
     * reconnects as in the app:
     *
     *  1. rename without a commit: the radio holds the name in memory, unsaved. The manager connects again, finds the
     *     name in the download, commits, says the change was saved, and the name survives the restart that follows;
     *  2. rename without a commit again, then restart the radio without a commit (an admin reboot): the name is
     *     lost. The manager connects again, finds the old name, sends nothing (a stray commit would restart the radio
     *     a second time a few seconds later, which the test watches for) and says the change was not saved.
     *
     * Then the owner is put back as found.
     */
    @Test fun an_interrupted_sequence_is_closed_or_given_up_on_at_the_next_link_up() {
        assumeTrue("MESHSIM_HOST is not set, so the simulated-radio test is skipped", host != null)

        var s = connect()
        val found = AsFound.of(s.cache)
        val asFound = view(s.cache)
        assumeTrue("the peer is not a simulator (hardware model ${asFound["owner.hw_model"]}), nothing was written", asFound["owner.hw_model"] == PORTDUINO)
        val foundName = asFound["owner.long_name"] as String
        log("as found: owner '$foundName'")
        var readsAfterRestore: Map<String, Pair<Any?, Any?>>? = null

        try {
            // 1. the change is still in the radio's memory
            val first = renameWithoutCommit(s, "Txn App One")
            log("1. the push said: ${first.describe()}")
            assertTrue(first.toString(), first is AdminWriteResult.Incomplete && !first.committed)
            awaitOwnerName(s, "Txn App One")
            log("1. the radio reports the new name, unsaved, with the transaction open")
            s.close()
            assertTrue(waitUntil(10_000) { app.state.radio == null })
            s.reconnect()
            assertTrue("the manager says what it did", waitUntil(20_000) { s.mgr.lastPushResult.value == InterruptedWrite.SAVED })
            log("1. the app says: ${s.mgr.lastPushResult.value}")
            s.awaitRebootAndForget() // the commit made the radio save and restart
            s.reconnect()
            val afterOne = view(s.cache)
            log("1. after the commit and the restart: owner '${afterOne["owner.long_name"]}'")
            assertEquals("saved", "Txn App One", afterOne["owner.long_name"])
            s.close()

            // 2. the radio restarted without a commit: the change is gone
            s = connect()
            val second = renameWithoutCommit(s, "Txn App Two")
            assertTrue(second.toString(), second is AdminWriteResult.Incomplete && !second.committed)
            awaitOwnerName(s, "Txn App Two")
            log("2. the radio reports the second name, unsaved")
            // The radio is restarted by an admin reboot, which does not commit: the transaction and the name go with it.
            send(s, listOf(adminFrame(s.node, ProtoMsg().varint(97, 2).build())))
            s.awaitRebootAndForget()
            s.reconnect()
            val afterTwo = view(s.cache)
            log("2. after the restart: owner '${afterTwo["owner.long_name"]}'")
            assertEquals("the unsaved name is gone with the restart", "Txn App One", afterTwo["owner.long_name"])
            assertTrue("the manager says what it found", waitUntil(20_000) { s.mgr.lastPushResult.value == InterruptedWrite.NOT_SAVED })
            log("2. the app says: ${s.mgr.lastPushResult.value}")
            assertNull("the writes were lost with the restart: the radio is not blamed for ignoring them", s.mgr.settingsNotice.value)
            // A commit sent to this radio would save its config and restart it again about seven seconds later.
            Thread.sleep(15_000)
            assertTrue("nothing was sent: the radio was not restarted a second time", s.mgr.activeConnectionState.value is ConnectionState.Connected)
            log("2. fifteen seconds later the link is still up: no stray commit")
        } finally {
            runCatching {
                s.close()
                val back = connect()
                restore(back, found)
                back.awaitRebootAndForget()
                back.close()
                val last = connect()
                readsAfterRestore = diff(asFound, view(last.cache))
                log("restored: ${readsAfterRestore!!.ifEmpty { "identical to what was found" }}")
                last.close()
            }.onFailure { log("RESTORE FAILED, the radio was left changed: ${it.message}") }
        }

        assertEquals(
            "after the test puts its changes back, every watched field reads as it did when the test started",
            emptyMap<String, Pair<Any?, Any?>>(),
            readsAfterRestore,
        )
    }

    // endregion

    private companion object {
        /** HardwareModel.PORTDUINO in mesh.proto: the simulator's. */
        const val PORTDUINO = 37L
        const val INTERVAL = "position.position_broadcast_secs"

        const val PORT = 4403
        const val REBOOT_WAIT_MS = 45_000L
    }
}
