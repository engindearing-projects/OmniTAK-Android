package soy.engindearing.omnitak.mobile.data.uas

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Plays a raw H264 Annex B byte stream delivered over UDP, decoding
 * through [MediaCodec] directly to an Android [Surface].
 *
 * Wire format (matches RubyFPV "Video Forward To USB Device: Raw H264"):
 *   - one or more H264 NAL units per UDP datagram
 *   - Annex B framing (0x00 0x00 0x00 0x01 or 0x00 0x00 0x01 start codes)
 *   - no RTP header, no MPEG-TS framing, no timestamps
 *   - NAL units may span multiple datagrams
 *
 * Lifecycle: [start] binds the socket and spins up two threads (one
 * for UDP read, one for MediaCodec output rendering). [stop] tears
 * everything down. Safe to call [stop] from any thread; idempotent.
 *
 * Errors are reported via [onError] so the host composable can show
 * a banner instead of crashing the app. Socket bind failure (port in
 * use) is the most common one — the operator can pick a different port.
 */
class RawH264UdpPlayer(
    private val port: Int,
    private val surface: Surface,
    private val onError: (Throwable) -> Unit = {},
    private val onFirstFrame: () -> Unit = {},
) {
    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var codec: MediaCodec? = null
    private var readerThread: Thread? = null
    private var renderThread: Thread? = null
    private val splitter = H264NalSplitter()
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var codecConfigured = false
    @Volatile private var firstFrameRendered = false

    fun start() {
        if (running) return
        running = true
        // Capture the constructor param into a local so the `apply`
        // block below doesn't shadow it with DatagramSocket.getPort()
        // (which returns -1 on an unbound socket and made bind() blow
        // up with "port out of range: -1").
        val bindPort = port
        try {
            val sock = DatagramSocket(null)
            sock.reuseAddress = true
            sock.bind(InetSocketAddress(bindPort))
            sock.soTimeout = 500
            sock.receiveBufferSize = 1 shl 20
            socket = sock
            Log.i(TAG, "bound UDP 0.0.0.0:$bindPort")
        } catch (t: Throwable) {
            running = false
            Log.e(TAG, "bind failed on port $bindPort", t)
            onError(t)
            return
        }
        readerThread = thread(name = "h264-udp-reader-$bindPort", isDaemon = true) { readLoop() }
    }

    fun stop() {
        if (!running) return
        running = false
        try { socket?.close() } catch (_: Throwable) {}
        try { codec?.signalEndOfInputStream() } catch (_: Throwable) {}
        try { codec?.stop() } catch (_: Throwable) {}
        try { codec?.release() } catch (_: Throwable) {}
        codec = null
        readerThread?.interrupt()
        renderThread?.interrupt()
        readerThread = null
        renderThread = null
        splitter.reset()
        sps = null
        pps = null
        codecConfigured = false
    }

    private fun readLoop() {
        val sock = socket ?: return
        val buf = ByteArray(64 * 1024)
        val pkt = DatagramPacket(buf, buf.size)
        while (running) {
            try {
                sock.receive(pkt)
                val nals = splitter.push(buf, 0, pkt.length)
                for (nal in nals) handleNal(nal)
            } catch (_: java.net.SocketTimeoutException) {
                // expected — lets us re-check `running` periodically
            } catch (_: java.net.SocketException) {
                // socket closed during stop(), exit cleanly
                return
            } catch (t: Throwable) {
                Log.w(TAG, "udp read error", t)
                onError(t)
                return
            }
        }
    }

    private fun handleNal(nal: ByteArray) {
        if (nal.isEmpty()) return
        val type = nal[0].toInt() and 0x1F
        when (type) {
            NAL_SPS -> {
                sps = nal
                tryConfigureCodec()
            }
            NAL_PPS -> {
                pps = nal
                tryConfigureCodec()
            }
            NAL_AUD, NAL_SEI -> {
                // Non-VCL — skip. AUD/SEI not required by decoder.
            }
            else -> {
                if (!codecConfigured) return // can't decode VCL until SPS+PPS in hand
                feedDecoder(nal, isKeyFrame = type == NAL_IDR)
            }
        }
    }

    private fun tryConfigureCodec() {
        if (codecConfigured) return
        val s = sps ?: return
        val p = pps ?: return
        try {
            // We must know width/height to configure the decoder. Parse
            // the SPS to extract resolution. If parsing fails, fall back
            // to a sensible default and let the decoder reconfigure
            // itself from in-band SPS — most MediaCodec impls do.
            val dims = SpsParser.parseDimensions(s) ?: Dimensions(1280, 720)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, dims.width, dims.height).apply {
                // csd-0 and csd-1 must include the start code prefix.
                setByteBuffer("csd-0", ByteBuffer.wrap(withStartCode(s)))
                setByteBuffer("csd-1", ByteBuffer.wrap(withStartCode(p)))
            }
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(format, surface, null, 0)
            c.start()
            codec = c
            codecConfigured = true
            renderThread = thread(name = "h264-udp-render-$port", isDaemon = true) { renderLoop() }
            Log.i(TAG, "decoder configured ${dims.width}x${dims.height}")
        } catch (t: Throwable) {
            Log.e(TAG, "decoder configure failed", t)
            onError(t)
        }
    }

    private fun feedDecoder(nal: ByteArray, isKeyFrame: Boolean) {
        val c = codec ?: return
        try {
            val idx = c.dequeueInputBuffer(10_000) // 10 ms
            if (idx < 0) return // drop frame if decoder is stalled — better than backing up
            val input = c.getInputBuffer(idx) ?: return
            input.clear()
            // MediaCodec accepts raw NAL bytes with the start code prefix.
            val framed = withStartCode(nal)
            input.put(framed)
            val flags = if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            c.queueInputBuffer(idx, 0, framed.size, presentationTimeUs(), flags)
        } catch (t: Throwable) {
            Log.w(TAG, "feed error", t)
        }
    }

    private fun renderLoop() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (running) {
            try {
                val idx = c.dequeueOutputBuffer(info, 10_000)
                when {
                    idx >= 0 -> {
                        c.releaseOutputBuffer(idx, true) // render to surface
                        if (!firstFrameRendered) {
                            firstFrameRendered = true
                            try { onFirstFrame() } catch (_: Throwable) {}
                        }
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.i(TAG, "decoder output format: ${c.outputFormat}")
                    }
                    // INFO_TRY_AGAIN_LATER and other negatives: just spin.
                }
            } catch (_: IllegalStateException) {
                // Codec released while we were dequeueing — exit.
                return
            } catch (t: Throwable) {
                Log.w(TAG, "render error", t)
            }
        }
    }

    private fun presentationTimeUs(): Long {
        // No PTS in raw H264. Monotonic clock works for live playback —
        // MediaCodec just renders as fast as it can decode.
        return System.nanoTime() / 1000L
    }

    private fun withStartCode(nal: ByteArray): ByteArray {
        val out = ByteArray(nal.size + 4)
        out[0] = 0; out[1] = 0; out[2] = 0; out[3] = 1
        System.arraycopy(nal, 0, out, 4, nal.size)
        return out
    }

    companion object {
        private const val TAG = "RawH264UdpPlayer"
        private const val NAL_IDR = 5
        private const val NAL_SEI = 6
        private const val NAL_SPS = 7
        private const val NAL_PPS = 8
        private const val NAL_AUD = 9
    }
}

internal data class Dimensions(val width: Int, val height: Int)

/**
 * Minimal SPS parser that extracts the encoded picture dimensions.
 * Just enough to seed [MediaFormat] before configure(); the decoder
 * itself handles the full bitstream.
 *
 * H264 SPS payload after the NAL header byte:
 *   profile_idc (u8), constraint_set_flags (u8), level_idc (u8),
 *   then exp-Golomb fields including pic_width_in_mbs_minus1 and
 *   pic_height_in_map_units_minus1 which give the dimensions in
 *   16-px macroblocks (with adjustments for interlaced + cropping).
 *
 * We deliberately skip cropping/interlace — the decoder will report
 * the true crop rect via [MediaCodec.INFO_OUTPUT_FORMAT_CHANGED] once
 * frames flow.
 */
internal object SpsParser {
    fun parseDimensions(sps: ByteArray): Dimensions? {
        if (sps.size < 4) return null
        return try {
            // Strip NAL header byte + emulation prevention bytes (00 00 03 -> 00 00).
            val rbsp = stripEmulationPrevention(sps, 1)
            val br = BitReader(rbsp)
            val profileIdc = br.readByte()
            br.readByte() // constraint_set_flags
            br.readByte() // level_idc
            br.readUE()   // seq_parameter_set_id

            if (profileIdc in arrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
                val chromaFormatIdc = br.readUE()
                if (chromaFormatIdc == 3) br.readBit() // separate_colour_plane_flag
                br.readUE() // bit_depth_luma_minus8
                br.readUE() // bit_depth_chroma_minus8
                br.readBit() // qpprime_y_zero_transform_bypass_flag
                val seqScalingMatrix = br.readBit()
                if (seqScalingMatrix == 1) {
                    // Skip scaling list — operator video rarely uses it; if it shows up
                    // we'll fall back to the default dims and let MediaCodec reconfigure.
                    return null
                }
            }
            br.readUE() // log2_max_frame_num_minus4
            val pocType = br.readUE()
            when (pocType) {
                0 -> br.readUE() // log2_max_pic_order_cnt_lsb_minus4
                1 -> {
                    br.readBit() // delta_pic_order_always_zero_flag
                    br.readSE() // offset_for_non_ref_pic
                    br.readSE() // offset_for_top_to_bottom_field
                    val numRefFrames = br.readUE()
                    repeat(numRefFrames) { br.readSE() }
                }
            }
            br.readUE() // num_ref_frames
            br.readBit() // gaps_in_frame_num_value_allowed_flag
            val picWidthInMbs = br.readUE() + 1
            val picHeightInMapUnits = br.readUE() + 1
            val frameMbsOnly = br.readBit()
            val height = (2 - frameMbsOnly) * picHeightInMapUnits * 16
            val width = picWidthInMbs * 16
            if (width <= 0 || height <= 0 || width > 8192 || height > 8192) null
            else Dimensions(width, height)
        } catch (_: Throwable) {
            null
        }
    }

    private fun stripEmulationPrevention(data: ByteArray, from: Int): ByteArray {
        val out = ArrayList<Byte>(data.size - from)
        var i = from
        var zeroes = 0
        while (i < data.size) {
            val byte = data[i]
            if (zeroes >= 2 && byte == 0x03.toByte()) {
                zeroes = 0
            } else {
                out.add(byte)
                zeroes = if (byte == 0.toByte()) zeroes + 1 else 0
            }
            i++
        }
        return ByteArray(out.size) { out[it] }
    }
}

internal class BitReader(private val data: ByteArray) {
    private var bytePos = 0
    private var bitPos = 0

    fun readBit(): Int {
        if (bytePos >= data.size) throw IndexOutOfBoundsException()
        val v = (data[bytePos].toInt() ushr (7 - bitPos)) and 1
        bitPos++
        if (bitPos == 8) { bitPos = 0; bytePos++ }
        return v
    }

    fun readByte(): Int {
        var v = 0
        repeat(8) { v = (v shl 1) or readBit() }
        return v
    }

    /** Unsigned exp-Golomb. */
    fun readUE(): Int {
        var leadingZeros = 0
        while (leadingZeros < 32 && readBit() == 0) leadingZeros++
        if (leadingZeros == 0) return 0
        var v = 0
        repeat(leadingZeros) { v = (v shl 1) or readBit() }
        return (1 shl leadingZeros) - 1 + v
    }

    /** Signed exp-Golomb. */
    fun readSE(): Int {
        val v = readUE()
        return if (v and 1 == 1) (v + 1) / 2 else -(v / 2)
    }
}
