package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.maplibre.android.geometry.LatLng
import soy.engindearing.omnitak.mobile.data.CoTAffiliation
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent
import soy.engindearing.omnitak.mobile.ui.theme.TacticalBackground
import soy.engindearing.omnitak.mobile.ui.theme.TacticalSurface

/** Result payload emitted when the user saves a marker sheet. */
data class MarkerEditResult(
    val callsign: String,
    val affiliation: CoTAffiliation,
    val altitudeMeters: Double?,
    val remarks: String,
    /**
     * Issue #98 — the full CoT type the operator chose from the icon
     * picker (e.g. `a-h-G-U-C-A`). Null means "no specific symbol was
     * picked" — the caller falls back to a generic per-affiliation point
     * (`a-<aff>-G-U-C`), preserving the pre-icon-suite behavior.
     */
    val cotType: String?,
)

/**
 * Bottom sheet for editing a newly-dropped or existing point marker.
 * Fields shipped in this slice: callsign + affiliation. Remarks and
 * altitude arrive in the full marker-edit UI (Slice 11).
 *
 * [initialCallsign] seeds the input; [latLng] is shown read-only so the
 * operator can sanity-check where the marker will land.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkerEditSheet(
    visible: Boolean,
    latLng: LatLng?,
    initialCallsign: String = "",
    initialAffiliation: CoTAffiliation = CoTAffiliation.FRIEND,
    initialAltitude: Double? = null,
    initialRemarks: String = "",
    /** Issue #98 — the marker's current CoT type, so an existing marker
     *  re-opens with the symbol it already carries. Null = no specific
     *  symbol picked yet (generic per-affiliation point). */
    initialCotType: String? = null,
    editing: Boolean = false,
    onSave: (MarkerEditResult) -> Unit,
    onDelete: (() -> Unit)? = null,
    /** When non-null, renders a "Pursue with UAS" button — Map screen
     *  supplies this only when (a) the marker is an existing contact
     *  (editing=true), and (b) a UAS is currently connected. */
    onPursueWithUas: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    // Partial detent + light scrim keep the map visible above the sheet — edit
    // a marker while still seeing where it sits. Drag up for the full form.
    val state = rememberModalBottomSheetState()

    var callsign by remember(initialCallsign) { mutableStateOf(initialCallsign) }
    var affiliation by remember(initialAffiliation) { mutableStateOf(initialAffiliation) }
    var altitudeText by remember(initialAltitude) {
        mutableStateOf(initialAltitude?.let { "%.0f".format(it) } ?: "")
    }
    var remarks by remember(initialRemarks) { mutableStateOf(initialRemarks) }
    // Issue #98 — selected CoT type from the icon picker. Null until the
    // operator picks a specific symbol; the affiliation chips below keep it
    // re-affiliated so picking "armor" then flipping to hostile yields the
    // hostile-armor symbol without re-opening the picker.
    var cotType by remember(initialCotType) { mutableStateOf(initialCotType) }
    var iconPickerOpen by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = TacticalSurface,
        scrimColor = Color.Black.copy(alpha = 0.3f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                if (editing) "Edit Marker" else "Drop Marker",
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge,
            )

            Spacer(Modifier.height(4.dp))
            latLng?.let {
                Text(
                    rememberCoordText(it.latitude, it.longitude),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = callsign,
                onValueChange = { callsign = it },
                label = { Text("Callsign") },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onBackground,
                    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                    focusedContainerColor = TacticalBackground,
                    unfocusedContainerColor = TacticalBackground,
                    focusedIndicatorColor = TacticalAccent,
                    unfocusedIndicatorColor = TacticalAccent.copy(alpha = 0.4f),
                    focusedLabelColor = TacticalAccent,
                    unfocusedLabelColor = TacticalAccent.copy(alpha = 0.6f),
                    cursorColor = TacticalAccent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            Text(
                "Affiliation",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    CoTAffiliation.FRIEND,
                    CoTAffiliation.HOSTILE,
                    CoTAffiliation.NEUTRAL,
                    CoTAffiliation.UNKNOWN,
                ).forEach { a ->
                    AffiliationChip(
                        affiliation = a,
                        selected = affiliation == a,
                        onClick = {
                            affiliation = a
                            // Keep a picked symbol on the new side (#98).
                            cotType = cotType?.let {
                                soy.engindearing.omnitak.mobile.data.symbology
                                    .MilStdIconService.withAffiliation(it, a.code)
                            }
                        },
                    )
                }
            }

            // Issue #98 — symbol / icon row. Tapping opens the MIL-STD-2525
            // picker; the chosen CoT type rides into the result so the placed
            // marker resolves to that exact symbol (same path received markers
            // use). When nothing is picked the marker stays a generic
            // per-affiliation point — the pre-icon-suite behavior.
            Spacer(Modifier.height(16.dp))
            Text(
                "Symbol",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(8.dp))
            MarkerSymbolRow(
                cotType = cotType,
                onClick = { iconPickerOpen = true },
            )

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = altitudeText,
                onValueChange = { altitudeText = it.filter { ch -> ch.isDigit() || ch == '.' || ch == '-' } },
                label = { Text("Altitude (m HAE)") },
                singleLine = true,
                colors = tacticalFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = remarks,
                onValueChange = { remarks = it },
                label = { Text("Remarks") },
                colors = tacticalFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
            )

            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (editing && onDelete != null) {
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = soy.engindearing.omnitak.mobile.ui.theme.HostileRed,
                        ),
                    ) { Text("Delete") }
                }
                if (onPursueWithUas != null) {
                    TextButton(
                        onClick = onPursueWithUas,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = androidx.compose.ui.graphics.Color(0xFF00E5FF),
                        ),
                    ) { Text("Pursue with UAS") }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        onSave(
                            MarkerEditResult(
                                callsign = callsign.trim().ifEmpty { "Marker" },
                                affiliation = affiliation,
                                altitudeMeters = altitudeText.toDoubleOrNull(),
                                remarks = remarks.trim(),
                                cotType = cotType,
                            )
                        )
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TacticalAccent,
                        contentColor = TacticalBackground,
                    ),
                ) { Text(if (editing) "Save" else "Drop") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    // Issue #98 — MIL-STD-2525 icon picker. Picking sets the CoT type and
    // snaps the affiliation chips to match the symbol's own affiliation so
    // the sheet stays internally consistent.
    MarkerIconPickerSheet(
        visible = iconPickerOpen,
        selectedCotType = cotType,
        onPick = { def ->
            cotType = def.value
            affiliation = CoTAffiliation.fromCode(def.value.getOrNull(2))
            iconPickerOpen = false
        },
        onDismiss = { iconPickerOpen = false },
    )
}

/**
 * Issue #98 — current-symbol row inside [MarkerEditSheet]. Shows the
 * rendered MIL-STD glyph + label for the picked CoT type (or a "Choose
 * symbol" prompt when none is picked) and opens the picker on tap.
 */
@Composable
private fun MarkerSymbolRow(
    cotType: String?,
    onClick: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val def = remember(cotType) {
        cotType?.let {
            soy.engindearing.omnitak.mobile.data.symbology.MilStdIconService.getDefinition(it)
        }
    }
    val bitmap by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null, cotType,
    ) {
        value = if (cotType == null) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            soy.engindearing.omnitak.mobile.data.symbology.MilStdIconCache
                .bitmapFor(context, cotType, sizePx = 72)
                ?.asImageBitmap()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(TacticalBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier.size(36.dp),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = bitmap
            if (bmp != null) {
                androidx.compose.foundation.Image(
                    bitmap = bmp,
                    contentDescription = def?.label,
                    modifier = Modifier.size(32.dp),
                )
            } else {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Filled.Category,
                    contentDescription = null,
                    tint = TacticalAccent.copy(alpha = 0.8f),
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            // Prefer the catalogue label; fall back to the raw type for a
            // marker carrying a type the picker doesn't enumerate (e.g. a
            // received RID track being re-typed), then to the prompt.
            Text(
                def?.label ?: cotType ?: "Choose symbol",
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                cotType ?: "Generic point (by affiliation)",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        androidx.compose.material3.Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun tacticalFieldColors() = TextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onBackground,
    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
    focusedContainerColor = TacticalBackground,
    unfocusedContainerColor = TacticalBackground,
    focusedIndicatorColor = TacticalAccent,
    unfocusedIndicatorColor = TacticalAccent.copy(alpha = 0.4f),
    focusedLabelColor = TacticalAccent,
    unfocusedLabelColor = TacticalAccent.copy(alpha = 0.6f),
    cursorColor = TacticalAccent,
)

@Composable
private fun AffiliationChip(
    affiliation: CoTAffiliation,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val color = Color(ContactLayer.previewColor(affiliation))
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) color.copy(alpha = 0.25f) else TacticalBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            affiliation.name.lowercase().replaceFirstChar { it.uppercase() },
            color = if (selected) color else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
