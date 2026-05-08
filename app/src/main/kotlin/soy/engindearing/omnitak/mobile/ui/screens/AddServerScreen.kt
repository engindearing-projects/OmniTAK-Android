package soy.engindearing.omnitak.mobile.ui.screens

import android.net.Uri
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import soy.engindearing.omnitak.mobile.OmniTAKApp
import soy.engindearing.omnitak.mobile.data.ConnectionProtocol
import soy.engindearing.omnitak.mobile.data.TAKServer
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

    val port = portText.toIntOrNull()
    val canSave = name.isNotBlank() && host.isNotBlank() && port != null && port in 1..65535

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
                        manager.addServer(
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
                            ),
                        )
                        onDone()
                    },
                    enabled = canSave,
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
                .padding(inner)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                placeholder = { Text("e.g. FreeTAK") },
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
            }

            Spacer(Modifier.height(4.dp))

            Text(
                "Server credentials (optional)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "Required for OpenTAKserver and most CoT servers with auth.",
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
