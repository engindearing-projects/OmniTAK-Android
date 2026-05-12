package soy.engindearing.omnitak.mobile.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import soy.engindearing.omnitak.mobile.OmniTAKApp
import soy.engindearing.omnitak.mobile.data.MapProvider
import soy.engindearing.omnitak.mobile.data.UserPrefs
import soy.engindearing.omnitak.mobile.domain.offline.TileMath
import soy.engindearing.omnitak.mobile.domain.offline.TileRegion
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent
import soy.engindearing.omnitak.mobile.ui.theme.TacticalBackground
import soy.engindearing.omnitak.mobile.ui.theme.TacticalSurface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Offline-map region UI. Operators pick a radius around their current
 * position + a zoom range, and the [TileDownloader] pre-fetches every
 * tile inside that box at every zoom level. When cellular drops, the
 * [OfflineTileCache] interceptor serves those same tiles back to
 * MapLibre so the search area stays drawable.
 *
 * Closes K9Blue's TROP ask: offline maps were iOS-only.
 */
@Composable
fun OfflineMapsSection(prefs: UserPrefs) {
    val app = LocalContext.current.applicationContext as OmniTAKApp
    val scope = rememberCoroutineScope()
    val regions by app.offlineTileStore.regions.collectAsState()
    val selfFix by app.locationProvider.fix.collectAsState()
    val progress by app.offlineTileDownloader.progress.collectAsState()

    var radiusKm by remember { mutableIntStateOf(5) }
    var zoomMin by remember { mutableIntStateOf(12) }
    var zoomMax by remember { mutableIntStateOf(15) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Pre-fetch tiles around your current position so the map keeps drawing when you lose cell. Tiles are stored locally and served back to the map automatically when offline.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )

        // Radius picker
        Text(
            "Radius: $radiusKm km",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in listOf(1, 5, 10, 25)) {
                val selected = option == radiusKm
                OutlinedButton(
                    onClick = { radiusKm = option },
                    colors = if (selected) {
                        ButtonDefaults.outlinedButtonColors(
                            containerColor = TacticalAccent.copy(alpha = 0.2f),
                            contentColor = TacticalAccent,
                        )
                    } else {
                        ButtonDefaults.outlinedButtonColors()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("${option}k") }
            }
        }

        // Zoom range picker (just min/max stepped chunks).
        Text(
            "Zoom: $zoomMin–$zoomMax",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { zoomMin = (zoomMin - 1).coerceAtLeast(8) },
                modifier = Modifier.weight(1f),
            ) { Text("Min −") }
            OutlinedButton(
                onClick = { zoomMin = (zoomMin + 1).coerceAtMost(zoomMax) },
                modifier = Modifier.weight(1f),
            ) { Text("Min +") }
            OutlinedButton(
                onClick = { zoomMax = (zoomMax - 1).coerceAtLeast(zoomMin) },
                modifier = Modifier.weight(1f),
            ) { Text("Max −") }
            OutlinedButton(
                onClick = { zoomMax = (zoomMax + 1).coerceAtMost(18) },
                modifier = Modifier.weight(1f),
            ) { Text("Max +") }
        }

        val downloading = progress?.isComplete == false
        val locationReady = selfFix != null
        val tilesPreview = if (locationReady) {
            val fix = selfFix!!
            val bbox = TileMath.bboxAroundPoint(fix.lat, fix.lon, radiusKm * 1000.0)
            TileMath.countTiles(
                north = bbox.north, south = bbox.south,
                east = bbox.east, west = bbox.west,
                zoomMin = zoomMin, zoomMax = zoomMax,
            )
        } else 0
        Text(
            if (locationReady) "Estimate: $tilesPreview tiles (~${tilesPreview * 25 / 1024} MB)"
            else "Waiting for GPS fix — open the Map tab first to grant location permission.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )

        Button(
            onClick = {
                val fix = selfFix ?: return@Button
                val bbox = TileMath.bboxAroundPoint(fix.lat, fix.lon, radiusKm * 1000.0)
                val template = resolveTileTemplate(prefs)
                val tileCount = TileMath.countTiles(
                    bbox.north, bbox.south, bbox.east, bbox.west, zoomMin, zoomMax,
                )
                val region = TileRegion(
                    name = "Region ${SimpleDateFormat("MM-dd HHmm", Locale.US).format(Date())}",
                    north = bbox.north,
                    south = bbox.south,
                    east = bbox.east,
                    west = bbox.west,
                    zoomMin = zoomMin,
                    zoomMax = zoomMax,
                    tileCount = tileCount,
                    urlTemplate = template,
                )
                app.offlineTileStore.upsert(region)
                scope.launch(Dispatchers.IO) {
                    app.offlineTileDownloader.download(region)
                }
            },
            enabled = locationReady && !downloading,
            colors = ButtonDefaults.buttonColors(
                containerColor = TacticalAccent,
                contentColor = TacticalBackground,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (downloading) "Downloading…" else "Download region around current position")
        }

        if (downloading) {
            val p = progress!!
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinearProgressIndicator(
                    progress = { p.fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = TacticalAccent,
                )
                Text(
                    "${p.completedTiles} / ${p.totalTiles} tiles" +
                        if (p.failedTiles > 0) " (${p.failedTiles} failed)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                )
            }
        }

        if (regions.isEmpty()) {
            Text(
                "No regions downloaded yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
            )
        } else {
            for (region in regions) {
                RegionRow(
                    region = region,
                    onDelete = { app.offlineTileStore.delete(region.id) },
                )
            }
        }
    }
}

@Composable
private fun RegionRow(region: TileRegion, onDelete: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(TacticalSurface)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            region.name,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.bodyLarge,
        )
        val sizeLabel = if (region.sizeBytes > 0) {
            "${region.sizeBytes / 1024 / 1024} MB"
        } else "—"
        Text(
            "Z${region.zoomMin}–Z${region.zoomMax} · ${region.downloadedTiles}/${region.tileCount} tiles · $sizeLabel",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
        )
        OutlinedButton(
            onClick = onDelete,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Delete region") }
    }
}

/** Pick the tile URL template the operator already prefers — same
 *  scheme MapLibre is rendering, so the offline tiles match what's on
 *  screen. */
private fun resolveTileTemplate(prefs: UserPrefs): String {
    return when (prefs.mapProvider) {
        MapProvider.OSM_RASTER -> "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        MapProvider.TOPO_HINT -> "https://tile.opentopomap.org/{z}/{x}/{y}.png"
        MapProvider.SATELLITE_HINT ->
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
        MapProvider.WMTS_CUSTOM -> {
            val custom = prefs.customTileUrl
            if (custom.isNotBlank()) custom
            else "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        }
    }
}
