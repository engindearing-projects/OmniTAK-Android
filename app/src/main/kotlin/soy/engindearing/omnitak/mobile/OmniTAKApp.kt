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
import soy.engindearing.omnitak.mobile.data.MeshOwnerNames
import soy.engindearing.omnitak.mobile.data.TAKServerStore
import soy.engindearing.omnitak.mobile.data.UserPrefsStore
import soy.engindearing.omnitak.mobile.domain.ChatStore
import soy.engindearing.omnitak.mobile.domain.ConfigBundleFetcher
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
        // First-launch only: copy any bundled demo data-package from
        // assets/ into the import dir so DataPackageBootstrap's existing
        // sideload path picks it up. Production builds bundle one
        // pointing at tak.engindearing.soy:8089; open-source builds
        // skip silently.
        seedBundledDemoPackageIfNeeded()

        // Sideload data-package zips dropped into <files-dir>/import/.
        // Touches `serverManager` (and through it `userPrefsStore`,
        // `certVault`), so kicking it off here also wakes those lazies.
        DataPackageBootstrap(this, certVault, serverManager).runIfNeeded()

        // Mirror operator callsign onto the connected mesh node so the
        // self-dedup in MeshtasticCoTBridge always has a clean name
        // match. Idempotent + gated by `autoSyncCallsignToMesh`.
        startMeshOwnerSync()

        // GAP-108 — server-driven config. If the operator set a
        // configBundleUrl (via Settings or a tak://preference QR), fetch
        // it now and apply any updated prefs or staged data package.
        // Fire-and-forget; failures log + toast at next surface.
        appScope.launch {
            val url = userPrefsStore.prefs.first().configBundleUrl
            if (url.isNotBlank()) {
                val res = ConfigBundleFetcher.runIfConfigured(
                    context = this@OmniTAKApp,
                    userPrefsStore = userPrefsStore,
                    certVault = certVault,
                    serverManager = serverManager,
                    url = url,
                )
                android.util.Log.i("OmniTAK", "Config bundle pull: $res")
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
    }

    // Application-scoped singletons. Screens reach these via
    // LocalContext.current.applicationContext as OmniTAKApp.
    private val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val contactStore: ContactStore by lazy { ContactStore() }
    val drawingStore: DrawingStore by lazy { DrawingStore() }
    val mapCameraStore: MapCameraStore by lazy { MapCameraStore() }
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
            // == S3:drawing-cot-receive BEGIN ==
            drawingStore = drawingStore,
            // == S3:drawing-cot-receive END ==
        )
    }

    private fun readDeviceBatteryPercent(): Int? {
        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (level in 0..100) level else null
    }

    private fun seedBundledDemoPackageIfNeeded() {
        val prefs = getSharedPreferences("omnitak_install", Context.MODE_PRIVATE)
        if (prefs.getBoolean("demoPackageSeeded", false)) return
        val importDir = getExternalFilesDir("import") ?: return
        if (!importDir.exists()) importDir.mkdirs()
        val target = java.io.File(importDir, "demo-package.zip")
        val imported = java.io.File(importDir, "demo-package.zip.imported")
        if (target.exists() || imported.exists()) {
            prefs.edit().putBoolean("demoPackageSeeded", true).apply()
            return
        }
        runCatching {
            assets.open("demo-package.zip").use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            prefs.edit().putBoolean("demoPackageSeeded", true).apply()
        }
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

    /**
     * GAP-130 (unified identity, half 1) — when the operator's TAK
     * callsign changes and a Meshtastic radio is connected, push the
     * new long/short owner name to the radio. This pairs with the
     * [MeshtasticCoTBridge.isSelfNode] dedup so the mesh node and the
     * TAK client always share a name — the operator sees themselves
     * once on the map regardless of mesh role (TAK or TAK_TRACKER).
     *
     * Gated by `userPrefs.autoSyncCallsignToMesh` (default true).
     * Skipped silently when no radio is connected; the next time a
     * transport comes online we re-sync because the flow re-emits the
     * (still-current) callsign on transport changes.
     */
    private fun startMeshOwnerSync() {
        appScope.launch {
            // Combine callsign + transport availability + the gating
            // toggle so we only push when (a) operator wants auto-sync,
            // (b) a callsign is set, and (c) a radio is reachable.
            kotlinx.coroutines.flow.combine(
                userPrefsStore.prefs,
                meshtastic.activeTransport,
            ) { prefs, transport -> Triple(prefs.autoSyncCallsignToMesh, prefs.callsign, transport) }
                .distinctUntilChanged()
                .collect { (enabled, callsign, transport) ->
                    if (!enabled || transport == null) return@collect
                    val derived = MeshOwnerNames.derive(callsign) ?: return@collect
                    val pushed = meshtastic.pushOwnerName(
                        longName = derived.first,
                        shortName = derived.second,
                    )
                    android.util.Log.i(
                        "OmniTAK",
                        "Mesh owner sync: ${derived.first} / ${derived.second} → $pushed",
                    )
                }
        }
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
            // TAK_TRACKER self-dedup — when our callsign matches the
            // mesh node's name (long or short), the node is our own and
            // its PPLI duplicates the SelfPositionBroadcaster output.
            // hideSelfFromMeshContacts gates this; default true.
            appScope.launch {
                userPrefsStore.prefs
                    .map { Pair(it.hideSelfFromMeshContacts, it.callsign.trim()) }
                    .distinctUntilChanged()
                    .collect { (hide, callsign) ->
                        bridge.isSelfNode = if (!hide || callsign.isBlank()) {
                            { false }
                        } else {
                            { node ->
                                node.longName.trim().equals(callsign, ignoreCase = true) ||
                                    node.shortName.trim().equals(callsign, ignoreCase = true)
                            }
                        }
                    }
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
