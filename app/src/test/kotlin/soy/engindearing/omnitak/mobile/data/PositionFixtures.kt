package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertTrue

/**
 * Messages as a radio reports them, for the tests of the position floor: channels, a LoRa config and a device role,
 * and a [RadioSettingsCache] holding them, which is where the app reads them from. All keys and names are made up.
 */
internal object PositionFixtures {

    /** A channel slot as a radio reports it. A null [key] has no key; a null [precision] has no module settings. */
    fun channel(
        index: Int = 0,
        key: ByteArray? = byteArrayOf(1),
        name: String = "",
        precision: Int? = 13,
        role: Int = 1,
    ): ByteArray {
        val settings = ProtoMsg()
        if (key != null) settings.bytes(2, key)
        if (name.isNotEmpty()) settings.string(3, name)
        if (precision != null) settings.msg(7, ProtoMsg().varint(1, precision))
        val ch = ProtoMsg()
        if (index != 0) ch.varint(1, index)
        return ch.msg(2, settings).varint(3, role).build()
    }

    /** A disabled slot: no settings at all. */
    fun disabled(index: Int): ByteArray = ProtoMsg().varint(1, index).build()

    /** A LoRa config. A null [usePreset] or [preset] is not on the wire, as the proto3 default is not. */
    fun lora(usePreset: Boolean? = true, preset: Int? = null): ByteArray {
        val m = ProtoMsg()
        if (usePreset != null) m.bool(1, usePreset)
        if (preset != null) m.varint(2, preset)
        return m.varint(7, 1).build()
    }

    /** A private 32 byte key, made up. */
    fun privateKey(seed: Int = 9): ByteArray = ByteArray(32) { (it + seed).toByte() }

    /** A cache holding what a radio reported: [channels] by slot (the other slots disabled), the LoRa config and the device role. */
    fun cache(
        channels: Map<Int, ByteArray> = mapOf(0 to channel()),
        lora: ByteArray? = lora(),
        role: Int? = 0,
        fillRest: Boolean = true,
    ): RadioSettingsCache {
        val cache = RadioSettingsCache()
        for (i in 0 until RadioSettingsCache.MAX_CHANNELS) {
            val bytes = channels[i] ?: if (fillRest) disabled(i) else continue
            assertTrue(cache.ingestChannel(bytes))
        }
        if (lora != null) assertTrue(cache.ingestConfig(ProtoMsg().bytes(6, lora).build()))
        if (role != null) {
            val device = if (role == 0) ByteArray(0) else ProtoMsg().varint(1, role).build()
            assertTrue(cache.ingestConfig(ProtoMsg().bytes(1, device).build()))
        }
        return cache
    }

    /** The facts read from [cache]. */
    fun facts(
        channels: Map<Int, ByteArray> = mapOf(0 to channel()),
        lora: ByteArray? = lora(),
        role: Int? = 0,
    ): PositionFacts = PositionFacts.read(cache(channels, lora, role))
}
