package soy.engindearing.omnitak.mobile.ui.screens

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import soy.engindearing.omnitak.mobile.BuildConfig
import soy.engindearing.omnitak.mobile.OmniTAKApp
import soy.engindearing.omnitak.mobile.domain.DiagnosticsLog
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent
import soy.engindearing.omnitak.mobile.ui.theme.TacticalBackground

/**
 * Settings > Diagnostics Log. This process's logcat lines since launch,
 * newest first, with a filter, copy, and share as a .txt (iOS #169 parity).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as OmniTAKApp
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var filter by remember { mutableStateOf("") }
    var errorsOnly by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }

    fun header(): String = DiagnosticsLog.header(
        appVersion = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        androidRelease = Build.VERSION.RELEASE ?: "?",
        sdkInt = Build.VERSION.SDK_INT,
        device = "${Build.MANUFACTURER} ${Build.MODEL}",
        servers = app.serverManager.servers.value,
    )

    fun reload() {
        copied = false
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { DiagnosticsLog.readLogcat() } }
            result.onSuccess { lines = it; loadError = null }
            result.onFailure { loadError = "Could not read the log: ${it.message ?: it.javaClass.simpleName}" }
        }
    }

    LaunchedEffect(Unit) { reload() }

    val visible = lines.filter { line ->
        (filter.isBlank() || line.contains(filter, ignoreCase = true)) &&
            (!errorsOnly || line.contains(" E/") || line.contains(" W/"))
    }

    fun exportText() = DiagnosticsLog.render(header(), visible)

    fun share() {
        val file = runCatching { DiagnosticsLog.exportFile(context, exportText()) }.getOrNull() ?: return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share diagnostics log"))
    }

    Scaffold(
        containerColor = TacticalBackground,
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics Log") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { reload() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = TacticalAccent)
                    }
                    IconButton(onClick = { share() }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share", tint = TacticalAccent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = TacticalBackground),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(TacticalBackground)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                "Lines written since the app was opened. Reproduce the problem first (for example refresh Mission Sync), then share this log.",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                label = { Text("Filter") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Warnings and errors only", color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.weight(1f))
                Switch(checked = errorsOnly, onCheckedChange = { errorsOnly = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "${visible.size} of ${lines.size} lines, newest first",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(exportText()))
                    copied = true
                }) {
                    Text(if (copied) "Copied" else "Copy", color = TacticalAccent)
                }
            }
            loadError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(visible.asReversed()) { line ->
                    Text(
                        line,
                        color = if (line.contains(" E/")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 3.dp),
                    )
                }
            }
            Spacer(Modifier.width(0.dp))
        }
    }
}
