package soy.engindearing.omnitak.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent

@Composable
fun AboutScreen() {
    // #207 — landscape on a short-height phone can put this below the fold
    // (large font scale, a narrow landscape height, etc.); scroll keeps it
    // reachable instead of clipped. Arrangement.Center still centers the
    // content when it already fits.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("OmniTAK", style = MaterialTheme.typography.titleLarge, color = TacticalAccent)
        Spacer(Modifier.height(8.dp))
        Text("By Engindearing", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        Text(
            "ATAK-compatible tactical awareness client.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Version ${soy.engindearing.omnitak.mobile.BuildConfig.VERSION_NAME} " +
                "(${soy.engindearing.omnitak.mobile.BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
        )
    }
}
