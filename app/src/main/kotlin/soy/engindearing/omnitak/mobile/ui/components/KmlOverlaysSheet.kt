package soy.engindearing.omnitak.mobile.ui.components

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import soy.engindearing.omnitak.mobile.OmniTAKApp
import soy.engindearing.omnitak.mobile.i18n.Loc
import soy.engindearing.omnitak.mobile.data.KmlVectorOverlay
import soy.engindearing.omnitak.mobile.data.MBTilesOverlay
import soy.engindearing.omnitak.mobile.data.RasterOverlay
import java.io.File

private val PALETTE = listOf(
    "#A78BFA", "#5AC8FA", "#34C759", "#FF9F0A", "#FF375F",
    "#FFD60A", "#0A84FF", "#FF453A", "#30D158", "#FFFFFF",
)

private fun parseColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Magenta)

/**
 * "Map Overlays" — full CRUD over imported KML/KMZ vector overlays.
 * List → tap an overlay to edit (rename, recolor, opacity, line width,
 * visibility, zoom-to-fit, delete). Backed by KmlVectorOverlayStore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KmlOverlaysSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as OmniTAKApp
    val store = app.kmlOverlayStore
    val mbStore = app.mbtilesOverlayStore
    val rasterStore = app.rasterOverlayStore
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState()

    val overlays by store.overlays.collectAsState()
    val mbtiles by mbStore.overlays.collectAsState()
    val imagery by rasterStore.overlays.collectAsState()
    val importing by store.isImporting.collectAsState()
    val rasterImporting by rasterStore.isImporting.collectAsState()
    val status by store.status.collectAsState()
    val kmlError by store.lastError.collectAsState()
    val rasterError by rasterStore.lastError.collectAsState()
    // MBTiles/GPKG failures were invisible: the store sets lastError
    // ("… import failed: …") but the sheet never collected it, so a
    // corrupt pick did nothing visible. copyError covers the step before
    // the stores — a content-resolver read that dies mid-copy.
    val mbError by mbStore.lastError.collectAsState()
    var copyError by remember { mutableStateOf<String?>(null) }
    val error = copyError ?: kmlError ?: rasterError ?: mbError

    var editingId by remember { mutableStateOf<String?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            copyError = null
            scope.launch(Dispatchers.IO) {
                val name = queryDisplayName(context, uri) ?: "overlay.kml"
                val tmp = File(context.cacheDir, name)
                val copied = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    } ?: error("no readable stream for this document")
                }
                if (copied.isFailure) {
                    // Don't proceed to "import" an empty/partial temp file —
                    // tell the operator why nothing appeared instead.
                    copyError = Loc.t(
                        "overlays.importReadError",
                        name,
                        copied.exceptionOrNull()?.message ?: "unknown error",
                    )
                    tmp.delete()
                    return@launch
                }
                val lower = name.lowercase()
                if (lower.endsWith(".mbtiles") || lower.endsWith(".gpkg")) {
                    val ok = if (lower.endsWith(".gpkg")) mbStore.importGPKG(tmp, name) else mbStore.importMBTiles(tmp, name)
                    if (ok) {
                        mbStore.overlays.value.lastOrNull()?.takeIf { it.hasBounds }?.let {
                            KmlOverlayEvents.requestZoomBounds(it.north, it.south, it.east, it.west)
                        }
                    }
                } else if (lower.endsWith(".tif") || lower.endsWith(".tiff")) {
                    if (rasterStore.importGeoTIFF(tmp, name)) {
                        rasterStore.overlays.value.lastOrNull()?.let {
                            KmlOverlayEvents.requestZoomBounds(it.north, it.south, it.east, it.west)
                        }
                    }
                } else if (lower.endsWith(".pdf")) {
                    if (rasterStore.importGeoPDF(tmp, name)) {
                        rasterStore.overlays.value.lastOrNull()?.let {
                            KmlOverlayEvents.requestZoomBounds(it.north, it.south, it.east, it.west)
                        }
                    }
                } else if (lower.endsWith(".kmz") && rasterStore.importGroundOverlay(tmp, name)) {
                    // KMZ image overlay (GroundOverlay).
                    rasterStore.overlays.value.lastOrNull()?.let {
                        KmlOverlayEvents.requestZoomBounds(it.north, it.south, it.east, it.west)
                    }
                } else {
                    store.importKml(tmp, name)
                    store.overlays.value.lastOrNull()?.let { KmlOverlayEvents.requestZoom(it) }
                }
                tmp.delete()
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Color(0xFF0F1115)) {
        val editing = overlays.firstOrNull { it.id == editingId }
        if (editing != null) {
            OverlayEditor(
                overlay = editing,
                onBack = { editingId = null },
                onRename = { store.rename(editing.id, it) },
                onColor = { store.setColor(editing.id, it) },
                onOpacity = { store.setOpacity(editing.id, it) },
                onLineWidth = { store.setLineWidth(editing.id, it) },
                onVisible = { store.setVisible(editing.id, it) },
                onZoom = { KmlOverlayEvents.requestZoom(editing); onDismiss() },
                onDelete = { store.remove(editing.id); editingId = null },
            )
        } else {
            OverlayList(
                overlays = overlays,
                imagery = imagery,
                mbtiles = mbtiles,
                importing = importing || rasterImporting,
                status = status,
                error = error,
                onImport = { picker.launch(arrayOf("*/*")) },
                onSelect = { editingId = it },
                onToggle = { id, v -> store.setVisible(id, v) },
                onImgToggle = { id, v -> rasterStore.setVisible(id, v) },
                onImgZoom = { o ->
                    KmlOverlayEvents.requestZoomBounds(o.north, o.south, o.east, o.west)
                    onDismiss()
                },
                onImgDelete = { rasterStore.remove(it) },
                onMbToggle = { id, v -> mbStore.setVisible(id, v) },
                onMbZoom = { o ->
                    if (o.hasBounds) {
                        KmlOverlayEvents.requestZoomBounds(o.north, o.south, o.east, o.west)
                        onDismiss()
                    }
                },
                onMbDelete = { mbStore.remove(it) },
                onDeleteAll = { confirmDeleteAll = true },
            )
        }
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete all overlays?") },
            text = { Text("This removes every imported overlay (vector + imagery + tiles). It can't be undone.") },
            confirmButton = { TextButton(onClick = { store.removeAll(); rasterStore.removeAll(); mbStore.removeAll(); confirmDeleteAll = false }) { Text("Delete All") } },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun OverlayList(
    overlays: List<KmlVectorOverlay>,
    imagery: List<RasterOverlay>,
    mbtiles: List<MBTilesOverlay>,
    importing: Boolean,
    status: String,
    error: String?,
    onImport: () -> Unit,
    onSelect: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onImgToggle: (String, Boolean) -> Unit,
    onImgZoom: (RasterOverlay) -> Unit,
    onImgDelete: (String) -> Unit,
    onMbToggle: (String, Boolean) -> Unit,
    onMbZoom: (MBTilesOverlay) -> Unit,
    onMbDelete: (String) -> Unit,
    onDeleteAll: () -> Unit,
) {
    // #222: one row per imported overlay plus the Imagery and Tiles sections, so
    // the list outgrows a landscape phone after a few imports.
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Text("Map Overlays", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

        Row(
            modifier = Modifier.fillMaxWidth().clickable { onImport() }.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.FileDownload, contentDescription = null, tint = Color(0xFF4FA8FF))
            Spacer(Modifier.width(14.dp))
            Text("Import KML / KMZ / GeoTIFF / GeoPDF / MBTiles / GPKG", color = Color(0xFF4FA8FF), fontSize = 13.sp, maxLines = 1)
        }

        if (importing) {
            Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(status.ifEmpty { "Importing…" }, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
            }
        }
        error?.let { Text(it, color = Color(0xFFFF6B6B), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }

        Text("Vector files render as a GPU layer; MBTiles render as raster tiles. Tap a vector overlay to rename/restyle.",
            color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))

        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

        if (overlays.isEmpty() && imagery.isEmpty() && mbtiles.isEmpty()) {
            Text("No overlays yet.", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
        } else {
            overlays.forEach { overlay ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(overlay.id) }.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.size(14.dp).clip(CircleShape).background(parseColor(overlay.colorHex)))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(overlay.name, color = Color.White, fontSize = 16.sp, maxLines = 1)
                        Text("${overlay.featureCount} features", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                    }
                    IconButton(onClick = { onToggle(overlay.id, !overlay.visible) }) {
                        Icon(
                            if (overlay.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = "Toggle visibility",
                            tint = if (overlay.visible) Color(0xFF4FA8FF) else Color.White.copy(alpha = 0.4f),
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(start = 20.dp), color = Color.White.copy(alpha = 0.06f))
            }

            if (imagery.isNotEmpty()) {
                Text("Imagery", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                imagery.forEach { o ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onImgZoom(o) }.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.size(14.dp).clip(CircleShape).background(Color(0xFF30D5C8)))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(o.name, color = Color.White, fontSize = 16.sp, maxLines = 1)
                            Text("Image overlay", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                        }
                        IconButton(onClick = { onImgToggle(o.id, !o.visible) }) {
                            Icon(
                                if (o.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = "Toggle visibility",
                                tint = if (o.visible) Color(0xFF4FA8FF) else Color.White.copy(alpha = 0.4f),
                            )
                        }
                        IconButton(onClick = { onImgDelete(o.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Color(0xFFFF3B30))
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(start = 20.dp), color = Color.White.copy(alpha = 0.06f))
                }
            }

            if (mbtiles.isNotEmpty()) {
                Text("Tiles (MBTiles)", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                mbtiles.forEach { o ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onMbZoom(o) }.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.size(14.dp).clip(CircleShape).background(Color(0xFFFF9F0A)))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(o.name, color = Color.White, fontSize = 16.sp, maxLines = 1)
                            Text("Tiles z${o.minZoom}–${o.maxZoom}", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                        }
                        IconButton(onClick = { onMbToggle(o.id, !o.visible) }) {
                            Icon(
                                if (o.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = "Toggle visibility",
                                tint = if (o.visible) Color(0xFF4FA8FF) else Color.White.copy(alpha = 0.4f),
                            )
                        }
                        IconButton(onClick = { onMbDelete(o.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Color(0xFFFF3B30))
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(start = 20.dp), color = Color.White.copy(alpha = 0.06f))
                }
            }

            TextButton(onClick = onDeleteAll, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = null, tint = Color(0xFFFF3B30))
                Spacer(Modifier.width(8.dp))
                Text("Delete All Overlays", color = Color(0xFFFF3B30))
            }
        }
    }
}

@Composable
private fun OverlayEditor(
    overlay: KmlVectorOverlay,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onColor: (String) -> Unit,
    onOpacity: (Float) -> Unit,
    onLineWidth: (Float) -> Unit,
    onVisible: (Boolean) -> Unit,
    onZoom: () -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(overlay.id) { mutableStateOf(overlay.name) }
    var confirmDelete by remember { mutableStateOf(false) }

    // #222: the editor is taller than a landscape phone; Zoom to overlay and
    // Delete overlay sat below the fold with no way to reach them.
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, end = 20.dp, top = 4.dp)) {
            IconButton(onClick = { onRename(name); onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text("Edit Overlay", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Visible", color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Switch(checked = overlay.visible, onCheckedChange = onVisible)
        }

        Text("Color", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.padding(start = 20.dp, top = 4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PALETTE.take(5).forEach { hex -> ColorSwatch(hex, overlay.colorHex.equals(hex, true)) { onColor(hex) } }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PALETTE.drop(5).forEach { hex -> ColorSwatch(hex, overlay.colorHex.equals(hex, true)) { onColor(hex) } }
        }

        Text("Opacity — ${(overlay.opacity * 100).toInt()}%", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp,
            modifier = Modifier.padding(start = 20.dp))
        Slider(value = overlay.opacity, onValueChange = onOpacity, valueRange = 0.05f..1.0f,
            modifier = Modifier.padding(horizontal = 20.dp))

        Text("Line width — ${"%.1f".format(overlay.lineWidth)}×", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp,
            modifier = Modifier.padding(start = 20.dp))
        Slider(value = overlay.lineWidth, onValueChange = onLineWidth, valueRange = 0.5f..6.0f,
            modifier = Modifier.padding(horizontal = 20.dp))

        Text("${overlay.featureCount} features", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))

        HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 6.dp))

        Row(
            modifier = Modifier.fillMaxWidth().clickable { onZoom() }.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.MyLocation, contentDescription = null, tint = Color(0xFF4FA8FF))
            Spacer(Modifier.width(14.dp))
            Text("Zoom to overlay", color = Color(0xFF4FA8FF), fontSize = 16.sp)
        }
        Row(
            modifier = Modifier.fillMaxWidth().clickable { confirmDelete = true }.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Delete, contentDescription = null, tint = Color(0xFFFF3B30))
            Spacer(Modifier.width(14.dp))
            Text("Delete overlay", color = Color(0xFFFF3B30), fontSize = 16.sp)
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this overlay?") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ColorSwatch(hex: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(34.dp).clip(CircleShape).background(parseColor(hex)).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Text("✓", color = if (hex.equals("#FFFFFF", true)) Color.Black else Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

private fun queryDisplayName(context: android.content.Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
