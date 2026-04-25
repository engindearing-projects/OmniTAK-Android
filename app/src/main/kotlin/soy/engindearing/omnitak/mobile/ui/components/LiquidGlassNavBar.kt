package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Per-tab descriptor for the liquid-glass nav bar. Each entry carries
 * its own brand color so the icon row reads as a tactical traffic-stop
 * rather than five greyscale glyphs (mirrors the iOS bottom bar).
 */
data class NavTabSpec(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val tint: Color,
)

/**
 * Floating-pill bottom navigation matching the iOS 26 "Liquid Glass"
 * look. A rounded translucent capsule floats above the system bar with
 * horizontal margin; the selected tab gets a tinted halo behind its
 * icon. Each tab carries its own brand color so the row reads as a
 * tactical traffic-stop instead of five greyscale glyphs.
 */
@Composable
fun LiquidGlassNavBar(
    tabs: List<NavTabSpec>,
    currentRoute: String?,
    onSelect: (NavTabSpec) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(elevation = 16.dp, shape = RoundedCornerShape(34.dp), clip = false)
                .clip(RoundedCornerShape(34.dp))
                .background(Color(0xCC0F1115))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(34.dp))
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                NavTabItem(
                    tab = tab,
                    selected = currentRoute == tab.route,
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NavTabItem(
    tab: NavTabSpec,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haloColor by animateColorAsState(
        targetValue = if (selected) tab.tint.copy(alpha = 0.22f) else Color.Transparent,
        label = "navTabHalo",
    )
    val iconTint by animateColorAsState(
        targetValue = if (selected) tab.tint else Color.White.copy(alpha = 0.85f),
        label = "navTabIcon",
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) tab.tint else Color.White.copy(alpha = 0.7f),
        label = "navTabLabel",
    )
    val haloSize by animateDpAsState(
        targetValue = if (selected) 44.dp else 36.dp,
        label = "navTabHaloSize",
    )

    val interaction = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true, color = tab.tint),
                onClick = onClick,
            )
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(haloSize)
                .clip(RoundedCornerShape(percent = 50))
                .background(haloColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = tab.icon,
                contentDescription = tab.label,
                tint = iconTint,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = tab.label,
            color = labelColor,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
