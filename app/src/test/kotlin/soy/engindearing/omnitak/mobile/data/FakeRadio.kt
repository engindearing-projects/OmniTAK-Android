package soy.engindearing.omnitak.mobile.data

import soy.engindearing.omnitak.mobile.data.AdminTestFrames.has
import soy.engindearing.omnitak.mobile.data.AdminTestFrames.single

/**
 * A radio as the firmware behaves for admin messages, for tests that need an
 * answer to a read before they can write.
 *
 * - `get_*_request` is answered with the radio's current message, the way the
 *   real one does, unless [answers] is false (a managed radio ignores every
 *   local admin message, reads included).
 * - `set_config` and `set_channel` REPLACE the whole config or channel with
 *   what the frame carries, which is why a write built from one field wipes the
 *   rest. `set_owner` takes the names and the licensed flag from the message
 *   (a missing flag clears it) and keeps the other fields.
 * - [onSetConfig] runs after a `set_config`, to play what the firmware does as
 *   a side effect (a role change installing role defaults).
 *
 * - an edit transaction behaves as in AdminModule.cpp: `begin_edit_settings` opens it, `commit_edit_settings`
 *   saves what is in memory and asks for a restart, and while it is open nothing is saved and nothing restarts. A
 *   client that goes away does not close it. What was written stays in memory ([config], [channels], [owner]) and is
 *   reported as the radio's values, until the radio restarts: then only what was saved is there. A write outside a
 *   transaction is saved at once and asks for a restart.
 * - [countsRestarts] says whether `my_info` carries `reboot_count`: ESP32 firmware counts restarts, every other
 *   radio (nRF52, the simulator) reports 0 every time.
 * - [restart] plays what firmware 2.7.26 does when it loads its config at start and
 *   the position channel is its default channel (NodeDB.cpp, "Enforce position
 *   broadcast minimums"): the position interval is raised to at least one hour,
 *   twelve for a router, and 0 is left alone.
 *
 * Every admin frame it sees is added to [log] by name, so a test can say
 * exactly what the app sent and in what order. All values are made up.
 */
internal class FakeRadio(
    val config: MutableMap<Int, ByteArray> = mutableMapOf(),
    val channels: MutableMap<Int, ByteArray> = mutableMapOf(),
    var owner: ByteArray = ByteArray(0),
) {
    /** Safe to read from the test while the app's coroutines write to the radio. */
    val log: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    var answers = true
    var inTransaction = false

    /** ESP32-like: `my_info` carries the number of times the radio has started. Otherwise it is 0, always. */
    var countsRestarts = false
    var rebootCount = 1

    /** A commit, or a write outside a transaction, saved and asked for a restart that has not happened yet. */
    var restartPending = false

    /** What is saved, once something could differ from it (the first transaction); null until then: memory is saved. */
    private var saved: Saved? = null

    private class Saved(val config: Map<Int, ByteArray>, val channels: Map<Int, ByteArray>, val owner: ByteArray)

    private fun snapshot() = Saved(config.mapValues { it.value.copyOf() }, channels.mapValues { it.value.copyOf() }, owner.copyOf())

    /** The frame of `my_info` this radio sends. */
    fun myInfo(me: Int): ByteArray = AdminTestFrames.myInfoFrame(me, if (countsRestarts) rebootCount else 0)
    var onSetConfig: (variant: Int, radio: FakeRadio) -> Unit = { _, _ -> }

    /**
     * Handle one ToRadio frame. [deliver] gets each AdminMessage the radio answers with, and the id of the
     * request it answers (a radio quotes the packet id of the request in `Data.request_id`).
     */
    fun handle(frame: ByteArray, deliver: (admin: ByteArray, requestId: UInt) -> Unit) {
        val decoded = AdminTestFrames.decode(frame)
        val admin = decoded.admin
        val requestId = decoded.packet.single(6).fixed32
        val field = admin.first()
        val name = when (field.number) {
            1 -> "get_channel:${field.varint.toInt() - 1}"
            3 -> "get_owner"
            5 -> "get_config:${field.varint.toInt() + 1}"
            32 -> "set_owner"
            33 -> "set_channel:${AdminTestFrames.fields(field.bytes).let { f -> if (f.has(1)) f.single(1).varint.toInt() else 0 }}"
            34 -> "set_config:${AdminTestFrames.fields(field.bytes).single().number}"
            64 -> "begin"
            65 -> "commit"
            else -> "admin:${field.number}"
        }
        log += if (answers) name else "ignored $name"
        if (!answers) return

        when (field.number) {
            1 -> deliver(ProtoMsg().bytes(2, channels[field.varint.toInt() - 1] ?: ByteArray(0)).build(), requestId)
            3 -> deliver(ProtoMsg().bytes(4, owner).build(), requestId)
            5 -> {
                val variant = field.varint.toInt() + 1
                deliver(ProtoMsg().msg(6, ProtoMsg().bytes(variant, config[variant] ?: ByteArray(0))).build(), requestId)
            }
            32 -> {
                owner = applyOwner(owner, field.bytes)
                savedUnlessOpen()
            }
            33 -> {
                val channel = field.bytes
                val f = AdminTestFrames.fields(channel)
                channels[if (f.has(1)) f.single(1).varint.toInt() else 0] = channel
                savedUnlessOpen()
            }
            34 -> {
                val inner = AdminTestFrames.fields(field.bytes).single()
                config[inner.number] = inner.bytes
                onSetConfig(inner.number, this)
                savedUnlessOpen()
            }
            64 -> {
                // What is in memory when a transaction opens is what is saved.
                if (saved == null) saved = snapshot()
                inTransaction = true
            }
            65 -> {
                inTransaction = false
                saved = snapshot()
                restartPending = true
            }
        }
    }

    /**
     * What a restart does to the settings (the firmware loads its config again, and the app's link drops).
     *
     * Memory is loaded again from what was saved: a transaction that was never committed, and the changes it held,
     * are gone, and a transaction left open is closed. The restart count goes up on a radio that counts them.
     * Then the position floor is applied.
     *
     * Written from the firmware source, not from the app's own reading of it: the position channel is the first
     * channel whose module settings carry a position precision other than 0; it is the default channel when its key
     * is exactly one byte with the value 1 and its name is the modem preset's display name (an empty name is that
     * name); then `position_broadcast_secs` becomes at least 3600, or 43200 for the roles 2 (ROUTER) and 11
     * (ROUTER_LATE), unless it is 0, and `broadcast_smart_minimum_interval_secs` at least 300, unless it is 0.
     */
    fun restart() {
        // Memory is loaded again from what was saved: a transaction that was never committed, and what it held, is gone.
        saved?.let { s ->
            config.clear(); config.putAll(s.config.mapValues { it.value.copyOf() })
            channels.clear(); channels.putAll(s.channels.mapValues { it.value.copyOf() })
            owner = s.owner.copyOf()
        }
        inTransaction = false
        restartPending = false
        if (countsRestarts) rebootCount++
        applyPositionFloor()
        if (saved != null) saved = snapshot() // the raised interval is saved
    }

    private fun applyPositionFloor() {
        val lora = AdminTestFrames.fields(config[6] ?: ByteArray(0))
        val usePreset = lora.lastOrNull { it.number == 1 }?.varint == 1uL
        val preset = lora.lastOrNull { it.number == 2 }?.varint?.toInt() ?: 0
        val presetName = when {
            !usePreset -> "Custom"
            preset == 0 -> "LongFast"
            preset == 1 -> "LongSlow"
            preset == 3 -> "MediumSlow"
            preset == 4 -> "MediumFast"
            preset == 5 -> "ShortSlow"
            preset == 6 -> "ShortFast"
            preset == 7 -> "LongMod"
            preset == 8 -> "ShortTurbo"
            preset == 9 -> "LongTurbo"
            else -> "Invalid"
        }
        val role = AdminTestFrames.fields(config[1] ?: ByteArray(0)).lastOrNull { it.number == 1 }?.varint?.toInt() ?: 0
        var onDefaultChannel = false
        for (i in 0..7) {
            val settings = AdminTestFrames.fields(channels[i] ?: ByteArray(0)).lastOrNull { it.number == 2 }
                ?.let { AdminTestFrames.fields(it.bytes) } ?: emptyList()
            val module = settings.lastOrNull { it.number == 7 }?.let { AdminTestFrames.fields(it.bytes) } ?: emptyList()
            if ((module.lastOrNull { it.number == 1 }?.varint ?: 0uL) == 0uL) continue
            val key = settings.lastOrNull { it.number == 2 }?.bytes
            val name = settings.lastOrNull { it.number == 3 }?.bytes?.toString(Charsets.UTF_8) ?: ""
            onDefaultChannel = key != null && key.size == 1 && key[0] == 1.toByte() && (name.ifEmpty { presetName } == presetName)
            break
        }
        if (!onDefaultChannel) return
        val floor = if (role == 2 || role == 11) 43_200 else 3_600
        config[2]?.let { config[2] = raised(raised(it, 1, floor), 11, 300) }
    }

    /** [message] with the varint field [number] raised to at least [minimum]; a field that is 0 or absent stays as it is. */
    private fun raised(message: ByteArray, number: Int, minimum: Int): ByteArray {
        val out = ProtoMsg()
        for (f in AdminTestFrames.fields(message)) when {
            f.number == number && f.varint != 0uL -> out.varint(number, maxOf(f.varint.toLong(), minimum.toLong()))
            f.wire == 0 -> out.varint(f.number, f.varint.toLong())
            f.wire == 5 -> out.fixed32(f.number, f.fixed32.toInt())
            else -> out.bytes(f.number, f.bytes)
        }
        return reorder(out.build())
    }

    /**
     * What the radio sends when an app connects: my_info, its own node info, the eight channels, the configs,
     * and config_complete_id last. [me] is its node number.
     */
    fun download(me: Int): List<ByteArray> = buildList {
        add(myInfo(me))
        add(AdminTestFrames.nodeInfoFrame(me, owner))
        for (i in 0..7) add(AdminTestFrames.channelFrame(channels.getValue(i)))
        for (variant in 1..10) add(AdminTestFrames.configFrame(variant, config[variant] ?: ByteArray(0)))
        add(AdminTestFrames.configCompleteFrame())
    }

    /** AdminModule::saveChanges: outside a transaction a write is saved and the radio restarts; inside one, neither happens. */
    private fun savedUnlessOpen() {
        if (inTransaction) return
        if (saved != null) saved = snapshot()
        restartPending = true
    }

    /** What the firmware does with a set_owner: names and the licensed flag come from the message, the rest stays. */
    private fun applyOwner(current: ByteArray, message: ByteArray): ByteArray {
        val have = AdminTestFrames.fields(current)
        val sent = AdminTestFrames.fields(message)
        fun text(n: Int): String? = sent.lastOrNull { it.number == n }?.bytes?.toString(Charsets.UTF_8)?.takeIf { it.isNotEmpty() }
        val out = ProtoMsg()
        for (f in have.sortedBy { it.number }) {
            when (f.number) {
                2 -> out.string(2, text(2) ?: f.bytes.toString(Charsets.UTF_8))
                3 -> out.string(3, text(3) ?: f.bytes.toString(Charsets.UTF_8))
                6 -> Unit // is_licensed is assigned from the message below
                else -> when (f.wire) {
                    0 -> out.varint(f.number, f.varint.toLong())
                    5 -> out.fixed32(f.number, f.fixed32.toInt())
                    else -> out.bytes(f.number, f.bytes)
                }
            }
        }
        val licensed = sent.lastOrNull { it.number == 6 }?.varint == 1uL
        if (licensed) out.bool(6, true)
        return reorder(out.build())
    }

    private fun reorder(message: ByteArray): ByteArray {
        val fields = AdminTestFrames.fields(message).sortedBy { it.number }
        val out = ProtoMsg()
        for (f in fields) when (f.wire) {
            0 -> out.varint(f.number, f.varint.toLong())
            5 -> out.fixed32(f.number, f.fixed32.toInt())
            else -> out.bytes(f.number, f.bytes)
        }
        return out.build()
    }

    companion object {
        /**
         * The radio as the stock image comes: the primary channel on the default key (one byte, 1), unnamed, with
         * position precision 13, LONG_FAST, region US and a position interval of one hour. Everything else as in
         * [factory].
         */
        fun stock(): FakeRadio = factory().also { radio ->
            radio.channels[0] = defaultChannel()
            radio.config[2] = ProtoMsg().varint(1, 3_600).varint(7, 811).varint(11, 300).varint(13, 1).build()
            radio.config[6] = ProtoMsg().bool(1, true).varint(7, 1).varint(8, 5).bool(9, true).build()
        }

        /** Channel 0 on the default key, named [name] (empty: the preset's name), with position precision [precision]. */
        fun defaultChannel(name: String = "", precision: Int = 13): ByteArray {
            val settings = ProtoMsg().bytes(2, byteArrayOf(1))
            if (name.isNotEmpty()) settings.string(3, name)
            if (precision != 0) settings.msg(7, ProtoMsg().varint(1, precision.toLong()))
            return ProtoMsg().msg(2, settings).varint(3, 1).build()
        }

        /**
         * A radio with nothing set up: role CLIENT (absent on the wire), the primary channel unnamed with a
         * 32 byte key and position precision 13, region US, hop limit 5, position interval 900, a time zone
         * and the LED switch set, owner "Sim Radio One" / "SR1".
         */
        fun factory(): FakeRadio = FakeRadio(
            config = mutableMapOf(
                1 to ProtoMsg().varint(7, 7200).string(11, "PST8PDT,M3.2.0,M11.1.0").bool(12, true).build(),
                2 to ProtoMsg().varint(1, 900).varint(7, 811).varint(13, 1).build(),
                6 to ProtoMsg().bool(1, true).varint(7, 1).varint(8, 5).bool(9, true).build(),
            ),
            channels = (0..7).associateWith { i ->
                if (i == 0) {
                    ProtoMsg()
                        .msg(2, ProtoMsg().bytes(2, AdminTestFrames.keyBytes(0x61)).msg(7, ProtoMsg().varint(1, 13)))
                        .varint(3, 1)
                        .build()
                } else {
                    ProtoMsg().varint(1, i).build() // a disabled slot: no settings, no role
                }
            }.toMutableMap(),
            owner = ProtoMsg()
                .string(1, "!0a0b0c0d").string(2, "Sim Radio One").string(3, "SR1")
                .varint(5, 37).bytes(8, AdminTestFrames.keyBytes(0x40)).bool(9, false)
                .build(),
        )
    }
}
