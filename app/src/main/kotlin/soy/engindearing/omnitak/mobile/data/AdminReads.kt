package soy.engindearing.omnitak.mobile.data

import kotlinx.coroutines.CompletableDeferred
import soy.engindearing.omnitak.mobile.data.RadioSettingsCache.Key
import java.security.SecureRandom

/**
 * What the app requires of a read-back before it believes it.
 *
 * Everything a settings write patches comes from the radio's own answer to a read, so what counts as that
 * answer matters. An admin message is taken as a read-back only when all of these hold:
 *
 *  1. it is from our own node;
 *  2. it carries none of the marks a radio puts on a packet that came in from outside: a signal strength
 *     (`rx_rssi`), a signal to noise ratio (`rx_snr`), `via_mqtt`, or a `transport_mechanism` other than 0.
 *     An answer the radio makes to a local request has none of them;
 *  3. it answers a request this app sent: `Data.request_id` is the packet id of a read that is still
 *     outstanding, and the answer is for the entry that read asked for (the same config type, the same channel
 *     index, or the owner).
 *
 * Each read has its own random, non-zero packet id and a deadline, and is answered once. A reused id, an
 * expired id, an id that was asked for a different entry, and a message with no id are all ignored.
 * `rx_time` and `hop_start` are deliberately not part of the rule. A radio with a clock puts `rx_time` on its own
 * answers, and what other firmware puts in either field on a packet it makes itself was not measured, so a rule
 * that used them could refuse every answer. The request id is what carries the weight.
 *
 * Pure Kotlin, safe to call from any thread.
 */
class AdminReads(
    private val clock: () -> Long = MONOTONIC_MS,
    private val nextId: () -> Int = { SECURE.nextInt() },
) {

    /** One outstanding read. */
    class Request internal constructor(
        val id: UInt,
        val key: Key,
        internal val deadline: Long,
        awaited: Boolean,
    ) {
        /** Completed with the radio's answer when a writer waits for this read. Null for a read nobody waits for. */
        val answer: CompletableDeferred<ByteArray>? = if (awaited) CompletableDeferred() else null
    }

    /** Why an admin message was or was not taken as a read-back. [reason] is what the log says, never a payload. */
    enum class Admission(val reason: String) {
        ACCEPTED("accepted"),
        NOT_OUR_NODE("it is not from our node"),
        HAS_RX_RSSI("it carries a receive signal strength (rx_rssi)"),
        HAS_RX_SNR("it carries a receive signal to noise ratio (rx_snr)"),
        VIA_MQTT("it came in through MQTT (via_mqtt)"),
        HAS_TRANSPORT("it names a transport other than the radio's own (transport_mechanism)"),
        NO_REQUEST_ID("it answers no request (no request id)"),
        NOT_A_READ_BACK("it is not a settings read-back"),
        UNKNOWN_REQUEST("its request id is not one of ours that is waiting"),
        EXPIRED("its request id has expired"),
        WRONG_ENTRY("its request id was asked for a different entry"),
    }

    /** The outcome of [admit]. [answer] and [request] are set only when [admission] is [Admission.ACCEPTED]. */
    class Decision internal constructor(
        val admission: Admission,
        val answer: AdminAnswer? = null,
        val request: Request? = null,
    ) {
        /** Hand the answer to the writer waiting for it, if there is one. Call after the answer is stored. */
        fun deliver() {
            val bytes = answer?.bytes ?: return
            request?.answer?.complete(bytes)
        }
    }

    private val outstanding = HashMap<UInt, Request>()

    /** A new read of [key]: a fresh random non-zero packet id, outstanding until it is answered or [timeoutMs] pass. */
    @Synchronized
    fun open(key: Key, timeoutMs: Long, awaited: Boolean = false): Request {
        val now = clock()
        outstanding.values.removeAll { now > it.deadline }
        var id: UInt
        do {
            id = nextId().toUInt()
        } while (id == 0u || id in outstanding)
        return Request(id, key, now + timeoutMs, awaited).also { outstanding[id] = it }
    }

    /** Forget a read that will not be answered (the send failed, or the wait ran out). */
    @Synchronized
    fun cancel(id: UInt) {
        outstanding.remove(id)
    }

    /** Forget every read: the link they were sent on is gone. */
    @Synchronized
    fun clear() {
        outstanding.clear()
    }

    /** How many reads are waiting. */
    val size: Int @Synchronized get() = outstanding.size

    /**
     * Decide whether [packet], an ADMIN_APP packet the radio handed to the phone, is the radio's own answer to one
     * of our reads. [myNodeNum] is the number the radio reported in `my_info`. An accepted answer uses up its read.
     */
    fun admit(packet: MeshPacketDecoded, myNodeNum: UInt?): Decision {
        if (myNodeNum == null || packet.from != myNodeNum) return Decision(Admission.NOT_OUR_NODE)
        if ((packet.rxRssi ?: 0) != 0) return Decision(Admission.HAS_RX_RSSI)
        // Any non-zero bit pattern counts, a negative zero included.
        if ((packet.rxSnr?.toRawBits() ?: 0) != 0) return Decision(Admission.HAS_RX_SNR)
        if (packet.viaMqtt) return Decision(Admission.VIA_MQTT)
        if (packet.transportMechanism != 0) return Decision(Admission.HAS_TRANSPORT)
        val id = packet.requestId
        if (id == null || id == 0u) return Decision(Admission.NO_REQUEST_ID)
        val answer = AdminAnswer.parse(packet.payload) ?: return Decision(Admission.NOT_A_READ_BACK)
        return take(id, answer)
    }

    @Synchronized
    private fun take(id: UInt, answer: AdminAnswer): Decision {
        val request = outstanding[id] ?: return Decision(Admission.UNKNOWN_REQUEST)
        if (clock() > request.deadline) {
            outstanding.remove(id)
            return Decision(Admission.EXPIRED)
        }
        // Not used up: the answer this id is waiting for may still come.
        if (request.key != answer.key) return Decision(Admission.WRONG_ENTRY)
        outstanding.remove(id)
        return Decision(Admission.ACCEPTED, answer, request)
    }

    companion object {
        private val SECURE = SecureRandom()

        /** Milliseconds on a monotonic clock: deadlines do not move when the wall clock does. */
        val MONOTONIC_MS: () -> Long = { System.nanoTime() / 1_000_000L }
    }
}

/**
 * The one settings entry an `AdminMessage` read-back carries: which entry it is, and the entry's own bytes
 * (the Config variant's message, the whole Channel, the User).
 */
class AdminAnswer(val key: Key, val bytes: ByteArray) {

    companion object {
        // AdminMessage (admin.proto): the three responses that carry a settings message.
        private const val GET_CHANNEL_RESPONSE = 2
        private const val GET_OWNER_RESPONSE = 4
        private const val GET_CONFIG_RESPONSE = 6
        private const val CHANNEL_INDEX = 1 // Channel.index

        /**
         * The entry a `get_channel_response`, `get_owner_response` or `get_config_response` carries. Null for
         * anything else: a message that is damaged, carries no such response or more than one, a channel index
         * past the last slot, or a Config that is not exactly one of the variants the app keeps.
         */
        fun parse(admin: ByteArray): AdminAnswer? {
            val fields = ProtoFields.parse(admin) ?: return null
            var found: AdminAnswer? = null
            for (field in fields) {
                val inner = field.bytes() ?: continue
                val answer = when (field.number) {
                    GET_CHANNEL_RESPONSE -> channel(inner)
                    GET_OWNER_RESPONSE -> AdminAnswer(Key.Owner, inner).takeIf { ProtoFields.parse(inner) != null }
                    GET_CONFIG_RESPONSE -> config(inner)
                    else -> continue
                } ?: return null
                if (found != null) return null
                found = answer
            }
            return found
        }

        private fun channel(bytes: ByteArray): AdminAnswer? {
            val fields = ProtoFields.parse(bytes) ?: return null
            // Channel.index is a proto3 int32: slot 0 is not on the wire at all.
            val index = ProtoFields.lastVarint(fields, CHANNEL_INDEX) ?: 0uL
            if (index >= RadioSettingsCache.MAX_CHANNELS.toULong()) return null
            return AdminAnswer(Key.Channel(index.toInt()), bytes)
        }

        private fun config(bytes: ByteArray): AdminAnswer? {
            val fields = ProtoFields.parse(bytes) ?: return null
            val variant = fields.singleOrNull() ?: return null
            if (variant.number !in RadioSettingsCache.PATCHED_CONFIGS) return null
            val inner = variant.bytes() ?: return null
            if (ProtoFields.parse(inner) == null) return null
            return AdminAnswer(Key.Config(variant.number), inner)
        }
    }
}
