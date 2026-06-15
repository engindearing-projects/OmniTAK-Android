package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import soy.engindearing.omnitak.mobile.OmniTAKApp
import soy.engindearing.omnitak.mobile.data.MapProvider
import soy.engindearing.omnitak.mobile.data.offline.BoundingBox
import soy.engindearing.omnitak.mobile.data.offline.OfflineRegion
import soy.engindearing.omnitak.mobile.data.offline.TileMath
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent
import kotlin.math.roundToInt

/**
 * ATAK-style offline map download (#120). The operator picks a region — by
 * default the current viewport [initialBbox] — and a min/max zoom range, sees
 * a live tile-count + estimated-size readout, then downloads those tiles from
 * the active basemap into a local MBTiles cache that serves with no network.
 *
 * Drag-a-rectangle region selection and the on-map render of cached tiles are
 * device-pending; this sheet ships the viewport-based selection, the
 * count/size estimate, the download with progress + cancel, and region
 * management (list / delete).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMapsSheet(
    initialBbox: BoundingBox?,
    provider: MapProvider,
    customTileUrl: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as OmniTAKApp
    val store = app.offlineRegionStore
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState()

    val regions by store.regions.collectAsState()
    val progress by store.progress.collectAsState()
    val error by store.lastError.collectAsState()

    val template = remember(provider, customTileUrl) {
        TileMath.templateForProvider(provider, customTileUrl)
    }
    val sourceLabel = when (provider) {
        MapProvider.OSM_RASTER -> "OSM"
        MapProvider.TOPO_HINT -> "Topo"
        MapProvider.SATELLITE_HINT -> "Satellite"
        MapProvider.WMTS_CUSTOM -> "Custom"
    }

    var zoomRange by remember { mutableStateOf(10f..15f) }
    val minZoom = zoomRange.start.roundToInt()
    val maxZoom = zoomRange.endInclusive.roundToInt()

    val tileCount = remember(initialBbox, minZoom, maxZoom) {
        if (initialBbox == null) 0L else TileMath.tileCount(initialBbox, minZoom, maxZoom)
    }
    val sizeText = remember(tileCount) { TileMath.humanizeBytes(tileCount * TileMath.AVG_TILE_BYTES) }

    val downloading = progress != null
    val canDownload = initialBbox != null && template != null && tileCount > 0 && !downloading

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Color(0xFF0F1115)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                "Offline maps",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Download the current map view for use with no network.",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(16.dp))

            if (template == null) {
                Text(
                    "Set a tile source first (a custom WMTS/XYZ URL needs a value before it can be cached).",
                    color = Color(0xFFFF9F0A),
                    fontSize = 13.sp,
                )
            } else if (initialBbox == null) {
                Text(
                    "Pan the map to the area you want, then reopen this sheet.",
                    color = Color(0xFFFF9F0A),
                    fontSize = 13.sp,
                )
            } else {
                Text("Source: $sourceLabel", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Zoom $minZoom – $maxZoom",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                )
                RangeSlider(
                    value = zoomRange,
                    onValueChange = { zoomRange = it },
                    valueRange = 0f..TileMath.MAX_ZOOM.toFloat(),
                    // Snap to integer zoom levels (MAX_ZOOM-1 internal stops).
                    steps = TileMath.MAX_ZOOM - 1,
                    enabled = !downloading,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "$tileCount tiles · ~$sizeText",
                    color = TacticalAccent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                )
                if (tileCount > 50_000) {
                    Text(
                        "Large download — consider a tighter area or lower max zoom.",
                        color = Color(0xFFFF9F0A),
                        fontSize = 12.sp,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            if (downloading) {
                val p = progress!!
                LinearProgressIndicator(
                    progress = { p.fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = TacticalAccent,
                )
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "${p.processed} / ${p.total}",
                        color = Color.White.copy(alpha = 0.8f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    )
                    TextButton(onClick = { store.cancel() }) {
                        Text("Cancel", color = Color(0xFFFF453A))
                    }
                }
            } else {
                Button(
                    onClick = {
                        val bbox = initialBbox ?: return@Button
                        val tpl = template ?: return@Button
                        scope.launch {
                            store.download(
                                name = "$sourceLabel z$minZoom-$maxZoom",
                                bbox = bbox, minZoom = minZoom, maxZoom = maxZoom,
                                template = tpl, sourceLabel = sourceLabel,
                            )
                        }
                    },
                    enabled = canDownload,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = TacticalAccent),
                ) {
                    Text("Download this region", color = Color(0xFF0A1628), fontWeight = FontWeight.Bold)
                }
            }

            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error!!, color = Color(0xFFFF453A), fontSize = 12.sp)
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Downloaded regions (${regions.size})",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                if (regions.isNotEmpty()) {
                    TextButton(onClick = { store.removeAll() }) {
                        Text("Clear all", color = Color(0xFFFF453A), fontSize = 13.sp)
                    }
                }
            }

            if (regions.isEmpty()) {
                Text(
                    "No regions yet.",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.height(180.dp)) {
                    items(regions, key = { it.id }) { region ->
                        RegionRow(
                            region = region,
                            onZoom = {
                                KmlOverlayEvents.requestZoomBounds(region.north, region.south, region.east, region.west)
                                onDismiss()
                            },
                            onDelete = { store.remove(region.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RegionRow(region: OfflineRegion, onZoom: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(region.name, color = Color.White, fontSize = 14.sp)
            Text(
                "z${region.minZoom}-${region.maxZoom} · ${region.tileCount} tiles · ${TileMath.humanizeBytes(region.sizeBytes)}",
                color = Color.White.copy(alpha = 0.55f),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
        TextButton(onClick = onZoom) { Text("View", color = TacticalAccent, fontSize = 13.sp) }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete region", tint = Color(0xFFFF453A))
        }
    }
}
