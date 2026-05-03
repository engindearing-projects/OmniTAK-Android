package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import soy.engindearing.omnitak.mobile.data.MeshNode
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent
import soy.engindearing.omnitak.mobile.ui.theme.TacticalBackground
import soy.engindearing.omnitak.mobile.ui.theme.TacticalSurface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * GAP-121 — bottom-sheet detail for a Meshtastic node. Designed to
 * cover the position-less case: you tap a node in the Mesh tab Nodes
 * section, you see everything we know about it even if there's no GPS
 * lock yet. Same sheet is reused on map-marker taps in GAP-122.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshNodeDetailSheet(
    node: MeshNode,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = TacticalBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Header — call sign + ID hex.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(TacticalSurface),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        node.shortName.takeIf { it.isNotBlank() } ?: "?",
                        color = TacticalAccent,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        node.longName.ifBlank { "Node ${node.idHex}" },
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "id ${node.idHex}",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            DetailDivider()

            // Position — surface "no GPS yet" instead of empty.
            if (node.position != null) {
                DetailRow("Latitude", "%.6f°".format(node.position.lat))
                DetailRow("Longitude", "%.6f°".format(node.position.lon))
                node.position.altitudeM?.let { DetailRow("Altitude", "$it m") }
            } else {
                DetailRow(
                    "Position",
                    "no GPS lock yet — node won't appear on map until it broadcasts a position",
                    multilineHint = true,
                )
            }

            DetailDivider()

            // Link health — SNR, hops, battery, last heard.
            node.snr?.let { DetailRow("SNR", "%.1f dB".format(it)) }
            node.hopDistance?.let { DetailRow("Hops away", "$it") }
            node.batteryLevel?.let { DetailRow("Battery", "$it%") }
            DetailRow("Last heard", formatLastHeard(node.lastHeardEpoch))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, multilineHint: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            color = TacticalAccent,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .width(110.dp)
                .padding(end = 12.dp),
        )
        Text(
            value,
            color = MaterialTheme.colorScheme.onBackground,
            style = if (multilineHint) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DetailDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(TacticalSurface),
    )
}

private val timestampFormatter = SimpleDateFormat("HH:mm:ss", Locale.US).apply {
    timeZone = TimeZone.getDefault()
}

private fun formatLastHeard(epochSec: Long): String {
    if (epochSec <= 0L) return "—"
    val ageSec = (System.currentTimeMillis() / 1000L) - epochSec
    return when {
        ageSec < 0 -> timestampFormatter.format(Date(epochSec * 1000L))
        ageSec < 60 -> "just now (${ageSec}s ago)"
        ageSec < 3600 -> "${ageSec / 60} min ago"
        ageSec < 86400 -> "${ageSec / 3600} h ago · " + timestampFormatter.format(Date(epochSec * 1000L))
        else -> "${ageSec / 86400} d ago"
    }
}
