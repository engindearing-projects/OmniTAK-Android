package soy.engindearing.omnitak.mobile

import android.app.Application
import android.content.Context
import android.os.BatteryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import soy.engindearing.omnitak.mobile.domain.LocalMarkerStore
import kotlinx.coroutines.launch
import soy.engindearing.omnitak.mobile.data.AdminResponse
import soy.engindearing.omnitak.mobile.data.CertVault
import soy.engindearing.omnitak.mobile.data.ConfigProfileStore
import soy.engindearing.omnitak.mobile.data.LocationProvider
import soy.engindearing.omnitak.mobile.data.MeshDeviceConfigStore
import soy.engindearing.omnitak.mobile.data.SelfFixPersistence
import soy.engindearing.omnitak.mobile.data.TAKServerStore
import soy.engindearing.omnitak.mobile.data.UserPrefsStore
import soy.engindearing.omnitak.mobile.domain.ChatStore
import soy.engindearing.omnitak.mobile.domain.ContactStore
import soy.engindearing.omnitak.mobile.domain.ConnectionState
import soy.engindearing.omnitak.mobile.domain.DataPackageBootstrap
import soy.engindearing.omnitak.mobile.domain.DittoMeshService
import soy.engindearing.omnitak.mobile.domain.DrawingStore
import soy.engindearing.omnitak.mobile.domain.MapCameraStore
import soy.engindearing.omnitak.mobile.domain.MeshCoTBridge
import soy.engindearing.omnitak.mobile.domain.MeshCoTRouter
import soy.engindearing.omnitak.mobile.domain.MeshFrameworkManager
import soy.engindearing.omnitak.mobile.domain.SelfPositionBroadcaster
import soy.engindearing.omnitak.mobile.domain.TAKConnectionService
import soy.engindearing.omnitak.mobile.domain.MeshtasticManager
import soy.engindearing.omnitak.mobile.domain.MeshCoreManager
import soy.engindearing.omnitak.mobile.data.MeshFramework
import soy.engindearing.omnitak.mobile.data.MeshtasticCoTConverter
import soy.engindearing.omnitak.mobile.data.MeshCoreCoTConverter
import soy.engindearing.omnitak.mobile.domain.ServerManager

class OmniTAKApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // #139 — give MapLibre an HTTP client that sends an app-identifying
        // User-Agent. OSM/OpenTopoMap serve a "403 Access blocked" tile to
        // clients with a generic UA. Must run before any MapView is created so
        // the first tile fetch already carries it.
        soy.engindearing.omnitak.mobile.data.MapTileHttp.install(this)

        // Restore the operator's UI language (or match the device locale)
        // before any Activity composes, so the first frame renders in the
        // right language. Mirrors iOS's LocalizationManager init.
        soy.engindearing.omnitak.mobile.i18n.Loc.init(this)

        // Load the canonical CoT-type → SIDC catalogue from
        // assets/cot_types.json. Silent failure keeps the hardcoded
        // floor active so the map still renders if the asset is
        // missing or corrupt.
        soy.engindearing.omnitak.mobile.data.symbology.MilStdIconService
            .loadFromAssets(this)

        // Sideload data-package zips dropped into <files-dir>/import/.
        // Touches `serverManager` (and through it `userPrefsStore`,
        // `certVault`), so kicking it off here also wakes those lazies.
        DataPackageBootstrap(this, certVault, serverManager).runIfNeeded()

        // Plugin SDK — register the bundled plugins and activate the ones whose
        // enable flag is true (default true on first run, so existing testers
        // see ADS-B exactly as before). activate() only registers hooks; it does
        // NOT start any network poll, so this stays cheap and launch-safe.
        loadBundledPlugins()

        // Eagerly populate cachedPrefs so non-suspend sinks (cotSink,
        // mesh broadcast toggles) can read prefs.value immediately.
        appScope.launch {
            userPrefsStore.prefs.collect { cachedPrefs.value = it }
        }

        // Ditto peer mesh — prefs-driven lifecycle. applyPrefs is idempotent
        // (start on enable, stop on disable, rebuild on channel change), so
        // collecting every prefs write is fine. No-ops when the build carries
        // no Ditto credentials.
        appScope.launch {
            userPrefsStore.prefs.collect { p ->
                dittoMesh.applyPrefs(p.dittoMeshEnabled, p.dittoMeshChannel, p.dittoGatewayEnabled)
            }
        }

        // #119 — Restore locally-dropped markers from the previous session,
        // then keep the persisted blob in sync with any future changes.
        // The collect loop re-persists only the "local-*" subset so that
        // server/mesh contacts never bleed into the on-disk store.
        appScope.launch {
            localMarkerStore.load().forEach { contactStore.ingest(it) }
            contactStore.contacts.collect { contacts ->
                val localMarkers = contacts.values.filter { it.uid.startsWith("local-") }
                localMarkerStore.persist(localMarkers)
            }
        }

        // Issue #5 — start a foreground service while we're holding a
        // connection so Doze doesn't kill the read loop within ~10s of
        // backgrounding. The service is just a privilege carrier; the
        // socket itself stays in ServerManager.
        //
        // Issues #12 / SLAB-2026-05-07 — debounce Connected before firing
        // startForegroundService(). When a TAK Server requires mTLS and we
        // connect without a client cert, the TLS handshake completes (state
        // briefly = Connected), then the server immediately sends
        // TLSV1_ALERT_CERTIFICATE_REQUIRED and the read loop dies in
        // milliseconds. If we fire startForegroundService() in that window
        // and then stopService() right after, Android tears the service
        // down before onStartCommand can call startForeground() — and the
        // 5-second FGS-must-elevate timer still fires, crashing the app
        // with ForegroundServiceDidNotStartInTimeException. Waiting
        // [FGS_DEBOUNCE_MS] before calling start() means transient
        // connections never trigger the FGS at all.
        // Field feedback (PatoG, 2026-08) — the FGS must also run for
        // mesh-only operation. His MilSim devices with a radio but no TAK
        // server had no foreground privilege at all, so location (and
        // eventually the process) died at screen lock. The service now runs
        // while EITHER a server socket or a mesh radio link is up, and its
        // location FGS type keeps background GPS flowing for both.
        appScope.launch {
            var pendingStart: Job? = null
            fun currentLinkLabel(): String? {
                val server = serverManager.connectionState.value as? ConnectionState.Connected
                val meshUp = meshtastic.activeConnectionState.value is ConnectionState.Connected ||
                    meshcore.activeConnectionState.value is ConnectionState.Connected
                return when {
                    server != null && meshUp -> "${server.serverName} + mesh"
                    server != null -> server.serverName
                    meshUp -> "mesh radio"
                    // Field feedback (2026-08) — link dropped (radio power-off /
                    // out of range) but the BLE auto-reconnect loop is still
                    // trying every 20s. Keeping a label here is what keeps the
                    // FGS alive below, which in turn is what keeps that retry
                    // loop running once the screen locks — without it Doze
                    // stalls the delay()/BLE calls and reconnect silently stops
                    // working the moment the app leaves the foreground.
                    meshtastic.autoReconnectPending.value -> "reconnecting to mesh radio…"
                    else -> null
                }
            }
            combine(
                serverManager.connectionState,
                meshtastic.activeConnectionState,
                meshcore.activeConnectionState,
                meshtastic.autoReconnectPending,
            ) { server, mesh, meshCore, meshReconnectPending ->
                val states = listOf(server, mesh, meshCore)
                when {
                    states.any { it is ConnectionState.Connected } -> FgsWant.START
                    // A BLE radio dropped but we're still retrying it —
                    // start (or keep) the FGS so the retry loop survives
                    // Doze instead of stalling until the app is foregrounded.
                    meshReconnectPending -> FgsWant.START
                    // Connecting / Failed hold the service as before — a
                    // transient reconnect must not bounce the FGS.
                    states.all { it is ConnectionState.Disconnected } -> FgsWant.STOP
                    else -> FgsWant.HOLD
                }
            }.distinctUntilChanged().collect { want ->
                when (want) {
                    FgsWant.START -> {
                        pendingStart?.cancel()
                        pendingStart = appScope.launch {
                            delay(FGS_DEBOUNCE_MS)
                            currentLinkLabel()?.let { label ->
                                TAKConnectionService.start(this@OmniTAKApp, label)
                            }
                        }
                    }
                    FgsWant.STOP -> {
                        pendingStart?.cancel()
                        TAKConnectionService.stop(this@OmniTAKApp)
                    }
                    FgsWant.HOLD -> { /* leave service state alone */ }
                }
            }
        }

        // Seed the in-memory camera cache from DataStore so MapScreen's
        // cold-start read gets the persisted view before first composition.
        appScope.launch {
            val saved = userPrefsStore.prefs.first()
            mapCameraStore.seedFromPrefs(saved)
        }

        // Issue #75 — self-marker persistence across screen-off + process
        // death. Seed the in-memory fix from the persisted one so every
        // consumer (2D puck, Cesium self entity, HUD card, PPLI
        // prefs-fallback) renders immediately on cold start — stale-marked
        // downstream via SelfFix.timeMs — then persist real fixes
        // (throttled) so the NEXT cold start has them. Seed-then-collect
        // in one coroutine: the collector starts only after the seed is
        // applied, and the seeded fix never re-persists itself because
        // shouldPersist requires a strictly newer timestamp.
        appScope.launch {
            val saved = userPrefsStore.prefs.first()
            SelfFixPersistence.restoredFixOrNull(saved)?.let {
                locationProvider.seedFromPersisted(it)
            }
            var lastPersistedMs = saved.selfFixTimeMs
            locationProvider.fix.collect { fix ->
                if (fix != null &&
                    SelfFixPersistence.shouldPersist(fix.timeMs, lastPersistedMs)
                ) {
                    lastPersistedMs = fix.timeMs
                    userPrefsStore.setLastSelfFix(fix)
                }
            }
        }

        // Off-grid mesh plan Step 1b — broadcaster is now owned here so it
        // starts when EITHER a TAK server OR Meshtastic radio is connected.
        // ServerManager's own startPliBroadcast/stopPliBroadcast is kept
        // for the server-PPLI path; the app-level broadcaster handles the
        // COMBINED (server + mesh) lifecycle with mesh TX wired in.
        // distinctUntilChanged on the derived bool avoids spurious restarts.
        appScope.launch {
            combine(
                serverManager.connectionState,
                meshtastic.activeConnectionState,
                meshcore.activeConnectionState,
                // Ditto counts as connectivity: two phones alone in a field
                // must still exchange PPLI, and the broadcaster's sendCoT
                // path fans onto the peer mesh even with zero servers up.
                dittoMesh.state,
            ) { serverState, meshState, meshCoreState, dittoState ->
                serverState is ConnectionState.Connected ||
                    meshState is ConnectionState.Connected ||
                    meshCoreState is ConnectionState.Connected ||
                    dittoState is DittoMeshService.State.Syncing
            }
                .distinctUntilChanged()
                .collect { eitherConnected ->
                    if (eitherConnected) {
                        startAppBroadcaster()
                    } else {
                        stopAppBroadcaster()
                    }
                }
        }

        // Phase 2 of the gy6 plan — FAA Remote ID BLE scanner.
        // Lifecycle is driven by `remoteIdScanEnabled` so operators can
        // opt out of the always-on BLE scan (battery cost). When a
        // drone broadcasts OpenDroneID, its track flows scanner →
        // RemoteIdTrackStore → RemoteIdToCoTConverter → ContactStore,
        // and ContactLayer renders it with the SUAPMHQ---- multirotor
        // or SUAPMFQ---- fixed-wing symbol that Phase D's catalogue
        // provides.
        appScope.launch {
            userPrefsStore.prefs
                .map { it.remoteIdScanEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) {
                        remoteIdScanner.start()
                    } else {
                        remoteIdScanner.stop()
                    }
                }
        }
        appScope.launch {
            remoteIdScanner.trackUpdates.collect { changedIds ->
                for (uasId in changedIds) {
                    val track = remoteIdScanner.tracks.firstOrNull { it.uasId == uasId }
                    if (track == null) {
                        // Stale-purged track: drop the drone + pilot markers
                        // so the map stops showing a ghost.
                        contactStore.remove("RID-$uasId")
                        contactStore.remove("RID-OP-$uasId")
                    } else {
                        // Emit the drone and/or operator (pilot) markers.
                        soy.engindearing.omnitak.mobile.data.remoteid.RemoteIdToCoTConverter
                            .toCoTs(track)
                            .forEach { contactStore.ingest(it) }
                    }
                }
            }
        }

        // Phase 3 — external gyb_detect sensor. When enabled, the manager's
        // collectors run and parsed drones flow gyb → RemoteIdTrackStore →
        // RemoteIdToCoTConverter → ContactStore, sharing the `RID-` UID with
        // the on-device scanner so the same drone never double-plots.
        // Scan/connect/disconnect live in Settings → GybDeviceSheet; the
        // manager auto-reconnects to the persisted last device on start()
        // and after a BLE drop (with backoff), and stops with the toggle.
        appScope.launch {
            userPrefsStore.prefs
                .map { it.gybDetectorEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) gybManager.start() else gybManager.stop()
                }
        }
    }

    // Application-scoped singletons. Screens reach these via
    // LocalContext.current.applicationContext as OmniTAKApp.
    //
    // #174 — internal (not private) so the in-app QR enroll path can launch
    // the CSR network call on a scope that survives the scanner sheet leaving
    // composition (the bug: rememberCoroutineScope cancels mid-enrollment when
    // the scanner pops, throwing "The coroutine left the composition").
    internal val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Eagerly-cached prefs snapshot for non-suspending sinks (cotSink,
    // mesh broadcast lambdas). Initialized to defaults until DataStore emits.
    private val cachedPrefs = MutableStateFlow(soy.engindearing.omnitak.mobile.data.UserPrefs())

    /** Single plugin host instance shared by [loadBundledPlugins] and the
     *  screens that consume registrations (MapScreen overlays/radial,
     *  SettingsScreen rows). Holds the registered hooks. */
    val pluginHost: soy.engindearing.omnitak.mobile.ui.plugin.AppPluginHost by lazy {
        soy.engindearing.omnitak.mobile.ui.plugin.AppPluginHost()
    }

    /** The bundled ADS-B reference plugin instance. Held so the host can wire
     *  its camera-center provider + camera-follow at the MapScreen seam. */
    val adsbPlugin: soy.engindearing.adsb.AdsbPlugin by lazy { soy.engindearing.adsb.AdsbPlugin() }

    /** The bundled Diagnostics reference plugin — the second example plugin,
     *  exercising the radial-action + CoT-handler hooks ADS-B doesn't use.
     *  DISABLED by default (parity with iOS's DiagnosticsPlugin); see
     *  [loadBundledPlugins] for the first-run enable-flag seeding. */
    val diagnosticsPlugin: soy.engindearing.diagnostics.DiagnosticsPlugin by lazy {
        soy.engindearing.diagnostics.DiagnosticsPlugin()
    }

    val contactStore: ContactStore by lazy { ContactStore() }

    /** #119 — Persists locally-dropped point markers across process death. */
    val localMarkerStore: LocalMarkerStore by lazy { LocalMarkerStore(this) }
    /** External gyb_detect sensor over BLE GATT (Phase 3 of the gy6 plan).
     *  Parsed drones merge into ContactStore via the shared RID- UID;
     *  driven by `gybDetectorEnabled` in [onCreate]. */
    val gybManager: soy.engindearing.omnitak.mobile.domain.GybManager by lazy {
        soy.engindearing.omnitak.mobile.domain.GybManager(
            context = this,
            cotSink = { event -> contactStore.ingest(event) },
            cotRemove = { uid -> contactStore.remove(uid) },
            scope = appScope,
            trackStore = remoteIdTrackStore,
            // cachedPrefs is eagerly collected in onCreate, so by the time
            // the toggle-driven start() fires this read is non-suspending
            // and current.
            lastDeviceAddress = { cachedPrefs.value.gybLastDeviceAddress },
            persistLastDevice = { addr ->
                appScope.launch {
                    userPrefsStore.update { it.copy(gybLastDeviceAddress = addr) }
                }
            },
        )
    }
    val drawingStore: DrawingStore by lazy { DrawingStore() }
    val mapCameraStore: MapCameraStore by lazy { MapCameraStore(userPrefsStore) }

    /** #173 — raises an Android notification for incoming mesh GeoChat. Wired
     *  into both mesh managers' chat-ingest paths below. */
    val meshChatNotifier: soy.engindearing.omnitak.mobile.domain.MeshChatNotifier by lazy {
        soy.engindearing.omnitak.mobile.domain.MeshChatNotifier(this)
    }

    /** #173 follow-up — a conversation id a notification tap wants opened.
     *  MainActivity publishes the tapped notification's EXTRA_CONVERSATION_ID
     *  here; AppNav observes it, navigates to that chat thread, then clears it
     *  (back to null) so a recomposition doesn't re-navigate. */
    val pendingChatConversation = MutableStateFlow<String?>(null)

    val chatStore: ChatStore by lazy {
        ChatStore().also { store ->
            // GAP-122 — Mesh primary channel always visible so users can
            // open it before any text traffic has arrived.
            store.upsertConversationIfMissing(
                id = "MESH-CH0",
                title = "Mesh: Primary",
            )
        }
    }
    val meshtastic: MeshtasticManager by lazy {
        MeshtasticManager(this).also { mgr ->
            // Phase 4: portnum-72 ATAK-plugin payloads decode straight
            // into CoT events; route them into the same ingest sink the
            // node-table bridge uses so they surface as map contacts.
            // Off-grid Phase 1 Step 3: b-t-f events are GeoChat and must
            // route to chatStore, not contactStore. Self-echo is dropped
            // (events from our own UID would double-display — mirrors iOS).
            mgr.cotSink = { event ->
                // Drop self-echo: skip events whose UID or callsign matches ours.
                // Uses cachedPrefs.value (non-suspending) since cotSink is a
                // plain lambda invoked from a coroutine, not a suspend lambda.
                val selfUid = cachedPrefs.value.selfUid
                val selfCallsign = cachedPrefs.value.callsign
                val isSelf = (selfUid.isNotBlank() && event.uid == selfUid) ||
                    (selfCallsign.isNotBlank() && event.callsign == selfCallsign)
                if (!isSelf) {
                    when (MeshCoTRouter.classify(event)) {
                        MeshCoTRouter.Destination.CHAT -> {
                            // Convert b-t-f CoTEvent to ChatMessage and ingest.
                            // Try rawXml first (full GeoChat parse); fall back to
                            // building a ChatMessage directly from CoTEvent fields.
                            val selfUidNow = selfUid.ifBlank { null }
                            val chatMsg = event.rawXml?.let {
                                runCatching {
                                    soy.engindearing.omnitak.mobile.data.ChatXml.parse(it, selfUidNow)
                                }.getOrNull()
                            } ?: run {
                                // Manual ChatMessage from CoTEvent fields.
                                val senderCallsign = event.callsign ?: event.uid
                                val convId = soy.engindearing.omnitak.mobile.data.ChatRoom.ALL_USERS
                                soy.engindearing.omnitak.mobile.data.ChatMessage(
                                    id = event.uid,
                                    conversationId = convId,
                                    senderUid = event.uid,
                                    senderCallsign = senderCallsign,
                                    text = event.remarks.ifBlank { "[mesh chat]" },
                                    timeIso = event.timeIso
                                        ?: soy.engindearing.omnitak.mobile.data.CotXml.isoMillis(),
                                    status = soy.engindearing.omnitak.mobile.data.ChatStatus.RECEIVED,
                                    isFromSelf = false,
                                )
                            }
                            chatStore.ingest(chatMsg)
                            // #173 — surface a notification for the incoming
                            // GeoChat (no-op for our own echoes / blank text).
                            meshChatNotifier.notify(chatMsg)
                        }
                        // #180 — tag the mesh transport so the detail sheet
                        // shows "Mesh: Meshtastic".
                        MeshCoTRouter.Destination.CONTACT -> {
                            val tagged = event.copy(
                                source = soy.engindearing.omnitak.mobile.data.CoTSource
                                    .mesh("Meshtastic"),
                            )
                            contactStore.ingest(tagged)
                            // #179 — offer the mesh-origin point to the gateway
                            // relay (no-op unless on + both transports up).
                            relayInbound(tagged)
                        }
                    }
                }
            }
            // GAP-109 read-back — admin responses to our get_*_request
            // calls fold into the device-config store on a background
            // coroutine. Screen collects from the store and re-renders.
            //
            // GAP-123 — non-disabled Channel responses also seed/rename
            // the chat conversation for that channel index, so the Chat
            // tab shows every channel the radio actually has configured
            // (not just CH0).
            mgr.adminResponseSink = { response ->
                appScope.launch { meshDeviceConfigStore.applyAdminResponse(response) }
                if (response is AdminResponse.Channel && !response.isDisabled) {
                    val title = meshChannelTitle(response)
                    chatStore.upsertOrRenameConversation(
                        id = "MESH-CH${response.index}",
                        title = title,
                    )
                }
            }
            // GAP-122 — ingest mesh text messages into the same ChatStore
            // the TAK GeoChat path uses, so the Chat tab shows mesh chat
            // alongside server chat.
            //
            // GAP-124 — DM bucket "MESH-DM-{otherNodeIdHex}" titled with
            // the sender's callsign; broadcast bucket "MESH-CHn" titled
            // with the channel name (or placeholder when the radio
            // hasn't read back yet).
            mgr.chatSink = { msg ->
                val title = when {
                    msg.conversationId.startsWith("MESH-DM-") -> "DM: ${msg.senderCallsign}"
                    msg.conversationId == "MESH-CH0" -> "Mesh: Primary"
                    msg.conversationId.startsWith("MESH-CH") -> {
                        val ch = msg.conversationId.removePrefix("MESH-CH")
                        "Mesh: Channel $ch"
                    }
                    else -> msg.senderCallsign
                }
                chatStore.upsertConversationIfMissing(
                    id = msg.conversationId,
                    title = title,
                    isGroup = !msg.conversationId.startsWith("MESH-DM-"),
                )
                chatStore.ingest(msg)
                // #173 — notify on incoming mesh text from a peer.
                meshChatNotifier.notify(msg)
            }
        }
    }

    /** MeshCore companion-radio manager — the second mesh framework. Wired in
     *  parallel to [meshtastic]: contacts' adverts decode to CoT (via
     *  [meshCoreCoTBridge]); inbound DMs/channel text land in the same chat
     *  store. The mesh screen + broadcaster switch onto this when the operator
     *  selects MeshCore. */
    val meshcore: MeshCoreManager by lazy {
        MeshCoreManager(this).also { mgr ->
            mgr.cotSink = { event ->
                val selfUid = cachedPrefs.value.selfUid
                val selfCallsign = cachedPrefs.value.callsign
                val isSelf = (selfUid.isNotBlank() && event.uid == selfUid) ||
                    (selfCallsign.isNotBlank() && event.callsign == selfCallsign)
                if (!isSelf) {
                    when (MeshCoTRouter.classify(event)) {
                        MeshCoTRouter.Destination.CHAT -> {
                            val senderCallsign = event.callsign ?: event.uid
                            val convId = soy.engindearing.omnitak.mobile.data.ChatRoom.ALL_USERS
                            val chatMsg = event.rawXml?.let {
                                runCatching {
                                    soy.engindearing.omnitak.mobile.data.ChatXml.parse(it, selfUid.ifBlank { null })
                                }.getOrNull()
                            } ?: soy.engindearing.omnitak.mobile.data.ChatMessage(
                                id = event.uid,
                                conversationId = convId,
                                senderUid = event.uid,
                                senderCallsign = senderCallsign,
                                text = event.remarks.ifBlank { "[mesh chat]" },
                                timeIso = event.timeIso
                                    ?: soy.engindearing.omnitak.mobile.data.CotXml.isoMillis(),
                                status = soy.engindearing.omnitak.mobile.data.ChatStatus.RECEIVED,
                                isFromSelf = false,
                            )
                            chatStore.ingest(chatMsg)
                            // #173 — notify on incoming MeshCore GeoChat.
                            meshChatNotifier.notify(chatMsg)
                        }
                        // #180 — tag the mesh transport so the detail sheet
                        // shows "Mesh: MeshCore".
                        MeshCoTRouter.Destination.CONTACT -> {
                            val tagged = event.copy(
                                source = soy.engindearing.omnitak.mobile.data.CoTSource
                                    .mesh("MeshCore"),
                            )
                            contactStore.ingest(tagged)
                            // #179 — offer the mesh-origin point to the gateway
                            // relay (no-op unless on + both transports up).
                            relayInbound(tagged)
                        }
                    }
                }
            }
            mgr.chatSink = { msg ->
                val title = when {
                    msg.conversationId.startsWith("MESHCORE-DM-") -> "DM: ${msg.senderCallsign}"
                    msg.conversationId.startsWith("MESHCORE-CH") -> {
                        val ch = msg.conversationId.removePrefix("MESHCORE-CH")
                        "MeshCore: Channel $ch"
                    }
                    else -> msg.senderCallsign
                }
                chatStore.upsertConversationIfMissing(
                    id = msg.conversationId,
                    title = title,
                    isGroup = !msg.conversationId.startsWith("MESHCORE-DM-"),
                )
                chatStore.ingest(msg)
                // #173 — notify on incoming MeshCore text from a peer.
                meshChatNotifier.notify(msg)
            }
        }
    }
    val meshDeviceConfigStore: MeshDeviceConfigStore by lazy { MeshDeviceConfigStore(this) }
    val userPrefsStore: UserPrefsStore by lazy { UserPrefsStore(this) }

    /**
     * Holds a profile decoded from a deep-link that has not yet been
     * confirmed by the user. The UI observes this and presents
     * [ImportPreviewDialog]; on confirm/dismiss it clears this to null.
     * Using App-level state (not Activity-level) so the pending import
     * survives an onNewIntent delivered to a resumed Activity.
     */
    val pendingProfileImport = MutableStateFlow<soy.engindearing.omnitak.mobile.data.ConfigProfile?>(null)

    /** Profile store — manages named config snapshots + QR sync. */
    val configProfileStore: ConfigProfileStore by lazy {
        ConfigProfileStore(
            context = this,
            userPrefsStore = userPrefsStore,
            serverStore = TAKServerStore(this),
        )
    }
    val kmlOverlayStore: soy.engindearing.omnitak.mobile.data.KmlVectorOverlayStore by lazy {
        soy.engindearing.omnitak.mobile.data.KmlVectorOverlayStore(this)
    }
    val mbtilesOverlayStore: soy.engindearing.omnitak.mobile.data.MBTilesOverlayStore by lazy {
        soy.engindearing.omnitak.mobile.data.MBTilesOverlayStore(this)
    }
    val rasterOverlayStore: soy.engindearing.omnitak.mobile.data.RasterOverlayStore by lazy {
        soy.engindearing.omnitak.mobile.data.RasterOverlayStore(this)
    }
    /** Downloaded offline map regions (#120) — each is an MBTiles file served
     *  by the shared MBTilesServer so the map works with no network. */
    val offlineRegionStore: soy.engindearing.omnitak.mobile.data.offline.OfflineRegionStore by lazy {
        soy.engindearing.omnitak.mobile.data.offline.OfflineRegionStore(this)
    }

    /** Single Remote ID track roster shared by the phone scanner and the
     *  gyb sensor. One store means either source refreshes a drone's
     *  lastSeen and the stale purge only drops a track when BOTH sensors
     *  have gone silent — two private stores used to delete each other's
     *  live RID-/RID-OP- markers. */
    val remoteIdTrackStore: soy.engindearing.omnitak.mobile.data.remoteid.RemoteIdTrackStore by lazy {
        soy.engindearing.omnitak.mobile.data.remoteid.RemoteIdTrackStore()
    }

    /** FAA Remote ID BLE scanner (Phase 2 of the gy6 plan). Starts/stops
     *  from a coroutine in [onCreate] driven by `remoteIdScanEnabled`. */
    val remoteIdScanner: soy.engindearing.omnitak.mobile.data.remoteid.RemoteIdScanner by lazy {
        soy.engindearing.omnitak.mobile.data.remoteid.RemoteIdScanner(this, remoteIdTrackStore)
    }
    val certVault: CertVault by lazy { CertVault(this) }

    // Off-grid mesh plan Step 1b — single app-owned broadcaster instance.
    // Null until startAppBroadcaster() is called. Thread-safe via @Volatile.
    @Volatile private var appBroadcaster: SelfPositionBroadcaster? = null
    // The prefs collector feeding the broadcaster's lambda getters. Held so
    // stopAppBroadcaster() can cancel it — every connect→disconnect cycle
    // used to leak one infinite collector coroutine in process-lifetime
    // appScope (a field device flapping connectivity for days accumulated
    // hundreds, all re-running on every prefs write).
    @Volatile private var broadcasterPrefsJob: kotlinx.coroutines.Job? = null

    private fun startAppBroadcaster() {
        if (appBroadcaster != null) return
        val fixFlow = locationProvider.fix
        // Eagerly cache the mesh-broadcast prefs as StateFlows so the
        // lambda getters in SelfPositionBroadcaster can read .value
        // without suspending — they're called inside a non-suspending context.
        val broadcastOverMeshFlow = kotlinx.coroutines.flow.MutableStateFlow(true)
        val meshIntervalMsFlow = kotlinx.coroutines.flow.MutableStateFlow(30_000L)
        broadcasterPrefsJob?.cancel()
        broadcasterPrefsJob = appScope.launch {
            userPrefsStore.prefs.collect { p ->
                broadcastOverMeshFlow.value = p.broadcastOverMesh
                meshIntervalMsFlow.value = p.meshBroadcastIntervalSecs.coerceIn(30, 60).toLong() * 1000L
            }
        }
        appBroadcaster = SelfPositionBroadcaster(
            scope = appScope,
            prefsStore = userPrefsStore,
            sendCoT = { xml -> serverManager.sendCoT(xml) },
            locationFix = fixFlow,
            // #82 — when the operator manually repositions their self-marker,
            // PPLI broadcasts that coordinate instead of live GPS. The override
            // lives on LocationProvider as the shared source of truth (MapScreen
            // writes it, the puck + card read it), so the broadcast can't drift
            // from what's on screen.
            manualFixProvider = { locationProvider.manualFix.value },
            batteryProvider = ::readDeviceBatteryPercent,
            sendToMesh = { event -> activeMeshManager.sendCoTOverMesh(event) },
            meshConnected = { activeMeshManager.activeConnectionState.value is ConnectionState.Connected },
            meshBroadcastEnabled = { broadcastOverMeshFlow.value },
            // Lambda, not a snapshot — the operator's interval pref now
            // applies to a running broadcaster instead of freezing at the
            // pre-DataStore 30 s default.
            meshThrottleMs = { meshIntervalMsFlow.value },
        ).also { it.start() }
    }

    private fun stopAppBroadcaster() {
        appBroadcaster?.stop()
        appBroadcaster = null
        broadcasterPrefsJob?.cancel()
        broadcasterPrefsJob = null
    }
    val locationProvider: LocationProvider by lazy { LocationProvider(this) }
    // #83 — device compass heading for the Cesium triangle self-marker, so it
    // rotates with the operator's facing just like the 2D RenderMode.COMPASS
    // puck. Started/stopped by MapScreen only while the globe is on screen.
    val headingProvider: soy.engindearing.omnitak.mobile.data.DeviceHeadingProvider by lazy {
        soy.engindearing.omnitak.mobile.data.DeviceHeadingProvider(this)
    }
    val serverManager: ServerManager by lazy {
        ServerManager(
            store = TAKServerStore(this),
            contactStore = contactStore,
            chatStore = chatStore,
            certVault = certVault,
            userPrefsStore = userPrefsStore,
            locationProvider = locationProvider,
            // Issue #11 — read the EUD's real battery level instead of
            // hardcoding 100 in PPLI. BatteryManager returns -1 for
            // unknown; map that to null so SelfPositionBroadcaster omits
            // <status> rather than emitting a misleading number.
            batteryProvider = ::readDeviceBatteryPercent,
            // Plugin SDK — fan inbound CoT (after core ingest) to any
            // plugin-registered handlers. Inert until a plugin registers one.
            pluginCoTDispatch = { event -> pluginHost.dispatchCoT(event) },
            // #179 — offer server-origin CoT to the mesh↔server relay so the
            // gateway can push the server picture down to the LoRa mesh. The
            // event is already tagged TAK_SERVER by the inbound path above.
            inboundRelay = { event -> relayInbound(event) },
            // Ditto peer mesh — every app-originated broadcast also lands on
            // the peer mesh, even with zero servers connected. Deferred lambda
            // so the two lazy singletons can reference each other.
            dittoPublish = { xml -> dittoMesh.publish(xml) },
        )
    }

    /** Ditto peer-to-peer mesh — full-fidelity CoT sync with nearby OmniTAK
     *  devices over BLE / Wi-Fi Aware / LAN, no TAK server required. Runs
     *  ALONGSIDE the LoRa mesh (Meshtastic/MeshCore), not behind
     *  [activeMeshManager]: different radio, different bandwidth class, and
     *  an operator may legitimately run both at once. Lifecycle is prefs-
     *  driven from [onCreate]; inert without build credentials. */
    val dittoMesh: DittoMeshService by lazy {
        DittoMeshService(
            context = this,
            scope = appScope,
            // isRelay — gateway-forwarded traffic must not echo back onto the
            // mesh (see ServerManager.sendCoT).
            sendToServer = { xml -> serverManager.sendCoT(xml, isRelay = true) },
            serverConnected = { serverManager.connectionState.value is ConnectionState.Connected },
        ).also { svc ->
            // Same inbound contract as the Meshtastic sink: self-echo drop,
            // b-t-f → chat, everything else → contact store tagged with its
            // transport. NO relayInbound here — the Ditto gateway relays
            // inside the service (original XML, not a re-encode), and feeding
            // the #179 LoRa relay too would double-forward under one toggle.
            svc.cotSink = { event -> dittoInbound(event) }
        }
    }

    /** Inbound sink for Ditto-mesh CoT. Mirrors the Meshtastic cotSink wiring
     *  (self-drop → classify → ingest) with the #180 source tag "Ditto". */
    private fun dittoInbound(event: soy.engindearing.omnitak.mobile.data.CoTEvent) {
        val selfUid = cachedPrefs.value.selfUid
        val selfCallsign = cachedPrefs.value.callsign
        val isSelf = (selfUid.isNotBlank() && event.uid == selfUid) ||
            (selfCallsign.isNotBlank() && event.callsign == selfCallsign)
        if (isSelf) return
        when (MeshCoTRouter.classify(event)) {
            MeshCoTRouter.Destination.CHAT -> {
                // Ditto carries the full GeoChat XML (rawXml always set), so
                // the full parse is the primary path; the field fallback only
                // covers malformed detail blocks.
                val chatMsg = event.rawXml?.let {
                    runCatching {
                        soy.engindearing.omnitak.mobile.data.ChatXml.parse(it, selfUid.ifBlank { null })
                    }.getOrNull()
                } ?: soy.engindearing.omnitak.mobile.data.ChatMessage(
                    id = event.uid,
                    conversationId = soy.engindearing.omnitak.mobile.data.ChatRoom.ALL_USERS,
                    senderUid = event.uid,
                    senderCallsign = event.callsign ?: event.uid,
                    text = event.remarks.ifBlank { "[mesh chat]" },
                    timeIso = event.timeIso
                        ?: soy.engindearing.omnitak.mobile.data.CotXml.isoMillis(),
                    status = soy.engindearing.omnitak.mobile.data.ChatStatus.RECEIVED,
                    isFromSelf = false,
                )
                chatStore.ingest(chatMsg)
                meshChatNotifier.notify(chatMsg)
            }
            MeshCoTRouter.Destination.CONTACT -> {
                contactStore.ingest(
                    event.copy(
                        source = soy.engindearing.omnitak.mobile.data.CoTSource.mesh("Ditto"),
                    ),
                )
            }
        }
    }

    /** #179 — the mesh↔server CoT gateway. Bridges inbound CoT both ways when
     *  the operator has enabled it AND the device is connected to both a TAK
     *  server and a mesh. Loop-proof via the #180 [CoTSource] tag and hard
     *  per-uid throttling server→mesh. See [MeshServerRelay]. */
    val meshServerRelay: soy.engindearing.omnitak.mobile.domain.MeshServerRelay by lazy {
        soy.engindearing.omnitak.mobile.domain.MeshServerRelay(
            // isRelay — LoRa-mesh-heard traffic pushed up to the servers is
            // relay traffic; without the flag it would also fan onto the
            // Ditto peer mesh, and gateway devices would re-broadcast each
            // other's forwards.
            sendToServer = { xml -> serverManager.sendCoT(xml, isRelay = true) },
            sendToMesh = { event -> activeMeshManager.sendCoTOverMesh(event) },
            // Prefer the original wire XML (preserves full detail / symbology);
            // fall back to rebuilding the minimal envelope from the parsed fields.
            eventToServerXml = { event ->
                event.rawXml
                    ?: soy.engindearing.omnitak.mobile.domain.CotBuilders.rebuildEvent(event, emptyList())
            },
            serverConnected = {
                serverManager.connectionState.value is ConnectionState.Connected
            },
            meshConnected = {
                activeMeshManager.activeConnectionState.value is ConnectionState.Connected
            },
            relayEnabled = { cachedPrefs.value.relayGatewayEnabled },
        )
    }

    /** #179 — non-suspending entry the inbound sinks (server + mesh) call for
     *  every CoT. Launches the relay decision on [appScope] so the sinks stay
     *  non-blocking; the relay itself no-ops unless the gateway is on. */
    private fun relayInbound(event: soy.engindearing.omnitak.mobile.data.CoTEvent) {
        appScope.launch { meshServerRelay.onInbound(event, event.source) }
    }

    /** Multi-server mission / data-package sync over the Marti REST API.
     *  Aggregates across every enabled TLS+cert server (iOS parity). */
    val missionSyncManager: soy.engindearing.omnitak.mobile.domain.MissionSyncManager by lazy {
        soy.engindearing.omnitak.mobile.domain.MissionSyncManager(
            serverManager = serverManager,
            certVault = certVault,
        )
    }

    /** UAS / drone control (GAP-XXX). Single active drone connection
     *  at a time. Multi-drone is a fast-follow once we have a UI for it. */
    /** Registry of all connected UAS — supports multi-drone ops. The
     *  active manager is what the HUD / commands bind to; inactive
     *  managers keep streaming telemetry and rendering CoT.
     *  [uasManager] proxies to the currently-active drone so legacy
     *  one-shot reads keep working; Compose code should collect
     *  [uasRegistry.active] for reactive HUD swaps. */
    val uasRegistry: soy.engindearing.omnitak.mobile.domain.MultiUasRegistry by lazy {
        soy.engindearing.omnitak.mobile.domain.MultiUasRegistry(
            sendCoT = { xml -> serverManager.sendCoT(xml) },
            operatorFix = { locationProvider.fix.value },
        )
    }

    val uasManager: soy.engindearing.omnitak.mobile.domain.UASManager
        get() = uasRegistry.active.value

    private fun readDeviceBatteryPercent(): Int? {
        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (level in 0..100) level else null
    }

    /** SharedPreferences carrying the per-plugin `plugin_<id>_enabled` flags.
     *  Deliberately SEPARATE from the DataStore UserPrefsStore — the plugin
     *  enable flag is the RFC-specified SharedPreferences boolean, while
     *  ADS-B's `aircraftVisible` layer-visibility pref stays in DataStore. */
    fun pluginPrefs() = getSharedPreferences("omnitak_plugins", MODE_PRIVATE)

    /**
     * Register the bundled plugins and activate the enabled ones. Compile-time
     * modules only — there is no dynamic/remote code here; the plugin classes
     * are statically linked into the app, which keeps OmniTAK Play-Store
     * compliant.
     *
     * Bundled plugins default ON (so first-time users see ADS-B) EXCEPT those in
     * [DISABLED_BY_DEFAULT], whose `plugin_<id>_enabled` flag is seeded to false
     * on first run. This mirrors iOS's `registeredDisabledByDefault` and keeps
     * the registry/UI's `getBoolean(key, true)` reads honest: once the flag is
     * stored, the registry won't activate it and the Plugins list shows it OFF.
     *
     * Each plugin's hooks are tagged with its id (via the registry's
     * `onActivating` bracket → [pluginHost.withActivating]) so disabling a
     * plugin later removes exactly its hooks.
     */
    private fun loadBundledPlugins() {
        soy.engindearing.omnitak.plugin.PluginRegistry.register(adsbPlugin)
        soy.engindearing.omnitak.plugin.PluginRegistry.register(diagnosticsPlugin)

        // First-run: seed the default-OFF plugins' enable flags to false so the
        // default-true reads everywhere else (registry + Plugins UI) yield OFF.
        val prefs = pluginPrefs()
        for (id in DISABLED_BY_DEFAULT) {
            val key = soy.engindearing.omnitak.plugin.PluginRegistry.enabledKey(id)
            if (!prefs.contains(key)) {
                prefs.edit().putBoolean(key, false).apply()
            }
        }

        soy.engindearing.omnitak.plugin.PluginRegistry.activateEnabled(
            host = pluginHost.asPluginHost(),
            prefs = prefs,
            onActivating = { id, activate -> pluginHost.withActivating(id) { activate() } },
        )
    }

    /** Bridges Meshtastic node updates into the active server's CoT
     *  pipeline by feeding [ContactStore.ingest] (which is what every
     *  other CoT source already lands in). Started lazily on first
     *  access so we don't pay for it until the user opens the
     *  Meshtastic screen or connects to a radio.
     *
     *  Phase 3: respects the persisted `autoPublishMeshToTak` toggle —
     *  the bridge stays "started" but its `enabled` flag is observed
     *  off the prefs store so flipping the menu item from any screen
     *  immediately stops/resumes pushes to `cotSink`. */
    private companion object {
        const val FGS_DEBOUNCE_MS = 750L

        /** Bundled plugins that ship OFF on first run (parity with iOS's
         *  `registeredDisabledByDefault`). The Diagnostics probe is an SDK
         *  reference plugin, never on for shipping users. */
        val DISABLED_BY_DEFAULT = setOf("soy.engindearing.diagnostics")
    }

    val meshtasticCoTBridge: MeshCoTBridge by lazy {
        MeshCoTBridge(
            mesh = meshtastic,
            cotSink = { event -> contactStore.ingest(event) },
            nodeToCoT = { MeshtasticCoTConverter.nodeToCoT(it) },
        ).also { bridge ->
            bridge.start()
            // Mirror the persisted prefs into the bridge so the
            // operator's last choice survives a process restart.
            appScope.launch {
                userPrefsStore.prefs
                    .map { it.autoPublishMeshToTak }
                    .distinctUntilChanged()
                    .collect { bridge.enabled = it }
            }
        }
    }

    /** MeshCore-side CoT bridge — parallel to [meshtasticCoTBridge], but
     *  converts MeshCore contacts (UID `MESHCORE-…`). Both bridges feed the
     *  same contact store; only the active framework's manager produces
     *  nodes (the other has no live connection), so running both is inert.
     *  Honors the same `autoPublishMeshToTak` toggle. */
    val meshCoreCoTBridge: MeshCoTBridge by lazy {
        MeshCoTBridge(
            mesh = meshcore,
            cotSink = { event -> contactStore.ingest(event) },
            nodeToCoT = { MeshCoreCoTConverter.nodeToCoT(it) },
        ).also { bridge ->
            bridge.start()
            appScope.launch {
                userPrefsStore.prefs
                    .map { it.autoPublishMeshToTak }
                    .distinctUntilChanged()
                    .collect { bridge.enabled = it }
            }
        }
    }

    /** The mesh manager matching the operator's selected framework. Read off
     *  the eagerly-cached prefs snapshot so non-suspending callers (the
     *  broadcaster lambdas) can resolve it. Defaults to Meshtastic. */
    val activeMeshManager: MeshFrameworkManager
        get() = when (cachedPrefs.value.selectedMeshFramework) {
            MeshFramework.MESHCORE -> meshcore
            MeshFramework.MESHTASTIC -> meshtastic
        }
}

/**
 * GAP-123 — render a chat-conversation title for a Meshtastic channel
 * the radio reported. Prefers the operator's actual channel name; falls
 * back to "Primary" / "Channel N" when the radio left the slot blank
 * (eg. PRIMARY with no custom name).
 */
private fun meshChannelTitle(channel: AdminResponse.Channel): String {
    val name = channel.name.trim()
    return when {
        name.isNotEmpty() -> "Mesh: $name"
        channel.isPrimary -> "Mesh: Primary"
        else -> "Mesh: Channel ${channel.index}"
    }
}

/** Desired foreground-service state derived from the server + mesh links. */
private enum class FgsWant { START, HOLD, STOP }
