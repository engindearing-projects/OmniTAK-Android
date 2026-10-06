package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.AdminSetting
import soy.engindearing.omnitak.mobile.data.AdminTestFrames
import soy.engindearing.omnitak.mobile.data.AdminWriteResult
import soy.engindearing.omnitak.mobile.data.DeviceEdits
import soy.engindearing.omnitak.mobile.data.DeviceSettingsState
import soy.engindearing.omnitak.mobile.data.FakeRadio
import soy.engindearing.omnitak.mobile.data.MeshChannelPreset
import soy.engindearing.omnitak.mobile.data.MeshDeviceConfig
import soy.engindearing.omnitak.mobile.data.MeshRole
import soy.engindearing.omnitak.mobile.data.PositionFixtures
import soy.engindearing.omnitak.mobile.data.PositionFloor
import soy.engindearing.omnitak.mobile.data.ProtoMsg
import soy.engindearing.omnitak.mobile.data.RefusalReason

/**
 * The position floor, as the app lives it: a radio on its default channel puts a short position interval back to
 * one hour when it restarts (firmware 2.7.26, [PositionFloor]), and the app says so before the push and after the
 * restart.
 *
 * The app is wired the way [soy.engindearing.omnitak.mobile.OmniTAKApp] wires it. Frames go in through
 * [MeshtasticManager.dispatchFrame], the settings state the screen reads is fed by the manager's reports, and the
 * link is a [FakeRadio] that answers reads, replaces whole messages on writes and, when it restarts, does what the
 * firmware does to the position interval. A restart drops the link and the app connects again, to the same radio
 * or to another one. What the screen would show is read from the same places the screen reads it: the state's
 * `positionIntervalHint` with the manager's `positionFacts`, and the manager's `restartNote`.
 *
 * Node numbers, names and keys are made up.
 */
class MeshtasticManagerPositionFloorTest {

    private val me = 0x0A0B0C0D
    private val other = 0x01020304

    private val reasonOneHour = PositionFloor.reason(PositionFloor.DEFAULT_FLOOR_SECS)
    private val reasonTwelveHours = PositionFloor.reason(PositionFloor.ROUTER_FLOOR_SECS)

    /** The stock radio with its primary channel replaced, and a device [role] and position [intervalSecs] if given. */
    private fun stockWith(channel: ByteArray, role: Int? = null, intervalSecs: Int? = null): FakeRadio = FakeRadio.stock().also { radio ->
        radio.channels[0] = channel
        if (role != null) radio.config[1] = ProtoMsg().varint(1, role).varint(7, 7200).build()
        if (intervalSecs != null) radio.config[2] = ProtoMsg().varint(1, intervalSecs).varint(7, 811).varint(11, 300).varint(13, 1).build()
    }

    private fun interval(secs: Int) = DeviceEdits(positionBroadcastSecs = secs)

    // region the case from the report ---------------------------------------------------------------

    @Test fun `on the default channel 120 s is announced before the push and explained after the restart`() {
        val app = RadioApp(FakeRadio.stock()).connect()
        assertEquals("the stock radio holds one hour", 3_600, app.held)
        assertNull("one hour is the floor: nothing to say", app.hint())
        assertEquals("120 s is under it: say so before the push", reasonOneHour, app.hint { copy(positionBroadcastSecs = 120) })

        val result = app.push(app.edits { copy(positionBroadcastSecs = 120) })

        assertEquals(AdminWriteResult.Sent(listOf(AdminSetting.POSITION_INTERVAL)), result)
        assertEquals("the radio holds what it was sent until it restarts", 120, app.held)
        assertEquals("and reports it to the read-back", 120, app.state.radio?.positionBroadcastSecs)
        assertNull("nothing to report yet: the radio took what it was sent", app.mgr.restartNote.value)
        assertNull(app.mgr.settingsNotice.value)

        app.restart()
        assertEquals("the firmware put it back to the floor", 3_600, app.held)
        assertNull("the link is down: nothing is judged yet", app.mgr.restartNote.value)

        app.connect()

        assertEquals(3_600, app.state.radio?.positionBroadcastSecs)
        assertEquals(
            "The radio reports 3600 s for the position interval. 120 s was sent. $reasonOneHour",
            app.mgr.restartNote.value,
        )
        assertNull("this is not the managed-radio note", app.mgr.settingsNotice.value)
    }

    @Test fun `the note stays through a link drop and a reconnect of the same radio, until the operator edits or pushes`() {
        val app = RadioApp(FakeRadio.stock()).connect()
        app.push(interval(120))
        app.restart().connect()
        val note = app.mgr.restartNote.value
        assertNotNull(note)

        app.mgr.onLinkState(ConnectionState.Disconnected, wasConnected = true)
        assertEquals("a link drop does not take it away", note, app.mgr.restartNote.value)
        app.connect()
        assertEquals("nor does another download of the same radio", note, app.mgr.restartNote.value)

        app.mgr.clearLastPushResult() // what the screen does on the next edit or push
        assertNull(app.mgr.restartNote.value)
        app.connect()
        assertNull("and it is not rebuilt for a push that is forgotten", app.mgr.restartNote.value)
    }

    @Test fun `a new push replaces the note of the one before`() {
        val app = RadioApp(FakeRadio.stock()).connect()
        app.push(interval(120))
        app.restart().connect()
        assertTrue("120 s was sent" in app.mgr.restartNote.value!!)

        // Not through the screen's own clear: the manager starts afresh by itself when it is asked to push.
        runBlocking { app.mgr.pushDeviceConfig(interval(90)) }
        assertNull("pushing clears the old note", app.mgr.restartNote.value)
        app.restart().connect()

        val note = app.mgr.restartNote.value!!
        assertTrue(note, "90 s was sent" in note)
        assertTrue(note, "120 s" !in note)
    }

    // endregion

    // region no hint, no note ----------------------------------------------------------------------

    @Test fun `a primary channel with a 32 byte key gets no hint, keeps the value and gets no note`() {
        val app = RadioApp(stockWith(PositionFixtures.channel(key = PositionFixtures.privateKey()))).connect()
        assertNull(app.hint { copy(positionBroadcastSecs = 120) })

        app.push(interval(120))
        app.restart()
        assertEquals("the radio keeps it", 120, app.held)
        app.connect()

        assertEquals(120, app.state.radio?.positionBroadcastSecs)
        assertNull(app.mgr.restartNote.value)
    }

    @Test fun `a changed name on the default key is not the default channel, so the value is kept and nothing is said`() {
        val app = RadioApp(stockWith(FakeRadio.defaultChannel(name = "Alpha"))).connect()
        assertNull(app.hint { copy(positionBroadcastSecs = 120) })

        app.push(interval(120))
        app.restart()
        assertEquals("the firmware does not call this the default channel", 120, app.held)
        app.connect()

        assertNull(app.mgr.restartNote.value)
    }

    @Test fun `the preset's own name written out on the default key is the default channel`() {
        val app = RadioApp(stockWith(FakeRadio.defaultChannel(name = "LongFast"))).connect()
        assertEquals(reasonOneHour, app.hint { copy(positionBroadcastSecs = 120) })

        app.push(interval(120))
        app.restart().connect()

        assertEquals(
            "The radio reports 3600 s for the position interval. 120 s was sent. $reasonOneHour",
            app.mgr.restartNote.value,
        )
    }

    @Test fun `position precision 0 on every channel gets no hint, keeps the value and gets no note`() {
        val app = RadioApp(stockWith(FakeRadio.defaultChannel(precision = 0))).connect()
        assertNull(app.hint { copy(positionBroadcastSecs = 120) })

        app.push(interval(120))
        app.restart()
        assertEquals("no channel sends positions, so nothing is raised", 120, app.held)
        app.connect()

        assertNull(app.mgr.restartNote.value)
    }

    @Test fun `an interval of 0 is left alone by the firmware, so there is no hint and no note`() {
        val app = RadioApp(FakeRadio.stock()).connect()
        assertNull(app.hint { copy(positionBroadcastSecs = 0) })

        app.push(interval(0))
        app.restart()
        assertEquals(0, app.held)
        app.connect()

        assertEquals(0, app.state.radio?.positionBroadcastSecs)
        assertNull(app.mgr.restartNote.value)
    }

    @Test fun `an interval at the floor or over it is kept and says nothing`() {
        val app = RadioApp(FakeRadio.stock()).connect()
        assertNull(app.hint { copy(positionBroadcastSecs = 7_200) })

        app.push(interval(7_200))
        app.restart().connect()

        assertEquals(7_200, app.state.radio?.positionBroadcastSecs)
        assertNull(app.mgr.restartNote.value)
    }

    // endregion

    // region a router -------------------------------------------------------------------------------------

    @Test fun `a router's floor is twelve hours, in the hint and in the note`() {
        for (role in listOf(2, 11)) {
            // A router on the default channel holds twelve hours after any restart.
            val app = RadioApp(stockWith(FakeRadio.defaultChannel(), role = role, intervalSecs = 43_200)).connect()
            assertEquals("role $role", reasonTwelveHours, app.hint { copy(positionBroadcastSecs = 3_600) })
            assertNull("role $role: twelve hours is not under the floor", app.hint { copy(positionBroadcastSecs = 43_200) })

            app.push(interval(3_600))
            app.restart()
            assertEquals("role $role", 43_200, app.held)
            app.connect()

            assertEquals(
                "role $role",
                "The radio reports 43200 s for the position interval. 3600 s was sent. $reasonTwelveHours",
                app.mgr.restartNote.value,
            )
        }
    }

    @Test fun `a role that is not a router keeps the one hour floor`() {
        // CLIENT_BASE (12), TAK (7) and the deprecated ROUTER_CLIENT (3) are not routers for the firmware's floor.
        for (role in listOf(7, 12, 3)) {
            val app = RadioApp(stockWith(FakeRadio.defaultChannel(), role = role)).connect()
            assertNull("role $role", app.hint { copy(positionBroadcastSecs = 3_600) })
            assertEquals("role $role", reasonOneHour, app.hint { copy(positionBroadcastSecs = 3_599) })
        }
    }

    @Test fun `choosing the router role in the same push is announced with the router floor`() {
        val app = RadioApp(FakeRadio.stock()).connect()
        assertEquals(reasonTwelveHours, app.hint { copy(role = MeshRole.ROUTER, positionBroadcastSecs = 3_600) })
    }

    // endregion

    // region a different radio ---------------------------------------------------------------------------

    @Test fun `a different radio after the push gets no note, and the first radio is not judged when it comes back`() {
        val app = RadioApp(FakeRadio.stock()).connect()
        app.push(interval(120))
        app.restart()

        // Another radio, which holds one hour: the same numbers that the first one will show.
        app.connect(to = FakeRadio.stock(), as_ = other)
        assertNull("the push went to another radio", app.mgr.restartNote.value)
        assertEquals(3_600, app.state.radio?.positionBroadcastSecs)

        app.mgr.onLinkState(ConnectionState.Disconnected, wasConnected = true)
        app.connect(to = FakeRadio.stock(), as_ = me)
        assertNull("it was forgotten when the other radio connected", app.mgr.restartNote.value)
    }

    @Test fun `a note is forgotten when a different radio connects`() {
        val app = RadioApp(FakeRadio.stock()).connect()
        app.push(interval(120))
        app.restart().connect()
        assertNotNull(app.mgr.restartNote.value)

        app.mgr.onLinkState(ConnectionState.Disconnected, wasConnected = true)
        app.connect(to = FakeRadio.stock(), as_ = other)

        assertNull(app.mgr.restartNote.value)
    }

    // endregion

    // region what else is compared -------------------------------------------------------------------------

    @Test fun `every setting the radio does not keep is named with both values, and the reason comes once`() {
        val app = RadioApp(FakeRadio.stock()).connect()
        app.push(app.edits { copy(role = MeshRole.TAK, positionBroadcastSecs = 120, channelPreset = MeshChannelPreset.SHORT_FAST) })
        app.restart()
        // The radio goes back to CLIENT as well, whatever the reason.
        app.radio.config[1] = ProtoMsg().varint(7, 7200).build()
        app.connect()

        val note = app.mgr.restartNote.value!!
        assertEquals(
            "The radio reports Client for the role. TAK was sent. " +
                "The radio reports 3600 s for the position interval. 120 s was sent. " +
                reasonOneHour,
            note,
        )
    }

    @Test fun `a push that stays on the radio says nothing about the settings it kept`() {
        val app = RadioApp(FakeRadio.stock()).connect()
        app.push(app.edits { copy(role = MeshRole.TAK, channelName = "Alpha") })
        app.restart().connect()

        assertEquals(MeshRole.TAK, app.state.radio?.role)
        assertNull(app.mgr.restartNote.value)
    }

    @Test fun `the values are compared as they were sent, the interval kept in range`() {
        val app = RadioApp(FakeRadio.stock()).connect()
        app.push(DeviceEdits(positionBroadcastSecs = 100_000))
        app.restart()
        app.radio.config[2] = ProtoMsg().varint(1, 3_600).varint(7, 811).varint(13, 1).build()
        app.connect()

        assertEquals("The radio reports 3600 s for the position interval. 86400 s was sent.", app.mgr.restartNote.value)
    }

    @Test fun `a download that leaves out a setting does not judge it by what an earlier download reported`() {
        val app = RadioApp(FakeRadio.stock()).connect() // the download reported 3600 s
        runBlocking { app.mgr.pushDeviceConfig(interval(120)) } // no read-back: what the app last heard is still 3600 s
        // A second download on the same link, with no position config in it.
        val position = AdminTestFrames.configFrame(2, app.radio.config.getValue(2))
        app.radio.download(me).filterNot { it.contentEquals(position) }.forEach { app.mgr.dispatchFrame(it) }

        assertNull(app.mgr.restartNote.value)
    }

    @Test fun `a push that did not reach the radio leaves nothing to judge`() {
        val app = RadioApp(FakeRadio.stock()).connect()
        app.linkOpen = false
        val result = app.push(interval(120))
        assertEquals(AdminWriteResult.Refused(RefusalReason.NO_RADIO), result)
        app.linkOpen = true
        app.restart().connect()

        assertNull(app.mgr.restartNote.value)
    }

    // endregion

    // region the facts follow what the radio reported ------------------------------------------------------

    @Test fun `the facts are what the download reported, and the link dropping empties them`() {
        val app = RadioApp(FakeRadio.stock())
        assertNull("nothing reported yet", app.mgr.positionFacts.value.onDefaultChannel())
        assertNull(app.hint { copy(positionBroadcastSecs = 120) })

        app.connect()
        assertEquals(true, app.mgr.positionFacts.value.onDefaultChannel())
        assertEquals(0, app.mgr.positionFacts.value.role)

        app.mgr.onLinkState(ConnectionState.Disconnected, wasConnected = true)
        assertNull("what the radio reported is gone with the link", app.mgr.positionFacts.value.onDefaultChannel())
    }

    @Test fun `nothing is decided in the middle of a download`() {
        val app = RadioApp(FakeRadio.stock())
        val frames = app.radio.download(me)
        // my_info, node info, then only channel 0: the LoRa config has not come yet.
        frames.take(3).forEach { app.mgr.dispatchFrame(it) }
        assertNull(app.mgr.positionFacts.value.onDefaultChannel())
        // The rest of the channels and the configs.
        frames.drop(3).forEach { app.mgr.dispatchFrame(it) }
        assertEquals(true, app.mgr.positionFacts.value.onDefaultChannel())
    }

    @Test fun `a channel the app writes is dropped from the facts until the radio reports it again`() {
        val app = RadioApp(FakeRadio.stock()).connect()
        assertEquals(true, app.mgr.positionFacts.value.onDefaultChannel())

        runBlocking { app.mgr.pushDeviceConfig(app.edits { copy(channelName = "Alpha") }) }
        // The writer dropped channel 0 from the cache when it sent the rename: what the radio holds now is not known.
        assertNull(app.mgr.positionFacts.value.onDefaultChannel())

        runBlocking { app.mgr.requestDeviceConfig() }
        assertEquals("the radio's own answer: the channel has a name now", false, app.mgr.positionFacts.value.onDefaultChannel())
    }

    // endregion
}
