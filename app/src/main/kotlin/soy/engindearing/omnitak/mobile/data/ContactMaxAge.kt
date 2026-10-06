package soy.engindearing.omnitak.mobile.data

import soy.engindearing.omnitak.mobile.domain.ContactStore

/**
 * #215: hide, then remove, a teammate that has stopped reporting.
 *
 * Field request (a 50+ operator MilSim event): a teammate whose position has not been updated
 * for a long time is noise on a busy map. The operator picks a max age. Past it the marker
 * leaves the map; at twice that the contact itself goes.
 *
 * Everything here is a pure function of epoch-millis values and a [CoTEvent], so it unit-tests
 * on a fixed clock with no Android dependencies, like [CoTAge] next to it.
 *
 * The clock the rule reads is when THIS device last received a report from the contact
 * ([CoTEvent.receivedAtMs]). It is not the CoT `time` or `stale` attribute: those come from the
 * sender's clock. The CoT stale time is deliberately not used either. Senders often set it a few
 * minutes ahead, which would hide teammates far sooner than the setting says, and the #178 fade
 * already shows that.
 */
object ContactMaxAge {
    /** What the rule says about one contact right now. */
    enum class Decision {
        /** Show it, as today (the #178 fade still applies). */
        FRESH,

        /** Leave it off the map, keep it in the store, list it as not heard from. */
        HIDDEN,

        /** Take it out of the contact store. Chat conversations are not touched. */
        REMOVE,
    }

    /** The "Never" choice. The rule then never hides or removes anything, which is the behaviour before #215. */
    const val NEVER = 0

    /** Default max age in whole minutes. */
    const val DEFAULT_MINUTES = 30

    /** What Settings offers, in the order it shows them. [NEVER] is last. */
    val CHOICES: List<Int> = listOf(5, 10, 15, 30, 60, 120, NEVER)

    private const val MINUTE_MS = 60_000L

    /**
     * The whole rule for one contact: age is [nowMs] minus [lastReceivedMs].
     *
     *  - age < max age: [Decision.FRESH]
     *  - max age <= age < 2 x max age: [Decision.HIDDEN]
     *  - age >= 2 x max age: [Decision.REMOVE]
     *
     * [maxAgeMinutes] of 0 or less is Never: always [Decision.FRESH]. A clock that reads earlier
     * than the last report (a negative age) is fresh, and so is a contact with no received-at
     * stamp (0 means the store never stamped it), because there is nothing to measure.
     */
    fun decide(nowMs: Long, lastReceivedMs: Long, maxAgeMinutes: Int): Decision {
        if (maxAgeMinutes <= 0) return Decision.FRESH
        if (lastReceivedMs <= 0L) return Decision.FRESH
        val age = nowMs - lastReceivedMs
        val maxMs = maxAgeMinutes.toLong() * MINUTE_MS
        return when {
            age < maxMs -> Decision.FRESH
            age < 2 * maxMs -> Decision.HIDDEN
            else -> Decision.REMOVE
        }
    }

    /** [decide] for a stored contact: anything the rule does not apply to is always [Decision.FRESH]. */
    fun decide(event: CoTEvent, nowMs: Long, maxAgeMinutes: Int, selfUid: String?): Decision =
        if (appliesTo(event, selfUid)) decide(nowMs, event.receivedAtMs, maxAgeMinutes) else Decision.FRESH

    /**
     * Whether the rule applies to [event]: only another operator's own position reports, the
     * device you could send a direct message to. Never anything someone placed, never me.
     *
     * [ContactStore.isEndpoint] is the first test, and it drops this device's own dropped markers
     * (`local-…`) and bookmark points and spots (`b-m-p-…`). It is not enough alone: a hostile
     * marker, a FEMA marker, a report or a drawing that ANOTHER operator placed passes it too,
     * and so do Remote ID drones and the mesh layer's node markers. Hiding those after a
     * while would lose information the operator put there on purpose. So the rule also needs:
     *
     *  - not my own uid (a server that echoes my position back is still me);
     *  - not a Remote ID drone or pilot (`RID-…`) or a mesh node marker (`MESHTASTIC-…`,
     *    `MESHCORE-…`, `mesh-…`): those have their own lifetimes;
     *  - a friendly type (`a-f-…`): a device reports itself as a friendly unit; neutral, unknown
     *    and hostile tracks are feeds or markers, and aircraft from the ADS-B plugin never enter
     *    the contact store at all;
     *  - a machine-reported position (CoT `how` starting `m-`): a person-placed marker says `h-…`.
     *
     * An event with no `how` (it came through a path that does not read it) is left alone.
     * When a type is unclear the rule does nothing, which is what the contact store did before.
     */
    fun appliesTo(event: CoTEvent, selfUid: String?): Boolean {
        if (!ContactStore.isEndpoint(event)) return false
        if (!selfUid.isNullOrBlank() && event.uid == selfUid) return false
        if (NOT_A_REPORTING_DEVICE_UID_PREFIXES.any { event.uid.startsWith(it) }) return false
        if (!event.type.startsWith("a-f-")) return false
        return event.how?.startsWith("m-") == true
    }

    /** Uid prefixes of things that are not another operator's own device. */
    private val NOT_A_REPORTING_DEVICE_UID_PREFIXES = listOf("RID-", "MESHTASTIC-", "MESHCORE-", "mesh-")

    /** A number of minutes as Settings and the list show it: "5 min", "1 h", "1 h 30 min", "Never". */
    fun minutesLabel(minutes: Int): String = when {
        minutes <= 0 -> "Never"
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }

    /** Title of the collapsed list section: "Not heard from for over 30 min". */
    fun sectionTitle(maxAgeMinutes: Int): String = "Not heard from for over ${minutesLabel(maxAgeMinutes)}"

    /** How long ago the last report was, for a row of that section: "<1 min", "42 min", "1 h 5 min". */
    fun ageLabel(ageMs: Long): String {
        val minutes = if (ageMs <= 0L) 0 else (ageMs / MINUTE_MS).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return if (minutes < 1) "<1 min" else minutesLabel(minutes)
    }
}
