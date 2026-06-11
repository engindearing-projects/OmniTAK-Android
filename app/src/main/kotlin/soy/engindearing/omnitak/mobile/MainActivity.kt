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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import soy.engindearing.omnitak.mobile.data.CSREnrollmentService
import soy.engindearing.omnitak.mobile.data.ConnectionProtocol
import soy.engindearing.omnitak.mobile.data.DeepLinkImport
import soy.engindearing.omnitak.mobile.data.ImportedServerConfig
import soy.engindearing.omnitak.mobile.data.TAKServer
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
        //
        // Issue #75 — also force an immediate location refresh on resume
        // (fused cache + active single-shot) instead of waiting for the
        // next passive interval tick, so the self-marker snaps back to a
        // live position right after screen-on. Foreground-only; no
        // background-location permission involved.
        val app = applicationContext as OmniTAKApp
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    app.serverManager.reconnectIfNeeded()
                    app.locationProvider.requestImmediateFix()
                }
            },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleImportIntent(intent)
    }

    /**
     * GAP-105 rest — handle `atak://` / `omnitak://` deep links carrying
     * a server-onboarding payload. Singletask launchMode means a second
     * scan while the app is open re-enters via [onNewIntent] instead of
     * spawning a new task.
     */
    private fun handleImportIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        if (!DeepLinkImport.isServerConfig(uri)) return

        val cfg = DeepLinkImport.parseServerConfig(uri)
        if (cfg == null) {
            Toast.makeText(
                this,
                "Onboarding link missing host or port",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        // username + password on a TLS server → CSR auto-enroll (easy connect).
        // Otherwise fall back to the cert-less add (plain TCP / anon SSL).
        if (cfg.needsEnrollment) {
            enrollFromDeepLink(uri, cfg)
        } else {
            val app = applicationContext as OmniTAKApp
            val server = DeepLinkImport.toServer(cfg)
            app.serverManager.addServer(server)
            Log.i("OmniTAK", "Imported server '${server.name}' from $uri")
            Toast.makeText(
                this,
                "Added server: ${server.name} (${server.host}:${server.port})",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /**
     * Easy-connect auto-enroll: scan a QR / open a deep link carrying
     * host + username + password, request a client cert from the TAK
     * Server's `/Marti/api/tls/signClient/v2` endpoint, then add the
     * server with the enrolled `.p12` wired up. Mirrors EnrollServerScreen.
     */
    private fun enrollFromDeepLink(uri: android.net.Uri, cfg: ImportedServerConfig) {
        val app = applicationContext as OmniTAKApp
        Toast.makeText(this, "Enrolling with ${cfg.host}…", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
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
                        // password is the login credential, not the .p12 passphrase
                        password = null,
                        certificateName = enrolled.certificateName,
                        certificatePassword = enrolled.certificatePassword,
                    ),
                )
                Log.i("OmniTAK", "Enrolled + added server '${cfg.name}' from $uri")
                Toast.makeText(
                    this@MainActivity,
                    "Enrolled & connected: ${cfg.name}",
                    Toast.LENGTH_LONG,
                ).show()
            }
            result.onFailure { e ->
                Log.e("OmniTAK", "Deep-link enrollment failed for ${cfg.host}", e)
                Toast.makeText(
                    this@MainActivity,
                    "Enrollment failed: ${e.message ?: e.javaClass.simpleName}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
}
