package soy.engindearing.omnitak.mobile.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
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
import kotlinx.coroutines.launch
import soy.engindearing.omnitak.mobile.domain.ConnectionState
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

/**
 * BLE transport for a **MeshCore companion** radio over the Nordic UART
 * service. Hand-rolled against the raw Android GATT API (mirroring the
 * TAK-MeshCore plugin's `BtConnectionManager`), not the Nordic BleManager
 * the Meshtastic client uses — the MeshCore companion path is a simple
 * write-to-RX / notify-on-TX command channel where each notification is
 * exactly one companion frame.
 *
 * Wire:
 *  - Service `6E400001-…`, RX (write) `6E400002-…`, TX (notify) `6E400003-…`,
 *    CCCD `00002902-…`.
 *  - Scan filter: NUS service UUID, with a name fallback ("MeshCore-").
 *  - On connect: request MTU 512, discover services, enable TX notify, then
 *    flip to Connected (the manager runs the APP_START handshake).
 *  - [send] writes one companion command to RX (queued, one in-flight at a
 *    time — `writeCharacteristic` is async and only one may be pending).
 *  - Each TX notification payload is emitted verbatim on [frames] — the
 *    manager/codec decode whole frames (no length prefix on the BLE path).
 *
 * Implements [MeshTransport]; default pairing PIN guidance (123456 / OLED
 * code) is surfaced via [PAIRING_PIN].
 */
@SuppressLint("MissingPermission")
class MeshCoreUartClient(context: Context) : MeshTransport {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _frames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val frames: SharedFlow<ByteArray> = _frames.asSharedFlow()

    private val _bytesReceived = MutableStateFlow(0L)
    val bytesReceived: StateFlow<Long> = _bytesReceived.asStateFlow()

    private val _rssi = MutableStateFlow(0)
    val rssi: StateFlow<Int> = _rssi.asStateFlow()

    private val _scanResults = MutableSharedFlow<BleScanResult>(extraBufferCapacity = 64)
    val scanResults: SharedFlow<BleScanResult> = _scanResults.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val bluetoothAdapter: BluetoothAdapter? = run {
        val mgr = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        mgr?.adapter
    }

    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var txChar: BluetoothGattCharacteristic? = null

    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeInFlight = false
    private val writeLock = Any()

    private var scanning = false
    private var scanJob: Job? = null

    // region Scan ---------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            // Trust the hardware NUS filter; also accept by name as a fallback
            // for chipsets that don't surface the service UUID in the record.
            val name = runCatching { device.name }.getOrNull() ?: result.scanRecord?.deviceName
            if (!advertisesNus(result) && !matchesMeshCoreName(name)) return
            _scanResults.tryEmit(
                BleScanResult(name = name, address = device.address, rssi = result.rssi),
            )
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "MeshCore BLE scan failed: $errorCode")
        }
    }

    /** Start an NUS-filtered BLE scan; results stream on [scanResults]. */
    fun startScan(timeoutMs: Long = 10_000) {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            Log.w(TAG, "no BluetoothLeScanner — Bluetooth disabled?")
            return
        }
        if (scanning) return
        scanning = true
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(UUID_UART_SERVICE))
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
        scanJob?.cancel()
        scanJob = scope.launch {
            delay(timeoutMs)
            stopScan()
        }
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        scanJob?.cancel()
        runCatching { bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private fun advertisesNus(result: ScanResult): Boolean {
        val uuids = result.scanRecord?.serviceUuids ?: return false
        return uuids.any { it?.uuid == UUID_UART_SERVICE }
    }

    private fun matchesMeshCoreName(name: String?): Boolean {
        val t = name?.trim().orEmpty()
        if (t.isEmpty()) return false
        return t.regionMatches(0, NAME_PREFIX, 0, NAME_PREFIX.length, ignoreCase = true) ||
            t.lowercase(Locale.US).contains("meshcore")
    }

    // endregion

    // region Connect / Disconnect ----------------------------------------

    /**
     * Connect to the MeshCore radio at [address]. Returns true once the TX
     * notification is enabled (link ready for the APP_START handshake), or
     * false on failure / timeout.
     *
     * Pairing: an unbonded device triggers Android's system pairing dialog
     * lazily on the first GATT access (standard passkey-entry — the 6-digit
     * code shows on the node's OLED, or [PAIRING_PIN] for screenless nodes).
     */
    suspend fun connectToAddress(address: String, timeoutMs: Long = 20_000): Boolean {
        val adapter = bluetoothAdapter ?: return false
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return false
        stopScan()
        closeGatt()
        _state.value = ConnectionState.Connecting(address)
        clearWriteQueue()

        synchronized(this) {
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(appContext, false, gattCallback)
            }
        }
        if (gatt == null) {
            _state.value = ConnectionState.Failed("connectGatt returned null")
            return false
        }

        // Poll the state flow until Connected/Failed or timeout.
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            when (val s = _state.value) {
                is ConnectionState.Connected -> return true
                is ConnectionState.Failed -> return false
                else -> delay(100)
            }
        }
        _state.value = ConnectionState.Failed("connect timeout")
        closeGatt()
        return false
    }

    override fun disconnect() {
        clearWriteQueue()
        closeGatt()
        _state.value = ConnectionState.Disconnected
    }

    private fun closeGatt() {
        synchronized(this) {
            runCatching {
                gatt?.disconnect()
                gatt?.close()
            }
            gatt = null
            rxChar = null
            txChar = null
        }
    }

    // endregion

    // region TX -----------------------------------------------------------

    /**
     * [MeshTransport.send] — queue one companion command for the RX char.
     * Only one GATT write may be in flight; the queue drains as each
     * `onCharacteristicWrite` callback fires.
     */
    override suspend fun send(payload: ByteArray): Boolean {
        if (payload.isEmpty()) return false
        if (_state.value !is ConnectionState.Connected) return false
        synchronized(writeLock) {
            writeQueue.addLast(payload)
            if (writeInFlight) return true
            writeInFlight = true
        }
        drainWriteQueue()
        return true
    }

    private fun drainWriteQueue() {
        val g = gatt
        val ch = rxChar
        if (g == null || ch == null) {
            synchronized(writeLock) { writeInFlight = false }
            return
        }
        val next: ByteArray? = synchronized(writeLock) { writeQueue.peekFirst() }
        if (next == null) {
            synchronized(writeLock) { writeInFlight = false }
            return
        }
        val ok = writeToRx(g, ch, next)
        if (!ok) {
            // Retry shortly; keep the frame at the head of the queue.
            scope.launch {
                delay(150)
                drainWriteQueue()
            }
        }
        // else: wait for onCharacteristicWrite to pop + drain the next.
    }

    @Suppress("DEPRECATION")
    private fun writeToRx(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(
                    ch,
                    value,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                ) == BluetoothGatt.GATT_SUCCESS
            } else {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ch.value = value
                g.writeCharacteristic(ch)
            }
        }.getOrDefault(false)
    }

    private fun clearWriteQueue() {
        synchronized(writeLock) {
            writeQueue.clear()
            writeInFlight = false
        }
    }

    // endregion

    // region GATT callback ------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "onConnectionStateChange status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt = g
                runCatching { g.requestMtu(REQUESTED_MTU) }
                runCatching { g.discoverServices() }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                clearWriteQueue()
                if (_state.value is ConnectionState.Connected ||
                    _state.value is ConnectionState.Connecting
                ) {
                    _state.value = ConnectionState.Disconnected
                }
                closeGatt()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU negotiated: $mtu (status=$status)")
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = ConnectionState.Failed("service discovery failed")
                closeGatt()
                return
            }
            val svc: BluetoothGattService? = g.getService(UUID_UART_SERVICE)
            if (svc == null) {
                _state.value = ConnectionState.Failed("MeshCore UART service not found")
                closeGatt()
                return
            }
            rxChar = svc.getCharacteristic(UUID_UART_RX)
            txChar = svc.getCharacteristic(UUID_UART_TX)
            if (rxChar == null || txChar == null) {
                _state.value = ConnectionState.Failed("MeshCore RX/TX characteristic missing")
                closeGatt()
                return
            }
            // Enable notifications on TX.
            g.setCharacteristicNotification(txChar, true)
            val ccc = txChar?.getDescriptor(UUID_CCC)
            if (ccc == null) {
                _state.value = ConnectionState.Failed("MeshCore CCC descriptor missing")
                closeGatt()
                return
            }
            writeCccEnable(g, ccc)
        }

        @Suppress("DEPRECATION")
        private fun writeCccEnable(g: BluetoothGatt, ccc: BluetoothGattDescriptor) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    ccc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(ccc)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != UUID_CCC) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = ConnectionState.Failed("notification enable failed")
                closeGatt()
                return
            }
            // Link is ready — the manager runs APP_START + the poll loop.
            _state.value = ConnectionState.Connected(
                gatt?.device?.address ?: "MeshCore",
                useTLS = false,
            )
        }

        // Android 13+ value-carrying overload.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid != UUID_UART_TX) return
            emitFrame(value)
        }

        // Pre-13 overload — read value off the characteristic.
        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            if (characteristic.uuid != UUID_UART_TX) return
            emitFrame(characteristic.value)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            // Pop the frame we just attempted and drain the next.
            synchronized(writeLock) {
                writeQueue.pollFirst()
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "MeshCore BLE write failed: status=$status")
            }
            drainWriteQueue()
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) _rssi.value = rssi
        }
    }

    private fun emitFrame(value: ByteArray?) {
        val payload = value ?: return
        if (payload.isEmpty()) return
        _bytesReceived.value += payload.size.toLong()
        _frames.tryEmit(payload.copyOf())
    }

    /** Sample link RSSI (best-effort; result lands on [rssi]). */
    fun requestRssi() {
        runCatching { gatt?.readRemoteRssi() }
    }

    // endregion

    data class BleScanResult(
        val name: String?,
        val address: String,
        val rssi: Int,
    )

    companion object {
        private const val TAG = "MeshCoreBle"

        val UUID_UART_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val UUID_UART_RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val UUID_UART_TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        val UUID_CCC: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        /** Firmware BLE name prefix (`BLE_NAME_PREFIX`). */
        const val NAME_PREFIX = "MeshCore-"

        /** Default pairing PIN for screenless nodes (firmware `BLE_PIN_CODE`). */
        const val PAIRING_PIN = 123456

        const val REQUESTED_MTU = 512

        /** Runtime BLE permissions (Android 12+). */
        val RUNTIME_PERMISSIONS: Array<String> = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    }
}
