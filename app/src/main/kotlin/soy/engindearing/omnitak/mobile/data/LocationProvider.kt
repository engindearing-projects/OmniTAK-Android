package soy.engindearing.omnitak.mobile.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SelfFix(
    val lat: Double,
    val lon: Double,
    val altitudeM: Double,
    val speedKmh: Double,
    val accuracyM: Float,
    val timeMs: Long,
)

class LocationProvider(private val context: Context) {

    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val _fix = MutableStateFlow<SelfFix?>(null)
    val fix: StateFlow<SelfFix?> = _fix.asStateFlow()

    // #82 — manual self-position override. When the operator taps the
    // self-marker and drops it somewhere (MapScreen's reposition sheet),
    // the chosen coordinate lands here as the single source of truth for
    // "where we say we are." Non-null means manual mode is active:
    //  - the map puck + SelfPositionCard render this instead of GPS, and
    //  - SelfPositionBroadcaster broadcasts THIS in PPLI so teammates and
    //    the TAK server see the operator where they placed themselves, not
    //    at their real GPS spot (iOS PositionBroadcastService parity).
    // Clearing it ("resume GPS") reverts every consumer to the live fix.
    private val _manualFix = MutableStateFlow<SelfFix?>(null)
    val manualFix: StateFlow<SelfFix?> = _manualFix.asStateFlow()

    /** #82 — enter manual mode at [fix] (set) or resume live GPS (null). */
    fun setManualFix(fix: SelfFix?) {
        _manualFix.value = fix
    }

    /** #82 — the position every consumer should treat as authoritative:
     *  the manual override when active, else the live GPS fix. */
    fun effectiveFix(): SelfFix? = _manualFix.value ?: _fix.value

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { offerFix(it.toSelfFix()) }
        }
    }

    private var started = false

    @SuppressLint("MissingPermission")
    fun start(intervalMs: Long = 10_000L): Boolean {
        if (started) return true
        if (!hasPermission()) return false
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()
        client.requestLocationUpdates(req, callback, Looper.getMainLooper())
        client.lastLocation.addOnSuccessListener { loc ->
            // Suppress fixes older than 5 minutes — better to wait for a fresh
            // one than show last-week's location as LIVE on cold start. (The
            // persisted-fix seed handles older positions, rendered stale —
            // issue #75.)
            if (loc != null && System.currentTimeMillis() - loc.time < 5 * 60_000L) {
                offerFix(loc.toSelfFix())
            }
        }
        started = true
        return true
    }

    fun stop() {
        if (!started) return
        client.removeLocationUpdates(callback)
        started = false
    }

    /**
     * Issue #75 — seed the in-memory fix from the persisted one so the
     * self-marker renders immediately on cold start instead of vanishing
     * until GPS reacquires. Newer-wins: a live fix that has already
     * arrived is never replaced by the (older) persisted seed. No
     * permission required — this only replays a position we recorded.
     */
    fun seedFromPersisted(persisted: SelfFix) {
        offerFix(persisted)
    }

    /**
     * Issue #75 — force a location refresh the moment the app regains
     * foreground instead of waiting for the next passive interval tick:
     *  - fused cache ([com.google.android.gms.location.FusedLocationProviderClient.getLastLocation])
     *    for an instant (≤5 min old) answer,
     *  - an active single-shot
     *    [com.google.android.gms.location.FusedLocationProviderClient.getCurrentLocation]
     *    for a fresh fix.
     * Both funnel through the newer-wins gate, so out-of-order results
     * can't regress the marker. Safe no-op without permission.
     */
    @SuppressLint("MissingPermission")
    fun requestImmediateFix(): Boolean {
        if (!hasPermission()) return false
        client.lastLocation.addOnSuccessListener { loc ->
            if (loc != null && System.currentTimeMillis() - loc.time < 5 * 60_000L) {
                offerFix(loc.toSelfFix())
            }
        }
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { loc ->
                if (loc != null) offerFix(loc.toSelfFix())
            }
        return true
    }

    /** Single write gate for [_fix]: an incoming fix only lands if it is
     *  at least as recent as the current one (issue #75 newer-wins). */
    private fun offerFix(candidate: SelfFix) {
        _fix.value = SelfFixPersistence.newerOf(_fix.value, candidate)
    }

    private fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun Location.toSelfFix() = SelfFix(
        lat = latitude,
        lon = longitude,
        altitudeM = if (hasAltitude()) altitude else 0.0,
        speedKmh = if (hasSpeed()) speed * 3.6 else 0.0,
        accuracyM = if (hasAccuracy()) accuracy else Float.NaN,
        timeMs = time,
    )
}
