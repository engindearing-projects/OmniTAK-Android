package soy.engindearing.omnitak.mobile.data.geofence

import soy.engindearing.omnitak.mobile.data.Drawing
import soy.engindearing.omnitak.mobile.data.DrawingKind
import soy.engindearing.omnitak.mobile.data.DistanceUnit
import soy.engindearing.omnitak.mobile.data.MapScale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The small rules of the new-geofence form, pure so they are tested without a screen. The same
 * choices and the same labels as the iOS form.
 */
object GeofenceForm {

    /**
     * The radius a circle at the map centre can have: the slider walks this list, so the steps are
     * small where a small zone needs them and wide where a large one does. 50 m to 5 km.
     */
    val RADIUS_CHOICES: List<Double> = listOf(
        50.0, 75.0, 100.0, 150.0, 200.0, 250.0, 300.0, 400.0, 500.0, 750.0,
        1_000.0, 1_500.0, 2_000.0, 2_500.0, 3_000.0, 4_000.0, 5_000.0,
    )

    /** What the slider starts on. */
    const val DEFAULT_RADIUS_M = 200.0

    /** The dwell times the form offers, in minutes. */
    val DWELL_CHOICES_MINUTES: List<Int> = listOf(1, 5, 10, 30, 60)

    /** The dwell time the form starts on once the dwell alert is switched on. */
    const val DEFAULT_DWELL_MINUTES = 5

    /** The choice nearest to [meters]. */
    fun nearestRadius(meters: Double): Double =
        RADIUS_CHOICES.minByOrNull { abs(it - meters) } ?: DEFAULT_RADIUS_M

    /** The index of the choice nearest to [meters], for the slider. */
    fun radiusIndex(meters: Double): Int = RADIUS_CHOICES.indexOf(nearestRadius(meters))

    /** The choice at slider position [index], kept inside the list. */
    fun radiusAt(index: Int): Double = RADIUS_CHOICES[index.coerceIn(0, RADIUS_CHOICES.lastIndex)]

    /** A radius in the operator's units: "200 m", "1.5 km", "660 ft", "0.62 mi". */
    fun radiusLabel(meters: Double, unit: DistanceUnit): String = when (unit) {
        DistanceUnit.METRIC ->
            if (meters < 1_000.0) "${meters.roundToInt()} m" else "${trimmed(meters / 1_000.0, 1)} km"
        DistanceUnit.IMPERIAL -> {
            // The same threshold as the iOS form: feet below 800 m, miles from there.
            if (meters < 800.0) {
                "${((meters / MapScale.METERS_PER_FOOT) / 10.0).roundToInt() * 10} ft"
            } else {
                "${trimmed(meters / MapScale.METERS_PER_MILE, 2)} mi"
            }
        }
    }

    private fun trimmed(value: Double, decimals: Int): String {
        var text = String.format(java.util.Locale.US, "%.${decimals}f", value)
        while (text.contains('.') && text.endsWith('0')) text = text.dropLast(1)
        if (text.endsWith('.')) text = text.dropLast(1)
        return text
    }

    /**
     * The name offered for a new geofence, "Geofence 1", "Geofence 2", and so on: the lowest number
     * not taken yet, so Save never waits on a name. [template] has one `%d` in it.
     */
    fun defaultName(existing: List<String>, template: String): String {
        val taken = existing.toHashSet()
        var number = 1
        while (String.format(template, number) in taken) number++
        return String.format(template, number)
    }

    /**
     * The name a saved geofence gets: what the operator typed, trimmed, or the offered name when
     * they left it empty. The name never blocks Save.
     */
    fun finalName(typed: String, existing: List<String>, template: String): String =
        typed.trim().ifEmpty { defaultName(existing, template) }

    /** What the form can pick a shape from: one of the operator's own drawings. */
    fun isUsableSource(drawing: Drawing): Boolean = when (drawing.kind) {
        DrawingKind.CIRCLE -> drawing.points.size >= 2 &&
            GeofenceDrawingSource.circleRadiusMeters(drawing.points) > 0.0
        DrawingKind.POLYGON -> drawing.points.size >= 3
        DrawingKind.LINE -> false
    }

    /**
     * The drawings a geofence can be made from, in the order they were drawn: circles and polygons.
     * A line has no inside. Anything the app keeps under a reserved id (range rings, geofences
     * themselves, the drawing being made) is not one of the operator's drawings.
     */
    fun drawingSources(drawings: List<Drawing>): List<Drawing> =
        drawings.filter { !it.id.startsWith("__") && isUsableSource(it) }

    /**
     * A circle geofence at [lat], [lon]. [id] and [nowMs] are passed in so it is tested without
     * a clock or a random number.
     */
    fun circleAt(
        id: String,
        nowMs: Long,
        name: String,
        lat: Double,
        lon: Double,
        radiusMeters: Double,
        settings: Settings,
    ): Geofence = Geofence(
        id = id,
        name = name,
        type = GeofenceType.CIRCLE,
        color = settings.color,
        watchSelf = settings.watchSelf,
        watchContacts = settings.watchContacts,
        alertOnEntry = settings.alertOnEntry,
        alertOnExit = settings.alertOnExit,
        dwellMinutes = settings.dwellMinutes,
        centerLat = lat,
        centerLon = lon,
        radiusMeters = radiusMeters,
        createdAtMs = nowMs,
    )

    /**
     * A geofence with a drawing's shape. The shape is copied, so moving, reshaping or deleting the
     * drawing afterwards leaves the geofence where it is. A circle drawing is stored as a centre and
     * a point on its edge; the radius is the distance between them on the ground. Null for a
     * drawing that has no inside (a line) or no size.
     */
    fun fromDrawing(
        id: String,
        nowMs: Long,
        name: String,
        drawing: Drawing,
        settings: Settings,
    ): Geofence? {
        if (!isUsableSource(drawing)) return null
        val common = circleAt(id, nowMs, name, 0.0, 0.0, 0.0, settings)
        return when (drawing.kind) {
            DrawingKind.CIRCLE -> {
                val (centerLat, centerLon) = drawing.points.first()
                common.copy(
                    centerLat = centerLat,
                    centerLon = centerLon,
                    radiusMeters = GeofenceDrawingSource.circleRadiusMeters(drawing.points),
                )
            }
            DrawingKind.POLYGON -> common.copy(
                type = GeofenceType.POLYGON,
                centerLat = null,
                centerLon = null,
                radiusMeters = null,
                points = drawing.points.map { (lat, lon) -> LatLon(lat, lon) },
            )
            DrawingKind.LINE -> null
        }
    }

    /** What the form's switches and pickers say, apart from where the geofence is. */
    data class Settings(
        val color: GeofenceColor = GeofenceColor.YELLOW,
        val watchSelf: Boolean = true,
        val watchContacts: Boolean = true,
        val alertOnEntry: Boolean = true,
        val alertOnExit: Boolean = true,
        /** 0 is off. */
        val dwellMinutes: Int = 0,
    ) {
        /** A geofence that watches nobody does nothing, so the form does not save one. */
        val watchesSomeone: Boolean get() = watchSelf || watchContacts
    }
}

/** How the geometry of an operator's drawing becomes the shape of a geofence. */
object GeofenceDrawingSource {

    /**
     * The radius of a circle drawing, in metres on the ground: a drawn circle is its centre and a
     * point on its edge, so it is the distance between the two.
     */
    fun circleRadiusMeters(points: List<Pair<Double, Double>>): Double {
        if (points.size < 2) return 0.0
        val (lat0, lon0) = points[0]
        val (lat1, lon1) = points[1]
        return soy.engindearing.omnitak.mobile.data.GeoMath.haversineMeters(lat0, lon0, lat1, lon1)
    }
}
