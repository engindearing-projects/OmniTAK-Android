package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The note about what a radio holds after it restarted, compared with what the last push sent it
 * ([SentSettings.check]): one sentence for each value it did not keep, with both values, and the reason for a
 * short interval when the firmware's floor explains it. A value the radio kept says nothing.
 *
 * The wording is pinned here, because it is what the operator reads.
 */
class SentSettingsTest {

    private val node = 0x0A0B0C0Du

    private val defaultChannel = PositionFacts.ChannelFacts(defaultKey = true, name = "", positionPrecision = 13)
    private val privateChannel = PositionFacts.ChannelFacts(defaultKey = false, name = "", positionPrecision = 13)
    private val disabledSlot = PositionFacts.ChannelFacts(defaultKey = false, name = "", positionPrecision = 0)

    private fun facts(
        primary: PositionFacts.ChannelFacts = defaultChannel,
        role: Int? = 0,
        lora: PositionFacts.LoraFacts? = PositionFacts.LoraFacts(usePreset = true, modemPreset = 0),
    ) = PositionFacts(listOf(primary) + List(7) { disabledSlot }, lora, role)

    private fun sent(vararg values: Pair<AdminSetting, Any>) = SentSettings(node, mapOf(*values))

    private val reason = PositionFloor.reason(PositionFloor.DEFAULT_FLOOR_SECS)

    @Test fun `an interval raised to the floor on the default channel says both numbers and the reason`() {
        val note = sent(AdminSetting.POSITION_INTERVAL to 120)
            .check(RadioSettings(positionBroadcastSecs = 3_600), facts())
        assertEquals("The radio reports 3600 s for the position interval. 120 s was sent. $reason", note)
    }

    @Test fun `a value the radio kept says nothing`() {
        assertNull(sent(AdminSetting.POSITION_INTERVAL to 120).check(RadioSettings(positionBroadcastSecs = 120), facts()))
        assertNull(sent(AdminSetting.ROLE to MeshRole.TAK).check(RadioSettings(role = MeshRole.TAK), facts()))
        assertNull(sent(AdminSetting.CHANNEL_NAME to "Alpha").check(RadioSettings(channelName = "Alpha"), facts()))
    }

    @Test fun `a changed interval on a private channel is reported without the reason`() {
        val note = sent(AdminSetting.POSITION_INTERVAL to 120)
            .check(RadioSettings(positionBroadcastSecs = 3_600), facts(primary = privateChannel))
        assertEquals("The radio reports 3600 s for the position interval. 120 s was sent.", note)
    }

    @Test fun `the reason is added only when the floor explains the value the radio reports`() {
        val radio = { secs: Int -> RadioSettings(positionBroadcastSecs = secs) }
        // Not the floor: something else changed it.
        assertEquals(
            "The radio reports 900 s for the position interval. 120 s was sent.",
            sent(AdminSetting.POSITION_INTERVAL to 120).check(radio(900), facts()),
        )
        // The floor is not what 0 turns into: the firmware leaves 0 alone.
        assertEquals(
            "The radio reports 3600 s for the position interval. 0 s was sent.",
            sent(AdminSetting.POSITION_INTERVAL to 0).check(radio(3_600), facts()),
        )
        // The interval that was sent was not under the floor.
        assertEquals(
            "The radio reports 3600 s for the position interval. 7200 s was sent.",
            sent(AdminSetting.POSITION_INTERVAL to 7_200).check(radio(3_600), facts()),
        )
        // The channels are not loaded, so nothing is known about them.
        assertEquals(
            "The radio reports 3600 s for the position interval. 120 s was sent.",
            sent(AdminSetting.POSITION_INTERVAL to 120).check(radio(3_600), PositionFacts(List(8) { null }, null, null)),
        )
        assertEquals(
            "The radio reports 3600 s for the position interval. 120 s was sent.",
            sent(AdminSetting.POSITION_INTERVAL to 120).check(radio(3_600), null),
        )
        // The role is not known, so the floor is not known.
        assertEquals(
            "The radio reports 3600 s for the position interval. 120 s was sent.",
            sent(AdminSetting.POSITION_INTERVAL to 120).check(radio(3_600), facts(role = null)),
        )
    }

    @Test fun `a router's floor is twelve hours, in the numbers and in the reason`() {
        val note = sent(AdminSetting.POSITION_INTERVAL to 3_600)
            .check(RadioSettings(positionBroadcastSecs = 43_200), facts(role = 2))
        assertEquals(
            "The radio reports 43200 s for the position interval. 3600 s was sent. " +
                PositionFloor.reason(PositionFloor.ROUTER_FLOOR_SECS),
            note,
        )
        assertEquals(
            "ROUTER_LATE is a router too",
            note,
            sent(AdminSetting.POSITION_INTERVAL to 3_600).check(RadioSettings(positionBroadcastSecs = 43_200), facts(role = 11)),
        )
    }

    @Test fun `a router that keeps one hour because it was not on the default channel is not blamed on the floor`() {
        val note = sent(AdminSetting.POSITION_INTERVAL to 3_600)
            .check(RadioSettings(positionBroadcastSecs = 43_200), facts(primary = privateChannel, role = 2))
        assertEquals("The radio reports 43200 s for the position interval. 3600 s was sent.", note)
    }

    @Test fun `each setting that was not kept gets its own sentence, in the order of the settings, with the reason once`() {
        val note = sent(
            AdminSetting.POSITION_INTERVAL to 120,
            AdminSetting.ROLE to MeshRole.TAK,
            AdminSetting.MODEM_PRESET to MeshChannelPreset.SHORT_FAST,
            AdminSetting.LONG_NAME to "Sim Four",
        ).check(
            RadioSettings(
                longName = "Sim Old", role = MeshRole.CLIENT, positionBroadcastSecs = 3_600,
                channelPreset = MeshChannelPreset.LONG_FAST,
            ),
            facts(),
        )
        assertEquals(
            "The radio reports \"Sim Old\" for the long name. \"Sim Four\" was sent. " +
                "The radio reports Client for the role. TAK was sent. " +
                "The radio reports 3600 s for the position interval. 120 s was sent. " +
                "The radio reports Long Fast for the modem preset. Short Fast was sent. " + reason,
            note,
        )
    }

    @Test fun `only the values that differ are named`() {
        val note = sent(
            AdminSetting.POSITION_INTERVAL to 120,
            AdminSetting.ROLE to MeshRole.TAK,
        ).check(RadioSettings(role = MeshRole.TAK, positionBroadcastSecs = 3_600), facts())
        assertEquals("The radio reports 3600 s for the position interval. 120 s was sent. $reason", note)
    }

    @Test fun `a name the radio reports as empty is said in words`() {
        val note = sent(AdminSetting.CHANNEL_NAME to "Alpha").check(RadioSettings(channelName = ""), facts())
        assertEquals("The radio reports no name for the channel name. \"Alpha\" was sent.", note)
    }

    @Test fun `a region is named the way the operator reads it`() {
        val note = sent(AdminSetting.REGION to MeshRegion.EU_868).check(RadioSettings(region = MeshRegion.US), facts())
        assertEquals("The radio reports United States for the region. EU 868 MHz was sent.", note)
    }

    @Test fun `a setting the radio has not reported, or reports as something this app has no name for, is not judged`() {
        assertNull(sent(AdminSetting.POSITION_INTERVAL to 120).check(RadioSettings(), facts()))
        assertNull(sent(AdminSetting.ROLE to MeshRole.TAK).check(RadioSettings(role = null), facts()))
    }

    @Test fun `settings the screen does not track are not judged`() {
        assertNull(sent(AdminSetting.REBROADCAST_MODE to RebroadcastMode.LOCAL_ONLY).check(RadioSettings(), facts()))
        assertNull(sent(AdminSetting.CHANNEL to "imported").check(RadioSettings(), facts()))
    }

    @Test fun `nothing sent, nothing to say`() {
        assertNull(SentSettings(node, emptyMap()).check(RadioSettings(positionBroadcastSecs = 3_600), facts()))
    }
}
