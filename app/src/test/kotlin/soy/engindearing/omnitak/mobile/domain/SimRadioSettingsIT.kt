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
import soy.engindearing.omnitak.mobile.data.MeshChannel
import soy.engindearing.omnitak.mobile.data.MeshChannelPreset
import soy.engindearing.omnitak.mobile.data.MeshDeviceConfig
import soy.engindearing.omnitak.mobile.data.MeshRole
import soy.engindearing.omnitak.mobile.data.MeshWire
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
     * restart: the simulator as it comes from the stock image set `position_broadcast_secs` back to its default
     * on a restart that followed a write of 400 s (writes of the GPS mode and the GPS update interval alone
     * survived), and did not once the test had written its own position config. The rule is not known. The
     * radio's report before the restart is where the app's write is always visible.
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

    private companion object {
        /** HardwareModel.PORTDUINO in mesh.proto: the simulator's. */
        const val PORTDUINO = 37L
        const val INTERVAL = "position.position_broadcast_secs"

        const val PORT = 4403
        const val REBOOT_WAIT_MS = 45_000L
    }
}
