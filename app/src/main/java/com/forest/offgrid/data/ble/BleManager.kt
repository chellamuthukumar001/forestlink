package com.forest.offgrid.data.ble

import com.forest.offgrid.util.ChunkedTransferManager
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.forest.offgrid.data.model.ConnectionState
import com.forest.offgrid.data.model.ScannedDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.*

@SuppressLint("MissingPermission") // Permissions handled in Activity
class BleManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: BleManager? = null

        fun getInstance(context: Context): BleManager {
            return instance ?: synchronized(this) {
                instance ?: BleManager(context.applicationContext).also { instance = it }
            }
        }

        // Standard Nordic UART Service UUIDs
        val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val CHAR_TX_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val CHAR_RX_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        

        // Command Prefixes
        const val CMD_TXT = "TXT|"
        const val CMD_ACK = "ACK|"
        const val CMD_SOS = "SOS|"
        const val CMD_MEDIA = "MEDIA|"

        private const val PREFS_NAME = "ble_prefs"
        private const val KEY_LAST_DEVICE = "last_device_address"
        private const val SCAN_DURATION: Long = 10000 // 10 seconds scan
        private const val SCAN_INTERVAL: Long = 2000 // 2 seconds pause

        // Exponential backoff reconnect delays (ms)
        private val RECONNECT_DELAYS = longArrayOf(2000, 4000, 8000, 16000, 30000)
        
        // Specific Target Hardware
        const val TARGET_ESP32_MAC = "1C:DB:D4:45:73:D5"
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager?.adapter
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _connectedDevice = MutableStateFlow<BluetoothDevice?>(null)
    val connectedDevice: StateFlow<BluetoothDevice?> = _connectedDevice

    // Pair<MessageId, MessageContent> - Simple FIFO queue for reliable delivery could be added here
    private val pendingMessages = java.util.concurrent.ConcurrentHashMap<String, String>()
    // Set to track recently received IDs to prevent duplicates (Simple dedup)
    private val receivedMessageIds = Collections.synchronizedSet(HashSet<String>())
    
    private val _incomingMessages = MutableStateFlow<String?>(null)
    val incomingMessages: StateFlow<String?> = _incomingMessages

    // Media reassembly completion: Triple(msgId, mediaType, base64Data)
    private val _mediaReceived = kotlinx.coroutines.flow.MutableSharedFlow<Triple<String, String, String>>(replay = 0)
    val mediaReceived: kotlinx.coroutines.flow.SharedFlow<Triple<String, String, String>> = _mediaReceived

    private val _messageAck = kotlinx.coroutines.flow.MutableSharedFlow<String>(replay = 0)
    val messageAck: kotlinx.coroutines.flow.SharedFlow<String> = _messageAck

    private val _hardwareData = MutableStateFlow<String?>(null)
    val hardwareData: StateFlow<String?> = _hardwareData

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising

    // ── Reconnect Backoff ────────────────────────────────────────────────────
    private val _reconnectCountdown = MutableStateFlow(-1) // -1 = not reconnecting
    val reconnectCountdown: StateFlow<Int> = _reconnectCountdown
    private var reconnectAttempt = 0
    private var lastConnectedDevice: BluetoothDevice? = null
    private var reconnectJob: kotlinx.coroutines.Job? = null

    // ── Write Queue ──────────────────────────────────────────────────────────
    private lateinit var writeQueue: BleWriteQueue
    private val queueScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val writeQueuePending get() = if (::writeQueue.isInitialized) writeQueue.pendingCount else MutableStateFlow(0)

    private val scannedDevicesMap = java.util.concurrent.ConcurrentHashMap<String, ScannedDevice>()
    private val handler = Handler(Looper.getMainLooper())
    private var isScanning = false
    private var userRequestedDisconnect = false
    
    // RSSI Polling
    private val rssiRunnable_ = object : Runnable {
        override fun run() {
            if (_connectionState.value == ConnectionState.CONNECTED && bluetoothGatt != null) {
                try {
                     bluetoothGatt?.readRemoteRssi()
                } catch (e: SecurityException) {
                     Log.e("BleManager", "Error reading RSSI: ${e.message}")
                }
                handler.postDelayed(this, 3000) // Poll every 3 seconds
            }
        }
    }

    private val cleanupRunnable = object : Runnable {
        override fun run() {
            val threshold = System.currentTimeMillis() - 15000 // 15 seconds timeout
            val iterator = scannedDevicesMap.entries.iterator()
            var changed = false
            val connectedAddr = _connectedDevice.value?.address
            
            while (iterator.hasNext()) {
                val entry = iterator.next()
                // Don't remove the currently connected device
                if (entry.key == connectedAddr) {
                     // Keep it "fresh" in the list visuals (optional, but good for UI)
                     continue
                }
                
                if (entry.value.lastSeen < threshold) {
                    iterator.remove()
                    changed = true
                }
            }
            if (changed) {
                _scannedDevices.value = scannedDevicesMap.values.toList().sortedByDescending { it.rssi }
            }
            handler.postDelayed(this, 5000)
        }
    }

    private val scanRunnable = object : Runnable {
        override fun run() {
            // Check connection state to decide if we should scan
            // For now, pause scanning while connected to ensure max throughput for messages
            // But we must ensure the connected device stays in the list (handled by cleanupRunnable)
            if (_connectionState.value == ConnectionState.CONNECTED) {
                // Optionally: We could scan periodically even when connected? 
                // For now, let's keep it quiet to prevent interference
                handler.postDelayed(this, SCAN_INTERVAL)
                return
            }
            
            if (isScanning) {
                stopScanInternal()
                handler.postDelayed(this, SCAN_INTERVAL)
            } else {
                startScanInternal()
                handler.postDelayed(this, SCAN_DURATION)
            }
        }
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun startScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
             Log.e("BleManager", "Bluetooth is disabled or not available")
             return
        }
        
        // Start the intelligent scan cycle
        handler.removeCallbacks(scanRunnable)
        handler.post(scanRunnable)
        
        // Start cleanup task
        handler.removeCallbacks(cleanupRunnable)
        handler.post(cleanupRunnable)
    }

    private fun startScanInternal() {
        if (isScanning) return

        // Do NOT clear previous results to avoid UI shuffling
        // Old devices will be removed by cleanupRunnable if not seen

        val settingsBuilder = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            settingsBuilder.setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            settingsBuilder.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        }
        
        val settings = settingsBuilder.build()
            
        // ENABLED: We only want to see our ForestLink devices OR specific MAC
        val filters: MutableList<android.bluetooth.le.ScanFilter> = mutableListOf(
            android.bluetooth.le.ScanFilter.Builder()
                .setServiceUuid(android.os.ParcelUuid(SERVICE_UUID))
                .build()
        )
        
        // Add specific MAC filter
        if (android.bluetooth.BluetoothAdapter.checkBluetoothAddress(TARGET_ESP32_MAC)) {
             filters.add(android.bluetooth.le.ScanFilter.Builder()
                .setDeviceAddress(TARGET_ESP32_MAC)
                .build())
        }

        try {
            bluetoothAdapter?.bluetoothLeScanner?.startScan(filters, settings, scanCallback)
            isScanning = true
            // ... (rest of function)
        } catch (e: SecurityException) {
            Log.e("BleManager", "Permission denied for scanning: ${e.message}")
        } catch (e: Exception) {
            Log.e("BleManager", "Error starting scan: ${e.message}")
        }
	}

    fun stopScan() {
        handler.removeCallbacks(scanRunnable)
        // We can leave the cleanup task running as it's lightweight
        stopScanInternal()
    }

    private fun stopScanInternal() {
        if (!isScanning) return

        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            isScanning = false
        } catch (e: SecurityException) {
            Log.e("BleManager", "Permission denied for stopping scan: ${e.message}")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val scanRecord = result.scanRecord
            val name = device.name ?: scanRecord?.deviceName ?: "Unknown"

            // Log.d("BleManager", "Scanned: $name (${device.address})")

            // AUTO-CONNECT to Target ESP32 - DISABLED to allow manual selection
            /*
            if (device.address.equals(TARGET_ESP32_MAC, ignoreCase = true)) {
                Log.d("BleManager", "FOUND TARGET ESP32! Initiating Auto-Connect...")
                if (_connectionState.value == ConnectionState.DISCONNECTED || _connectionState.value == ConnectionState.SCANNING) {
                     connect(device)
                     return // Stop processing scan result as we are connecting
                }
            }
            */
            
            // STRICT FILTER: Only show devices that match our ESP32 naming convention or have our specific UUID
            val isTargetDevice = device.address.equals(TARGET_ESP32_MAC, ignoreCase = true) || 
                                 name.contains("FOREST", ignoreCase = true) || 
                                 name.contains("ESP32", ignoreCase = true) ||
                                 result.scanRecord?.serviceUuids?.contains(android.os.ParcelUuid(SERVICE_UUID)) == true

            if (isTargetDevice) {
                // ... (rest of logic)
                val existing = scannedDevicesMap[device.address]
                if (existing == null || Math.abs(existing.rssi - result.rssi) > 5 || (System.currentTimeMillis() - existing.lastSeen) > 2000) {
                     val scannedDevice = ScannedDevice(device, name, result.rssi, System.currentTimeMillis())
                     scannedDevicesMap[device.address] = scannedDevice
                     _scannedDevices.value = scannedDevicesMap.values.toList().sortedByDescending { it.rssi }
                } else {
                     scannedDevicesMap[device.address] = existing.copy(lastSeen = System.currentTimeMillis())
                }
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
             // ...
             Log.e("BleManager", "Scan failed with error: $errorCode")
             isScanning = false
             _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    private val connectionTimeoutRunnable = Runnable {
        if (_connectionState.value == ConnectionState.CONNECTING) {
            Log.e("BleManager", "Connection timed out")
            disconnect()
            // Optional: Retry?
        }
    }

    fun connect(device: BluetoothDevice) {
        userRequestedDisconnect = false
        reconnectAttempt = 0
        reconnectJob?.cancel()
        _reconnectCountdown.value = -1
        if (isScanning) stopScan()
        lastConnectedDevice = device
        Log.d("BleManager", "Starting connection to ${device.address}")

        // Check if already connected to this device
        if (connectedDevice.value?.address == device.address && _connectionState.value == ConnectionState.CONNECTED) {
            Log.d("BleManager", "Already connected to ${device.address}")
            return
        }

        // Initialise write queue
        if (::writeQueue.isInitialized) writeQueue.drain()
        writeQueue = BleWriteQueue(
            onWrite = { bytes -> writeCharacteristicRaw(bytes) },
            scope = queueScope
        )
        writeQueue.start()

        _connectionState.value = ConnectionState.CONNECTING
        handler.removeCallbacks(connectionTimeoutRunnable)
        handler.postDelayed(connectionTimeoutRunnable, 10000)

        handler.postDelayed({
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    bluetoothGatt = device.connectGatt(context, false, gattCallback)
                }
            } catch (e: SecurityException) {
                Log.e("BleManager", "Permission denied for connection: ${e.message}")
                _connectionState.value = ConnectionState.DISCONNECTED
                handler.removeCallbacks(connectionTimeoutRunnable)
            }
        }, 200)
    }

    /** Attempt silent reconnect to last known device with exponential backoff. */
    fun triggerReconnect() {
        val target = lastConnectedDevice ?: run {
            val lastMac = prefs.getString(KEY_LAST_DEVICE, null) ?: return
            try {
                bluetoothAdapter?.getRemoteDevice(lastMac)
            } catch (e: Exception) { null }
        } ?: return

        if (userRequestedDisconnect) return
        if (_connectionState.value == ConnectionState.CONNECTED || _connectionState.value == ConnectionState.CONNECTING) return

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delayMs = RECONNECT_DELAYS.getOrElse(reconnectAttempt) { RECONNECT_DELAYS.last() }
            val delaySec = (delayMs / 1000).toInt()
            Log.d("BleManager", "Reconnect attempt ${reconnectAttempt + 1} in ${delaySec}s")

            // Countdown for UI
            for (t in delaySec downTo 1) {
                _reconnectCountdown.value = t
                kotlinx.coroutines.delay(1000)
                if (_connectionState.value == ConnectionState.CONNECTED) {
                    _reconnectCountdown.value = -1
                    return@launch
                }
            }
            _reconnectCountdown.value = -1

            if (!userRequestedDisconnect && _connectionState.value == ConnectionState.DISCONNECTED) {
                reconnectAttempt++
                connect(target)
            }
        }
    }
    


    private val _rssi = MutableStateFlow(0)
    val rssi: StateFlow<Int> = _rssi

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e("BleManager", "Connection failed with status: $status")
                _connectionState.value = ConnectionState.DISCONNECTED
                bluetoothGatt?.close()
                bluetoothGatt = null
                _connectedDevice.value = null
                // Trigger backoff reconnect on failure
                if (!userRequestedDisconnect) triggerReconnect()
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                handler.removeCallbacks(connectionTimeoutRunnable)
                _connectionState.value = ConnectionState.CONNECTED
                _connectedDevice.value = gatt.device
                reconnectAttempt = 0 // reset backoff on success
                reconnectJob?.cancel()
                _reconnectCountdown.value = -1
                Log.d("BleManager", "Connected to ${gatt.device.address}")

                prefs.edit().putString(KEY_LAST_DEVICE, gatt.device.address).apply()
                handler.post(rssiRunnable_)

                try {
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                } catch (e: SecurityException) {
                    Log.e("BleManager", "Permission denied for requestConnectionPriority: ${e.message}")
                }

                // ── Fixed: request MTU first, then discover services in onMtuChanged ──
                try {
                    Log.d("BleManager", "Requesting MTU 517")
                    gatt.requestMtu(517)
                } catch (e: SecurityException) {
                    Log.e("BleManager", "Permission denied for requestMtu: ${e.message}")
                    // Fallback: discover immediately
                    handler.postDelayed({ tryDiscoverServices(gatt) }, 600)
                }

                if (isScanning) stopScan()

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handler.removeCallbacks(connectionTimeoutRunnable)
                _connectionState.value = ConnectionState.DISCONNECTED
                _connectedDevice.value = null
                Log.d("BleManager", "Disconnected from ${gatt.device.address}")
                bluetoothGatt?.close()
                bluetoothGatt = null
                handler.removeCallbacks(rssiRunnable_)
                if (::writeQueue.isInitialized) writeQueue.drain()

                // Trigger backoff reconnect unless user explicitly disconnected
                if (!userRequestedDisconnect) triggerReconnect()
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w("BleManager", "Write failed with status: $status")
            }
            // Unblock the write queue consumer regardless of status
            if (::writeQueue.isInitialized) writeQueue.onWriteComplete()
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _rssi.value = rssi
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BleManager", "MTU Changed to: $mtu – now discovering services")
            } else {
                Log.e("BleManager", "MTU Change failed ($status) – discovering services anyway")
            }
            // ── Fixed: discover services AFTER MTU negotiation completes ──
            gatt?.let { tryDiscoverServices(it) }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    txCharacteristic = service.getCharacteristic(CHAR_TX_UUID)
                    val rxCharacteristic = service.getCharacteristic(CHAR_RX_UUID)
                    
                    if (rxCharacteristic != null) {
                        try {
                            gatt.setCharacteristicNotification(rxCharacteristic, true)
                            val descriptor = rxCharacteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
                            if (descriptor != null) {
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(descriptor)
                            }
                        } catch (e: SecurityException) {
                            Log.e("BleManager", "Permission error setting notification")
                        }
                    }
                } else {
                    Log.w("BleManager", "Target Service not found")
                }
            } else {
                Log.w("BleManager", "onServicesDiscovered received: $status")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == CHAR_RX_UUID) {
                // Handle different deprecated methods if needed, but for now assuming standard byte access
                val dataBytes = characteristic.value
                val data = String(dataBytes)
                handleIncomingData(data)
            }
        }
    }

    private val _nodeData = MutableStateFlow<String?>(null)
    val nodeData: StateFlow<String?> = _nodeData

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    private fun handleIncomingData(data: String?) {
        if (data == null || data.isBlank()) return
        Log.d("BleManager", "Received RAW: $data")
        
        try {
            when {
                data.startsWith(CMD_TXT) -> {
                    val content = data.substringAfter(CMD_TXT)
                    
                    // Intercept auto-GPS messages from hardware
                    if (content.contains(": GPS: ")) {
                        val coords = content.substringAfter(": GPS: ").replace(", ", ",")
                        _hardwareData.value = "GPS|$coords"
                        return
                    }
                    
                    // Parse structured message: msgId|senderName|msgBody
                    val parts = content.split("|", limit = 3)
                    if (parts.size >= 2) {
                        val msgId = parts[0]
                        val senderName = if (parts.size == 3) parts[1] else "REMOTE"
                        val msgBody = if (parts.size == 3) parts[2] else parts[1]
                        
                        sendAck(msgId)
                        
                        if (!receivedMessageIds.contains(msgId)) {
                            receivedMessageIds.add(msgId)
                            _incomingMessages.value = "$senderName: $msgBody"
                            
                            if (receivedMessageIds.size > 200) receivedMessageIds.clear()
                        }
                    } else {
                        // Simple text
                        _incomingMessages.value = content
                    }
                }
                
                data.startsWith(CMD_ACK) -> {
                    val msgId = data.substringAfter(CMD_ACK).trim()
                    Log.d("BleManager", "ACK received: $msgId")
                    pendingMessages.remove(msgId)
                    scope.launch { _messageAck.emit(msgId) }
                }
                
                data.startsWith(CMD_SOS) -> {
                    val sosContent = data.substringAfter(CMD_SOS)
                    _incomingMessages.value = "SOS: $sosContent"
                }
                
                data.startsWith(CMD_MEDIA) -> {
                    // Handle chunked media (voice/image)
                    val result = ChunkedTransferManager.processChunk(data)
                    if (result != null) {
                        // Reassembly complete
                        scope.launch { _mediaReceived.emit(result) }
                    }
                }
                
                data.trim().startsWith("{") -> {
                    _nodeData.value = data.trim()
                }
                
                data.startsWith("NODE|") -> {
                    _nodeData.value = data
                }
                
                data.contains("GPS|") || data.contains("BAT|") ||
                data.startsWith("AI_ROUTE|") || data.startsWith("HEALTH|") ||
                data.startsWith("ANOMALY|") || data.startsWith("TLM|") -> {
                    _hardwareData.value = data
                }
            }
        } catch (e: Exception) {
            Log.e("BleManager", "Data parsing error: ${e.message}")
        }
    }
    
    private fun sendAck(msgId: String) {
        val ackPacket = "$CMD_ACK$msgId"
        sendDataInternal(ackPacket)
    }

    fun sendData(data: String, msgId: String? = null) {
        if (msgId != null) {
            val senderName = android.os.Build.MODEL ?: "AndroidPhone"
            val packet = "$CMD_TXT$msgId|$senderName|$data"
            pendingMessages[msgId] = packet
            sendDataInternal(packet)
        } else {
            sendDataInternal(data)
        }
    }
    
    /**
     * Sends chunked media data via the serialized write queue.
     * Chunks are delivered in order with no race conditions.
     */
    fun sendChunkedData(chunks: List<String>) {
        scope.launch {
            chunks.forEachIndexed { index, chunk ->
                sendDataInternal(chunk)
                Log.d("BleManager", "Queued media chunk ${index + 1}/${chunks.size}")
            }
            Log.d("BleManager", "All ${chunks.size} media chunks queued")
        }
    }

    /** Raw write used by BleWriteQueue – do NOT call directly for messages. */
    private fun writeCharacteristicRaw(bytes: ByteArray): Boolean {
        val char = txCharacteristic ?: return false
        val gatt = bluetoothGatt ?: return false
        return try {
            char.value = bytes
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            gatt.writeCharacteristic(char)
        } catch (e: SecurityException) {
            Log.e("BleManager", "Security Exception in raw write: ${e.message}")
            false
        }
    }

    private fun sendDataInternal(data: String) {
        val bytes = data.toByteArray()

        // 1. Send as Client via the serialized write queue (prevents packet loss)
        if (bluetoothGatt != null && txCharacteristic != null && ::writeQueue.isInitialized) {
            scope.launch { writeQueue.enqueue(bytes) }
        }

        // 2. Send as Server (Notify connected Clients via GATT server)
        if (bluetoothGattServer != null && connectedClients.isNotEmpty()) {
            val service = bluetoothGattServer?.getService(SERVICE_UUID)
            val charTx = service?.getCharacteristic(CHAR_RX_UUID)
            if (charTx != null) {
                charTx.value = bytes
                connectedClients.forEach { device ->
                    try {
                        bluetoothGattServer?.notifyCharacteristicChanged(device, charTx, false)
                    } catch (e: SecurityException) {
                        Log.e("BleManager", "Error notifying client: ${e.message}")
                    }
                }
            }
        }
    }

    private fun tryDiscoverServices(gatt: BluetoothGatt) {
        handler.postDelayed({
            try {
                gatt.discoverServices()
            } catch (e: SecurityException) {
                Log.e("BleManager", "Permission denied for discoverServices: ${e.message}")
            }
        }, 300)
    }

    private var bluetoothGattServer: BluetoothGattServer? = null
    private var bluetoothLeAdvertiser: android.bluetooth.le.BluetoothLeAdvertiser? = null

    private val connectedClients = mutableListOf<BluetoothDevice>()

    // Server Callback
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("BleManager", "Device connected to server: ${device.address}")
                connectedClients.add(device)
                _connectionState.value = ConnectionState.CONNECTED
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("BleManager", "Device disconnected from server: ${device.address}")
                connectedClients.remove(device)
                if (connectedClients.isEmpty() && bluetoothGatt == null) {
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
            }
        }
        
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)
            
            // Client writes to 0002 (CHAR_TX_UUID). Server receives on 0002.
            if (characteristic.uuid == CHAR_TX_UUID) { 
                val message = String(value)
                Log.d("BleManager", "Server received: $message")
                handleIncomingData(message) 

                if (responseNeeded) {
                    bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
            } else {
                 if (responseNeeded) {
                    bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
            }
        }
        
         override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (responseNeeded) {
                 bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }
    }

    private val advertiseCallback = object : android.bluetooth.le.AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: android.bluetooth.le.AdvertiseSettings) {
            Log.d("BleManager", "Advertising started successfully")
            _isAdvertising.value = true
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e("BleManager", "Advertising failed: $errorCode")
            _isAdvertising.value = false
        }
    }

    fun startAdvertising() {
        if (bluetoothLeAdvertiser != null) return // Already advertising

        bluetoothLeAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (bluetoothLeAdvertiser == null) {
            Log.e("BleManager", "Advertiser not available")
            return
        }

        setupGattServer()

        val settings = android.bluetooth.le.AdvertiseSettings.Builder()
            .setAdvertiseMode(android.bluetooth.le.AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(android.bluetooth.le.AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        // Use ParcelUuid from android.os
        val advertiseData = android.bluetooth.le.AdvertiseData.Builder()
            .setIncludeDeviceName(false) // IMPORTANT: Name often exceeds 31 bytes causing failure
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(android.os.ParcelUuid(SERVICE_UUID))
            .build()

        val scanResponse = android.bluetooth.le.AdvertiseData.Builder()
            .setIncludeDeviceName(true) // Put name in scan response (secondary packet)
            .build()

        try {
            bluetoothLeAdvertiser?.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
            Log.d("BleManager", "Advertising started with separate scan response")
        } catch (e: SecurityException) {
             Log.e("BleManager", "Error starting advertising: ${e.message}")
        } catch (e: Exception) {
             Log.e("BleManager", "Generic error starting advertising: ${e.message}")
        }
    }

    private fun setupGattServer() {
        if (bluetoothGattServer != null) return

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        try {
             bluetoothGattServer = bluetoothManager.openGattServer(context, gattServerCallback)
             
             // Create Service
             val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
             
             // Create Characteristics
             // RX (0002) - Write (Client writes here). Named CHAR_TX_UUID in client config.
             val charRx = BluetoothGattCharacteristic(
                 CHAR_TX_UUID, 
                 BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                 BluetoothGattCharacteristic.PERMISSION_WRITE
             )
             
             // TX (0003) - Notify (Client listens here). Named CHAR_RX_UUID in client config.
             val charTx = BluetoothGattCharacteristic(
                 CHAR_RX_UUID,
                 BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
                 BluetoothGattCharacteristic.PERMISSION_READ
             )
             
             val configDescriptor = BluetoothGattDescriptor(CLIENT_CHARACTERISTIC_CONFIG, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)
             charTx.addDescriptor(configDescriptor)
             
             service.addCharacteristic(charRx)
             service.addCharacteristic(charTx)
             
             bluetoothGattServer?.addService(service)
             Log.d("BleManager", "GATT Server setup complete")
             
        } catch (e: SecurityException) {
             Log.e("BleManager", "Error setting up GATT Server: ${e.message}")
        }
    }

    private fun stopAdvertising() {
        try {
            bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            bluetoothLeAdvertiser = null
            bluetoothGattServer?.close()
            bluetoothGattServer = null
            Log.d("BleManager", "Advertising stopped")
        } catch (e: SecurityException) {
             Log.e("BleManager", "Error stopping advertising")
        }
    }

    fun disconnect() {
        userRequestedDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        _reconnectCountdown.value = -1
        if (::writeQueue.isInitialized) writeQueue.drain()
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt?.disconnect()
                bluetoothGatt?.close()
            } catch (e: SecurityException) {
                Log.e("BleManager", "Error closing gatt")
            }
            bluetoothGatt = null
        }
        _connectionState.value = ConnectionState.DISCONNECTED
    }
}
