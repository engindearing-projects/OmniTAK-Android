package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import soy.engindearing.omnitak.mobile.ui.theme.TacticalBackground

/**
 * The window's insets, in the two ways the map uses them.
 *
 * [safeArea]: the map fills the whole window, under the status bar and the navigation bar.
 * Everything that is drawn on it for the operator to read or touch is laid out inside this: clear
 * of the system bars (gesture or three-button navigation, on whichever edge they are) and of a
 * display cutout. One definition, so the Compose chrome ([mapSafeAreaPadding]) and the MapLibre
 * ornaments ([mapSafeAreaPx]) cannot disagree about where the safe area is. The keyboard is not
 * part of it: nothing on the map moves when it opens.
 *
 * [systemBars]: where a system icon can be. The fade behind the icons ([SystemBarScrims]) covers
 * this and nothing else: a cutout is a hole for a camera with no icons in it, and in landscape the
 * fade put a dark band down that edge of the map.
 */
internal class MapInsets(systemBars: WindowInsets, displayCutout: WindowInsets) {
    val safeArea: WindowInsets = systemBars.union(displayCutout)
    val systemBars: WindowInsets = systemBars
}

@Composable
private fun mapInsets(): MapInsets = MapInsets(WindowInsets.systemBars, WindowInsets.displayCutout)

@Composable
private fun mapSafeAreaInsets(): WindowInsets = mapInsets().safeArea

/**
 * Lay this out inside the safe area of the window. Put it before the other padding of a map
 * control: that padding is then measured from the safe area, as it always was. The insets are
 * consumed, so a control inside another that already did this is not moved twice.
 */
@Composable
internal fun Modifier.mapSafeAreaPadding(): Modifier = windowInsetsPadding(mapSafeAreaInsets())

/** The safe area in px, read in composition so it follows rotation and a change of navigation mode. */
@Composable
internal fun mapSafeAreaPx(): MapOrnamentLayout.EdgePx = edgesPx(mapSafeAreaInsets())

/** The system bars alone in px: what [SystemBarScrims] covers. */
@Composable
private fun systemBarsPx(): MapOrnamentLayout.EdgePx = edgesPx(mapInsets().systemBars)

@Composable
private fun edgesPx(insets: WindowInsets): MapOrnamentLayout.EdgePx {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    return MapOrnamentLayout.EdgePx(
        left = insets.getLeft(density, direction),
        top = insets.getTop(density),
        right = insets.getRight(density, direction),
        bottom = insets.getBottom(density),
    )
}

/**
 * The system bars' icons are light and the map under them can be bright (sand, snow, a
 * satellite basemap): without help the clock, the signal and the gesture handle wash out. A
 * navy fade, only as tall (or wide) as the bar itself, keeps them readable. It is drawn on the
 * bar's own pixels, it takes no touches, and the map inside the safe area is not tinted. It
 * covers the system bars only, not a display cutout ([MapInsets]).
 */
@Composable
internal fun SystemBarScrims(modifier: Modifier = Modifier) {
    val safe = systemBarsPx()
    val navy = TacticalBackground
    // Strongest at the screen edge, where the icons are; gone at the safe area's edge.
    val stops = arrayOf(0f to navy.copy(alpha = 0.78f), 0.55f to navy.copy(alpha = 0.55f), 1f to Color.Transparent)
    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                val w = size.width
                val h = size.height
                if (safe.top > 0) {
                    drawRect(
                        brush = Brush.verticalGradient(colorStops = stops, startY = 0f, endY = safe.top.toFloat()),
                        size = Size(w, safe.top.toFloat()),
                    )
                }
                if (safe.bottom > 0) {
                    drawRect(
                        brush = Brush.verticalGradient(colorStops = stops, startY = h, endY = h - safe.bottom),
                        topLeft = Offset(0f, h - safe.bottom),
                        size = Size(w, safe.bottom.toFloat()),
                    )
                }
                if (safe.left > 0) {
                    drawRect(
                        brush = Brush.horizontalGradient(colorStops = stops, startX = 0f, endX = safe.left.toFloat()),
                        size = Size(safe.left.toFloat(), h),
                    )
                }
                if (safe.right > 0) {
                    drawRect(
                        brush = Brush.horizontalGradient(colorStops = stops, startX = w, endX = w - safe.right),
                        topLeft = Offset(w - safe.right, 0f),
                        size = Size(safe.right.toFloat(), h),
                    )
                }
            },
    )
}
