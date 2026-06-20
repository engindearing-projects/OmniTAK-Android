package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import soy.engindearing.omnitak.mobile.data.military.CbrnContamination
import soy.engindearing.omnitak.mobile.data.military.MarkingMethod
import soy.engindearing.omnitak.mobile.data.military.Medevac9Line
import soy.engindearing.omnitak.mobile.data.military.PatientNationality
import soy.engindearing.omnitak.mobile.data.military.PickupSecurity
import soy.engindearing.omnitak.mobile.data.military.ReportType
import soy.engindearing.omnitak.mobile.data.military.SaluteReport
import soy.engindearing.omnitak.mobile.data.military.SpecialEquipment

/** What [MilitaryReportSheet] hands back on Send: the report's CoT type + the
 *  formatted body that rides in `<remarks>`. The caller geolocates it at the
 *  operator's position, ingests it as a marker, and broadcasts it (#154). */
data class MilitaryReportResult(val type: ReportType, val reportText: String)

/**
 * #154 — entry forms for the three military reports the core (`MilitaryReportCoT`)
 * can build: MEDEVAC 9-line, SALUTE, and SPOTREP. SALUTE/SPOTREP share the
 * six-element observation form (only the title differs); MEDEVAC has its nine
 * coded lines. Send returns the formatted text; the map screen wraps it in a CoT
 * point and ships it over the active TAK connection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MilitaryReportSheet(
    defaultLocationGrid: String,
    onSend: (MilitaryReportResult) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var type by remember { mutableStateOf(ReportType.SALUTE) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Military report", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)

            // Report-type selector.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    ReportType.MEDEVAC to "MEDEVAC",
                    ReportType.SALUTE to "SALUTE",
                    ReportType.SPOTREP to "SPOTREP",
                ).forEach { (t, label) ->
                    FilterChip(selected = type == t, onClick = { type = t }, label = { Text(label) })
                }
            }

            when (type) {
                ReportType.MEDEVAC -> MedevacForm(defaultLocationGrid, onSend)
                ReportType.SALUTE -> SaluteForm("SALUTE REPORT", ReportType.SALUTE, defaultLocationGrid, onSend)
                ReportType.SPOTREP -> SaluteForm("SPOT REPORT", ReportType.SPOTREP, defaultLocationGrid, onSend)
            }
        }
    }
}

/** SALUTE / SPOTREP — six observation elements. */
@Composable
private fun SaluteForm(
    title: String,
    type: ReportType,
    defaultLocationGrid: String,
    onSend: (MilitaryReportResult) -> Unit,
) {
    var size by remember { mutableStateOf("") }
    var activity by remember { mutableStateOf("") }
    var location by remember { mutableStateOf(defaultLocationGrid) }
    var unit by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("") }
    var equipment by remember { mutableStateOf("") }

    ReportField("S — Size", size) { size = it }
    ReportField("A — Activity", activity) { activity = it }
    ReportField("L — Location", location) { location = it }
    ReportField("U — Unit", unit) { unit = it }
    ReportField("T — Time", time) { time = it }
    ReportField("E — Equipment", equipment) { equipment = it }

    SendButton {
        val text = SaluteReport(
            size = size, activity = activity, locationGrid = location,
            unit = unit, time = time, equipment = equipment, title = title,
        ).formattedText()
        onSend(MilitaryReportResult(type, text))
    }
}

/** MEDEVAC 9-line. Coded lines (4/6/7/8/9) cycle through their enum on tap. */
@Composable
private fun MedevacForm(
    defaultLocationGrid: String,
    onSend: (MilitaryReportResult) -> Unit,
) {
    var location by remember { mutableStateOf(defaultLocationGrid) }
    var freq by remember { mutableStateOf("") }
    var callsign by remember { mutableStateOf("") }
    var urgent by remember { mutableStateOf("0") }
    var priority by remember { mutableStateOf("0") }
    var routine by remember { mutableStateOf("0") }
    var litter by remember { mutableStateOf("0") }
    var ambulatory by remember { mutableStateOf("0") }
    var equip by remember { mutableStateOf(SpecialEquipment.NONE) }
    var security by remember { mutableStateOf(PickupSecurity.NO_ENEMY) }
    var marking by remember { mutableStateOf(MarkingMethod.NONE) }
    var nationality by remember { mutableStateOf(PatientNationality.US_MILITARY) }
    var cbrn by remember { mutableStateOf(CbrnContamination.NONE) }

    ReportField("Line 1 — Location", location) { location = it }
    ReportField("Line 2 — Radio freq", freq) { freq = it }
    ReportField("Line 2 — Callsign", callsign) { callsign = it }
    ReportField("Line 3 — Urgent", urgent, numeric = true) { urgent = it }
    ReportField("Line 3 — Priority", priority, numeric = true) { priority = it }
    ReportField("Line 3 — Routine", routine, numeric = true) { routine = it }
    CycleField("Line 4 — Special equipment", equip.label) {
        equip = SpecialEquipment.entries[(equip.ordinal + 1) % SpecialEquipment.entries.size]
    }
    ReportField("Line 5 — Litter", litter, numeric = true) { litter = it }
    ReportField("Line 5 — Ambulatory", ambulatory, numeric = true) { ambulatory = it }
    CycleField("Line 6 — Security", security.label) {
        security = PickupSecurity.entries[(security.ordinal + 1) % PickupSecurity.entries.size]
    }
    CycleField("Line 7 — Marking", marking.label) {
        marking = MarkingMethod.entries[(marking.ordinal + 1) % MarkingMethod.entries.size]
    }
    CycleField("Line 8 — Nationality", nationality.label) {
        nationality = PatientNationality.entries[(nationality.ordinal + 1) % PatientNationality.entries.size]
    }
    CycleField("Line 9 — CBRN", cbrn.label) {
        cbrn = CbrnContamination.entries[(cbrn.ordinal + 1) % CbrnContamination.entries.size]
    }

    SendButton {
        val text = Medevac9Line(
            locationGrid = location, radioFreq = freq, callSign = callsign,
            patientsUrgent = urgent.toIntOrNull() ?: 0,
            patientsPriority = priority.toIntOrNull() ?: 0,
            patientsRoutine = routine.toIntOrNull() ?: 0,
            specialEquipment = equip,
            patientsLitter = litter.toIntOrNull() ?: 0,
            patientsAmbulatory = ambulatory.toIntOrNull() ?: 0,
            security = security, marking = marking, nationality = nationality, cbrn = cbrn,
        ).formattedText()
        onSend(MilitaryReportResult(ReportType.MEDEVAC, text))
    }
}

@Composable
private fun ReportField(label: String, value: String, numeric: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A read-only field that cycles its coded value on tap (compact vs a dropdown). */
@Composable
private fun CycleField(label: String, current: String, onCycle: () -> Unit) {
    OutlinedTextField(
        value = current,
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        trailingIcon = {
            androidx.compose.material3.TextButton(onClick = onCycle) { Text("Next") }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SendButton(onSend: () -> Unit) {
    Button(onClick = onSend, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text("Send report", fontFamily = FontFamily.Monospace)
    }
}
