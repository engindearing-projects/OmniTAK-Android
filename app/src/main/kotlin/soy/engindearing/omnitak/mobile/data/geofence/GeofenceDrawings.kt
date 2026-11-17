package soy.engindearing.omnitak.mobile.data.geofence

import soy.engindearing.omnitak.mobile.data.Drawing
import soy.engindearing.omnitak.mobile.data.DrawingKind
import soy.engindearing.omnitak.mobile.data.DrawingLabel
import soy.engindearing.omnitak.mobile.data.rangebearing.RangeBearing

/**
 * What both map engines draw for the geofences: an outline in the geofence's colour over a dark
 * casing (so it reads on a pale street map), a light fill, and its name.
 *
 * They are handed to the engines as transient [Drawing]s, the way the range rings are
 * ([soy.engindearing.omnitak.mobile.data.rangebearing.RangeRings]): the map screen merges them into
 * the list the native drawing renderer paints, which is the path that paints on the GL-buggy
 * Adreno and Mali drivers and survives a style reload. They are never in the drawing store, so
 * they cannot be edited, saved as drawings, picked up by the lasso or opened in the drawing sheet,
 * and their ids start with [ID_PREFIX] so nothing can mistake one for an operator's drawing.
 *
 * Only a geofence that is switched on and has a shape is drawn. The ring of a circle is the
 * geodesic circle ([RangeBearing.ringPoints]), the same sphere the monitor measures on, so the
 * edge that is drawn is the edge that counts.
 */
object GeofenceDrawings {

    /** Every id of a drawing made for a geofence starts with this. */
    const val ID_PREFIX = "__geofence_"

    /** The fill is light enough to read the map through. */
    const val FILL_ALPHA = 0.12f

    /** The coloured outline's width in px, and the casing under it. */
    const val OUTLINE_WIDTH_PX = 3f
    const val CASING_WIDTH_PX = 5f
    const val CASING_COLOR_HEX = "#1A1A1A"

    /** Points on the outline of a circle. */
    const val CIRCLE_SEGMENTS = 64

    /** The id of the drawing for geofence [geofenceId]. */
    fun idFor(geofenceId: String): String = "$ID_PREFIX${geofenceId}__"

    private fun casingIdFor(geofenceId: String): String = "$ID_PREFIX${geofenceId}_casing__"

    /** Whether [drawingId] belongs to a geofence. */
    fun isGeofenceDrawing(drawingId: String): Boolean = drawingId.startsWith(ID_PREFIX)

    /**
     * The drawings for [geofences], in order: for each one that is on and has a shape, the casing
     * first and the shape over it. Switched-off ones, and ones without a usable shape, are left out.
     */
    fun from(geofences: List<Geofence>): List<Drawing> {
        val out = ArrayList<Drawing>(geofences.size * 2)
        for (geofence in geofences) {
            if (!geofence.isActive || !geofence.hasUsableShape) continue
            val label = geofence.labelPoint ?: continue
            val ring = ringOf(geofence) ?: continue
            out += Drawing(
                id = casingIdFor(geofence.id),
                kind = DrawingKind.LINE,
                points = ring + ring.first(),
                colorHex = CASING_COLOR_HEX,
                widthPx = CASING_WIDTH_PX,
            )
            out += Drawing(
                id = idFor(geofence.id),
                kind = DrawingKind.POLYGON,
                name = geofence.name,
                points = ring,
                colorHex = geofence.color.hex,
                widthPx = OUTLINE_WIDTH_PX,
                fillAlpha = FILL_ALPHA,
                label = DrawingLabel(geofence.name, label.lat, label.lon),
            )
        }
        return out
    }

    /**
     * The corners of the outline, open (the first is not repeated at the end): a polygon's own
     * corners, or [CIRCLE_SEGMENTS] points around a circle. Null when there is no shape.
     */
    fun ringOf(geofence: Geofence): List<Pair<Double, Double>>? {
        if (!geofence.hasUsableShape) return null
        return when (geofence.type) {
            GeofenceType.POLYGON -> geofence.points?.map { it.lat to it.lon }
            GeofenceType.CIRCLE -> RangeBearing
                .ringPoints(geofence.centerLat!!, geofence.centerLon!!, geofence.radiusMeters!!, CIRCLE_SEGMENTS)
                .dropLast(1)
                .map { it[0] to it[1] }
        }
    }
}
