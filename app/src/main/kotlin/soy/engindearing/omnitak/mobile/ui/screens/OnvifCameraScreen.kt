package soy.engindearing.omnitak.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch
import soy.engindearing.omnitak.mobile.data.onvif.OnvifClient
import soy.engindearing.omnitak.mobile.ui.theme.HostileRed
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent
import soy.engindearing.omnitak.mobile.ui.theme.TacticalBackground
import soy.engindearing.omnitak.mobile.ui.theme.TacticalSurface

/**
 * ONVIF PTZ camera control. Connect to an ONVIF-compliant IP camera
 * (mast cam, vehicle PTZ, fixed install), pull its RTSP feed, and drive
 * pan/tilt/zoom from a joystick pad. ATAK needs a plugin for this;
 * OmniTAK does it natively.
 *
 * MVP: connect → live video + PTZ pad. Presets, absolute moves, and
 * placing the camera as a CoT marker are fast-follows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnvifCameraScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var host by remember { mutableStateOf("192.168.1.") }
    var portText by remember { mutableStateOf("80") }
    var user by remember { mutableStateOf("admin") }
    var pass by remember { mutableStateOf("") }

    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var session by remember { mutableStateOf<OnvifClient.Session?>(null) }
    var client by remember { mutableStateOf<OnvifClient?>(null) }

    // ExoPlayer for the RTSP feed — built when a session lands.
    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }

    Scaffold(
        containerColor = TacticalBackground,
        topBar = {
            TopAppBar(
                title = { Text("ONVIF Camera") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TacticalBackground,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { inner: PaddingValues ->
        // #207 — the full-width 16:9 video box plus the PTZ pad is taller
        // than a landscape phone viewport; scroll keeps the PTZ controls and
        // the Connect / Disconnect buttons reachable instead of clipped.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val sess = session
            if (sess == null) {
                Text(
                    "Connect a PTZ ONVIF-compliant IP camera. OmniTAK pulls its RTSP feed and drives pan/tilt/zoom. Works with mast cams, vehicle gimbals, and fixed installs.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                )
                TextField(host, { host = it.trim() }, label = { Text("Camera IP") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextField(portText, { portText = it.filter(Char::isDigit) },
                        label = { Text("ONVIF port") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    TextField(user, { user = it }, label = { Text("Username") },
                        singleLine = true, modifier = Modifier.weight(1.5f))
                }
                TextField(pass, { pass = it }, label = { Text("Password") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                error?.let { Text(it, color = HostileRed, style = MaterialTheme.typography.bodySmall) }
                Button(
                    onClick = {
                        connecting = true; error = null
                        val c = OnvifClient(host.trim(), portText.toIntOrNull() ?: 80, user.trim(), pass)
                        client = c
                        scope.launch {
                            runCatching { c.connect() }
                                .onSuccess { s ->
                                    session = s
                                    val src = RtspMediaSource.Factory()
                                        .setForceUseRtpTcp(true)
                                        .createMediaSource(MediaItem.fromUri(s.rtspUri))
                                    player.setMediaSource(src); player.prepare(); player.playWhenReady = true
                                }
                                .onFailure { error = "Connect failed: ${it.message}" }
                            connecting = false
                        }
                    },
                    enabled = !connecting && host.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = TacticalAccent, contentColor = TacticalBackground),
                ) { Text(if (connecting) "Connecting…" else "Connect") }
            } else {
                // -------- Live video --------
                Box(
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(8.dp)).background(Color.Black),
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { PlayerView(it).apply { useController = false; this.player = player } },
                        update = { it.player = player },
                    )
                    Box(
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                            .clip(RoundedCornerShape(4.dp)).background(Color(0xFFFF3B30))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) { Text("LIVE", color = Color.White, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace) }
                }
                Text("${sess.profileName}  •  ${if (sess.hasPtz) "PTZ ready" else "no PTZ on this profile"}",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)

                if (sess.hasPtz) {
                    PtzPad(
                        onMove = { p, t, z -> scope.launch { client?.continuousMove(sess.profileToken, p, t, z) } },
                        onStop = { scope.launch { client?.stop(sess.profileToken) } },
                    )
                }
                Button(
                    onClick = { player.stop(); session = null; client = null },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = TacticalSurface),
                ) { Text("Disconnect", color = HostileRed) }
            }
        }
    }
}

/**
 * PTZ joystick pad: a D-pad (pan/tilt) + zoom row. Each direction fires
 * ContinuousMove on press and Stop on release — the standard hold-to-
 * move PTZ pattern.
 */
@Composable
private fun PtzPad(
    onMove: (pan: Float, tilt: Float, zoom: Float) -> Unit,
    onStop: () -> Unit,
) {
    val speed = 0.6f
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PtzBtn(Icons.Filled.KeyboardArrowUp, "Tilt up", onStop) { onMove(0f, speed, 0f) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PtzBtn(Icons.Filled.KeyboardArrowLeft, "Pan left", onStop) { onMove(-speed, 0f, 0f) }
            PtzBtn(Icons.Filled.KeyboardArrowDown, "Tilt down", onStop) { onMove(0f, -speed, 0f) }
            PtzBtn(Icons.Filled.KeyboardArrowRight, "Pan right", onStop) { onMove(speed, 0f, 0f) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            PtzBtn(Icons.Filled.Add, "Zoom in", onStop) { onMove(0f, 0f, speed) }
            PtzBtn(Icons.Filled.Remove, "Zoom out", onStop) { onMove(0f, 0f, -speed) }
        }
    }
}

@Composable
private fun PtzBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onRelease: () -> Unit,
    onPress: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Color(0xFF1E2A38))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onPress()
                        // Block until the finger lifts / gesture cancels,
                        // then issue Stop — classic press-and-hold PTZ.
                        tryAwaitRelease()
                        onRelease()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = desc, tint = TacticalAccent, modifier = Modifier.size(30.dp))
    }
}
