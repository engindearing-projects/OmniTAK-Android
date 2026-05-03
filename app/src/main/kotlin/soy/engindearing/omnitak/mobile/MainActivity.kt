package soy.engindearing.omnitak.mobile

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import soy.engindearing.omnitak.mobile.data.DeepLinkImport
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
