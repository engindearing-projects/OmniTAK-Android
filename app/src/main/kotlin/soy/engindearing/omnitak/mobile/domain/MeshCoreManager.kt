package soy.engindearing.omnitak.mobile.domain

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import soy.engindearing.omnitak.mobile.data.ChatMessage
import soy.engindearing.omnitak.mobile.data.ChatStatus
import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.CotXml
import soy.engindearing.omnitak.mobile.data.MeshCoreFrameCodec
import soy.engindearing.omnitak.mobile.data.MeshCoreUartClient
import soy.engindearing.omnitak.mobile.data.MeshNode

/**
 * Application-scoped MeshCore state holder — the MeshCore-side parallel to
 * [MeshtasticManager]. Owns a [MeshCoreUartClient] companion-BLE transport
 * and exposes a node table + connection state to the mesh screen + CoT
 * bridge through [MeshFrameworkManager].
 *
 * v1 scope:
 *  - On connect: run APP_START, then poll GET_NEXT_MSG (~2.5 s) to drain the
 *    node's inbound queue (this is exactly how the reference plugin behaves).
 *  - Inbound contacts/adverts → node table (positions → CoT PLI via the
 *    [MeshCoTBridge] + [soy.engindearing.omnitak.mobile.data.MeshCoreCoTConverter]).
 *  - Inbound text (contact DM / channel msg) → [chatSink] as a GeoChat.
 *  - Outbound self-position → SET_ADVERT_LATLON + SEND_SELF_ADVERT.
 *  - Outbound GeoChat → SEND_TXT_MSG to a target contact (pubkey lookup).
 *
 * NOT in scope (v2): AX.25 channel-datagram TAK interop.
 */
class MeshCoreManager(private val context: Context? = null) : MeshFrameworkManager {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _nodes = MutableStateFlow<Map<Long, MeshNode>>(emptyMap())
    override val nodes: StateFlow<Map<Long, MeshNode>> = _nodes.asStateFlow()

    /** nodeId → full pubkey hex, so outbound DMs can recover the 32-byte key
     *  the firmware matches on (we collapse ids to 6 bytes for the node map).
     *  ConcurrentHashMap: written from the BLE/frame thread, read from coroutine senders. */
    private val pubKeyByNodeId = ConcurrentHashMap<Long, String>()

    private var client: MeshCoreUartClient? = null
    private var frameCollector: Job? = null
    private var pollJob: Job? = null
    @Volatile private var connected = false

    @Volatile override var cotSink: ((CoTEvent) -> Unit)? = null
    @Volatile override var chatSink: ((ChatMessage) -> Unit)? = null

    /** Latest battery percent reported by the node, or -1. */
    @Volatile var batteryPercent: Int = -1
        private set

    /** This node's own pubkey hex (from APP_START self-info), or empty. */
    @Volatile var selfPubKeyHex: String = ""
        private set

    private val _activeTransport = MutableStateFlow(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    override val activeConnectionState: StateFlow<ConnectionState> =
        _activeTransport.flatMapLatest { active ->
            if (active) clientOrNull()?.state ?: flowOf(ConnectionState.Disconnected)
            else flowOf(ConnectionState.Disconnected)
        }.stateIn(scope, SharingStarted.Eagerly, ConnectionState.Disconnected)

    fun ensureBleReady(): Boolean = clientOrNull() != null

    private fun clientOrNull(): MeshCoreUartClient? {
        client?.let { return it }
        val ctx = context ?: return null
        return MeshCoreUartClient(ctx).also { client = it }
    }

    /** BLE link state for the MeshCore tab. */
    fun bleState(): StateFlow<ConnectionState>? = clientOrNull()?.state
    fun bleBytesReceived(): StateFlow<Long>? = clientOrNull()?.bytesReceived
    fun bleRssi(): StateFlow<Int>? = client?.rssi

    // region Scan ---------------------------------------------------------

    override suspend fun startMeshScan(timeoutMs: Long): Flow<MeshScanResult>? {
        val c = clientOrNull() ?: return null
        c.startScan(timeoutMs)
        return c.scanResults.map { MeshScanResult(it.name, it.address, it.rssi) }
    }

    /** Concrete-typed BLE scan for the MeshCore pane (parallels
     *  [MeshtasticManager.startBleScan]). Null when BLE is unavailable. */
    suspend fun startBleScan(timeoutMs: Long = 10_000): Flow<MeshCoreUartClient.BleScanResult>? {
        val c = clientOrNull() ?: return null
        c.startScan(timeoutMs)
        return c.scanResults
    }

    override fun stopMeshScan() {
        client?.stopScan()
    }

    // endregion

    // region Connect / Disconnect ----------------------------------------

    override suspend fun connectBle(deviceAddress: String): Boolean {
        val c = clientOrNull() ?: run {
            Log.w(TAG, "connectBle called but MeshCore client unavailable")
            return false
        }
        frameCollector?.cancel()
        _activeTransport.value = true
        frameCollector = scope.launch {
            c.frames.collect { frame -> handleFrame(frame) }
        }
        val ok = c.connectToAddress(deviceAddress)
        if (ok) {
            connected = true
            // Companion handshake: APP_START → self-info, then a steady
            // GET_NEXT_MSG poll loop draining contacts/adverts/messages.
            c.send(MeshCoreFrameCodec.buildAppStart())
            c.send(MeshCoreFrameCodec.buildDeviceQuery())
            c.send(MeshCoreFrameCodec.buildGetBattery())
            c.send(MeshCoreFrameCodec.buildGetNextMessage())
            startPollLoop()
            Log.i(TAG, "MeshCore connected — APP_START sent, polling started")
        } else {
            _activeTransport.value = false
            frameCollector?.cancel()
        }
        return ok
    }

    override fun disconnect() {
        connected = false
        pollJob?.cancel()
        pollJob = null
        frameCollector?.cancel()
        frameCollector = null
        client?.disconnect()
        _activeTransport.value = false
    }

    private fun startPollLoop() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive && connected) {
                delay(POLL_INTERVAL_MS)
                if (!connected) break
                client?.send(MeshCoreFrameCodec.buildGetNextMessage())
                // Periodically refresh battery + sample RSSI for the UI.
                client?.requestRssi()
            }
        }
    }

    // endregion

    // region Inbound ------------------------------------------------------

    private fun handleFrame(frame: ByteArray) {
        when (val d = MeshCoreFrameCodec.decode(frame)) {
            is MeshCoreFrameCodec.Decoded.MessagesWaiting -> {
                client?.let { c -> scope.launch { c.send(MeshCoreFrameCodec.buildGetNextMessage()) } }
            }
            is MeshCoreFrameCodec.Decoded.NoMoreMessages -> Unit
            is MeshCoreFrameCodec.Decoded.Contact -> {
                upsertContact(d.node, frame)
                // Drain the queue promptly while frames are flowing.
                client?.let { c -> scope.launch { c.send(MeshCoreFrameCodec.buildGetNextMessage()) } }
            }
            is MeshCoreFrameCodec.Decoded.SelfInfo -> {
                selfPubKeyHex = d.pubKeyHex
                Log.i(TAG, "MeshCore self-info node='${d.nodeName}' pubkey=${d.pubKeyHex.take(12)}…")
            }
            is MeshCoreFrameCodec.Decoded.Battery -> {
                batteryPercent = d.percent
                // Fold battery into known nodes? No — battery here is the
                // local node's, not a contact's. Just track it for the UI.
            }
            is MeshCoreFrameCodec.Decoded.ContactMessage -> {
                routeInboundText(d.senderPubKeyPrefixHex, d.text)
                client?.let { c -> scope.launch { c.send(MeshCoreFrameCodec.buildGetNextMessage()) } }
            }
            is MeshCoreFrameCodec.Decoded.ChannelMessage -> {
                routeInboundChannelText(d.channelIndex, d.text)
                client?.let { c -> scope.launch { c.send(MeshCoreFrameCodec.buildGetNextMessage()) } }
            }
            is MeshCoreFrameCodec.Decoded.DeviceInfo ->
                Log.i(TAG, "MeshCore device-info fwCode=${d.fwCode} maxContacts=${d.maxContacts}" +
                    " maxChannels=${d.maxChannels} mfg='${d.manufacturer}' ver='${d.version}'")
            is MeshCoreFrameCodec.Decoded.Unhandled ->
                Log.v(TAG, "MeshCore unhandled frame code=0x${Integer.toHexString(d.code)} (${frame.size}B)")
            null -> Log.v(TAG, "MeshCore empty/undecodable frame (${frame.size}B)")
        }
    }

    private fun upsertContact(node: MeshNode, advertFrame: ByteArray) {
        // Recover the full pubkey hex (bytes 1..32) so we can DM this contact.
        if (advertFrame.size >= 33) {
            val pubKeyHex = bytesToHex(advertFrame, 1, 32)
            if (pubKeyHex.isNotEmpty()) pubKeyByNodeId[node.id] = pubKeyHex
        }
        val existing = _nodes.value[node.id]
        val merged = if (existing != null) {
            node.copy(
                position = node.position ?: existing.position,
                snr = node.snr ?: existing.snr,
                hopDistance = node.hopDistance ?: existing.hopDistance,
                batteryLevel = node.batteryLevel ?: existing.batteryLevel,
                longName = node.longName.ifBlank { existing.longName },
                shortName = node.shortName.ifBlank { existing.shortName },
            )
        } else {
            node
        }
        _nodes.value = _nodes.value + (merged.id to merged)
    }

    /** Inbound native DM → GeoChat keyed by the sender's pubkey prefix. */
    private fun routeInboundText(senderPrefixHex: String, text: String) {
        if (text.isBlank()) return
        val nodeId = MeshCoreFrameCodec.nodeIdFromPubKey(senderPrefixHex)
        // Record the 6-byte prefix as a pubkey stand-in so outbound DM replies
        // can resolve the target even if the sender has never sent an advert.
        if (nodeId != 0L && senderPrefixHex.length >= 12) {
            pubKeyByNodeId.putIfAbsent(nodeId, senderPrefixHex)
        }
        val node = _nodes.value[nodeId]
        val callsign = node?.longName?.takeIf { it.isNotBlank() }
            ?: node?.shortName?.takeIf { it.isNotBlank() }
            ?: "MeshCore ${senderPrefixHex.take(8)}"
        val now = System.currentTimeMillis()
        val msg = ChatMessage(
            conversationId = meshCoreDmConversationId(nodeId),
            senderUid = "MESHCORE-${"%012X".format(nodeId)}",
            senderCallsign = callsign,
            text = text,
            timeIso = CotXml.isoSeconds(now),
            status = ChatStatus.RECEIVED,
            isFromSelf = false,
        )
        Log.i(TAG, "RX MeshCore DM from $callsign: $text")
        runCatching { chatSink?.invoke(msg) }
            .onFailure { Log.w(TAG, "chatSink failed: ${it.message}") }
    }

    private fun routeInboundChannelText(channelIndex: Int, text: String) {
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        val msg = ChatMessage(
            conversationId = meshCoreChannelConversationId(channelIndex),
            senderUid = "MESHCORE-CH$channelIndex",
            senderCallsign = "MeshCore Ch $channelIndex",
            text = text,
            timeIso = CotXml.isoSeconds(now),
            status = ChatStatus.RECEIVED,
            isFromSelf = false,
        )
        Log.i(TAG, "RX MeshCore channel $channelIndex text: $text")
        runCatching { chatSink?.invoke(msg) }
            .onFailure { Log.w(TAG, "chatSink failed: ${it.message}") }
    }

    fun meshCoreDmConversationId(nodeId: Long): String =
        "MESHCORE-DM-${"%012X".format(nodeId)}"

    fun meshCoreChannelConversationId(channelIndex: Int): String =
        "MESHCORE-CH$channelIndex"

    // endregion

    // region Outbound -----------------------------------------------------

    /**
     * Send a plain-text chat over MeshCore as a native pubkey-to-pubkey DM
     * ([MeshCoreFrameCodec.buildSendTxtMsg]). MeshCore has no AX.25-less
     * broadcast text in v1 scope, so a message with no resolvable target is
     * dropped (returns false) rather than guessed.
     *
     * [toNodeId] is the collapsed 4-byte node id; we map it back to the
     * contact's full pubkey to address the DM.
     */
    override suspend fun sendMeshChat(text: String, channelIndex: Int, toNodeId: UInt?): Boolean {
        if (text.isEmpty() || !connected) return false
        val targetId = toNodeId?.toLong()?.and(0xFFFFFFFFL)
        val pubKeyHex = targetId?.let { pubKeyByNodeId[it] }
        if (pubKeyHex == null) {
            Log.w(TAG, "sendMeshChat: no MeshCore contact pubkey for target=$toNodeId — dropping")
            return false
        }
        val cmd = MeshCoreFrameCodec.buildSendTxtMsg(pubKeyHex, text) ?: return false
        return client?.send(cmd) ?: false
    }

    /**
     * Bridge a CoT event onto MeshCore.
     *  - GeoChat (`b-t-f`): forwarded as a native DM when a target pubkey is
     *    known; otherwise dropped (no broadcast text in v1).
     *  - PLI / self-position (everything else): store the lat/lon in the
     *    node's advert (SET_ADVERT_LATLON) and broadcast it (SEND_SELF_ADVERT)
     *    so MeshCore peers plot this operator.
     */
    override suspend fun sendCoTOverMesh(event: CoTEvent, channelIndex: UInt): Boolean {
        if (!connected) return false
        if (event.type == "b-t-f") {
            // GeoChat — only DM is in scope; need a resolvable recipient.
            // CoT chat carries no MeshCore pubkey, so v1 cannot fan a server
            // GeoChat out to MeshCore peers. Skip rather than misroute.
            Log.v(TAG, "sendCoTOverMesh: MeshCore GeoChat fan-out not in v1 scope — skipping")
            return false
        }
        // Self/PLI position broadcast.
        val latLon = MeshCoreFrameCodec.buildSetAdvertLatLon(event.lat, event.lon, event.hae)
            ?: return false
        val c = client ?: return false
        val a = c.send(latLon)
        val b = c.send(MeshCoreFrameCodec.buildSendSelfAdvert())
        return a && b
    }

    // endregion

    private fun bytesToHex(src: ByteArray, off: Int, len: Int): String {
        if (off < 0 || len <= 0 || src.size < off + len) return ""
        val sb = StringBuilder(len * 2)
        for (i in off until off + len) {
            val v = src[i].toInt() and 0xFF
            if (v < 0x10) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    companion object {
        private const val TAG = "MeshCoreManager"
        private const val POLL_INTERVAL_MS = 2_500L
    }
}
