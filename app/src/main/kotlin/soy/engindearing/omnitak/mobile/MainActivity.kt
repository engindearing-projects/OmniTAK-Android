package soy.engindearing.omnitak.mobile

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.launch
import soy.engindearing.omnitak.mobile.data.DeepLinkAction
import soy.engindearing.omnitak.mobile.data.DeepLinkImport
import soy.engindearing.omnitak.mobile.data.PreferenceUriApplier
import soy.engindearing.omnitak.mobile.domain.CsrEnrollment
import soy.engindearing.omnitak.mobile.domain.DataPackageBootstrap
import soy.engindearing.omnitak.mobile.domain.DataPackageDownloader
import soy.engindearing.omnitak.mobile.ui.navigation.AppNav
import soy.engindearing.omnitak.mobile.ui.theme.OmniTAKTheme
import soy.engindearing.omnitak.mobile.ui.theme.TacticalBackground

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(TacticalBackground.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(TacticalBackground.toArgb()),
        )
        setContent {
            OmniTAKTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppNav()
                }
            }
        }

        handleImportIntent(intent)

        // Re-open the TLS socket on every foreground resume if Android
        // killed the read loop while we were backgrounded (Doze, app
        // standby, network swap). Cold-launch reconnect lives in
        // ServerManager.hydrate; this hook handles foreground-resume.
        // Issue #6.
        val app = applicationContext as OmniTAKApp
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    app.serverManager.reconnectIfNeeded()
                }
            },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleImportIntent(intent)
    }

    /**
     * Dispatch ATAK-compatible deep links. Singletask launchMode means a
     * second QR scan while the app is open re-enters via [onNewIntent]
     * instead of spawning a new task.
     *
     * Supported verbs (any of `tak://`, `atak://`, `omnitak://`, or an
     * `https://?host=…` fallback):
     *
     * - `/connect` or bare-query — add a server config + auto-connect.
     * - `/import?url=…` — download a data-package zip and apply it via
     *   [DataPackageBootstrap].
     * - `/preference?keyN=…&typeN=…&valueN=…` — write supported keys to
     *   [UserPrefs]. Unsupported keys are dropped (toast names them).
     * - `/enroll?host=…&username=…&token=…` — CSR enrollment. iOS has
     *   the full flow; on Android we add the server with credentials so
     *   the connect attempt at least surfaces in the Servers tab while
     *   GAP-081 is in flight.
     */
    private fun handleImportIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return

        val action = DeepLinkImport.parse(uri)
        Log.i("OmniTAK", "Deep link $uri → ${action::class.simpleName}")

        val app = applicationContext as OmniTAKApp
        when (action) {
            is DeepLinkAction.AddServer -> {
                val server = DeepLinkImport.toServer(action.config)
                app.serverManager.addServer(server)
                toast("Added server: ${server.name} (${server.host}:${server.port})")
            }
            is DeepLinkAction.ImportFromUrl -> {
                toast("Downloading data package…")
                lifecycleScope.launch {
                    val file = DataPackageDownloader.download(this@MainActivity, action.url)
                    if (file == null) {
                        toast("Data-package download failed")
                        return@launch
                    }
                    DataPackageBootstrap(this@MainActivity, app.certVault, app.serverManager)
                        .runIfNeeded()
                    toast("Importing ${file.name}")
                }
            }
            is DeepLinkAction.SetPreferences -> {
                lifecycleScope.launch {
                    val result = applyPreferences(app, action.entries)
                    val msg = buildString {
                        append("Applied ${result.applied.size} preference")
                        if (result.applied.size != 1) append("s")
                        if (result.ignored.isNotEmpty()) {
                            append(" (ignored ${result.ignored.size}: ${result.ignored.joinToString(",")})")
                        }
                    }
                    toast(msg)
                }
            }
            is DeepLinkAction.Enroll -> {
                toast("Enrolling with ${action.host}…")
                lifecycleScope.launch {
                    runCatching {
                        CsrEnrollment(this@MainActivity, app.certVault).enroll(
                            host = action.host,
                            username = action.username,
                            token = action.token,
                            enrollmentPort = action.port,
                        )
                    }.onSuccess { server ->
                        app.serverManager.addServer(server)
                        toast("Enrolled: ${server.name} → auto-connecting")
                    }.onFailure { t ->
                        Log.w("OmniTAK", "CSR enrollment failed", t)
                        toast("Enrollment failed: ${t.message ?: t.javaClass.simpleName}")
                    }
                }
            }
            DeepLinkAction.Unknown -> {
                toast("Onboarding link missing host or payload")
            }
        }
    }

    private suspend fun applyPreferences(
        app: OmniTAKApp,
        entries: List<DeepLinkAction.PreferenceEntry>,
    ): PreferenceUriApplier.ApplyResult {
        var captured: PreferenceUriApplier.ApplyResult? = null
        app.userPrefsStore.update { current ->
            val res = PreferenceUriApplier.apply(current, entries)
            captured = res
            res.prefs
        }
        return captured!!
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
