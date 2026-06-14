package soy.engindearing.omnitak.mobile.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import soy.engindearing.omnitak.mobile.OmniTAKApp
import soy.engindearing.omnitak.mobile.data.CSREnrollmentService
import soy.engindearing.omnitak.mobile.data.ConnectionProtocol
import soy.engindearing.omnitak.mobile.data.DeepLinkImport
import soy.engindearing.omnitak.mobile.data.ImportedServerConfig
import soy.engindearing.omnitak.mobile.data.TAKServer
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent
import soy.engindearing.omnitak.mobile.ui.theme.TacticalBackground
import java.util.concurrent.Executors

private const val TAG = "ServerQrScan"

/**
 * #100 — full-screen camera QR scanner for server onboarding.
 *
 * Reuses the exact CameraX + MLKit pipeline proven by the config-profile
 * scanner in [ProfilesScreen.QrScannerDialog], lifted here as a standalone
 * screen so the server-enrollment flow doesn't depend on a profile-only,
 * `private` dialog. The full-screen treatment keeps the sacred map intact —
 * this is its own route, never a panel compressing the map.
 *
 * Decoding is delegated to [accept]: the caller decides which QR shapes are
 * valid (e.g. a `tak://…/enroll` enrollment link) so this stays generic. On
 * the first accepted QR we fire [onScanned] once and stop analyzing.
 *
 * @param accept returns true if the decoded [Uri] is one this scan flow
 *   should consume. Keeps the camera running on unrelated QR codes.
 * @param onScanned invoked once with the first accepted [Uri].
 * @param onDismiss user backed out without scanning.
 */
@OptIn(ExperimentalGetImage::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerQrScanScreen(
    accept: (Uri) -> Boolean,
    onScanned: (Uri) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Camera permission gate — request on first composition if not already held.
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> cameraGranted = granted }
    LaunchedEffect(Unit) {
        if (!cameraGranted) permLauncher.launch(Manifest.permission.CAMERA)
    }

    Scaffold(
        containerColor = TacticalBackground,
        topBar = {
            TopAppBar(
                title = { Text("Scan enrollment QR", color = MaterialTheme.colorScheme.onBackground) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close scanner",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = TacticalBackground),
            )
        },
    ) { inner: PaddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            if (!cameraGranted) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Camera permission is required to scan an enrollment QR code.",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { permLauncher.launch(Manifest.permission.CAMERA) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TacticalAccent,
                            contentColor = TacticalBackground,
                        ),
                    ) { Text("Grant camera access") }
                }
                return@Box
            }

            val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
            val executor = remember { Executors.newSingleThreadExecutor() }
            var scanned by remember { mutableStateOf(false) }
            val barcodeScanner = remember { BarcodeScanning.getClient() }

            DisposableEffect(Unit) {
                onDispose {
                    barcodeScanner.close()
                    executor.shutdown()
                }
            }

            // Full-bleed camera preview.
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    cameraProviderFuture.addListener({
                        val provider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(executor) { imageProxy ->
                            if (scanned) { imageProxy.close(); return@setAnalyzer }
                            val mediaImage = imageProxy.image
                            if (mediaImage != null) {
                                val inputImage = InputImage.fromMediaImage(
                                    mediaImage,
                                    imageProxy.imageInfo.rotationDegrees,
                                )
                                barcodeScanner.process(inputImage)
                                    .addOnSuccessListener { barcodes ->
                                        val qr = barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }
                                        val raw = qr?.rawValue
                                        if (!raw.isNullOrBlank() && !scanned) {
                                            val uri = runCatching { Uri.parse(raw) }.getOrNull()
                                            if (uri != null && accept(uri)) {
                                                scanned = true
                                                onScanned(uri)
                                            }
                                        }
                                    }
                                    .addOnCompleteListener { imageProxy.close() }
                            } else {
                                imageProxy.close()
                            }
                        }
                        runCatching {
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis,
                            )
                        }.onFailure { Log.e(TAG, "CameraX bind failed", it) }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                modifier = Modifier.fillMaxSize(),
            )

            // Reticle + hint overlay.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(2.dp, TacticalAccent, RoundedCornerShape(16.dp)),
                )
                Spacer(Modifier.height(20.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(TacticalBackground.copy(alpha = 0.7f))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        "Point at a TAK / ATAK enrollment QR code",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * #100 — drop-in route that pairs [ServerQrScanScreen] with the server
 * onboarding flow. Accepts the standard ATAK / TAK Server / ArgusTAK
 * enrollment QR (`tak://…/enroll`) plus our existing connect-form links, then:
 *
 *  - enrollment link → CSR-enroll a client cert from the server's
 *    `/Marti/api/tls/signClient/v2` endpoint and add (auto-connect) the server,
 *    mirroring EnrollServerScreen's success path,
 *  - plain connect link with creds → same CSR auto-enroll,
 *  - plain connect link without creds → cert-less add.
 *
 * This is the reliable in-app on-ramp that does not depend on the OS camera
 * app routing the `tak://` scheme back to us. Pops back via [onDone] once the
 * scan is consumed (enrollment itself continues async with a toast).
 */
@Composable
fun ServerEnrollScanRoute(onDone: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as OmniTAKApp
    val scope = rememberCoroutineScope()

    ServerQrScanScreen(
        accept = { uri ->
            DeepLinkImport.isEnrollLink(uri) || DeepLinkImport.isServerConfig(uri)
        },
        onScanned = { uri ->
            val enrollCfg = if (DeepLinkImport.isEnrollLink(uri)) {
                DeepLinkImport.parseEnrollLink(uri)
            } else {
                DeepLinkImport.parseServerConfig(uri)
            }
            if (enrollCfg == null) {
                Toast.makeText(
                    context,
                    "QR missing host / credentials",
                    Toast.LENGTH_LONG,
                ).show()
                onDone()
                return@ServerQrScanScreen
            }
            if (enrollCfg.needsEnrollment) {
                enrollAndAdd(context, app, scope, enrollCfg)
            } else {
                app.serverManager.addServer(DeepLinkImport.toServer(enrollCfg))
                Toast.makeText(
                    context,
                    "Added server: ${enrollCfg.name} (${enrollCfg.host}:${enrollCfg.port})",
                    Toast.LENGTH_LONG,
                ).show()
            }
            onDone()
        },
        onDismiss = onDone,
    )
}

/**
 * Shared CSR enroll-and-add used by the in-app scanner. Same call shape as
 * MainActivity's deep-link enroll path and EnrollServerScreen — request a
 * signed client cert, then add (auto-connect) the server with the enrolled
 * .p12 + pinned CA wired up. Enrollment runs off the main thread; the user
 * gets toasts on start / success / failure.
 */
private fun enrollAndAdd(
    context: android.content.Context,
    app: OmniTAKApp,
    scope: kotlinx.coroutines.CoroutineScope,
    cfg: ImportedServerConfig,
) {
    Toast.makeText(context, "Enrolling with ${cfg.host}…", Toast.LENGTH_SHORT).show()
    scope.launch {
        val result = runCatching {
            withContext(Dispatchers.IO) {
                CSREnrollmentService(app.certVault).enroll(
                    CSREnrollmentService.Config(
                        host = cfg.host,
                        enrollmentPort = cfg.enrollmentPort,
                        username = cfg.username!!,
                        password = cfg.password!!,
                        trustSelfSigned = cfg.trustSelfSigned,
                    ),
                )
            }
        }
        result.onSuccess { enrolled ->
            app.serverManager.addServer(
                TAKServer(
                    name = cfg.name,
                    host = cfg.host,
                    port = cfg.port,
                    protocol = ConnectionProtocol.TLS.wire,
                    useTLS = true,
                    username = cfg.username,
                    // password is the login/enrollment credential, not the .p12 passphrase
                    password = null,
                    certificateName = enrolled.certificateName,
                    certificatePassword = enrolled.certificatePassword,
                    caCertificateName = enrolled.caCertificateName,
                ),
            )
            Toast.makeText(
                context,
                "Enrolled & connected: ${cfg.name}",
                Toast.LENGTH_LONG,
            ).show()
        }
        result.onFailure { e ->
            Toast.makeText(
                context,
                "Enrollment failed: ${e.message ?: e.javaClass.simpleName}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
