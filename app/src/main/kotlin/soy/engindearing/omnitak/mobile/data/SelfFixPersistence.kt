package soy.engindearing.omnitak.mobile.data

/**
 * Issue #75 — self-marker persistence policy. Pure Kotlin (no Android
 * imports) so the rules are unit-testable on the JVM.
 *
 * The bug: the self-marker vanished after screen-off and took until the
 * next GPS fix to reappear. iOS is immune because its SDK-managed puck
 * holds through backgrounding; Android mirrors that *intent* without any
 * background-location permission by:
 *
 *  1. persisting the last real fix (lat/lon/hae/time) to DataStore
 *     (throttled via [shouldPersist]),
 *  2. seeding [LocationProvider] from the persisted fix on cold start
 *     ([restoredFixOrNull]) so every consumer — 2D puck, Cesium self
 *     entity, HUD card, PPLI prefs-fallback — renders immediately,
 *  3. marking the marker visually stale when the fix is older than
 *     [STALE_AFTER_MS] ([isStale]), and
 *  4. never letting an old fix clobber a newer one ([newerOf]).
 */
object SelfFixPersistence {

    /** A fix older than this renders visually stale (dimmed puck). Matches
     *  MapLibre LocationComponent's default stale timeout so live-GPS-loss
     *  staleness and restored-fix staleness look identical. */
    const val STALE_AFTER_MS: Long = 30_000L

    /** Minimum spacing between DataStore writes of the self fix. GPS ticks
     *  every ~10 s; persisting each one would triple disk writes for no
     *  recovery benefit (PPLI interval is 30 s anyway). */
    const val MIN_PERSIST_INTERVAL_MS: Long = 15_000L

    /** True when a fix taken at [fixTimeMs] should render stale at [nowMs].
     *  Unknown (non-positive) fix times are always stale. */
    fun isStale(
        fixTimeMs: Long,
        nowMs: Long,
        staleAfterMs: Long = STALE_AFTER_MS,
    ): Boolean {
        if (fixTimeMs <= 0L) return true
        return nowMs - fixTimeMs > staleAfterMs
    }

    /** Throttle gate for persisting fixes: the candidate must be strictly
     *  newer than the last persisted fix AND at least [minIntervalMs]
     *  newer (first write always passes — lastPersistedFixTimeMs = 0). */
    fun shouldPersist(
        fixTimeMs: Long,
        lastPersistedFixTimeMs: Long,
        minIntervalMs: Long = MIN_PERSIST_INTERVAL_MS,
    ): Boolean {
        if (fixTimeMs <= lastPersistedFixTimeMs) return false
        if (lastPersistedFixTimeMs <= 0L) return true
        return fixTimeMs - lastPersistedFixTimeMs >= minIntervalMs
    }

    /**
     * Rebuild a [SelfFix] from persisted prefs, or null when no fix was
     * ever persisted (NaN sentinels — GAP-030b). The restored fix keeps
     * its original wall-clock time so consumers can derive staleness, but
     * deliberately carries NO speed and NaN accuracy: the PPLI broadcaster
     * maps NaN accuracy to ce=9999999 ("unknown"), so a restored position
     * is never broadcast pretending to have live GPS confidence.
     */
    fun restoredFixOrNull(prefs: UserPrefs): SelfFix? {
        if (prefs.selfLat.isNaN() || prefs.selfLon.isNaN()) return null
        return SelfFix(
            lat = prefs.selfLat,
            lon = prefs.selfLon,
            altitudeM = if (prefs.selfHae.isNaN()) 0.0 else prefs.selfHae,
            speedKmh = 0.0,
            accuracyM = Float.NaN,
            timeMs = prefs.selfFixTimeMs,
        )
    }

    /** Newer-wins merge: a candidate fix only replaces the current one if
     *  it is at least as recent. Protects the live GPS fix from being
     *  clobbered by a late-arriving persisted seed (and vice versa lets a
     *  fused cached fix upgrade a stale seed). */
    fun newerOf(current: SelfFix?, candidate: SelfFix): SelfFix {
        if (current == null) return candidate
        return if (candidate.timeMs >= current.timeMs) candidate else current
    }
}
