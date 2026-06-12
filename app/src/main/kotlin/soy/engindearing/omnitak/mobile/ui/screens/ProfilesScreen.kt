package soy.engindearing.omnitak.mobile.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import soy.engindearing.omnitak.mobile.OmniTAKApp
import soy.engindearing.omnitak.mobile.data.ConfigProfile
import soy.engindearing.omnitak.mobile.data.ConfigProfileStore
import soy.engindearing.omnitak.mobile.data.ProfileQrCodec
import soy.engindearing.omnitak.mobile.data.ProfileQrGenerator
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent
import soy.engindearing.omnitak.mobile.ui.theme.TacticalBackground
import soy.engindearing.omnitak.mobile.ui.theme.TacticalSurface
import java.util.concurrent.Executors

private const val TAG = "ProfilesScreen"

/**
 * Full-screen profile manager.
 *
 * Features:
 *  - List all saved profiles; active profile highlighted.
 *  - Tap profile → switch (apply) it.
 *  - Long-press (via trailing icons) to rename or delete.
 *  - "Snapshot current" → names and saves the live config.
 *  - "Generate QR" → shows a QR code for sharing.
 *  - "Scan to import" → CameraX + MLKit scanner with an import preview/confirm dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val app = context.applicationContext as OmniTAKApp
    val store = app.configProfileStore
    val scope = rememberCoroutineScope()

    val profiles by store.profiles.collectAsState(initial = emptyList())
    val activeId by store.activeProfileId.collectAsState(initial = null)

    // ── Dialog state ────────────────────────────────────────────────────────
    var showSnapshotDialog by remember { mutableStateOf(false) }
    var profileForQr by remember { mutableStateOf<ConfigProfile?>(null) }
    var profileForRename by remember { mutableStateOf<ConfigProfile?>(null) }
    var profileForDelete by remember { mutableStateOf<ConfigProfile?>(null) }
    var showScanner by remember { mutableStateOf(false) }
    var importCandidate by remember { mutableStateOf<ConfigProfile?>(null) }

    Scaffold(
        containerColor = TacticalBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Config Profiles",
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.Close, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                actions = {
                    IconButton(onClick = { showScanner = true }) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = "Scan QR", tint = TacticalAccent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = TacticalBackground),
            )
        },
    ) { inner: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── Snapshot button ────────────────────────────────────────────
            Button(
                onClick = { showSnapshotDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = TacticalAccent),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Snapshot current config", fontWeight = FontWeight.SemiBold)
            }

            if (profiles.isEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "No profiles yet. Snapshot your current settings to create one.",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    "SAVED PROFILES",
                    color = TacticalAccent,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(profiles, key = { it.id }) { profile ->
                        ProfileRow(
                            profile = profile,
                            isActive = profile.id == activeId,
                            onSwitch = {
                                scope.launch {
                                    store.apply(profile)
                                }
                            },
                            onGenerateQr = { profileForQr = profile },
                            onRename = { profileForRename = profile },
                            onDelete = { profileForDelete = profile },
                        )
                    }
                }
            }
        }
    }

    // ── Snapshot dialog ─────────────────────────────────────────────────────
    if (showSnapshotDialog) {
        SnapshotDialog(
            onConfirm = { name ->
                showSnapshotDialog = false
                scope.launch {
                    val servers = app.serverManager.servers.value
                    store.snapshotCurrent(name, servers)
                }
            },
            onDismiss = { showSnapshotDialog = false },
        )
    }

    // ── QR display dialog ───────────────────────────────────────────────────
    profileForQr?.let { profile ->
        QrDialog(profile = profile, onDismiss = { profileForQr = null })
    }

    // ── Rename dialog ───────────────────────────────────────────────────────
    profileForRename?.let { profile ->
        RenameDialog(
            current = profile.name,
            onConfirm = { newName ->
                profileForRename = null
                scope.launch { store.renameProfile(profile.id, newName) }
            },
            onDismiss = { profileForRename = null },
        )
    }

    // ── Delete confirmation ─────────────────────────────────────────────────
    profileForDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { profileForDelete = null },
            containerColor = TacticalSurface,
            title = { Text("Delete profile?", color = MaterialTheme.colorScheme.onBackground) },
            text = {
                Text(
                    "\"${profile.name}\" will be removed. This doesn't affect your current config.",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    profileForDelete = null
                    scope.launch { store.deleteProfile(profile.id) }
                }) {
                    Text("Delete", color = Color(0xFFFF5252))
                }
            },
            dismissButton = {
                TextButton(onClick = { profileForDelete = null }) {
                    Text("Cancel", color = TacticalAccent)
                }
            },
        )
    }

    // ── QR scanner ──────────────────────────────────────────────────────────
    if (showScanner) {
        QrScannerDialog(
            onScanned = { uri ->
                val profile = runCatching { ProfileQrCodec.decode(uri) }.getOrNull()
                if (profile != null) {
                    importCandidate = profile
                }
                showScanner = false
            },
            onDismiss = { showScanner = false },
        )
    }

    // ── Import preview/confirm dialog ───────────────────────────────────────
    importCandidate?.let { candidate ->
        ImportPreviewDialog(
            profile = candidate,
            onConfirm = {
                importCandidate = null
                scope.launch {
                    store.saveProfile(candidate)
                    store.apply(candidate)
                }
            },
            onDismiss = { importCandidate = null },
        )
    }
}

// ── Profile row ─────────────────────────────────────────────────────────────

@Composable
private fun ProfileRow(
    profile: ConfigProfile,
    isActive: Boolean,
    onSwitch: () -> Unit,
    onGenerateQr: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) TacticalAccent.copy(alpha = 0.15f) else TacticalSurface,
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSwitch),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isActive) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = TacticalAccent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(profile.name, color = MaterialTheme.colorScheme.onBackground, fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal)
                }
                Text(
                    buildString {
                        append("Team ${profile.team}")
                        if (profile.servers.isNotEmpty()) append(" · ${profile.servers.size} server${if (profile.servers.size != 1) "s" else ""}")
                    },
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onGenerateQr) {
                Icon(Icons.Filled.QrCode, contentDescription = "Generate QR", tint = TacticalAccent)
            }
            IconButton(onClick = onRename) {
                Icon(Icons.Filled.Edit, contentDescription = "Rename", tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Color(0xFFFF5252).copy(alpha = 0.8f))
            }
        }
    }
}

// ── Snapshot dialog ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SnapshotDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TacticalSurface,
        title = { Text("Save current config", color = MaterialTheme.colorScheme.onBackground) },
        text = {
            Column {
                Text(
                    "Name this profile so teammates know what it's for (e.g. \"Alpha Team\").",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Profile name") },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onBackground,
                        unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                        focusedContainerColor = TacticalSurface,
                        unfocusedContainerColor = TacticalSurface,
                        focusedIndicatorColor = TacticalAccent,
                        unfocusedIndicatorColor = TacticalAccent.copy(alpha = 0.4f),
                        focusedLabelColor = TacticalAccent,
                        unfocusedLabelColor = TacticalAccent.copy(alpha = 0.6f),
                        cursorColor = TacticalAccent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim()) },
            ) { Text("Save", color = if (name.isNotBlank()) TacticalAccent else TacticalAccent.copy(alpha = 0.4f)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)) }
        },
    )
}

// ── Rename dialog ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenameDialog(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TacticalSurface,
        title = { Text("Rename profile", color = MaterialTheme.colorScheme.onBackground) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Profile name") },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onBackground,
                    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                    focusedContainerColor = TacticalSurface,
                    unfocusedContainerColor = TacticalSurface,
                    focusedIndicatorColor = TacticalAccent,
                    unfocusedIndicatorColor = TacticalAccent.copy(alpha = 0.4f),
                    focusedLabelColor = TacticalAccent,
                    unfocusedLabelColor = TacticalAccent.copy(alpha = 0.6f),
                    cursorColor = TacticalAccent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && name != current,
                onClick = { onConfirm(name.trim()) },
            ) { Text("Rename", color = if (name.isNotBlank()) TacticalAccent else TacticalAccent.copy(alpha = 0.4f)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)) }
        },
    )
}

// ── QR display dialog ────────────────────────────────────────────────────────

@Composable
private fun QrDialog(profile: ConfigProfile, onDismiss: () -> Unit) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(profile.id) {
        bitmap = withContext(Dispatchers.Default) {
            ProfileQrGenerator.generate(profile, sizePx = 512)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(TacticalSurface)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                profile.name,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Teammates scan this to sync your config.\nCallsigns are kept individual.",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(16.dp))

            val bmp = bitmap
            if (bmp == null) {
                CircularProgressIndicator(color = TacticalAccent, modifier = Modifier.size(80.dp))
            } else {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "QR code for ${profile.name}",
                    modifier = Modifier
                        .size(260.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .padding(8.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Team ${profile.team} · ${profile.servers.size} server${if (profile.servers.size != 1) "s" else ""}",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onDismiss) {
                Text("Done", color = TacticalAccent, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ── QR scanner dialog ────────────────────────────────────────────────────────

@OptIn(ExperimentalGetImage::class)
@Composable
private fun QrScannerDialog(
    onScanned: (android.net.Uri) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Camera permission gate
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraGranted = granted
    }
    LaunchedEffect(Unit) {
        if (!cameraGranted) permLauncher.launch(Manifest.permission.CAMERA)
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(TacticalSurface)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Scan team QR code",
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(12.dp))

            if (!cameraGranted) {
                Text(
                    "Camera permission required to scan QR codes.",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
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

                Box(
                    modifier = Modifier
                        .size(260.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black),
                ) {
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
                                                    val uri = runCatching { android.net.Uri.parse(raw) }.getOrNull()
                                                    if (uri != null && ProfileQrCodec.isProfileUri(uri)) {
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
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Point at an OmniTAK team QR code",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
            }
        }
    }
}

// ── Import preview dialog ─────────────────────────────────────────────────────

@Composable
private fun ImportPreviewDialog(
    profile: ConfigProfile,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TacticalSurface,
        title = { Text("Import config profile?", color = MaterialTheme.colorScheme.onBackground) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    profile.name,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(8.dp))
                ProfileDetailRow("Team", profile.team)
                ProfileDetailRow("Servers", "${profile.servers.size} server${if (profile.servers.size != 1) "s" else ""}")
                if (profile.servers.isNotEmpty()) {
                    profile.servers.forEach { server ->
                        Text(
                            "  · ${server.name} (${server.host}:${server.port})",
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                ProfileDetailRow("Map", profile.mapProvider.replace("_", " ").lowercase().replaceFirstChar { it.titlecase() })
                ProfileDetailRow("Coords", profile.coordFormat.replace("_", " "))
                ProfileDetailRow("Units", profile.distanceUnit.lowercase().replaceFirstChar { it.titlecase() })
                Spacer(Modifier.height(8.dp))
                Text(
                    "Your callsign will not be changed. Servers that already exist won't be duplicated.",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Import & Apply", color = TacticalAccent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)) }
        },
    )
}

@Composable
private fun ProfileDetailRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            "$label: ",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(value, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.bodySmall)
    }
}
