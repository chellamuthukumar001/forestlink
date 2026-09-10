package com.forest.offgrid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.forest.offgrid.MainActivity
import com.forest.offgrid.data.ble.BleManager
import com.forest.offgrid.data.repository.MsgRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CommunicationService : Service() {

    private lateinit var bleManager: BleManager
    private lateinit var repository: MsgRepository
    private lateinit var mapRepository: com.forest.offgrid.data.repository.MapRepository
    private val scope = CoroutineScope(Dispatchers.IO)
    private val gson = com.google.gson.Gson()

    companion object {
        const val CHANNEL_ID = "OffGridChannel"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
    }

    override fun onCreate() {
        super.onCreate()
        bleManager = BleManager.getInstance(this)
        repository = MsgRepository.getInstance(this, bleManager)
        
        val db = com.forest.offgrid.data.local.AppDatabase.getDatabase(this)
        mapRepository = com.forest.offgrid.data.repository.MapRepository(db.mapNodeDao(), db.breadcrumbDao())
        
        // Start scanning automatically on service start (or based on pref)
        repository.startScanning()
        
        observeBleData()
    }
    
    private fun observeBleData() {
        // Observe Node Data (JSON/CSV from Mesh)
        scope.launch {
            bleManager.nodeData.collect { data ->
                data?.let { handleNodeData(it) }
            }
        }
        
        // Observe Direct Hardware Data (GPS/BAT from Gateway)
        scope.launch {
            bleManager.hardwareData.collect { data ->
                data?.let { handleHardwareData(it) }
            }
        }
    }
    
    private fun handleNodeData(data: String) {
        try {
            if (data.trim().startsWith("{")) {
                // JSON format: {"id":"...","name":"...","lat":...,"lon":...,"type":"HARDWARE","signal":...,"battery":...,"sos":...}
                val json = org.json.JSONObject(data)
                val id = json.optString("id", "UNKNOWN")
                val name = json.optString("name", "Unknown Node")
                val lat = json.optDouble("lat", 0.0)
                val lon = json.optDouble("lon", 0.0)
                val typeStr = json.optString("type", "HARDWARE")
                val signal = json.optInt("signal", 0)
                val battery = json.optInt("battery", 0)
                val isSos = json.optBoolean("sos", false)
                
                val type = try {
                    com.forest.offgrid.data.model.NodeType.valueOf(typeStr)
                } catch (e: Exception) {
                    com.forest.offgrid.data.model.NodeType.HARDWARE_NODE
                }
                
                // Calculate distance if user location is known (optional, ViewModel usually does this)
                // But we need to save the node
                val node = com.forest.offgrid.data.model.MapNode(
                    deviceId = id,
                    deviceName = name,
                    latitude = lat,
                    longitude = lon,
                    nodeType = type,
                    signalStrength = signal,
                    batteryLevel = battery,
                    isSOS = isSos,
                    lastSeen = System.currentTimeMillis()
                )
                
                scope.launch {
                    mapRepository.insertNode(node)
                }
                
            } else if (data.startsWith("NODE|")) {
                // CSV format: NODE|ID|LAT|LON|SIGNAL|BAT|SOS
                val parts = data.split("|")
                if (parts.size >= 4) {
                    val id = parts[1]
                    val lat = parts[2].toDoubleOrNull() ?: 0.0
                    val lon = parts[3].toDoubleOrNull() ?: 0.0
                    // ... optional other fields
                    
                    val node = com.forest.offgrid.data.model.MapNode(
                        deviceId = id,
                        deviceName = "Node $id",
                        latitude = lat,
                        longitude = lon,
                        nodeType = com.forest.offgrid.data.model.NodeType.HARDWARE_NODE,
                        lastSeen = System.currentTimeMillis()
                    )
                    
                    scope.launch {
                        mapRepository.insertNode(node)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CommService", "Error parsing node data: ${e.message}")
        }
    }
    
    private fun handleHardwareData(data: String) {
        // Format: GPS|lat,lng|BAT|80
        // Updates the currently connected device
        val device = bleManager.connectedDevice.value ?: return
        
        try {
            var lat = 0.0
            var lon = 0.0
            var bat = 0
            
            val parts = data.split("|")
            for (i in parts.indices) {
                when (parts[i]) {
                    "GPS" -> {
                        val coords = parts.getOrNull(i+1)?.split(",")
                        if (coords?.size == 2) {
                            lat = coords[0].toDoubleOrNull() ?: 0.0
                            lon = coords[1].toDoubleOrNull() ?: 0.0
                        }
                    }
                    "BAT" -> {
                        bat = parts.getOrNull(i+1)?.toIntOrNull() ?: 0
                    }
                }
            }
            
            if (lat != 0.0 && lon != 0.0) {
                val node = com.forest.offgrid.data.model.MapNode(
                    deviceId = device.address, // Connect via MAC
                    deviceName = device.name ?: "Gateway",
                    latitude = lat,
                    longitude = lon,
                    nodeType = com.forest.offgrid.data.model.NodeType.HARDWARE_NODE, // Or GATEWAY if we had it
                    batteryLevel = bat,
                    lastSeen = System.currentTimeMillis()
                )
                
                scope.launch {
                    mapRepository.insertNode(node)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CommService", "Error parsing hardware data: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            bleManager.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val notification = createNotification()
        startForeground(1, notification)

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Disconnect when app is cleared from recent tasks
        bleManager.disconnect()
        stopSelf()
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Forest Link Active")
            .setContentText("Monitoring for signals...")
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Use default launcher icon for now
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Forest Communication Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // We don't bind, we use singleton Repo/ServiceLocator pattern for data access
    }
}
