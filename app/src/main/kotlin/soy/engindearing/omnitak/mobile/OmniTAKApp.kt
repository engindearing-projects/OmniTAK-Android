package soy.engindearing.omnitak.mobile

import android.app.Application
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
    val contactStore: ContactStore by lazy { ContactStore() }
    val drawingStore: DrawingStore by lazy { DrawingStore() }
    val chatStore: ChatStore by lazy { ChatStore() }
    val meshtastic: MeshtasticManager by lazy {
        MeshtasticManager().also { mgr ->
            // Phase 4: portnum-72 ATAK-plugin payloads decode straight
            // into CoT events; route them into the same ingest sink the
            // node-table bridge uses so they surface as map contacts.
            mgr.cotSink = { event -> contactStore.ingest(event) }
        }
    }
    val userPrefsStore: UserPrefsStore by lazy { UserPrefsStore(this) }
    val serverManager: ServerManager by lazy {
        ServerManager(TAKServerStore(this), contactStore, chatStore)
    }

    /** Bridges Meshtastic node updates into the active server's CoT
     *  pipeline by feeding [ContactStore.ingest] (which is what every
     *  other CoT source already lands in). Started lazily on first
     *  access so we don't pay for it until the user opens the
     *  Meshtastic screen or connects to a radio. */
    val meshtasticCoTBridge: MeshtasticCoTBridge by lazy {
        MeshtasticCoTBridge(
            mesh = meshtastic,
            cotSink = { event -> contactStore.ingest(event) },
        ).also { it.start() }
    }
}
