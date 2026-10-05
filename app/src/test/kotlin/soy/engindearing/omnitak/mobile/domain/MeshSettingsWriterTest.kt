package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.AdminMessageParser
import soy.engindearing.omnitak.mobile.data.AdminReads
import soy.engindearing.omnitak.mobile.data.AdminResponse
import soy.engindearing.omnitak.mobile.data.AdminSetting
import soy.engindearing.omnitak.mobile.data.AdminTestFrames
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.fields
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.keyBytes
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.single
import soy.engindearing.omnitak.mobile.data.AdminWriteResult
import soy.engindearing.omnitak.mobile.data.AdminWriteResult.Incomplete.Cause
import soy.engindearing.omnitak.mobile.data.DeviceEdits
import soy.engindearing.omnitak.mobile.data.DeviceSettingsState
import soy.engindearing.omnitak.mobile.data.FakeRadio
import soy.engindearing.omnitak.mobile.data.MeshChannel
import soy.engindearing.omnitak.mobile.data.MeshChannelPreset
import soy.engindearing.omnitak.mobile.data.MeshConnectionType
import soy.engindearing.omnitak.mobile.data.MeshPacketDecoded
import soy.engindearing.omnitak.mobile.data.MeshRegion
import soy.engindearing.omnitak.mobile.data.MeshRole
import soy.engindearing.omnitak.mobile.data.ProtoMsg
import soy.engindearing.omnitak.mobile.data.RadioSettingsCache
import soy.engindearing.omnitak.mobile.data.RadioSettingsCache.Key
import soy.engindearing.omnitak.mobile.data.RebroadcastMode
import soy.engindearing.omnitak.mobile.data.RefusalReason
import soy.engindearing.omnitak.mobile.data.SentLedger

/**
 * [MeshSettingsWriter] against a [FakeRadio] that answers reads and replaces
 * whole messages on writes, the way the firmware does.
 *
 * What is pinned here: only the edited settings are written; each is patched
 * onto what the radio holds when it is about to be written, never onto an older
 * copy; the role goes first and what follows is read after it; every sequence
 * is inside begin and commit; a radio that does not answer is left alone; and
 * the result says what was and was not sent.
 *
 * Names, node numbers and key bytes are made up.
 */
class MeshSettingsWriterTest {

    private val node = 0x0A0B0C0Du
    private val otherNode = 0x01020304u

    /** A writer, a radio and the cache the radio's answers land in. Times are virtual. */
    private class Rig(
        scope: TestScope,
        val radio: FakeRadio = FakeRadio.factory(),
        destination: UInt? = 0x0A0B0C0Du,
        frameSpacingMs: Long = 0,
        var readTimeoutMs: Long = 3_000,
        var latencyMs: Long = 0,
        val ledger: SentLedger = SentLedger(),
        /** 1-based numbers of the frames the link refuses. */
        val failAt: MutableSet<Int> = mutableSetOf(),
    ) {
        val cache = RadioSettingsCache()
        val frameTimes = mutableListOf<Long>()
        private var attempts = 0
        private val clock = scope
        private val answers: CoroutineScope = scope
        private val node = destination ?: 0x0A0B0C0Du

        /** The reads the writer has outstanding. Deadlines run on the test's virtual clock. */
        val reads = AdminReads(clock = { scope.currentTime })

        val writer = MeshSettingsWriter(
            cache, { destination }, { frame -> send(frame) },
            ledger = ledger, frameSpacingMs = frameSpacingMs, readTimeoutMs = { readTimeoutMs }, reads = reads,
        )

        private suspend fun send(frame: ByteArray): Boolean {
            attempts++
            if (attempts in failAt) return false
            frameTimes += clock.currentTime
            val wait = latencyMs // as it is now: a coroutine launched here starts later, when the test body suspends
            radio.handle(frame) { admin, id ->
                if (wait > 0) answers.launch { delay(wait); answer(admin, id) } else answer(admin, id)
            }
            return true
        }

        /** What the manager does with a frame from the radio: the rule, then the cache, then the read waiting for it. */
        fun answer(admin: ByteArray, requestId: UInt) {
            val packet = MeshPacketDecoded(from = node, to = node, channel = 0u, portnum = 6u, payload = admin, requestId = requestId)
            val decision = reads.admit(packet, node)
            decision.answer?.let { cache.put(it.key, it.bytes) }
            decision.deliver()
        }
    }

    private fun sameBytes(what: String, expected: ByteArray, actual: ByteArray?) {
        // Not assertArrayEquals: its failure message would print key bytes.
        assertTrue("$what is missing", actual != null)
        assertEquals("$what (length)", expected.size, actual!!.size)
        assertTrue("$what (bytes differ)", expected.contentEquals(actual))
    }

    private val key = keyBytes(0x61)

    // region only what was edited is written ----------------------------------------------

    @Test fun `one edited setting is one write, inside a transaction, patched onto a fresh read`() = runTest {
        val rig = Rig(this)
        val result = rig.writer.pushDeviceConfig(DeviceEdits(positionBroadcastSecs = 300))

        assertEquals(AdminWriteResult.Sent(listOf(AdminSetting.POSITION_INTERVAL)), result)
        assertEquals(listOf("get_config:2", "begin", "set_config:2", "commit"), rig.radio.log)
        sameBytes("position config", ProtoMsg().varint(1, 300).varint(7, 811).varint(13, 1).build(), rig.radio.config[2])
        assertFalse("the transaction is closed", rig.radio.inTransaction)
    }

    @Test fun `a factory radio and a fresh install draft, edit one field and exactly one write goes out`() = runTest {
        val rig = Rig(this)
        // The screen's state: fresh-install defaults (TAK, "OmniTAK", 30 s), then what the radio reports.
        var state = DeviceSettingsState()
        for (report in reportsFrom(rig.radio)) state = state.withReport(report)

        val edits = state.edits(state.draft.copy(positionBroadcastSecs = 300))
        assertEquals(listOf(AdminSetting.POSITION_INTERVAL), edits.settings)

        val roleBefore = rig.radio.config[1]!!.copyOf()
        val channelBefore = rig.radio.channels[0]!!.copyOf()
        val result = rig.writer.pushDeviceConfig(edits)

        assertEquals(AdminWriteResult.Sent(listOf(AdminSetting.POSITION_INTERVAL)), result)
        assertEquals("one write, and it is the position config", 1, rig.radio.log.count { it.startsWith("set_") })
        assertEquals(listOf("get_config:2", "begin", "set_config:2", "commit"), rig.radio.log)
        sameBytes("device config (the role is still CLIENT, still not on the wire)", roleBefore, rig.radio.config[1])
        sameBytes("primary channel (still unnamed, same key)", channelBefore, rig.radio.channels[0])
    }

    @Test fun `a role-only push leaves the interval of a radio above the app's limit alone`() = runTest {
        val rig = Rig(this)
        // Another client set the interval to three days: more than the app lets the operator type.
        val threeDays = ProtoMsg().varint(1, 259_200).varint(7, 811).varint(13, 1).build()
        rig.radio.config[2] = threeDays.copyOf()
        var state = DeviceSettingsState()
        for (report in reportsFrom(rig.radio)) state = state.withReport(report)

        val edits = state.edits(state.draft.copy(role = MeshRole.CLIENT_MUTE))
        assertEquals(listOf(AdminSetting.ROLE), edits.settings)
        val result = rig.writer.pushDeviceConfig(edits)

        assertEquals(AdminWriteResult.Sent(listOf(AdminSetting.ROLE)), result)
        assertEquals("the position config was neither read nor written", listOf("get_config:1", "begin", "set_config:1", "commit"), rig.radio.log)
        sameBytes("position config (still three days)", threeDays, rig.radio.config[2])
    }

    @Test fun `nothing edited is nothing sent`() = runTest {
        val rig = Rig(this)
        assertEquals(AdminWriteResult.NothingToChange, rig.writer.pushDeviceConfig(DeviceEdits()))
        assertTrue(rig.radio.log.isEmpty())
    }

    @Test fun `a value the radio already holds is not written, and no transaction is opened for it`() = runTest {
        val rig = Rig(this) // interval 900
        val result = rig.writer.pushDeviceConfig(DeviceEdits(positionBroadcastSecs = 900))
        assertEquals(AdminWriteResult.NothingToChange, result)
        assertEquals("only the read", listOf("get_config:2"), rig.radio.log)
    }

    // endregion

    // region role first, then a fresh read -----------------------------------------------------

    @Test fun `the role goes first, and the position write is patched onto bytes read after the role defaults went in`() = runTest {
        val rig = Rig(this)
        // The firmware installs role defaults when the role changes: position flags 999 and interval 60.
        rig.radio.onSetConfig = { variant, radio ->
            if (variant == 1) radio.config[2] = ProtoMsg().varint(1, 60).varint(7, 999).varint(13, 1).build()
        }

        val result = rig.writer.pushDeviceConfig(DeviceEdits(role = MeshRole.TRACKER, positionBroadcastSecs = 300))

        assertEquals(AdminWriteResult.Sent(listOf(AdminSetting.ROLE, AdminSetting.POSITION_INTERVAL)), result)
        assertEquals(
            listOf("get_config:1", "begin", "set_config:1", "get_config:2", "set_config:2", "commit"),
            rig.radio.log,
        )
        sameBytes(
            "position config: the interval is the operator's, the flags are the role's defaults",
            ProtoMsg().varint(1, 300).varint(7, 999).varint(13, 1).build(),
            rig.radio.config[2],
        )
    }

    @Test fun `edits are written in the order role, owner, interval, channel name, preset`() = runTest {
        val rig = Rig(this)
        val edits = DeviceEdits(
            longName = "New Long", shortName = "NEW", role = MeshRole.ROUTER, positionBroadcastSecs = 60,
            channelName = "ops", channelPreset = MeshChannelPreset.MEDIUM_FAST,
        )
        val result = rig.writer.pushDeviceConfig(edits)

        assertEquals(edits.settings, (result as AdminWriteResult.Sent).written)
        assertEquals(
            listOf(
                "get_config:1", "begin", "set_config:1",
                "get_owner", "set_owner",
                "get_config:2", "set_config:2",
                "get_channel:0", "set_channel:0",
                "get_config:6", "set_config:6",
                "commit",
            ),
            rig.radio.log,
        )
    }

    // endregion

    // region a fresh read, not the cache ----------------------------------------------------------

    @Test fun `a channel key rotated by another client is not put back by a rename`() = runTest {
        // The answer takes a moment, as it does on a real link, so an old copy in the cache would be used if it were allowed.
        val rig = Rig(this, latencyMs = 100)
        val oldKey = keyBytes(0x10)
        // What the app saw when the link came up (and still has).
        rig.cache.put(Key.Channel(0), ProtoMsg().msg(2, ProtoMsg().bytes(2, oldKey)).varint(3, 1).build())
        // Another client has since rotated the key: the radio holds key B.
        val rotated = keyBytes(0x99)
        rig.radio.channels[0] = ProtoMsg().msg(2, ProtoMsg().bytes(2, rotated).msg(7, ProtoMsg().varint(1, 13))).varint(3, 1).build()

        rig.writer.pushDeviceConfig(DeviceEdits(channelName = "ops"))

        val settings = fields(fields(rig.radio.channels[0]!!).single(2).bytes)
        assertTrue("the rotated key survives the rename", rotated.contentEquals(settings.single(2).bytes))
        assertEquals("ops", settings.single(3).bytes.toString(Charsets.UTF_8))
        assertEquals("position precision survives too", 13uL, fields(settings.single(7).bytes).single(1).varint)
    }

    @Test fun `the cache does not become what was sent`() = runTest {
        val rig = Rig(this)
        rig.writer.pushDeviceConfig(DeviceEdits(positionBroadcastSecs = 300, channelName = "ops"))

        assertNull("the written entries are dropped, not stored as if the radio had accepted them", rig.cache.config(2))
        assertNull(rig.cache.channel(0))
    }

    @Test fun `a slow answer is waited for`() = runTest {
        val rig = Rig(this, latencyMs = 1_500)
        val result = rig.writer.pushDeviceConfig(DeviceEdits(positionBroadcastSecs = 300))
        assertEquals(AdminWriteResult.Sent(listOf(AdminSetting.POSITION_INTERVAL)), result)
    }

    @Test fun `a read ends with the answer to its own request, not with an older answer for the same entry`() = runTest {
        val rig = Rig(this, latencyMs = 1_000)
        // Twelve reads go out and their answers are a second away: the position config says flags 811.
        rig.writer.readAll()
        // Another client changes the flags. The write that follows asks again, and this answer is slower.
        rig.radio.config[2] = ProtoMsg().varint(1, 900).varint(7, 999).varint(13, 1).build()
        rig.latencyMs = 2_500

        val result = rig.writer.pushDeviceConfig(DeviceEdits(positionBroadcastSecs = 300))

        assertEquals(AdminWriteResult.Sent(listOf(AdminSetting.POSITION_INTERVAL)), result)
        // The older answer got there first, at one second. It is the radio's own, but not the answer to this read.
        sameBytes("position config", ProtoMsg().varint(1, 300).varint(7, 999).varint(13, 1).build(), rig.radio.config[2])
    }

    // endregion

    // region a radio that does not answer ------------------------------------------------------------

    @Test fun `a radio that does not answer is left alone and the result says why`() = runTest {
        val rig = Rig(this, readTimeoutMs = 3_000)
        rig.radio.answers = false

        val result = rig.writer.pushDeviceConfig(DeviceEdits(role = MeshRole.ROUTER))

        assertEquals(AdminWriteResult.Refused(RefusalReason.NO_ANSWER), result)
        assertEquals("only the read went out: no begin, no write, no commit", listOf("ignored get_config:1"), rig.radio.log)
        assertTrue("it waited a few seconds, not forever", currentTime in 3_000..4_000)
        // All that is known is that the radio did not answer. A managed radio looks the same as a slow link.
        assertTrue(result.describe().contains("did not answer"))
        assertFalse("not a guess about why: ${result.describe()}", result.describe().contains("managed"))
        assertEquals("a refusal is not a write", false, result.reachedRadio)
    }

    @Test fun `a slower link gets a longer wait before a write is refused`() = runTest {
        // An answer at five seconds: late over TCP, in time over Bluetooth.
        val tcp = Rig(this, latencyMs = 5_000, readTimeoutMs = MeshSettingsWriter.readTimeoutFor(MeshConnectionType.TCP))
        assertEquals(AdminWriteResult.Refused(RefusalReason.NO_ANSWER), tcp.writer.pushDeviceConfig(DeviceEdits(positionBroadcastSecs = 300)))

        val ble = Rig(this, latencyMs = 5_000, readTimeoutMs = MeshSettingsWriter.readTimeoutFor(MeshConnectionType.BLUETOOTH))
        assertEquals(AdminWriteResult.Sent(listOf(AdminSetting.POSITION_INTERVAL)), ble.writer.pushDeviceConfig(DeviceEdits(positionBroadcastSecs = 300)))
    }

    @Test fun `the read timeout follows the link in use`() {
        assertEquals(3_000L, MeshSettingsWriter.readTimeoutFor(MeshConnectionType.TCP))
        assertEquals("before any transport is chosen", 3_000L, MeshSettingsWriter.readTimeoutFor(null))
        assertEquals(8_000L, MeshSettingsWriter.readTimeoutFor(MeshConnectionType.BLUETOOTH))
    }

    @Test fun `a timeout in the middle of a push says the radio stopped answering, not why`() = runTest {
        val rig = Rig(this)
        rig.radio.onSetConfig = { _, radio -> radio.answers = false }
        val result = rig.writer.pushDeviceConfig(DeviceEdits(role = MeshRole.ROUTER, positionBroadcastSecs = 300))
        assertFalse("not a guess about why: ${result.describe()}", result.describe().contains("managed"))
        assertTrue(result.describe().contains("stopped answering"))
    }

    @Test fun `an answer that comes too late is a refusal`() = runTest {
        val rig = Rig(this, latencyMs = 5_000, readTimeoutMs = 3_000)
        assertEquals(AdminWriteResult.Refused(RefusalReason.NO_ANSWER), rig.writer.pushDeviceConfig(DeviceEdits(positionBroadcastSecs = 300)))
        assertFalse(rig.radio.log.any { it.startsWith("set_") })
    }

    @Test fun `a radio that stops answering part way reports what was sent and what was not`() = runTest {
        val rig = Rig(this)
        rig.radio.onSetConfig = { _, radio -> radio.answers = false } // goes quiet after the role write

        val result = rig.writer.pushDeviceConfig(DeviceEdits(role = MeshRole.ROUTER, positionBroadcastSecs = 300))

        assertEquals(
            AdminWriteResult.Incomplete(
                written = listOf(AdminSetting.ROLE), notWritten = listOf(AdminSetting.POSITION_INTERVAL),
                cause = Cause.NO_ANSWER, committed = true,
            ),
            result,
        )
        assertEquals(
            "the transaction is still closed",
            listOf("get_config:1", "begin", "set_config:1", "ignored get_config:2", "ignored commit"),
            rig.radio.log,
        )
        assertTrue(result.describe().contains("Sent to the radio: role."))
        assertTrue(result.describe().contains("Not sent: position interval"))
        assertTrue(result.reachedRadio)
    }

    // endregion

    // region transactions ---------------------------------------------------------------------------------

    @Test fun `even a single write is inside begin and commit`() = runTest {
        val rig = Rig(this)
        val result = rig.writer.applyRebroadcastMode(RebroadcastMode.KNOWN_ONLY)

        assertEquals(AdminWriteResult.Sent(listOf(AdminSetting.REBROADCAST_MODE)), result)
        assertEquals(listOf("get_config:1", "begin", "set_config:1", "commit"), rig.radio.log)
        sameBytes(
            "device config: the mode changed, the time zone and LED switch stayed",
            ProtoMsg().varint(6, 3).varint(7, 7200).string(11, "PST8PDT,M3.2.0,M11.1.0").bool(12, true).build(),
            rig.radio.config[1],
        )
    }

    @Test fun `a transaction left open by a dropped link is closed by the next write`() = runTest {
        val rig = Rig(this)
        rig.radio.inTransaction = true // an earlier link died between begin and commit
        rig.writer.applyRebroadcastMode(RebroadcastMode.LOCAL_ONLY)
        assertFalse(rig.radio.inTransaction)
    }

    @Test fun `the link dropping before the commit says some settings may have been applied`() = runTest {
        val rig = Rig(this, failAt = mutableSetOf(4)) // get, begin, set, then the commit fails
        val result = rig.writer.pushDeviceConfig(DeviceEdits(role = MeshRole.ROUTER))

        assertEquals(
            AdminWriteResult.Incomplete(listOf(AdminSetting.ROLE), emptyList(), Cause.LINK_LOST, committed = false),
            result,
        )
        val text = result.describe()
        assertTrue(text, text.contains("may have been applied"))
        assertTrue(text, text.contains("Reconnect and check"))
        assertFalse("the write did reach the radio: " + text, text.contains("did not reach"))
        assertTrue(result.reachedRadio)
    }

    @Test fun `a write that cannot be sent still gets its commit, and the result does not say nothing reached the radio`() = runTest {
        val rig = Rig(this, failAt = mutableSetOf(5)) // get, begin, set (ok), get, set fails, commit goes out
        val result = rig.writer.pushDeviceConfig(DeviceEdits(role = MeshRole.ROUTER, positionBroadcastSecs = 300))

        assertEquals(
            AdminWriteResult.Incomplete(
                written = listOf(AdminSetting.ROLE), notWritten = listOf(AdminSetting.POSITION_INTERVAL),
                cause = Cause.LINK_LOST, committed = true,
            ),
            result,
        )
        assertEquals("get_config:1", rig.radio.log.first())
        assertEquals("commit", rig.radio.log.last())
        assertFalse(result.describe().contains("did not reach"))
        assertTrue(result.describe().contains("saved what it received"))
    }

    @Test fun `a begin that cannot be sent writes nothing`() = runTest {
        val rig = Rig(this, failAt = mutableSetOf(2))
        assertEquals(AdminWriteResult.Refused(RefusalReason.NO_RADIO), rig.writer.pushDeviceConfig(DeviceEdits(role = MeshRole.ROUTER)))
        assertEquals(listOf("get_config:1"), rig.radio.log)
    }

    @Test fun `a read that cannot be sent is no radio`() = runTest {
        val rig = Rig(this, failAt = mutableSetOf(1))
        assertEquals(AdminWriteResult.Refused(RefusalReason.NO_RADIO), rig.writer.pushDeviceConfig(DeviceEdits(role = MeshRole.ROUTER)))
        assertTrue(rig.radio.log.isEmpty())
    }

    @Test fun `with no radio to address nothing is sent at all`() = runTest {
        val rig = Rig(this, destination = null)
        val w = rig.writer
        assertEquals(AdminWriteResult.Refused(RefusalReason.NO_RADIO), w.pushDeviceConfig(DeviceEdits(role = MeshRole.ROUTER)))
        assertEquals(AdminWriteResult.Refused(RefusalReason.NO_RADIO), w.applyRebroadcastMode(RebroadcastMode.ALL))
        assertEquals(AdminWriteResult.Refused(RefusalReason.NO_RADIO), w.applyLoRaConfig(MeshRegion.US, MeshChannelPreset.LONG_FAST))
        assertEquals(AdminWriteResult.Refused(RefusalReason.NO_RADIO), w.applyOwner("A", "B"))
        assertEquals(AdminWriteResult.Refused(RefusalReason.NO_RADIO), w.applyChannel(MeshChannel(name = "Shared", psk = key)))
        assertEquals(0, w.readAll())
        assertTrue(rig.radio.log.isEmpty())
    }

    // endregion

    // region what was sent is remembered ---------------------------------------------------------------------

    @Test fun `what was sent is remembered for that radio, and judged by its next report`() = runTest {
        val rig = Rig(this)
        rig.writer.pushDeviceConfig(DeviceEdits(role = MeshRole.TAK, positionBroadcastSecs = 300))

        assertEquals("another radio's report judges nothing", emptyList<AdminSetting>(), rig.ledger.check(otherNode, AdminResponse.PositionConfig(900)))
        assertEquals("the radio kept 900", listOf(AdminSetting.POSITION_INTERVAL), rig.ledger.check(node, AdminResponse.PositionConfig(900)))
        assertEquals("the radio took the role", emptyList<AdminSetting>(), rig.ledger.check(node, AdminResponse.DeviceConfig(MeshRole.TAK, RebroadcastMode.ALL)))
    }

    @Test fun `a value that was already set is not remembered as sent`() = runTest {
        val rig = Rig(this)
        rig.writer.pushDeviceConfig(DeviceEdits(positionBroadcastSecs = 900)) // the radio has it
        assertEquals(0, rig.ledger.size)
    }

    // endregion

    // region the LoRa config ------------------------------------------------------------------------------------

    @Test fun `a preset-only apply keeps the radio's region`() = runTest {
        val rig = Rig(this)
        rig.radio.config[6] = ProtoMsg().bool(1, true).varint(2, 6).varint(7, 3).varint(8, 5).bool(9, true).build() // EU_868
        val result = rig.writer.applyLoRaConfig(MeshRegion.UNSET, MeshChannelPreset.MEDIUM_FAST)

        assertEquals(AdminWriteResult.Sent(listOf(AdminSetting.MODEM_PRESET)), result)
        sameBytes(
            "lora config: preset 4, region 3, hop limit 5, transmit on",
            ProtoMsg().bool(1, true).varint(2, 4).varint(7, 3).varint(8, 5).bool(9, true).build(),
            rig.radio.config[6],
        )
    }

    @Test fun `a region-only apply leaves the preset`() = runTest {
        val rig = Rig(this)
        rig.radio.config[6] = ProtoMsg().bool(1, true).varint(2, 6).varint(7, 1).varint(8, 5).build()
        val result = rig.writer.applyLoRaConfig(MeshRegion.EU_868, null)

        assertEquals(AdminWriteResult.Sent(listOf(AdminSetting.REGION)), result)
        sameBytes(
            "lora config: region 3, preset still 6",
            ProtoMsg().bool(1, true).varint(2, 6).varint(7, 3).varint(8, 5).build(),
            rig.radio.config[6],
        )
    }

    @Test fun `nothing picked is nothing to change`() = runTest {
        val rig = Rig(this)
        assertEquals(AdminWriteResult.NothingToChange, rig.writer.applyLoRaConfig(MeshRegion.UNSET, null))
        assertTrue(rig.radio.log.isEmpty())
    }

    // endregion

    // region the owner --------------------------------------------------------------------------------------------------

    @Test fun `an owner rename keeps the licensed flag`() = runTest {
        val rig = Rig(this)
        rig.radio.owner = AdminTestFrames.userMessage(longName = "Sim Radio One", shortName = "SR1", licensed = true)
        val result = rig.writer.applyOwner("New Long", "NEW")

        assertEquals(AdminWriteResult.Sent(listOf(AdminSetting.LONG_NAME, AdminSetting.SHORT_NAME)), result)
        val user = fields(rig.radio.owner)
        assertEquals("New Long", user.single(2).bytes.toString(Charsets.UTF_8))
        assertEquals("the licensed flag survived the rename", 1uL, user.single(6).varint)
    }

    @Test fun `an owner write with both names blank has nothing to say`() = runTest {
        val rig = Rig(this)
        assertEquals(AdminWriteResult.NothingToChange, rig.writer.applyOwner("", "  "))
        assertTrue(rig.radio.log.isEmpty())
    }

    // endregion

    // region importing a channel ------------------------------------------------------------------------------------------

    private fun FakeRadio.useSlot(index: Int, role: Int = 2) {
        channels[index] = ProtoMsg().varint(1, index).msg(2, ProtoMsg().bytes(2, keyBytes(index)).string(3, "slot$index")).varint(3, role).build()
    }

    @Test fun `an imported channel goes into the first free secondary slot, and the primary is left alone`() = runTest {
        val rig = Rig(this)
        rig.radio.useSlot(1) // in use; slot 2 is free
        val primaryBefore = rig.radio.channels[0]!!.copyOf()
        val slot1Before = rig.radio.channels[1]!!.copyOf()

        val result = rig.writer.applyChannel(MeshChannel(name = "Shared", psk = key))

        assertEquals(AdminWriteResult.Sent(listOf(AdminSetting.CHANNEL)), result)
        assertEquals(listOf("get_channel:1", "get_channel:2", "begin", "set_channel:2", "commit"), rig.radio.log)
        sameBytes("primary channel", primaryBefore, rig.radio.channels[0])
        sameBytes("slot 1", slot1Before, rig.radio.channels[1])
        val channel = fields(rig.radio.channels[2]!!)
        assertEquals("secondary", 2uL, channel.single(3).varint)
        val settings = fields(channel.single(2).bytes)
        assertEquals("Shared", settings.single(3).bytes.toString(Charsets.UTF_8))
        assertTrue(key.contentEquals(settings.single(2).bytes))
    }

    @Test fun `the first import on a factory radio lands in slot 1, not on the primary`() = runTest {
        val rig = Rig(this)
        val primaryBefore = rig.radio.channels[0]!!.copyOf()
        rig.writer.applyChannel(MeshChannel(name = "Shared", psk = key))
        sameBytes("primary channel (name, key, precision)", primaryBefore, rig.radio.channels[0])
        assertTrue(rig.radio.log.contains("set_channel:1"))
    }

    @Test fun `the primary is replaced only when the operator asks, with no reads`() = runTest {
        val rig = Rig(this)
        val result = rig.writer.applyChannel(MeshChannel(name = "Shared", psk = key), replacePrimary = true)

        assertEquals(AdminWriteResult.Sent(listOf(AdminSetting.CHANNEL)), result)
        assertEquals(listOf("begin", "set_channel:0", "commit"), rig.radio.log)
        val settings = fields(fields(rig.radio.channels[0]!!).single(2).bytes)
        assertEquals("Shared", settings.single(3).bytes.toString(Charsets.UTF_8))
    }

    @Test fun `with every secondary slot in use nothing is imported`() = runTest {
        val rig = Rig(this)
        for (i in 1..7) rig.radio.useSlot(i)
        val result = rig.writer.applyChannel(MeshChannel(name = "Shared", psk = key))

        assertEquals(AdminWriteResult.Refused(RefusalReason.NO_FREE_SLOT), result)
        assertEquals((1..7).map { "get_channel:$it" }, rig.radio.log)
    }

    @Test fun `an import needs the radio to say which slots are free`() = runTest {
        val rig = Rig(this)
        rig.radio.answers = false
        assertEquals(AdminWriteResult.Refused(RefusalReason.NO_ANSWER), rig.writer.applyChannel(MeshChannel(name = "Shared", psk = key)))
        assertFalse(rig.radio.log.any { it.contains("set_") || it.contains("begin") })
    }

    @Test fun `an imported channel name is cut to 11 bytes`() = runTest {
        val rig = Rig(this)
        rig.writer.applyChannel(MeshChannel(name = "x".repeat(40), psk = key))
        val settings = fields(fields(rig.radio.channels[1]!!).single(2).bytes)
        assertEquals(11, settings.single(3).bytes.size)
    }

    // endregion

    // region spacing, turns and cancellation ------------------------------------------------------------------------------

    @Test fun `frames go out a gap apart, reads included`() = runTest {
        val rig = Rig(this, frameSpacingMs = 100)
        rig.writer.pushDeviceConfig(DeviceEdits(role = MeshRole.ROUTER, positionBroadcastSecs = 300))

        assertEquals("get, begin, set, get, set, commit", 6, rig.frameTimes.size)
        assertTrue(rig.frameTimes.zipWithNext { a, b -> b - a }.all { it >= 100 })
    }

    @Test fun `reading everything takes its turn behind a write and spaces its requests`() = runTest {
        val rig = Rig(this, frameSpacingMs = 100, latencyMs = 300)
        val push = async { rig.writer.pushDeviceConfig(DeviceEdits(positionBroadcastSecs = 300)) }
        val read = async { rig.writer.readAll() }

        assertEquals(AdminWriteResult.Sent(listOf(AdminSetting.POSITION_INTERVAL)), push.await())
        assertEquals(12, read.await())
        val log = rig.radio.log
        val commitAt = log.indexOf("commit")
        assertTrue("the whole write comes before the first read-all request: $log", log.indexOf("get_owner") > commitAt)
        val readAllTimes = rig.frameTimes.takeLast(12)
        assertTrue(readAllTimes.zipWithNext { a, b -> b - a }.all { it >= 100 })
    }

    @Test fun `two writes started together run one after the other`() = runTest {
        val rig = Rig(this, latencyMs = 100)
        val first = async { rig.writer.pushDeviceConfig(DeviceEdits(positionBroadcastSecs = 300)) }
        val second = async { rig.writer.pushDeviceConfig(DeviceEdits(channelName = "ops")) }
        first.await()
        second.await()

        assertEquals(
            listOf("get_config:2", "begin", "set_config:2", "commit", "get_channel:0", "begin", "set_channel:0", "commit"),
            rig.radio.log,
        )
    }

    @Test fun `a sequence that has begun reaches its commit even when the caller goes away`() = runTest {
        // A screen left mid-push cancels its scope. A transaction left open would swallow the next edit from any client.
        val rig = Rig(this, frameSpacingMs = 100)
        val begun = CompletableDeferred<Unit>()
        val push = launch {
            rig.writer.pushDeviceConfig(DeviceEdits(role = MeshRole.ROUTER, positionBroadcastSecs = 300))
        }
        // Cancel as soon as the radio has seen the begin. The wait is bounded, so a writer that never begins
        // fails this test instead of leaving it waiting.
        launch {
            val seen = withTimeoutOrNull(10_000) {
                while ("begin" !in rig.radio.log) delay(10)
                true
            }
            if (seen == true) begun.complete(Unit)
            else begun.completeExceptionally(AssertionError("the writer never opened a transaction: ${rig.radio.log}"))
        }
        begun.await()
        push.cancel()
        push.join()

        assertEquals("commit", rig.radio.log.last())
        assertEquals(listOf("get_config:1", "begin", "set_config:1", "get_config:2", "set_config:2", "commit"), rig.radio.log)
    }

    // endregion

    // region the result text ---------------------------------------------------------------------------------------------------

    @Test fun `every outcome has text the operator can act on`() {
        assertEquals(
            "Sent to the radio: role, position interval. The radio restarts to save them.",
            AdminWriteResult.Sent(listOf(AdminSetting.ROLE, AdminSetting.POSITION_INTERVAL)).describe(),
        )
        assertTrue(AdminWriteResult.NothingToChange.describe().startsWith("Nothing to change"))
        assertEquals("No radio connected.", AdminWriteResult.Refused(RefusalReason.NO_RADIO).describe())
        assertTrue(AdminWriteResult.Refused(RefusalReason.UNREADABLE).describe().contains("Reconnect"))
        assertTrue(AdminWriteResult.Refused(RefusalReason.NO_FREE_SLOT).describe().contains("Replace primary"))
        assertFalse(AdminWriteResult.Refused(RefusalReason.NO_ANSWER).reachedRadio)
        assertFalse(AdminWriteResult.NothingToChange.reachedRadio)
        assertTrue(AdminWriteResult.Sent(listOf(AdminSetting.ROLE)).reachedRadio)
    }

    @Test fun `an incomplete write that never sent a setting says nothing was changed`() {
        val result = AdminWriteResult.Incomplete(emptyList(), listOf(AdminSetting.ROLE), Cause.LINK_LOST, committed = false)
        assertTrue(result.describe(), result.describe().contains("Nothing was changed"))
        assertFalse(result.reachedRadio)
    }

    // endregion

    /** The reports the screen would have received from [radio]: owner, device, position and lora config, primary channel. */
    private fun reportsFrom(radio: FakeRadio): List<AdminResponse> = buildList {
        AdminMessageParser.parse(ProtoMsg().bytes(4, radio.owner).build())?.let { add(it) }
        for (variant in listOf(1, 2, 6)) {
            AdminMessageParser.parse(ProtoMsg().msg(6, ProtoMsg().bytes(variant, radio.config.getValue(variant))).build())?.let { add(it) }
        }
        AdminMessageParser.parse(ProtoMsg().bytes(2, radio.channels.getValue(0)).build())?.let { add(it) }
    }
}
