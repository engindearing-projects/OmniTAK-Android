package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which settings the operator changed, and how a report from the radio moves
 * the draft ([DeviceSettingsState]).
 *
 * The bug this pins: a radio with role CLIENT (the proto3 default, so absent
 * on the wire) and an unnamed primary channel was decoded as "no role, no
 * name", the draft kept a fresh install's TAK and "OmniTAK", and a push that
 * changed only the position interval wrote the role and renamed the primary
 * channel (which changes its frequency slot and takes the radio off the air).
 *
 * Names and numbers are made up.
 */
class DeviceSettingsStateTest {

    /** What a factory-fresh radio reports: role CLIENT, unnamed primary, interval 900, region US, preset LONG_FAST. */
    private val factoryReports = listOf(
        AdminResponse.Owner(longName = "Sim Radio One", shortName = "SR1"),
        AdminResponse.DeviceConfig(role = MeshRole.CLIENT, rebroadcastMode = RebroadcastMode.ALL),
        AdminResponse.PositionConfig(broadcastSecs = 900),
        AdminResponse.LoraConfig(preset = MeshChannelPreset.LONG_FAST, region = MeshRegion.US),
        AdminResponse.Channel(index = 0, name = "", role = 1),
    )

    private fun DeviceSettingsState.report(vararg reports: AdminResponse) = reports.fold(this) { s, r -> s.withReport(r) }

    private val freshInstall = DeviceSettingsState() // draft TAK / "OmniTAK" / "OTK" / 30 s / LONG_FAST, no radio

    private fun factory() = freshInstall.report(*factoryReports.toTypedArray())

    // region the reproduced bug ---------------------------------------------------

    @Test fun `a fresh install takes everything from a factory radio, and nothing is edited`() {
        val state = factory()

        assertEquals("Sim Radio One", state.draft.longName)
        assertEquals("SR1", state.draft.shortName)
        assertEquals("the draft's TAK is gone: the radio says CLIENT", MeshRole.CLIENT, state.draft.role)
        assertEquals(900, state.draft.positionBroadcastSecs)
        assertEquals("the leftover \"OmniTAK\" is gone: the primary channel has no name", "", state.draft.channelName)
        assertEquals(MeshChannelPreset.LONG_FAST, state.draft.channelPreset)
        assertTrue(state.edits().isEmpty)
    }

    @Test fun `changing only the interval edits only the interval`() {
        val state = factory()
        val edits = state.edits(state.draft.copy(positionBroadcastSecs = 300))

        assertEquals(DeviceEdits(positionBroadcastSecs = 300), edits)
        assertEquals(listOf(AdminSetting.POSITION_INTERVAL), edits.settings)
    }

    @Test fun `a factory radio with an unnamed primary never has its channel name edited by leftovers`() {
        // The draft shown on the screen is the radio's, so a push of it names nothing.
        val state = factory()
        assertNull(state.edits().channelName)
        assertNull(state.edits().role)
    }

    // endregion

    // region nothing is edited without a report ------------------------------------

    @Test fun `before any report nothing is edited, whatever the draft holds`() {
        val state = DeviceSettingsState(draft = MeshDeviceConfig(role = MeshRole.ROUTER, positionBroadcastSecs = 5, channelName = "x"))
        assertTrue(state.edits().isEmpty)
        assertTrue(state.edits(MeshDeviceConfig(role = MeshRole.REPEATER)).isEmpty)
    }

    @Test fun `a setting the radio has not reported yet is not edited`() {
        // Only the position interval has been reported. The other draft values are leftovers.
        val state = freshInstall.report(AdminResponse.PositionConfig(900))
        assertTrue("the leftovers are not edits", state.edits().isEmpty)
        assertEquals(900, state.draft.positionBroadcastSecs)
        assertEquals("not reported yet, so unchanged", MeshRole.TAK, state.draft.role)
    }

    // endregion

    // region another radio ------------------------------------------------------------

    @Test fun `a draft left by radio A does not carry over to radio B`() {
        val a = freshInstall.report(
            AdminResponse.Owner("Alpha Radio", "ALF"),
            AdminResponse.DeviceConfig(MeshRole.ROUTER, RebroadcastMode.ALL),
            AdminResponse.PositionConfig(60),
            AdminResponse.Channel(0, "AlphaNet", 1),
        )
        // The operator had an unsent edit on A, and the link then dropped.
        val withEdit = a.copy(draft = a.draft.copy(positionBroadcastSecs = 300))
        assertEquals(DeviceEdits(positionBroadcastSecs = 300), withEdit.edits())
        val down = withEdit.withLinkDown()
        assertNull(down.radio)
        assertTrue("nothing counts as edited with no radio", down.edits().isEmpty)

        val b = down.report(
            AdminResponse.Owner("Bravo Radio", "BRV"),
            AdminResponse.DeviceConfig(MeshRole.CLIENT, RebroadcastMode.ALL),
            AdminResponse.PositionConfig(900),
            AdminResponse.Channel(0, "", 1),
        )

        assertEquals("Bravo Radio", b.draft.longName)
        assertEquals(MeshRole.CLIENT, b.draft.role)
        assertEquals("B's interval, not A's pending 300", 900, b.draft.positionBroadcastSecs)
        assertEquals("B's unnamed primary, not A's name", "", b.draft.channelName)
        assertTrue(b.edits().isEmpty)
    }

    @Test fun `a draft setting radio B has not reported yet is still not edited`() {
        val a = freshInstall.report(AdminResponse.DeviceConfig(MeshRole.ROUTER, RebroadcastMode.ALL))
        val b = a.withLinkDown().report(AdminResponse.PositionConfig(900))
        assertEquals("A's role is still in the draft, waiting for B to say", MeshRole.ROUTER, b.draft.role)
        assertNull("and it is not an edit", b.edits().role)
    }

    // endregion

    // region the radio changed, the operator did not ------------------------------------

    @Test fun `a setting changed on the radio but not edited follows the radio and is not written`() {
        val before = factory()
        val after = before.report(AdminResponse.PositionConfig(120), AdminResponse.DeviceConfig(MeshRole.ROUTER, RebroadcastMode.ALL))

        assertEquals(120, after.draft.positionBroadcastSecs)
        assertEquals(MeshRole.ROUTER, after.draft.role)
        assertTrue("a value that merely differs from the old report is not an edit", after.edits().isEmpty)
    }

    @Test fun `an edit survives later reports`() {
        var state = factory()
        state = state.copy(draft = state.draft.copy(positionBroadcastSecs = 300))

        state = state.report(AdminResponse.PositionConfig(900))
        assertEquals("the report says what the radio still has", 300, state.draft.positionBroadcastSecs)
        assertEquals(DeviceEdits(positionBroadcastSecs = 300), state.edits())

        state = state.report(AdminResponse.PositionConfig(600)) // another client changed it meanwhile
        assertEquals(300, state.draft.positionBroadcastSecs)
        assertEquals(DeviceEdits(positionBroadcastSecs = 300), state.edits())

        state = state.report(AdminResponse.PositionConfig(300)) // the write landed
        assertTrue("the radio now has it: no longer an edit", state.edits().isEmpty)
        assertEquals(300, state.draft.positionBroadcastSecs)
    }

    @Test fun `the radio keeping its own value after a push leaves the edit pending`() {
        var state = factory()
        state = state.copy(draft = state.draft.copy(role = MeshRole.TAK))
        assertEquals(DeviceEdits(role = MeshRole.TAK), state.edits())

        state = state.report(AdminResponse.DeviceConfig(MeshRole.CLIENT, RebroadcastMode.ALL)) // it did not take
        assertEquals(DeviceEdits(role = MeshRole.TAK), state.edits())
    }

    // endregion

    // region an interval above the app's limit ---------------------------------------------

    @Test fun `an interval above the app's limit is shown as the radio has it and is not an edit`() {
        // Another client set the radio to three days. The app lets the operator type up to a day.
        val state = factory().report(AdminResponse.PositionConfig(broadcastSecs = 259_200))

        assertEquals("the screen shows the radio's real value", 259_200, state.draft.positionBroadcastSecs)
        assertTrue("nothing the operator did not change is edited", state.edits().isEmpty)
        assertNull(state.edits().positionBroadcastSecs)
    }

    @Test fun `a push of another setting leaves the interval of such a radio alone`() {
        val state = factory().report(AdminResponse.PositionConfig(broadcastSecs = 259_200))

        val edits = state.edits(state.draft.copy(role = MeshRole.CLIENT_MUTE))

        assertEquals(DeviceEdits(role = MeshRole.CLIENT_MUTE), edits)
        assertEquals(listOf(AdminSetting.ROLE), edits.settings)
    }

    @Test fun `an interval the operator changes is sent in range`() {
        val state = factory().report(AdminResponse.PositionConfig(broadcastSecs = 259_200))

        assertEquals(DeviceEdits(positionBroadcastSecs = 300), state.edits(state.draft.copy(positionBroadcastSecs = 300)))
        assertEquals("never past a day", DeviceEdits(positionBroadcastSecs = 86_400), state.edits(state.draft.copy(positionBroadcastSecs = 100_000)))
    }

    @Test fun `an out of range interval follows the radio while it is not edited`() {
        var state = factory().report(AdminResponse.PositionConfig(broadcastSecs = 259_200))
        state = state.report(AdminResponse.PositionConfig(broadcastSecs = 600_000)) // changed elsewhere again
        assertEquals(600_000, state.draft.positionBroadcastSecs)
        assertTrue(state.edits().isEmpty)

        state = state.report(AdminResponse.PositionConfig(broadcastSecs = 300)) // and back in range
        assertEquals(300, state.draft.positionBroadcastSecs)
        assertTrue(state.edits().isEmpty)
    }

    @Test fun `an edit on a radio above the limit stays an edit through later reports`() {
        var state = factory().report(AdminResponse.PositionConfig(broadcastSecs = 259_200))
        state = state.copy(draft = state.draft.copy(positionBroadcastSecs = 120))
        state = state.report(AdminResponse.PositionConfig(broadcastSecs = 259_200))

        assertEquals(120, state.draft.positionBroadcastSecs)
        assertEquals(DeviceEdits(positionBroadcastSecs = 120), state.edits())
    }

    // endregion

    // region values with no name ----------------------------------------------------------

    @Test fun `a role or preset this app has no name for is unknown and never edited`() {
        val state = factory().report(
            AdminResponse.DeviceConfig(role = null, rebroadcastMode = RebroadcastMode.ALL),
            AdminResponse.LoraConfig(preset = null, region = MeshRegion.US),
        )
        assertNull(state.radio!!.role)
        assertNull(state.radio!!.channelPreset)

        val candidate = state.draft.copy(role = MeshRole.ROUTER, channelPreset = MeshChannelPreset.SHORT_FAST)
        assertNull("never written over what it cannot name", state.edits(candidate).role)
        assertNull(state.edits(candidate).channelPreset)
        assertEquals("the draft keeps what it had", MeshRole.CLIENT, state.draft.role)
    }

    // endregion

    // region names -------------------------------------------------------------------------------

    @Test fun `a blank name is not an edit`() {
        val state = factory()
        val edits = state.edits(state.draft.copy(longName = "", shortName = "  "))
        assertNull(edits.longName)
        assertNull(edits.shortName)
    }

    @Test fun `names are judged as they would be sent, so a name that gets cut is not an edit forever`() {
        val state = factory().report(
            AdminResponse.Owner("x".repeat(39), "ABCD"),
            AdminResponse.Channel(0, "abcdefghijk", 1),
        )
        val candidate = state.draft.copy(longName = "x".repeat(45), channelName = "abcdefghijkl")
        assertTrue(state.edits(candidate).isEmpty)
    }

    @Test fun `an edited name is an edit`() {
        val state = factory()
        val edits = state.edits(state.draft.copy(longName = "New Name", channelName = "ops"))
        assertEquals("New Name", edits.longName)
        assertEquals("ops", edits.channelName)
        assertNull(edits.shortName)
    }

    // endregion

    // region the primary channel only --------------------------------------------------------------

    @Test fun `a report for another channel slot changes nothing`() {
        val state = factory().report(AdminResponse.Channel(index = 3, name = "Local", role = 2))
        assertEquals("", state.draft.channelName)
        assertEquals("", state.radio!!.channelName)
    }

    // endregion

    // region lora ------------------------------------------------------------------------------------

    @Test fun `the radio's region and preset are kept for the Mesh Channels screen`() {
        val state = freshInstall.report(AdminResponse.LoraConfig(MeshChannelPreset.MEDIUM_FAST, MeshRegion.EU_868))
        assertEquals(MeshRegion.EU_868, state.radio!!.region)
        assertEquals(MeshChannelPreset.MEDIUM_FAST, state.radio!!.channelPreset)
        assertTrue(state.radio!!.loraLoaded)
        assertTrue(DeviceSettingsState().radio?.loraLoaded != true)
    }

    // endregion

    // region the draft buffer ----------------------------------------------------------------------------

    @Test fun `rebased keeps what was typed in and follows the saved draft for the rest`() {
        val from = MeshDeviceConfig(longName = "A", shortName = "AA", role = MeshRole.CLIENT, positionBroadcastSecs = 900, channelName = "", channelPreset = MeshChannelPreset.LONG_FAST)
        val to = from.copy(positionBroadcastSecs = 120, role = MeshRole.ROUTER, channelName = "ops")
        val typed = from.copy(positionBroadcastSecs = 300) // the operator typed a new interval

        val rebased = typed.rebased(from = from, to = to)

        assertEquals("typed in: stays", 300, rebased.positionBroadcastSecs)
        assertEquals("not touched: follows", MeshRole.ROUTER, rebased.role)
        assertEquals("not touched: follows", "ops", rebased.channelName)
    }

    // endregion

    // region the position floor, before the push ---------------------------------------------------------------

    /** What the stock radio reports: the default channel, interval one hour. */
    private fun stock() = factory().report(AdminResponse.PositionConfig(3_600))

    private val reasonOneHour = PositionFloor.reason(PositionFloor.DEFAULT_FLOOR_SECS)

    private fun hint(state: DeviceSettingsState, facts: PositionFacts?, change: MeshDeviceConfig.() -> MeshDeviceConfig): String? =
        state.positionIntervalHint(facts, state.draft.change())

    @Test fun `a short interval chosen for a radio on the default channel gets the reason`() {
        val state = stock()
        assertEquals(reasonOneHour, hint(state, PositionFixtures.facts()) { copy(positionBroadcastSecs = 120) })
    }

    @Test fun `the interval the radio reports shows the hint too, when it is under the floor`() {
        // The radio holds what it was sent until it restarts, so it can report a short interval.
        val state = factory().report(AdminResponse.PositionConfig(120))
        assertEquals(reasonOneHour, state.positionIntervalHint(PositionFixtures.facts()))
    }

    @Test fun `an interval at or over the floor, or 0, gets no hint`() {
        val state = stock()
        val facts = PositionFixtures.facts()
        assertNull("the floor itself", hint(state, facts) { copy(positionBroadcastSecs = 3_600) })
        assertNull("over it", hint(state, facts) { copy(positionBroadcastSecs = 7_200) })
        assertEquals("just under it", reasonOneHour, hint(state, facts) { copy(positionBroadcastSecs = 3_599) })
        assertNull("0 is left alone by the firmware", hint(state, facts) { copy(positionBroadcastSecs = 0) })
        assertNull("the radio's own value, at the floor, unedited", state.positionIntervalHint(facts))
    }

    @Test fun `a primary channel with a 32 byte key gets no hint`() {
        val facts = PositionFixtures.facts(mapOf(0 to PositionFixtures.channel(key = PositionFixtures.privateKey())))
        assertNull(hint(stock(), facts) { copy(positionBroadcastSecs = 120) })
    }

    @Test fun `a renamed channel on the default key gets no hint, the firmware does not call it the default channel`() {
        val facts = PositionFixtures.facts(mapOf(0 to PositionFixtures.channel(name = "Alpha")))
        assertNull(hint(stock(), facts) { copy(positionBroadcastSecs = 120) })
    }

    @Test fun `no position precision on any channel gets no hint`() {
        val facts = PositionFixtures.facts(mapOf(0 to PositionFixtures.channel(precision = 0)))
        assertNull(hint(stock(), facts) { copy(positionBroadcastSecs = 120) })
    }

    @Test fun `a router's floor is twelve hours`() {
        val state = stock()
        val facts = PositionFixtures.facts(role = 2)
        assertEquals(PositionFloor.reason(PositionFloor.ROUTER_FLOOR_SECS), hint(state, facts) { copy(positionBroadcastSecs = 3_600) })
        assertNull(hint(state, facts) { copy(positionBroadcastSecs = 43_200) })
        assertEquals("ROUTER_LATE too", PositionFloor.reason(PositionFloor.ROUTER_FLOOR_SECS), hint(state, PositionFixtures.facts(role = 11)) { copy(positionBroadcastSecs = 600) })
    }

    @Test fun `choosing the router role in the same push moves the floor, and leaving it moves it back`() {
        val state = stock()
        val client = PositionFixtures.facts(role = 0)
        assertNull("a client at one hour", hint(state, client) { copy(positionBroadcastSecs = 3_600) })
        assertEquals(
            "a router once pushed",
            PositionFloor.reason(PositionFloor.ROUTER_FLOOR_SECS),
            hint(state, client) { copy(role = MeshRole.ROUTER, positionBroadcastSecs = 3_600) },
        )
        val router = PositionFixtures.facts(role = 2)
        assertNull(
            "a router leaving the role",
            hint(state.report(AdminResponse.DeviceConfig(MeshRole.ROUTER, RebroadcastMode.ALL)), router) { copy(role = MeshRole.CLIENT, positionBroadcastSecs = 3_600) },
        )
    }

    @Test fun `a rename in the same push decides it too`() {
        val state = stock()
        val facts = PositionFixtures.facts()
        assertEquals(reasonOneHour, hint(state, facts) { copy(positionBroadcastSecs = 120) })
        assertNull("renamed: no longer the default channel", hint(state, facts) { copy(positionBroadcastSecs = 120, channelName = "Alpha") })
        assertEquals("renamed to the preset's name", reasonOneHour, hint(state, facts) { copy(positionBroadcastSecs = 120, channelName = "LongFast") })
    }

    @Test fun `a preset in the same push decides it too`() {
        val named = PositionFixtures.facts(mapOf(0 to PositionFixtures.channel(name = "MediumFast")))
        val state = stock().report(AdminResponse.Channel(0, "MediumFast", 1))
        assertNull("the radio is on LONG_FAST: this name is not the default", hint(state, named) { copy(positionBroadcastSecs = 120) })
        assertEquals(
            "moved to MEDIUM_FAST, it is",
            reasonOneHour,
            hint(state, named) { copy(positionBroadcastSecs = 120, channelPreset = MeshChannelPreset.MEDIUM_FAST) },
        )
    }

    @Test fun `nothing is said while what the decision needs is not loaded`() {
        val state = stock()
        val short = { s: DeviceSettingsState -> s.draft.copy(positionBroadcastSecs = 120) }
        assertNull("no facts", state.positionIntervalHint(null, short(state)))
        assertNull("no LoRa config", state.positionIntervalHint(PositionFixtures.facts(lora = null), short(state)))
        assertNull("no device config", state.positionIntervalHint(PositionFixtures.facts(role = null), short(state)))
        assertNull(
            "channel 0 not reported",
            state.positionIntervalHint(PositionFacts(List(8) { null }, PositionFacts.LoraFacts(true, 0), 0), short(state)),
        )
        assertNull(
            "the radio has not reported its interval, so the draft's is a leftover",
            DeviceSettingsState(radio = RadioSettings(role = MeshRole.CLIENT))
                .positionIntervalHint(PositionFixtures.facts(), MeshDeviceConfig(positionBroadcastSecs = 30)),
        )
        assertNull(
            "no radio at all",
            DeviceSettingsState(radio = null).positionIntervalHint(PositionFixtures.facts(), MeshDeviceConfig(positionBroadcastSecs = 30)),
        )
    }

    // endregion
}
