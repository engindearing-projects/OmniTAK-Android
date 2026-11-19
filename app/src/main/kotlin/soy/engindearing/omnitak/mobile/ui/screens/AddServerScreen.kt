package soy.engindearing.omnitak.mobile.ui.screens

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import soy.engindearing.omnitak.mobile.OmniTAKApp
import soy.engindearing.omnitak.mobile.data.CaFingerprint
import soy.engindearing.omnitak.mobile.data.CaTrust
import soy.engindearing.omnitak.mobile.data.CertVault
import soy.engindearing.omnitak.mobile.data.ConnectionProtocol
import soy.engindearing.omnitak.mobile.data.ManualPinResult
import soy.engindearing.omnitak.mobile.data.TAKServer
import soy.engindearing.omnitak.mobile.data.discovery.TakNsdDiscovery
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent
import soy.engindearing.omnitak.mobile.ui.theme.TacticalBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as OmniTAKApp
    val manager = app.serverManager
    val certVault = app.certVault

    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("8089") }
    var useTLS by remember { mutableStateOf(true) }
    // GAP-105 — basic-auth credentials. Either both fields filled or both blank.
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    // GAP-105 — client-cert .p12 + passphrase for mTLS to TAK Server.
    var certName by remember { mutableStateOf<String?>(null) }
    var certPassword by remember { mutableStateOf("") }
    var certError by remember { mutableStateOf<String?>(null) }
    // Manual CA pin for servers on a private CA without CSR enrollment.
    // Stored as a PEM chain in the vault and wired to caCertificateName,
    // which TakTls already treats as the per-server root of trust. A CA the
    // phone already trusts publicly is refused (CaTrust.evaluateManualPin).
    var caName by remember { mutableStateOf<String?>(null) }
    var caFingerprints by remember { mutableStateOf<List<CaFingerprint>>(emptyList()) }
    var caError by remember { mutableStateOf<String?>(null) }
    // A picked CA file is read and checked off the main thread. Until that is done
    // the CA buttons and Save are off, so a server cannot be saved before it is applied.
    var caChecking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // BUG-A — visibility toggles so users can confirm what they typed.
    // Default to masked (dots) on both password fields.
    var passwordVisible by remember { mutableStateOf(false) }
    var certPasswordVisible by remember { mutableStateOf(false) }

    val pickCertLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val displayName = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "client.p12"
        val saved = certVault.import(context, uri, displayName)
        if (saved != null) {
            certName = saved
            certError = null
        } else {
            certError = "Couldn't read that file. Pick a .p12 from local storage."
        }
    }

    val pickCaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        caChecking = true
        scope.launch {
            try {
                val pick = withContext(Dispatchers.IO) { checkCaPick(context, certVault, uri) }
                // A file that is not used leaves the earlier choice as it was, and says so.
                when (val pin = pick.result) {
                    ManualPinResult.NoCa -> {
                        caError = "That file was not used. No CA certificate was found in it. " +
                            "Pick the CA (.pem/.crt) your admin gave you."
                    }
                    ManualPinResult.TooLarge -> {
                        caError = "That file is too large to be a CA certificate."
                    }
                    ManualPinResult.CannotCheck -> {
                        caError = "The app could not read this phone's own CA list, so it cannot check " +
                            "this file. Add the server by QR code or enrollment instead."
                    }
                    is ManualPinResult.PublicCa -> {
                        caError = "That file was not used. It holds a public CA (${pin.commonName}). " +
                            "Pick a file with only your server's own CA. " +
                            "If the server has a public certificate, leave this empty."
                    }
                    is ManualPinResult.Ok -> {
                        if (pick.savedName != null) {
                            caName = pick.savedName
                            caFingerprints = pin.fingerprints
                            caError = null
                        } else {
                            caError = "Couldn't save that CA certificate."
                        }
                    }
                }
            } finally {
                caChecking = false
            }
        }
    }

    val port = portText.toIntOrNull()
    val canSave = name.isNotBlank() && host.isNotBlank() && port != null && port in 1..65535

    // #158 — live Bonjour/mDNS browse of _tak._tcp while this screen is open
    // (stops when it leaves composition). Tapping a hit prefills the form;
    // nothing renders when the LAN is quiet.
    val discovered by remember {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsd != null) TakNsdDiscovery(nsd).discover() else flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    Scaffold(
        containerColor = TacticalBackground,
        topBar = {
            TopAppBar(
                title = { Text("Add TAK Server") },
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
        // BUG-B — pin the primary action to the bottom of the screen so
        // it is always reachable. The form is long and used to live at
        // the bottom of a verticalScroll column, where reporters
        // (closed-test, May 2026) couldn't find it under the floating
        // bottom nav. windowInsetsPadding keeps it clear of the gesture
        // nav; imePadding lifts it above the soft keyboard.
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TacticalBackground)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .imePadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Button(
                    onClick = {
                        if (!canSave) return@Button
                        manager.addOrReplaceServer(
                            TAKServer(
                                name = name.trim(),
                                host = host,
                                port = port!!,
                                protocol = if (useTLS) {
                                    ConnectionProtocol.TLS.wire
                                } else {
                                    ConnectionProtocol.TCP.wire
                                },
                                useTLS = useTLS,
                                username = username.takeIf { it.isNotBlank() },
                                password = password.takeIf { it.isNotEmpty() },
                                certificateName = certName.takeIf { useTLS },
                                certificatePassword = certPassword.takeIf {
                                    useTLS && certName != null && it.isNotEmpty()
                                },
                                caCertificateName = caName.takeIf { useTLS },
                            ),
                        )
                        onDone()
                    },
                    enabled = canSave && !caChecking,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TacticalAccent,
                        contentColor = TacticalBackground,
                    ),
                ) {
                    Text("Save Server")
                }
            }
        },
    ) { inner: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                // BUG-C (closed-test, May 2026) — the focused field used to
                // disappear behind the keyboard. Root cause was a double
                // keyboard inset: the sticky bottomBar already applies
                // imePadding, so Scaffold reports a keyboard-aware `inner`.
                // The content Column ALSO applied .imePadding(), reserving
                // ~2x the keyboard height at the bottom of the scroll
                // viewport and pushing the focused field into the dead zone.
                // `inner` is the single source of truth — no second imePadding.
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // BUG-C — orient the user before the long form. Manual entry here
            // vs. the Quick Connect (⚡) enrollment flow on the Servers screen.
            Text(
                "Fill in your server's details below. Has your server's admin " +
                    "set up auto-enrollment? Use Quick Connect (⚡) on the " +
                    "Servers screen instead to fetch a certificate automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
            )

            if (discovered.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Discovered on this network",
                        style = MaterialTheme.typography.titleSmall,
                        color = TacticalAccent,
                    )
                    discovered.forEach { found ->
                        OutlinedButton(
                            onClick = {
                                name = found.name
                                host = found.host
                                portText = found.port.toString()
                                useTLS = found.useTLS
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(found.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${found.host}:${found.port}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                                )
                            }
                            Text(
                                if (found.useTLS) "TLS" else "TCP",
                                style = MaterialTheme.typography.labelSmall,
                                color = TacticalAccent,
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                placeholder = { Text("e.g. Team Server") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = tacticalOutlineColors(),
            )

            OutlinedTextField(
                value = host,
                onValueChange = { host = it.trim() },
                label = { Text("Host or IP") },
                placeholder = { Text("tak.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors = tacticalOutlineColors(),
            )

            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
                label = { Text("Port") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = tacticalOutlineColors(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Use TLS", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Most TAK servers require TLS with client certs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = useTLS,
                    onCheckedChange = { useTLS = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TacticalBackground,
                        checkedTrackColor = TacticalAccent,
                    ),
                )
            }

            if (useTLS) {
                Spacer(Modifier.height(4.dp))

                Text(
                    "Client certificate",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "Required by TAK Server's default mTLS input on port 8089. " +
                        "Pick the .p12 your admin gave you, then enter its password.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            certName ?: "No certificate selected",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (certName != null) {
                                TacticalAccent
                            } else {
                                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                            },
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(
                        onClick = {
                            certError = null
                            // Most file pickers don't expose application/x-pkcs12,
                            // so accept any file and validate by extension/content.
                            pickCertLauncher.launch(arrayOf("*/*"))
                        },
                    ) {
                        Text(if (certName == null) "Choose .p12" else "Replace")
                    }
                }

                if (certName != null) {
                    OutlinedTextField(
                        value = certPassword,
                        onValueChange = { certPassword = it },
                        label = { Text("Certificate password") },
                        placeholder = { Text("atakatak") },
                        singleLine = true,
                        visualTransformation = if (certPasswordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { certPasswordVisible = !certPasswordVisible }) {
                                Icon(
                                    imageVector = if (certPasswordVisible) {
                                        Icons.Filled.VisibilityOff
                                    } else {
                                        Icons.Filled.Visibility
                                    },
                                    contentDescription = if (certPasswordVisible) {
                                        "Hide certificate password"
                                    } else {
                                        "Show certificate password"
                                    },
                                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = tacticalOutlineColors(),
                    )
                }

                if (certError != null) {
                    Text(
                        certError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(4.dp))

                Text(
                    "Server CA certificate",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "For servers on a private CA. The server's certificate must " +
                        "chain to this CA or the connection is refused. Leave empty " +
                        "only if the server uses a publicly-trusted certificate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                caChecking -> "Checking..."
                                caName != null -> CaTrust.manualPinLabel(caFingerprints)
                                else -> "No CA selected (system trust)"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (caName != null && !caChecking) {
                                TacticalAccent
                            } else {
                                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                            },
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    if (caName != null) {
                        TextButton(
                            onClick = {
                                caName = null
                                caFingerprints = emptyList()
                                caError = null
                            },
                            enabled = !caChecking,
                        ) {
                            Text("Remove")
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            caError = null
                            pickCaLauncher.launch(arrayOf("*/*"))
                        },
                        enabled = !caChecking,
                    ) {
                        Text(if (caName == null) "Choose CA" else "Replace")
                    }
                }

                if (caFingerprints.isNotEmpty()) {
                    Text(
                        caFingerprintText(caFingerprints),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                    )
                }

                if (caError != null) {
                    Text(
                        caError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Text(
                "Server credentials (optional)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "A username/password login — separate from the client " +
                    "certificate above. Required for OpenTAKServer and most " +
                    "CoT servers with basic auth. Leave blank if your server " +
                    "only uses certificates.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
            )

            OutlinedTextField(
                value = username,
                onValueChange = { username = it.trim() },
                label = { Text("Username") },
                placeholder = { Text("operator") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = tacticalOutlineColors(),
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) {
                                Icons.Filled.VisibilityOff
                            } else {
                                Icons.Filled.Visibility
                            },
                            contentDescription = if (passwordVisible) {
                                "Hide password"
                            } else {
                                "Show password"
                            },
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = tacticalOutlineColors(),
            )

            // Tail spacer so the last field clears the sticky Save bar
            // when the keyboard is up. The bottomBar already adds its
            // own padding above the gesture nav inset.
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** A picked CA file once checked: what it turned out to be, and the vault name it was saved under when it can be pinned. */
private class CaPick(val result: ManualPinResult, val savedName: String?)

/** Read, check and save a picked CA file. It does file IO: call it off the main thread. */
private fun checkCaPick(context: Context, certVault: CertVault, uri: Uri): CaPick {
    val result = runCatching {
        context.contentResolver.openInputStream(uri)?.use { CaTrust.evaluateManualPin(it, CaTrust.builtInCas()) }
    }.getOrNull() ?: ManualPinResult.NoCa
    if (result !is ManualPinResult.Ok) return CaPick(result, null)
    val pem = CaTrust.encodePemChain(result.cas)
    // Two servers on one CA share one file. Leave an identical one alone: importBytes deletes it when a write fails.
    val saved = if (certVault.read(result.fileName)?.contentEquals(pem) == true) {
        result.fileName
    } else {
        certVault.importBytes(result.fileName, pem)
    }
    return CaPick(result, saved)
}

/**
 * One CA reads as it always did. With more than one, each gets its own entry
 * (common name, then SHA-256), since all of them are pinned.
 */
private fun caFingerprintText(fingerprints: List<CaFingerprint>): String =
    if (fingerprints.size == 1) {
        "SHA-256: ${fingerprints.first().sha256}\nCompare with the fingerprint your admin gave you."
    } else {
        fingerprints.joinToString("\n\n") { "${it.commonName}\nSHA-256: ${it.sha256}" } +
            "\n\nCompare with the fingerprints your admin gave you."
    }

private fun queryDisplayName(context: android.content.Context, uri: Uri): String? {
    val cursor = context.contentResolver.query(
        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
    ) ?: return null
    return cursor.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun tacticalOutlineColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onBackground,
    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
    focusedBorderColor = TacticalAccent,
    unfocusedBorderColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
    focusedLabelColor = TacticalAccent,
    unfocusedLabelColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
    cursorColor = TacticalAccent,
)
