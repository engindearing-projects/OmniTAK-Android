package soy.engindearing.omnitak.mobile.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Device compass heading in degrees clockwise from true/magnetic north
 * (0..360). Backed by the rotation-vector sensor — the same source
 * MapLibre's `RenderMode.COMPASS` uses to spin the 2D self-puck, so the
 * Cesium globe's triangle self-marker (#83) rotates in lockstep with the
 * 2D engine instead of being stuck pointing north.
 *
 * Emits null until the first sensor sample (or on a device with no
 * rotation-vector sensor), which callers treat as "heading unknown →
 * draw the triangle pointing north." Lifecycle is caller-owned: [start]
 * when the globe is on screen, [stop] when it leaves so we don't keep the
 * magnetometer powered for nothing.
 */
class DeviceHeadingProvider(private val context: Context) {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotationSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val _headingDeg = MutableStateFlow<Float?>(null)
    val headingDeg: StateFlow<Float?> = _headingDeg.asStateFlow()

    private var started = false
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientation)
            // azimuth (radians, -PI..PI) → degrees clockwise from north (0..360),
            // then corrected for the natural display rotation so the heading is
            // referenced to the top of the screen the operator is holding.
            val azimuthDeg = Math.toDegrees(orientation[0].toDouble()).toFloat()
            val corrected = (azimuthDeg + displayRotationDegrees() + 360f) % 360f
            _headingDeg.value = corrected
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* no-op */ }
    }

    /** Start listening. No-op (returns false) when there's no sensor. */
    fun start(): Boolean {
        if (started) return true
        val sm = sensorManager ?: return false
        val sensor = rotationSensor ?: return false
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        started = true
        return true
    }

    fun stop() {
        if (!started) return
        sensorManager?.unregisterListener(listener)
        started = false
    }

    @Suppress("DEPRECATION")
    private fun displayRotationDegrees(): Float {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return 0f
        return when (wm.defaultDisplay?.rotation) {
            Surface.ROTATION_90 -> 90f
            Surface.ROTATION_180 -> 180f
            Surface.ROTATION_270 -> 270f
            else -> 0f
        }
    }
}
