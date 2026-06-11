package soy.engindearing.omnitak.mobile.ui.components

import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow

/**
 * Web-mercator zoom ↔ Cesium camera height conversion (issue #78).
 *
 * The Cesium globe and the MapLibre 2D engine describe "how far out the
 * operator is looking" differently — MapLibre as a web-mercator zoom
 * level, Cesium as camera altitude in meters. The scene bridge already
 * converts 3D → 2D: `_zoomFromHeight` in `cesium_scene.html` maps the
 * camera height to a zoom on every `camerachanged` event, which is how
 * the 3D → 2D engine switch inherits the viewport (via MapCameraStore).
 *
 * [heightForZoom] is the exact algebraic inverse of that JS function, so
 * a camera seeded for the 2D → 3D handoff reports back the same zoom it
 * was seeded from — repeated engine toggles must not zoom-creep. Keep
 * the two implementations in lockstep.
 *
 * The JS forward mapping (height → zoom):
 *
 *     mpp  = max(h * 256 / 1000, 1)            // height → meters-per-pixel
 *     zoom = clamp(log2(40075017 * max(cos(lat), 0.01) / mpp), 0, 22)
 *
 * Inverting for height gives the standard web-mercator shape
 * `height = C * cos(lat) / 2^zoom` with C = 40_075_017 * 1000 / 256
 * ≈ 156_543_035 m (equatorial circumference × the bridge's 1000/256
 * height-per-meters-per-pixel scale). Sanity: zoom 15 at the equator
 * ≈ 4.8 km altitude.
 */
object CesiumCameraMath {

    /** Equatorial circumference used by the JS `_zoomFromHeight`. */
    private const val EARTH_CIRCUMFERENCE_M = 40_075_017.0

    /** The JS bridge maps height → meters-per-pixel as `mpp = h * 256 / 1000`. */
    private const val HEIGHT_PER_MPP = 1000.0 / 256.0

    /** Zoom clamp range — mirrors the JS `Math.max(0, Math.min(22, z))`. */
    private const val MIN_ZOOM = 0.0
    private const val MAX_ZOOM = 22.0

    /** `cos(lat)` floor — mirrors the JS `Math.max(Math.cos(latRad), 0.01)`. */
    private const val MIN_COS_LAT = 0.01

    /**
     * Bootstrap world-flight altitude from `cesium_scene.html` — also the
     * ceiling for seeded cameras, so a whole-world 2D zoom (< ~3) never
     * places the camera farther out than the legacy intro view.
     */
    const val WORLD_VIEW_HEIGHT_M = 20_000_000.0

    /** Floor so degenerate inputs can never seed a subterranean camera. */
    private const val MIN_HEIGHT_M = 10.0

    /**
     * Cesium camera altitude (meters above the ellipsoid) that frames the
     * same ground extent as web-mercator [zoom] at latitude [latDeg].
     */
    fun heightForZoom(zoom: Double, latDeg: Double): Double {
        val z = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val c = max(cos(Math.toRadians(latDeg)), MIN_COS_LAT)
        val mpp = EARTH_CIRCUMFERENCE_M * c / 2.0.pow(z)
        return (mpp * HEIGHT_PER_MPP).coerceIn(MIN_HEIGHT_M, WORLD_VIEW_HEIGHT_M)
    }

    /**
     * Web-mercator zoom for a Cesium camera at [heightMeters] above
     * latitude [latDeg] — the exact Kotlin mirror of the JS
     * `_zoomFromHeight`, kept here so the inverse pair can be unit
     * tested on the JVM.
     */
    fun zoomForHeight(heightMeters: Double, latDeg: Double): Double {
        val c = max(cos(Math.toRadians(latDeg)), MIN_COS_LAT)
        val mpp = max(heightMeters / HEIGHT_PER_MPP, 1.0)
        return log2(EARTH_CIRCUMFERENCE_M * c / mpp).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }
}

/**
 * Viewport captured from the 2D engine at engine-switch time, used to
 * seed the Cesium camera on bridge-ready (issue #78). [bearing] is
 * degrees clockwise from north — MapLibre's bearing and Cesium's
 * heading share that convention.
 */
data class CesiumCameraSeed(
    val lat: Double,
    val lon: Double,
    val zoom: Double,
    val bearing: Double,
) {
    /** All fields must be finite — these are interpolated into JS source. */
    val isValid: Boolean
        get() = lat.isFinite() && lon.isFinite() && zoom.isFinite() && bearing.isFinite()
}
