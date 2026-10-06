package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals

/**
 * Test-side reading of the frames the app sends to a radio, and building of the
 * frames a radio sends to the app.
 *
 * Decoding goes through [ProtoReader], not through the code under test
 * ([ProtoFields] and the serializer), and the numbers are named here from the
 * official protos, so a wrong number in production cannot hide behind the same
 * wrong number in a test.
 *
 * All names, node numbers and key bytes in test fixtures are made up.
 */
internal object AdminTestFrames {

    /** One decoded field. [value] is a ULong for varint, a UInt for fixed32, a ByteArray for length-delimited. */
    class Field(val number: Int, val wire: Int, val value: Any?) {
        val varint: ULong get() = value as ULong
        val fixed32: UInt get() = value as UInt
        val bytes: ByteArray get() = value as ByteArray
    }

    fun fields(data: ByteArray): List<Field> {
        val r = ProtoReader(data)
        val out = mutableListOf<Field>()
        while (r.hasMore()) {
            val tag = r.readTag() ?: error("truncated tag")
            val value: Any? = when (tag.wire) {
                0 -> r.readVarint()
                5 -> r.readFixed32()
                2 -> r.readLengthDelimited()
                else -> error("unexpected wire type ${tag.wire} for field ${tag.field}")
            }
            out += Field(tag.field, tag.wire, value)
        }
        return out
    }

    fun List<Field>.single(number: Int): Field {
        val matches = filter { it.number == number }
        assertEquals("expected exactly one field $number in ${map { it.number }}", 1, matches.size)
        return matches.single()
    }

    fun List<Field>.has(number: Int) = any { it.number == number }

    // ToRadio { packet = 1 }, MeshPacket { to = 2, decoded = 4, want_ack = 10 }, Data { portnum = 1, payload = 2, want_response = 3 }

    /** A decoded admin ToRadio: the MeshPacket fields, the Data fields and the AdminMessage fields. */
    class Admin(val packet: List<Field>, val data: List<Field>, val admin: List<Field>) {
        val to: UInt get() = packet.single(2).fixed32
        val wantAck: Boolean get() = packet.has(10) && packet.single(10).varint == 1uL
        val wantResponse: Boolean get() = data.has(3) && data.single(3).varint == 1uL
        val portnum: ULong get() = data.single(1).varint

        /** The message inside `set_config` (34): (Config oneof field number, that variant's bytes). */
        fun setConfig(): Pair<Int, ByteArray> {
            val config = fields(admin.single(34).bytes)
            assertEquals("Config carries exactly one variant", 1, config.size)
            return config[0].number to config[0].bytes
        }

        /** The Channel inside `set_channel` (33). */
        fun setChannel(): ByteArray = admin.single(33).bytes

        /** The User inside `set_owner` (32). */
        fun setOwner(): ByteArray = admin.single(32).bytes
    }

    fun decode(toRadio: ByteArray): Admin {
        val top = fields(toRadio)
        assertEquals("ToRadio should carry only the packet", listOf(1), top.map { it.number })
        val packet = fields(top.single(1).bytes)
        val data = fields(packet.single(4).bytes)
        return Admin(packet, data, fields(data.single(2).bytes))
    }

    // region Frames from the radio ---------------------------------------------

    /** FromRadio.my_info = 3 { my_node_num = 1 }. */
    fun myInfoFrame(nodeNum: Int): ByteArray = ProtoMsg().msg(3, ProtoMsg().varint(1, nodeNum.toLong() and 0xFFFFFFFFL)).build()

    /** FromRadio.config_complete_id = 7: the last frame of a config download. */
    fun configCompleteFrame(id: Int = 1): ByteArray = ProtoMsg().varint(7, id).build()

    /** FromRadio.config = 5 { <variant> = message }. */
    fun configFrame(variant: Int, message: ByteArray): ByteArray =
        ProtoMsg().msg(5, ProtoMsg().bytes(variant, message)).build()

    /** FromRadio.channel = 10 { Channel }. */
    fun channelFrame(channel: ByteArray): ByteArray = ProtoMsg().bytes(10, channel).build()

    /** FromRadio.node_info = 4 { num = 1, user = 2 }. */
    fun nodeInfoFrame(nodeNum: Int, user: ByteArray?): ByteArray {
        val info = ProtoMsg().varint(1, nodeNum.toLong() and 0xFFFFFFFFL)
        if (user != null) info.bytes(2, user)
        return ProtoMsg().bytes(4, info.build()).build()
    }

    /**
     * FromRadio.packet = 2 { from = 1, to = 2, decoded = 4 { portnum = 1, payload = 2, request_id = 6 },
     * rx_time = 7, rx_snr = 8, rx_rssi = 12, via_mqtt = 14, hop_start = 15, transport_mechanism = 21 }.
     *
     * The radio's own answer to a local request has a request id and none of the receive fields, which is
     * how it is built by default. The receive fields are there for tests of what the app refuses.
     */
    fun packetFrame(
        from: Int, to: Int, portnum: Int, payload: ByteArray,
        requestId: Int? = null,
        rxTime: Int = 0, rxSnr: Float = 0f, rxRssi: Int = 0, viaMqtt: Boolean = false, hopStart: Int = 0,
        transportMechanism: Int = 0,
    ): ByteArray {
        val data = ProtoMsg().varint(1, portnum).bytes(2, payload)
        if (requestId != null) data.fixed32(6, requestId)
        val packet = ProtoMsg().fixed32(1, from).fixed32(2, to).msg(4, data)
        if (rxTime != 0) packet.fixed32(7, rxTime)
        if (rxSnr != 0f) packet.fixed32(8, java.lang.Float.floatToRawIntBits(rxSnr))
        if (rxRssi != 0) packet.varint(12, rxRssi)
        if (viaMqtt) packet.bool(14, true)
        if (hopStart != 0) packet.varint(15, hopStart)
        if (transportMechanism != 0) packet.varint(21, transportMechanism)
        return ProtoMsg().msg(2, packet).build()
    }

    /** An admin response as the radio sends it: FromRadio.packet carrying `AdminMessage { <field> = message }`. */
    fun adminResponseFrame(
        from: Int, to: Int, adminField: Int, message: ByteArray,
        requestId: Int? = null,
        rxTime: Int = 0, rxSnr: Float = 0f, rxRssi: Int = 0, viaMqtt: Boolean = false, hopStart: Int = 0,
        transportMechanism: Int = 0,
    ): ByteArray = packetFrame(
        from, to, portnum = 6, payload = ProtoMsg().bytes(adminField, message).build(),
        requestId = requestId, rxTime = rxTime, rxSnr = rxSnr, rxRssi = rxRssi, viaMqtt = viaMqtt, hopStart = hopStart,
        transportMechanism = transportMechanism,
    )

    /** The packet id of a ToRadio frame the app sent (MeshPacket.id, field 6): what the radio's answer quotes. */
    fun packetIdOf(toRadio: ByteArray): Int = decode(toRadio).packet.single(6).fixed32.toInt()

    // endregion

    // region Messages as a radio reports them ----------------------------------

    /** 32 made-up key bytes. Never a real key. */
    fun keyBytes(seed: Int = 0xA0): ByteArray = ByteArray(32) { ((seed + it * 3) and 0xFF).toByte() }

    /**
     * A DeviceConfig as a radio reports it: role, rebroadcast mode, node info
     * interval, time zone, LED switch, buzzer mode, and field 99, one this app
     * has never heard of.
     */
    fun deviceConfig(role: Int = 7, rebroadcast: Int = 2): ByteArray = ProtoMsg()
        .varint(1, role)
        .varint(6, rebroadcast)
        .varint(7, 7200)
        .string(11, "PST8PDT,M3.2.0,M11.1.0")
        .bool(12, true)
        .varint(13, 2)
        .bytes(99, byteArrayOf(0x11, 0x22, 0x33))
        .build()

    /** A PositionConfig: interval 900 s, smart broadcast on, GPS mode ENABLED, flags 811, plus unknown field 77. */
    fun positionConfig(secs: Int = 900): ByteArray = ProtoMsg()
        .varint(1, secs)
        .bool(2, true)
        .varint(5, 120)
        .varint(7, 811)
        .varint(10, 150)
        .varint(11, 30)
        .varint(13, 1)
        .fixed32(77, 0x01020304)
        .build()

    /**
     * A LoRaConfig: preset on, modem preset [preset], region [region] (US = 1),
     * hop limit 5, transmit on, boosted gain on, a packed repeated field and two
     * fields this app has never heard of.
     */
    fun loraConfig(preset: Int = 6, region: Int = 1): ByteArray = ProtoMsg()
        .bool(1, true)
        .varint(2, preset)
        .varint(3, 250)
        .varint(4, 11)
        .varint(5, 5)
        .varint(7, region)
        .varint(8, 5)
        .bool(9, true)
        .varint(10, 27)
        .bool(13, true)
        .bytes(103, byteArrayOf(0x05, 0x06, 0x07)) // packed repeated uint32
        .varint(106, 1)
        .fixed32(200, 0x0A0B0C0D)
        .build()

    /** A Channel slot as a radio reports it: key, name, id, uplink and downlink on, module settings, AEAD flag. */
    fun channelMessage(index: Int = 0, name: String = "Alpha", key: ByteArray = keyBytes(), role: Int = 1): ByteArray {
        val settings = ProtoMsg()
            .bytes(2, key)
            .string(3, name)
            .fixed32(4, 0x5A5A1234)
            .bool(5, true)
            .bool(6, true)
            .msg(7, ProtoMsg().varint(1, 13).bool(2, true)) // module_settings { position_precision, is_muted }
            .bool(8, true)
        val ch = ProtoMsg()
        if (index != 0) ch.varint(1, index) // index 0 is the proto3 default and is not on the wire
        return ch.msg(2, settings).varint(3, role).build()
    }

    /** A User as a radio reports it: licensed, explicit is_unmessagable, a key, and an unknown field. */
    fun userMessage(
        longName: String = "Test Node One",
        shortName: String = "TNO",
        licensed: Boolean = true,
    ): ByteArray = ProtoMsg()
        .string(1, "!0a0b0c0d")
        .string(2, longName)
        .string(3, shortName)
        .bytes(4, byteArrayOf(1, 2, 3, 4, 5, 6))
        .varint(5, 37)
        .also { if (licensed) it.bool(6, true) }
        .varint(7, 7)
        .bytes(8, keyBytes(0x40))
        .bool(9, false) // optional bool: present with value false
        .bytes(55, byteArrayOf(0x7F))
        .build()

    // endregion
}
