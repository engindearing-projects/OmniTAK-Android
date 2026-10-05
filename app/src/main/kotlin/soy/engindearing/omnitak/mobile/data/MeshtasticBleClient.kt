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
import kotlinx.coroutines.flow.update
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
    //
    // [link] and [_state] change together under [linkLock]: a callback from a
    // link only gets to write the state if that link is still the current
    // one at the moment of the write.

    private fun currentLink(): Link? = synchronized(linkLock) { link }

    private fun isCurrent(l: Link): Boolean = synchronized(linkLock) { link === l }

    private fun newLink(address: String, device: BluetoothDevice): Link =
        Link(address, device, synchronized(linkLock) { ++linkSeq })

    // endregion

    // region Connect / Disconnect ---------------------------------------

    /** How one [connect] attempt ended. */
    enum class ConnectResult {
        /** The session is up. */
        CONNECTED,

        /** The attempt failed; the failure is on [lastFailure]. */
        FAILED,

        /**
         * It failed on an error from the Bluetooth stack before the link was
         * up (status 133 and its relatives). Those usually clear at once, so
         * the caller may try again right away instead of waiting for its
         * next scheduled attempt.
         */
        FAILED_BEFORE_LINK_UP,

        /** A newer connect or a disconnect took over; nothing was recorded. */
        SUPERSEDED,
    }

    /**
     * Connect to the radio at [address]: one attempt on a link of its own.
     * Suspends until the session is ready or the attempt has failed; it
     * always returns, and never leaves [state] on Connecting.
     *
     * [handshake] is the first ToRadio frame of the session (want_config for
     * Meshtastic). It is written here, on the link this attempt brought up,
     * and a session it could not be delivered on is not kept: without it
     * the radio sends nothing.
     *
     * A newer call replaces an attempt that is still in flight: the older
     * one returns [ConnectResult.SUPERSEDED] and leaves [state] to the newer
     * one.
     */
    suspend fun connect(address: String, handshake: ByteArray? = null): ConnectResult {
        val adapter = bluetoothAdapter ?: return ConnectResult.FAILED
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return ConnectResult.FAILED

        val attempt = runCatching { newLink(address, device) }.getOrElse { e ->
            Log.w(TAG, "BLE link could not be created: ${e.message}")
            return ConnectResult.FAILED
        }
        val previous = synchronized(linkLock) {
            val old = link
            link = attempt
            _state.value = ConnectionState.Connecting(address)
            old
        }
        stopBackgroundJobs()

        val end = try {
            if (previous != null) {
                previous.retire()
                // Android wants a moment between closing one GATT client and
                // opening the next to the same radio (Nordic waits 200 ms at
                // the same point when it reuses a manager).
                delay(GATT_SETTLE_MS)
            }
            awaitWithDeadline<ConnectEnd>(attempt, CONNECT_TIMEOUT_MS, onClosed = ConnectEnd.Closed) { complete ->
                starting("connect", { complete(ConnectEnd.Failed(FailCallback.REASON_REQUEST_FAILED)) }) {
                    attempt.startConnect(complete)
                }
            } ?: ConnectEnd.TimedOut
        } catch (e: CancellationException) {
            synchronized(linkLock) {
                if (link === attempt) {
                    link = null
                    _state.value = ConnectionState.Disconnected
                }
            }
            attempt.retire()
            throw e
        }

        // On success the link's observer (onLinkReady) already flipped us to
        // Connected and started the read loops.
        if (attempt.sessionUp && isCurrent(attempt)) return finishConnect(attempt, handshake)

        val bondState = attempt.bondState()
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
        val draft = describeConnectFailure(facts)
        var cameUpAfterAll = false
        val failure = synchronized(linkLock) {
            when {
                link !== attempt -> null
                // Setup finished between the deadline passing and this line:
                // onLinkReady has the session, so it is a success.
                attempt.sessionUp -> {
                    cameUpAfterAll = true
                    null
                }
                else -> {
                    link = null
                    failures.record(draft, bondState).also { _state.value = ConnectionState.Failed(it.message) }
                }
            }
        }
        if (cameUpAfterAll) return finishConnect(attempt, handshake)
        attempt.retire()
        // Replaced by a newer attempt, disconnected by the operator, or the
        // session came up and dropped again before we got here: the state and
        // the failure record belong to whoever did that.
        if (failure == null) return ConnectResult.SUPERSEDED
        stopBackgroundJobs()
        Log.w(TAG, "BLE connect to $address failed: ${failure.message}")
        return if (isQuickRetryable(facts)) ConnectResult.FAILED_BEFORE_LINK_UP else ConnectResult.FAILED
    }

    /** The session on [l] is up: deliver [handshake] before calling it connected. */
    private suspend fun finishConnect(l: Link, handshake: ByteArray?): ConnectResult {
        if (handshake == null) return ConnectResult.CONNECTED
        val delivered = try {
            sendOn(l, handshake)
        } catch (e: CancellationException) {
            // The caller gave up mid-handshake. A session nobody finished
            // setting up must not stay on Connected.
            dropDeadLink(l, BleFailure.Phase.INIT, FailCallback.REASON_CANCELLED, HANDSHAKE_NOT_DELIVERED)
            throw e
        }
        if (delivered) return ConnectResult.CONNECTED
        // Does nothing if the link is already gone (dropped, or replaced by a
        // newer attempt): whoever did that recorded what happened.
        dropDeadLink(l, BleFailure.Phase.INIT, FailCallback.REASON_REQUEST_FAILED, HANDSHAKE_NOT_DELIVERED)
        return ConnectResult.FAILED
    }

    /**
     * Waits for a request on [l] and gives up when it has made no progress
     * for [idleLimitMs]. Time spent pairing does not count against the limit
     * (see [ProgressDeadline]), so an operator reading the PIN off the radio
     * is not cut off, and neither is the setup that follows the pairing.
     * Returns null when the deadline passes.
     */
    private suspend fun <T : Any> awaitWithDeadline(
        l: Link,
        idleLimitMs: Long,
        onClosed: T,
        start: (complete: (T) -> Unit) -> Unit,
    ): T? = coroutineScope {
        val result = async { l.awaits.await(onClosed, start) }
        // uptimeMillis, like the delay behind each tick, stops while the CPU
        // sleeps. With elapsedRealtime a phone that dozed for a minute would
        // wake up to a wait that looks a minute overdue, before the answer
        // that woke it has been handled.
        val deadline = ProgressDeadline(SystemClock.uptimeMillis(), idleLimitMs, PAIRING_TIMEOUT_MS)
        var end: T? = null
        var expired = false
        while (end == null && !expired) {
            end = withTimeoutOrNull(WATCHDOG_TICK_MS) { result.await() }
            if (end == null && deadline.expired(SystemClock.uptimeMillis(), l.isBonding())) {
                result.cancel()
                expired = true
            }
        }
        end
    }

    suspend fun disconnectClean() {
        val l = synchronized(linkLock) {
            val old = link
            link = null
            old
        }
        stopBackgroundJobs()
        try {
            if (l != null && l.sessionUp) {
                // Ask the radio for an orderly disconnect, but never wait on
                // it for long: the link may already be gone.
                withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) {
                    l.awaits.await(onClosed = Unit) { complete ->
                        starting("disconnect", { complete(Unit) }) { l.startDisconnect { complete(Unit) } }
                    }
                }
            }
        } finally {
            l?.retire()
            // A connect started while we were tearing down owns the state now.
            synchronized(linkLock) {
                if (link == null) _state.value = ConnectionState.Disconnected
            }
        }
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
        val handedOver = synchronized(linkLock) {
            // A link that came up without the Meshtastic characteristics is
            // not a session; connect() reports it as a failed attempt.
            if (link !== l || !l.usable) {
                false
            } else {
                l.sessionUp = true
                _rssi.value = 0
                _state.value = ConnectionState.Connected(l.address, useTLS = false)
                true
            }
        }
        if (!handedOver) return
        failures.sessionCameUp()
        // The session is up without fromNum notifications: it still works on
        // the poll, and this is where that gets written down (once, here,
        // rather than from the request's own callback while the connect is
        // still being decided).
        l.notifyEnableStatus?.let { status ->
            val bondState = l.bondState()
            failures.record(
                phase = BleFailure.Phase.INIT,
                reason = status,
                status = null,
                bondState = bondState,
                message = "fromNum notification enable failed: status=$status",
                hint = sessionFailureHint(bondState, l.pairingSeen),
            )
        }
        // Safety-net polling loop (iOS uses a 1.0s Timer; we mirror that),
        // plus a first drain: the radio may already have queued frames.
        startPollLoop(l)
        triggerDrain(l)
    }

    /** The GATT link of [l] is gone. */
    private fun onLinkDown(l: Link, reason: Int) {
        // Before the session is up, connect() owns the outcome.
        if (!l.sessionUp) return
        val bondState = l.bondState()
        // A teardown of ours (operator disconnect, replaced by a new attempt,
        // a dead session we dropped) released the link first and never gets
        // past this check. Whatever does is a drop we did not ask for: link
        // loss, the radio hanging up, Android ending the link itself, or
        // Bluetooth being switched off. This is the "drops after about an
        // hour" field report and gets recorded.
        val wasCurrent = synchronized(linkLock) {
            if (link !== l) {
                false
            } else {
                link = null
                failures.record(
                    phase = BleFailure.Phase.LINK_LOSS,
                    reason = reason,
                    status = null,
                    bondState = bondState,
                    message = "link lost: reason=$reason",
                    hint = if (bluetoothAdapter?.isEnabled == false) {
                        HINT_BLUETOOTH_OFF
                    } else {
                        sessionFailureHint(bondState, l.pairingSeen)
                    },
                )
                _state.value = ConnectionState.Disconnected
                true
            }
        }
        l.retire()
        if (wasCurrent) stopBackgroundJobs()
    }

    /**
     * A pairing failed on [l]. During a connect the attempt's own deadline
     * deals with it. On a session it ends the session: Nordic drops the
     * request that was in flight when the pairing failed without calling it
     * done or failed, so whoever waits on it would wait out a deadline and
     * take the session down later, after the next pairing had succeeded.
     * Android and the radio normally drop the link at this point anyway.
     */
    private fun onPairingFailed(l: Link) {
        if (!l.sessionUp) return
        dropDeadLink(l, BleFailure.Phase.LINK_LOSS, ConnectionObserver.REASON_UNKNOWN, "pairing failed")
    }

    /**
     * The services of [l] were invalidated while its session was up. On a
     * link loss that is routine and onLinkDown has already dealt with it by
     * the time this looks. If the link is still the current one after a
     * moment, the radio changed its services under a live session: Nordic
     * has dropped what was queued and is discovering again, and the session
     * is ended rather than patched up.
     */
    private fun onSessionServicesInvalidated(l: Link) {
        scope.launch {
            delay(SERVICES_INVALIDATED_GRACE_MS)
            dropDeadLink(l, BleFailure.Phase.LINK_LOSS, ConnectionObserver.REASON_UNKNOWN, "the radio's services changed")
        }
    }

    /**
     * Gives up on [l] as a session that stopped working while its GATT link
     * stayed up, so the reconnect loop starts a new one. Does nothing when
     * [l] is no longer the current link.
     */
    private fun dropDeadLink(l: Link, phase: BleFailure.Phase, reason: Int, message: String) {
        val bondState = l.bondState()
        val dropped = synchronized(linkLock) {
            if (link !== l) {
                false
            } else {
                link = null
                failures.record(
                    phase = phase,
                    reason = reason,
                    status = null,
                    bondState = bondState,
                    message = message,
                    hint = sessionFailureHint(bondState, l.pairingSeen),
                )
                _state.value = ConnectionState.Disconnected
                true
            }
        }
        if (!dropped) return
        Log.w(TAG, "BLE session dropped: $message")
        l.retire()
        stopBackgroundJobs()
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
     * false when there is no session, as soon as the link drops, or when a
     * write goes [WRITE_TIMEOUT_MS] without an answer.
     */
    suspend fun sendToRadio(bytes: ByteArray): Boolean {
        val l = currentLink() ?: return false
        return sendOn(l, bytes)
    }

    private suspend fun sendOn(l: Link, bytes: ByteArray): Boolean {
        if (!l.sessionUp || !isCurrent(l)) return false
        val ch = l.toRadio ?: return false
        val noResp = ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val writeType = if (noResp) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        val chunks = chunkPayload(bytes, CHUNK_SIZE_BYTES)
        for (chunk in chunks) {
            val ok = awaitWithDeadline(l, WRITE_TIMEOUT_MS, onClosed = false) { complete ->
                starting("write", { complete(false) }) { l.startWrite(ch, chunk, writeType, complete) }
            }
            if (ok == null) {
                // Neither done nor failed. Nordic can drop a request without
                // telling anyone (a failed pairing does it), and while one
                // request sits unanswered nothing queued behind it runs.
                dropDeadLink(l, BleFailure.Phase.WRITE_TIMEOUT, FailCallback.REASON_TIMEOUT, "the radio stopped answering writes")
                return false
            }
            if (!ok) return false
        }
        return true
    }

    // endregion

    // region Drain / Poll loops -----------------------------------------

    private fun triggerDrain(l: Link) {
        if (!isCurrent(l)) return
        synchronized(jobLock) {
            if (drainJob?.isActive == true) return
            drainJob = scope.launch { drainFromRadio(l) }
        }
    }

    /** How one fromRadio read ended. */
    private enum class ReadEnd { FRAME, EMPTY, FAILED }

    /**
     * Read fromRadio repeatedly until an empty payload is returned.
     * Each non-empty read goes to [_frames] (raw FromRadio protobuf
     * bytes, which the parser turns into a `FromRadioFrame`).
     */
    private suspend fun drainFromRadio(l: Link) {
        repeat(MAX_DRAIN_PER_BATCH) {
            val ch = l.fromRadio ?: return
            when (readOnce(l, ch)) {
                ReadEnd.FRAME -> l.readFailures = 0
                ReadEnd.EMPTY -> {
                    l.readFailures = 0
                    // The radio answered a moment ago and nothing is queued:
                    // the one point where an RSSI read cannot hold anything up.
                    l.sampleRssiIfDue { rssi -> _rssi.value = rssi }
                    return
                }
                ReadEnd.FAILED -> {
                    // Reads that keep failing on a link that stays up (a
                    // pairing the radio no longer honours answers every read
                    // with an error) are a session that will never carry data.
                    if (shouldDropAfterReadFailures(++l.readFailures, l.isBonding())) {
                        dropDeadLink(l, BleFailure.Phase.READ_TIMEOUT, FailCallback.REASON_REQUEST_FAILED, "the radio keeps refusing reads")
                    }
                    return
                }
                null -> {
                    // No answer and no failure for [READ_AWAIT_MS], and not
                    // because of a pairing. Nordic keeps an unanswered read in
                    // flight and runs nothing queued behind it, so this
                    // session cannot do anything more.
                    dropDeadLink(l, BleFailure.Phase.READ_TIMEOUT, FailCallback.REASON_TIMEOUT, "the radio stopped answering reads")
                    return
                }
            }
        }
    }

    /**
     * One fromRadio read; null when it got no answer of any kind.
     *
     * The read runs inside an atomic request queue because a bare
     * `readCharacteristic()` cannot carry a timeout
     * ([no.nordicsemi.android.ble.ReadRequest] is not a `TimeoutableRequest`).
     * That timeout reports a slow read ([BleFailure.Phase.READ_TIMEOUT]); it
     * does not free the queue, which stays behind the unanswered read until
     * its callback arrives or the link goes down. The wait here is what ends
     * a session stuck that way.
     */
    private suspend fun readOnce(l: Link, ch: BluetoothGattCharacteristic): ReadEnd? =
        awaitWithDeadline(l, READ_AWAIT_MS, onClosed = ReadEnd.FAILED) { complete ->
            starting("read", { complete(ReadEnd.FAILED) }) {
                l.startRead(
                    ch = ch,
                    onData = { data ->
                        val payload = data.value ?: ByteArray(0)
                        if (payload.isEmpty()) {
                            complete(ReadEnd.EMPTY)
                        } else {
                            // Delivered from here, not by whoever awaited the
                            // read: an answer that comes after the wait was
                            // given up (a read held up by pairing) still
                            // carries a frame the radio has taken off its queue.
                            deliverFrame(l, payload)
                            complete(ReadEnd.FRAME)
                        }
                    },
                    onFail = { status ->
                        Log.w(TAG, "fromRadio read failed: $status")
                        if (status == FailCallback.REASON_TIMEOUT && isCurrent(l)) {
                            val bondState = l.bondState()
                            failures.record(
                                phase = BleFailure.Phase.READ_TIMEOUT,
                                reason = status,
                                status = null,
                                bondState = bondState,
                                message = "fromRadio read timed out after ${READ_TIMEOUT_MS}ms",
                                hint = sessionFailureHint(bondState, l.pairingSeen),
                            )
                        }
                        complete(ReadEnd.FAILED)
                    },
                )
            }
        }

    private fun deliverFrame(l: Link, payload: ByteArray) {
        if (!isCurrent(l)) return
        _bytesReceived.update { it + payload.size.toLong() }
        _frames.tryEmit(payload)
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
    private inner class Link(
        val address: String,
        private val device: BluetoothDevice,
        private val seq: Int,
    ) : BleManager(appContext) {

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

        /** Status the fromNum notification subscribe failed with, if it did. */
        @Volatile var notifyEnableStatus: Int? = null

        /** fromRadio reads that failed in a row on this link. */
        @Volatile var readFailures = 0

        @Volatile private var cacheRefreshRequested = false

        @Volatile private var rssiPending = false
        @Volatile private var rssiUnanswered = false
        @Volatile private var lastRssiAt = 0L

        /** Android's service cache for this radio was refreshed on this link,
         *  so what discovery returned afterwards came from the radio itself. */
        @Volatile var cacheRefreshed = false

        /** Need toRadio to send and at least one of fromRadio/fromNum to receive. */
        val usable: Boolean get() = toRadio != null && (fromRadio != null || fromNum != null)

        fun bondState(): Int = runCatching { device.bondState }.getOrDefault(BluetoothDevice.BOND_NONE)

        /** Android is pairing with the radio right now. */
        fun isBonding(): Boolean =
            (bondState() == BluetoothDevice.BOND_BONDING).also { if (it) pairingSeen = true }

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
                    onLinkDown(this@Link, reason)
                }
            })
            setBondingObserver(object : BondingObserver {
                override fun onBondingRequired(device: BluetoothDevice) {
                    pairingSeen = true
                }
                override fun onBonded(device: BluetoothDevice) = Unit
                override fun onBondingFailed(device: BluetoothDevice) {
                    pairingSeen = true
                    onPairingFailed(this@Link)
                }
            })
        }

        // region requests

        fun startConnect(complete: (ConnectEnd) -> Unit) {
            // No Nordic retry: it re-runs the connect from a timer that nothing
            // can cancel, so a retry scheduled just before retire() would open
            // a new GATT client on a link that is already gone. A connect that
            // fails on a stack error is tried again by the caller instead (see
            // ConnectResult.FAILED_BEFORE_LINK_UP), on a link of its own.
            connect(device)
                // Backstop only: connect()'s own deadline fires first.
                .timeout(NORDIC_BACKSTOP_TIMEOUT_MS)
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
            onFail: (Int) -> Unit,
        ) {
            beginAtomicRequestQueue()
                .add(readCharacteristic(ch).with { _, data -> onData(data) })
                .timeout(READ_TIMEOUT_MS)
                .fail { _, status -> onFail(status) }
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
            // the failure happened. The prefix is the attempt number. Nordic
            // prints every value it reads and writes; those are radio traffic
            // (channel keys, positions, messages) and this text gets pasted
            // into bug reports, so only their sizes are kept.
            val line = "#$seq ${redactPayload(message)}"
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
                    .fail { _, status ->
                        // Recorded by onLinkReady if the session comes up
                        // anyway; a connect that fails reports itself.
                        Log.w(TAG, "fromNum notify enable failed: $status")
                        notifyEnableStatus = status
                    }
                    .enqueue()
            }
        }

        override fun onServicesInvalidated() {
            toRadio = null
            fromRadio = null
            fromNum = null
            // Called on Nordic's own thread, in the middle of its handling:
            // nothing is torn down from here.
            if (sessionUp) onSessionServicesInvalidated(this)
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
        enum class Phase { CONNECT, SERVICE_DISCOVERY, INIT, LINK_LOSS, READ_TIMEOUT, WRITE_TIMEOUT }

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

    /**
     * When to give up on something that should finish within [idleLimitMs]
     * unless Android is pairing with the radio (#175, #203).
     *
     * The limit runs from the start, or from the last moment a pairing was
     * seen: entering a PIN takes longer than the limit, and what follows a
     * pairing (service discovery, setup, the request being sent again) needs
     * the whole limit once more. A single pairing may take [pairingLimitMs].
     */
    internal class ProgressDeadline(
        startedAtMs: Long,
        private val idleLimitMs: Long,
        private val pairingLimitMs: Long,
    ) {
        private var idleSinceMs = startedAtMs
        private var pairingSinceMs: Long? = null

        /** Feed one observation; true when it is time to give up. */
        fun expired(nowMs: Long, pairing: Boolean): Boolean {
            if (!pairing) {
                pairingSinceMs = null
                return nowMs - idleSinceMs >= idleLimitMs
            }
            val since = pairingSinceMs ?: nowMs.also { pairingSinceMs = it }
            idleSinceMs = nowMs
            return nowMs - since >= pairingLimitMs
        }
    }

    /** What a connect attempt knew when it ended without a session. */
    internal data class ConnectAttemptFacts(
        /** Our own deadline passed (see [ProgressDeadline]). */
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

        /** How long a connect attempt may go without getting anywhere when
         *  no pairing is going on (#175: it must never sit on Connecting). */
        internal const val CONNECT_TIMEOUT_MS: Long = 15_000

        /** #203 — how long one pairing may take. Reading a six-digit PIN off
         *  the radio and typing it in does not fit in [CONNECT_TIMEOUT_MS];
         *  the attempt used to be torn down mid-entry, which also dismissed
         *  the pairing request. */
        internal const val PAIRING_TIMEOUT_MS: Long = 60_000

        /** Nordic's own connect timeout. Deliberately above a pairing plus the
         *  setup after it: [connect] decides when an attempt is over,
         *  and this only matters if that wait is somehow not running. */
        internal const val NORDIC_BACKSTOP_TIMEOUT_MS: Long = 90_000

        /** How long a toRadio write may go unanswered. */
        internal const val WRITE_TIMEOUT_MS: Long = 15_000

        private const val WATCHDOG_TICK_MS: Long = 1_000
        private const val DISCONNECT_TIMEOUT_MS: Long = 3_000
        private const val GATT_SETTLE_MS: Long = 300
        private const val SERVICES_INVALIDATED_GRACE_MS: Long = 500
        private const val HANDSHAKE_NOT_DELIVERED = "the handshake was not delivered"

        /** fromRadio reads that may fail in a row before the session is given up. */
        internal const val MAX_READ_FAILURES: Int = 5

        /** Whether a session whose reads keep failing should be ended. Not
         *  while Android is pairing: reads fail until the request is answered. */
        internal fun shouldDropAfterReadFailures(failuresInARow: Int, bonding: Boolean): Boolean =
            !bonding && failuresInARow >= MAX_READ_FAILURES

        /**
         * #203 — whether a failed connect is worth trying again at once: an
         * error from the Bluetooth stack before the link was up (status 133
         * and its relatives), as opposed to no answer, a pairing, or a radio
         * that is not Meshtastic.
         */
        internal fun isQuickRetryable(f: ConnectAttemptFacts): Boolean =
            !f.timedOut && !f.linkCameUp && !f.serviceMissing && (f.failStatus ?: 0) > 0

        // Nordic prints a value either as "(0x) AA-BB" (what it read or was
        // notified of) or as "value=0xAABB" / "setValue(0xAABB)" (what it
        // writes). Status codes are printed as "(0x85)" and are left alone.
        private val PRINTED_VALUE = Regex("""\(0x\) ([0-9A-Fa-f]{2}(?:-[0-9A-Fa-f]{2})*)""")
        private val WRITTEN_VALUE = Regex("""(value=|setValue\()0x([0-9A-Fa-f-]+)""")

        /**
         * Replaces the values in a Nordic log line with their sizes. On this
         * link they are FromRadio and ToRadio traffic, which carries channel
         * keys, positions and messages, and the log is what "Copy diagnostics"
         * puts on the clipboard for a bug report.
         */
        internal fun redactPayload(message: String): String {
            val printed = PRINTED_VALUE.replace(message) { m -> "${(m.groupValues[1].length + 1) / 3} bytes" }
            return WRITTEN_VALUE.replace(printed) { m ->
                "${m.groupValues[1]}${m.groupValues[2].count { it != '-' } / 2} bytes"
            }
        }

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

        // #203 — Nordic RequestQueue timeout on one fromRadio read (see
        // readOnce): how long before a slow read is reported.
        private const val READ_TIMEOUT_MS: Long = 10_000

        /** How long a fromRadio read may go without any answer at all, a
         *  Nordic failure included, before the session is given up. Above
         *  [READ_TIMEOUT_MS] so that Nordic reports a read it did start. */
        internal const val READ_AWAIT_MS: Long = 15_000
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
