package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonPin
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import soy.engindearing.omnitak.mobile.domain.FollowHealth
import soy.engindearing.omnitak.mobile.domain.FollowHoldReason
import soy.engindearing.omnitak.mobile.domain.UASManager

/**
 * Follow-Me toggle chip. Lives under the cruise altitude pill in the
 * map HUD when a UAS is connected.
 *
 * Inactive: dark "Follow Me" tap to engage.
 * Active: red "FOLLOWING · STOP" — operator can hit it from anywhere
 * to disengage and put the drone back in AUTO.LOITER.
 * Active but holding ([health] is [FollowHealth.Holding], #249): amber, with
 * the reason ("Follow: waiting for GPS", or "Follow: contact position lost"
 * when the [subject] is a pursued contact). Nothing is being sent to the
 * vehicle, and the pill still stops following when tapped.
 */
@Composable
fun UasFollowMePill(
    active: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    health: FollowHealth = FollowHealth.Idle,
    subject: UASManager.FollowSubject? = null,
) {
    val holdText = (health as? FollowHealth.Holding)
        ?.takeIf { active }
        ?.let { followHoldLabel(it.reason, subject) }
    val holding = holdText != null
    val bg = when {
        holding -> Color(0xFFFFC107).copy(alpha = 0.92f)
        active -> Color(0xFFFF3B30).copy(alpha = 0.9f)
        else -> Color.Black.copy(alpha = 0.78f)
    }
    val fg = when {
        holding -> Color.Black
        active -> Color.White
        else -> Color(0xFF00E5FF)
    }
    val label = holdText ?: if (active) "FOLLOWING · STOP" else "Follow Me"
    val icon = if (active) Icons.Filled.Stop else Icons.Filled.PersonPin

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.padding(end = 2.dp))
        Text(
            text = label,
            color = if (holding) Color.Black else Color.White,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * Pill text while the follow stream is holding (#249). A stale position means
 * different things for the two subjects, so the wording follows [subject]: the
 * operator's GPS fix has stopped arriving, or a pursued contact has no position
 * (it left the contact store). A null subject reads as the operator.
 */
fun followHoldLabel(reason: FollowHoldReason, subject: UASManager.FollowSubject?): String = when (reason) {
    FollowHoldReason.StaleFix ->
        if (subject is UASManager.FollowSubject.Contact) "Follow: contact position lost"
        else "Follow: waiting for GPS"
    FollowHoldReason.NoAltitudeReference -> "Follow: no altitude reference"
}

/**
 * Message when the follow stream stopped itself and put the drone in LOITER
 * (#249). Worded by [subject] like [followHoldLabel]; the subject comes from
 * [FollowHealth.Stopped], because the follow state is already cleared by the
 * time this is shown.
 */
fun followStoppedMessage(reason: FollowHoldReason, subject: UASManager.FollowSubject?): String = when (reason) {
    FollowHoldReason.StaleFix ->
        if (subject is UASManager.FollowSubject.Contact) "Follow stopped: contact position lost — drone parked in LOITER"
        else "Follow stopped: GPS fix too old — drone parked in LOITER"
    FollowHoldReason.NoAltitudeReference -> "Follow stopped: no altitude reference — drone parked in LOITER"
}
