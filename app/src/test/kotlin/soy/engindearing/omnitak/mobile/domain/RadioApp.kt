package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import soy.engindearing.omnitak.mobile.data.AdminTestFrames
import soy.engindearing.omnitak.mobile.data.AdminWriteResult
import soy.engindearing.omnitak.mobile.data.DeviceEdits
import soy.engindearing.omnitak.mobile.data.DeviceSettingsState
import soy.engindearing.omnitak.mobile.data.FakeRadio
import soy.engindearing.omnitak.mobile.data.MeshDeviceConfig

/**
 * The app as [soy.engindearing.omnitak.mobile.OmniTAKApp] wires it, connected to a [FakeRadio], for the tests that
 * follow a push through a restart or a lost link.
 *
 * Frames from the radio go in through [MeshtasticManager.dispatchFrame], the settings state the screen reads is fed by
 * the manager's reports, and the link is the fake radio: it answers reads, replaces whole messages on writes, keeps an
 * edit transaction open until it is committed or the radio restarts, and when it restarts does what the firmware does.
 * Everything the app sends goes through [sent], in order, as the link took it.
 *
 * Node numbers, names and keys are made up.
 */
internal class RadioApp(var radio: FakeRadio, var node: Int = 0x0A0B0C0D) {
    val mgr = MeshtasticManager()
    var state = DeviceSettingsState()
        private set

    /** False: the link refuses every frame. */
    var linkOpen = true

    /** The link refuses the n-th frame of the connection and every one after it (counted from 1), when set. */
    var failFromFrame: Int? = null
    private var frames = 0

    /** How long the link holds a frame before it takes it, in ms: a slow link, to see what goes out ahead of a frame. */
    var delayFrame: ((ByteArray) -> Long)? = null

    /** Every frame the link took on every connection, as the app sent it. */
    val sent: MutableList<ByteArray> = java.util.concurrent.CopyOnWriteArrayList()

    init {
        mgr.adminSendOverride = { frame ->
            frames++
            val refused = !linkOpen || failFromFrame?.let { frames >= it } == true
            delayFrame?.invoke(frame)?.takeIf { it > 0 }?.let { delay(it) }
            if (refused) {
                false
            } else {
                sent += frame
                radio.handle(frame) { admin, id ->
                    mgr.dispatchFrame(AdminTestFrames.packetFrame(from = node, to = node, portnum = 6, payload = admin, requestId = id.toInt()))
                }
                true
            }
        }
        mgr.adminResponseSink = { state = state.withReport(it) }
        mgr.linkDownSink = { state = state.withLinkDown() }
        mgr.settleQuietMs = 0
    }

    /** The app connects: the radio's download comes in. */
    fun connect(to: FakeRadio = radio, as_: Int = node): RadioApp {
        radio = to
        node = as_
        frames = 0
        mgr.onLinkState(ConnectionState.Connected("radio", useTLS = false), wasConnected = false)
        radio.download(node).forEach { mgr.dispatchFrame(it) }
        return this
    }

    /** The link drops and the radio keeps running. */
    fun drop(): RadioApp {
        mgr.onLinkState(ConnectionState.Disconnected, wasConnected = true)
        return this
    }

    /** The radio restarts: the link drops, and the firmware loads its config again. */
    fun restart(): RadioApp {
        radio.restart()
        return drop()
    }

    /** What the screen would send if the operator changed the draft as [change] says. */
    fun edits(change: MeshDeviceConfig.() -> MeshDeviceConfig): DeviceEdits = state.edits(state.draft.change())

    /** The line the screen shows next to the interval control with the draft changed as [change] says. */
    fun hint(change: MeshDeviceConfig.() -> MeshDeviceConfig = { this }): String? =
        state.positionIntervalHint(mgr.positionFacts.value, state.draft.change())

    /** What the screen does on Push: clear the old note, push, and read the radio again. */
    fun push(edits: DeviceEdits): AdminWriteResult = runBlocking {
        mgr.clearSettingsNotice()
        mgr.clearLastPushResult()
        mgr.pushDeviceConfig(edits).also { mgr.requestDeviceConfig() }
    }

    /** The position interval in the radio's own config, as the radio holds it in memory. */
    val held: Int
        get() = AdminTestFrames.fields(radio.config.getValue(2)).lastOrNull { it.number == 1 }?.varint?.toInt() ?: 0
}
