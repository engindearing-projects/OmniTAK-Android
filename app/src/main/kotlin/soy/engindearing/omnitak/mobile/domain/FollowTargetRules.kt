package soy.engindearing.omnitak.mobile.domain

/**
 * Why the follow stream is not sending (#249).
 */
enum class FollowHoldReason {
    /** The target has no position, or its position is older than
     *  [FollowTargetRules.MAX_TARGET_AGE_MS]. */
    StaleFix,

    /** Nothing to measure the cruise height from: no terrain sample at the
     *  target and no GPS altitude on the operator's fix. */
    NoAltitudeReference,
}

/**
 * What the follow stream is doing, for the HUD (#249). Published by
 * [UASManager.followHealth].
 */
sealed interface FollowHealth {
    /** Not following. */
    data object Idle : FollowHealth

    /** Following, and a target went out on the last tick. */
    data object Streaming : FollowHealth

    /** Following, but nothing is being sent because of [reason], since
     *  [sinceMs] (wall clock). The vehicle keeps whatever it was last told. */
    data class Holding(val reason: FollowHoldReason, val sinceMs: Long) : FollowHealth

    /** The stream gave up on its own at [atMs] after holding for
     *  [FollowTargetRules.MAX_HOLD_MS] and the vehicle was told to LOITER.
     *  Stays until the next engage or [UASManager.acknowledgeFollowStop]. */
    data class Stopped(val reason: FollowHoldReason, val atMs: Long) : FollowHealth
}

/**
 * A position for the vehicle to follow, with the time that position was
 * observed.
 *
 * [timeMs] is wall-clock epoch millis: the GPS fix time for the operator
 * ([soy.engindearing.omnitak.mobile.data.SelfFix.timeMs]), the receive time
 * for a pursued contact ([soy.engindearing.omnitak.mobile.data.CoTEvent.receivedAtMs]).
 * null means the source cannot say, and the age check is skipped for it. That
 * is the only way a point is ever treated as fresh without a time; nothing
 * fills a time in.
 */
data class FollowTargetPoint(
    val lat: Double,
    val lon: Double,
    val timeMs: Long?,
)

/** What one tick of the follow stream should do. */
sealed interface FollowDecision {
    /** Send the target at [altMsl], which is [cruiseMeters] above the altitude
     *  reference. */
    data class Send(
        val latDeg: Double,
        val lonDeg: Double,
        val altMsl: Double,
        val cruiseMeters: Double,
    ) : FollowDecision

    /** Send nothing this tick. [sinceMs] is when the hold began; pass it back
     *  as `holdSinceMs` on the next tick. */
    data class Hold(val reason: FollowHoldReason, val sinceMs: Long) : FollowDecision

    /** The hold has lasted long enough: stop following and LOITER. */
    data class Stop(val reason: FollowHoldReason) : FollowDecision
}

/**
 * The two limits behind [FollowTargetRules]. The app always runs on the
 * defaults; a test that has to watch a real stream give up passes shorter ones.
 */
data class FollowLimits(
    /** A target position observed longer ago than this is stale. */
    val maxTargetAgeMs: Long = FollowTargetRules.MAX_TARGET_AGE_MS,
    /** A hold that has lasted this long (or longer) becomes a stop. */
    val maxHoldMs: Long = FollowTargetRules.MAX_HOLD_MS,
)

/**
 * #249 - the rules for what the follow stream sends, kept pure so every branch
 * is a unit test.
 *
 * Two things used to go wrong. With no terrain sample and no operator fix, the
 * stream sent "cruise metres above sea level", which is underground anywhere
 * higher than the sea. And when GPS stopped updating, the last position kept
 * going out once a second forever, as if it were current.
 *
 *  - A target with no position, or older than [MAX_TARGET_AGE_MS], is not
 *    followed: [FollowDecision.Hold] with [FollowHoldReason.StaleFix].
 *  - With no altitude reference, [FollowDecision.Hold] with
 *    [FollowHoldReason.NoAltitudeReference]. Zero is never a stand-in for a
 *    missing altitude.
 *  - A hold that has lasted [MAX_HOLD_MS] becomes [FollowDecision.Stop]; the
 *    caller parks the vehicle in LOITER and tells the operator why.
 *  - A fresh target with a reference ends the hold and sends.
 */
object FollowTargetRules {

    /** A target position older than this is stale. Strictly older: a point
     *  exactly this old still goes out. */
    const val MAX_TARGET_AGE_MS: Long = 10_000L

    /** A hold that has lasted this long (or longer) becomes a stop. */
    const val MAX_HOLD_MS: Long = 30_000L

    /**
     * The altitude (MSL, metres) the cruise height is measured from: the
     * terrain sample at the target when there is one, else the operator's own
     * GPS altitude, else null. A real 0.0 counts (a coast is at sea level);
     * null and non-finite values do not.
     */
    fun referenceMsl(terrainMsl: Double?, operatorAltitudeM: Double?): Double? =
        terrainMsl?.takeIf { it.isFinite() } ?: operatorAltitudeM?.takeIf { it.isFinite() }

    /**
     * True when [target] is missing or was observed more than [maxAgeMs]
     * before [nowMs]. A point with no time is never stale (see
     * [FollowTargetPoint.timeMs]); a non-positive time is a real, very old time.
     */
    fun isStale(
        nowMs: Long,
        target: FollowTargetPoint?,
        maxAgeMs: Long = MAX_TARGET_AGE_MS,
    ): Boolean {
        if (target == null) return true
        val observedAt = target.timeMs ?: return false
        return nowMs - observedAt > maxAgeMs
    }

    /**
     * One tick of the follow stream.
     *
     * @param nowMs wall clock, taken after any terrain lookup so the age is the
     *   age at the moment of the decision
     * @param target where to follow, or null when the source has no position
     * @param terrainMsl terrain elevation at [target], or null when the sample
     *   failed
     * @param operatorAltitudeM the operator's GPS altitude, or null when the
     *   live fix has none
     * @param cruiseMeters height above the reference to fly at
     * @param holdSinceMs when the current hold began (the `sinceMs` of the last
     *   [FollowDecision.Hold]), or null when the last tick sent
     * @param limits the age and hold limits; the defaults outside tests
     */
    fun decide(
        nowMs: Long,
        target: FollowTargetPoint?,
        terrainMsl: Double?,
        operatorAltitudeM: Double?,
        cruiseMeters: Double,
        holdSinceMs: Long?,
        limits: FollowLimits = FollowLimits(),
    ): FollowDecision {
        if (target == null || isStale(nowMs, target, limits.maxTargetAgeMs)) {
            return holdOrStop(FollowHoldReason.StaleFix, nowMs, holdSinceMs, limits.maxHoldMs)
        }
        val reference = referenceMsl(terrainMsl, operatorAltitudeM)
            ?: return holdOrStop(FollowHoldReason.NoAltitudeReference, nowMs, holdSinceMs, limits.maxHoldMs)
        return FollowDecision.Send(
            latDeg = target.lat,
            lonDeg = target.lon,
            altMsl = reference + cruiseMeters,
            cruiseMeters = cruiseMeters,
        )
    }

    /** The hold clock is one clock: it keeps running if the reason changes
     *  from one to the other, and a start time ahead of [nowMs] (the clock was
     *  set back) counts as starting now. */
    private fun holdOrStop(
        reason: FollowHoldReason,
        nowMs: Long,
        holdSinceMs: Long?,
        maxHoldMs: Long,
    ): FollowDecision {
        val since = minOf(holdSinceMs ?: nowMs, nowMs)
        return if (nowMs - since >= maxHoldMs) {
            FollowDecision.Stop(reason)
        } else {
            FollowDecision.Hold(reason, since)
        }
    }
}
