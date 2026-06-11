package soy.engindearing.omnitak.mobile.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import soy.engindearing.omnitak.mobile.OmniTAKApp
import soy.engindearing.omnitak.mobile.i18n.Loc
import soy.engindearing.omnitak.mobile.data.CoordFormat
import soy.engindearing.omnitak.mobile.data.DistanceUnit
import soy.engindearing.omnitak.mobile.data.MapProvider
import soy.engindearing.omnitak.mobile.data.UserPrefs
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent
import soy.engindearing.omnitak.mobile.ui.theme.TacticalBackground
import soy.engindearing.omnitak.mobile.ui.theme.TacticalSurface
import soy.engindearing.omnitak.mobile.ui.components.ToolbarEditBus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val app = LocalContext.current.applicationContext as OmniTAKApp
    val prefs by app.userPrefsStore.prefs.collectAsState(initial = UserPrefs())
    val scope = rememberCoroutineScope()

    fun mutate(block: (UserPrefs) -> UserPrefs) {
        scope.launch {
            app.userPrefsStore.update { current -> block(current) }
        }
    }

    Scaffold(
        containerColor = TacticalBackground,
        topBar = {
            TopAppBar(
                title = { Text(Loc.t("settings.title"), color = MaterialTheme.colorScheme.onBackground) },
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionHeader(Loc.t("settings.section.identity"))
            // GAP-103 — local state insulates the field from DataStore round-trip
            // latency, otherwise every keystroke re-emits prefs.callsign and the
            // cursor jumps. The remember-key resyncs when prefs change externally.
            var callsignDraft by remember(prefs.callsign) { mutableStateOf(prefs.callsign) }
            OutlinedTextField(
                value = callsignDraft,
                onValueChange = { v ->
                    callsignDraft = v
                    mutate { it.copy(callsign = v) }
                },
                label = { Text(Loc.t("settings.callsign")) },
                singleLine = true,
                colors = settingsFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            // GAP-104 — ATAK standard team color dropdown. Free-text broke
            // CoT compatibility; ATAK clients only render the canonical colors.
            TeamColorDropdown(
                value = prefs.team,
                onSelect = { v -> mutate { it.copy(team = v) } },
            )

            SectionHeader(Loc.t("settings.section.interface"))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(TacticalSurface)
                    .clickable { ToolbarEditBus.requestEdit() }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(Loc.t("settings.customizeToolbar"), color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        Loc.t("settings.customizeToolbar.desc"),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text("›", color = TacticalAccent, fontWeight = FontWeight.Bold)
            }

            SectionHeader(Loc.t("settings.section.units"))
            SegmentedRow(
                options = listOf(
                    DistanceUnit.METRIC to Loc.t("settings.unit.metric"),
                    DistanceUnit.IMPERIAL to Loc.t("settings.unit.imperial"),
                ),
                selected = prefs.distanceUnit,
                onSelect = { v -> mutate { it.copy(distanceUnit = v) } },
            )

            SectionHeader(Loc.t("settings.section.coordinates"))
            SegmentedRow(
                options = listOf(
                    CoordFormat.LATLON_DECIMAL to Loc.t("settings.coord.latlon"),
                    CoordFormat.LATLON_DMS to Loc.t("settings.coord.dms"),
                    CoordFormat.MGRS to Loc.t("settings.coord.mgrs"),
                    CoordFormat.UTM to Loc.t("settings.coord.utm"),
                    CoordFormat.TWD97 to Loc.t("settings.coord.twd97"),
                ),
                selected = prefs.coordFormat,
                onSelect = { v -> mutate { it.copy(coordFormat = v) } },
            )

            SectionHeader(Loc.t("settings.section.mapTiles"))
            SegmentedRow(
                options = listOf(
                    MapProvider.OSM_RASTER to Loc.t("settings.map.osm"),
                    MapProvider.SATELLITE_HINT to Loc.t("settings.map.satellite"),
                    MapProvider.TOPO_HINT to Loc.t("settings.map.topo"),
                    MapProvider.WMTS_CUSTOM to Loc.t("settings.map.custom"),
                ),
                selected = prefs.mapProvider,
                onSelect = { v -> mutate { it.copy(mapProvider = v) } },
            )
            Text(
                Loc.t("settings.mapTiles.desc"),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
            )

            // GAP-107 — show the custom URL field only when Custom is picked.
            if (prefs.mapProvider == MapProvider.WMTS_CUSTOM) {
                var urlDraft by remember(prefs.customTileUrl) {
                    mutableStateOf(prefs.customTileUrl)
                }
                OutlinedTextField(
                    value = urlDraft,
                    onValueChange = { v ->
                        urlDraft = v
                        mutate { it.copy(customTileUrl = v) }
                    },
                    label = { Text(Loc.t("settings.tileUrl")) },
                    placeholder = { Text("https://host/{z}/{x}/{y}.png") },
                    singleLine = true,
                    colors = settingsFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    Loc.t("settings.customTile.help"),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SectionHeader(Loc.t("settings.section.selfPosition"))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        Loc.t("settings.milStdSymbol"),
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        Loc.t("settings.milStdSymbol.desc"),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = prefs.useMilStdSelfSymbol,
                    onCheckedChange = { v -> mutate { it.copy(useMilStdSelfSymbol = v) } },
                )
            }

            SectionHeader(Loc.t("settings.section.droneDetection"))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        Loc.t("settings.faaRemoteIdScanner"),
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        Loc.t("settings.faaRemoteId.desc"),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val context = LocalContext.current
                val needsRuntimePerm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                var scanPermGranted by remember {
                    mutableStateOf(
                        !needsRuntimePerm ||
                            ContextCompat.checkSelfPermission(
                                context, Manifest.permission.BLUETOOTH_SCAN,
                            ) == PackageManager.PERMISSION_GRANTED
                    )
                }
                val permLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { result ->
                    val ok = result[Manifest.permission.BLUETOOTH_SCAN] == true
                    scanPermGranted = ok
                    if (ok) {
                        // Pref is already true (user just toggled), but the
                        // scanner-lifecycle flow won't re-fire start() without
                        // a distinct change — kick it directly now that perms
                        // are in hand.
                        app.remoteIdScanner.start()
                    } else {
                        // User denied — undo the optimistic pref flip so the
                        // Switch reflects reality.
                        mutate { it.copy(remoteIdScanEnabled = false) }
                    }
                }
                Switch(
                    checked = prefs.remoteIdScanEnabled && scanPermGranted,
                    onCheckedChange = { v ->
                        if (!v) {
                            mutate { it.copy(remoteIdScanEnabled = false) }
                        } else if (scanPermGranted) {
                            mutate { it.copy(remoteIdScanEnabled = true) }
                        } else {
                            mutate { it.copy(remoteIdScanEnabled = true) }
                            permLauncher.launch(
                                arrayOf(
                                    Manifest.permission.BLUETOOTH_SCAN,
                                    Manifest.permission.BLUETOOTH_CONNECT,
                                )
                            )
                        }
                    },
                )
            }

            // Language — switches the UI language live, no restart. Bound
            // straight to Loc; reading Loc.current here means a switch
            // recomposes the whole screen instantly (iOS parity).
            SectionHeader(Loc.t("settings.section.language"))
            SegmentedRow(
                options = Loc.Language.entries.map { it to "${it.flag}  ${it.displayName}" },
                selected = Loc.current,
                onSelect = { lang -> Loc.setLanguage(lang) },
            )

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        color = TacticalAccent,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun <T> SegmentedRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(TacticalSurface),
    ) {
        options.forEachIndexed { idx, (value, label) ->
            val isSelected = value == selected
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .background(if (isSelected) TacticalAccent.copy(alpha = 0.2f) else Color.Transparent)
                    .clickable { onSelect(value) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    label,
                    color = if (isSelected) TacticalAccent else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (idx < options.size - 1) {
                Row(modifier = Modifier.width(1.dp).height(44.dp).background(TacticalBackground)) { }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun settingsFieldColors() = TextFieldDefaults.colors(
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

/**
 * Standard ATAK CoT team colors. Title Case matches ATAK / OpenTakServer
 * (`<__group name="Cyan" role="Team Member"/>`) — OTS's DB keys teams by
 * the canonical capitalized name and rejects all-caps variants in its web
 * UI. Hex values are tuned for readability against the OmniTAK dark
 * surface.
 */
private val ATAK_TEAM_COLORS: List<Pair<String, Color>> = listOf(
    "White" to Color(0xFFFFFFFF),
    "Yellow" to Color(0xFFFFEB3B),
    "Orange" to Color(0xFFFF9800),
    "Magenta" to Color(0xFFE91E63),
    "Red" to Color(0xFFF44336),
    "Maroon" to Color(0xFF8B0000),
    "Purple" to Color(0xFF9C27B0),
    "Dark Blue" to Color(0xFF1A237E),
    "Blue" to Color(0xFF2196F3),
    "Cyan" to Color(0xFF00BCD4),
    "Teal" to Color(0xFF009688),
    "Green" to Color(0xFF4CAF50),
    "Dark Green" to Color(0xFF1B5E20),
    "Brown" to Color(0xFF6D4C41),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TeamColorDropdown(value: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = ATAK_TEAM_COLORS.firstOrNull { it.first.equals(value, ignoreCase = true) }
        ?: ("Cyan" to Color(0xFF00BCD4))

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            value = current.first,
            onValueChange = {},
            readOnly = true,
            label = { Text(Loc.t("settings.team")) },
            leadingIcon = {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(current.second),
                )
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = settingsFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(TacticalSurface),
        ) {
            ATAK_TEAM_COLORS.forEach { (label, swatch) ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(swatch),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                label,
                                color = MaterialTheme.colorScheme.onBackground,
                                fontWeight = if (label == current.first) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    },
                    onClick = {
                        onSelect(label)
                        expanded = false
                    },
                )
            }
        }
    }
}

