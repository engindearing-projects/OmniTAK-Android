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
 * Every admin frame it sees is added to [log] by name, so a test can say
 * exactly what the app sent and in what order. All values are made up.
 */
internal class FakeRadio(
    val config: MutableMap<Int, ByteArray> = mutableMapOf(),
    val channels: MutableMap<Int, ByteArray> = mutableMapOf(),
    var owner: ByteArray = ByteArray(0),
) {
    val log = mutableListOf<String>()
    var answers = true
    var inTransaction = false
    var onSetConfig: (variant: Int, radio: FakeRadio) -> Unit = { _, _ -> }

    /** Handle one ToRadio frame. [deliver] gets each AdminMessage the radio answers with. */
    fun handle(frame: ByteArray, deliver: (ByteArray) -> Unit) {
        val admin = AdminTestFrames.decode(frame).admin
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
            1 -> deliver(ProtoMsg().bytes(2, channels[field.varint.toInt() - 1] ?: ByteArray(0)).build())
            3 -> deliver(ProtoMsg().bytes(4, owner).build())
            5 -> {
                val variant = field.varint.toInt() + 1
                deliver(ProtoMsg().msg(6, ProtoMsg().bytes(variant, config[variant] ?: ByteArray(0))).build())
            }
            32 -> owner = applyOwner(owner, field.bytes)
            33 -> {
                val channel = field.bytes
                val f = AdminTestFrames.fields(channel)
                channels[if (f.has(1)) f.single(1).varint.toInt() else 0] = channel
            }
            34 -> {
                val inner = AdminTestFrames.fields(field.bytes).single()
                config[inner.number] = inner.bytes
                onSetConfig(inner.number, this)
            }
            64 -> inTransaction = true
            65 -> inTransaction = false
        }
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
