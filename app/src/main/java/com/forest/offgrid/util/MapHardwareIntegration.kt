package com.forest.offgrid.util

import android.util.Log
import com.forest.offgrid.data.model.NodeType
import com.forest.offgrid.ui.map.MapViewModel
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * Hardware Integration Helper for Map System
 * 
 * This class handles parsing BLE data from hardware devices
 * and updating the map with node information
 * 
 * PROTOCOL:
 * Hardware sends GPS coordinates and node info via BLE in JSON format
 * Example: {"id":"NODE_001","name":"Relay 1","lat":12.9716,"lon":77.5946,"type":"HARDWARE","signal":85,"battery":90,"sos":false}
 */
class MapHardwareIntegration(private val mapViewModel: MapViewModel) {

    private val gson = Gson()

    /**
     * Parse incoming BLE data and update map
     * 
     * Expected JSON format:
     * {
     *   "id": "DEVICE_ID",
     *   "name": "Device Name",
     *   "lat": 12.9716,
     *   "lon": 77.5946,
     *   "type": "HARDWARE|HUB|DEVICE|SOS",
     *   "signal": 85,
     *   "battery": 90,
     *   "sos": false
     * }
     */
    fun processHardwareData(bleData: String) {
        try {
            val nodeData = gson.fromJson(bleData, HardwareNodeData::class.java)
            
            // Validate data
            if (!isValidNodeData(nodeData)) {
                Log.w(TAG, "Invalid node data received: $bleData")
                return
            }
            
            // Convert to NodeType
            val nodeType = parseNodeType(nodeData.type)
            
            // Update map
            mapViewModel.processHardwareNodeData(
                deviceId = nodeData.id,
                deviceName = nodeData.name,
                latitude = nodeData.lat,
                longitude = nodeData.lon,
                nodeType = nodeType,
                signalStrength = nodeData.signal,
                batteryLevel = nodeData.battery,
                isSOS = nodeData.sos
            )
            
            Log.d(TAG, "Node updated: ${nodeData.name} at (${nodeData.lat}, ${nodeData.lon})")
            
        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "Failed to parse BLE data: $bleData", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing hardware data", e)
        }
    }

    /**
     * Process raw coordinate data (simple format)
     * Format: "DEVICE_ID,LAT,LON,SIGNAL,BATTERY"
     * Example: "NODE_001,12.9716,77.5946,85,90"
     */
    fun processSimpleCoordinates(rawData: String) {
        try {
            val parts = rawData.split(",")
            if (parts.size < 3) {
                Log.w(TAG, "Invalid coordinate format: $rawData")
                return
            }
            
            val deviceId = parts[0]
            val latitude = parts[1].toDouble()
            val longitude = parts[2].toDouble()
            val signal = if (parts.size > 3) parts[3].toIntOrNull() ?: 50 else 50
            val battery = if (parts.size > 4) parts[4].toIntOrNull() ?: 100 else 100
            
            mapViewModel.processHardwareNodeData(
                deviceId = deviceId,
                deviceName = "Node $deviceId",
                latitude = latitude,
                longitude = longitude,
                nodeType = NodeType.HARDWARE_NODE,
                signalStrength = signal,
                batteryLevel = battery
            )
            
            Log.d(TAG, "Simple node updated: $deviceId at ($latitude, $longitude)")
            
        } catch (e: NumberFormatException) {
            Log.e(TAG, "Failed to parse coordinates: $rawData", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing simple coordinates", e)
        }
    }

    /**
     * Process SOS alert from hardware
     */
    fun processSOS(deviceId: String, latitude: Double, longitude: Double, message: String = "") {
        mapViewModel.processHardwareNodeData(
            deviceId = deviceId,
            deviceName = "SOS: $deviceId",
            latitude = latitude,
            longitude = longitude,
            nodeType = NodeType.SOS_ALERT,
            signalStrength = 100,
            batteryLevel = 0,
            isSOS = true
        )
        
        Log.w(TAG, "SOS Alert from $deviceId at ($latitude, $longitude): $message")
    }

    /**
     * Validate node data
     */
    private fun isValidNodeData(data: HardwareNodeData): Boolean {
        return data.id.isNotBlank() &&
               data.name.isNotBlank() &&
               data.lat >= -90 && data.lat <= 90 &&
               data.lon >= -180 && data.lon <= 180 &&
               data.signal in 0..100 &&
               data.battery in 0..100
    }

    /**
     * Parse node type string to NodeType enum
     */
    private fun parseNodeType(typeString: String): NodeType {
        return when (typeString.uppercase()) {
            "HUB" -> NodeType.HUB
            "HARDWARE", "RELAY" -> NodeType.HARDWARE_NODE
            "DEVICE", "TREKKER", "GUARD" -> NodeType.NEARBY_DEVICE
            "SOS", "EMERGENCY" -> NodeType.SOS_ALERT
            else -> NodeType.NEARBY_DEVICE
        }
    }

    /**
     * Data class for hardware node data
     */
    private data class HardwareNodeData(
        val id: String,
        val name: String,
        val lat: Double,
        val lon: Double,
        val type: String,
        val signal: Int,
        val battery: Int,
        val sos: Boolean = false
    )

    companion object {
        private const val TAG = "MapHardwareIntegration"
    }
}
