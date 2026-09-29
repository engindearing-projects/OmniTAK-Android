package soy.engindearing.omnitak.mobile.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.WriteRequest
import no.nordicsemi.android.ble.callback.FailCallback
import no.nordicsemi.android.ble.data.Data
import no.nordicsemi.android.ble.observer.ConnectionObserver
import soy.engindearing.omnitak.mobile.domain.ConnectionState
import java.util.UUID
import kotlin.coroutines.resume

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
 */
@SuppressLint("MissingPermission")
class MeshtasticBleClient(context: Context) : BleManager(context) {

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

    // #203 — BLE failure diagnostics. Reset to null in onDeviceReady;
    // populated by the connect / disconnect / service-discovery / read
    // paths below via recordFailure().
    private val _lastFailure = MutableStateFlow<BleFailure?>(null)
    val lastFailure: StateFlow<BleFailure?> = _lastFailure.asStateFlow()

    /** #203 — when true, Nordic DEBUG/VERBOSE log lines also print to
     *  Logcat. Every line lands in [logRingBufferSnapshot] regardless of
     *  this flag. Off by default; wired from the BLE pane's toggle via
     *  [soy.engindearing.omnitak.mobile.domain.MeshtasticManager.setVerboseBleLogging]. */
    @Volatile var verboseLoggingEnabled: Boolean = false

    // endregion

    // region Internal ----------------------------------------------------

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val bluetoothAdapter: BluetoothAdapter? = run {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        mgr?.adapter
    }

    private var toRadioChar: BluetoothGattCharacteristic? = null
    private var fromRadioChar: BluetoothGattCharacteristic? = null
    private var fromNumChar: BluetoothGattCharacteristic? = null

    private var pollJob: Job? = null
    private var drainJob: Job? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = runCatching { result.device.name }.getOrNull()
            val r = BleScanResult(
                name = name ?: result.scanRecord?.deviceName,
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

    init {
        // Nordic ConnectionObserver — keep our StateFlow in sync.
        setConnectionObserver(object : ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) {
                _state.value = ConnectionState.Connecting(device.address)
            }
            override fun onDeviceConnected(device: BluetoothDevice) {
                // Wait for service discovery before flipping to Connected.
            }
            override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
                // #203 — REASON_NOT_SUPPORTED means isRequiredServiceSupported()
                // already recorded a richer SERVICE_DISCOVERY failure (with the
                // discovered service list) just before Nordic tore the link back
                // down to call us here; don't clobber it with a generic CONNECT
                // entry for the same underlying event.
                if (reason != ConnectionObserver.REASON_NOT_SUPPORTED) {
                    recordFailure(
                        phase = BleFailure.Phase.CONNECT,
                        reason = reason,
                        status = null,
                        bondState = device.bondState,
                        message = "connect failed: reason=$reason",
                    )
                }
                // #203 — carry the just-recorded (reason-bearing) failure message
                // forward instead of a bare "connect failed: $reason" here, since
                // connectToAddress's own resolveFailedConnectState() reads
                // _lastFailure to build the Failed state it settles on.
                _state.value = ConnectionState.Failed(_lastFailure.value?.message ?: "connect failed: $reason")
            }
            override fun onDeviceReady(device: BluetoothDevice) {
                _lastFailure.value = null
                _state.value = ConnectionState.Connected(device.address, useTLS = false)
            }
            override fun onDeviceDisconnecting(device: BluetoothDevice) {
                // intermediate
            }
            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                stopBackgroundJobs()
                // #203 — a local, user-initiated disconnect (disconnectClean())
                // reports REASON_TERMINATE_LOCAL_HOST; that's expected and not a
                // failure worth surfacing. Anything else — link loss, the peer
                // hanging up, a timeout — is exactly the "drops after about an
                // hour" field report and gets recorded.
                if (reason != ConnectionObserver.REASON_TERMINATE_LOCAL_HOST) {
                    recordFailure(
                        phase = BleFailure.Phase.LINK_LOSS,
                        reason = reason,
                        status = null,
                        bondState = device.bondState,
                        message = "link lost: reason=$reason",
                    )
                }
                if (_state.value !is ConnectionState.Failed) {
                    _state.value = ConnectionState.Disconnected
                }
            }
        })
    }

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

    // region Connect / Disconnect ---------------------------------------

    /** Connect to the radio at [address]. Suspends until ready or fails. */
    suspend fun connectToAddress(address: String): Boolean {
        val adapter = bluetoothAdapter ?: return false
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return false

        // #175 — clear any stale GATT/manager session before reconnecting.
        // Reconnecting to a previously-connected radio without closing the prior
        // session leaves a half-open GATT in the OS stack; Nordic then sits in
        // Connecting and its own .timeout()/.fail() never fire, so the UI hangs
        // on "Connecting" until the app is restarted (a fresh process drops the
        // stale session). BleManager.close() releases the prior GATT and resets
        // the manager so the next connect() starts from a clean slate.
        runCatching { close() }
        stopBackgroundJobs()

        _state.value = ConnectionState.Connecting(address)

        // #175 — outer watchdog. Nordic's .timeout()/.retry() should fail back,
        // but on a stuck stale GATT the failure callback can never fire, leaving
        // us pinned in Connecting forever. withTimeoutOrNull guarantees we leave
        // Connecting within a bounded window and reset to a *retryable*
        // Disconnected state (not Failed — Failed is sticky and blocks
        // onDeviceDisconnected from resetting), so the user can simply tap
        // Connect again.
        val ok = withTimeoutOrNull(WATCHDOG_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                connect(device)
                    .timeout(CONNECT_TIMEOUT_MS)
                    .retry(2, 200)
                    .useAutoConnect(false)
                    .done {
                        if (cont.isActive) cont.resume(true)
                    }
                    .fail { _, status ->
                        Log.w(TAG, "BLE connect failed: status=$status")
                        if (cont.isActive) cont.resume(false)
                    }
                    .enqueue()
            }
        }

        if (ok != true) {
            // Whether timed out (null) or hard-failed (false), drop any half-open
            // GATT so the next attempt starts from a clean slate, then settle on
            // the resolved retryable state.
            runCatching { close() }
            stopBackgroundJobs()
            // #203 — pass the failure the ConnectionObserver just recorded (if
            // any) so a hard failure surfaces its real reason instead of the
            // generic "connect failed for $addr" placeholder.
            _state.value = resolveFailedConnectState(address, ok, _lastFailure.value)
        }
        // On success the ConnectionObserver (onDeviceReady) already flipped us to
        // Connected; don't second-guess it here.
        return ok == true
    }

    suspend fun disconnectClean() {
        stopBackgroundJobs()
        suspendCancellableCoroutine<Unit> { cont ->
            disconnect()
                .done { if (cont.isActive) cont.resume(Unit) }
                .fail { _, _ -> if (cont.isActive) cont.resume(Unit) }
                .enqueue()
        }
        _state.value = ConnectionState.Disconnected
    }

    /**
     * [MeshTransport] view over this client.
     *
     * Exposed as an adapter rather than `MeshtasticBleClient : MeshTransport`
     * because Nordic's [BleManager.disconnect] is `public final` and returns
     * a `DisconnectRequest` — a same-name `disconnect(): Unit` from the
     * interface would be an irreconcilable accidental-override clash. The
     * adapter delegates [MeshTransport.send] to [sendToRadio] (byte-identical
     * Meshtastic behavior) and [MeshTransport.disconnect] to a fire-and-forget
     * [disconnectClean] on this client's own IO scope — the same teardown the
     * manager already drives.
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

    // region TX ---------------------------------------------------------

    /**
     * Write a serialized ToRadio protobuf to the radio. Splits into
     * chunks of [CHUNK_SIZE_BYTES] if needed and uses NO_RESPONSE
     * writes when the characteristic supports them (faster).
     */
    suspend fun sendToRadio(bytes: ByteArray): Boolean {
        val ch = toRadioChar ?: return false
        val noResp = ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val writeType = if (noResp) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        val chunks = chunkPayload(bytes, CHUNK_SIZE_BYTES)
        for (chunk in chunks) {
            val ok = suspendCancellableCoroutine<Boolean> { cont ->
                val req: WriteRequest = writeCharacteristic(ch, chunk, writeType)
                req
                    .done { if (cont.isActive) cont.resume(true) }
                    .fail { _, status ->
                        Log.w(TAG, "writeCharacteristic failed: $status")
                        if (cont.isActive) cont.resume(false)
                    }
                    .enqueue()
            }
            if (!ok) return false
        }
        return true
    }

    // endregion

    // region BleManager hooks -------------------------------------------

    override fun getMinLogPriority(): Int = Log.VERBOSE

    override fun log(priority: Int, message: String) {
        // #203 — buffer every line (VERBOSE and up, per getMinLogPriority)
        // regardless of the toggle below, so "Copy diagnostics" has recent
        // BLE chatter even when verbose forwarding to Logcat was off when
        // the failure happened.
        appendLogLine(priority, message)
        if (priority >= Log.INFO) {
            Log.println(priority, TAG, message)
        } else if (verboseLoggingEnabled) {
            // Only DEBUG/VERBOSE land here (INFO+ is handled above).
            Log.d(TAG, message)
        }
    }

    // Modern BleManager 2.6+ API: override these directly on the
    // manager subclass instead of returning a `BleManagerGattCallback`
    // inner class.

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val service: BluetoothGattService = gatt.getService(SERVICE_UUID) ?: run {
            // #203 — the field-report symptom (link drops after ~an hour,
            // reconnect never recovers) smells like the radio's GATT cache
            // going stale, so record exactly what WAS discovered — that's
            // the detail that tells the difference between "wrong radio",
            // "stale cache" (unrelated services present) and "nothing came
            // back at all".
            recordFailure(
                phase = BleFailure.Phase.SERVICE_DISCOVERY,
                reason = ConnectionObserver.REASON_NOT_SUPPORTED,
                status = null,
                bondState = gatt.device.bondState,
                message = "required GATT service not found: $SERVICE_UUID",
                services = gatt.services.map { it.uuid.toString() },
            )
            return false
        }
        toRadioChar = service.getCharacteristic(TO_RADIO_UUID)
        fromRadioChar = service.getCharacteristic(FROM_RADIO_UUID)
        fromNumChar = service.getCharacteristic(FROM_NUM_UUID)
        // Need toRadio to send and at least one of fromRadio/fromNum
        // to receive.
        val canSend = toRadioChar != null
        val canReceive = fromRadioChar != null || fromNumChar != null
        return canSend && canReceive
    }

    override fun initialize() {
        // Negotiate MTU first so chunking has headroom.
        requestMtu(REQUESTED_MTU)
            .with { _, mtu -> Log.i(TAG, "MTU negotiated: $mtu") }
            .enqueue()

        // Subscribe to fromNum notifications — each notification is
        // a "data waiting" signal; drain fromRadio when fired.
        fromNumChar?.let { ch ->
            setNotificationCallback(ch).with { _, data ->
                Log.v(TAG, "fromNum notify (${data.size()}B) → drain")
                triggerDrain()
            }
            enableNotifications(ch)
                .fail { device, status ->
                    Log.w(TAG, "fromNum notify enable failed: $status")
                    recordFailure(
                        phase = BleFailure.Phase.INIT,
                        reason = status,
                        status = null,
                        bondState = device.bondState,
                        message = "fromNum notification enable failed: status=$status",
                    )
                }
                .enqueue()
        }

        // Kick off the safety-net polling loop. iOS uses a 1.0s
        // Timer; we mirror that.
        startPollLoop()

        // First drain — the radio may already have queued frames.
        triggerDrain()
    }

    override fun onServicesInvalidated() {
        toRadioChar = null
        fromRadioChar = null
        fromNumChar = null
        stopBackgroundJobs()
    }

    // endregion

    // region Drain / Poll loops -----------------------------------------

    private fun triggerDrain() {
        if (drainJob?.isActive == true) return
        drainJob = scope.launch { drainFromRadio() }
    }

    /**
     * Read fromRadio repeatedly until an empty payload is returned.
     * Each non-empty read goes to [_frames] (raw FromRadio protobuf
     * bytes, which the parser turns into a `FromRadioFrame`).
     */
    private suspend fun drainFromRadio() {
        val ch = fromRadioChar ?: return
        repeat(MAX_DRAIN_PER_BATCH) {
            val data = readOnce(ch) ?: return
            val payload = data.value ?: ByteArray(0)
            if (payload.isEmpty()) return
            _bytesReceived.value += payload.size.toLong()
            _frames.tryEmit(payload)
        }
    }

    /**
     * #203 — a bare `readCharacteristic()` request can't carry its own
     * timeout: [no.nordicsemi.android.ble.ReadRequest] extends
     * `SimpleValueRequest`, not `TimeoutableRequest` (verified against the
     * ble 2.8.0 jar — `ReadRequest.timeout()` doesn't exist). Previously this
     * relied solely on the outer `withTimeoutOrNull` to abandon the
     * *coroutine*, but Nordic's own internal request handler processes one
     * request at a time — a read that never gets a GATT callback (a stalled
     * link) stays "in flight" there forever and wedges every request queued
     * behind it (the poll loop's next read, the next write, …), which is
     * exactly the "only force-stop + re-pair recovers it" field report.
     *
     * Wrapping the read in an atomic request queue upgrades it to a
     * `TimeoutableRequest` (`RequestQueue extends TimeoutableRequest`), so
     * `.timeout()` is real: it fires `.fail()` with
     * [FailCallback.REASON_TIMEOUT] AND tells Nordic's internal handler to
     * move on, unblocking whatever is queued next.
     */
    private suspend fun readOnce(ch: BluetoothGattCharacteristic): Data? =
        suspendCancellableCoroutine { cont ->
            val queue = beginAtomicRequestQueue()
                .add(
                    readCharacteristic(ch).with { _, data ->
                        if (cont.isActive) cont.resume(data)
                    },
                )
                .timeout(READ_TIMEOUT_MS)
                .fail { device, status ->
                    if (status == FailCallback.REASON_TIMEOUT) {
                        recordFailure(
                            phase = BleFailure.Phase.READ_TIMEOUT,
                            reason = status,
                            status = null,
                            bondState = device.bondState,
                            message = "fromRadio read timed out after ${READ_TIMEOUT_MS}ms",
                        )
                    }
                    Log.w(TAG, "fromRadio read failed: $status")
                    if (cont.isActive) cont.resume(null)
                }
            // Belt-and-suspenders: if OUR coroutine gets cancelled from
            // outside (e.g. stopBackgroundJobs() cancelling drainJob) before
            // Nordic's own .timeout() fires, proactively cancel the Nordic
            // side too instead of leaving it to complete into a continuation
            // nobody is waiting on.
            cont.invokeOnCancellation { runCatching { queue.cancel() } }
            queue.enqueue()
        }

    private fun startPollLoop() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                if (_state.value !is ConnectionState.Connected) continue
                triggerDrain()
                // Sample RSSI for the link-status UI.
                runCatching {
                    readRssi()
                        .with { _, rssi -> _rssi.value = rssi }
                        .enqueue()
                }
            }
        }
    }

    private fun stopBackgroundJobs() {
        pollJob?.cancel(); pollJob = null
        drainJob?.cancel(); drainJob = null
    }

    // endregion

    // region Failure diagnostics (#203) -----------------------------------

    /** Builds the next [BleFailure] (bumping [BleFailure.consecutiveFailures]
     *  when it matches the previous one's phase+reason+status) and publishes
     *  it on [lastFailure]. */
    private fun recordFailure(
        phase: BleFailure.Phase,
        reason: Int,
        status: Int?,
        bondState: Int,
        message: String,
        services: List<String> = emptyList(),
    ) {
        _lastFailure.value = BleFailure.next(
            previous = _lastFailure.value,
            phase = phase,
            reason = reason,
            status = status,
            bondState = bondState,
            nowMs = System.currentTimeMillis(),
            message = message,
            services = services,
        )
    }

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
     *  [verboseLoggingEnabled] — see [log]. */
    fun logRingBufferSnapshot(): List<String> = synchronized(logRingBufferLock) { logRingBuffer.toList() }

    /** Text block for the BLE pane's "Copy diagnostics" action: the last
     *  recorded failure (if any) plus the log ring buffer. */
    fun diagnosticsSnapshot(nowMs: Long = System.currentTimeMillis()): String {
        val failure = _lastFailure.value
        val header = if (failure != null) {
            buildString {
                append(failure.summaryLine(nowMs))
                append('\n')
                append(failure.message)
                if (failure.discoveredServices.isNotEmpty()) {
                    append('\n')
                    append("Discovered services: ${failure.discoveredServices.joinToString()}")
                }
            }
        } else {
            "No BLE failures recorded"
        }
        val lines = logRingBufferSnapshot()
        return buildString {
            append(header)
            append("\n\n--- BLE log (last ${lines.size}) ---\n")
            append(lines.joinToString("\n"))
        }
    }

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

    // endregion

    companion object {
        private const val TAG = "MeshBle"

        /**
         * #175 — pure decision for the post-connect state when a connect attempt
         * did NOT succeed. Lives in the companion so the timeout →
         * retryable-Disconnected transition is unit-testable without a live BLE
         * stack (the manager itself can't be JVM-instantiated).
         *
         * @param attemptResult `null` = watchdog timeout (Nordic's own callbacks
         *   never fired — the stale-GATT hang), `false` = Nordic reported a hard
         *   failure. A timeout resets to [ConnectionState.Disconnected] so the UI
         *   stops spinning and shows a tappable Connect; a hard failure surfaces
         *   the reason but is still recoverable (the next connect closes the
         *   session first). Crucially neither path is a sticky state that would
         *   leave the user pinned in "Connecting" forever.
         * @param failure #203 — the client's [lastFailure] value at the moment
         *   the attempt resolved. When present (the ConnectionObserver already
         *   recorded a reason-bearing failure for this same attempt), its
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
        val FROM_NUM_UUID: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547de15e6")

        const val REQUESTED_MTU: Int = 512
        const val CHUNK_SIZE_BYTES: Int = 500
        private const val CONNECT_TIMEOUT_MS: Long = 15_000

        // #175 — hard backstop above Nordic's own CONNECT_TIMEOUT_MS. If the
        // stale-GATT hang swallows Nordic's timeout/fail callback, this guarantees
        // connectToAddress leaves the Connecting state and resets to a retryable
        // Disconnected within a bounded window. Sized a few seconds over Nordic's
        // own timeout so the library gets first chance to report a clean failure.
        internal const val WATCHDOG_TIMEOUT_MS: Long = 20_000

        // #203 — was 5_000 as a coroutine-only (non-Nordic) timeout; now a
        // real Nordic RequestQueue timeout (see readOnce), widened to 10s to
        // give a slow/congested link room before we call it a READ_TIMEOUT.
        private const val READ_TIMEOUT_MS: Long = 10_000
        private const val POLL_INTERVAL_MS: Long = 1_000
        private const val MAX_DRAIN_PER_BATCH: Int = 32

        /** #203 — how many recent BLE log lines [logRingBufferSnapshot] keeps. */
        private const val LOG_RING_BUFFER_SIZE: Int = 200

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
