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
                _state.value = ConnectionState.Failed("connect failed: $reason")
            }
            override fun onDeviceReady(device: BluetoothDevice) {
                _state.value = ConnectionState.Connected(device.address, useTLS = false)
            }
            override fun onDeviceDisconnecting(device: BluetoothDevice) {
                // intermediate
            }
            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                stopBackgroundJobs()
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
        _state.value = ConnectionState.Connecting(address)
        return suspendCancellableCoroutine { cont ->
            connect(device)
                .timeout(CONNECT_TIMEOUT_MS)
                .retry(2, 200)
                .useAutoConnect(false)
                .done {
                    if (cont.isActive) cont.resume(true)
                }
                .fail { _, status ->
                    Log.w(TAG, "BLE connect failed: status=$status")
                    _state.value = ConnectionState.Failed("status=$status")
                    if (cont.isActive) cont.resume(false)
                }
                .enqueue()
        }
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
        if (priority >= Log.INFO) Log.println(priority, TAG, message)
    }

    // Modern BleManager 2.6+ API: override these directly on the
    // manager subclass instead of returning a `BleManagerGattCallback`
    // inner class.

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val service: BluetoothGattService = gatt.getService(SERVICE_UUID) ?: return false
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
                .fail { _, status -> Log.w(TAG, "fromNum notify enable failed: $status") }
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

    private suspend fun readOnce(ch: BluetoothGattCharacteristic): Data? =
        withTimeoutOrNull(READ_TIMEOUT_MS) {
            suspendCancellableCoroutine<Data?> { cont ->
                readCharacteristic(ch)
                    .with { _, data -> if (cont.isActive) cont.resume(data) }
                    .fail { _, status ->
                        Log.w(TAG, "fromRadio read failed: $status")
                        if (cont.isActive) cont.resume(null)
                    }
                    .enqueue()
            }
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

    // region Scan result ------------------------------------------------

    data class BleScanResult(
        val name: String?,
        val address: String,
        val rssi: Int,
    )

    // endregion

    companion object {
        private const val TAG = "MeshBle"

        // Meshtastic GATT service & characteristic UUIDs. Matches the
        // canonical service the Meshtastic firmware advertises.
        val SERVICE_UUID: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
        val TO_RADIO_UUID: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
        val FROM_RADIO_UUID: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
        val FROM_NUM_UUID: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547de15e6")

        const val REQUESTED_MTU: Int = 512
        const val CHUNK_SIZE_BYTES: Int = 500
        private const val CONNECT_TIMEOUT_MS: Long = 15_000
        private const val READ_TIMEOUT_MS: Long = 5_000
        private const val POLL_INTERVAL_MS: Long = 1_000
        private const val MAX_DRAIN_PER_BATCH: Int = 32

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
