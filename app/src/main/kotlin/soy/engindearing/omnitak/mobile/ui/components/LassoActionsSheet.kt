package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.HighlightOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Action sheet for the lasso selection. Mirrors the iOS
 * confirmationDialog wired off `LassoSelectionPill` — five options:
 * Add to Data Package / Export KML / Send to Contacts / Bulk Delete /
 * Clear Selection.
 *
 * v1 wires Bulk Delete + Clear with real implementations (see
 * MapScreen call site). The other three surface a snackbar message
 * so the affordance is visible while the deeper implementations
 * (data package builder, KML exporter, contact picker) follow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LassoActionsSheet(
    selectionCount: Int,
    onDismiss: () -> Unit,
    onAddToDataPackage: () -> Unit,
    onExportKML: () -> Unit,
    onSendToContacts: () -> Unit,
    onBulkDelete: () -> Unit,
    onClear: () -> Unit,
    // Non-null when at least one enrolled+enabled TLS server is
    // configured; null hides the row so we don't dangle a button
    // operators can't use. #30 slice 3.
    onUploadToServer: (() -> Unit)? = null,
) {
    val sheet = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = Color(0xFF0F1115),
    ) {
        // #222: five or six action rows plus the header are taller than a
        // landscape phone; Delete was half cut off and Clear Selection was gone.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            // Header — count + subtitle
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Column {
                    Text(
                        "$selectionCount selected",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Choose an action for the lasso selection",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 13.sp,
                    )
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

            ActionRow(
                icon = Icons.Filled.Inventory2,
                title = "Add to Data Package…",
                onClick = onAddToDataPackage,
            )
            HorizontalDivider(modifier = Modifier.padding(start = 76.dp), color = Color.White.copy(alpha = 0.08f))

            onUploadToServer?.let { upload ->
                ActionRow(
                    icon = Icons.Filled.CloudUpload,
                    title = "Upload to TAK Server…",
                    onClick = upload,
                )
                HorizontalDivider(modifier = Modifier.padding(start = 76.dp), color = Color.White.copy(alpha = 0.08f))
            }

            ActionRow(
                icon = Icons.Filled.FileUpload,
                title = "Export as KML…",
                onClick = onExportKML,
            )
            HorizontalDivider(modifier = Modifier.padding(start = 76.dp), color = Color.White.copy(alpha = 0.08f))

            ActionRow(
                icon = Icons.Filled.Send,
                title = "Send to Contacts…",
                onClick = onSendToContacts,
            )
            HorizontalDivider(modifier = Modifier.padding(start = 76.dp), color = Color.White.copy(alpha = 0.08f))

            ActionRow(
                icon = Icons.Filled.Delete,
                title = "Delete $selectionCount item(s)",
                tint = Color(0xFFFF453A), // iOS systemRed — destructive
                onClick = onBulkDelete,
            )
            HorizontalDivider(modifier = Modifier.padding(start = 76.dp), color = Color.White.copy(alpha = 0.08f))

            ActionRow(
                icon = Icons.Filled.HighlightOff,
                title = "Clear Selection",
                onClick = onClear,
            )
        }
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint)
        }
        Spacer(Modifier.width(14.dp))
        Text(
            title,
            color = tint,
            fontSize = 16.sp,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.35f),
        )
    }
}
