package soy.engindearing.omnitak.mobile.ui.components

import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.symbology.MilStdIconService

/**
 * Issue #77 — pure, device-free description of one contact marker as it should
 * appear on the MapLibre [org.maplibre.android.plugins.annotation.SymbolManager]
 * render path (the annotation/bitmap pipeline LocationComponent uses, NOT a
 * GeoJSON [org.maplibre.android.style.layers.SymbolLayer]).
 *
 * Why this exists as a plain data class: the GeoJSON CircleLayer / SymbolLayer
 * contact path is "queryable but never draws pixels" on Adreno 610 / Mali-G57 /
 * Pixel Immortalis — a MapLibre-Android fragment-pipeline failure a full style
 * reload does not fix (confirmed on-device, issue #77). The self-marker renders
 * because it is a [org.maplibre.android.location.LocationComponent] (annotation
 * path). [ContactSymbolManager] moves contacts onto that same proven path.
 *
 * Splitting the contact→symbol mapping out here lets us TDD the load-bearing
 * logic (icon-image id resolution, callsign, team color, coordinates) in pure
 * JVM. The only thing left for on-device verification is whether the annotation
 * pipeline paints — which is exactly the part a unit test cannot assert.
 *
 * @property uid        stable contact identity — the diff key, so updates
 *                      move/restyle the existing [org.maplibre.android.plugins.annotation.Symbol]
 *                      instead of churning add+remove.
 * @property lat        latitude in degrees. Finite (NaN rows are dropped upstream).
 * @property lon        longitude in degrees. Finite.
 * @property imageId    the MapLibre style image name registered via
 *                      [org.maplibre.android.maps.Style.addImage] and referenced
 *                      by [org.maplibre.android.plugins.annotation.SymbolOptions.withIconImage].
 *                      Either `milstd-<sidc>` or a TAK-icon-suite id
 *                      (`takicon-spot-…`) — mirrors [ContactSymbolLayer] exactly.
 * @property callsign   visible text label. Falls back to uid when no callsign —
 *                      so a marker is never anonymous (regression guard against
 *                      the "all blue, no callsign" report the GeoJSON path was
 *                      added to fix).
 * @property teamColorArgb ARGB team / affiliation color from [CoTEvent.displayColor].
 *                      Applied to the **callsign text label** (see note below) so
 *                      the team-color signal the GeoJSON CircleLayer carried is
 *                      preserved — Red-1's label reads red, Orange-Chef's orange.
 *
 * ### Why team color goes on the label, not the icon
 * MapLibre's `icon-color` / [org.maplibre.android.plugins.annotation.Symbol.setIconColor]
 * only tints **SDF** icons (single-channel alpha masks). Our MIL-STD frames and
 * TAK-suite glyphs are full-color raster bitmaps, so an icon tint would be a
 * silent no-op on them — and registering them as SDF would flatten the
 * affiliation frame (friend-blue rectangle, hostile-red diamond) and the
 * multi-color FEMA/spot glyphs to a single hue. The icon therefore keeps its own
 * baked colors (the MIL-STD bitmap already encodes friend/hostile/neutral), and
 * the team color rides on the callsign label text, which IS rendered from SDF
 * glyphs and tints reliably. This mirrors the GeoJSON path's split (icon + a
 * separately-colored element) without its never-painting CircleLayer.
 */
data class ContactSymbolSpec(
    val uid: String,
    val lat: Double,
    val lon: Double,
    val imageId: String,
    val callsign: String,
    val teamColorArgb: Int,
) {
    companion object {
        /**
         * Build the [ContactSymbolSpec] for [c]. [resolvedImageId] is the image
         * name the caller already registered via [org.maplibre.android.maps.Style.addImage]
         * (TAK-suite id when [c] resolved to a bundled iconset, else the MIL-STD
         * `milstd-<sidc>` name).
         *
         * Kept out of the live [ContactSymbolManager] so the whole mapping is
         * exercised in pure JVM with no MapLibre / Android types in scope.
         */
        fun from(
            c: CoTEvent,
            resolvedImageId: String,
        ): ContactSymbolSpec = ContactSymbolSpec(
            uid = c.uid,
            lat = c.lat,
            lon = c.lon,
            imageId = resolvedImageId,
            // Never anonymous: a blank callsign would reintroduce the "no
            // callsign" half of the P-E regression — fall back to the uid.
            callsign = c.callsign?.takeIf { it.isNotBlank() } ?: c.uid,
            // displayColor = TAK team palette when teamName is set, else the
            // MIL-STD affiliation color. Either way it is the team-color signal
            // we paint the label with.
            teamColorArgb = c.displayColor,
        )

        /**
         * Convenience for the MIL-STD path: resolve the SIDC and build the
         * `milstd-<sidc>` image id the same way [ContactSymbolLayer] does, so the
         * registration key and the spec never drift.
         */
        fun milStd(c: CoTEvent): ContactSymbolSpec {
            val sidc = MilStdIconService.getSidc(c.type)
            return from(c = c, resolvedImageId = ContactSymbolLayer.ICON_IMAGE_PREFIX + sidc)
        }
    }
}
