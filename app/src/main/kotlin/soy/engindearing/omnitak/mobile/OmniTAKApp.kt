package soy.engindearing.omnitak.mobile

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import soy.engindearing.omnitak.mobile.data.MeshDeviceConfigStore
import soy.engindearing.omnitak.mobile.data.TAKServerStore
import soy.engindearing.omnitak.mobile.data.UserPrefsStore
import soy.engindearing.omnitak.mobile.domain.ChatStore
import soy.engindearing.omnitak.mobile.domain.ContactStore
import soy.engindearing.omnitak.mobile.domain.DrawingStore
import soy.engindearing.omnitak.mobile.domain.MeshtasticCoTBridge
import soy.engindearing.omnitak.mobile.domain.MeshtasticManager
import soy.engindearing.omnitak.mobile.domain.ServerManager

class OmniTAKApp : Application() {
    // Application-scoped singletons. Screens reach these via
    // LocalContext.current.applicationContext as OmniTAKApp.
    private val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val contactStore: ContactStore by lazy { ContactStore() }
    val drawingStore: DrawingStore by lazy { DrawingStore() }
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
            mgr.adminResponseSink = { response ->
                appScope.launch { meshDeviceConfigStore.applyAdminResponse(response) }
            }
            // GAP-122 — ingest mesh text messages into the same ChatStore
            // the TAK GeoChat path uses, so the Chat tab shows mesh chat
            // alongside server chat. Conversation id is "MESH-CHn" per the
            // channel the message arrived on.
            mgr.chatSink = { msg ->
                chatStore.upsertConversationIfMissing(
                    id = msg.conversationId,
                    title = msg.conversationId.removePrefix("MESH-CH").let { ch ->
                        if (ch == "0") "Mesh: Primary" else "Mesh: Channel $ch"
                    },
                )
                chatStore.ingest(msg)
            }
        }
    }
    val meshDeviceConfigStore: MeshDeviceConfigStore by lazy { MeshDeviceConfigStore(this) }
    val userPrefsStore: UserPrefsStore by lazy { UserPrefsStore(this) }
    val serverManager: ServerManager by lazy {
        ServerManager(TAKServerStore(this), contactStore, chatStore)
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
