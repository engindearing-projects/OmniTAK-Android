package soy.engindearing.omnitak.mobile.data.uas

/**
 * Where the live drone camera stream is coming from.
 *
 *  - [None]: no video. PIP not rendered.
 *  - [Rtsp]: RTSP URL the operator configured. Decoded by Media3
 *    ExoPlayer + RtspMediaSource, RTP-over-TCP forced (see
 *    UasVideoPip for the rationale).
 *  - [RawH264Udp]: raw H264 Annex B NAL units delivered as UDP
 *    datagrams to [port] on any local interface. Compatible with
 *    RubyFPV "Video Forward To USB Device: Raw (H264)" and any
 *    similar long-range FPV ground station that doesn't expose RTSP.
 *    Decoded by MediaCodec directly.
 *  - [MpegTsUdp]: H264 elementary stream wrapped in MPEG-TS, delivered
 *    as UDP datagrams to [port]. This is what ATAK UAS Tool consumes
 *    and what RubyFPV operators produce via the canonical gstreamer
 *    pipeline (h264parse → mpegtsmux → udpsink). Decoded by Media3
 *    ExoPlayer + UdpDataSource + TsExtractor.
 */
sealed class VideoSource {
    object None : VideoSource()
    data class Rtsp(val url: String) : VideoSource()
    data class RawH264Udp(val port: Int) : VideoSource()
    data class MpegTsUdp(val port: Int) : VideoSource()

    val isActive: Boolean get() = this !is None

    companion object {
        fun rtsp(url: String): VideoSource =
            if (url.isBlank()) None else Rtsp(url.trim())

        fun rawH264Udp(port: Int): VideoSource =
            if (port in 1..65535) RawH264Udp(port) else None

        fun mpegTsUdp(port: Int): VideoSource =
            if (port in 1..65535) MpegTsUdp(port) else None
    }
}
