package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The 1 Hz stream behind Follow-Me and Pursue (#249). Each tick reads the
 * target, looks up the terrain under it, and asks [FollowTargetRules] what to
 * do: send it, hold (send nothing), or give up.
 *
 * It owns no MAVLink and no coroutine scope. Everything it touches is behind
 * [Host], so the tests drive it with a fake host under virtual time and
 * [UASManager] supplies the real one.
 */
internal class FollowStreamLoop(
    private val host: Host,
    private val limits: FollowLimits = FollowLimits(),
    private val tickMs: Long = TICK_MS,
) {

    interface Host {
        /** The stream's clock, in milliseconds: [readTarget]'s times are on the
         *  same one, and so are the hold times the loop publishes. The app uses
         *  the device's monotonic clock. */
        fun nowMs(): Long

        /** The target's current position and when it was observed (on the
         *  [nowMs] clock; null = it has no age), or null when there is none. */
        fun readTarget(): FollowTargetPoint?

        /** Terrain elevation (MSL) at the point, or null when unavailable. */
        suspend fun terrainMslAt(latDeg: Double, lonDeg: Double): Double?

        /** The operator's GPS altitude, or null when the live fix has none. */
        fun operatorAltitudeM(): Double?

        /** Height above the altitude reference to fly at. */
        fun cruiseMeters(): Double

        suspend fun send(target: FollowDecision.Send)

        fun publish(health: FollowHealth)

        /** The hold ran out: LOITER the vehicle, clear the follow state and
         *  record [reason] as the stop. Called once, as the loop's last act. */
        suspend fun stop(reason: FollowHoldReason, atMs: Long)
    }

    /** Runs until cancelled or until [Host.stop] has been called. */
    suspend fun run() {
        var holdSinceMs: Long? = null
        while (currentCoroutineContext().isActive) {
            val target = host.readTarget()
            // Looked up before the clock is read: the lookup can take a while
            // and the age check must see the time the decision is made.
            val terrainMsl = target?.let { host.terrainMslAt(it.lat, it.lon) }
            val nowMs = host.nowMs()
            when (
                val decision = FollowTargetRules.decide(
                    nowMs = nowMs,
                    target = target,
                    terrainMsl = terrainMsl,
                    operatorAltitudeM = host.operatorAltitudeM(),
                    cruiseMeters = host.cruiseMeters(),
                    holdSinceMs = holdSinceMs,
                    limits = limits,
                )
            ) {
                is FollowDecision.Send -> {
                    holdSinceMs = null
                    host.send(decision)
                    host.publish(FollowHealth.Streaming)
                }
                is FollowDecision.Hold -> {
                    holdSinceMs = decision.sinceMs
                    host.publish(FollowHealth.Holding(decision.reason, decision.sinceMs))
                }
                is FollowDecision.Stop -> {
                    host.stop(decision.reason, nowMs)
                    return
                }
            }
            delay(tickMs)
        }
    }

    companion object {
        const val TICK_MS: Long = 1_000L
    }
}
