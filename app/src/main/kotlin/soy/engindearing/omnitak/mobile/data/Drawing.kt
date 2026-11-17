package soy.engindearing.omnitak.mobile.data

/**
 * Persistent map drawing. [points] is vertex-ordered; for [DrawingKind.POLYGON]
 * the first and last points are implicitly connected and don't need
 * to be duplicated. For [DrawingKind.CIRCLE] the list is
 * [center, edge] — a single radius point.
 */
data class Drawing(
    val id: String,
    val kind: DrawingKind,
    val name: String = "",
    val points: List<Pair<Double, Double>>,  // (lat, lon) pairs
    val colorHex: String = "#4ADE80",
    val widthPx: Float = 3f,
    /**
     * How opaque the fill of a polygon or circle is, 0 to 1. The operator's own drawings keep
     * [DEFAULT_FILL_ALPHA]; the geofences the app draws for the operator use a lighter one so the
     * map reads through them.
     */
    val fillAlpha: Float = DEFAULT_FILL_ALPHA,
    /** A name to write on the map at a point, for the shapes the app draws itself (geofences). Null for none. */
    val label: DrawingLabel? = null,
) {
    companion object {
        const val DEFAULT_FILL_ALPHA = 0.3f
    }
}

/** A name written on the map at ([lat], [lon]). */
data class DrawingLabel(val text: String, val lat: Double, val lon: Double)

enum class DrawingKind {
    LINE, POLYGON, CIRCLE;
}
