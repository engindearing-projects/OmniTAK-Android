package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import soy.engindearing.omnitak.mobile.data.CoTAffiliation
import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.ContactMaxAge
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent
import soy.engindearing.omnitak.mobile.ui.theme.TacticalBackground
import soy.engindearing.omnitak.mobile.ui.theme.TacticalSurface

/**
 * Bottom-sheet list of every tracked CoT contact. Tap a row to pan
 * the map onto that contact; tap outside or the handle to dismiss.
 *
 * Rows are sorted with friendlies first, then neutrals, then hostiles
 * so the most common ATAK workflow — "where's my team?" — lines up
 * without scrolling.
 *
 * #215: [notHeardFrom] are the contacts the map is hiding because no report has arrived for
 * [maxAgeMinutes]. They are not in the main list; they sit in a collapsed section at the end,
 * "Not heard from for over 30 min", each row with how long ago the last report came ([nowMs]
 * is the clock of the decision that hid them). Tapping one still pans to where it was last seen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsPanel(
    contacts: List<CoTEvent>,
    onSelect: (CoTEvent) -> Unit,
    onDismiss: () -> Unit,
    notHeardFrom: List<CoTEvent> = emptyList(),
    maxAgeMinutes: Int = ContactMaxAge.NEVER,
    nowMs: Long = 0L,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val sorted = contacts.sortedWith(
        compareBy(
            { affiliationSortKey(it.affiliation) },
            { it.callsign ?: it.uid },
        ),
    )
    // Most recently heard first: the one that just went quiet is on top.
    val stale = notHeardFrom.sortedByDescending { it.receivedAtMs }
    var staleExpanded by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = TacticalBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "TEAMS",
                color = TacticalAccent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                "${sorted.size} contact${if (sorted.size == 1) "" else "s"}",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
            )

            if (sorted.isEmpty() && stale.isEmpty()) {
                Text(
                    "No contacts yet. Connect to a TAK server or drop a marker to start populating this list.",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(sorted, key = { it.uid }) { c -> ContactRow(c, onSelect) }
                    if (stale.isNotEmpty()) {
                        item(key = "not-heard-from") {
                            NotHeardFromHeader(
                                title = ContactMaxAge.sectionTitle(maxAgeMinutes),
                                count = stale.size,
                                expanded = staleExpanded,
                                onToggle = { staleExpanded = !staleExpanded },
                            )
                        }
                        if (staleExpanded) {
                            items(stale, key = { "not-heard-from-" + it.uid }) { c ->
                                ContactRow(
                                    contact = c,
                                    onSelect = onSelect,
                                    ageText = ContactMaxAge.ageLabel(nowMs - c.receivedAtMs) + " ago",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The collapsed section's header row: title, how many it holds, and a chevron. */
@Composable
private fun NotHeardFromHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "$count",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Icon(
            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun ContactRow(contact: CoTEvent, onSelect: (CoTEvent) -> Unit, ageText: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .background(TacticalSurface)
            .clickable { onSelect(contact) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(affiliationColor(contact.affiliation)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                contact.callsign?.takeIf { it.isNotBlank() } ?: contact.uid,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                rememberCoordText(contact.lat, contact.lon) + " · " +
                    contact.affiliation.name.lowercase(),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (ageText != null) {
            Text(
                ageText,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun affiliationSortKey(a: CoTAffiliation): Int = when (a) {
    CoTAffiliation.FRIEND -> 0
    CoTAffiliation.NEUTRAL -> 1
    CoTAffiliation.UNKNOWN, CoTAffiliation.PENDING, CoTAffiliation.ASSUMED -> 2
    CoTAffiliation.SUSPECT -> 3
    CoTAffiliation.HOSTILE -> 4
    CoTAffiliation.EXERCISE -> 5
}

private fun affiliationColor(a: CoTAffiliation): Color = when (a) {
    CoTAffiliation.FRIEND -> Color(0xFF4ADE80)
    CoTAffiliation.HOSTILE -> Color(0xFFF44336)
    CoTAffiliation.NEUTRAL -> Color(0xFFFFC107)
    CoTAffiliation.SUSPECT -> Color(0xFFFF9800)
    CoTAffiliation.EXERCISE -> Color(0xFF9C27B0)
    else -> Color(0xFFB39DDB)
}
