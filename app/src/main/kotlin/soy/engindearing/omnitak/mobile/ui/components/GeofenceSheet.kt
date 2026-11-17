package soy.engindearing.omnitak.mobile.ui.components

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Fence
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import soy.engindearing.omnitak.mobile.data.CoordFormat
import soy.engindearing.omnitak.mobile.data.CoordFormatter
import soy.engindearing.omnitak.mobile.data.DistanceUnit
import soy.engindearing.omnitak.mobile.data.Drawing
import soy.engindearing.omnitak.mobile.data.DrawingKind
import soy.engindearing.omnitak.mobile.data.geofence.Geofence
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceAlertText
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceColor
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceDrawingSource
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceEvent
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceEventType
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceForm
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceOccupancy
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceType
import soy.engindearing.omnitak.mobile.data.geofence.LatLon
import soy.engindearing.omnitak.mobile.domain.OneShotRequest
import soy.engindearing.omnitak.mobile.i18n.Loc
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent
import java.util.UUID

/**
 * Lets the Tools popup (hosted in AppNav) ask the map (hosted in MapScreen) to open the geofence
 * sheet, the same way it asks for "Go to Coordinate": the request waits when AppNav switches to
 * the map in the same frame, and is gone once acted on, so coming back to the map later does not
 * open the sheet again.
 */
object GeofenceSheetEvents {
    val open = OneShotRequest()

    fun requestOpen() = open.request()
}

private val SheetColor = Color(0xFF0F1115)
private val CardColor = Color(0xFF1B2130)
private val FieldColor = Color(0xFF262D3D)
private val Muted = Color.White.copy(alpha = 0.6f)

/**
 * The geofences, as a modal sheet over the map: the list (switch one on or off, see who is inside,
 * show it on the map, delete it, read what it reported lately) and the form that makes a new one.
 * The map behind stays where it is; the sheet scrolls, so on a phone held sideways every row and
 * the Save button can be reached (#222).
 *
 * It holds no data of its own: everything comes in and every change goes out, so the map screen
 * decides what a save or a delete does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeofenceSheet(
    geofences: List<Geofence>,
    occupancy: Map<String, GeofenceOccupancy>,
    events: List<GeofenceEvent>,
    /** The operator's own drawings (circles, polygons and lines): a geofence can be made from a circle or a polygon. */
    drawings: List<Drawing>,
    /** The middle of the map when the sheet opened, or null when the map has not said. */
    mapCenter: LatLon?,
    distanceUnit: DistanceUnit,
    coordFormat: CoordFormat,
    onSave: (Geofence) -> Unit,
    onSetActive: (id: String, active: Boolean) -> Unit,
    onDelete: (id: String) -> Unit,
    onShowOnMap: (Geofence) -> Unit,
    onClearEvents: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Fully open at once: the form is taller than a landscape phone, and a half-open sheet would
    // show a strip of it.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Geofence?>(null) }

    // Back from the form goes back to the list, not out of the sheet.
    BackHandler(enabled = creating) { creating = false }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetColor,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // With the keyboard up the sheet is only as tall as what is left above it, and still
                // scrolls, so the Save button is never behind the keyboard with no way to reach it (#222).
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (creating) {
                NewGeofenceForm(
                    existingNames = geofences.map { it.name },
                    drawings = GeofenceForm.drawingSources(drawings),
                    mapCenter = mapCenter,
                    distanceUnit = distanceUnit,
                    coordFormat = coordFormat,
                    onBack = { creating = false },
                    onSave = {
                        creating = false
                        onSave(it)
                    },
                )
            } else {
                GeofenceList(
                    geofences = geofences,
                    occupancy = occupancy,
                    events = events,
                    distanceUnit = distanceUnit,
                    onNew = { creating = true },
                    onSetActive = onSetActive,
                    onAskDelete = { deleting = it },
                    onShowOnMap = onShowOnMap,
                    onClearEvents = onClearEvents,
                )
            }
        }
    }

    deleting?.let { geofence ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = SheetColor,
            title = { Text(Loc.t("geofence.delete.confirm.title"), color = Color.White) },
            text = { Text(Loc.t("geofence.delete.confirm.body", geofence.name), color = Muted) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    onDelete(geofence.id)
                }) { Text(Loc.t("geofence.delete"), color = Color(0xFFFF3B30)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(Loc.t("geofence.cancel"), color = TacticalAccent)
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------------------- the list

@Composable
private fun GeofenceList(
    geofences: List<Geofence>,
    occupancy: Map<String, GeofenceOccupancy>,
    events: List<GeofenceEvent>,
    distanceUnit: DistanceUnit,
    onNew: () -> Unit,
    onSetActive: (String, Boolean) -> Unit,
    onAskDelete: (Geofence) -> Unit,
    onShowOnMap: (Geofence) -> Unit,
    onClearEvents: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            Loc.t("geofence.title"),
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = onNew,
            colors = ButtonDefaults.buttonColors(containerColor = TacticalAccent, contentColor = Color.Black),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(Loc.t("geofence.create"))
        }
    }

    if (geofences.isEmpty()) {
        EmptyState()
    } else {
        geofences.forEach { geofence ->
            GeofenceCard(
                geofence = geofence,
                occupancy = occupancy[geofence.id] ?: GeofenceOccupancy(),
                distanceUnit = distanceUnit,
                onSetActive = { onSetActive(geofence.id, it) },
                onShowOnMap = { onShowOnMap(geofence) },
                onDelete = { onAskDelete(geofence) },
            )
        }
    }

    RecentEvents(events, onClearEvents)
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Fence, contentDescription = null, tint = Muted, modifier = Modifier.size(48.dp))
        Text(Loc.t("geofence.empty.title"), style = MaterialTheme.typography.titleMedium, color = Color.White)
        Text(
            Loc.t("geofence.empty.body"),
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
        )
    }
}

@Composable
private fun GeofenceCard(
    geofence: Geofence,
    occupancy: GeofenceOccupancy,
    distanceUnit: DistanceUnit,
    onSetActive: (Boolean) -> Unit,
    onShowOnMap: () -> Unit,
    onDelete: () -> Unit,
) {
    val tint = Color(android.graphics.Color.parseColor(geofence.color.hex))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardColor)
            .border(
                BorderStroke(1.dp, if (geofence.isActive) tint.copy(alpha = 0.5f) else Color.Transparent),
                RoundedCornerShape(12.dp),
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(tint))
            Spacer(Modifier.width(10.dp))
            Text(
                geofence.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = geofence.isActive,
                onCheckedChange = onSetActive,
                colors = SwitchDefaults.colors(checkedTrackColor = TacticalAccent, checkedThumbColor = Color.Black),
                modifier = Modifier.semantics { contentDescription = geofence.name },
            )
        }

        Text(
            GeofenceAlertText.stateLine(geofence.isActive, occupancy),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (geofence.isActive) TacticalAccent else Muted,
        )
        Text(details(geofence, distanceUnit), style = MaterialTheme.typography.bodySmall, color = Muted)

        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onShowOnMap) {
                Icon(Icons.Filled.Explore, contentDescription = null, tint = TacticalAccent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(Loc.t("geofence.showOnMap"), color = TacticalAccent)
            }
            TextButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = null, tint = Color(0xFFFF3B30), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(Loc.t("geofence.delete"), color = Color(0xFFFF3B30))
            }
        }
    }
}

/** The shape, who it watches, and what it alerts on, on one quiet line. */
private fun details(geofence: Geofence, unit: DistanceUnit): String {
    val shape = when (geofence.type) {
        GeofenceType.CIRCLE ->
            Loc.t("geofence.shape.circle") + " " + GeofenceForm.radiusLabel(geofence.radiusMeters ?: 0.0, unit)
        GeofenceType.POLYGON -> Loc.t("geofence.shape.polygon", geofence.points?.size ?: 0)
    }
    val watching = buildList {
        if (geofence.watchSelf) add(Loc.t("geofence.watch.me"))
        if (geofence.watchContacts) add(Loc.t("geofence.watch.teammates"))
    }.joinToString(", ")
    val alerts = buildList {
        if (geofence.alertOnEntry) add(Loc.t("geofence.alerts.entry"))
        if (geofence.alertOnExit) add(Loc.t("geofence.alerts.exit"))
        if (geofence.dwellMinutes > 0) add(Loc.t("geofence.alerts.dwell", geofence.dwellMinutes))
    }.joinToString(", ")
    return listOf(shape, watching, alerts).filter { it.isNotEmpty() }.joinToString(" · ")
}

@Composable
private fun RecentEvents(events: List<GeofenceEvent>, onClear: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Text(
            Loc.t("geofence.recent").uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Muted,
            modifier = Modifier.weight(1f),
        )
        if (events.isNotEmpty()) {
            TextButton(onClick = onClear) { Text(Loc.t("geofence.clearEvents"), color = TacticalAccent) }
        }
    }
    if (events.isEmpty()) {
        Text(Loc.t("geofence.noEvents"), style = MaterialTheme.typography.bodyMedium, color = Muted)
    } else {
        val now = System.currentTimeMillis()
        events.forEach { event ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(CardColor)
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    eventIcon(event.type),
                    contentDescription = null,
                    tint = eventColor(event.type),
                    modifier = Modifier.size(20.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(GeofenceAlertText.sentence(event), style = MaterialTheme.typography.bodyMedium, color = Color.White)
                    Text(
                        DateUtils.getRelativeTimeSpanString(
                            event.atMs, now, DateUtils.SECOND_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
                        ).toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
            }
        }
    }
}

private fun eventIcon(type: GeofenceEventType): ImageVector = when (type) {
    GeofenceEventType.ENTRY -> Icons.AutoMirrored.Filled.Login
    GeofenceEventType.EXIT -> Icons.AutoMirrored.Filled.Logout
    GeofenceEventType.DWELL -> Icons.Filled.Timer
}

/** Entry yellow, exit red, dwell orange: the colours the iOS list uses for the same three. */
private fun eventColor(type: GeofenceEventType): Color = when (type) {
    GeofenceEventType.ENTRY -> Color(0xFFFFCC00)
    GeofenceEventType.EXIT -> Color(0xFFFF3B30)
    GeofenceEventType.DWELL -> Color(0xFFFF9500)
}

// ---------------------------------------------------------------------------------------- the form

private enum class Source { MAP, DRAWING }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewGeofenceForm(
    existingNames: List<String>,
    drawings: List<Drawing>,
    mapCenter: LatLon?,
    distanceUnit: DistanceUnit,
    coordFormat: CoordFormat,
    onBack: () -> Unit,
    onSave: (Geofence) -> Unit,
) {
    val nameTemplate = Loc.t("geofence.form.defaultName")
    var name by remember { mutableStateOf(GeofenceForm.defaultName(existingNames, nameTemplate)) }
    var source by remember { mutableStateOf(Source.MAP) }
    var radiusIndex by remember { mutableIntStateOf(GeofenceForm.radiusIndex(GeofenceForm.DEFAULT_RADIUS_M)) }
    var drawingId by remember { mutableStateOf<String?>(null) }
    var watchSelf by remember { mutableStateOf(true) }
    var watchContacts by remember { mutableStateOf(true) }
    var alertOnEntry by remember { mutableStateOf(true) }
    var alertOnExit by remember { mutableStateOf(true) }
    var dwellOn by remember { mutableStateOf(false) }
    var dwellMinutes by remember { mutableIntStateOf(GeofenceForm.DEFAULT_DWELL_MINUTES) }
    var color by remember { mutableStateOf(GeofenceColor.YELLOW) }

    val settings = GeofenceForm.Settings(
        color = color,
        watchSelf = watchSelf,
        watchContacts = watchContacts,
        alertOnEntry = alertOnEntry,
        alertOnExit = alertOnExit,
        dwellMinutes = if (dwellOn) dwellMinutes else 0,
    )
    val chosenDrawing = drawings.firstOrNull { it.id == drawingId }
    val canSave = settings.watchesSomeone && when (source) {
        Source.MAP -> mapCenter != null
        Source.DRAWING -> chosenDrawing != null
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = Loc.t("geofence.cancel"),
                tint = Color.White,
            )
        }
        Text(Loc.t("geofence.new"), style = MaterialTheme.typography.titleLarge, color = Color.White)
    }

    // ---- name
    SectionTitle(Loc.t("geofence.form.name"))
    TextField(
        value = name,
        onValueChange = { name = it },
        placeholder = { Text(Loc.t("geofence.form.namePlaceholder")) },
        singleLine = true,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = FieldColor,
            unfocusedContainerColor = FieldColor,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
        ),
        modifier = Modifier.fillMaxWidth(),
    )

    // ---- position
    SectionTitle(Loc.t("geofence.form.position"))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        SourceButton(
            icon = Icons.Filled.Explore,
            title = Loc.t("geofence.form.source.map"),
            selected = source == Source.MAP,
            enabled = true,
            modifier = Modifier.weight(1f),
        ) { source = Source.MAP }
        SourceButton(
            icon = Icons.Filled.Edit,
            title = Loc.t("geofence.form.source.drawing"),
            selected = source == Source.DRAWING,
            enabled = drawings.isNotEmpty(),
            modifier = Modifier.weight(1f),
        ) { source = Source.DRAWING }
    }
    if (drawings.isEmpty()) {
        Text(Loc.t("geofence.form.noDrawings"), style = MaterialTheme.typography.bodySmall, color = Muted)
    }

    when (source) {
        Source.MAP -> {
            if (mapCenter != null) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(Loc.t("geofence.form.center"), style = MaterialTheme.typography.labelSmall, color = Muted)
                    Text(
                        CoordFormatter.position(mapCenter.lat, mapCenter.lon, coordFormat),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                }
            } else {
                Text(Loc.t("geofence.form.noCenter"), style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF9500))
            }
            Column {
                Row {
                    Text(
                        Loc.t("geofence.form.radius"),
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        GeofenceForm.radiusLabel(GeofenceForm.radiusAt(radiusIndex), distanceUnit),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                }
                Slider(
                    value = radiusIndex.toFloat(),
                    onValueChange = { radiusIndex = it.toInt().coerceIn(0, GeofenceForm.RADIUS_CHOICES.lastIndex) },
                    valueRange = 0f..GeofenceForm.RADIUS_CHOICES.lastIndex.toFloat(),
                    steps = GeofenceForm.RADIUS_CHOICES.size - 2,
                    colors = SliderDefaults.colors(thumbColor = TacticalAccent, activeTrackColor = TacticalAccent),
                )
            }
        }
        Source.DRAWING -> {
            drawings.forEach { drawing ->
                DrawingChoice(drawing, distanceUnit, selected = drawing.id == drawingId) { drawingId = drawing.id }
            }
        }
    }

    // ---- who is watched
    SectionTitle(Loc.t("geofence.form.watch"))
    SwitchRow(Loc.t("geofence.watch.me"), watchSelf) { watchSelf = it }
    SwitchRow(Loc.t("geofence.watch.teammates"), watchContacts) { watchContacts = it }

    // ---- what is alerted on
    SectionTitle(Loc.t("geofence.form.alerts"))
    SwitchRow(Loc.t("geofence.form.alertEntry"), alertOnEntry) { alertOnEntry = it }
    SwitchRow(Loc.t("geofence.form.alertExit"), alertOnExit) { alertOnExit = it }
    SwitchRow(Loc.t("geofence.form.alertDwell"), dwellOn) { dwellOn = it }
    if (dwellOn) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(Loc.t("geofence.form.dwellAfter"), color = Muted, style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GeofenceForm.DWELL_CHOICES_MINUTES.forEach { minutes ->
                    FilterChip(
                        selected = dwellMinutes == minutes,
                        onClick = { dwellMinutes = minutes },
                        label = { Text(Loc.t("geofence.duration.minutes", minutes)) },
                        colors = FilterChipDefaults.filterChipColors(
                            labelColor = Color.White,
                            selectedContainerColor = TacticalAccent,
                            selectedLabelColor = Color.Black,
                        ),
                    )
                }
            }
        }
    }

    // ---- colour
    SectionTitle(Loc.t("geofence.form.color"))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GeofenceColor.entries.forEach { choice ->
            val tint = Color(android.graphics.Color.parseColor(choice.hex))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(tint)
                    .border(
                        width = if (choice == color) 3.dp else 1.dp,
                        color = if (choice == color) Color.White else Color.White.copy(alpha = 0.3f),
                        shape = CircleShape,
                    )
                    .clickable(role = Role.RadioButton) { color = choice }
                    .semantics { contentDescription = choice.name.lowercase().replaceFirstChar { it.uppercase() } },
            )
        }
    }

    // ---- save
    if (!settings.watchesSomeone) {
        Text(Loc.t("geofence.form.watchSomeone"), style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF9500))
    }
    Button(
        onClick = {
            val finalName = GeofenceForm.finalName(name, existingNames, nameTemplate)
            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val geofence = when (source) {
                Source.MAP -> mapCenter?.let {
                    GeofenceForm.circleAt(id, now, finalName, it.lat, it.lon, GeofenceForm.radiusAt(radiusIndex), settings)
                }
                Source.DRAWING -> chosenDrawing?.let { GeofenceForm.fromDrawing(id, now, finalName, it, settings) }
            }
            if (geofence != null) onSave(geofence)
        },
        enabled = canSave,
        colors = ButtonDefaults.buttonColors(containerColor = TacticalAccent, contentColor = Color.Black),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(Loc.t("geofence.form.save")) }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = Muted,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Color.White, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = TacticalAccent, checkedThumbColor = Color.Black),
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

@Composable
private fun SourceButton(
    icon: ImageVector,
    title: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> Color.White.copy(alpha = 0.3f)
        selected -> TacticalAccent
        else -> Muted
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) TacticalAccent.copy(alpha = 0.18f) else FieldColor)
            .border(BorderStroke(1.dp, if (selected) TacticalAccent else Color.Transparent), RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Text(
            title,
            color = tint,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun DrawingChoice(drawing: Drawing, unit: DistanceUnit, selected: Boolean, onClick: () -> Unit) {
    val detail = when (drawing.kind) {
        DrawingKind.CIRCLE -> Loc.t("geofence.shape.circle") + " " +
            GeofenceForm.radiusLabel(GeofenceDrawingSource.circleRadiusMeters(drawing.points), unit)
        else -> Loc.t("geofence.shape.polygon", drawing.points.size)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) TacticalAccent.copy(alpha = 0.15f) else FieldColor)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                drawing.name.ifBlank { detail },
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (drawing.name.isNotBlank()) {
                Text(detail, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(TacticalAccent))
    }
}
