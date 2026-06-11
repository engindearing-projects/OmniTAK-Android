package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mil.nga.mgrs.MGRS
import soy.engindearing.omnitak.mobile.data.Twd97Converter
import soy.engindearing.omnitak.mobile.i18n.Loc

/**
 * "Go to Coordinate" — type a coordinate and jump the map there
 * (optionally dropping a marker). Mirrors the iOS `CoordinateEntryView`.
 *
 * Supports TWD97/TM2 (Taiwan) with a selectable digit-input mode
 * (7+7 absolute vs 5+5 local grid — Gavin's comparison request), plus
 * MGRS and decimal Lat/Lon.
 *
 * The sheet is engine-agnostic: it hands the parsed (lat, lon) and a
 * drop-marker flag back to the caller via [onGo]; MapScreen centres the
 * camera and (optionally) drops a marker using its existing mechanisms.
 *
 * @param refLat / [refLon] current map centre — used to recover the
 *   100 km cell for 5+5 grid input. Null falls back to central Taiwan.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoordinateEntrySheet(
    refLat: Double?,
    refLon: Double?,
    onGo: (lat: Double, lon: Double, dropMarker: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var format by remember { mutableStateOf(EntryFormat.TWD97) }
    var digitMode by remember { mutableStateOf(Twd97Converter.DigitMode.FULL7) }

    // TWD97 fields
    var easting by remember { mutableStateOf("") }
    var northing by remember { mutableStateOf("") }
    // MGRS field
    var mgrsText by remember { mutableStateOf("") }
    // Lat/Lon fields
    var latText by remember { mutableStateOf("") }
    var lonText by remember { mutableStateOf("") }

    var dropMarker by remember { mutableStateOf(true) }

    val parsed: Pair<Double, Double>? = when (format) {
        EntryFormat.TWD97 ->
            if (easting.isBlank() || northing.isBlank()) null
            else Twd97Converter.parse(easting, northing, digitMode, refLat, refLon)
        EntryFormat.MGRS ->
            if (mgrsText.isBlank()) null
            else runCatching {
                val p = MGRS.parse(mgrsText.trim()).toPoint()
                p.latitude to p.longitude
            }.getOrNull()
        EntryFormat.LATLON -> {
            val la = latText.trim().toDoubleOrNull()
            val lo = lonText.trim().toDoubleOrNull()
            if (la != null && lo != null && la in -90.0..90.0 && lo in -180.0..180.0) la to lo else null
        }
    }

    val hasAnyInput = when (format) {
        EntryFormat.TWD97 -> easting.isNotBlank() || northing.isNotBlank()
        EntryFormat.MGRS -> mgrsText.isNotBlank()
        EntryFormat.LATLON -> latText.isNotBlank() || lonText.isNotBlank()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = Color(0xFF0F1115),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                Loc.t("coordentry.title"),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )

            // Format selector
            Segmented(
                options = EntryFormat.entries.map { it to it.label },
                selected = format,
                onSelect = { format = it },
            )

            // Digit-mode toggle — TWD97 only (Gavin's 7+7 vs 5+5).
            if (format == EntryFormat.TWD97) {
                Segmented(
                    options = listOf(
                        Twd97Converter.DigitMode.FULL7 to Twd97Converter.DigitMode.FULL7.label,
                        Twd97Converter.DigitMode.GRID5 to Twd97Converter.DigitMode.GRID5.label,
                    ),
                    selected = digitMode,
                    onSelect = { digitMode = it },
                )
            }

            // Input fields per format
            when (format) {
                EntryFormat.TWD97 -> {
                    val placeholder = "0".repeat(digitMode.digits)
                    NumField(
                        label = Loc.t("coordentry.easting"),
                        value = easting,
                        placeholder = placeholder,
                        onValueChange = { easting = it.filter(Char::isDigit) },
                    )
                    NumField(
                        label = Loc.t("coordentry.northing"),
                        value = northing,
                        placeholder = placeholder,
                        onValueChange = { northing = it.filter(Char::isDigit) },
                    )
                    if (digitMode == Twd97Converter.DigitMode.GRID5) {
                        Caption(Loc.t("coordentry.grid5.note"))
                    }
                }
                EntryFormat.MGRS -> {
                    EntryField(
                        label = "MGRS",
                        value = mgrsText,
                        placeholder = "51R UR 12345 67890",
                        keyboardType = KeyboardType.Text,
                        onValueChange = { mgrsText = it },
                    )
                }
                EntryFormat.LATLON -> {
                    EntryField(
                        label = Loc.t("coordentry.lat"),
                        value = latText,
                        placeholder = "25.0339",
                        keyboardType = KeyboardType.Number,
                        onValueChange = { latText = it },
                    )
                    EntryField(
                        label = Loc.t("coordentry.lon"),
                        value = lonText,
                        placeholder = "121.5645",
                        keyboardType = KeyboardType.Number,
                        onValueChange = { lonText = it },
                    )
                }
            }

            // Live preview
            Text(
                Loc.t("coordentry.preview").uppercase(),
                color = Color(0xFF66D9A0),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
            )
            when {
                parsed != null -> {
                    val (la, lo) = parsed
                    PreviewRow(Loc.t("coordentry.lat"), "%.6f".format(la))
                    PreviewRow(Loc.t("coordentry.lon"), "%.6f".format(lo))
                    // Echo TWD97 readback so Gavin can compare 7+7 vs 5+5.
                    if (Twd97Converter.isWithinBounds(la, lo)) {
                        PreviewRow(
                            "TWD97 7+7",
                            Twd97Converter.formatTwd97(la, lo, Twd97Converter.DigitMode.FULL7),
                        )
                        PreviewRow(
                            "TWD97 5+5",
                            Twd97Converter.formatTwd97(la, lo, Twd97Converter.DigitMode.GRID5),
                        )
                    }
                }
                hasAnyInput -> Text(
                    Loc.t("coordentry.invalid"),
                    color = Color(0xFFFF6B6B),
                    fontSize = 13.sp,
                )
                else -> Caption(Loc.t("coordentry.enterprompt"))
            }

            // Drop-marker toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    Loc.t("coordentry.dropmarker"),
                    color = Color.White,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = dropMarker, onCheckedChange = { dropMarker = it })
            }

            // Go button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(
                        Loc.t("common.cancel"),
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    enabled = parsed != null,
                    onClick = { parsed?.let { (la, lo) -> onGo(la, lo, dropMarker) } },
                ) {
                    Text(
                        Loc.t("coordentry.go"),
                        color = if (parsed != null) Color(0xFF66D9A0) else Color.White.copy(alpha = 0.3f),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                    )
                }
            }
        }
    }
}

/** Format options offered by the entry sheet. */
private enum class EntryFormat(val label: String) {
    TWD97("TWD97"),
    MGRS("MGRS"),
    LATLON("Lat/Lon");
}

@Composable
private fun <T> Segmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.06f)),
    ) {
        options.forEachIndexed { idx, (value, label) ->
            val isSelected = value == selected
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .background(if (isSelected) Color(0xFF66D9A0).copy(alpha = 0.22f) else Color.Transparent)
                    .clickable { onSelect(value) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    label,
                    color = if (isSelected) Color(0xFF66D9A0) else Color.White.copy(alpha = 0.8f),
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 14.sp,
                )
            }
            if (idx < options.size - 1) {
                Row(modifier = Modifier.width(1.dp).height(40.dp).background(Color(0xFF0F1115))) {}
            }
        }
    }
}

@Composable
private fun NumField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
) = EntryField(label, value, placeholder, KeyboardType.Number, onValueChange)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryField(
    label: String,
    value: String,
    placeholder: String,
    keyboardType: KeyboardType,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder, fontFamily = FontFamily.Monospace) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = TextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedContainerColor = Color.White.copy(alpha = 0.04f),
            unfocusedContainerColor = Color.White.copy(alpha = 0.04f),
            focusedIndicatorColor = Color(0xFF66D9A0),
            unfocusedIndicatorColor = Color(0xFF66D9A0).copy(alpha = 0.4f),
            focusedLabelColor = Color(0xFF66D9A0),
            unfocusedLabelColor = Color(0xFF66D9A0).copy(alpha = 0.6f),
            cursorColor = Color(0xFF66D9A0),
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PreviewRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(
            value,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
}
