package soy.engindearing.omnitak.mobile.domain.tracking

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import soy.engindearing.omnitak.mobile.data.GeoMath
import soy.engindearing.omnitak.mobile.data.LocationProvider
import soy.engindearing.omnitak.mobile.data.SelfFix
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sampling configuration for [TrackRecorder]. Defaults match the iOS
 * TrackRecordingConfiguration so cross-platform tracks look the same
 * shape when imported into CalTopo. Tuned for K9 SAR: a handler
 * walking 3 km/h emits ~1 point every 6 s with the 5 m threshold,
 * which is enough resolution to draw a clean leg without bloating the
 * file for a 4-hour search.
 */
data class TrackRecorderConfig(
    val minimumTimeIntervalMs: Long = 1_000L,
    val minimumDistanceM: Double = 5.0,
    /** Stationary force-record — keeps the timeline alive when an
     *  operator parks at an IC. */
    val maximumTimeIntervalMs: Long = 60_000L,
)

/**
 * Records a breadcrumb track by sampling [LocationProvider.fix].
 * Sampling decisions live in the pure [shouldRecord] helper so tests
 * don't need an Android Looper.
 */
class TrackRecorder(
    private val store: TrackStore,
    private val locationProvider: LocationProvider,
    private val config: TrackRecorderConfig = TrackRecorderConfig(),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private var collectJob: Job? = null

    /** Start recording a new track. [name] defaults to a timestamped
     *  label so quick-press from the FAB still produces a recognisable
     *  entry in the Tracks list. */
    fun start(name: String? = null) {
        if (_isRecording.value) return
        val resolvedName = name?.takeIf { it.isNotBlank() } ?: defaultName()
        _currentTrack.value = Track(
            name = resolvedName,
            startedMs = nowMs(),
        )
        _isRecording.value = true
        _isPaused.value = false
        collectJob?.cancel()
        // Require GPS — start() is a no-op if location permission is
        // missing. LocationProvider already guards that internally;
        // start() returns false when it can't subscribe.
        locationProvider.start()
        collectJob = scope.launch {
            locationProvider.fix.filterNotNull().collect { fix -> ingest(fix) }
        }
    }

    /** Pause point ingestion. The current track stays around; the
     *  location collector keeps running so resume picks up instantly.
     *  iOS pauses location updates too, but on Android we get useful
     *  behaviour (the map continues to render the operator's pin) by
     *  leaving the collector running. */
    fun pause() {
        if (!_isRecording.value) return
        _isPaused.value = true
    }

    fun resume() {
        if (!_isRecording.value) return
        _isPaused.value = false
    }

    /** Stop the track, persist it via [TrackStore], and return the
     *  finalised value. Returns null if not recording. */
    fun stop(): Track? {
        if (!_isRecording.value) return null
        val finalised = _currentTrack.value?.copy(endedMs = nowMs()) ?: return null
        store.save(finalised)
        _currentTrack.value = null
        _isRecording.value = false
        _isPaused.value = false
        collectJob?.cancel()
        collectJob = null
        return finalised
    }

    /** Throw the in-flight recording away without saving. */
    fun discard() {
        if (!_isRecording.value) return
        _currentTrack.value = null
        _isRecording.value = false
        _isPaused.value = false
        collectJob?.cancel()
        collectJob = null
    }

    private fun ingest(fix: SelfFix) {
        if (_isPaused.value) return
        val current = _currentTrack.value ?: return
        val candidate = TrackPoint(
            lat = fix.lat,
            lon = fix.lon,
            altitudeM = fix.altitudeM,
            timestampMs = fix.timeMs,
            accuracyM = if (fix.accuracyM.isNaN()) null else fix.accuracyM,
            speedKmh = fix.speedKmh,
        )
        val last = current.points.lastOrNull()
        if (!shouldRecord(last, candidate, config)) return
        val addedDist = if (last == null) 0.0 else GeoMath.haversineMeters(
            last.lat, last.lon, candidate.lat, candidate.lon,
        )
        _currentTrack.value = current.copy(
            points = current.points + candidate,
            totalDistanceM = current.totalDistanceM + addedDist,
        )
    }

    private fun defaultName(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US)
        return "Track ${fmt.format(Date(nowMs()))}"
    }

    companion object {
        /** Pure sampling rule. Public so unit tests can hit it
         *  directly without standing up an Android Looper. */
        fun shouldRecord(
            previous: TrackPoint?,
            candidate: TrackPoint,
            config: TrackRecorderConfig = TrackRecorderConfig(),
        ): Boolean {
            if (previous == null) return true
            val dtMs = candidate.timestampMs - previous.timestampMs
            // Force-record on stationary so the timeline keeps moving.
            if (dtMs >= config.maximumTimeIntervalMs) return true
            // Otherwise rate-limit: too fast a sample = skip.
            if (dtMs < config.minimumTimeIntervalMs) return false
            val distM = GeoMath.haversineMeters(
                previous.lat, previous.lon, candidate.lat, candidate.lon,
            )
            return distM >= config.minimumDistanceM
        }
    }
}
