package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import soy.engindearing.omnitak.mobile.data.symbology.IconPackRegistry
import soy.engindearing.omnitak.mobile.data.symbology.MilStdIconCache
import soy.engindearing.omnitak.mobile.data.symbology.MilStdIconService
import soy.engindearing.omnitak.mobile.data.symbology.TakIconRegistry
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent
import soy.engindearing.omnitak.mobile.ui.theme.TacticalBackground
import soy.engindearing.omnitak.mobile.ui.theme.TacticalSurface

/**
 * Issue #98 — what the icon picker hands back. Either a MIL-STD-2525 symbol
 * (carries only a CoT [type]) or a standard TAK icon-suite icon — Spot Map,
 * Markers, or Google — which additionally carries the `<usericon iconsetpath>`
 * and (Spot Map only) the swatch [argbHex] so the placed marker round-trips
 * byte-for-byte with ATAK / iTAK.
 */
data class MarkerIconChoice(
    val cotType: String,
    val iconsetPath: String? = null,
    val argbHex: String? = null,
    val label: String,
)

/** CoT type ridden by Markers / Google placements. Those packs have no
 *  dedicated type the way Spot Map owns `b-m-p-s-m`; an unknown-ground point is
 *  the sensible cross-client fallback while the `usericon iconsetpath` carries
 *  the actual glyph. */
private const val BADGE_MARKER_COT_TYPE = "a-u-G"

/**
 * Issue #98 (Phase 1) — TAK icon-suite picker.
 *
 * Modal bottom sheet exposing the standard TAK icon sets so the operator can
 * pick a *specific* symbol when placing a marker instead of being limited to a
 * generic per-affiliation point:
 *  - the standard **Spot Map** set (ATAK's coloured points, the most common
 *    script-pushed iconset) — selecting one emits the canonical `b-m-p-s-m`
 *    CoT type + `COT_MAPPING_SPOTMAP/{color}` `usericon` path so peers render
 *    the identical dot; and
 *  - the full **MIL-STD-2525** catalogue ([MilStdIconService.getAllDefinitions],
 *    108 symbols from `assets/cot_types.json`) — infantry, armor, aircraft, …
 *
 * plus the **Markers** and **Google** packs (Material-Symbols / Apache-2.0
 * glyphs on a tinted badge — see [TakIconRegistry]) so received CoT carrying
 * those iconset paths resolves and the same icons are selectable when placing.
 *
 * The choice is handed back as a [MarkerIconChoice] and flows into the placed
 * [soy.engindearing.omnitak.mobile.data.CoTEvent], so the symbol renders
 * through the same [ContactSymbolLayer] / Cesium path received markers use —
 * closing the "standard TAK icon sets … selectable when placing markers" half
 * of #98. (Per-marker import of arbitrary custom iconsets is Phase 2.)
 *
 * Modal-only (never compresses the full-screen map, per
 * `feedback_omnitak_fullscreen_map`). Mirrors the FEMA palette
 * ([FemaMarkerPaletteSheet]) styling so the marker pickers feel like one family.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkerIconPickerSheet(
    visible: Boolean,
    /** Highlighted on open so the operator sees their current choice. */
    selectedCotType: String?,
    /** The marker's current iconset path, so a Spot Map pick stays highlighted
     *  on re-open even though several spot colours share one CoT type. */
    selectedIconsetPath: String? = null,
    onPick: (MarkerIconChoice) -> Unit,
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
                "Pick the symbol this marker should use. Sent over CoT so peers see the same icon.",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )

            // ── Spot Map set ──────────────────────────────────────────────
            // ATAK's coloured points — the most common script-pushed iconset.
            // A horizontal swatch row keeps it compact above the MIL-STD grid.
            Text(
                "SPOT MAP",
                color = TacticalAccent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 6.dp, bottom = 10.dp),
            ) {
                for (spot in TakIconRegistry.selectableSpotIcons) {
                    SpotSwatch(
                        spot = spot,
                        selected = selectedIconsetPath?.equals(spot.iconsetPath, ignoreCase = true) == true,
                        onClick = {
                            onPick(
                                MarkerIconChoice(
                                    cotType = TakIconRegistry.SpotIcon.COT_TYPE,
                                    iconsetPath = spot.iconsetPath,
                                    argbHex = spot.argbHex,
                                    label = "Spot ${spot.displayName}",
                                ),
                            )
                        },
                    )
                }
            }

            // ── Markers set ───────────────────────────────────────────────
            // ATAK's general-purpose marker glyphs (flag / warning / vehicle …).
            BadgeIconRow(
                title = "MARKERS",
                icons = TakIconRegistry.selectableMarkerIcons,
                selectedIconsetPath = selectedIconsetPath,
                onPick = onPick,
            )

            // ── Google POI set ────────────────────────────────────────────
            // ATAK's Google place-marker pack (airport / hospital / gas …).
            BadgeIconRow(
                title = "GOOGLE",
                icons = TakIconRegistry.selectableGoogleIcons,
                selectedIconsetPath = selectedIconsetPath,
                onPick = onPick,
            )

            // ── Imported custom packs (Phase 2) ───────────────────────────
            // Each pack imported via file-picker / data package shows its own
            // horizontally-scrolling row. Empty when no packs have been imported.
            val context = LocalContext.current
            val importedPacks = remember {
                IconPackRegistry.get(context).allPacks()
            }
            for (pack in importedPacks) {
                ImportedPackRow(
                    pack = pack,
                    selectedIconsetPath = selectedIconsetPath,
                    onPick = onPick,
                )
            }

            Text(
                "MIL-STD-2525 · ${all.size} SYMBOLS",
                color = TacticalAccent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 6.dp),
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
                            // A MIL-STD tile is the active choice only when the
                            // marker carries no iconset path (a Spot Map pick
                            // shares the b-m-p-s-m type but owns the highlight).
                            selected = selectedIconsetPath == null && def.value == selectedCotType,
                            onClick = {
                                onPick(
                                    MarkerIconChoice(cotType = def.value, label = def.label),
                                )
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * A horizontally-scrolling row of bitmap-glyph icons for one badge pack
 * (Markers / Google). Each tile renders the same badge bitmap the map draws and,
 * when tapped, emits the pack's canonical `usericon iconsetpath` so the placed
 * marker round-trips to ATAK / iTAK. These packs carry no per-icon colour, so a
 * generic unknown-ground CoT type rides along as the receiver's fallback while
 * the iconset path carries the real glyph.
 */
@Composable
private fun <T> BadgeIconRow(
    title: String,
    icons: List<T>,
    selectedIconsetPath: String?,
    onPick: (MarkerIconChoice) -> Unit,
) where T : TakIconRegistry.BadgeIcon {
    Text(
        title,
        color = TacticalAccent,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 6.dp, bottom = 10.dp),
    ) {
        for (icon in icons) {
            BadgeSwatch(
                icon = icon,
                selected = selectedIconsetPath?.equals(icon.iconsetPath, ignoreCase = true) == true,
                onClick = {
                    onPick(
                        MarkerIconChoice(
                            // No dedicated CoT type for these packs; an
                            // unknown-ground point is the cross-client fallback.
                            cotType = BADGE_MARKER_COT_TYPE,
                            iconsetPath = icon.iconsetPath,
                            argbHex = null,
                            label = "${icon.pack.displayName} ${icon.displayName}",
                        ),
                    )
                },
            )
        }
    }
}

/** A single badge-pack icon tile — the rendered Markers / Google glyph the
 *  operator taps to place that marker. Matches the badge bitmap [TakIconRegistry]
 *  draws on the map so the picker preview and the placed marker are identical. */
@Composable
private fun BadgeSwatch(
    icon: TakIconRegistry.BadgeIcon,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, icon.token) {
        value = withContext(Dispatchers.Default) {
            TakIconRegistry.bitmapFor(context, icon, sizePx = 96).asImageBitmap()
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) TacticalAccent.copy(alpha = 0.18f) else TacticalSurface)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) TacticalAccent else Color(icon.badgeArgb).copy(alpha = 0.5f),
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 8.dp),
    ) {
        Box(
            modifier = Modifier.size(36.dp),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = bitmap
            if (bmp != null) {
                androidx.compose.foundation.Image(
                    bitmap = bmp,
                    contentDescription = icon.displayName,
                    modifier = Modifier.size(32.dp),
                )
            } else {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(icon.badgeArgb).copy(alpha = 0.6f)),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            icon.displayName,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
            fontSize = 9.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A single Spot Map colour swatch — a filled dot the operator taps to place
 *  an ATAK spot point. Matches the runtime-rendered dot in [TakIconRegistry]. */
@Composable
private fun SpotSwatch(
    spot: TakIconRegistry.SpotIcon,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) TacticalAccent.copy(alpha = 0.18f) else TacticalSurface)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) TacticalAccent else spot.color.copy(alpha = 0.5f),
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(spot.color)
                .border(1.dp, Color.Black.copy(alpha = 0.4f), CircleShape),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            spot.displayName,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
            fontSize = 9.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
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

/**
 * Issue #98 Phase 2 — horizontally-scrolling row for one imported iconset pack.
 * Each tile shows the icon's image (loaded off the main thread from app-private
 * storage) and its name. Tapping emits the ATAK canonical iconsetpath so the
 * placed marker round-trips to ATAK / iTAK peers.
 */
@Composable
private fun ImportedPackRow(
    pack: IconPackRegistry.ImportedPack,
    selectedIconsetPath: String?,
    onPick: (MarkerIconChoice) -> Unit,
) {
    if (pack.icons.isEmpty()) return
    val context = LocalContext.current
    val registry = remember { IconPackRegistry.get(context) }

    Text(
        pack.name.uppercase(),
        color = TacticalAccent,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = androidx.compose.ui.Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 6.dp, bottom = 10.dp),
    ) {
        for (icon in pack.icons) {
            val iconsetPath = "${pack.uid}/${icon.pathToken()}"
            val bitmap by produceState<ImageBitmap?>(initialValue = null, icon.filename) {
                value = withContext(Dispatchers.IO) {
                    registry.loadBitmap(pack, icon)?.asImageBitmap()
                }
            }
            val isSelected = selectedIconsetPath.equals(iconsetPath, ignoreCase = true)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = androidx.compose.ui.Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) TacticalAccent.copy(alpha = 0.18f) else TacticalSurface)
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) TacticalAccent else TacticalAccent.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(10.dp),
                    )
                    .clickable {
                        onPick(
                            MarkerIconChoice(
                                cotType = BADGE_MARKER_COT_TYPE,
                                iconsetPath = iconsetPath,
                                argbHex = null,
                                label = "${pack.name} ${icon.name}",
                            ),
                        )
                    }
                    .padding(vertical = 8.dp, horizontal = 8.dp),
            ) {
                Box(
                    modifier = androidx.compose.ui.Modifier.size(36.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = bitmap
                    if (bmp != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bmp,
                            contentDescription = icon.name,
                            modifier = androidx.compose.ui.Modifier.size(32.dp),
                        )
                    } else {
                        Box(
                            androidx.compose.ui.Modifier
                                .size(20.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(TacticalAccent.copy(alpha = 0.4f)),
                        )
                    }
                }
                Spacer(androidx.compose.ui.Modifier.height(4.dp))
                Text(
                    icon.name,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
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
