package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.InterruptedWrite.Verdict
import soy.engindearing.omnitak.mobile.data.InterruptedWrite.WrittenValue
import soy.engindearing.omnitak.mobile.data.RadioSettingsCache.Key

/**
 * What a download says about a transaction a lost link left open, on a radio that does not count its restarts
 * ([InterruptedWrite.verdict]), and how the values of a sequence are read from the radio's own messages.
 */
class InterruptedWriteTest {

    private fun write(vararg values: Pair<AdminSetting, WrittenValue>, settings: List<AdminSetting> = values.map { it.first }) =
        InterruptedWrite(0x0A0B0C0Du, 0u, settings, mapOf(*values))

    private fun w(before: Any?, sent: Any) = WrittenValue(before, sent)

    // region the verdict ----------------------------------------------------------------------------------------

    @Test fun `a written value the radio still reports, that it did not have before, means its memory survived`() {
        val interrupted = write(AdminSetting.LONG_NAME to w("Old", "New"))
        assertEquals(Verdict.HOLDS, interrupted.verdict(RadioSettings(longName = "New")))
    }

    @Test fun `the old values back mean the radio restarted or never got the writes`() {
        val interrupted = write(AdminSetting.LONG_NAME to w("Old", "New"))
        assertEquals(Verdict.GONE, interrupted.verdict(RadioSettings(longName = "Old")))
    }

    @Test fun `a value that is neither the old one nor the one sent is not the radio's memory either`() {
        val interrupted = write(AdminSetting.LONG_NAME to w("Old", "New"))
        assertEquals("another client named it since", Verdict.GONE, interrupted.verdict(RadioSettings(longName = "Other")))
    }

    @Test fun `one written value still reported is enough, whatever the others say`() {
        val interrupted = write(
            AdminSetting.ROLE to w(MeshRole.CLIENT, MeshRole.TAK),
            AdminSetting.POSITION_INTERVAL to w(900, 120),
            AdminSetting.LONG_NAME to w("Old", "New"),
        )
        val radio = RadioSettings(role = MeshRole.CLIENT, positionBroadcastSecs = 120, longName = "Old")
        assertEquals(Verdict.HOLDS, interrupted.verdict(radio))
    }

    @Test fun `a value the radio held before the sequence says nothing either way`() {
        // The short name was sent as it already was.
        val interrupted = write(AdminSetting.LONG_NAME to w("Old", "New"), AdminSetting.SHORT_NAME to w("SN", "SN"))
        assertEquals("only the short name, which proves nothing", Verdict.UNKNOWN, interrupted.verdict(RadioSettings(shortName = "SN")))
        assertEquals("the long name is old: gone", Verdict.GONE, interrupted.verdict(RadioSettings(longName = "Old", shortName = "SN")))
        assertEquals("the long name is new: held", Verdict.HOLDS, interrupted.verdict(RadioSettings(longName = "New", shortName = "SN")))
    }

    @Test fun `a setting the radio has not reported, or reports as a value with no name here, is not judged`() {
        val interrupted = write(AdminSetting.ROLE to w(MeshRole.CLIENT, MeshRole.TAK))
        assertEquals(Verdict.UNKNOWN, interrupted.verdict(RadioSettings()))
        val twoSettings = write(AdminSetting.ROLE to w(MeshRole.CLIENT, MeshRole.TAK), AdminSetting.LONG_NAME to w("Old", "New"))
        assertEquals("the role is not reported, the long name is old", Verdict.GONE, twoSettings.verdict(RadioSettings(longName = "Old")))
    }

    @Test fun `a value the radio held before that was unknown here is still a change`() {
        val interrupted = write(AdminSetting.ROLE to w(null, MeshRole.TAK))
        assertEquals(Verdict.HOLDS, interrupted.verdict(RadioSettings(role = MeshRole.TAK)))
        assertEquals(Verdict.GONE, interrupted.verdict(RadioSettings(role = MeshRole.CLIENT)))
    }

    @Test fun `nothing comparable was written, so nothing is known`() {
        assertEquals(Verdict.UNKNOWN, write(settings = listOf(AdminSetting.CHANNEL)).verdict(RadioSettings(longName = "x")))
        assertEquals(Verdict.UNKNOWN, write().verdict(RadioSettings()))
    }

    @Test fun `every setting the app writes can be compared, the rebroadcast mode included`() {
        val cases = listOf(
            AdminSetting.LONG_NAME to (w("a", "b") to RadioSettings(longName = "b")),
            AdminSetting.SHORT_NAME to (w("a", "b") to RadioSettings(shortName = "b")),
            AdminSetting.ROLE to (w(MeshRole.CLIENT, MeshRole.TAK) to RadioSettings(role = MeshRole.TAK)),
            AdminSetting.POSITION_INTERVAL to (w(900, 120) to RadioSettings(positionBroadcastSecs = 120)),
            AdminSetting.CHANNEL_NAME to (w("", "Alpha") to RadioSettings(channelName = "Alpha")),
            AdminSetting.MODEM_PRESET to (w(MeshChannelPreset.LONG_FAST, MeshChannelPreset.SHORT_FAST) to RadioSettings(channelPreset = MeshChannelPreset.SHORT_FAST)),
            AdminSetting.REGION to (w(MeshRegion.UNSET, MeshRegion.US) to RadioSettings(region = MeshRegion.US)),
            AdminSetting.REBROADCAST_MODE to (w(RebroadcastMode.ALL, RebroadcastMode.KNOWN_ONLY) to RadioSettings(rebroadcastMode = RebroadcastMode.KNOWN_ONLY)),
        )
        for ((setting, pair) in cases) {
            assertEquals(setting.name, Verdict.HOLDS, write(setting to pair.first).verdict(pair.second))
            assertEquals(setting.name, Verdict.GONE, write(setting to pair.first).verdict(RadioSettings().let { r ->
                // The radio reports the value it had before.
                when (setting) {
                    AdminSetting.LONG_NAME -> r.copy(longName = "a")
                    AdminSetting.SHORT_NAME -> r.copy(shortName = "a")
                    AdminSetting.ROLE -> r.copy(role = MeshRole.CLIENT)
                    AdminSetting.POSITION_INTERVAL -> r.copy(positionBroadcastSecs = 900)
                    AdminSetting.CHANNEL_NAME -> r.copy(channelName = "")
                    AdminSetting.MODEM_PRESET -> r.copy(channelPreset = MeshChannelPreset.LONG_FAST)
                    AdminSetting.REGION -> r.copy(region = MeshRegion.UNSET)
                    AdminSetting.REBROADCAST_MODE -> r.copy(rebroadcastMode = RebroadcastMode.ALL)
                    AdminSetting.CHANNEL -> r
                }
            }))
        }
    }

    // endregion

    // region the values of a sequence, read from the radio's own messages -------------------------------------------

    @Test fun `the owner entry gives the two names`() {
        val values = settingValuesOf(Key.Owner, AdminTestFrames.userMessage(longName = "Test Node One", shortName = "TNO"))
        assertEquals(mapOf(AdminSetting.LONG_NAME to "Test Node One", AdminSetting.SHORT_NAME to "TNO"), values)
    }

    @Test fun `the config entries give the role, the rebroadcast mode, the interval, the preset and the region`() {
        assertEquals(
            mapOf(AdminSetting.ROLE to MeshRole.TAK, AdminSetting.REBROADCAST_MODE to RebroadcastMode.LOCAL_ONLY),
            settingValuesOf(Key.Config(RadioSettingsCache.CONFIG_DEVICE), AdminTestFrames.deviceConfig(role = 7, rebroadcast = 2)),
        )
        assertEquals(
            mapOf(AdminSetting.POSITION_INTERVAL to 321),
            settingValuesOf(Key.Config(RadioSettingsCache.CONFIG_POSITION), AdminTestFrames.positionConfig(secs = 321)),
        )
        assertEquals(
            mapOf(AdminSetting.MODEM_PRESET to MeshChannelPreset.MEDIUM_FAST, AdminSetting.REGION to MeshRegion.EU_868),
            settingValuesOf(Key.Config(RadioSettingsCache.CONFIG_LORA), AdminTestFrames.loraConfig(preset = 4, region = 3)),
        )
    }

    @Test fun `only the primary channel's name is a setting`() {
        assertEquals(
            mapOf(AdminSetting.CHANNEL_NAME to "Alpha"),
            settingValuesOf(Key.Channel(0), AdminTestFrames.channelMessage(index = 0, name = "Alpha")),
        )
        assertTrue(settingValuesOf(Key.Channel(2), AdminTestFrames.channelMessage(index = 2, name = "Beta")).isEmpty())
    }

    @Test fun `a role or preset with no name here is left out, not guessed`() {
        assertTrue(settingValuesOf(Key.Config(RadioSettingsCache.CONFIG_DEVICE), ProtoMsg().varint(1, 11).build()).keys.none { it == AdminSetting.ROLE })
    }

    // endregion
}
