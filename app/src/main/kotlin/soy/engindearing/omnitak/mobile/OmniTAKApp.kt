package soy.engindearing.omnitak.mobile

import android.app.Application
import android.content.Context
import android.os.BatteryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import soy.engindearing.omnitak.mobile.data.AdminResponse
import soy.engindearing.omnitak.mobile.data.CertVault
import soy.engindearing.omnitak.mobile.data.LocationProvider
import soy.engindearing.omnitak.mobile.data.MeshDeviceConfigStore
import soy.engindearing.omnitak.mobile.data.TAKServerStore
import soy.engindearing.omnitak.mobile.data.UserPrefsStore
import soy.engindearing.omnitak.mobile.domain.ChatStore
import soy.engindearing.omnitak.mobile.domain.ContactStore
import soy.engindearing.omnitak.mobile.domain.ConnectionState
import soy.engindearing.omnitak.mobile.domain.DataPackageBootstrap
import soy.engindearing.omnitak.mobile.domain.DrawingStore
import soy.engindearing.omnitak.mobile.domain.MapCameraStore
import soy.engindearing.omnitak.mobile.domain.MeshtasticCoTBridge
import soy.engindearing.omnitak.mobile.domain.TAKConnectionService
import soy.engindearing.omnitak.mobile.domain.MeshtasticManager
import soy.engindearing.omnitak.mobile.domain.ServerManager

class OmniTAKApp : Application() {

    override fun onCreate() {
        super.onCreate()

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
        appScope.launch {
            var pendingStart: Job? = null
            serverManager.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        pendingStart?.cancel()
                        pendingStart = appScope.launch {
                            delay(FGS_DEBOUNCE_MS)
                            if (serverManager.connectionState.value is ConnectionState.Connected) {
                                TAKConnectionService.start(this@OmniTAKApp, state.serverName)
                            }
                        }
                    }
                    ConnectionState.Disconnected -> {
                        pendingStart?.cancel()
                        TAKConnectionService.stop(this@OmniTAKApp)
                    }
                    else -> { /* Connecting / Failed: leave service state alone */ }
                }
            }
        }

        // Seed the in-memory camera cache from DataStore so MapScreen's
        // cold-start read gets the persisted view before first composition.
        appScope.launch {
            val saved = userPrefsStore.prefs.first()
            mapCameraStore.seedFromPrefs(saved)
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
                        // Stale-purged track: drop the marker so the map
                        // stops showing a ghost.
                        contactStore.remove(
                            "RID-$uasId",
                        )
                    } else {
                        soy.engindearing.omnitak.mobile.data.remoteid.RemoteIdToCoTConverter
                            .toCoT(track)
                            ?.let { contactStore.ingest(it) }
                    }
                }
            }
        }

        // Phase 3 — external gyb_detect sensor. When enabled, the manager's
        // collectors run and parsed drones flow gyb → RemoteIdTrackStore →
        // RemoteIdToCoTConverter → ContactStore, sharing the `RID-` UID with
        // the on-device scanner so the same drone never double-plots. Actual
        // BLE connect/disconnect is driven from Settings.
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
    private val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val contactStore: ContactStore by lazy { ContactStore() }
    val drawingStore: DrawingStore by lazy { DrawingStore() }
    val mapCameraStore: MapCameraStore by lazy { MapCameraStore(userPrefsStore) }
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
            mgr.cotSink = { event -> contactStore.ingest(event) }
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
            }
        }
    }
    val meshDeviceConfigStore: MeshDeviceConfigStore by lazy { MeshDeviceConfigStore(this) }
    val userPrefsStore: UserPrefsStore by lazy { UserPrefsStore(this) }
    val kmlOverlayStore: soy.engindearing.omnitak.mobile.data.KmlVectorOverlayStore by lazy {
        soy.engindearing.omnitak.mobile.data.KmlVectorOverlayStore(this)
    }
    val mbtilesOverlayStore: soy.engindearing.omnitak.mobile.data.MBTilesOverlayStore by lazy {
        soy.engindearing.omnitak.mobile.data.MBTilesOverlayStore(this)
    }
    val rasterOverlayStore: soy.engindearing.omnitak.mobile.data.RasterOverlayStore by lazy {
        soy.engindearing.omnitak.mobile.data.RasterOverlayStore(this)
    }

    /** FAA Remote ID BLE scanner (Phase 2 of the gy6 plan). Starts/stops
     *  from a coroutine in [onCreate] driven by `remoteIdScanEnabled`. */
    val remoteIdScanner: soy.engindearing.omnitak.mobile.data.remoteid.RemoteIdScanner by lazy {
        soy.engindearing.omnitak.mobile.data.remoteid.RemoteIdScanner(this)
    }

    /** External gyb_detect sensor over BLE GATT (Phase 3 of the gy6 plan).
     *  Catches WiFi-beacon Remote ID the phone can't see and streams it over
     *  Bluetooth; detections merge with on-device BLE Remote ID into one
     *  `RID-` contact. Connect/disconnect driven from the UI; enabled state
     *  driven by `gybDetectorEnabled` in [onCreate]. */
    val gybManager: soy.engindearing.omnitak.mobile.domain.GybManager by lazy {
        soy.engindearing.omnitak.mobile.domain.GybManager(
            context = this,
            cotSink = { event -> contactStore.ingest(event) },
            cotRemove = { uid -> contactStore.remove(uid) },
            scope = appScope,
        )
    }
    val certVault: CertVault by lazy { CertVault(this) }
    val locationProvider: LocationProvider by lazy { LocationProvider(this) }
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
        )
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
    }

    val meshtasticCoTBridge: MeshtasticCoTBridge by lazy {
        MeshtasticCoTBridge(
            mesh = meshtastic,
            cotSink = { event -> contactStore.ingest(event) },
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
