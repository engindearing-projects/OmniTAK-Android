package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import kotlinx.coroutines.flow.collectLatest

/** How long a control takes to move out of the way of the floating toolbar: the same as the toolbar's own fade and slide. */
internal const val TOOLBAR_CLEARANCE_MS = 150

/**
 * How many dp above the safe area's bottom edge the map's bottom controls must keep free for the
 * floating toolbar ([MapOrnamentLayout.clearanceDp]), animating toward what [occupiedDp] says the
 * toolbar occupies now. [occupiedDp] is read here, outside composition, so a change of the
 * toolbar (it shows, it hides, its tip goes away) moves the controls and recomposes nothing; the
 * value itself must only be read while laying out (see [liftAboveToolbar]) or in an effect.
 */
@Composable
internal fun rememberToolbarClearance(occupiedDp: () -> Int): State<Float> {
    val currentOccupied = rememberUpdatedState(occupiedDp)
    // Start where the toolbar is, not at nothing: a map composed while the toolbar is already
    // showing must not open with the controls under it and then slide out.
    val clearance = remember {
        Animatable(Snapshot.withoutReadObservation { MapOrnamentLayout.clearanceDp(occupiedDp().toFloat()) })
    }
    LaunchedEffect(clearance) {
        snapshotFlow { MapOrnamentLayout.clearanceDp(currentOccupied.value().toFloat()) }
            .collectLatest { clearance.animateTo(it, tween(TOOLBAR_CLEARANCE_MS)) }
    }
    return clearance.asState()
}

/**
 * Lifts a bottom control, with everything that is its place in its corner, above the floating
 * toolbar: by [MapOrnamentLayout.liftDp] for a lowest edge that rests [restingBottomDp] above the
 * bottom edge, nothing while the toolbar is hidden. It reads [clearanceDp] while laying out, so
 * the animation moves the control without recomposing anything. Put it after the safe area's
 * padding and before the control's own padding: the lift is added under the control, and what
 * the control lays out in shrinks by the same amount (a dial that grows upward gets less room).
 */
internal fun Modifier.liftAboveToolbar(clearanceDp: State<Float>, restingBottomDp: Float): Modifier =
    layout { measurable, constraints ->
        val liftPx = MapOrnamentLayout.liftDp(restingBottomDp, clearanceDp.value).dp.roundToPx()
        val placeable = measurable.measure(constraints.offset(vertical = -liftPx))
        layout(placeable.width, placeable.height + liftPx) { placeable.place(0, 0) }
    }
