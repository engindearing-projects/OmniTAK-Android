package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.AdminTestFrames
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.Field
import soy.engindearing.omnitak.mobile.data.AdminWriteResult
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
 * cache and builders talking to a simulated radio (meshtasticd).
 *
 * Opt in with `MESHSIM_HOST=<host>` (the radio's TCP API is on port 4403).
 * Without it this test is skipped, so CI and a plain `testDebugUnitTest` never
 * touch a radio:
 *
 *     MESHSIM_HOST=127.0.0.1 ./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest \
 *         --tests '*SimRadioSettingsIT*'
 *
 * It takes a few minutes: every write ends in a reboot of the radio (about
 * 15 s) and the test reconnects and downloads the config again each time.
 *
 * What it does, in order:
 *  1. connect, run the config download through the app's parser and cache;
 *  2. ask for the settings with get_*_request and check the answers reach the
 *     cache (so admin responses are accepted from the radio);
 *  3. arrange a radio that has something to lose: write values into the fields
 *     the old partial writes used to reset (region, hop limit, transmit switch,
 *     GPS mode, position flags, channel key and precision, time zone, ...). The
 *     arranging writes are built here from the official field numbers, not by
 *     the app's builders;
 *  4. make each change through the app's own paths (a position interval, a
 *     LoRa preset, a channel 0 rename, a rebroadcast mode, an owner rename),
 *     let the radio reboot, download again, and assert that exactly the
 *     intended field changed and every other field read the same as before;
 *  5. put back what the test changed, and assert the radio reads as it did when the test started.
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

    // region One connection to the radio ------------------------------------------------------

    /** A session: a fresh app-side manager (its own TCP client, parser and cache) connected to the radio. */
    private inner class Session(private val host: String) : AutoCloseable {
        val mgr = MeshtasticManager()

        /** The config download is in once the last Config variant (device_ui, 10) and the pieces we use are cached. */
        private fun downloaded(): Boolean = mgr.radioSettings.run {
            config(10) != null && config(RadioSettingsCache.CONFIG_DEVICE) != null &&
                config(RadioSettingsCache.CONFIG_POSITION) != null && config(RadioSettingsCache.CONFIG_LORA) != null &&
                channel(0) != null && owner() != null
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
    private class AsFound(val device: ByteArray, val position: ByteArray, val lora: ByteArray, val channel0: ByteArray, val owner: ByteArray) {
        companion object {
            fun of(cache: RadioSettingsCache) = AsFound(
                cache.config(RadioSettingsCache.CONFIG_DEVICE)!!, cache.config(RadioSettingsCache.CONFIG_POSITION)!!,
                cache.config(RadioSettingsCache.CONFIG_LORA)!!, cache.channel(0)!!, cache.owner()!!,
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

    /** The draft the Device Settings screen would hold if the operator had changed nothing. */
    private fun draftFrom(cache: RadioSettingsCache): MeshDeviceConfig {
        val owner = Msg(cache.owner()!!)
        val device = Msg(cache.config(RadioSettingsCache.CONFIG_DEVICE)!!)
        val position = Msg(cache.config(RadioSettingsCache.CONFIG_POSITION)!!)
        val lora = Msg(cache.config(RadioSettingsCache.CONFIG_LORA)!!)
        val channel = Msg(cache.channel(0)!!)
        val role = MeshRole.entries.first { roleOrdinal(it) == (device.varint(1) ?: 0L).toInt() }
        val preset = MeshChannelPreset.entries.first { presetOrdinal(it) == (lora.varint(2) ?: 0L).toInt() }
        return MeshDeviceConfig(
            longName = owner.string(2) ?: "",
            shortName = owner.string(3) ?: "",
            role = role,
            positionBroadcastSecs = (position.varint(1) ?: 0L).toInt(),
            channelName = channel.sub(2)?.string(3) ?: "",
            channelPreset = preset,
        )
    }

    // Enum ordinals from config.proto, written out here so the test does not lean on the app's own tables.
    private fun roleOrdinal(role: MeshRole) = when (role) {
        MeshRole.CLIENT -> 0; MeshRole.CLIENT_MUTE -> 1; MeshRole.ROUTER -> 2; MeshRole.ROUTER_CLIENT -> 3
        MeshRole.REPEATER -> 4; MeshRole.TRACKER -> 5; MeshRole.SENSOR -> 6; MeshRole.TAK -> 7
        MeshRole.CLIENT_HIDDEN -> 8; MeshRole.LOST_AND_FOUND -> 9; MeshRole.TAK_TRACKER -> 10
    }

    private fun presetOrdinal(preset: MeshChannelPreset) = when (preset) {
        MeshChannelPreset.LONG_FAST -> 0; MeshChannelPreset.LONG_SLOW -> 1; MeshChannelPreset.VERY_LONG_SLOW -> 2
        MeshChannelPreset.MEDIUM_SLOW -> 3; MeshChannelPreset.MEDIUM_FAST -> 4; MeshChannelPreset.SHORT_SLOW -> 5
        MeshChannelPreset.SHORT_FAST -> 6; MeshChannelPreset.SHORT_TURBO -> 8
    }

    /**
     * Make one change through the app, let the radio reboot, download again, and return what the radio now says.
     * [change] runs against the live session and returns the result of the app call.
     */
    private fun stepThroughApp(
        name: String,
        s: Session,
        expect: AdminWriteResult = AdminWriteResult.Sent(1),
        change: (Session) -> AdminWriteResult,
    ): Pair<Session, Map<String, Any?>> {
        val result = change(s)
        log("$name: the app reported $result")
        assertEquals("$name: every write must go out", expect, result)
        s.awaitRebootAndForget()
        s.close()
        val next = connect()
        return next to view(next.cache)
    }

    @Test fun settings_writes_change_only_the_edited_field() {
        assumeTrue("MESHSIM_HOST is not set, so the simulated-radio test is skipped", host != null)

        var s = connect()
        val found = AsFound.of(s.cache)
        val asFound = view(s.cache)
        log("as found: $asFound")
        var readsAfterRestore: Map<String, Pair<Any?, Any?>>? = null

        try {
            // 2. admin responses reach the cache
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

            // 3. arrange a radio that has something to lose
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

            // 4a. a position interval change, through pushDeviceConfig (one edit transaction)
            var before: Map<String, Any?> = seeded
            var step = stepThroughApp("position interval", s) { sess ->
                runBlocking { sess.mgr.pushDeviceConfig(draftFrom(sess.cache).copy(positionBroadcastSecs = 321)) }
            }
            s = step.first
            var changed = diff(before, step.second)
            log("position interval changed: $changed")
            assertEquals(
                "only the position interval may change",
                mapOf("position.position_broadcast_secs" to (900L to 321L)),
                changed,
            )

            // 4b. a LoRa preset change
            before = step.second
            step = stepThroughApp("LoRa preset", s) { sess ->
                runBlocking { sess.mgr.pushDeviceConfig(draftFrom(sess.cache).copy(channelPreset = MeshChannelPreset.MEDIUM_FAST)) }
            }
            s = step.first
            changed = diff(before, step.second)
            log("LoRa preset changed: $changed")
            assertEquals(
                "only the modem preset may change: region, hop limit and the transmit switch must stay",
                mapOf("lora.modem_preset" to (6L to 4L)),
                changed,
            )

            // 4c. a channel 0 rename
            before = step.second
            step = stepThroughApp("channel 0 rename", s) { sess ->
                runBlocking { sess.mgr.pushDeviceConfig(draftFrom(sess.cache).copy(channelName = "simren")) }
            }
            s = step.first
            changed = diff(before, step.second)
            log("channel 0 renamed: $changed")
            assertEquals(
                "only the channel name may change: the 32 byte key and the position precision must stay",
                mapOf("channel0.name" to ("simtest" to "simren")),
                changed,
            )

            // 4d. a rebroadcast mode change, a single write outside a transaction
            before = step.second
            step = stepThroughApp("rebroadcast mode", s) { sess ->
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

            // 4e. an owner rename, a single write outside a transaction
            before = step.second
            step = stepThroughApp("owner rename", s) { sess ->
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

            // 4f. one push that changes four settings at once: begin, four writes, commit
            before = step.second
            step = stepThroughApp("four changes in one push", s, expect = AdminWriteResult.Sent(4)) { sess ->
                runBlocking {
                    sess.mgr.pushDeviceConfig(
                        draftFrom(sess.cache).copy(
                            longName = "Sim Four", shortName = "SF4", positionBroadcastSecs = 123,
                            channelName = "simfour", channelPreset = MeshChannelPreset.SHORT_FAST,
                        ),
                    )
                }
            }
            s = step.first
            changed = diff(before, step.second)
            log("four changes in one push: $changed")
            assertEquals(
                "exactly the four settings that were edited may change, and every write of the batch must land",
                setOf(
                    "owner.long_name", "owner.short_name", "position.position_broadcast_secs",
                    "channel0.name", "lora.modem_preset",
                ),
                changed.keys,
            )
            assertEquals(123L, step.second["position.position_broadcast_secs"])
            assertEquals("simfour", step.second["channel0.name"])
            assertEquals(6L, step.second["lora.modem_preset"])
            assertEquals("region and hop limit survive the batch", 1L, step.second["lora.region"])
            assertEquals(32, step.second["channel0.key_length"])

            // A push with nothing changed sends nothing.
            val quiet = runBlocking { s.mgr.pushDeviceConfig(draftFrom(s.cache)) }
            assertEquals("an unchanged draft is a no-op on a real radio too", AdminWriteResult.NothingToChange, quiet)
        } finally {
            // 5. put back what the test changed (best effort, even when an assertion failed above)
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

        // Only reached when every step above passed, so a failure there is never hidden by this one.
        assertEquals(
            "after the test puts its changes back, every watched field reads as it did when the test started",
            emptyMap<String, Pair<Any?, Any?>>(),
            readsAfterRestore,
        )
    }

    private companion object {
        const val PORT = 4403
        const val REBOOT_WAIT_MS = 45_000L
    }
}
