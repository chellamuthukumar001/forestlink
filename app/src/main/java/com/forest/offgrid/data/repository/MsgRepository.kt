package com.forest.offgrid.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.LiveData
import com.forest.offgrid.data.ble.BleManager
import com.forest.offgrid.data.local.AppDatabase
import com.forest.offgrid.data.model.*
import com.forest.offgrid.util.SecurityUtils
import com.forest.offgrid.util.NotificationUtils
import com.forest.offgrid.util.MediaCompressor
import com.forest.offgrid.util.ChunkedTransferManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

class MsgRepository private constructor(private val context: Context, private val bleManager: BleManager) {

    companion object {
        @Volatile
        private var instance: MsgRepository? = null

        fun getInstance(context: Context, bleManager: BleManager): MsgRepository {
            return instance ?: synchronized(this) {
                instance ?: MsgRepository(context.applicationContext, bleManager).also { instance = it }
            }
        }
    }

    private val msgDao = AppDatabase.getDatabase(context).messageDao()
    private val scope = CoroutineScope(Dispatchers.IO)

    val allMessages: LiveData<List<Message>> = msgDao.getAllMessages()

    private val _hardwareState = MutableStateFlow(HardwareState())
    val hardwareState: StateFlow<HardwareState> = _hardwareState

    val scannedDevices: StateFlow<List<ScannedDevice>> = bleManager.scannedDevices
    val connectedDevice = bleManager.connectedDevice

    init {
        // Initialize Auto Reconnect


        // Observe BLE Connection
        scope.launch {
            bleManager.connectionState.collect { state ->
                _hardwareState.value = _hardwareState.value.copy(connectionState = state)
            }
        }

        // Observe Incoming Messages from BLE
        scope.launch {
            bleManager.incomingMessages.collect { rawMsg ->
                rawMsg?.let {
                    handleRawMessage(it)
                }
            }
        }

        // Observe Hardware Data (GPS/Bat)
        scope.launch {
            bleManager.hardwareData.collect { data ->
                data?.let { parseHardwareData(it) }
            }
        }
        
        // Observe RSSI
        scope.launch {
             bleManager.rssi.collect { rssi ->
                 _hardwareState.value = _hardwareState.value.copy(rssi = rssi)
             }
        }
        
        // Observe ACKs
        scope.launch {
             bleManager.messageAck.collect { idStr ->
                 try {
                     val id = idStr.toLong()
                     msgDao.updateMessageStatus(id, MessageStatus.DELIVERED)
                     Log.d("MsgRepo", "Message $id marked as DELIVERED")
                 } catch (e: NumberFormatException) {
                     Log.e("MsgRepo", "Invalid ACK ID: $idStr")
                 }
             }
        }

        // Observe Advertising State
        scope.launch {
             bleManager.isAdvertising.collect { advertising ->
                 _hardwareState.value = _hardwareState.value.copy(isAdvertising = advertising)
             }
        }
        
        // Observe completed media reassembly from BLE
        scope.launch {
            bleManager.mediaReceived.collect { (msgId, mediaType, base64Data) ->
                handleReceivedMedia(msgId, mediaType, base64Data)
            }
        }
    }

    private fun handleRawMessage(raw: String) {
        scope.launch {
            try {
                var actualMessage = raw
                var senderNode = "REMOTE_RANGER"
                var locationStr = ""

                // Extract sender if present (e.g., "NODE_001: ...")
                if (actualMessage.contains(": ")) {
                    val parts = actualMessage.split(": ", limit = 2)
                    if (parts.size == 2) {
                        senderNode = parts[0]
                        actualMessage = parts[1]
                    }
                }

                // Extract appended location if present (e.g., " ... [12.9716, 77.5946]")
                if (actualMessage.contains(" [") && actualMessage.endsWith("]")) {
                    val locStart = actualMessage.lastIndexOf(" [")
                    locationStr = actualMessage.substring(locStart)
                    actualMessage = actualMessage.substring(0, locStart)
                }

                // Incoming Text (Decryption handled here)
                val finalContent = if (actualMessage.startsWith("SOS:")) {
                    actualMessage // Don't decrypt SOS keyword if it's already plain
                } else {
                    SecurityUtils.decrypt(actualMessage)
                }
                
                // Save to local DB
                val message = Message(
                    content = finalContent + locationStr,
                    senderId = senderNode,
                    receiverId = "ME",
                    isIncoming = true,
                    status = MessageStatus.DELIVERED,
                    temperature = 0f,
                    humidity = 0f
                )
                
                msgDao.insertMessage(message)
                
                // Trigger Notification
                NotificationUtils.showNotification(context, "Forest Signal: $senderNode", finalContent)
                
            } catch (e: Exception) {
                Log.e("MsgRepo", "Error handling raw message: ${e.message}")
            }
        }
    }
    
    /**
     * Handles a fully reassembled media message (voice or image).
     */
    private fun handleReceivedMedia(msgId: String, mediaType: String, base64Data: String) {
        scope.launch {
            try {
                val mediaDir = File(context.filesDir, "media")
                if (!mediaDir.exists()) mediaDir.mkdirs()
                
                val extension = if (mediaType == "VOICE") "m4a" else "jpg"
                val outputFile = File(mediaDir, "received_${msgId}_${System.currentTimeMillis()}.$extension")
                
                MediaCompressor.base64ToFile(base64Data, outputFile)
                
                val type = if (mediaType == "VOICE") MessageType.VOICE else MessageType.IMAGE
                
                val message = Message(
                    content = if (type == MessageType.VOICE) "🎤 Voice Message" else "📷 Image",
                    senderId = "REMOTE_RANGER",
                    receiverId = "ME",
                    isIncoming = true,
                    type = type,
                    status = MessageStatus.DELIVERED,
                    mediaFilePath = outputFile.absolutePath,
                    mediaSize = outputFile.length()
                )
                
                msgDao.insertMessage(message)
                
                val notifText = if (type == MessageType.VOICE) "Voice message received" else "Image received"
                NotificationUtils.showNotification(context, "Forest Signal", notifText)
                
                Log.d("MsgRepo", "Media message saved: ${outputFile.absolutePath} (${outputFile.length()} bytes)")
                
            } catch (e: Exception) {
                Log.e("MsgRepo", "Error handling received media: ${e.message}")
            }
        }
    }

    private fun parseHardwareData(data: String) {
        // Format: GPS|lat,lng|BAT|80 OR AI_ROUTE|dest|nextHop|0.85|AI_DIRECT OR HEALTH|HEALTHY|48.0 OR ANOMALY|STORM|CRITICAL|desc
        try {
            var lat = _hardwareState.value.gpsLat
            var lng = _hardwareState.value.gpsLng
            var bat = _hardwareState.value.batteryLevel
            var routeInfo = _hardwareState.value.aiRouteInfo
            var health = _hardwareState.value.nodeHealth
            val anomalies = _hardwareState.value.activeAnomalies.toMutableList()

            val parts = data.replace(":", "|").split("|")
            var i = 0
            while (i < parts.size) {
                when (parts[i]) {
                    "GPS" -> {
                        val coordStr = parts.getOrNull(i + 1)
                        if (coordStr != null && coordStr.contains(",")) {
                            val coords = coordStr.split(",")
                            lat = coords[0].toDoubleOrNull() ?: lat
                            lng = coords[1].toDoubleOrNull() ?: lng
                        }
                        i += 2
                    }
                    "BAT" -> {
                        bat = parts.getOrNull(i + 1)?.toIntOrNull() ?: bat
                        i += 2
                    }
                    "AI_ROUTE" -> {
                        // AI_ROUTE|<dest>|<nextHop>|<relScore>|<mode>
                        val dest = parts.getOrNull(i + 1) ?: "ALL"
                        val next = parts.getOrNull(i + 2) ?: "DIRECT"
                        val score = parts.getOrNull(i + 3)?.toFloatOrNull() ?: 1.0f
                        val modeStr = parts.getOrNull(i + 4) ?: "AI_DIRECT"
                        val mode = try { RoutingMode.valueOf(modeStr) } catch (e: Exception) { RoutingMode.AI_DIRECT }
                        routeInfo = AiRouteInfo(
                            destNodeId = dest,
                            nextHopNodeId = next,
                            reliabilityScore = score,
                            mode = mode,
                            reason = "AI next-hop active"
                        )
                        i += 5
                    }
                    "HEALTH" -> {
                        // HEALTH|<status>|<hoursRemaining>
                        val statusStr = parts.getOrNull(i + 1) ?: "HEALTHY"
                        val hours = parts.getOrNull(i + 2)?.toFloatOrNull() ?: 24.0f
                        val status = try { HealthStatus.valueOf(statusStr) } catch (e: Exception) { HealthStatus.HEALTHY }
                        health = health.copy(
                            status = status,
                            batteryPct = bat,
                            hoursRemainingEst = hours,
                            lastUpdatedMillis = System.currentTimeMillis()
                        )
                        i += 3
                    }
                    "ANOMALY" -> {
                        // ANOMALY|<type>|<severity>|<desc>
                        val type = parts.getOrNull(i + 1) ?: "NETWORK_EVENT"
                        val sevStr = parts.getOrNull(i + 2) ?: "MEDIUM"
                        val desc = parts.getOrNull(i + 3) ?: "Abnormal network activity detected"
                        val sev = try { AlertSeverity.valueOf(sevStr) } catch (e: Exception) { AlertSeverity.MEDIUM }
                        anomalies.add(0, NetworkAnomalyAlert(
                            alertId = "ALERT_${System.currentTimeMillis()}",
                            alertType = type,
                            severity = sev,
                            description = desc
                        ))
                        i += 4
                    }
                    else -> {
                        i++
                    }
                }
            }
            _hardwareState.value = _hardwareState.value.copy(
                gpsLat = lat,
                gpsLng = lng,
                batteryLevel = bat,
                aiRouteInfo = routeInfo,
                nodeHealth = health,
                activeAnomalies = anomalies.take(10),
                lastUpdate = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e("MsgRepo", "Hardware parse error: ${e.message}")
        }
    }


    suspend fun sendMessage(content: String, isSos: Boolean = false) {
        val type = if (isSos) MessageType.SOS else MessageType.TEXT
        val prefix = if (isSos) BleManager.CMD_SOS else BleManager.CMD_TXT
        
        // Use 0.0f as default until real sensor data is available
        val currentTemp = 0f
        val currentHumidity = 0f
        
        // Save to DB first
        val message = Message(
            content = content,
            senderId = "ME",
            receiverId = "BROADCAST",
            isIncoming = false,
            type = type,
            status = MessageStatus.SENDING,
            temperature = currentTemp,
            humidity = currentHumidity
        )
        val id = msgDao.insertMessage(message)

        // Send via BLE with reliability
        val encrypted = SecurityUtils.encrypt(content)
        val msgId = id.toString() // Use DB ID as reliability ID
        
        // If it's SOS, we might want to blast it without waiting for ACK, 
        // but for now let's use reliable for everything except pure streams.
        // Actually, the new signature supports passing ID.
        
        if (isSos) {
             bleManager.sendData("${BleManager.CMD_SOS}$encrypted") // SOS is fire and forget broadcast usually
        } else {
             bleManager.sendData(encrypted, msgId)
        }
        
        msgDao.updateMessage(message.copy(id = id, status = MessageStatus.SENT))
    }
    
    /**
     * Sends a voice message by compressing, encoding, chunking, and transmitting over BLE.
     */
    suspend fun sendVoiceMessage(filePath: String, durationSeconds: Int) {
        val file = File(filePath)
        if (!file.exists()) {
            Log.e("MsgRepo", "Voice file not found: $filePath")
            return
        }
        
        // Save message to DB first (with SENDING status)
        val message = Message(
            content = "🎤 Voice Message (${durationSeconds}s)",
            senderId = "ME",
            receiverId = "BROADCAST",
            isIncoming = false,
            type = MessageType.VOICE,
            status = MessageStatus.SENDING,
            mediaFilePath = filePath,
            mediaDuration = durationSeconds,
            mediaSize = file.length()
        )
        val id = msgDao.insertMessage(message)
        
        // Convert to Base64
        val base64Data = MediaCompressor.fileToBase64(file)
        
        // Create chunks
        val chunks = ChunkedTransferManager.createChunks(id.toString(), base64Data, "VOICE")
        
        // Send chunks via BLE
        bleManager.sendChunkedData(chunks)
        
        // Mark as sent
        msgDao.updateMessage(message.copy(id = id, status = MessageStatus.SENT))
        Log.d("MsgRepo", "Voice message sent: ${chunks.size} chunks, ${file.length()} bytes")
    }
    
    /**
     * Sends an image message by compressing, encoding, chunking, and transmitting over BLE.
     */
    suspend fun sendImageMessage(uri: Uri) {
        // Compress image to tiny thumbnail
        val compressedFile = MediaCompressor.compressImage(uri, context)
        if (compressedFile == null) {
            Log.e("MsgRepo", "Image compression failed")
            return
        }
        
        // Save to permanent storage
        val savedFile = MediaCompressor.saveMediaFile(compressedFile, context, "img")
        compressedFile.delete() // Clean up cache
        
        // Save message to DB first
        val message = Message(
            content = "📷 Image",
            senderId = "ME",
            receiverId = "BROADCAST",
            isIncoming = false,
            type = MessageType.IMAGE,
            status = MessageStatus.SENDING,
            mediaFilePath = savedFile.absolutePath,
            mediaSize = savedFile.length()
        )
        val id = msgDao.insertMessage(message)
        
        // Convert to Base64
        val base64Data = MediaCompressor.fileToBase64(savedFile)
        
        // Create chunks
        val chunks = ChunkedTransferManager.createChunks(id.toString(), base64Data, "IMAGE")
        
        // Send chunks via BLE
        bleManager.sendChunkedData(chunks)
        
        // Mark as sent
        msgDao.updateMessage(message.copy(id = id, status = MessageStatus.SENT))
        Log.d("MsgRepo", "Image message sent: ${chunks.size} chunks, ${savedFile.length()} bytes")
    }
    

    fun startScanning() = bleManager.startScan()
    fun connect(device: android.bluetooth.BluetoothDevice) = bleManager.connect(device)
    fun disconnect() = bleManager.disconnect()
}
