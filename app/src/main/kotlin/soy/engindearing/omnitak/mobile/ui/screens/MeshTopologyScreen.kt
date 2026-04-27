package soy.engindearing.omnitak.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import soy.engindearing.omnitak.mobile.OmniTAKApp
import soy.engindearing.omnitak.mobile.data.MeshNode
import soy.engindearing.omnitak.mobile.data.MeshtasticCoTConverter
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent
import soy.engindearing.omnitak.mobile.ui.theme.TacticalBackground
import soy.engindearing.omnitak.mobile.ui.theme.TacticalSurface

/**
 * Compose port of iOS `MeshTopologyView`'s list mode. Renders the
 * current Meshtastic node table with detail rows (callsign, hex node
 * id, SNR, hop distance, battery, last-heard relative time) sorted by
 * recency. The graph view is intentionally deferred — see TODO inside.
 *
 * "Publish All to TAK" runs every node with a known position through
 * [MeshtasticCoTConverter.nodeToCoT] and dumps the resulting events
 * into [soy.engindearing.omnitak.mobile.domain.ContactStore.ingest],
 * regardless of whether the live bridge is currently enabled — handy
 * for one-shot publishing when auto-publish is paused.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshTopologyScreen(onBack: () -> Unit = {}) {
    val app = LocalContext.current.applicationContext as OmniTAKApp
    val mesh = app.meshtastic
    val nodes by mesh.nodes.collectAsState()
    val sorted = nodes.values.sortedByDescending { it.lastHeardEpoch }
    val withPositions = sorted.filter { it.position != null }

    Scaffold(
        containerColor = TacticalBackground,
        topBar = {
            TopAppBar(
                title = { Text("Mesh Topology", color = MaterialTheme.colorScheme.onBackground) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TacticalAccent,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TacticalBackground,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { inner: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NetworkSummaryCard(nodes = sorted)

            if (withPositions.isNotEmpty()) {
                Button(
                    onClick = {
                        // One-shot publish — bypass the bridge so it
                        // works even when auto-publish is off.
                        for (n in withPositions) {
                            val ev = MeshtasticCoTConverter.nodeToCoT(n) ?: continue
                            app.contactStore.ingest(ev)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TacticalAccent,
                        contentColor = Color.Black,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Filled.Map,
                        contentDescription = null,
                        tint = Color.Black,
                    )
                    Text(
                        "  Publish all ${withPositions.size} to TAK",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            HorizontalDivider(color = TacticalSurface)

            if (sorted.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(sorted, key = { it.id }) { node ->
                        TopologyRow(node, isOwnNode = node.id.toUInt() == mesh.myNodeNum)
                    }
                }
            }
        }
    }
    // TODO: graph view (iOS `MeshTopologyView.swift::meshGraphView`).
    // Lower priority than the list — defer until we have something to
    // visualise besides hop count rings.
}

@Composable
private fun NetworkSummaryCard(nodes: List<MeshNode>) {
    val total = nodes.size
    val direct = nodes.count { (it.hopDistance ?: Int.MAX_VALUE) <= 1 }
    val avgHops = if (total == 0) 0 else
        nodes.mapNotNull { it.hopDistance }.let { hops ->
            if (hops.isEmpty()) 0 else hops.sum() / hops.size
        }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(TacticalSurface)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "NETWORK SUMMARY",
            color = TacticalAccent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelMedium,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            SummaryStat(label = "Total", value = "$total")
            SummaryStat(label = "Direct", value = "$direct")
            SummaryStat(label = "Avg Hops", value = "$avgHops")
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            label,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "No nodes discovered",
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Mesh nodes will appear here as NodeInfo frames arrive.",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TopologyRow(node: MeshNode, isOwnNode: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(TacticalSurface)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                node.longName.ifBlank { node.shortName.ifBlank { "Node !${node.idHex}" } },
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (isOwnNode) {
                Text(
                    "YOU",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(TacticalAccent)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            node.hopDistance?.let { hops ->
                Text(
                    " $hops hop${if (hops == 1) "" else "s"}",
                    color = hopColor(hops),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Text(
            "!${node.idHex}",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
        )
        node.position?.let { pos ->
            Text(
                "lat ${"%.5f".format(pos.lat)}  lon ${"%.5f".format(pos.lon)}" +
                    (pos.altitudeM?.let { "  alt ${it}m" } ?: ""),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            node.snr?.let {
                Text(
                    "SNR ${"%.1f".format(it)}dB",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            node.batteryLevel?.let {
                Text(
                    "Bat $it%",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                relativeTime(node.lastHeardEpoch),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun hopColor(hops: Int): Color = when {
    hops <= 0 -> Color(0xFF34C759)   // green — direct/own
    hops == 1 -> Color(0xFF4FA8FF)   // blue
    hops == 2 -> Color(0xFFFF9F0A)   // orange
    else -> Color(0xFFFF3B30)        // red — distant
}

private fun relativeTime(epochSeconds: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (epochSeconds <= 0) return "—"
    val seconds = (nowMs / 1_000) - epochSeconds
    return when {
        seconds < 0 -> "just now"
        seconds < 60 -> "${seconds}s ago"
        seconds < 3_600 -> "${seconds / 60}m ago"
        seconds < 86_400 -> "${seconds / 3_600}h ago"
        else -> "${seconds / 86_400}d ago"
    }
}
