package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.ui.graphics.Color
import soy.engindearing.omnitak.mobile.domain.AggregateHealth
import soy.engindearing.omnitak.mobile.domain.ServerHealth
import soy.engindearing.omnitak.mobile.ui.theme.HostileRed
import soy.engindearing.omnitak.mobile.ui.theme.NeutralYellow
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent

/**
 * #209: the one colour mapping for [ServerHealth] / [AggregateHealth], used by
 * the map's status bar and by the Servers rows so the same state can't be green
 * in one place and red in the other. Amber is [NeutralYellow] (0xFFFFC107).
 */
fun ServerHealth.dotColor(): Color = when (this) {
    ServerHealth.CONNECTED -> TacticalAccent
    ServerHealth.CONNECTING -> NeutralYellow
    ServerHealth.FAILED -> HostileRed
    // Enabled but nothing live and nothing in flight: neutral, not an error.
    ServerHealth.DISCONNECTED -> Color.Gray
    ServerHealth.DISABLED -> Color.Gray.copy(alpha = 0.5f)
}

fun AggregateHealth.lightColor(): Color = when (this) {
    AggregateHealth.GREEN -> TacticalAccent
    AggregateHealth.AMBER -> NeutralYellow
    AggregateHealth.RED -> HostileRed
}
