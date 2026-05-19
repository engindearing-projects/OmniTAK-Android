package soy.engindearing.omnitak.mobile.ui.screens

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import soy.engindearing.omnitak.mobile.OmniTAKApp
import soy.engindearing.omnitak.mobile.data.CSREnrollmentService
import soy.engindearing.omnitak.mobile.data.ConnectionProtocol
import soy.engindearing.omnitak.mobile.data.TAKServer
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent
import soy.engindearing.omnitak.mobile.ui.theme.TacticalBackground

/**
 * Quick Connect — request and store a client cert from a TAK Server's
 * `/Marti/api/tls/signClient/v2` enrollment endpoint (GAP-081).
 *
 * On success the screen adds the server to [ServerManager] with the
 * enrolled `.p12` already wired up, then pops the back stack.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnrollServerScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as OmniTAKApp
    val manager = app.serverManager
    val certVault = app.certVault
    val scope = rememberCoroutineScope()
    val enrollment = remember { CSREnrollmentService(certVault) }

    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var connectPortText by remember { mutableStateOf("8089") }
    var enrollPortText by remember { mutableStateOf("8446") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var trustSelfSigned by remember { mutableStateOf(true) }
    var passwordVisible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var isEnrolling by remember { mutableStateOf(false) }

    val connectPort = connectPortText.toIntOrNull()
    val enrollPort = enrollPortText.toIntOrNull()
    val canEnroll = !isEnrolling &&
        host.isNotBlank() &&
        username.isNotBlank() &&
        password.isNotEmpty() &&
        connectPort != null && connectPort in 1..65535 &&
        enrollPort != null && enrollPort in 1..65535

    LaunchedEffect(host) {
        // Pre-fill the friendly name with the host so users don't have
        // to type it twice. They can still edit before enrolling.
        if (name.isBlank()) name = host
    }

    Scaffold(
        containerColor = TacticalBackground,
        topBar = {
            TopAppBar(
                title = { Text("Quick Connect") },
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
                        if (!canEnroll) return@Button
                        error = null
                        isEnrolling = true
                        scope.launch {
                            val result = runCatching {
                                withContext(Dispatchers.IO) {
                                    enrollment.enroll(
                                        CSREnrollmentService.Config(
                                            host = host.trim(),
                                            enrollmentPort = enrollPort!!,
                                            username = username.trim(),
                                            password = password,
                                            trustSelfSigned = trustSelfSigned,
                                        )
                                    )
                                }
                            }
                            isEnrolling = false
                            result.onSuccess { enrolled ->
                                manager.addServer(
                                    TAKServer(
                                        name = name.trim().ifBlank { host.trim() },
                                        host = host.trim(),
                                        port = connectPort!!,
                                        protocol = ConnectionProtocol.TLS.wire,
                                        useTLS = true,
                                        username = username.trim().takeIf { it.isNotBlank() },
                                        // password is the LOGIN credential, NOT the .p12 passphrase;
                                        // it's intentionally not persisted post-enrollment
                                        password = null,
                                        certificateName = enrolled.certificateName,
                                        certificatePassword = enrolled.certificatePassword,
                                    ),
                                )
                                onDone()
                            }
                            result.onFailure { error = it.message ?: it.javaClass.simpleName }
                        }
                    },
                    enabled = canEnroll,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TacticalAccent,
                        contentColor = TacticalBackground,
                    ),
                ) {
                    if (isEnrolling) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(20.dp),
                            color = TacticalBackground,
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.padding(horizontal = 8.dp))
                        Text("Enrolling…")
                    } else {
                        Text("Enroll & Save")
                    }
                }
            }
        },
    ) { inner: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Request a client certificate from the TAK Server's enrollment endpoint (port 8446 by default). The server signs a CSR generated on this device — your private key never leaves the device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )

            TextField(
                value = host,
                onValueChange = { host = it.trim() },
                label = { Text("Server host") },
                placeholder = { Text("tak.example.com or 10.0.0.5") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(
                    value = connectPortText,
                    onValueChange = { connectPortText = it.filter(Char::isDigit) },
                    label = { Text("Connect port") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                TextField(
                    value = enrollPortText,
                    onValueChange = { enrollPortText = it.filter(Char::isDigit) },
                    label = { Text("Enrollment port") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }

            TextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Display name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            TextField(
                value = username,
                onValueChange = { username = it.trim() },
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            TextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (passwordVisible) "Hide password" else "Show password",
                        )
                    }
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Trust self-signed certificate", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "On for self-hosted TAK Servers using their own CA. Off for Let's Encrypt / sectigo / other publicly trusted CAs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    )
                }
                Switch(checked = trustSelfSigned, onCheckedChange = { trustSelfSigned = it })
            }

            error?.let { msg ->
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
