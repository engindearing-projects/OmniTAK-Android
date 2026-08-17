package soy.engindearing.omnitak.mobile.ui.components

import android.content.res.Configuration
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/**
 * User-customizable floating "Liquid Glass" bottom bar. Renders the
 * operator's chosen shortcuts and, on long-press, enters an edit mode
 * (jiggle, remove with −, drag-to-reorder, and a ＋ tile that opens the
 * add-shortcut palette). Mirrors the iOS CustomToolbar.
 *
 * Reordering commits on drag release (the dragged cell follows the finger;
 * the list reshuffles when you let go) — robust against the recomposition-
 * during-drag pitfalls of live-shuffling a weighted Row.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CustomToolbar(
    items: List<BarItem>,
    currentRoute: String?,
    editing: Boolean,
    coachmarkVisible: Boolean,
    canAdd: Boolean,
    canRemove: Boolean,
    /** Per-item status dot colors keyed by BarItem id — e.g. the Mesh tab's
     *  radio-link state (field feedback: "is a mesh device even connected?"
     *  needs an always-visible answer). Absent id = no dot. */
    statusDots: Map<String, Color> = emptyMap(),
    onSelect: (BarItem) -> Unit,
    onEnterEdit: () -> Unit,
    onDoneEdit: () -> Unit,
    onRemove: (BarItem) -> Unit,
    onReorder: (Int, Int) -> Unit,
    onAddTapped: () -> Unit,
    onDismissCoachmark: () -> Unit,
) {
    val currentItems by rememberUpdatedState(items)
    // #182 — landscape (the plate-carrier orientation on a Pixel 9/10 Pro) has
    // little vertical room, and this full-width bottom bar used to eat a chunk
    // of the short map with its icon-over-label cells. In landscape the bar
    // collapses to a compact icon-only rail with tighter padding so the map
    // keeps its height; every action stays present and tappable (the label
    // rides on as the icon's content description). Edit mode forces the roomy
    // layout so the remove/reorder affordances stay usable.
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val compact = isLandscape && !editing
    var rowWidthPx by remember { mutableIntStateOf(0) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragStartIndex by remember { mutableIntStateOf(0) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }

    val totalCells = items.size + if (editing && canAdd) 1 else 0
    val itemWidthPx = if (totalCells > 0 && rowWidthPx > 0) rowWidthPx.toFloat() / totalCells else 1f

    val jiggle = rememberInfiniteTransition(label = "toolbarJiggle")
    val jiggleAngle by jiggle.animateFloat(
        initialValue = -2f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(animation = tween(140), repeatMode = RepeatMode.Reverse),
        label = "toolbarJiggleAngle",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 14.dp, vertical = if (compact) 4.dp else 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (editing) {
                EditHeader(onDoneEdit)
                Spacer(Modifier.height(8.dp))
            } else if (coachmarkVisible) {
                Coachmark(onDismissCoachmark)
                Spacer(Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { rowWidthPx = it.width }
                    .shadow(elevation = 16.dp, shape = RoundedCornerShape(34.dp), clip = false)
                    .clip(RoundedCornerShape(34.dp))
                    .background(Color(0xCC0F1115))
                    .border(
                        width = if (editing) 1.5.dp else 1.dp,
                        color = if (editing) Color(0xFFFFCC00).copy(alpha = 0.6f) else Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(34.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = if (compact) 3.dp else 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEachIndexed { index, item ->
                    val isDragging = draggingId == item.id
                    val dragModifier = if (editing) {
                        Modifier.pointerInput(item.id) {
                            detectDragGestures(
                                onDragStart = {
                                    draggingId = item.id
                                    dragStartIndex = currentItems.indexOfFirst { it.id == item.id }
                                    dragOffsetX = 0f
                                },
                                onDragEnd = {
                                    val target = (dragStartIndex + (dragOffsetX / itemWidthPx).roundToInt())
                                        .coerceIn(0, currentItems.size - 1)
                                    if (target != dragStartIndex) onReorder(dragStartIndex, target)
                                    draggingId = null
                                    dragOffsetX = 0f
                                },
                                onDragCancel = {
                                    draggingId = null
                                    dragOffsetX = 0f
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffsetX += amount.x
                                },
                            )
                        }
                    } else {
                        Modifier
                    }

                    BarCell(
                        item = item,
                        selected = currentRoute == item.route(),
                        editing = editing,
                        compact = compact,
                        canRemove = canRemove,
                        statusDot = statusDots[item.id],
                        jiggleAngle = if (isDragging) 0f else jiggleAngle,
                        isDragging = isDragging,
                        dragTranslationX = if (isDragging) dragOffsetX else 0f,
                        onSelect = { onSelect(item) },
                        onEnterEdit = onEnterEdit,
                        onRemove = { onRemove(item) },
                        gestureModifier = dragModifier,
                        modifier = Modifier.weight(1f),
                    )
                }

                if (editing && canAdd) {
                    AddCell(onAddTapped, Modifier.weight(1f))
                }
            }
        }
    }
}

private fun BarItem.route(): String? = (kind as? BarKind.Destination)?.route

@Composable
private fun EditHeader(onDone: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xCC0F1115))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(50))
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Drag to reorder · − remove · ＋ add",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color(0xFFFFCC00))
                .clickable(onClick = onDone)
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Text("Done", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Coachmark(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xFFFFCC00))
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.TouchApp, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            "Press & hold to build your own toolbar",
            color = Color.Black,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.clip(CircleShape).clickable(onClick = onDismiss).padding(2.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = Color.Black.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BarCell(
    item: BarItem,
    selected: Boolean,
    editing: Boolean,
    compact: Boolean,
    canRemove: Boolean,
    statusDot: Color?,
    jiggleAngle: Float,
    isDragging: Boolean,
    dragTranslationX: Float,
    onSelect: () -> Unit,
    onEnterEdit: () -> Unit,
    onRemove: () -> Unit,
    gestureModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .zIndex(if (isDragging) 2f else 0f)
            .graphicsLayer {
                rotationZ = if (editing) jiggleAngle else 0f
                translationX = dragTranslationX
                if (isDragging) {
                    scaleX = 1.12f
                    scaleY = 1.12f
                }
            }
            .then(gestureModifier),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .then(
                    if (editing) Modifier
                    else Modifier.combinedClickable(onClick = onSelect, onLongClick = onEnterEdit),
                )
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(if (selected) 40.dp else 32.dp)
                    .clip(CircleShape)
                    .background(if (selected) item.tint.copy(alpha = 0.22f) else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.label,
                    tint = if (selected) item.tint else Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(22.dp),
                )
                if (statusDot != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 3.dp, end = 3.dp)
                            .size(7.dp)
                            .clip(CircleShape)
                            // Thin dark ring so the dot reads on any tint.
                            .background(Color(0xFF0F1115))
                            .padding(1.dp)
                            .clip(CircleShape)
                            .background(statusDot),
                    )
                }
            }
            // #182 — drop the per-cell label in the compact landscape rail;
            // the icon's contentDescription keeps it accessible and the bar
            // shrinks to a minimal icon row instead of icon-over-label tiles.
            if (!compact) {
                Text(
                    text = item.label,
                    color = if (selected) item.tint else Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        if (editing && canRemove) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .clip(CircleShape)
                    .clickable(onClick = onRemove),
            ) {
                Icon(
                    Icons.Filled.RemoveCircle,
                    contentDescription = "Remove ${item.label}",
                    tint = Color(0xFFFF3B30),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun AddCell(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .border(1.5.dp, Color.White.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Add shortcut", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(18.dp))
        }
        Text(
            "Add",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
