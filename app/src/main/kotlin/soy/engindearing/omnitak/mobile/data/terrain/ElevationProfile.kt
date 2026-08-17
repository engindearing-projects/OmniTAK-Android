package soy.engindearing.omnitak.mobile.data.terrain

import kotlin.math.abs

/**
 * Elevation-profile + Line-of-Sight analysis (#153, ported from iOS
 * `ElevationProfileService`).
 *
 * Pure math over a list of [ProfilePoint] (distance from start + bare-earth
 * elevation), so it unit-tests directly. The Android layer walks the drawn line
 * at a fixed interval, samples each point's elevation with
 * [soy.engindearing.omnitak.mobile.data.uas.TerrainSampler], and feeds the
 * resulting profile in.
 */

/** One sample along the path: [distanceM] from the start, bare-earth [elevationM]. */
data class ProfilePoint(val distanceM: Double, val elevationM: Double)

/** Steepness bucket for a segment grade (matches the iOS categories/thresholds). */
enum class Steepness {
    FLAT, GENTLE, MODERATE, STEEP, VERY_STEEP, EXTREME;

    companion object {
        fun from(gradePct: Double): Steepness {
            val g = abs(gradePct)
            return when {
                g < 3.0 -> FLAT
                g < 8.0 -> GENTLE
                g < 15.0 -> MODERATE
                g < 25.0 -> STEEP
                g < 40.0 -> VERY_STEEP
                else -> EXTREME
            }
        }
    }
}

/** A constant-grade run between two consecutive samples. */
data class GradientSegment(
    val startM: Double,
    val endM: Double,
    val gradePct: Double,
    val steepness: Steepness,
)

/** Whole-profile summary. */
data class ProfileStats(
    val minElevationM: Double,
    val maxElevationM: Double,
    val totalClimbM: Double,
    val totalDescentM: Double,
    val totalDistanceM: Double,
    val maxGradePct: Double,
    val avgGradePct: Double,
)

/**
 * Result of a line-of-sight check. [minClearanceM] is the smallest vertical gap
 * between the sight line and the terrain across all intermediate points
 * (negative ⇒ the terrain pokes through, i.e. blocked).
 */
data class LineOfSightResult(
    val clear: Boolean,
    val firstObstructionIndex: Int?,
    val firstObstructionDistanceM: Double?,
    val minClearanceM: Double,
)

object ElevationProfile {

    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Min/max/climb/descent + max & average (absolute) grade. */
    fun stats(points: List<ProfilePoint>): ProfileStats {
        require(points.isNotEmpty()) { "elevation profile is empty" }
        var climb = 0.0
        var descent = 0.0
        var maxGrade = 0.0
        var gradeSum = 0.0
        var segments = 0
        for (i in 1 until points.size) {
            val dz = points[i].elevationM - points[i - 1].elevationM
            if (dz >= 0) climb += dz else descent += -dz
            val dx = points[i].distanceM - points[i - 1].distanceM
            if (dx > 0.0) {
                val grade = abs(dz / dx * 100.0)
                if (grade > maxGrade) maxGrade = grade
                gradeSum += grade
                segments++
            }
        }
        return ProfileStats(
            minElevationM = points.minOf { it.elevationM },
            maxElevationM = points.maxOf { it.elevationM },
            totalClimbM = climb,
            totalDescentM = descent,
            totalDistanceM = points.last().distanceM - points.first().distanceM,
            maxGradePct = maxGrade,
            avgGradePct = if (segments > 0) gradeSum / segments else 0.0,
        )
    }

    /** Per-segment grade + steepness category. */
    fun gradients(points: List<ProfilePoint>): List<GradientSegment> =
        (1 until points.size).mapNotNull { i ->
            val dx = points[i].distanceM - points[i - 1].distanceM
            if (dx <= 0.0) return@mapNotNull null
            val grade = (points[i].elevationM - points[i - 1].elevationM) / dx * 100.0
            GradientSegment(points[i - 1].distanceM, points[i].distanceM, grade, Steepness.from(grade))
        }

    /**
     * Is the target visible from the observer along this terrain profile?
     *
     * Sight line runs from `start.elev + observerHeight` to
     * `end.elev + targetHeight`. Each intermediate sample blocks if the terrain
     * rises above that line. When [earthCurvature] is on, the Earth's bulge
     * between the two points (with atmospheric [refractionK] ≈ 0.13 standard)
     * is subtracted from the available clearance — which is what drops a distant
     * sea-level target below the horizon.
     */
    fun lineOfSight(
        points: List<ProfilePoint>,
        observerHeightM: Double,
        targetHeightM: Double,
        earthCurvature: Boolean = true,
        refractionK: Double = 0.13,
    ): LineOfSightResult {
        require(points.size >= 2) { "line of sight needs at least 2 points" }
        val start = points.first().elevationM + observerHeightM
        val end = points.last().elevationM + targetHeightM
        val total = points.last().distanceM - points.first().distanceM
        val effR = EARTH_RADIUS_M / (1.0 - refractionK)

        var minClearance = Double.POSITIVE_INFINITY
        var firstIdx: Int? = null
        var firstDist: Double? = null
        for (i in 1 until points.size - 1) {
            val d1 = points[i].distanceM - points.first().distanceM
            val sight = if (total > 0.0) start + (end - start) * (d1 / total) else start
            val bulge = if (earthCurvature) d1 * (total - d1) / (2.0 * effR) else 0.0
            val clearance = (sight - bulge) - points[i].elevationM
            if (clearance < minClearance) minClearance = clearance
            if (clearance < 0.0 && firstIdx == null) {
                firstIdx = i
                firstDist = points[i].distanceM
            }
        }
        if (minClearance == Double.POSITIVE_INFINITY) minClearance = Double.MAX_VALUE // <3 points
        return LineOfSightResult(
            clear = firstIdx == null,
            firstObstructionIndex = firstIdx,
            firstObstructionDistanceM = firstDist,
            minClearanceM = minClearance,
        )
    }
}
