package soy.engindearing.omnitak.mobile.ui.components

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent
import soy.engindearing.omnitak.mobile.ui.theme.TacticalBackground

data class ToolEntry(
    val id: String,
    val icon: ImageVector,
    val label: String,
    val enabled: Boolean = true,
)

/**
 * Expanding tool dial anchored to the bottom-right of the map. Tapping the main FAB opens it;
 * every tool shows its [ToolEntry.label] next to its icon, and [onSelect] fires with the tool
 * when one is tapped (the dial closes first).
 *
 * Collapse-by-default behind the FAB, so the map owns the screen until the operator taps to
 * reveal the tools — and every tool stays one tap away in both orientations.
 *
 * The open state is [expanded], held by the caller: while the dial is open the caller hides
 * the position card, which the dial would otherwise be drawn over. A tap anywhere else on the
 * map, or Back, closes the dial and does nothing else: while it is open a layer over the map
 * takes the touch before the map sees it.
 *
 * Portrait is the Material "speed dial": a vertical stack that grows upward from the FAB,
 * each tool a dark label chip to the left of its icon. It is bounded by the room under the
 * status strip and scrolls rather than reaching off the top of a short screen or a large font.
 *
 * #182 — orientation aware. Landscape (the plate-carrier orientation on a Pixel 9/10 Pro) has
 * little vertical room, so there the tools flow into a compact horizontal rail that grows
 * sideways (wrapping into extra rows if needed) with tighter pip sizing, leaving the map its
 * height. Labels go under the icons when they fit ([ToolsDrawerLayout.landscapeShowsLabels]);
 * on a screen too short for that the rail keeps icons only.
 *
 * The drawer only renders itself when its host allows — callers place it in a Box on top of
 * the map.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolsDrawer(
    tools: List<ToolEntry>,
    onSelect: (ToolEntry) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val fontScale = LocalDensity.current.fontScale
    val toolSize = ToolsDrawerLayout.toolSizeDp(isLandscape)
    val fabSize = ToolsDrawerLayout.fabSizeDp(isLandscape)
    val spacing = ToolsDrawerLayout.spacingDp(isLandscape)

    // Back closes the dial before it leaves the map.
    BackHandler(enabled = expanded) { onExpandedChange(false) }

    // The layer over the map while the dial is open. It is above the map and the other map
    // controls and below the dial, and it takes every touch, so a tap on the map closes the
    // dial without selecting, placing or moving anything.
    if (expanded) {
        Box(
            Modifier
                .fillMaxSize()
                .zIndex(SCRIM_Z)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = "Close tools",
                ) { onExpandedChange(false) },
        )
    }

    val fab: @Composable () -> Unit = {
        // Main FAB — always visible; swaps to a close icon when expanded.
        Box(
            modifier = Modifier
                .size(fabSize.dp)
                .clip(CircleShape)
                .background(TacticalAccent)
                .clickable { onExpandedChange(!expanded) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (expanded) Icons.Filled.Close else Icons.Filled.Menu,
                contentDescription = if (expanded) "Close tools" else "Open tools",
                tint = TacticalBackground,
            )
        }
    }

    val select: (ToolEntry) -> Unit = { tool ->
        onExpandedChange(false)
        onSelect(tool)
    }

    // While open the dial is above the layer over the map; closed it takes no part in the
    // stacking order, so the main button stays under whatever the screen puts over it.
    BoxWithConstraints(modifier.zIndex(if (expanded) DIAL_Z else 0f)) {
        // What the safe area leaves to the dial: its own padding is inside this height.
        val availableHeightDp = maxHeight.value.toInt() - VERTICAL_PADDING_DP

        if (ToolsDrawerLayout.useHorizontalRail(isLandscape)) {
            // Landscape: the FAB pins to the bottom-right; the expanded tools
            // flow leftward in a wrapping rail above it. maxItemsInEachRow caps
            // the rail width (using the screen width) so it can't stretch
            // edge-to-edge and re-eat the map; extra tools wrap to a new row.
            val screenWidthDp = LocalConfiguration.current.screenWidthDp
            // Leave the right third for the map; pad both ends out of the rail box.
            val railWidthDp = (screenWidthDp * 2) / 3
            val longestLabel = tools.maxOfOrNull { it.label.length } ?: 0
            val showLabels = ToolsDrawerLayout.landscapeShowsLabels(
                toolCount = tools.size,
                toolSizeDp = toolSize,
                spacingDp = spacing,
                fabSizeDp = fabSize,
                railWidthDp = railWidthDp,
                availableHeightDp = availableHeightDp,
                longestLabelChars = longestLabel,
                fontScale = fontScale,
            )
            val cellWidth = if (showLabels) {
                ToolsDrawerLayout.landscapeCellWidthDp(toolSize, longestLabel, fontScale)
            } else {
                toolSize
            }
            val perRow = ToolsDrawerLayout.toolsPerRow(
                availableWidthDp = railWidthDp,
                toolSizeDp = cellWidth,
                spacingDp = spacing,
            )
            Column(
                modifier = Modifier.padding(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = MapOrnamentLayout.toolsButtonBottomDp(true).dp,
                    top = 12.dp,
                ),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(spacing.dp),
            ) {
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn(tween(120)) + scaleIn(tween(120), transformOrigin = FROM_THE_FAB),
                    exit = fadeOut(tween(120)) + scaleOut(tween(120), transformOrigin = FROM_THE_FAB),
                ) {
                    FlowRow(
                        modifier = Modifier
                            .widthIn(max = railWidthDp.dp)
                            .padding(end = ToolsDrawerLayout.dialEndInsetDp(isLandscape).dp),
                        horizontalArrangement = Arrangement.spacedBy(spacing.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(spacing.dp),
                        maxItemsInEachRow = perRow,
                    ) {
                        tools.forEach { tool ->
                            RailTool(tool, toolSize.dp, if (showLabels) cellWidth.dp else null) { select(tool) }
                        }
                    }
                }
                fab()
            }
        } else {
            // Portrait: a vertical stack growing upward from the FAB, each tool a label chip and
            // its icon. Bounded by the room under the status strip; scrolls past that, from the
            // FAB end, so the tools nearest the button are the ones in view first.
            val maxDialDp = ToolsDrawerLayout.dialMaxHeightDp(availableHeightDp, fabSize, spacing)
            Column(
                modifier = Modifier.padding(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = MapOrnamentLayout.toolsButtonBottomDp(false).dp,
                    top = 16.dp,
                ),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(spacing.dp),
            ) {
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn(tween(120)) + scaleIn(tween(120), transformOrigin = FROM_THE_FAB),
                    exit = fadeOut(tween(120)) + scaleOut(tween(120), transformOrigin = FROM_THE_FAB),
                ) {
                    Column(
                        modifier = Modifier
                            .heightIn(max = maxDialDp.dp)
                            .verticalScroll(rememberScrollState(), reverseScrolling = true)
                            .padding(end = ToolsDrawerLayout.dialEndInsetDp(isLandscape).dp),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(spacing.dp),
                    ) {
                        tools.forEach { tool ->
                            DialTool(tool, toolSize.dp) { select(tool) }
                        }
                    }
                }
                fab()
            }
        }
    }
}

/** Where the dial stacks while open: the layer over the map is one below it. */
private const val SCRIM_Z = 1f
private const val DIAL_Z = 2f

/** The dial's own padding above and below, inside the height the safe area gives it. */
private const val VERTICAL_PADDING_DP = 16

/** The dial and the rail come out of the main button, at the bottom right. */
private val FROM_THE_FAB = TransformOrigin(1f, 1f)

/** Portrait: a dark label chip to the left of the tool's icon; the whole row is the target. */
@Composable
private fun DialTool(tool: ToolEntry, size: Dp, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(size / 2))
            .clickable(enabled = tool.enabled, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ToolsDrawerLayout.LABEL_GAP_DP.dp),
    ) {
        LabelChip(tool, ToolsDrawerLayout.PORTRAIT_LABEL_SP, Modifier.widthIn(max = 220.dp))
        ToolCircle(tool, size)
    }
}

/**
 * Landscape: the icon, with its label under it when there is room ([cellWidth] is then the
 * width of the cell, so the cells line up), or the icon alone.
 */
@Composable
private fun RailTool(tool: ToolEntry, size: Dp, cellWidth: Dp?, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .then(if (cellWidth != null) Modifier.width(cellWidth) else Modifier)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = tool.enabled, role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy((ToolsDrawerLayout.LABEL_GAP_DP / 2).dp),
    ) {
        ToolCircle(tool, size, describe = cellWidth == null)
        if (cellWidth != null) {
            LabelChip(tool, ToolsDrawerLayout.LANDSCAPE_LABEL_SP)
        }
    }
}

/**
 * The tool's name on a dark chip with light text: readable over a bright map, and over a dark
 * one. At most two lines, so a large font grows the chip instead of cutting the word off.
 */
@Composable
private fun LabelChip(tool: ToolEntry, textSp: Int, modifier: Modifier = Modifier) {
    Text(
        text = tool.label,
        color = if (tool.enabled) Color.White else Color.White.copy(alpha = 0.55f),
        fontSize = textSp.sp,
        // Pinned, so the chip is the height ToolsDrawerLayout counts, not the Material default of 24 sp.
        lineHeight = (textSp * ToolsDrawerLayout.LABEL_LINE_HEIGHT_EM).sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(TacticalBackground.copy(alpha = 0.92f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .padding(
                horizontal = ToolsDrawerLayout.CHIP_PADDING_H_DP.dp,
                vertical = ToolsDrawerLayout.CHIP_PADDING_V_DP.dp,
            ),
    )
}

/**
 * The round icon. With a label beside or under it the icon is decoration and the label is the
 * name a screen reader reads; [describe] is true only for an icon on its own.
 */
@Composable
private fun ToolCircle(tool: ToolEntry, size: Dp, describe: Boolean = false) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(TacticalBackground.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = tool.icon,
            contentDescription = if (describe) tool.label else null,
            tint = if (tool.enabled) TacticalAccent else Color.Gray,
        )
    }
}
