package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Mesh Channels screen's LoRa controls.
 *
 * The region control used to start at US with UNSET filtered out of its list,
 * so applying a preset on a radio in Europe set the region to US as well. The
 * controls now show what the radio reported, and only what the operator picked
 * (and that differs from the radio) is sent.
 */
class LoraPicksTest {

    private val europe = RadioSettings(region = MeshRegion.EU_868, channelPreset = MeshChannelPreset.LONG_FAST, loraLoaded = true)

    @Test fun `the controls start at the radio's region and preset, not at US`() {
        val picks = LoraPicks()
        assertEquals(MeshRegion.EU_868, picks.shownRegion(europe))
        assertEquals(MeshChannelPreset.LONG_FAST, picks.shownPreset(europe))
    }

    @Test fun `a pick is what the controls show`() {
        val picks = LoraPicks(region = MeshRegion.US, preset = MeshChannelPreset.SHORT_FAST)
        assertEquals(MeshRegion.US, picks.shownRegion(europe))
        assertEquals(MeshChannelPreset.SHORT_FAST, picks.shownPreset(europe))
    }

    @Test fun `a preset pick on a radio in Europe sends the preset and leaves the region alone`() {
        val send = LoraPicks(preset = MeshChannelPreset.SHORT_FAST).toSend(europe)
        assertEquals(LoraSend(region = MeshRegion.UNSET, preset = MeshChannelPreset.SHORT_FAST), send)
    }

    @Test fun `a region pick sends the region and leaves the preset alone`() {
        val send = LoraPicks(region = MeshRegion.US).toSend(europe)
        assertEquals(LoraSend(region = MeshRegion.US, preset = null), send)
    }

    @Test fun `a pick that equals what the radio has is not sent`() {
        assertNull(LoraPicks(region = MeshRegion.EU_868, preset = MeshChannelPreset.LONG_FAST).toSend(europe))
    }

    @Test fun `nothing picked sends nothing`() {
        assertNull(LoraPicks().toSend(europe))
    }

    @Test fun `with no LoRa config loaded there is nothing to apply, whatever was picked`() {
        val picks = LoraPicks(region = MeshRegion.US, preset = MeshChannelPreset.SHORT_FAST)
        assertNull("no radio at all", picks.toSend(null))
        assertNull("a radio that has not reported its LoRa config", picks.toSend(RadioSettings(longName = "Sim Radio One")))
    }

    @Test fun `a radio with no region shows none, and a preset apply leaves it unset`() {
        val fresh = RadioSettings(region = MeshRegion.UNSET, channelPreset = MeshChannelPreset.LONG_FAST, loraLoaded = true)
        assertNull("not set: the control says so instead of showing a region", LoraPicks().shownRegion(fresh))
        assertEquals(
            LoraSend(MeshRegion.UNSET, MeshChannelPreset.MEDIUM_FAST),
            LoraPicks(preset = MeshChannelPreset.MEDIUM_FAST).toSend(fresh),
        )
        assertEquals(LoraSend(MeshRegion.US, null), LoraPicks(region = MeshRegion.US).toSend(fresh))
    }

    @Test fun `a region or preset this app has no name for shows nothing and is never matched by accident`() {
        val unnamed = RadioSettings(region = null, channelPreset = null, loraLoaded = true)
        assertNull(LoraPicks().shownRegion(unnamed))
        assertNull(LoraPicks().shownPreset(unnamed))
        assertEquals(LoraSend(MeshRegion.UNSET, MeshChannelPreset.LONG_SLOW), LoraPicks(preset = MeshChannelPreset.LONG_SLOW).toSend(unnamed))
    }
}
