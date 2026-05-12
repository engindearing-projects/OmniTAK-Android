package soy.engindearing.omnitak.mobile.domain.tracking

import kotlinx.serialization.Serializable

/**
 * One sample on a recorded breadcrumb track. Mirrors the iOS
 * `TrackPoint` struct field-for-field so KML/GPX exports look
 * identical regardless of platform — a SAR team mid-search can hand
 * off a track from an Android phone and an iOS phone and the IC sees
 * the same shape in CalTopo.
 */
@Serializable
data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val altitudeM: Double = 0.0,
    val timestampMs: Long,
    val accuracyM: Float? = null,
    val speedKmh: Double = 0.0,
)
