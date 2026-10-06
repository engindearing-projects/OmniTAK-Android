package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.PositionFixtures.cache
import soy.engindearing.omnitak.mobile.data.PositionFixtures.channel
import soy.engindearing.omnitak.mobile.data.PositionFixtures.disabled
import soy.engindearing.omnitak.mobile.data.PositionFixtures.lora
import soy.engindearing.omnitak.mobile.data.RadioSettingsCache.Key

/**
 * Whether a radio's position channel is its default channel, the way firmware 2.7.26 decides it, and the floor it
 * then puts under the position interval ([PositionFloor], [PositionFacts]).
 *
 * Every case is read from messages as a radio reports them, through [RadioSettingsCache], the way the app reads
 * them. The expected answers come from the firmware source (NodeDB.cpp, Channels.cpp, DisplayFormatters.cpp), not
 * from the code under test. All keys and names are made up.
 */
class PositionFactsTest {

    private fun onDefault(
        channels: Map<Int, ByteArray> = mapOf(0 to channel()),
        lora: ByteArray? = lora(),
    ): Boolean? = PositionFacts.read(cache(channels, lora)).onDefaultChannel()

    // region the default channel ----------------------------------------------------------------

    @Test fun `the stock primary channel, one byte key 1 and no name, is the default channel`() {
        assertEquals(true, onDefault())
    }

    @Test fun `a 32 byte key is not the default key`() {
        assertEquals(false, onDefault(mapOf(0 to channel(key = PositionFixtures.privateKey(5)))))
    }

    @Test fun `a one byte key other than 1 is not the default key, and neither is no key`() {
        assertEquals(false, onDefault(mapOf(0 to channel(key = byteArrayOf(2)))))
        assertEquals(false, onDefault(mapOf(0 to channel(key = byteArrayOf(0)))))
        assertEquals(false, onDefault(mapOf(0 to channel(key = null))))
        assertEquals("a two byte key starting with 1 is not it either", false, onDefault(mapOf(0 to channel(key = byteArrayOf(1, 1)))))
    }

    @Test fun `the default key with a name that is not the preset's name is not the default channel`() {
        assertEquals(false, onDefault(mapOf(0 to channel(name = "Alpha"))))
    }

    @Test fun `the preset's own name, written out, is the default channel`() {
        assertEquals(true, onDefault(mapOf(0 to channel(name = "LongFast"))))
        assertEquals(true, onDefault(mapOf(0 to channel(name = "MediumFast")), lora(preset = 4)))
    }

    @Test fun `the name is compared with the radio's own preset, an empty name standing for it`() {
        assertEquals("MediumFast radio, name LongFast", false, onDefault(mapOf(0 to channel(name = "LongFast")), lora(preset = 4)))
        assertEquals("MediumFast radio, empty name", true, onDefault(mapOf(0 to channel()), lora(preset = 4)))
        assertEquals("LongFast radio, name MediumFast", false, onDefault(mapOf(0 to channel(name = "MediumFast"))))
    }

    @Test fun `the name is case sensitive`() {
        assertEquals(false, onDefault(mapOf(0 to channel(name = "longfast"))))
    }

    @Test fun `every preset's display name is the default channel's name, and the ones without a display name are Invalid`() {
        val names = mapOf(
            0 to "LongFast", 1 to "LongSlow", 3 to "MediumSlow", 4 to "MediumFast", 5 to "ShortSlow",
            6 to "ShortFast", 7 to "LongMod", 8 to "ShortTurbo", 9 to "LongTurbo",
        )
        for ((preset, name) in names) {
            assertEquals("preset $preset", true, onDefault(mapOf(0 to channel(name = name)), lora(preset = preset)))
            assertEquals("preset $preset with the name of LongFast", preset == 0, onDefault(mapOf(0 to channel(name = "LongFast")), lora(preset = preset)))
        }
        // VERY_LONG_SLOW (2) and the newer presets (10 to 13) have no case in getModemPresetDisplayName: "Invalid".
        for (preset in listOf(2, 10, 11, 12, 13, 99)) {
            assertEquals("preset $preset, name Invalid", true, onDefault(mapOf(0 to channel(name = "Invalid")), lora(preset = preset)))
            assertEquals("preset $preset, empty name", true, onDefault(mapOf(0 to channel()), lora(preset = preset)))
            assertEquals("preset $preset, name LongFast", false, onDefault(mapOf(0 to channel(name = "LongFast")), lora(preset = preset)))
        }
    }

    @Test fun `without use_preset the name that stands for the preset is Custom`() {
        for (usePreset in listOf(false, null)) {
            assertEquals("use_preset $usePreset, empty name", true, onDefault(mapOf(0 to channel()), lora(usePreset = usePreset)))
            assertEquals("use_preset $usePreset, name Custom", true, onDefault(mapOf(0 to channel(name = "Custom")), lora(usePreset = usePreset)))
            assertEquals("use_preset $usePreset, name LongFast", false, onDefault(mapOf(0 to channel(name = "LongFast")), lora(usePreset = usePreset)))
        }
        assertEquals("with a preset, Custom is not the name", false, onDefault(mapOf(0 to channel(name = "Custom"))))
    }

    // endregion

    // region which channel sends positions -------------------------------------------------------

    @Test fun `with no position precision on any channel nothing is sent and nothing is raised`() {
        assertEquals("module settings with a precision of 0", false, onDefault(mapOf(0 to channel(precision = 0))))
        assertEquals("no module settings", false, onDefault(mapOf(0 to channel(precision = null))))
        assertEquals("a default channel in slot 1 with no precision either", false, onDefault(mapOf(0 to channel(precision = 0), 1 to channel(1, precision = 0))))
    }

    @Test fun `the first channel with a precision decides, later ones do not`() {
        val private = PositionFixtures.privateKey()
        assertEquals("private first, default second", false, onDefault(mapOf(0 to channel(key = private), 1 to channel(1))))
        assertEquals("default first, private second", true, onDefault(mapOf(0 to channel(), 1 to channel(1, key = private))))
        assertEquals("slot 0 sends no positions, slot 1 is default", true, onDefault(mapOf(0 to channel(key = private, precision = 0), 1 to channel(1))))
        assertEquals("slot 0 sends no positions, slot 1 is private", false, onDefault(mapOf(0 to channel(precision = 0), 1 to channel(1, key = private))))
        assertEquals("only slot 7 sends positions", true, onDefault(mapOf(0 to channel(precision = 0), 7 to channel(7))))
    }

    @Test fun `a channel's role is not looked at, as in the firmware`() {
        assertEquals(true, onDefault(mapOf(0 to channel(precision = 0), 3 to channel(3, role = 0))))
    }

    // endregion

    // region not loaded -----------------------------------------------------------------------------

    @Test fun `nothing is decided without the LoRa config`() {
        assertNull(PositionFacts.read(cache(lora = null)).onDefaultChannel())
    }

    @Test fun `nothing is decided while a channel the search has to look at is missing`() {
        val noChannels = cache(fillRest = false, channels = emptyMap())
        assertNull("no channel at all", PositionFacts.read(noChannels).onDefaultChannel())

        val slot0Missing = cache(channels = mapOf(1 to channel(1)), fillRest = false)
        assertNull("slot 0 is the first to look at", PositionFacts.read(slot0Missing).onDefaultChannel())

        val slot1Missing = cache(channels = mapOf(0 to channel(precision = 0)), fillRest = false)
        assertNull("slot 0 sends no positions, so slot 1 matters", PositionFacts.read(slot1Missing).onDefaultChannel())

        val nothingSendsYet = cache(channels = (0..6).associateWith { disabled(it) }, fillRest = false)
        assertNull("no channel sends positions, but slot 7 is not loaded", PositionFacts.read(nothingSendsYet).onDefaultChannel())
    }

    @Test fun `a slot after the position channel does not need to be loaded`() {
        val c = cache(channels = mapOf(0 to channel()), fillRest = false)
        assertEquals(true, PositionFacts.read(c).onDefaultChannel())
    }

    @Test fun `a cache the radio has not filled yet says nothing`() {
        val facts = PositionFacts.read(RadioSettingsCache())
        assertNull(facts.onDefaultChannel())
        assertNull(facts.lora)
        assertNull(facts.role)
        assertEquals(List(8) { null }, facts.channels)
    }

    @Test fun `a message that cannot be read is not stored, so it is not a fact`() {
        val c = RadioSettingsCache()
        assertFalse(c.put(Key.Channel(0), byteArrayOf(0x12, 0x7F))) // a length that runs past the end
        assertNull(PositionFacts.read(c).channels[0])
    }

    // endregion

    // region edits that are about to be pushed ---------------------------------------------------------

    @Test fun `a rename of the primary channel that is about to be pushed stands in for its name`() {
        val facts = PositionFacts.read(cache())
        assertEquals("renamed to something else", false, facts.onDefaultChannel(channelName = "Alpha"))
        assertEquals("renamed to the preset's name", true, facts.onDefaultChannel(channelName = "LongFast"))
        assertEquals("renamed to nothing", true, facts.onDefaultChannel(channelName = ""))

        val named = PositionFacts.read(cache(mapOf(0 to channel(name = "Alpha"))))
        assertEquals(false, named.onDefaultChannel())
        assertEquals("renamed back", true, named.onDefaultChannel(channelName = "LongFast"))
    }

    @Test fun `a rename of the primary channel does not stand in for the name of a position channel in another slot`() {
        val facts = PositionFacts.read(cache(mapOf(0 to channel(precision = 0), 1 to channel(1, name = "Alpha"))))
        assertEquals(false, facts.onDefaultChannel())
        assertEquals("slot 0 sends no positions: its name is not what decides", false, facts.onDefaultChannel(channelName = "LongFast"))
    }

    @Test fun `a preset that is about to be pushed stands in for the radio's, and turns use_preset on`() {
        val facts = PositionFacts.read(cache(mapOf(0 to channel(name = "MediumFast"))))
        assertEquals("LongFast radio, channel named MediumFast", false, facts.onDefaultChannel())
        assertEquals("preset moved to MEDIUM_FAST", true, facts.onDefaultChannel(modemPreset = 4))

        val custom = PositionFacts.read(cache(mapOf(0 to channel(name = "Custom")), lora(usePreset = false)))
        assertEquals(true, custom.onDefaultChannel())
        assertEquals("a preset turns use_preset on, so the name is no longer Custom", false, custom.onDefaultChannel(modemPreset = 0))
    }

    // endregion

    // region the role and the floor ----------------------------------------------------------------

    @Test fun `the role is read from the device config, and a client is not written on the wire`() {
        assertEquals(0, PositionFacts.read(cache(role = 0)).role)
        assertEquals(7, PositionFacts.read(cache(role = 7)).role)
        assertEquals(11, PositionFacts.read(cache(role = 11)).role)
        assertNull(PositionFacts.read(cache(role = null)).role)
    }

    @Test fun `the floor is one hour, and twelve hours for the router roles only`() {
        assertEquals(3_600, PositionFloor.floorSecs(0)) // CLIENT
        assertEquals(43_200, PositionFloor.floorSecs(2)) // ROUTER
        assertEquals(3_600, PositionFloor.floorSecs(3)) // ROUTER_CLIENT
        assertEquals(3_600, PositionFloor.floorSecs(4)) // REPEATER
        assertEquals(3_600, PositionFloor.floorSecs(7)) // TAK
        assertEquals(43_200, PositionFloor.floorSecs(11)) // ROUTER_LATE
        assertEquals(3_600, PositionFloor.floorSecs(12)) // CLIENT_BASE
    }

    @Test fun `the reason names the floor, in words, for both floors`() {
        val client = PositionFloor.reason(PositionFloor.DEFAULT_FLOOR_SECS)
        assertEquals(
            "This radio sends positions on its public default channel. " +
                "Meshtastic firmware 2.7.26 raises a position interval under one hour to one hour when the radio restarts. " +
                "A private channel is needed for faster position updates.",
            client,
        )
        val router = PositionFloor.reason(PositionFloor.ROUTER_FLOOR_SECS)
        assertTrue(router, "under twelve hours to twelve hours when the radio restarts" in router)
        assertFalse(router, "one hour" in router)
    }

    // endregion
}
