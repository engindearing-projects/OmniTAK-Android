package soy.engindearing.omnitak.mobile.data.geofence

import soy.engindearing.omnitak.mobile.i18n.Loc

/**
 * Looks up a string by its key. The app's own catalogue by default ([Loc]); a test passes a
 * lookup pinned to one language.
 */
typealias GeofenceLocalize = (key: String) -> String

private val appLookup: GeofenceLocalize = { key -> Loc.t(key) }

/**
 * The words of a geofence alert, the same sentences as the iOS app. Pure: the sentence builders
 * take the translation as an argument, so they are tested without a screen.
 */
object GeofenceAlertText {

    /**
     * "ALPHA-2 entered North gate", "You left North gate", "ALPHA-2 has been in North gate for 5 min".
     * [byOperator] is the phone's own position; the sentence then says "You" and ignores [subjectName].
     */
    fun sentence(
        type: GeofenceEventType,
        geofenceName: String,
        subjectName: String,
        byOperator: Boolean,
        stayMs: Long?,
        localize: GeofenceLocalize = appLookup,
    ): String = when (type) {
        GeofenceEventType.ENTRY ->
            if (byOperator) {
                String.format(localize("geofence.alert.you.entered"), geofenceName)
            } else {
                String.format(localize("geofence.alert.other.entered"), subjectName, geofenceName)
            }
        GeofenceEventType.EXIT ->
            if (byOperator) {
                String.format(localize("geofence.alert.you.left"), geofenceName)
            } else {
                String.format(localize("geofence.alert.other.left"), subjectName, geofenceName)
            }
        GeofenceEventType.DWELL -> {
            val length = duration(stayMs ?: 0L, localize)
            if (byOperator) {
                String.format(localize("geofence.alert.you.dwell"), geofenceName, length)
            } else {
                String.format(localize("geofence.alert.other.dwell"), subjectName, geofenceName, length)
            }
        }
    }

    fun sentence(transition: GeofenceTransition, localize: GeofenceLocalize = appLookup): String = sentence(
        type = transition.type,
        geofenceName = transition.geofenceName,
        subjectName = transition.subjectName,
        byOperator = transition.isOperator,
        stayMs = transition.stayMs,
        localize = localize,
    )

    /**
     * The same sentence for an event kept in the history. An event that did not say who it was reads
     * as a teammate called "Someone".
     */
    fun sentence(event: GeofenceEvent, localize: GeofenceLocalize = appLookup): String = sentence(
        type = event.type,
        geofenceName = event.geofenceName,
        subjectName = event.subjectName ?: localize("geofence.alert.someone"),
        byOperator = event.byOperator,
        stayMs = event.stayMs,
        localize = localize,
    )

    /**
     * The line on a geofence's card: "You are inside", "2 teammates inside", "Nobody inside", or that
     * it is switched off.
     */
    fun stateLine(isActive: Boolean, occupancy: GeofenceOccupancy, localize: GeofenceLocalize = appLookup): String {
        if (!isActive) return localize("geofence.state.off")
        val mates = occupancy.teammates
        return when {
            !occupancy.operatorInside && mates == 0 -> localize("geofence.state.nobody")
            occupancy.operatorInside && mates == 0 -> localize("geofence.state.you")
            !occupancy.operatorInside && mates == 1 -> localize("geofence.state.teammate.one")
            !occupancy.operatorInside -> String.format(localize("geofence.state.teammates"), mates)
            mates == 1 -> localize("geofence.state.you.teammate.one")
            else -> String.format(localize("geofence.state.you.teammates"), mates)
        }
    }

    /** "45 s", "5 min", "2 h", "1 h 5 min". Whole minutes, rounded down. */
    fun duration(ms: Long, localize: GeofenceLocalize = appLookup): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        if (total < 60L) return String.format(localize("geofence.duration.seconds"), total)
        val minutes = total / 60L
        if (minutes < 60L) return String.format(localize("geofence.duration.minutes"), minutes)
        val hours = minutes / 60L
        val rest = minutes % 60L
        return if (rest == 0L) {
            String.format(localize("geofence.duration.hours"), hours)
        } else {
            String.format(localize("geofence.duration.hoursMinutes"), hours, rest)
        }
    }
}

/** One alert on its way to the top of the map. */
data class GeofenceBanner(
    val id: String,
    val sentence: String,
    val type: GeofenceEventType,
    val geofenceId: String,
)

/**
 * Several alerts at once queue; each is shown for [SHOW_FOR_MS], then the next. The time is passed
 * in, so the order is tested without waiting. Not thread-safe: call it from one coroutine.
 */
class GeofenceBannerQueue {
    var current: GeofenceBanner? = null
        private set
    private val line = ArrayList<GeofenceBanner>()
    private var shownAtMs: Long? = null

    /** The ones waiting behind the one showing. */
    val waiting: List<GeofenceBanner> get() = line.toList()

    /** When the one showing is due to go; null when nothing is showing. */
    val showUntilMs: Long? get() = shownAtMs?.plus(SHOW_FOR_MS)

    /** Shows the banner now if nothing is showing, otherwise puts it in line. */
    fun add(banner: GeofenceBanner, nowMs: Long) {
        if (current == null) {
            current = banner
            shownAtMs = nowMs
        } else {
            line += banner
            // The history keeps them all; past this the oldest waiting one is dropped so a burst
            // does not keep the map busy for minutes.
            while (line.size > MOST_WAITING) line.removeAt(0)
        }
    }

    /** Moves on if the one showing has had its time. */
    fun tick(nowMs: Long) {
        val until = showUntilMs ?: return
        if (nowMs >= until) advance(nowMs)
    }

    /** Takes the one showing away (tapped, or closed) and shows the next. */
    fun dismissCurrent(nowMs: Long) {
        if (current != null) advance(nowMs)
    }

    private fun advance(nowMs: Long) {
        if (line.isEmpty()) {
            current = null
            shownAtMs = null
        } else {
            current = line.removeAt(0)
            shownAtMs = nowMs
        }
    }

    companion object {
        /** How long each banner stays up. */
        const val SHOW_FOR_MS = 5_000L

        /** How many may wait behind the one showing. */
        const val MOST_WAITING = 10
    }
}
