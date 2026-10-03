package soy.engindearing.omnitak.mobile.ui.components

import org.maplibre.android.location.modes.RenderMode

/**
 * #204 — pure recipe for how the self-marker "puck" should be built for a
 * given marker style: which registered style-image goes in the foreground
 * layer, which (if any) goes in the rotating bearing layer, their dimmed/
 * stale counterparts, and which LocationComponent [RenderMode] the style
 * needs.
 *
 * Why this exists: under `RenderMode.COMPASS`/`GPS`, MapLibre's
 * LocationComponent draws the foreground AND bearing layers as two
 * independent images at the SAME screen position — the foreground stays
 * upright while the bearing layer rotates to heading. Pointing both layers
 * at the identical bitmap (the pre-fix code, for both MIL-STD and the
 * triangle marker) draws the self-marker twice the moment heading != 0
 * (issue #204). `LocationComponentOptions.Builder` also has no
 * `bearingStaleName` method (verified against the compiled MapLibre 11.8.0
 * builder surface), so a bearing image can never dim through MapLibre's own
 * internal stale-timeout machinery — only the caller picking between
 * [bearingImage] and [bearingStaleImage] when it rebuilds the puck for a
 * new fix can express that.
 *
 * The three styles want different things:
 *  - **MIL-STD-2525** (default) — the symbol body must NOT rotate (the
 *    #159 echelon amplifier assumes an upright symbol), so [renderMode] is
 *    `RenderMode.NORMAL`, which never renders a bearing layer at all —
 *    [bearingImage]/[bearingStaleImage] are null because there is nothing
 *    to set. Nothing draws twice because there is only one layer.
 *  - **Triangle** (#83) — the whole point is to rotate with heading, so it
 *    stays on `RenderMode.COMPASS`, but ONLY the bearing layer carries the
 *    triangle; [foregroundImage]/[foregroundStaleImage] point at a fully
 *    transparent 1x1 bitmap so COMPASS's second, non-rotating copy is
 *    invisible instead of a ghost triangle sitting under the real one.
 *  - **Legacy disc** (and any unrecognized style string) — unchanged. It
 *    already uses two distinct drawables (`ic_self_marker` /
 *    `ic_self_marker_bearing`) via the `foregroundDrawable`/
 *    `bearingDrawable` builder methods — a different LocationComponentOptions
 *    code path from the named-style-image one this spec drives — so every
 *    image field here is null and only [renderMode] applies (see
 *    `buildPuckOptions`'s own `else` branch).
 */
internal data class SelfPuckSpec(
    val renderMode: Int,
    val foregroundImage: String?,
    val bearingImage: String?,
    val foregroundStaleImage: String?,
    val bearingStaleImage: String?,
)

internal const val SELF_MARKER_STYLE_MIL_STD = "milstd"
internal const val SELF_MARKER_STYLE_TRIANGLE = "triangle"
internal const val SELF_MARKER_STYLE_LEGACY = "legacy"

/** Registered style-image names for the MIL-STD-2525 self symbol. */
internal const val SELF_FOREGROUND_IMAGE = "omnitak-self-milstd-foreground"
internal const val SELF_FOREGROUND_STALE_IMAGE = "omnitak-self-milstd-foreground-stale"

/** Registered style-image names for the #83 triangle self-marker. */
internal const val SELF_TRIANGLE_IMAGE = "omnitak-self-triangle"
internal const val SELF_TRIANGLE_STALE_IMAGE = "omnitak-self-triangle-stale"
internal const val SELF_TRIANGLE_TRANSPARENT_IMAGE = "omnitak-self-triangle-transparent"
internal const val SELF_TRIANGLE_TRANSPARENT_STALE_IMAGE = "omnitak-self-triangle-transparent-stale"

/**
 * Resolve the self-marker style string from the two persisted preferences
 * ([soy.engindearing.omnitak.mobile.data.UserPrefs.useMilStdSelfSymbol] and
 * [soy.engindearing.omnitak.mobile.data.UserPrefs.selfMarkerTriangle]).
 * Precedence matches the pre-existing branch order in `buildPuckOptions`:
 * triangle overrides MIL-STD overrides the legacy disc default.
 */
internal fun selfMarkerStyleFor(useMilStdSelfSymbol: Boolean, selfMarkerTriangle: Boolean): String = when {
    selfMarkerTriangle -> SELF_MARKER_STYLE_TRIANGLE
    useMilStdSelfSymbol -> SELF_MARKER_STYLE_MIL_STD
    else -> SELF_MARKER_STYLE_LEGACY
}

/**
 * #204 — given the self-marker style string ([SELF_MARKER_STYLE_MIL_STD],
 * [SELF_MARKER_STYLE_TRIANGLE], or anything else — treated the same as
 * [SELF_MARKER_STYLE_LEGACY]), return the puck layout it needs. See the
 * class doc above for the reasoning behind each style's shape.
 */
internal fun selfPuckSpecFor(style: String): SelfPuckSpec = when (style) {
    SELF_MARKER_STYLE_MIL_STD -> SelfPuckSpec(
        renderMode = RenderMode.NORMAL,
        foregroundImage = SELF_FOREGROUND_IMAGE,
        bearingImage = null,
        foregroundStaleImage = SELF_FOREGROUND_STALE_IMAGE,
        bearingStaleImage = null,
    )
    SELF_MARKER_STYLE_TRIANGLE -> SelfPuckSpec(
        renderMode = RenderMode.COMPASS,
        foregroundImage = SELF_TRIANGLE_TRANSPARENT_IMAGE,
        bearingImage = SELF_TRIANGLE_IMAGE,
        foregroundStaleImage = SELF_TRIANGLE_TRANSPARENT_STALE_IMAGE,
        bearingStaleImage = SELF_TRIANGLE_STALE_IMAGE,
    )
    else -> SelfPuckSpec(
        renderMode = RenderMode.COMPASS,
        foregroundImage = null,
        bearingImage = null,
        foregroundStaleImage = null,
        bearingStaleImage = null,
    )
}
