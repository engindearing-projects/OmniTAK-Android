package soy.engindearing.omnitak.mobile.data.rangebearing

import soy.engindearing.omnitak.mobile.data.Drawing
import soy.engindearing.omnitak.mobile.data.DrawingKind

/**
 * #152 — tactical range rings: concentric geodesic circles around a center,
 * built on top of [RangeBearing.ringPoints]. Kept out of the UI layer so the
 * (parity-relevant) default radii + label format are unit-testable without an
 * Android/MapLibre runtime — CI can't exercise the map tool itself.
 *
 * Rings are emitted as transient [Drawing]s of kind [DrawingKind.LINE] (an
 * un-filled closed polyline), so they reuse the native drawing renderer that
 * paints on the GL-buggy Adreno/Mali drivers. They never touch the drawing
 * store; the map screen merges them into the render list while the tool is open.
 */
object RangeRings {

    /** Default radii in meters — matches iOS `RangeRingConfiguration.defaultConfiguration()`. */
    val DEFAULT_DISTANCES_M: List<Double> = listOf(100.0, 500.0, 1000.0, 2000.0, 5000.0)

    /** Amber, distinct from the green drawing default. */
    const val RING_COLOR_HEX: String = "#FFC107"

    private const val RING_SEGMENTS = 72

    /** "100m" / "1.0km" — matches iOS `formatRangeRingLabel`. */
    fun label(meters: Double): String =
        if (meters < 1000) "${meters.toInt()}m" else String.format("%.1fkm", meters / 1000.0)

    /**
     * One closed-loop [DrawingKind.LINE] drawing per radius in [distancesMeters],
     * each a great-circle ring around ([centerLat], [centerLon]). Stable ids so
     * recompositions don't thrash the renderer's annotation tracking.
     */
    fun ringDrawings(
        centerLat: Double,
        centerLon: Double,
        distancesMeters: List<Double> = DEFAULT_DISTANCES_M,
    ): List<Drawing> = distancesMeters.mapIndexed { i, radius ->
        Drawing(
            id = "__range_ring_${i}__",
            kind = DrawingKind.LINE,
            name = label(radius),
            points = RangeBearing.ringPoints(centerLat, centerLon, radius, RING_SEGMENTS)
                .map { it[0] to it[1] },
            colorHex = RING_COLOR_HEX,
            widthPx = 2f,
        )
    }
}
