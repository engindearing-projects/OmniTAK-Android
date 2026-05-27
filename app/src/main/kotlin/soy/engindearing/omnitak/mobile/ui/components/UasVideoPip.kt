package soy.engindearing.omnitak.mobile.ui.components

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CameraFront
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.UdpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.ui.PlayerView
import soy.engindearing.omnitak.mobile.data.uas.RawH264UdpPlayer
import soy.engindearing.omnitak.mobile.data.uas.VideoSource

/**
 * Picture-in-picture live video for the drone camera. Two source
 * modes are supported (see [VideoSource]):
 *
 *  - **RTSP** (default): Media3 ExoPlayer + RtspMediaSource. URL is
 *    operator-supplied. RTP-over-TCP forced — UDP/RTP works on a LAN
 *    but breaks through SSH tunnels (no UDP forwarding) and through
 *    many home routers (inter-VLAN UDP block); TCP is universally
 *    compatible, ~10 ms more latency, fine for ops video.
 *  - **Raw H264 UDP**: receives Annex B NAL units over a UDP port and
 *    decodes via MediaCodec direct-to-Surface. Used by RubyFPV and
 *    similar long-range FPV ground stations that don't speak RTSP.
 *
 * Layout:
 *  - Compact (default): 160 × 90 dp 16:9 box, bottom-right of the map.
 *  - Expanded: 320 × 180 dp. Tap fullscreen icon to toggle.
 *  - Close icon tears down the player and dismisses the PIP for this
 *    session (caller is responsible for re-showing on URL change).
 */
@Composable
fun UasVideoPip(
    source: VideoSource,
    onDismiss: () -> Unit,
    onPhoto: () -> Unit = {},
    onToggleRecord: (Boolean) -> Unit = {},
    onGimbalForward: () -> Unit = {},
    onGimbalNadir: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (!source.isActive) return
    var expanded by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    val widthDp = if (expanded) 320.dp else 160.dp

    Box(
        modifier = modifier
            .width(widthDp)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black),
    ) {
        when (source) {
            is VideoSource.Rtsp -> RtspSurface(source.url, Modifier.fillMaxSize())
            is VideoSource.RawH264Udp -> RawH264UdpSurface(source.port, Modifier.fillMaxSize())
            is VideoSource.MpegTsUdp -> MpegTsUdpSurface(source.port, Modifier.fillMaxSize())
            is VideoSource.None -> Unit
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFFFF3B30))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = "LIVE",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        if (recording) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 30.dp, start = 8.dp)
                    .size(8.dp)
                    .background(Color(0xFFFF3B30), shape = androidx.compose.foundation.shape.CircleShape),
            )
        }

        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(28.dp)) {
                Icon(
                    if (expanded) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    contentDescription = "Toggle size",
                    tint = Color.White,
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }

        if (expanded) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                IconButton(onClick = onPhoto, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = "Capture photo", tint = Color.White)
                }
                IconButton(
                    onClick = {
                        recording = !recording
                        onToggleRecord(recording)
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        if (recording) Icons.Filled.StopCircle else Icons.Filled.Videocam,
                        contentDescription = if (recording) "Stop recording" else "Start recording",
                        tint = if (recording) Color(0xFFFF3B30) else Color.White,
                    )
                }
                IconButton(onClick = onGimbalForward, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.CameraFront, contentDescription = "Gimbal forward (0°)", tint = Color.White)
                }
                IconButton(onClick = onGimbalNadir, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.PlayCircle, contentDescription = "Gimbal nadir (-90°)", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun RtspSurface(rtspUrl: String, modifier: Modifier = Modifier) {
    if (rtspUrl.isBlank()) return
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val player = remember(rtspUrl) {
        ExoPlayer.Builder(context).build().apply {
            val factory = RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .setTimeoutMs(8_000)
            setMediaSource(factory.createMediaSource(MediaItem.fromUri(rtspUrl)))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) { onDispose { player.release() } }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> player.pause()
                Lifecycle.Event.ON_START -> player.play()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                this.player = player
            }
        },
        update = { it.player = player },
    )
}

@Composable
private fun RawH264UdpSurface(port: Int, modifier: Modifier = Modifier) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val playerHolder = remember(port) { arrayOfNulls<RawH264UdpPlayer>(1) }
    var lastError by remember(port) { mutableStateOf<String?>(null) }

    DisposableEffect(port) {
        onDispose {
            playerHolder[0]?.stop()
            playerHolder[0] = null
        }
    }

    DisposableEffect(lifecycleOwner, port) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    playerHolder[0]?.stop()
                    playerHolder[0] = null
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    // MapLibre owns the primary SurfaceView; additional
                    // SurfaceViews go behind it by default. Z-order this
                    // one as a media overlay so the video PIP composes
                    // above the map.
                    setZOrderMediaOverlay(true)
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            playerHolder[0]?.stop()
                            val player = RawH264UdpPlayer(
                                port = port,
                                surface = holder.surface,
                                onError = { e -> lastError = e.message ?: e::class.simpleName },
                            )
                            playerHolder[0] = player
                            player.start()
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            playerHolder[0]?.stop()
                            playerHolder[0] = null
                        }
                    })
                }
            },
        )
        val err = lastError
        if (err != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                Text(
                    text = "UDP $port — $err",
                    color = Color(0xFFFF6B6B),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/**
 * MPEG-TS over UDP video receiver. Wraps Media3 ExoPlayer with a
 * [UdpDataSource] data source and a [TsExtractor] so an ATAK-UAS-Tool /
 * RubyFPV gstreamer pipeline ("h264parse → mpegtsmux → udpsink") plays
 * directly with no glue on the operator's side.
 *
 * Load control is tightened from the ExoPlayer defaults — for a live
 * UDP feed we want ~50 ms target buffer, not the 2 s default — so the
 * end-to-end latency stays under 200 ms when the network is clean.
 */
@Composable
private fun MpegTsUdpSurface(port: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val player = remember(port) {
        val loadControl = DefaultLoadControl.Builder()
            // (minBufferMs, maxBufferMs, bufferForPlaybackMs, bufferForPlaybackAfterRebufferMs)
            // Aggressive low-latency tuning — drop frames over rebuffering.
            .setBufferDurationsMs(50, 250, 50, 50)
            .build()
        val udpFactory = DataSource.Factory {
            // UdpDataSource(maxPacketSize) — 4096 is enough for MPEG-TS
            // packets (typically 188 × N per datagram, max 1500 MTU).
            UdpDataSource(/* maxPacketSize = */ 4096)
        }
        val extractorsFactory = DefaultExtractorsFactory()
            .setTsExtractorMode(TsExtractor.MODE_SINGLE_PMT)
            // Allow non-IDR keyframes — RubyFPV / gst pipelines often
            // emit MPEG-TS without strict IDR markers, and ExoPlayer's
            // default behavior is to refuse to start playback until it
            // sees one. With this flag it accepts any I-slice as the
            // first decodable frame.
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
        val mediaSource = ProgressiveMediaSource.Factory(udpFactory, extractorsFactory)
            .createMediaSource(MediaItem.fromUri("udp://0.0.0.0:$port"))
        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
            .apply {
                setMediaSource(mediaSource)
                prepare()
                playWhenReady = true
            }
    }

    DisposableEffect(player) { onDispose { player.release() } }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> player.pause()
                Lifecycle.Event.ON_START -> player.play()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                this.player = player
            }
        },
        update = { it.player = player },
    )
}
