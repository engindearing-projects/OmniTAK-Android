package soy.engindearing.omnitak.mobile.ui.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Add a shortcut" palette opened from the bar's ＋ tile. Lists every
 * catalog shortcut not already in the bar, grouped by section. Tapping
 * one adds it; the bar updates live behind the sheet. Mirrors the iOS
 * ToolbarAddPalette.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolbarAddPalette(
    available: List<BarItem>,
    isFull: Boolean,
    onAdd: (BarItem) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState()
    val screens = available.filter { it.kind is BarKind.Destination }
    val tools = available.filter { it.kind is BarKind.Command }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Color(0xFF0F1115)) {
        // #222: up to nine shortcut rows, two section headers and the Reset row
        // are taller than a landscape phone; only three rows could be reached
        // there and Reset not at all.
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(
                "Add Shortcut",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            if (isFull) {
                Text(
                    "Toolbar is full (${ToolbarCatalog.MAX_ITEMS} max). Remove a shortcut to add another.",
                    color = Color(0xFFFFCC00),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }

            Section("Screens", screens, isFull, onAdd)
            Section("Tools", tools, isFull, onAdd)

            HorizontalDivider(modifier = Modifier.padding(top = 8.dp), color = Color.White.copy(alpha = 0.08f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onReset)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, tint = Color(0xFFFF3B30), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Text("Reset to Default Toolbar", color = Color(0xFFFF3B30), fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun Section(title: String, items: List<BarItem>, isFull: Boolean, onAdd: (BarItem) -> Unit) {
    if (items.isEmpty()) return
    Text(
        title.uppercase(),
        color = Color.White.copy(alpha = 0.5f),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
    items.forEach { item ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !isFull) { onAdd(item) }
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color.White.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(item.icon, contentDescription = null, tint = item.tint, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Text(item.label, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Icon(
                imageVector = if (isFull) Icons.Filled.Block else Icons.Filled.AddCircle,
                contentDescription = if (isFull) "Toolbar full" else "Add ${item.label}",
                tint = if (isFull) Color.White.copy(alpha = 0.3f) else item.tint,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
