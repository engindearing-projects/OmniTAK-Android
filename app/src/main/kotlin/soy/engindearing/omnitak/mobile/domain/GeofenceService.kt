package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.SelfFix
import soy.engindearing.omnitak.mobile.data.geofence.Geofence
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceAlertText
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceBanner
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceBannerQueue
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceEvent
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceMonitor
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceOccupancy
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceSubjectRule
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceTransition
import java.util.UUID

/** Where the operator is, as the service needs it. */
data class OperatorPosition(val lat: Double, val lon: Double)

/**
 * Where the geofences take the operator to be: the same position the position card shows, which is
 * the one the operator placed by hand if they did ([manual]), else the live fix ([live]). A fix kept
 * from the last run is where the operator was, not where they are: it is drawn, but it is no
 * position to watch, and a watch that began from it would announce a move the operator never made.
 */
fun operatorPositionOf(manual: SelfFix?, live: SelfFix?): OperatorPosition? =
    (manual ?: live?.takeIf { !it.restored })?.let { OperatorPosition(it.lat, it.lon) }

/**
 * Watches the geofences. It runs for the life of the app (started once at launch, not by any
 * screen) and does nothing until at least one geofence is switched on; then it listens to what the
 * app already has:
 *  - the operator's own position ([operatorPosition]: the same fix the position card shows),
 *  - the teammates ([contacts]: the contact store, which also holds the contacts heard over a mesh
 *    radio),
 * and hands each position to the pure [GeofenceMonitor], which decides what happened. What it
 * reports becomes an entry in the history ([GeofenceStore.events]), a banner for the map
 * ([banner]), and, with the app out of the foreground, an Android notification ([notify]).
 *
 * Every position is given to the monitor again every [tickMs] (a teammate who stands still sends
 * nothing new, and the dwell alert needs the time to move on), and a contact that has gone quiet
 * or been removed is forgotten without an alert.
 *
 * The alerts are only as good as the positions that arrive. With a TAK server or a mesh radio link
 * up, the connection service (a foreground service of the location type) keeps the process alive and
 * the phone's GPS flowing with the screen off, so the operator's alerts and the teammates' both come,
 * and each is a notification. With no link nothing holds the process or the GPS once the app leaves
 * the screen: Android may stop it, and one it leaves alive is given a location only now and then, so
 * an alert about the operator is late or does not come. Nothing is watched while the process is not
 * running; the next start begins from a new baseline and does not announce what happened while the
 * app was away.
 *
 * Run [run] on a single-threaded dispatcher: everything here assumes one coroutine at a time. The
 * public state ([occupancy], [banner]) can be read from any thread.
 */
class GeofenceService(
    private val store: GeofenceStore,
    private val operatorPosition: Flow<OperatorPosition?>,
    private val contacts: StateFlow<Map<String, CoTEvent>>,
    /** Contacts the map is not showing because they have not been heard from: not watched. */
    private val goneContacts: () -> Set<String> = { emptySet() },
    /** Contacts that are not teammates for another reason (the operator's own paired radio). */
    private val ignoredContacts: () -> Set<String> = { emptySet() },
    private val selfUid: () -> String = { "" },
    private val selfCallsign: () -> String = { "" },
    /** Makes sure the phone's location is on while the operator is watched. Cheap, called often. */
    private val keepLocationOn: () -> Unit = {},
    /** True when no screen of the app is showing: the alert goes out as a notification too. */
    private val inBackground: () -> Boolean = { false },
    private val notify: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val tickMs: Long = TICK_MS,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val monitor = GeofenceMonitor()
    private val queue = GeofenceBannerQueue()
    private var zones: List<Geofence> = emptyList()

    private data class Subject(val id: String, val name: String, val lat: Double, val lon: Double, val isOperator: Boolean)

    private var operatorSubject: Subject? = null
    private val contactSubjects = HashMap<String, Subject>()

    /** The contact map as the last pass saw it: a contact whose entry is the same object has not reported since. */
    private var seenContacts: Map<String, CoTEvent> = emptyMap()

    private data class Wants(val operator: Boolean = false, val contacts: Boolean = false) {
        val any: Boolean get() = operator || contacts
    }

    private val wants = MutableStateFlow(Wants())

    private val _occupancy = MutableStateFlow<Map<String, GeofenceOccupancy>>(emptyMap())

    /** Who is inside each zone that is on, as far as the service knows. */
    val occupancy: StateFlow<Map<String, GeofenceOccupancy>> = _occupancy.asStateFlow()

    private val _banner = MutableStateFlow<GeofenceBanner?>(null)

    /** The alert on its way across the top of the map, if any. */
    val banner: StateFlow<GeofenceBanner?> = _banner.asStateFlow()

    /** When the banner showing is due to go. */
    private val bannerUntilMs = MutableStateFlow<Long?>(null)

    private sealed interface Command {
        data object DismissBanner : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)

    /** The banner on screen was tapped, or closed: show the next one. Safe from any thread. */
    fun dismissBanner() {
        commands.trySend(Command.DismissBanner)
    }

    /** Watches until cancelled. Launch it once, on the application scope, on a single-threaded dispatcher. */
    suspend fun run() {
        coroutineScope { watch() }
    }

    private fun CoroutineScope.watch() {
        launch { store.geofences.collect { apply(it) } }
        launch {
            wants.map { it.operator }.distinctUntilChanged().collectLatest { on ->
                if (!on) {
                    operatorSubject = null
                } else {
                    // Delivers the current position at once, which is the baseline for a new geofence.
                    operatorPosition.collect { handleOperator(it) }
                }
            }
        }
        launch {
            wants.map { it.contacts }.distinctUntilChanged().collectLatest { on ->
                if (!on) {
                    contactSubjects.clear()
                    seenContacts = emptyMap()
                } else {
                    contacts.collect { handleContacts(it) }
                }
            }
        }
        launch {
            wants.map { it.any }.distinctUntilChanged().collectLatest { on ->
                if (on) {
                    while (true) {
                        delay(tickMs)
                        tick()
                    }
                }
            }
        }
        launch {
            bannerUntilMs.collectLatest { until ->
                if (until != null) {
                    delay((until - clock()).coerceAtLeast(0L))
                    queue.tick(clock())
                    syncBanner()
                }
            }
        }
        launch { for (command in commands) handle(command) }
    }

    private fun handle(command: Command) {
        when (command) {
            Command.DismissBanner -> {
                queue.dismissCurrent(clock())
                syncBanner()
            }
        }
    }

    /**
     * The geofence list changed (one was made, switched, deleted): listen to what the switched-on
     * ones watch, and take a baseline of everyone already known.
     */
    private fun apply(list: List<Geofence>) {
        zones = list
        monitor.retain(list)

        val monitored = list.filter { it.isActive && it.hasUsableShape }
        val now = Wants(
            operator = monitored.any { it.watchSelf },
            contacts = monitored.any { it.watchContacts },
        )
        wants.value = now
        if (now.operator) keepLocationOn()
        if (now.any) refeed(clock())
        publishOccupancy()
    }

    // -------------------------------------------------------------------- positions in

    private fun handleOperator(position: OperatorPosition?) {
        if (position == null || !Geofence.isValidCoordinate(position.lat, position.lon)) return
        val subject = Subject(OPERATOR_ID, selfCallsign(), position.lat, position.lon, isOperator = true)
        operatorSubject = subject
        ingest(subject, clock())
        publishOccupancy()
    }

    private fun handleContacts(map: Map<String, CoTEvent>) {
        val now = clock()
        val gone = goneContacts()
        val ignored = ignoredContacts()
        for ((uid, event) in map) {
            // The contact store hands back the same object for a contact that has not reported.
            if (seenContacts[uid] === event) continue
            if (uid in gone) continue
            val subject = teammate(event, ignored) ?: continue
            contactSubjects[uid] = subject
            ingest(subject, now)
        }
        seenContacts = map
        publishOccupancy()
    }

    private fun teammate(event: CoTEvent, ignored: Set<String>): Subject? {
        if (!GeofenceSubjectRule.isTeammate(event.uid, event.type, event.how, selfUid())) return null
        if (event.uid in ignored) return null
        if (!Geofence.isValidCoordinate(event.lat, event.lon)) return null
        val name = event.callsign?.takeIf { it.isNotBlank() } ?: event.uid
        return Subject(event.uid, name, event.lat, event.lon, isOperator = false)
    }

    // -------------------------------------------------------------------- each pass

    private fun tick() {
        val now = clock()
        if (wants.value.operator) keepLocationOn()
        forgetContactsThatHaveGone()
        seedContacts()
        refeed(now)
        publishOccupancy()
    }

    /**
     * A contact removed from the map, or hidden for not having been heard from, raises nothing and
     * is forgotten: its next report is a new baseline.
     */
    private fun forgetContactsThatHaveGone() {
        if (contactSubjects.isEmpty()) return
        val present = contacts.value
        val gone = goneContacts()
        for (id in contactSubjects.keys.toList()) {
            if (id !in present || id in gone) {
                contactSubjects.remove(id)
                monitor.forget(id)
            }
        }
    }

    /**
     * Contacts that are on the map and not known yet, taken in with a baseline: ones that were
     * hidden a moment ago and have reported since, or that the map had before the geofence started
     * watching them.
     */
    private fun seedContacts() {
        if (!wants.value.contacts) return
        val gone = goneContacts()
        val ignored = ignoredContacts()
        val now = clock()
        for ((uid, event) in contacts.value) {
            if (uid in contactSubjects || uid in gone) continue
            val subject = teammate(event, ignored) ?: continue
            contactSubjects[uid] = subject
            ingest(subject, now)
        }
    }

    /**
     * Everyone's last known position, again, at [now]: a subject that is standing still sends
     * nothing new, and dwell needs the time to move on.
     */
    private fun refeed(now: Long) {
        operatorSubject?.let { ingest(it, now) }
        for (subject in contactSubjects.values) ingest(subject, now)
    }

    private fun ingest(subject: Subject, now: Long) {
        val transitions = monitor.update(
            subject = subject.id,
            name = subject.name,
            lat = subject.lat,
            lon = subject.lon,
            nowMs = now,
            zones = zones,
            isOperator = subject.isOperator,
        )
        if (transitions.isNotEmpty()) deliver(transitions)
    }

    private fun publishOccupancy() {
        val fresh = HashMap<String, GeofenceOccupancy>()
        for (zone in zones) {
            if (zone.isActive && zone.hasUsableShape) fresh[zone.id] = monitor.occupancy(zone.id)
        }
        if (fresh != _occupancy.value) _occupancy.value = fresh
    }

    // -------------------------------------------------------------------- alerts out

    private fun deliver(transitions: List<GeofenceTransition>) {
        store.recordEvents(
            transitions.map {
                GeofenceEvent(
                    id = newId(),
                    geofenceId = it.geofenceId,
                    geofenceName = it.geofenceName,
                    type = it.type,
                    atMs = it.atMs,
                    lat = it.lat,
                    lon = it.lon,
                    subjectId = it.subjectId,
                    subjectName = it.subjectName,
                    byOperator = it.isOperator,
                    stayMs = it.stayMs,
                )
            },
        )
        val now = clock()
        for (transition in transitions) {
            val sentence = GeofenceAlertText.sentence(transition)
            queue.add(GeofenceBanner(newId(), sentence, transition.type, transition.geofenceId), now)
            if (inBackground()) notify(sentence)
        }
        syncBanner()
    }

    /** Puts the queue's front on the published banner and sets the moment it is due to go. */
    private fun syncBanner() {
        if (_banner.value != queue.current) _banner.value = queue.current
        bannerUntilMs.value = queue.showUntilMs
    }

    companion object {
        /** The operator's id in the monitor. Not a CoT uid, so no contact can be taken for it. */
        const val OPERATOR_ID = "__operator__"

        /** Seconds between passes that give stationary subjects' last positions again. */
        const val TICK_MS = 10_000L
    }
}
