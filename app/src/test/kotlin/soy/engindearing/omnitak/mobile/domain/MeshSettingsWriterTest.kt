package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.AdminTestFrames
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.channelMessage
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.decode
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.deviceConfig
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.fields
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.has
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.keyBytes
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.loraConfig
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.positionConfig
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.single
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.userMessage
import soy.engindearing.omnitak.mobile.data.AdminWriteResult
import soy.engindearing.omnitak.mobile.data.MeshChannel
import soy.engindearing.omnitak.mobile.data.MeshChannelPreset
import soy.engindearing.omnitak.mobile.data.MeshDeviceConfig
import soy.engindearing.omnitak.mobile.data.MeshRegion
import soy.engindearing.omnitak.mobile.data.MeshRole
import soy.engindearing.omnitak.mobile.data.ProtoMsg
import soy.engindearing.omnitak.mobile.data.RadioSettingsCache
import soy.engindearing.omnitak.mobile.data.RadioSettingsCache.Key
import soy.engindearing.omnitak.mobile.data.RebroadcastMode
import soy.engindearing.omnitak.mobile.data.RefusalReason

/**
 * [MeshSettingsWriter]: what gets sent, and what does not.
 *
 * The writer is handed a fake link, so every test can say exactly which frames
 * left the app. Settings the radio "reported" are loaded into a
 * [RadioSettingsCache] with the fixtures from [AdminTestFrames]; names, node
 * numbers and key bytes are made up.
 */
class MeshSettingsWriterTest {

    private val node = 0x0A0B0C0Du

    /** The link: records every frame, and can be told to fail from the Nth frame on (1-based). */
    private class Link(private val failFrom: Int = Int.MAX_VALUE) {
        val frames = mutableListOf<ByteArray>()
        private var attempts = 0

        suspend fun send(frame: ByteArray): Boolean {
            attempts++
            if (attempts >= failFrom) return false
            frames += frame
            return true
        }
    }

    private fun loadedCache(
        device: ByteArray? = deviceConfig(),
        position: ByteArray? = positionConfig(),
        lora: ByteArray? = loraConfig(),
        channel0: ByteArray? = channelMessage(name = "Alpha"),
        owner: ByteArray? = userMessage(longName = "Test Node One", shortName = "TNO"),
    ) = RadioSettingsCache().apply {
        device?.let { put(Key.Config(1), it) }
        position?.let { put(Key.Config(2), it) }
        lora?.let { put(Key.Config(6), it) }
        channel0?.let { put(Key.Channel(0), it) }
        owner?.let { put(Key.Owner, it) }
    }

    private fun writer(cache: RadioSettingsCache, link: Link, destination: UInt? = node) =
        MeshSettingsWriter(cache, destination = { destination }, send = { link.send(it) }, frameSpacingMs = 0)

    /** The draft that matches [loadedCache]'s defaults exactly: nothing to push. */
    private val matchingDraft = MeshDeviceConfig(
        longName = "Test Node One",
        shortName = "TNO",
        role = MeshRole.TAK,
        positionBroadcastSecs = 900,
        channelName = "Alpha",
        channelPreset = MeshChannelPreset.SHORT_FAST,
    )

    private fun adminFieldNumbers(frame: ByteArray): List<Int> = decode(frame).admin.map { it.number }

    // region refusals: nothing is sent --------------------------------------

    private fun everyWrite(w: MeshSettingsWriter): Map<String, suspend () -> AdminWriteResult> = mapOf(
        "rebroadcast" to { w.applyRebroadcastMode(RebroadcastMode.KNOWN_ONLY) },
        "lora config" to { w.applyLoRaConfig(MeshRegion.US, MeshChannelPreset.MEDIUM_FAST) },
        "lora preset only" to { w.applyLoRaConfig(MeshRegion.UNSET, MeshChannelPreset.MEDIUM_FAST) },
        "owner" to { w.applyOwner("New Long", "NEW") },
        "push, role changed" to { w.pushDeviceConfig(matchingDraft.copy(role = MeshRole.ROUTER)) },
        "push, nothing changed" to { w.pushDeviceConfig(matchingDraft) },
    )

    @Test fun `with no cached settings no write is sent and the result says the settings are not loaded`() = runBlocking {
        val link = Link()
        for ((name, write) in everyWrite(writer(RadioSettingsCache(), link))) {
            assertEquals(name, AdminWriteResult.Refused(RefusalReason.NOT_LOADED), write())
        }
        assertTrue("nothing may be sent: ${link.frames.size} frames went out", link.frames.isEmpty())
    }

    @Test fun `the refusal text is the one the operator reads`() {
        assertEquals(
            "Radio settings are not loaded yet. Reconnect and try again.",
            AdminWriteResult.Refused(RefusalReason.NOT_LOADED).describe("done"),
        )
    }

    private suspend fun assertRefusedNotLoaded(name: String, cache: RadioSettingsCache, write: suspend (MeshSettingsWriter) -> AdminWriteResult) {
        val link = Link()
        assertEquals(name, AdminWriteResult.Refused(RefusalReason.NOT_LOADED), write(writer(cache, link)))
        assertTrue("$name: nothing may be sent", link.frames.isEmpty())
    }

    @Test fun `each write refuses when the one entry it needs is missing, even if the others are there`() = runBlocking {
        assertRefusedNotLoaded("rebroadcast needs the device config", loadedCache(device = null)) {
            it.applyRebroadcastMode(RebroadcastMode.KNOWN_ONLY)
        }
        assertRefusedNotLoaded("lora needs the lora config", loadedCache(lora = null)) {
            it.applyLoRaConfig(MeshRegion.US, MeshChannelPreset.SHORT_FAST)
        }
        assertRefusedNotLoaded("owner needs the owner", loadedCache(owner = null)) {
            it.applyOwner("New Long", "NEW")
        }
    }

    @Test fun `a push that has to change a setting whose entry is missing sends nothing at all`() = runBlocking {
        // The role differs and the device config is there; the preset differs and the lora config is not.
        val link = Link()
        val w = writer(loadedCache(lora = null), link)
        val result = w.pushDeviceConfig(matchingDraft.copy(role = MeshRole.ROUTER, channelPreset = MeshChannelPreset.LONG_SLOW))
        assertEquals(AdminWriteResult.Refused(RefusalReason.NOT_LOADED), result)
        assertTrue("no begin, no partial write: ${link.frames.size} frames went out", link.frames.isEmpty())
    }

    @Test fun `a push with the channel missing is refused as well`() = runBlocking {
        val link = Link()
        val result = writer(loadedCache(channel0 = null), link).pushDeviceConfig(matchingDraft)
        assertEquals(AdminWriteResult.Refused(RefusalReason.NOT_LOADED), result)
        assertTrue(link.frames.isEmpty())
    }

    @Test fun `a setting too big to re-send in one admin message is refused, not trimmed`() = runBlocking {
        // The radio drops an AdminMessage longer than 233 bytes, and cutting fields off it would reset them.
        val link = Link()
        val huge = ProtoMsg().varint(1, 1).bytes(99, ByteArray(240) { 1 }).build()
        val oversize = loadedCache(device = huge, lora = huge, owner = huge)
        for ((name, write) in everyWrite(writer(oversize, link))) {
            assertEquals(name, AdminWriteResult.Refused(RefusalReason.UNREADABLE), write())
        }
        assertTrue(link.frames.isEmpty())
    }

    @Test fun `with no radio to address nothing is sent`() = runBlocking {
        val link = Link()
        for ((name, write) in everyWrite(writer(loadedCache(), link, destination = null))) {
            assertEquals(name, AdminWriteResult.Refused(RefusalReason.NO_RADIO), write())
        }
        assertEquals(
            AdminWriteResult.Refused(RefusalReason.NO_RADIO),
            writer(loadedCache(), link, destination = null).applyChannel(MeshChannel(name = "Shared", psk = keyBytes()), 1),
        )
        assertTrue(link.frames.isEmpty())
    }

    @Test fun `a refusal never touches what the cache holds`() = runBlocking {
        val cache = loadedCache(lora = null)
        val before = cache.get(Key.Config(1))
        writer(cache, Link()).pushDeviceConfig(matchingDraft.copy(role = MeshRole.ROUTER, channelPreset = MeshChannelPreset.LONG_SLOW))
        assertTrue("device config still as reported", before!!.contentEquals(cache.get(Key.Config(1))))
    }

    // endregion

    // region one write -------------------------------------------------------

    @Test fun `a preset change sends the radio's own lora config with only the preset different`() = runBlocking {
        val link = Link()
        val result = writer(loadedCache(lora = loraConfig(preset = 6, region = 1)), link)
            .applyLoRaConfig(MeshRegion.UNSET, MeshChannelPreset.MEDIUM_FAST)

        assertEquals(AdminWriteResult.Sent(1), result)
        assertEquals("one frame", 1, link.frames.size)
        val (variant, message) = decode(link.frames.single()).setConfig()
        assertEquals(6, variant)
        assertTrue("region, hop limit, transmit switch all carried over", loraConfig(preset = 4, region = 1).contentEquals(message))
    }

    @Test fun `a rebroadcast change sends the radio's own device config with only the mode different`() = runBlocking {
        val link = Link()
        assertEquals(AdminWriteResult.Sent(1), writer(loadedCache(), link).applyRebroadcastMode(RebroadcastMode.KNOWN_ONLY))
        val (variant, message) = decode(link.frames.single()).setConfig()
        assertEquals(1, variant)
        assertTrue(deviceConfig(role = 7, rebroadcast = 3).contentEquals(message))
    }

    @Test fun `an owner rename keeps the licensed flag on the way to the radio`() = runBlocking {
        val link = Link()
        val w = writer(loadedCache(owner = userMessage(licensed = true)), link)
        assertEquals(AdminWriteResult.Sent(1), w.applyOwner("New Long", "NEW"))
        val user = fields(decode(link.frames.single()).setOwner())
        assertEquals(1uL, user.single(6).varint)
        assertEquals("New Long", user.single(2).bytes.toString(Charsets.UTF_8))
    }

    @Test fun `an owner write with both names blank has nothing to say`() = runBlocking {
        val link = Link()
        assertEquals(AdminWriteResult.NothingToChange, writer(loadedCache(), link).applyOwner("", "  "))
        assertTrue(link.frames.isEmpty())
    }

    @Test fun `a second edit builds on the first`() = runBlocking {
        val link = Link()
        val w = writer(loadedCache(lora = loraConfig(preset = 6, region = 1)), link)

        // First the region, then the preset, with no download in between.
        assertEquals(AdminWriteResult.Sent(1), w.applyLoRaConfig(MeshRegion.EU_868, MeshChannelPreset.SHORT_FAST))
        assertEquals(AdminWriteResult.Sent(1), w.applyLoRaConfig(MeshRegion.UNSET, MeshChannelPreset.MEDIUM_FAST))

        val second = decode(link.frames[1]).setConfig().second
        assertTrue(
            "the second write still has the region the first one set",
            loraConfig(preset = 4, region = 3).contentEquals(second),
        )
    }

    @Test fun `a write remembers what it sent, so a rebroadcast change keeps an earlier role change`() = runBlocking {
        val link = Link()
        val cache = loadedCache()
        val w = writer(cache, link)
        // A push changes the role, then a separate rebroadcast write follows.
        assertEquals(AdminWriteResult.Sent(1), w.pushDeviceConfig(matchingDraft.copy(role = MeshRole.ROUTER)))
        assertEquals(AdminWriteResult.Sent(1), w.applyRebroadcastMode(RebroadcastMode.NONE))

        val last = decode(link.frames.last()).setConfig().second
        assertTrue(deviceConfig(role = 2, rebroadcast = 4).contentEquals(last))
    }

    @Test fun `a failed send leaves the cache as it was and reports the link failure`() = runBlocking {
        val link = Link(failFrom = 1)
        val cache = loadedCache()
        val before = cache.get(Key.Config(6))
        val result = writer(cache, link).applyLoRaConfig(MeshRegion.UNSET, MeshChannelPreset.MEDIUM_FAST)
        assertEquals(AdminWriteResult.LinkFailed(sent = 0, total = 1), result)
        assertTrue(before!!.contentEquals(cache.get(Key.Config(6))))
    }

    @Test fun `an imported channel replaces the slot, and a rename afterwards starts from the imported key`() = runBlocking {
        val link = Link()
        val oldKey = keyBytes(0x10)
        val newKey = keyBytes(0x90)
        val cache = loadedCache(channel0 = channelMessage(name = "Alpha", key = oldKey))
        val w = writer(cache, link)

        assertEquals(AdminWriteResult.Sent(1), w.applyChannel(MeshChannel(name = "Shared", psk = newKey), index = 0))
        // The rename the operator makes next must not put the old key back.
        assertEquals(AdminWriteResult.Sent(1), w.pushDeviceConfig(matchingDraft.copy(channelName = "Bravo")))

        // Frames: the import, then begin, the rename, commit.
        val settings = fields(fields(decode(link.frames[link.frames.size - 2]).setChannel()).single(2).bytes)
        assertTrue("the imported key survives the rename", newKey.contentEquals(settings.single(2).bytes))
        assertEquals("Bravo", settings.single(3).bytes.toString(Charsets.UTF_8))
    }

    // endregion

    // region pushDeviceConfig --------------------------------------------------

    @Test fun `a draft that matches the radio sends nothing`() = runBlocking {
        val link = Link()
        assertEquals(AdminWriteResult.NothingToChange, writer(loadedCache(), link).pushDeviceConfig(matchingDraft))
        assertTrue("no begin, no commit: ${link.frames.size} frames went out", link.frames.isEmpty())
    }

    @Test fun `one changed setting sends begin, that one write, commit and nothing else`() = runBlocking {
        val link = Link()
        val result = writer(loadedCache(), link).pushDeviceConfig(matchingDraft.copy(positionBroadcastSecs = 300))

        assertEquals(AdminWriteResult.Sent(1), result)
        assertEquals(3, link.frames.size)
        assertEquals("begin_edit_settings", listOf(64), adminFieldNumbers(link.frames[0]))
        assertEquals("set_config", listOf(34), adminFieldNumbers(link.frames[1]))
        assertEquals("commit_edit_settings", listOf(65), adminFieldNumbers(link.frames[2]))

        val (variant, message) = decode(link.frames[1]).setConfig()
        assertEquals("position", 2, variant)
        assertTrue("only the interval differs", positionConfig(secs = 300).contentEquals(message))
        link.frames.forEach { assertEquals("every frame is addressed to the radio", node, decode(it).to) }
    }

    @Test fun `the writes of a batch go out between begin and commit in a fixed order`() = runBlocking {
        val link = Link()
        val draft = MeshDeviceConfig(
            longName = "New Long", shortName = "NEW", role = MeshRole.ROUTER,
            positionBroadcastSecs = 60, channelName = "Bravo", channelPreset = MeshChannelPreset.MEDIUM_FAST,
        )
        assertEquals(AdminWriteResult.Sent(5), writer(loadedCache(), link).pushDeviceConfig(draft))

        assertEquals(
            listOf(64, 32, 34, 34, 33, 34, 65),
            link.frames.map { adminFieldNumbers(it).single() },
        )
        val variants = link.frames.filter { adminFieldNumbers(it).single() == 34 }.map { decode(it).setConfig().first }
        assertEquals("role, position, then preset", listOf(1, 2, 6), variants)
    }

    @Test fun `each write in a batch is the radio's own message with one field changed`() = runBlocking {
        val link = Link()
        val draft = matchingDraft.copy(role = MeshRole.ROUTER, channelName = "Bravo", channelPreset = MeshChannelPreset.MEDIUM_FAST)
        writer(loadedCache(), link).pushDeviceConfig(draft)

        val byKind = link.frames.drop(1).dropLast(1)
        assertTrue(deviceConfig(role = 2, rebroadcast = 2).contentEquals(decode(byKind[0]).setConfig().second))
        assertTrue(channelMessage(name = "Bravo").contentEquals(decode(byKind[1]).setChannel()))
        assertTrue(loraConfig(preset = 4, region = 1).contentEquals(decode(byKind[2]).setConfig().second))
    }

    @Test fun `a push does not rewrite a setting that already matches`() = runBlocking {
        val link = Link()
        writer(loadedCache(), link).pushDeviceConfig(matchingDraft.copy(channelName = "Bravo"))
        // Only the channel went out: no owner, role, position or lora write.
        assertEquals(listOf(64, 33, 65), link.frames.map { adminFieldNumbers(it).single() })
    }

    @Test fun `a role or preset the app has no name for is left alone`() = runBlocking {
        // Role 11 and modem preset 7 are real firmware values this app does not list. The draft cannot hold
        // them, so comparing would "change" them on every push and overwrite what the radio is set to.
        val link = Link()
        val cache = loadedCache(device = deviceConfig(role = 11), lora = loraConfig(preset = 7))
        val result = writer(cache, link).pushDeviceConfig(matchingDraft.copy(role = MeshRole.TAK, channelPreset = MeshChannelPreset.LONG_FAST))
        assertEquals(AdminWriteResult.NothingToChange, result)
        assertTrue(link.frames.isEmpty())
    }

    @Test fun `names are compared the way they would be sent, so a name that gets cut does not look changed forever`() = runBlocking {
        val link = Link()
        val long = "x".repeat(45)
        val cache = loadedCache(owner = userMessage(longName = "x".repeat(39), shortName = "TNO"), channel0 = channelMessage(name = "abcdefghijk"))
        val result = writer(cache, link).pushDeviceConfig(matchingDraft.copy(longName = long, channelName = "abcdefghijkl"))
        assertEquals(AdminWriteResult.NothingToChange, result)
        assertTrue(link.frames.isEmpty())
    }

    @Test fun `blank names in the draft leave the radio's names alone`() = runBlocking {
        val link = Link()
        assertEquals(
            AdminWriteResult.NothingToChange,
            writer(loadedCache(), link).pushDeviceConfig(matchingDraft.copy(longName = "", shortName = " ")),
        )
        assertTrue(link.frames.isEmpty())
    }

    @Test fun `a changed owner name goes out and keeps the licensed flag`() = runBlocking {
        val link = Link()
        val w = writer(loadedCache(owner = userMessage(licensed = true)), link)
        assertEquals(AdminWriteResult.Sent(1), w.pushDeviceConfig(matchingDraft.copy(longName = "New Long")))
        val user = fields(decode(link.frames[1]).setOwner())
        assertEquals(1uL, user.single(6).varint)
        assertTrue(user.has(9))
    }

    @Test fun `a failed begin sends nothing else`() = runBlocking {
        val link = Link(failFrom = 1)
        val result = writer(loadedCache(), link).pushDeviceConfig(matchingDraft.copy(role = MeshRole.ROUTER, positionBroadcastSecs = 60))
        assertEquals(AdminWriteResult.LinkFailed(sent = 0, total = 2), result)
        assertTrue(link.frames.isEmpty())
    }

    @Test fun `when a write fails the transaction is still closed, and the result says how far it got`() = runBlocking {
        // Frames: begin (ok), role write (ok), position write (fails), then commit is attempted.
        class FailsOnce {
            val frames = mutableListOf<ByteArray>()
            val attempted = mutableListOf<Int>()
            suspend fun send(frame: ByteArray): Boolean {
                val kind = decode(frame).admin.single().number
                attempted += kind
                if (kind == 34 && attempted.count { it == 34 } == 2) return false
                frames += frame
                return true
            }
        }
        val link = FailsOnce()
        val w = MeshSettingsWriter(loadedCache(), { node }, { link.send(it) }, frameSpacingMs = 0)
        val result = w.pushDeviceConfig(matchingDraft.copy(role = MeshRole.ROUTER, positionBroadcastSecs = 60, channelName = "Bravo"))

        assertEquals(AdminWriteResult.LinkFailed(sent = 1, total = 3), result)
        assertEquals("begin, role, failed position, commit; the channel is not tried", listOf(64, 34, 34, 65), link.attempted)
        assertTrue("the result tells the screen something may have been written", result.reachedRadio)
    }

    @Test fun `a failed commit is reported even though every write went out`() = runBlocking {
        val link = Link(failFrom = 3) // begin, one write, then the commit fails
        val result = writer(loadedCache(), link).pushDeviceConfig(matchingDraft.copy(positionBroadcastSecs = 60))
        assertEquals(AdminWriteResult.LinkFailed(sent = 1, total = 1), result)
        assertTrue(result.describe("ok").contains("confirmed"))
    }

    @Test fun `a batch leaves the cache holding what it wrote, so the next edit builds on it`() = runBlocking {
        val link = Link()
        val cache = loadedCache()
        val w = writer(cache, link)
        w.pushDeviceConfig(matchingDraft.copy(positionBroadcastSecs = 300))

        assertTrue(positionConfig(secs = 300).contentEquals(cache.get(Key.Config(2))))
        // The same draft again is now a no-op: nothing is re-sent.
        val before = link.frames.size
        assertEquals(AdminWriteResult.NothingToChange, w.pushDeviceConfig(matchingDraft.copy(positionBroadcastSecs = 300)))
        assertEquals(before, link.frames.size)
    }

    // endregion

    // region pacing and cancellation ---------------------------------------------

    @Test fun `the frames of a batch go out a gap apart, so the radio can take each one`() = runTest {
        // The firmware keeps four inbound packets and drops the oldest when a fifth arrives. Virtual time here.
        val times = mutableListOf<Long>()
        val w = MeshSettingsWriter(loadedCache(), { node }, { times += currentTime; true }, frameSpacingMs = 100)

        val result = w.pushDeviceConfig(matchingDraft.copy(role = MeshRole.ROUTER, positionBroadcastSecs = 60))

        assertEquals(AdminWriteResult.Sent(2), result)
        assertEquals("begin, two writes, commit", listOf(0L, 100L, 200L, 300L), times)
    }

    @Test fun `a single write is not delayed`() = runTest {
        val times = mutableListOf<Long>()
        val w = MeshSettingsWriter(loadedCache(), { node }, { times += currentTime; true }, frameSpacingMs = 100)
        w.applyLoRaConfig(MeshRegion.UNSET, MeshChannelPreset.MEDIUM_FAST)
        assertEquals(listOf(0L), times)
    }

    @Test fun `two writes started together run one after the other, so the second builds on the first`() = runTest {
        // Each write reads the cache, sends, then stores what it sent. Run side by side, both would start from
        // the same message and the second would undo the first.
        val frames = mutableListOf<ByteArray>()
        val w = MeshSettingsWriter(
            loadedCache(lora = loraConfig(preset = 6, region = 1)), { node },
            { frames += it; delay(50); true },
            frameSpacingMs = 0,
        )

        val first = async { w.applyLoRaConfig(MeshRegion.EU_868, MeshChannelPreset.SHORT_FAST) }
        val second = async { w.applyLoRaConfig(MeshRegion.UNSET, MeshChannelPreset.MEDIUM_FAST) }

        assertEquals(AdminWriteResult.Sent(1), first.await())
        assertEquals(AdminWriteResult.Sent(1), second.await())
        assertTrue(
            "the second write still carries the region the first one set",
            loraConfig(preset = 4, region = 3).contentEquals(decode(frames[1]).setConfig().second),
        )
    }

    @Test fun `a push that has begun reaches its commit even when the caller goes away`() = runTest {
        // A screen left mid-push cancels its scope. A transaction left open would swallow the next edit from any client.
        val frames = mutableListOf<ByteArray>()
        val begun = CompletableDeferred<Unit>()
        val w = MeshSettingsWriter(
            loadedCache(), { node },
            {
                frames += it
                if (frames.size == 1) begun.complete(Unit)
                true
            },
            frameSpacingMs = 100,
        )

        val push = launch { w.pushDeviceConfig(matchingDraft.copy(role = MeshRole.ROUTER, positionBroadcastSecs = 60)) }
        begun.await()
        push.cancel()
        push.join()

        assertEquals("begin, both writes, commit", listOf(64, 34, 34, 65), frames.map { adminFieldNumbers(it).single() })
    }

    // endregion

    // region result text ---------------------------------------------------------

    @Test fun `every outcome has text the operator can act on`() {
        assertEquals("done", AdminWriteResult.Sent(2).describe("done"))
        assertTrue(AdminWriteResult.NothingToChange.describe("done").startsWith("Nothing to change"))
        assertEquals("No radio connected.", AdminWriteResult.Refused(RefusalReason.NO_RADIO).describe("done"))
        assertTrue(AdminWriteResult.Refused(RefusalReason.UNREADABLE).describe("done").contains("Reconnect"))
        assertTrue(AdminWriteResult.LinkFailed(0, 1).describe("done").contains("did not reach"))
        assertTrue(AdminWriteResult.LinkFailed(1, 3).describe("done").contains("1 of 3"))
        assertFalse(AdminWriteResult.Refused(RefusalReason.NOT_LOADED).reachedRadio)
        assertFalse(AdminWriteResult.NothingToChange.reachedRadio)
        assertFalse(AdminWriteResult.LinkFailed(0, 2).reachedRadio)
        assertTrue(AdminWriteResult.LinkFailed(1, 2).reachedRadio)
        assertTrue(AdminWriteResult.Sent(1).reachedRadio)
    }

    // endregion
}
