package soy.engindearing.omnitak.mobile.ui.onboarding

import android.content.Context
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import soy.engindearing.omnitak.mobile.i18n.Loc

// ---------------------------------------------------------------------------
// OnboardingManager
// ---------------------------------------------------------------------------

/** Tracks whether the first-run onboarding flow has been completed. */
object OnboardingManager {
    private const val PREFS = "omnitak_onboarding"
    private const val KEY = "onboarding_complete"

    fun isComplete(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)

    fun markComplete(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY, true)
            .apply()
    }
}

// ---------------------------------------------------------------------------
// Data model
// ---------------------------------------------------------------------------

/** A single onboarding page definition — mirrors iOS OnboardingPage. */
data class OnboardingPage(
    val icon: ImageVector,
    val titleKey: String,
    val descKey: String,
    val accentColor: Color,
    val featureKeys: List<String>,
)

private val pages = listOf(
    OnboardingPage(
        icon = Icons.Default.Wifi,
        titleKey = "onboarding.page1.title",
        descKey = "onboarding.page1.desc",
        accentColor = Color(0xFFFFFC00),
        featureKeys = listOf(
            "onboarding.page1.feature1",
            "onboarding.page1.feature2",
            "onboarding.page1.feature3",
            "onboarding.page1.feature4",
        ),
    ),
    OnboardingPage(
        icon = Icons.Default.Lock,
        titleKey = "onboarding.page2.title",
        descKey = "onboarding.page2.desc",
        accentColor = Color(0xFF00C853),
        featureKeys = listOf(
            "onboarding.page2.feature1",
            "onboarding.page2.feature2",
            "onboarding.page2.feature3",
            "onboarding.page2.feature4",
        ),
    ),
    OnboardingPage(
        icon = Icons.Default.FlashOn,
        titleKey = "onboarding.page3.title",
        descKey = "onboarding.page3.desc",
        accentColor = Color(0xFF00B0FF),
        featureKeys = listOf(
            "onboarding.page3.feature1",
            "onboarding.page3.feature2",
            "onboarding.page3.feature3",
            "onboarding.page3.feature4",
        ),
    ),
    OnboardingPage(
        icon = Icons.Default.Map,
        titleKey = "onboarding.page4.title",
        descKey = "onboarding.page4.desc",
        accentColor = Color(0xFFFF6B35),
        featureKeys = listOf(
            "onboarding.page4.feature1",
            "onboarding.page4.feature2",
            "onboarding.page4.feature3",
            "onboarding.page4.feature4",
        ),
    ),
    OnboardingPage(
        icon = Icons.Default.Tune,
        titleKey = "onboarding.page5.title",
        descKey = "onboarding.page5.desc",
        accentColor = Color(0xFFFFCC00),
        featureKeys = listOf(
            "onboarding.page5.feature1",
            "onboarding.page5.feature2",
            "onboarding.page5.feature3",
            "onboarding.page5.feature4",
        ),
    ),
)

// ---------------------------------------------------------------------------
// Root composable
// ---------------------------------------------------------------------------

/**
 * Full-screen first-run onboarding flow. Rendered before [AppNav] on the very
 * first launch (gated by [OnboardingManager.isComplete]).
 *
 * @param onComplete Called when the user taps "Get Started" on the last page
 *   or "Skip" on any page. The caller persists the flag and swaps in [AppNav].
 */
@Composable
fun OnboardingFlow(onComplete: () -> Unit) {
    var pageIndex by remember { mutableIntStateOf(0) }
    val page = pages[pageIndex]
    val isLast = pageIndex == pages.lastIndex

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A)),
    ) {
        // Skip — always visible at top-right
        TextButton(
            onClick = onComplete,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 8.dp),
        ) {
            Text(
                text = Loc.t("onboarding.skip"),
                color = Color(0xFFAAAAAA),
                fontSize = 16.sp,
            )
        }

        // Page content — centered
        OnboardingPageContent(
            page = page,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp)
                .padding(top = 80.dp, bottom = 220.dp),
        )

        // Bottom controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 24.dp)
                .padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Page indicator dots
            PageIndicator(
                count = pages.size,
                current = pageIndex,
                accentColor = Color(0xFFFFFC00),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Primary action button: Continue / Get Started
            Button(
                onClick = {
                    if (isLast) onComplete() else pageIndex++
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFFFC00),
                    contentColor = Color(0xFF000000),
                ),
            ) {
                Text(
                    text = if (isLast) Loc.t("onboarding.getStarted")
                           else Loc.t("onboarding.continue"),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            // Back button — visible from page 2 onwards
            if (pageIndex > 0) {
                TextButton(onClick = { pageIndex-- }) {
                    Text(
                        text = Loc.t("onboarding.back"),
                        color = Color(0xFF888888),
                        fontSize = 16.sp,
                    )
                }
            } else {
                // Reserve the same vertical space so the Continue button
                // doesn't shift when Back appears/disappears.
                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Page content
// ---------------------------------------------------------------------------

@Composable
private fun OnboardingPageContent(
    page: OnboardingPage,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Icon with two concentric tinted circles (outer 15 %, inner 25 %)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(140.dp)
                .clip(CircleShape)
                .background(page.accentColor.copy(alpha = 0.15f)),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(page.accentColor.copy(alpha = 0.25f)),
            ) {
                Icon(
                    imageVector = page.icon,
                    contentDescription = null,
                    tint = page.accentColor,
                    modifier = Modifier.size(44.dp),
                )
            }
        }

        // Title
        Text(
            text = Loc.t(page.titleKey),
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        // Description
        Text(
            text = Loc.t(page.descKey),
            color = Color(0xFFCCCCCC),
            fontSize = 17.sp,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp,
        )

        // Feature list
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (key in page.featureKeys) {
                FeatureRow(text = Loc.t(key), accentColor = page.accentColor)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Feature row
// ---------------------------------------------------------------------------

@Composable
private fun FeatureRow(text: String, accentColor: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            color = Color.White,
            fontSize = 15.sp,
        )
    }
}

// ---------------------------------------------------------------------------
// Page indicator dots
// ---------------------------------------------------------------------------

@Composable
private fun PageIndicator(
    count: Int,
    current: Int,
    accentColor: Color,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until count) {
            val isActive = i == current
            val width: Dp by animateDpAsState(
                targetValue = if (isActive) 24.dp else 8.dp,
                animationSpec = spring(),
                label = "dot_width_$i",
            )
            Box(
                modifier = Modifier
                    .width(width)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (isActive) accentColor else Color(0xFF444444),
                    ),
            )
        }
    }
}
