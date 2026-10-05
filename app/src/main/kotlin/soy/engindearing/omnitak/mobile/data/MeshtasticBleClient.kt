package soy.engindearing.omnitak.mobile.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.callback.FailCallback
import no.nordicsemi.android.ble.data.Data
import no.nordicsemi.android.ble.observer.BondingObserver
import no.nordicsemi.android.ble.observer.ConnectionObserver
import soy.engindearing.omnitak.mobile.domain.ConnectionState
import java.util.UUID

/**
 * BLE transport for a Meshtastic radio. Mirrors the iOS
 * `MeshtasticBLEClient` (CoreBluetooth) using the Nordic Android BLE
 * library.
 *
 * Wire protocol:
 *   - Connect to the device's GATT service [SERVICE_UUID].
 *   - Find characteristics: toRadio (write), fromRadio (read), fromNum
 *     (notify).
 *   - Each fromNum notification means "there's data waiting" — drain
 *     fromRadio with successive reads until an empty payload comes
 *     back.
 *   - As a safety net, also poll fromRadio every [POLL_INTERVAL_MS] ms
 *     (matches iOS 1.0s timer) — devices that miss a notification still
 *     get drained.
 *   - Outbound: chunk ToRadio at [CHUNK_SIZE_BYTES] using
 *     `WRITE_TYPE_NO_RESPONSE` if the characteristic supports it, else
 *     default with-response writes.
 *
 * The Phase 1 hand-rolled `MeshtasticProtoParser` handles each
 * fromRadio payload — so the consumer of [frames] is identical to the
 * TCP path.
 *
 * Link lifecycle (#203): every connect attempt gets its own Nordic
 * [BleManager] (a [Link]), and a link is never reused once it has failed
 * or dropped. A single long-lived manager used to be reset with `close()`
 * between attempts, but `close()` leaves the library's "operation in
 * progress" flag set when a request is still in flight. After one connect
 * that timed out with the GATT link already up, every later `connect()`
 * was parked behind that flag and never reached the Bluetooth stack: the
 * auto-reconnect loop kept counting attempts while nothing happened, until
 * the app was force-stopped. Callbacks from a link that is no longer the
 * current one are ignored.
 */
@SuppressLint("MissingPermission")
class MeshtasticBleClient(context: Context) {

    // region Public state ------------------------------------------------

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _frames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val frames: SharedFlow<ByteArray> = _frames.asSharedFlow()

    private val _rssi = MutableStateFlow(0)
    val rssi: StateFlow<Int> = _rssi.asStateFlow()

    private val _bytesReceived = MutableStateFlow(0L)
    val bytesReceived: StateFlow<Long> = _bytesReceived.asStateFlow()

    private val _scanResults = MutableSharedFlow<BleScanResult>(extraBufferCapacity = 64)
    val scanResults: SharedFlow<BleScanResult> = _scanResults.asSharedFlow()

    // #203 — BLE failure diagnostics, populated by the connect, link-loss,
    // init and read paths below. The last failure stays visible after the
    // link recovers (its relative time says how old it is), so a drop in
    // the night can still be read off the BLE pane in the morning.
    private val failures = BleFailureLog()
    val lastFailure: StateFlow<BleFailure?> = failures.last

    /** #203 — when true, Nordic DEBUG/VERBOSE log lines also print to
     *  Logcat. Every line lands in [logRingBufferSnapshot] regardless of
     *  this flag. Off by default; wired from the BLE pane's toggle via
     *  [soy.engindearing.omnitak.mobile.domain.MeshtasticManager.setVerboseBleLogging]. */
    @Volatile var verboseLoggingEnabled: Boolean = false

    // endregion

    // region Internal ----------------------------------------------------

    private val appContext: Context = context.applicationContext ?: context

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val bluetoothAdapter: BluetoothAdapter? = run {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        mgr?.adapter
    }

    private val linkLock = Any()

    /** The link for the current connect attempt or session. Guarded by [linkLock]. */
    private var link: Link? = null
    private var linkSeq = 0

    private val jobLock = Any()
    private var pollJob: Job? = null
    private var drainJob: Job? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val r = BleScanResult(
                name = scanDisplayName(
                    advertised = result.scanRecord?.deviceName,
                    cached = runCatching { result.device.name }.getOrNull(),
                ),
                address = result.device.address,
                rssi = result.rssi,
            )
            _scanResults.tryEmit(r)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed: $errorCode")
        }
    }

    private var scanning: Boolean = false

    // endregion

    // region Scan --------------------------------------------------------

    /**
     * Start a Meshtastic-service-filtered BLE scan. Results stream into
     * [scanResults]. Auto-stops after [timeoutMs].
     */
    suspend fun startScan(timeoutMs: Long = 10_000) {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            Log.w(TAG, "no BluetoothLeScanner — Bluetooth disabled?")
            return
        }
        if (scanning) return
        scanning = true
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching { scanner.startScan(listOf(filter), settings, scanCallback) }
            .onFailure {
                Log.w(TAG, "startScan threw: ${it.message}")
                scanning = false
                return
            }
        // Auto-stop.
        scope.launch {
            delay(timeoutMs)
            stopScan()
        }
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }
    }

    // endregion

    // region Link bookkeeping -------------------------------------------

    private fun currentLink(): Link? = synchronized(linkLock) { link }

    private fun isCurrent(l: Link): Boolean = synchronized(linkLock) { link === l }

    /** Makes [next] the current link and returns the one it replaced. */
    private fun swapLink(next: Link?): Link? = synchronized(linkLock) {
        val old = link
        link = next
        old
    }

    /** Stops [l] being the current link. False when something already replaced it. */
    private fun releaseLink(l: Link): Boolean = synchronized(linkLock) {
        if (link === l) {
            link = null
            true
        } else {
            false
        }
    }

    private fun newLink(address: String): Link = Link(address, synchronized(linkLock) { ++linkSeq })

    // endregion

    // region Connect / Disconnect ---------------------------------------

    /**
     * Connect to the radio at [address]. Suspends until the session is ready
     * or the attempt has failed; it always returns, and never leaves [state]
     * on Connecting.
     *
     * A newer call replaces an attempt that is still in flight: the older
     * one returns false and leaves [state] to the newer one.
     */
    suspend fun connectToAddress(address: String): Boolean {
        val adapter = bluetoothAdapter ?: return false
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return false

        val attempt = runCatching { newLink(address) }.getOrElse { e ->
            Log.w(TAG, "BLE link could not be created: ${e.message}")
            return false
        }
        swapLink(attempt)?.retire()
        stopBackgroundJobs()
        _state.value = ConnectionState.Connecting(address)

        val end = try {
            awaitConnect(attempt, device)
        } catch (e: CancellationException) {
            if (releaseLink(attempt)) _state.value = ConnectionState.Disconnected
            attempt.retire()
            throw e
        }

        // On success the link's observer (onLinkReady) already flipped us to
        // Connected and started the read loops.
        if (end is ConnectEnd.Ready && attempt.sessionUp && isCurrent(attempt)) return true

        val wasCurrent = releaseLink(attempt)
        val bondState = runCatching { device.bondState }.getOrDefault(BluetoothDevice.BOND_NONE)
        val servicesSeen = attempt.servicesSeen
        val facts = ConnectAttemptFacts(
            timedOut = end is ConnectEnd.TimedOut,
            failStatus = (end as? ConnectEnd.Failed)?.status,
            observerReason = attempt.observerReason,
            linkCameUp = attempt.cameUp,
            pairingSeen = attempt.pairingSeen,
            bondState = bondState,
            serviceMissing = servicesSeen != null,
            cacheRefreshed = attempt.cacheRefreshed,
            services = servicesSeen.orEmpty(),
        )
        attempt.retire()
        // Replaced by a newer attempt, disconnected by the operator, or the
        // session came up and dropped again before we got here: the state and
        // the failure record belong to whoever did that.
        if (!wasCurrent) return false
        stopBackgroundJobs()

        val failure = failures.record(describeConnectFailure(facts), bondState)
        Log.w(TAG, "BLE connect to $address failed: ${failure.message}")
        _state.value = resolveFailedConnectState(address, false, failure)
        return false
    }

    /**
     * Starts the connect on [l] and waits for it to end. Nordic reports
     * success and most failures itself; the loop here is the deadline. It is
     * [CONNECT_TIMEOUT_MS] normally and [PAIRING_TIMEOUT_MS] while Android is
     * pairing with the radio, so an operator reading the PIN off the radio's
     * screen is not cut off mid-entry.
     */
    private suspend fun awaitConnect(l: Link, device: BluetoothDevice): ConnectEnd = coroutineScope {
        val result = async {
            l.awaits.await<ConnectEnd>(onClosed = ConnectEnd.Closed) { complete ->
                starting("connect", { complete(ConnectEnd.Failed(FailCallback.REASON_REQUEST_FAILED)) }) {
                    l.startConnect(device, complete)
                }
            }
        }
        val startedAt = SystemClock.elapsedRealtime()
        var end: ConnectEnd? = null
        while (end == null) {
            end = withTimeoutOrNull(WATCHDOG_TICK_MS) { result.await() }
            if (end != null) break
            val bonding = runCatching { device.bondState == BluetoothDevice.BOND_BONDING }.getOrDefault(false)
            if (bonding) l.pairingSeen = true
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (shouldAbandonConnect(elapsed, bonding)) {
                result.cancel()
                end = ConnectEnd.TimedOut
            }
        }
        checkNotNull(end)
    }

    suspend fun disconnectClean() {
        val l = swapLink(null)
        stopBackgroundJobs()
        if (l != null) {
            if (l.sessionUp) {
                // Ask the radio for an orderly disconnect, but never wait on
                // it for long: the link may already be gone.
                withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) {
                    l.awaits.await(onClosed = Unit) { complete ->
                        starting("disconnect", { complete(Unit) }) { l.startDisconnect { complete(Unit) } }
                    }
                }
            }
            l.retire()
        }
        // A connect started while we were tearing down owns the state now.
        if (currentLink() == null) _state.value = ConnectionState.Disconnected
    }

    /**
     * [MeshTransport] view over this client. [MeshTransport.send] delegates
     * to [sendToRadio] (byte-identical Meshtastic behavior) and
     * [MeshTransport.disconnect] to a fire-and-forget [disconnectClean] on
     * this client's own IO scope — the same teardown the manager already
     * drives.
     */
    val asTransport: MeshTransport = object : MeshTransport {
        override val state: StateFlow<ConnectionState> get() = this@MeshtasticBleClient.state
        override val frames: SharedFlow<ByteArray> get() = this@MeshtasticBleClient.frames
        override suspend fun send(payload: ByteArray): Boolean = sendToRadio(payload)
        override fun disconnect() {
            scope.launch { disconnectClean() }
        }
    }

    // endregion

    // region Link events -------------------------------------------------

    /** Setup finished on [l]: hand the session to the app. */
    private fun onLinkReady(l: Link) {
        // A link that came up without the Meshtastic characteristics is not a
        // session; connectToAddress reports it as a failed attempt.
        if (!l.usable || !isCurrent(l)) return
        l.sessionUp = true
        failures.sessionCameUp()
        _rssi.value = 0
        _state.value = ConnectionState.Connected(l.address, useTLS = false)
        // Safety-net polling loop (iOS uses a 1.0s Timer; we mirror that),
        // plus a first drain: the radio may already have queued frames.
        startPollLoop(l)
        triggerDrain(l)
    }

    /** The GATT link of [l] is gone. */
    private fun onLinkDown(l: Link, device: BluetoothDevice, reason: Int) {
        // Before the session is up, connectToAddress owns the outcome.
        if (!l.sessionUp) return
        val wasCurrent = releaseLink(l)
        l.retire()
        // Torn down by us (operator disconnect, or replaced by a new attempt):
        // not a failure, and not this link's state to set.
        if (!wasCurrent) return
        stopBackgroundJobs()
        // Link loss, the radio hanging up, a supervision timeout: this is the
        // "drops after about an hour" field report and gets recorded.
        if (reason != ConnectionObserver.REASON_TERMINATE_LOCAL_HOST) {
            val bondState = runCatching { device.bondState }.getOrDefault(BluetoothDevice.BOND_NONE)
            failures.record(
                phase = BleFailure.Phase.LINK_LOSS,
                reason = reason,
                status = null,
                bondState = bondState,
                message = "link lost: reason=$reason",
                hint = sessionFailureHint(bondState, l.pairingSeen),
            )
        }
        _state.value = ConnectionState.Disconnected
    }

    /**
     * Runs [start], which hands a request to the Bluetooth stack. The stack
     * can refuse outright (the Nearby devices permission revoked while a
     * reconnect loop is running throws a SecurityException); that is a
     * failed request, reported through [onRefused], not a crash.
     */
    private inline fun starting(what: String, onRefused: () -> Unit, start: () -> Unit) {
        try {
            start()
        } catch (e: Exception) {
            Log.w(TAG, "BLE $what could not start: ${e.message}")
            onRefused()
        }
    }

    // endregion

    // region TX ---------------------------------------------------------

    /**
     * Write a serialized ToRadio protobuf to the radio. Splits into
     * chunks of [CHUNK_SIZE_BYTES] if needed and uses NO_RESPONSE
     * writes when the characteristic supports them (faster). Returns
     * false when there is no session, or as soon as the link drops.
     */
    suspend fun sendToRadio(bytes: ByteArray): Boolean {
        val l = currentLink() ?: return false
        if (!l.sessionUp) return false
        val ch = l.toRadio ?: return false
        val noResp = ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val writeType = if (noResp) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        val chunks = chunkPayload(bytes, CHUNK_SIZE_BYTES)
        for (chunk in chunks) {
            val ok = l.awaits.await(onClosed = false) { complete ->
                starting("write", { complete(false) }) { l.startWrite(ch, chunk, writeType, complete) }
            }
            if (!ok) return false
        }
        return true
    }

    // endregion

    // region Drain / Poll loops -----------------------------------------

    private fun triggerDrain(l: Link) {
        synchronized(jobLock) {
            if (drainJob?.isActive == true) return
            drainJob = scope.launch { drainFromRadio(l) }
        }
    }

    /**
     * Read fromRadio repeatedly until an empty payload is returned.
     * Each non-empty read goes to [_frames] (raw FromRadio protobuf
     * bytes, which the parser turns into a `FromRadioFrame`).
     */
    private suspend fun drainFromRadio(l: Link) {
        repeat(MAX_DRAIN_PER_BATCH) {
            val ch = l.fromRadio ?: return
            val data = readOnce(l, ch) ?: return
            val payload = data.value ?: ByteArray(0)
            if (payload.isEmpty()) {
                // The radio answered a moment ago and nothing is queued: the
                // one point where an RSSI read cannot hold anything up.
                l.sampleRssiIfDue { rssi -> _rssi.value = rssi }
                return
            }
            _bytesReceived.value += payload.size.toLong()
            _frames.tryEmit(payload)
        }
    }

    /**
     * One fromRadio read, or null when it failed, timed out or the link went
     * away. The read runs inside an atomic request queue because a bare
     * `readCharacteristic()` cannot carry a timeout
     * ([no.nordicsemi.android.ble.ReadRequest] is not a `TimeoutableRequest`),
     * and a read that never gets its GATT callback would hold up every
     * request queued behind it.
     */
    private suspend fun readOnce(l: Link, ch: BluetoothGattCharacteristic): Data? =
        l.awaits.await<Data?>(onClosed = null) { complete ->
            starting("read", { complete(null) }) {
                l.startRead(
                    ch = ch,
                    onData = { data -> complete(data) },
                    onFail = { device, status ->
                        if (status == FailCallback.REASON_TIMEOUT && isCurrent(l)) {
                            val bondState = runCatching { device.bondState }.getOrDefault(BluetoothDevice.BOND_NONE)
                            failures.record(
                                phase = BleFailure.Phase.READ_TIMEOUT,
                                reason = status,
                                status = null,
                                bondState = bondState,
                                message = "fromRadio read timed out after ${READ_TIMEOUT_MS}ms",
                                hint = sessionFailureHint(bondState, l.pairingSeen),
                            )
                        }
                        Log.w(TAG, "fromRadio read failed: $status")
                        complete(null)
                    },
                )
            }
        }

    private fun startPollLoop(l: Link) {
        synchronized(jobLock) {
            pollJob?.cancel()
            pollJob = scope.launch {
                while (isActive && isCurrent(l)) {
                    delay(POLL_INTERVAL_MS)
                    if (_state.value !is ConnectionState.Connected) continue
                    triggerDrain(l)
                }
            }
        }
    }

    private fun stopBackgroundJobs() {
        synchronized(jobLock) {
            pollJob?.cancel(); pollJob = null
            drainJob?.cancel(); drainJob = null
        }
    }

    // endregion

    // region Link (one Nordic manager per connect attempt) ---------------

    /** How a connect attempt ended. */
    private sealed interface ConnectEnd {
        /** Nordic finished setup. */
        data object Ready : ConnectEnd

        /** Nordic reported a failure with this FailCallback status. */
        data class Failed(val status: Int) : ConnectEnd

        /** Our deadline passed first. */
        data object TimedOut : ConnectEnd

        /** The link was retired while the connect was in flight. */
        data object Closed : ConnectEnd
    }

    /**
     * One connect attempt and, if it succeeds, the session that follows.
     * Never reused: see the class comment.
     */
    private inner class Link(val address: String, private val seq: Int) : BleManager(appContext) {

        val awaits = BleLinkAwaits()

        @Volatile var toRadio: BluetoothGattCharacteristic? = null
        @Volatile var fromRadio: BluetoothGattCharacteristic? = null
        @Volatile var fromNum: BluetoothGattCharacteristic? = null

        /** The GATT link came up at least once. */
        @Volatile var cameUp = false

        /** Android was pairing with the radio at some point on this link. */
        @Volatile var pairingSeen = false

        /** Setup finished and the session was handed to the app. */
        @Volatile var sessionUp = false

        /** The last reason the Nordic connection observer gave, if any. */
        @Volatile var observerReason: Int? = null

        /** Service UUIDs from the last discovery that lacked the Meshtastic
         *  service or its characteristics; null when the last one had them. */
        @Volatile var servicesSeen: List<String>? = null

        @Volatile private var cacheRefreshRequested = false

        @Volatile private var rssiPending = false
        @Volatile private var rssiUnanswered = false
        @Volatile private var lastRssiAt = 0L

        /** Android's service cache for this radio was refreshed on this link,
         *  so what discovery returned afterwards came from the radio itself. */
        @Volatile var cacheRefreshed = false

        /** Need toRadio to send and at least one of fromRadio/fromNum to receive. */
        val usable: Boolean get() = toRadio != null && (fromRadio != null || fromNum != null)

        init {
            setConnectionObserver(object : ConnectionObserver {
                override fun onDeviceConnecting(device: BluetoothDevice) = Unit
                override fun onDeviceConnected(device: BluetoothDevice) {
                    // Wait for service discovery before flipping to Connected.
                    cameUp = true
                }
                override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
                    observerReason = reason
                }
                override fun onDeviceReady(device: BluetoothDevice) = onLinkReady(this@Link)
                override fun onDeviceDisconnecting(device: BluetoothDevice) = Unit
                override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                    observerReason = reason
                    onLinkDown(this@Link, device, reason)
                }
            })
            setBondingObserver(object : BondingObserver {
                override fun onBondingRequired(device: BluetoothDevice) {
                    pairingSeen = true
                }
                override fun onBonded(device: BluetoothDevice) = Unit
                override fun onBondingFailed(device: BluetoothDevice) {
                    pairingSeen = true
                }
            })
        }

        // region requests

        fun startConnect(device: BluetoothDevice, complete: (ConnectEnd) -> Unit) {
            connect(device)
                // Backstop only: awaitConnect's own deadline always fires first.
                .timeout(NORDIC_BACKSTOP_TIMEOUT_MS)
                .retry(2, 200)
                .useAutoConnect(false)
                .done { complete(ConnectEnd.Ready) }
                .fail { _, status -> complete(ConnectEnd.Failed(status)) }
                .enqueue()
        }

        fun startDisconnect(onEnd: () -> Unit) {
            disconnect()
                .done { onEnd() }
                .fail { _, _ -> onEnd() }
                .enqueue()
        }

        fun startWrite(
            ch: BluetoothGattCharacteristic,
            chunk: ByteArray,
            writeType: Int,
            complete: (Boolean) -> Unit,
        ) {
            writeCharacteristic(ch, chunk, writeType)
                .done { complete(true) }
                .fail { _, status ->
                    Log.w(TAG, "writeCharacteristic failed: $status")
                    complete(false)
                }
                .enqueue()
        }

        fun startRead(
            ch: BluetoothGattCharacteristic,
            onData: (Data) -> Unit,
            onFail: (BluetoothDevice, Int) -> Unit,
        ) {
            beginAtomicRequestQueue()
                .add(readCharacteristic(ch).with { _, data -> onData(data) })
                .timeout(READ_TIMEOUT_MS)
                .fail { device, status -> onFail(device, status) }
                .enqueue()
        }

        /**
         * Samples RSSI for the link-status UI, at most every
         * [RSSI_INTERVAL_MS], one request at a time, and never again on this
         * link once a read goes unanswered.
         *
         * #203 — an RSSI read shares the request queue with the fromRadio
         * reads and the toRadio writes, and Nordic waits a full second for
         * one that gets no callback. Android stops answering RSSI reads after
         * one is issued on a link that is going down (seen on a Pixel 6a,
         * Android 17: 0 of 93 answered in the session after a radio reboot).
         * Asking every second, as this used to, then put a one-second stall
         * in front of every frame: the config download that takes 6 s on a
         * healthy link took 182 s after the reconnect.
         */
        fun sampleRssiIfDue(onRssi: (Int) -> Unit) {
            if (rssiPending || rssiUnanswered) return
            val now = SystemClock.elapsedRealtime()
            // The first call only starts the clock, so the first sample comes
            // after the handshake and the config download, not in front of them.
            val last = lastRssiAt
            lastRssiAt = if (last == 0L) now else last
            if (last == 0L || now - last < RSSI_INTERVAL_MS) return
            lastRssiAt = now
            rssiPending = true
            runCatching {
                readRssi()
                    .with { _, rssi -> onRssi(rssi) }
                    .done { rssiPending = false }
                    .fail { _, status ->
                        rssiPending = false
                        rssiUnanswered = true
                        onRssi(0)
                        log(Log.WARN, "RSSI read not answered (status=$status); no more RSSI sampling on this link")
                    }
                    .enqueue()
            }.onFailure { rssiPending = false }
        }

        /**
         * Ends this link for good: pending awaits return, the request in
         * flight is cancelled, the GATT client and the library's broadcast
         * receivers are released. Safe to call more than once.
         */
        fun retire() {
            awaits.close()
            runCatching { cancelQueue() }
            runCatching { close() }
        }

        // endregion

        // region BleManager hooks

        override fun getMinLogPriority(): Int = Log.VERBOSE

        override fun log(priority: Int, message: String) {
            // #203 — buffer every line (VERBOSE and up, per getMinLogPriority)
            // regardless of the toggle below, so "Copy diagnostics" has recent
            // BLE chatter even when verbose forwarding to Logcat was off when
            // the failure happened. The prefix is the attempt number.
            val line = "#$seq $message"
            appendLogLine(priority, line)
            if (priority >= Log.INFO) {
                Log.println(priority, TAG, line)
            } else if (verboseLoggingEnabled) {
                // Only DEBUG/VERBOSE land here (INFO+ is handled above).
                Log.d(TAG, line)
            }
        }

        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            val service = gatt.getService(SERVICE_UUID)
            toRadio = service?.getCharacteristic(TO_RADIO_UUID)
            fromRadio = service?.getCharacteristic(FROM_RADIO_UUID)
            fromNum = service?.getCharacteristic(FROM_NUM_UUID)
            if (usable) {
                servicesSeen = null
                log(
                    Log.INFO,
                    "Meshtastic service: toRadio=${toRadio != null} fromRadio=${fromRadio != null} " +
                        "fromNum=${fromNum != null} (${service?.characteristics?.size ?: 0} characteristics)",
                )
                return true
            }
            // #203 — record exactly what WAS discovered: that is what tells
            // "wrong radio" from "stale cache" (unrelated services present)
            // from "nothing came back at all".
            val seen = gatt.services.map { it.uuid.toString() }
            servicesSeen = seen
            if (!cacheRefreshRequested) {
                // First look on this link, and Android may have answered from
                // its cache (see initialize). Accept for now; the real answer
                // comes when Nordic calls back here after the refresh.
                log(Log.WARN, "Meshtastic service not in the first service list (${seen.size} services), refreshing")
                return true
            }
            log(Log.WARN, "Meshtastic service not found; services: ${seen.joinToString()}")
            return false
        }

        override fun initialize() {
            if (!cacheRefreshRequested) {
                cacheRefreshRequested = true
                // #203 — Android keeps a bonded radio's service table on disk
                // and, for a radio without robust caching (Meshtastic on the
                // ESP32 is one), trusts it on every later connect: discovery
                // is skipped. After the radio is reflashed, or updated to a
                // firmware with a different table, that copy is wrong; a board
                // moved from MeshCore to Meshtastic never showed the Meshtastic
                // service again until it was forgotten and re-paired. So the
                // first service list on a link is not trusted: refresh the
                // cache, and Nordic discovers again and re-runs
                // isRequiredServiceSupported() and initialize().
                refreshDeviceCache()
                    .done { cacheRefreshed = true }
                    .fail { _, status ->
                        log(Log.WARN, "service cache refresh did not run (status=$status); keeping the first service list")
                    }
                    .enqueue()
                // What follows is queued behind the refresh. Nordic drops it
                // when the refresh works (this method runs again after the
                // new discovery) and runs it as queued when it does not.
            }

            // Negotiate MTU first so chunking has headroom.
            requestMtu(REQUESTED_MTU)
                .with { _, mtu -> Log.i(TAG, "MTU negotiated: $mtu") }
                .enqueue()

            // Subscribe to fromNum notifications — each notification is
            // a "data waiting" signal; drain fromRadio when fired.
            fromNum?.let { ch ->
                setNotificationCallback(ch).with { _, data ->
                    Log.v(TAG, "fromNum notify (${data.size()}B) → drain")
                    if (sessionUp) triggerDrain(this@Link)
                }
                enableNotifications(ch)
                    .fail { device, status ->
                        Log.w(TAG, "fromNum notify enable failed: $status")
                        if (isCurrent(this@Link)) {
                            failures.record(
                                phase = BleFailure.Phase.INIT,
                                reason = status,
                                status = null,
                                bondState = device.bondState,
                                message = "fromNum notification enable failed: status=$status",
                                hint = sessionFailureHint(device.bondState, pairingSeen),
                            )
                        }
                    }
                    .enqueue()
            }
        }

        override fun onServicesInvalidated() {
            toRadio = null
            fromRadio = null
            fromNum = null
        }

        // endregion
    }

    // endregion

    // region Failure diagnostics (#203) -----------------------------------

    private val logRingBufferLock = Any()
    private val logRingBuffer = ArrayDeque<String>()

    private fun appendLogLine(priority: Int, message: String) {
        val line = "${System.currentTimeMillis()} ${logPriorityTag(priority)} $message"
        synchronized(logRingBufferLock) {
            logRingBuffer.addLast(line)
            while (logRingBuffer.size > LOG_RING_BUFFER_SIZE) logRingBuffer.removeFirst()
        }
    }

    private fun logPriorityTag(priority: Int): String = when (priority) {
        Log.VERBOSE -> "V"
        Log.DEBUG -> "D"
        Log.INFO -> "I"
        Log.WARN -> "W"
        Log.ERROR -> "E"
        else -> "?"
    }

    /** Snapshot of the last [LOG_RING_BUFFER_SIZE] Nordic BLE log lines
     *  (timestamped, epoch ms), oldest first. Populated regardless of
     *  [verboseLoggingEnabled]. */
    fun logRingBufferSnapshot(): List<String> = synchronized(logRingBufferLock) { logRingBuffer.toList() }

    /** Text block for the BLE pane's "Copy diagnostics" action: the last
     *  recorded failure (if any) plus the log ring buffer. */
    fun diagnosticsSnapshot(nowMs: Long = System.currentTimeMillis()): String =
        formatDiagnostics(failures.last.value, logRingBufferSnapshot(), nowMs)

    // endregion

    // region Scan result ------------------------------------------------

    data class BleScanResult(
        val name: String?,
        val address: String,
        val rssi: Int,
    )

    // endregion

    // region Failure model (#203) -----------------------------------------

    /**
     * A single recorded BLE failure — connect, service-discovery, init,
     * link-loss, or read-timeout. [consecutiveFailures] tracks repeats of the
     * *same* phase+reason+status so the UI can show "this has happened N
     * times in a row" instead of just the latest one (the field report is a
     * repeating failure, not a one-off).
     */
    data class BleFailure(
        val timestampMs: Long,
        val phase: Phase,
        val nordicReason: Int,
        val gattStatus: Int?,
        val bondState: Int,
        val consecutiveFailures: Int,
        val message: String,
        val discoveredServices: List<String> = emptyList(),
        /** What the operator can do about it, when we know; shown under the
         *  message on the BLE pane. */
        val hint: String? = null,
    ) {
        enum class Phase { CONNECT, SERVICE_DISCOVERY, INIT, LINK_LOSS, READ_TIMEOUT }

        /** "Last failure: <phase> reason=<n> status=<n or ->, bond=<NONE/BONDING/BONDED>, <relative time>" */
        fun summaryLine(nowMs: Long = System.currentTimeMillis()): String {
            val statusStr = gattStatus?.toString() ?: "-"
            val bond = when (bondState) {
                BluetoothDevice.BOND_BONDED -> "BONDED"
                BluetoothDevice.BOND_BONDING -> "BONDING"
                else -> "NONE"
            }
            return "Last failure: $phase reason=$nordicReason status=$statusStr, bond=$bond, " +
                relativeTime(timestampMs, nowMs)
        }

        companion object {
            /**
             * Pure builder — lives here (rather than inline at each call
             * site) so the consecutive-failure counting is unit-testable
             * without a live BLE stack. [previous] is the client's current
             * [lastFailure] value; a new failure with the same
             * phase+reason+status signature increments
             * [BleFailure.consecutiveFailures], anything else restarts it at 1.
             */
            fun next(
                previous: BleFailure?,
                phase: Phase,
                reason: Int,
                status: Int?,
                bondState: Int,
                nowMs: Long,
                message: String,
                services: List<String> = emptyList(),
                hint: String? = null,
            ): BleFailure {
                val sameSignature = previous != null &&
                    previous.phase == phase &&
                    previous.nordicReason == reason &&
                    previous.gattStatus == status
                return BleFailure(
                    timestampMs = nowMs,
                    phase = phase,
                    nordicReason = reason,
                    gattStatus = status,
                    bondState = bondState,
                    consecutiveFailures = if (sameSignature) previous!!.consecutiveFailures + 1 else 1,
                    message = message,
                    discoveredServices = services,
                    hint = hint,
                )
            }

            /** "3s ago" / "4m ago" / "2h ago" / "1d ago"; mirrors
             *  `ui.screens.relativeTime` but works in epoch-ms since
             *  [BleFailure.timestampMs] is ms, not seconds, and this (data
             *  layer) class shouldn't depend on the ui.screens package. */
            fun relativeTime(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
                if (timestampMs <= 0) return "—"
                val seconds = (nowMs - timestampMs) / 1_000
                return when {
                    seconds < 0 -> "just now"
                    seconds < 60 -> "${seconds}s ago"
                    seconds < 3_600 -> "${seconds / 60}m ago"
                    seconds < 86_400 -> "${seconds / 3_600}h ago"
                    else -> "${seconds / 86_400}d ago"
                }
            }
        }
    }

    /**
     * The last recorded failure and its streak. A session coming up does
     * not clear the failure (the BLE pane keeps showing what went wrong and
     * how long ago), but the next failure after it starts a new streak.
     */
    internal class BleFailureLog {
        private val _last = MutableStateFlow<BleFailure?>(null)
        val last: StateFlow<BleFailure?> = _last.asStateFlow()

        private var sessionSinceLast = false

        @Synchronized
        fun record(
            phase: BleFailure.Phase,
            reason: Int,
            status: Int?,
            bondState: Int,
            message: String,
            services: List<String> = emptyList(),
            hint: String? = null,
            nowMs: Long = System.currentTimeMillis(),
        ): BleFailure {
            val failure = BleFailure.next(
                previous = if (sessionSinceLast) null else _last.value,
                phase = phase,
                reason = reason,
                status = status,
                bondState = bondState,
                nowMs = nowMs,
                message = message,
                services = services,
                hint = hint,
            )
            sessionSinceLast = false
            _last.value = failure
            return failure
        }

        fun record(draft: FailureDraft, bondState: Int, nowMs: Long = System.currentTimeMillis()): BleFailure =
            record(
                phase = draft.phase,
                reason = draft.reason,
                status = draft.status,
                bondState = bondState,
                message = draft.message,
                services = draft.services,
                hint = draft.hint,
                nowMs = nowMs,
            )

        @Synchronized
        fun sessionCameUp() {
            sessionSinceLast = true
        }
    }

    /** What a connect attempt knew when it ended without a session. */
    internal data class ConnectAttemptFacts(
        /** Our own deadline passed (see [shouldAbandonConnect]). */
        val timedOut: Boolean,
        /** Nordic `FailCallback` status, when Nordic reported the failure. */
        val failStatus: Int?,
        /** Nordic `ConnectionObserver` reason, when one arrived. */
        val observerReason: Int?,
        /** The GATT link itself came up. */
        val linkCameUp: Boolean,
        /** Android was pairing with the radio during the attempt. */
        val pairingSeen: Boolean,
        val bondState: Int,
        /** The last discovery lacked the Meshtastic service or its characteristics. */
        val serviceMissing: Boolean,
        /** Android's service cache was refreshed before that discovery. */
        val cacheRefreshed: Boolean,
        val services: List<String> = emptyList(),
    )

    /** A failure worked out from [ConnectAttemptFacts], ready to record. */
    internal data class FailureDraft(
        val phase: BleFailure.Phase,
        val reason: Int,
        val status: Int?,
        val message: String,
        val hint: String?,
        val services: List<String> = emptyList(),
    )

    // endregion

    companion object {
        private const val TAG = "MeshBle"

        /**
         * #175 — pure decision for the post-connect state when a connect attempt
         * did NOT succeed. Lives in the companion so it is unit-testable without
         * a live BLE stack.
         *
         * @param attemptResult `false` = the attempt failed (Nordic reported it,
         *   or our own deadline passed); `null` = it ended with nothing to
         *   report. Neither is a state that would leave the user pinned in
         *   "Connecting": a failure surfaces its reason and the next connect
         *   starts from a new link, and `null` resets to
         *   [ConnectionState.Disconnected].
         * @param failure #203 — the failure recorded for this attempt. Its
         *   message wins over the generic placeholder below so the operator —
         *   and the "Last failure" row on the BLE pane — see the real reason
         *   instead of a bare "connect failed for $address".
         */
        internal fun resolveFailedConnectState(
            address: String,
            attemptResult: Boolean?,
            failure: BleFailure? = null,
        ): ConnectionState = when (attemptResult) {
            null -> ConnectionState.Disconnected
            false -> ConnectionState.Failed(failure?.message ?: "connect failed for $address")
            true -> ConnectionState.Disconnected // not used on the failure path
        }

        /**
         * #208 — resolves the human-readable name to show for a Meshtastic
         * BLE link, preferring the most authoritative source available:
         *
         *  1. [longName] — the node's real display name, broadcast in
         *     NodeInfo for our own node once it arrives. Not known until a
         *     moment after connect.
         *  2. [advertisedName] — the BLE advertisement / `BluetoothDevice`
         *     name captured when the operator picked the radio from the
         *     scan list. Known immediately, but sometimes just a firmware
         *     default (e.g. "Meshtastic_ab12") that never reflects the
         *     operator's actual configured name.
         *  3. [address] — the bare MAC, only when neither of the above is
         *     known yet (e.g. right at the start of a fresh connect before
         *     any scan result was captured).
         *
         * Blank strings (empty or all-whitespace) are treated as absent, not
         * as a valid name to show.
         */
        fun resolveDisplayName(advertisedName: String?, longName: String?, address: String): String {
            if (!longName.isNullOrBlank()) return longName
            if (!advertisedName.isNullOrBlank()) return advertisedName
            return address
        }

        // Meshtastic GATT service & characteristic UUIDs. Matches the
        // canonical service the Meshtastic firmware advertises.
        val SERVICE_UUID: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
        val TO_RADIO_UUID: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
        val FROM_RADIO_UUID: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
        // `FROMNUM_UUID` in the firmware's src/BluetoothCommon.h (and in the
        // Python client's ble_interface.py). This ended in …de15e6 until #203,
        // which matches nothing on the radio: fromNum was never found, its
        // notifications were never enabled, and the link ran on the
        // once-a-second poll alone.
        val FROM_NUM_UUID: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")

        const val REQUESTED_MTU: Int = 512
        const val CHUNK_SIZE_BYTES: Int = 500

        /** How long a connect attempt may take when no pairing is going on. */
        internal const val CONNECT_TIMEOUT_MS: Long = 15_000

        /** #203 — how long a connect attempt may take while Android is pairing
         *  with the radio. Reading a six-digit PIN off the radio and typing it
         *  in does not fit in [CONNECT_TIMEOUT_MS]; the attempt used to be torn
         *  down mid-entry, which also dismissed the pairing request. */
        internal const val PAIRING_TIMEOUT_MS: Long = 60_000

        /** Nordic's own connect timeout. Deliberately above both deadlines
         *  above: [awaitConnect] decides when an attempt is over, and this
         *  only matters if that loop is somehow not running. */
        internal const val NORDIC_BACKSTOP_TIMEOUT_MS: Long = 75_000

        private const val WATCHDOG_TICK_MS: Long = 1_000
        private const val DISCONNECT_TIMEOUT_MS: Long = 3_000

        /** #175/#203 — whether a connect attempt that is [elapsedMs] old and
         *  not ready yet should be given up. Pure, so the deadline is
         *  unit-testable. */
        internal fun shouldAbandonConnect(elapsedMs: Long, bonding: Boolean): Boolean =
            elapsedMs >= if (bonding) PAIRING_TIMEOUT_MS else CONNECT_TIMEOUT_MS

        internal const val HINT_NO_ANSWER =
            "Check that the radio is on, in range, and not connected to another phone."
        internal const val HINT_PAIRING =
            "Pairing needed. Open the pairing request on this phone (it may be in the notifications) " +
                "and enter the PIN shown on the radio."
        internal const val HINT_REPAIR =
            "The radio answered but the secure link did not come up. If this keeps happening, " +
                "forget the radio in Android Bluetooth settings and pair again."
        internal const val HINT_NOT_MESHTASTIC =
            "This device is not offering the Meshtastic service. Check that the radio runs Meshtastic firmware."
        internal const val HINT_STALE_SERVICES =
            "Android may be holding an old service list for this radio. " +
                "Forget the radio in Android Bluetooth settings, then connect again."
        internal const val HINT_BLUETOOTH_OFF = "Bluetooth is off on this phone."

        /**
         * #203 — turns what a failed connect attempt knew into the failure to
         * record: which phase, which reason, and what the operator can do.
         * Pure, so every branch is unit-testable.
         */
        internal fun describeConnectFailure(f: ConnectAttemptFacts): FailureDraft {
            val pairing = f.pairingSeen || f.bondState == BluetoothDevice.BOND_BONDING
            return when {
                f.serviceMissing && !f.timedOut -> FailureDraft(
                    phase = BleFailure.Phase.SERVICE_DISCOVERY,
                    reason = ConnectionObserver.REASON_NOT_SUPPORTED,
                    status = null,
                    message = "Meshtastic service not found on this device",
                    hint = if (f.cacheRefreshed) HINT_NOT_MESHTASTIC else HINT_STALE_SERVICES,
                    services = f.services,
                )
                f.timedOut -> FailureDraft(
                    phase = BleFailure.Phase.CONNECT,
                    reason = ConnectionObserver.REASON_TIMEOUT,
                    status = null,
                    message = when {
                        !f.linkCameUp -> "no answer from the radio"
                        pairing -> "pairing did not finish"
                        else -> "connected, but setup did not finish"
                    },
                    hint = when {
                        !f.linkCameUp -> HINT_NO_ANSWER
                        pairing -> HINT_PAIRING
                        f.bondState == BluetoothDevice.BOND_BONDED -> HINT_REPAIR
                        else -> null
                    },
                )
                else -> {
                    val status = f.failStatus
                    val reason = f.observerReason ?: status ?: ConnectionObserver.REASON_UNKNOWN
                    // Positive statuses are GATT/HCI codes; negative ones are
                    // Nordic's own FailCallback reasons.
                    val gattStatus = status?.takeIf { it > 0 }
                    FailureDraft(
                        phase = BleFailure.Phase.CONNECT,
                        reason = reason,
                        status = gattStatus,
                        message = "connect failed: reason=$reason" +
                            (if (gattStatus != null && gattStatus != reason) ", status=$gattStatus" else ""),
                        hint = when {
                            status == FailCallback.REASON_BLUETOOTH_DISABLED -> HINT_BLUETOOTH_OFF
                            f.linkCameUp && pairing -> HINT_PAIRING
                            else -> null
                        },
                    )
                }
            }
        }

        /**
         * #203 — hint for a failure inside a session (a read that timed out,
         * a notification subscribe that failed, a link that dropped). Android
         * raises the pairing request as a notification when the app is what
         * triggered it, so a first connect sits on "Connected" with no data
         * until someone looks there.
         */
        internal fun sessionFailureHint(bondState: Int, pairingSeen: Boolean): String? = when {
            bondState == BluetoothDevice.BOND_BONDING -> HINT_PAIRING
            pairingSeen && bondState != BluetoothDevice.BOND_BONDED -> HINT_PAIRING
            else -> null
        }

        /** The name to list a scanned radio under: what it is advertising
         *  right now, and only then the name Android remembers for the
         *  address. The remembered name outlives a reflash, so a board moved
         *  from MeshCore to Meshtastic kept showing up as "MeshCore-…". */
        internal fun scanDisplayName(advertised: String?, cached: String?): String? =
            advertised?.takeIf { it.isNotBlank() } ?: cached?.takeIf { it.isNotBlank() }

        /** Text for the BLE pane's "Copy diagnostics": the last failure (if
         *  any) followed by the recent BLE log [lines]. */
        internal fun formatDiagnostics(failure: BleFailure?, lines: List<String>, nowMs: Long): String {
            val header = if (failure != null) {
                buildString {
                    append(failure.summaryLine(nowMs))
                    append('\n')
                    append(failure.message)
                    failure.hint?.let {
                        append('\n')
                        append(it)
                    }
                    if (failure.discoveredServices.isNotEmpty()) {
                        append('\n')
                        append("Discovered services: ${failure.discoveredServices.joinToString()}")
                    }
                }
            } else {
                "No BLE failures recorded"
            }
            return buildString {
                append(header)
                append("\n\n--- BLE log (last ${lines.size}) ---\n")
                append(lines.joinToString("\n"))
            }
        }

        // #203 — was 5_000 as a coroutine-only (non-Nordic) timeout; now a
        // real Nordic RequestQueue timeout (see readOnce), widened to 10s to
        // give a slow/congested link room before we call it a READ_TIMEOUT.
        private const val READ_TIMEOUT_MS: Long = 10_000
        private const val POLL_INTERVAL_MS: Long = 1_000

        /** #203 — how often RSSI is sampled for the link-status row. */
        private const val RSSI_INTERVAL_MS: Long = 10_000
        private const val MAX_DRAIN_PER_BATCH: Int = 32

        /** #203 — how many recent BLE log lines [logRingBufferSnapshot] keeps.
         *  A connect attempt is about 25 lines, so this holds the last dozen. */
        private const val LOG_RING_BUFFER_SIZE: Int = 300

        /**
         * Pure helper — splits a ToRadio payload into BLE-write-sized
         * chunks. Exposed so JVM unit tests can verify the chunk
         * boundary logic without spinning up a real BleManager (which
         * needs a live Android Bluetooth stack).
         *
         * Empty input → list with one empty chunk (so the caller still
         * issues a single zero-length write, mirroring the iOS path
         * for empty wake-ups).
         */
        fun chunkPayload(bytes: ByteArray, chunkSize: Int): List<ByteArray> {
            require(chunkSize > 0) { "chunkSize must be > 0" }
            if (bytes.isEmpty()) return listOf(ByteArray(0))
            if (bytes.size <= chunkSize) return listOf(bytes)
            val out = ArrayList<ByteArray>((bytes.size + chunkSize - 1) / chunkSize)
            var i = 0
            while (i < bytes.size) {
                val end = minOf(i + chunkSize, bytes.size)
                out.add(bytes.copyOfRange(i, end))
                i = end
            }
            return out
        }

        /**
         * Required Android runtime permissions to scan + connect on
         * Android 12+. Older releases need ACCESS_FINE_LOCATION instead;
         * the legacy permissions in the manifest cover that.
         */
        val RUNTIME_PERMISSIONS: Array<String> = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )

        /** Simple Bluetooth-availability + radio-on probe. */
        fun isBluetoothReady(context: Context): Boolean {
            val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                ?: return false
            return mgr.adapter?.isEnabled == true
        }
    }
}
