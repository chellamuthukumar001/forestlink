package com.forest.offgrid.data.model

data class HardwareState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val batteryLevel: Int = 0, // 0-100
    val gpsLat: Double = 0.0,
    val gpsLng: Double = 0.0,
    val lastUpdate: Long = 0L,
    val rssi: Int = 0,
    val isAdvertising: Boolean = false
)

enum class ConnectionState {
    DISCONNECTED, SCANNING, CONNECTING, CONNECTED
}
