package soy.engindearing.omnitak.mobile.data.geofence

/**
 * The rules of geofencing, kept free of any location provider, timer or clock so they can be
 * tested on their own. The service feeds the monitor positions and the time; it says what
 * happened. The same rules, rule for rule, as the iOS app's `GeofenceMonitor`:
 *
 *  - The first time a subject is seen against a zone only sets the baseline and raises nothing:
 *    making a zone around yourself must not announce that you "entered" it, and a restart must
 *    not announce everyone again.
 *  - Entry is outside to inside, exit is inside to outside, and dwell fires once per stay when the
 *    stay reaches the zone's own dwell time (above zero).
 *  - A zone that is switched off, deleted, or no longer watching a kind of subject forgets those
 *    subjects; switching it back on starts from a new baseline.
 *  - A subject that disappears (a contact removed or gone quiet) raises nothing, and is
 *    forgotten, so its next sighting is a new baseline.
 *  - The time is always passed in. Nothing here reads a clock.
 */

/** One thing that happened: a subject crossed a zone's edge, or stayed long enough. */
data class GeofenceTransition(
    val geofenceId: String,
    val geofenceName: String,
    /** A stable id: the operator's own, or a contact's CoT uid. */
    val subjectId: String,
    /** What to call them: a callsign. */
    val subjectName: String,
    val isOperator: Boolean,
    val type: GeofenceEventType,
    /** Where the subject was when it happened. */
    val lat: Double,
    val lon: Double,
    val atMs: Long,
    /** How long the subject had been inside: set for an exit and for a dwell. */
    val stayMs: Long?,
)

/** Who is inside a zone right now, as far as the monitor knows. */
data class GeofenceOccupancy(
    val operatorInside: Boolean = false,
    val teammates: Int = 0,
) {
    val isEmpty: Boolean get() = !operatorInside && teammates == 0
}

/** Who counts as a teammate for a geofence. */
object GeofenceSubjectRule {

    /**
     * Uid prefixes (compared without regard to case) that are never a teammate:
     *  - `marker-` and `rid-`: a dropped marker, and a drone detection (`RID-`, `RID-OP-`): the same
     *    ones the iOS rule leaves out;
     *  - `local-`: a point the operator dropped on this phone, which is how this app names its
     *    markers (the same test as [soy.engindearing.omnitak.mobile.domain.ContactStore.isEndpoint]);
     *  - `mesh-self-`: the operator's own radio.
     */
    private val NOT_A_TEAMMATE_UID_PREFIXES = listOf("marker-", "rid-", "local-", "mesh-self-")

    /**
     * Whether a position report is a teammate for geofencing: a unit (CoT type `a-...`) that is
     * not hostile, suspect or joker, reported by a device (`how` starts with `m-`, or is absent) and
     * not placed by hand (`h-`). Not the operator's own uid (the phone's GPS covers that), not a
     * dropped marker, not a drone detection.
     *
     * Contacts heard over a mesh radio are teammates too: they reach the app through the same
     * contact store as the ones heard from a TAK server.
     */
    fun isTeammate(uid: String, type: String, how: String?, selfUid: String?): Boolean {
        if (!selfUid.isNullOrBlank() && uid == selfUid) return false
        val lowered = uid.lowercase()
        if (NOT_A_TEAMMATE_UID_PREFIXES.any { lowered.startsWith(it) }) return false

        val parts = type.lowercase().split('-')
        if (parts.size < 2 || parts[0] != "a") return false
        if (parts[1] == "h" || parts[1] == "s" || parts[1] == "j") return false

        val reportedBy = how?.lowercase()
        if (reportedBy != null && !reportedBy.startsWith("m-")) return false
        return true
    }
}

/**
 * Holds, for every zone and every subject, whether the subject was inside, since when, and whether
 * the dwell alert has fired for this stay. Not thread-safe: call it from one coroutine.
 */
class GeofenceMonitor {
    private data class Key(val zone: String, val subject: String)

    private data class Stay(
        val isOperator: Boolean,
        val inside: Boolean,
        /** When the current stay began; null while outside. */
        val sinceMs: Long?,
        val dwellFired: Boolean,
    )

    private val stays = HashMap<Key, Stay>()

    /**
     * Takes one position of one subject at [nowMs] and returns what changed, for every zone that
     * watches that kind of subject.
     *
     * [zones] is every geofence there is, not just the ones to look at: the ones that are gone or
     * switched off are forgotten here. [isOperator] is the phone's own position, as opposed to a
     * teammate's.
     *
     * Returns the transitions the zones asked to hear about: entry only if the zone alerts on entry,
     * exit only if it alerts on exit, dwell only if its dwell time is above zero. The state is kept
     * whether or not an alert was asked for.
     *
     * Dwell is judged from the times it is given, so a subject that stays put and sends nothing new
     * needs its last position given again later for the dwell alert to fire.
     */
    fun update(
        subject: String,
        name: String,
        lat: Double,
        lon: Double,
        nowMs: Long,
        zones: List<Geofence>,
        isOperator: Boolean = false,
    ): List<GeofenceTransition> {
        retain(zones)

        val transitions = ArrayList<GeofenceTransition>()
        for (zone in zones) {
            if (!zone.isActive || !zone.hasUsableShape || !zone.watches(isOperator)) continue
            val key = Key(zone.id, subject)
            val inside = zone.contains(lat, lon)

            val before = stays[key]
            if (before == null) {
                // First sighting: the baseline. Nothing is raised.
                stays[key] = Stay(isOperator, inside, if (inside) nowMs else null, dwellFired = false)
                continue
            }

            fun transition(type: GeofenceEventType, stayMs: Long?) = GeofenceTransition(
                geofenceId = zone.id,
                geofenceName = zone.name,
                subjectId = subject,
                subjectName = name,
                isOperator = isOperator,
                type = type,
                lat = lat,
                lon = lon,
                atMs = nowMs,
                stayMs = stayMs,
            )

            var stay = before
            if (inside && !before.inside) {
                stay = Stay(isOperator, inside = true, sinceMs = nowMs, dwellFired = false)
                if (zone.alertOnEntry) transitions += transition(GeofenceEventType.ENTRY, null)
            } else if (!inside && before.inside) {
                val spent = before.sinceMs?.let { (nowMs - it).coerceAtLeast(0L) }
                stay = Stay(isOperator, inside = false, sinceMs = null, dwellFired = false)
                if (zone.alertOnExit) transitions += transition(GeofenceEventType.EXIT, spent)
            } else if (inside && !before.dwellFired && zone.dwellMs > 0L && before.sinceMs != null) {
                val spent = (nowMs - before.sinceMs).coerceAtLeast(0L)
                if (spent >= zone.dwellMs) {
                    stay = before.copy(dwellFired = true)
                    transitions += transition(GeofenceEventType.DWELL, spent)
                }
            }
            stays[key] = stay
        }
        return transitions
    }

    /** Forgets a subject everywhere. It raises nothing, and its next sighting is a new baseline. */
    fun forget(subject: String) {
        stays.keys.removeAll { it.subject == subject }
    }

    /**
     * Forgets what the zones no longer watch: zones that are gone, switched off, without a shape,
     * or not watching that kind of subject any more.
     */
    fun retain(zones: List<Geofence>) {
        val byId = zones.associateBy { it.id }
        val iterator = stays.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val zone = byId[entry.key.zone]
            if (zone == null || !zone.isActive || !zone.hasUsableShape || !zone.watches(entry.value.isOperator)) {
                iterator.remove()
            }
        }
    }

    /** Who is inside one zone now. */
    fun occupancy(zoneId: String): GeofenceOccupancy {
        var operatorInside = false
        var teammates = 0
        for ((key, stay) in stays) {
            if (key.zone != zoneId || !stay.inside) continue
            if (stay.isOperator) operatorInside = true else teammates++
        }
        return GeofenceOccupancy(operatorInside, teammates)
    }
}
