package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Read-only failsafe configuration view. Shows what the autopilot will
 * do on RC loss, GCS loss, low battery, etc. — so the operator knows
 * the drone's safety net before they fly.
 *
 * No writes. Modifying failsafe params from a GCS without a full param
 * sync is how bricks happen; if the operator wants to change something
 * they go to QGroundControl or Mission Planner.
 *
 * Interprets the raw PARAM_VALUE map per autopilot type — same params
 * named differently across PX4 vs ArduPilot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UasFailsafeSheet(
    params: Map<String, Float>,
    autopilot: String?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val rows = interpret(params, autopilot)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1A1F2E),
    ) {
        // #222: a PX4 vehicle reports ten failsafe rows, which is far taller than a
        // landscape phone; only the first four could be read.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Failsafe configuration",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Text(
                "Read-only view of what the autopilot will do on lost link, " +
                    "low battery, etc. Edit these in QGroundControl / Mission Planner.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
            )
            if (rows.isEmpty()) {
                Text(
                    "No failsafe params reported yet — autopilot may still " +
                        "be initialising. Reconnect or wait a few seconds.",
                    color = Color(0xFFFFC107),
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }
            rows.forEach { (label, value) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.3f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        label,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        value,
                        color = Color(0xFF00E5FF),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

private fun interpret(p: Map<String, Float>, autopilot: String?): List<Pair<String, String>> {
    val isPX4 = autopilot?.contains("PX4") == true
    val out = mutableListOf<Pair<String, String>>()
    if (isPX4) {
        p["COM_RC_LOSS_T"]?.let { out += "RC loss timeout" to "%.1f s".format(it) }
        p["NAV_RCL_ACT"]?.let { out += "RC loss action" to px4RclAct(it.toInt()) }
        p["COM_DL_LOSS_T"]?.let { out += "GCS loss timeout" to "%.0f s".format(it) }
        p["NAV_DLL_ACT"]?.let { out += "GCS loss action" to px4RclAct(it.toInt()) }
        p["COM_LOW_BAT_ACT"]?.let { out += "Low battery action" to px4BatAct(it.toInt()) }
        p["BAT_LOW_THR"]?.let { out += "Low battery threshold" to "%.0f%%".format(it * 100) }
        p["BAT_CRIT_THR"]?.let { out += "Critical battery threshold" to "%.0f%%".format(it * 100) }
        p["BAT_EMERGEN_THR"]?.let { out += "Emergency battery threshold" to "%.0f%%".format(it * 100) }
        p["RTL_RETURN_ALT"]?.let { out += "RTL return altitude" to "%.0f m".format(it) }
        p["MIS_TAKEOFF_ALT"]?.let { out += "Default takeoff altitude" to "%.0f m".format(it) }
    } else {
        // ArduPilot
        p["FS_THR_ENABLE"]?.let { out += "RC failsafe" to ardupilotFsThr(it.toInt()) }
        p["FS_GCS_ENABLE"]?.let { out += "GCS failsafe" to ardupilotFsGcs(it.toInt()) }
        p["BATT_FS_LOW_ACT"]?.let { out += "Low battery action" to ardupilotBattAct(it.toInt()) }
        p["BATT_FS_CRT_ACT"]?.let { out += "Critical battery action" to ardupilotBattAct(it.toInt()) }
        p["BATT_LOW_VOLT"]?.let { out += "Low battery voltage" to "%.1f V".format(it) }
        p["BATT_CRT_VOLT"]?.let { out += "Critical battery voltage" to "%.1f V".format(it) }
        p["RTL_ALT"]?.let { out += "RTL altitude" to "%.0f m".format(it / 100) }
    }
    return out
}

private fun px4RclAct(v: Int): String = when (v) {
    0 -> "Disabled"; 1 -> "Hold (loiter)"; 2 -> "Return to launch"; 3 -> "Land"
    4 -> "Disarm"; 5 -> "Terminate (kill motors)"
    else -> "Mode $v"
}

private fun px4BatAct(v: Int): String = when (v) {
    0 -> "Warning only"; 1 -> "Return to launch"; 2 -> "Land at current"
    3 -> "Warn + RTL on critical"
    else -> "Mode $v"
}

private fun ardupilotFsThr(v: Int): String = when (v) {
    0 -> "Disabled"; 1 -> "Enabled — always RTL"
    2 -> "Enabled — continue mission, RTL otherwise"
    3 -> "Enabled — always Land"
    else -> "Mode $v"
}

private fun ardupilotFsGcs(v: Int): String = when (v) {
    0 -> "Disabled"; 1 -> "Enabled — always RTL"
    2 -> "Enabled — continue mission, RTL otherwise"
    else -> "Mode $v"
}

private fun ardupilotBattAct(v: Int): String = when (v) {
    0 -> "None"; 1 -> "Land"; 2 -> "RTL"; 3 -> "SmartRTL or RTL"
    4 -> "SmartRTL or Land"; 5 -> "Terminate"
    else -> "Mode $v"
}
