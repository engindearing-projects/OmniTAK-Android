package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import soy.engindearing.omnitak.mobile.data.symbology.Affiliation
import soy.engindearing.omnitak.mobile.data.symbology.CoTTypeDefinition
import soy.engindearing.omnitak.mobile.data.symbology.MilStdIconCache
import soy.engindearing.omnitak.mobile.data.symbology.MilStdIconService
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent
import soy.engindearing.omnitak.mobile.ui.theme.TacticalBackground
import soy.engindearing.omnitak.mobile.ui.theme.TacticalSurface

/**
 * Issue #98 (Phase 1) — TAK icon-suite picker.
 *
 * Modal bottom sheet that exposes the full MIL-STD-2525 catalogue
 * ([MilStdIconService.getAllDefinitions], 108 symbols loaded from
 * `assets/cot_types.json`) so the operator can pick a *specific* CoT type
 * — infantry, armor, aircraft, vessel — when placing a marker, instead of
 * being limited to a generic per-affiliation point. The chosen CoT type is
 * handed back as a raw string and flows straight into the placed
 * [soy.engindearing.omnitak.mobile.data.CoTEvent.type], so the symbol
 * renders through the same [soy.engindearing.omnitak.mobile.ui.components.ContactSymbolLayer]
 * / Cesium milsymbol path that received markers already use — closing the
 * "standard TAK icon sets … selectable when placing markers" half of #98.
 *
 * Modal-only (never compresses the full-screen map, per
 * `feedback_omnitak_fullscreen_map`). Mirrors the FEMA palette
 * ([FemaMarkerPaletteSheet]) styling so the two marker pickers feel like
 * one family.
 *
 * The catalogue is grouped by affiliation. A free-text filter narrows by
 * label or CoT type so the long list stays usable one-handed. Each tile
 * rasterises its SVG via [MilStdIconCache] off the main thread.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkerIconPickerSheet(
    visible: Boolean,
    /** Highlighted on open so the operator sees their current choice. */
    selectedCotType: String?,
    onPick: (CoTTypeDefinition) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }

    // Snapshot the active catalogue once per open. getAllDefinitions() is a
    // volatile read of an immutable list, so this is cheap and stable.
    val all = remember { MilStdIconService.getAllDefinitions() }
    val filtered = remember(query, all) {
        val q = query.trim()
        if (q.isEmpty()) all
        else all.filter {
            it.label.contains(q, ignoreCase = true) ||
                it.value.contains(q, ignoreCase = true) ||
                it.description.contains(q, ignoreCase = true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = TacticalBackground,
        scrimColor = Color.Black.copy(alpha = 0.35f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                "Marker Icon",
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "MIL-STD-2525 · ${all.size} SYMBOLS",
                color = TacticalAccent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "Pick the symbol this marker should use. Sent over CoT so peers see the same icon.",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search icons") },
                singleLine = true,
                colors = pickerFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))

            if (filtered.isEmpty()) {
                Text(
                    "No symbols match “${query.trim()}”.",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                // The grid scrolls inside the (fully-expanded) sheet. Cap its
                // height so the search field stays pinned and the map scrim is
                // never fully hidden.
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 84.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp),
                ) {
                    items(filtered, key = { it.value }) { def ->
                        IconTile(
                            def = def,
                            selected = def.value == selectedCotType,
                            onClick = { onPick(def) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun IconTile(
    def: CoTTypeDefinition,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    // Rasterise the SVG off the main thread; null until ready (and on the
    // rare asset miss, where the affiliation frame color below carries the
    // affiliation read instead).
    val bitmap by produceState<ImageBitmap?>(initialValue = null, def.value) {
        value = withContext(Dispatchers.Default) {
            MilStdIconCache.bitmapFor(context, def.value, sizePx = 96)?.asImageBitmap()
        }
    }
    val frame = def.affiliation.frameColor

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) TacticalAccent.copy(alpha = 0.18f) else TacticalSurface)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) TacticalAccent else frame.copy(alpha = 0.35f),
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
    ) {
        Box(
            modifier = Modifier.size(44.dp),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = bitmap
            if (bmp != null) {
                androidx.compose.foundation.Image(
                    bitmap = bmp,
                    contentDescription = def.label,
                    modifier = Modifier.size(40.dp),
                )
            } else {
                // Asset still rendering, or missing — show the affiliation
                // frame color as a placeholder dot so the tile never reads empty.
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(frame.copy(alpha = 0.5f)),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            def.label,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
            fontSize = 9.sp,
            lineHeight = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun pickerFieldColors() = TextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onBackground,
    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
    focusedContainerColor = TacticalSurface,
    unfocusedContainerColor = TacticalSurface,
    focusedIndicatorColor = TacticalAccent,
    unfocusedIndicatorColor = TacticalAccent.copy(alpha = 0.4f),
    focusedLabelColor = TacticalAccent,
    unfocusedLabelColor = TacticalAccent.copy(alpha = 0.6f),
    cursorColor = TacticalAccent,
)
