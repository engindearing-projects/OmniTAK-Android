package soy.engindearing.omnitak.mobile.data.uas

import java.io.ByteArrayOutputStream

/**
 * Splits an H264 Annex B byte stream into NAL units.
 *
 * Annex B framing: each NAL unit is preceded by a start code,
 * either `00 00 01` (3 bytes) or `00 00 00 01` (4 bytes). The NAL
 * payload runs until the next start code.
 *
 * Bytes are fed in via [push]. Each call returns 0+ complete NAL
 * units that were closed by the just-received bytes (i.e. a fresh
 * start code arrived after their payload). The trailing partial
 * NAL is held until the next push; this is correct for live
 * streaming where we never see EOF.
 *
 * Bytes before the very first start code are discarded — they're
 * either decoder garbage or the tail of a NAL we joined mid-stream
 * and can't decode anyway. After that, the splitter locks on and
 * NAL emission is deterministic.
 *
 * The decoder owns emulation-prevention removal; this splitter only
 * needs to find the framing boundaries. Trailing `0x00` cabac
 * padding between NAL units is absorbed into the next start code
 * (treated as the 4-byte form), which is benign for H264 RBSP per
 * the spec.
 */
class H264NalSplitter {
    private val buf = ByteArrayOutputStream()

    /** Length of the leading start code in [buf], or 0 when no start
     *  code has been seen yet. */
    private var headerLen = 0

    /**
     * Feed bytes from the network. Returns NAL unit payloads (no
     * start code prefix) that were finalized by this push.
     */
    fun push(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): List<ByteArray> {
        buf.write(bytes, offset, length)
        return drain()
    }

    /** Drop all buffered state. Use on reconnect / stream restart. */
    fun reset() {
        buf.reset()
        headerLen = 0
    }

    private fun drain(): List<ByteArray> {
        val data = buf.toByteArray()
        val out = mutableListOf<ByteArray>()

        // Where the current in-progress NAL's payload begins in `data`.
        // -1 means we haven't locked onto the first start code yet.
        var payloadStart = if (headerLen > 0) headerLen else -1
        var lastFoundAt = -1
        var lastFoundLen = 0
        var i = if (headerLen > 0) headerLen else 0

        while (i + 2 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                // Walk back over any leading 0x00s so a 4-byte start
                // code (00 00 00 01) is detected as one start code
                // instead of [trailing-pad-of-prev-NAL] + [3-byte-sc].
                val floor = if (payloadStart >= 0) payloadStart else 0
                var actualStart = i
                while (actualStart > floor && data[actualStart - 1] == 0.toByte()) {
                    actualStart--
                }
                val scLen = i + 3 - actualStart

                if (payloadStart >= 0 && actualStart > payloadStart) {
                    out += data.copyOfRange(payloadStart, actualStart)
                }
                lastFoundAt = actualStart
                lastFoundLen = scLen
                payloadStart = i + 3
                i += 3
            } else {
                i++
            }
        }

        when {
            lastFoundAt >= 0 -> {
                // Retain from the last start code onward — the in-progress
                // NAL needs to survive until the next push closes it.
                val tail = data.copyOfRange(lastFoundAt, data.size)
                buf.reset()
                buf.write(tail)
                headerLen = lastFoundLen
            }
            headerLen == 0 && data.size > 3 -> {
                // Still searching for the first start code; retain only
                // the last 3 bytes so a start code straddling chunks is
                // still detectable on the next push.
                val tail = data.copyOfRange(data.size - 3, data.size)
                buf.reset()
                buf.write(tail)
            }
            // else: leading start code + partial payload, no new start
            // code yet — keep buf intact for the next push.
        }
        return out
    }
}
