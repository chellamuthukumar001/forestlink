package com.forest.offgrid.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a device/node on the map
 */
@Entity(tableName = "map_nodes")
data class MapNode(
    @PrimaryKey val deviceId: String,
    val deviceName: String,
    val latitude: Double,
    val longitude: Double,
    val nodeType: NodeType,
    val signalStrength: Int = 0, // 0-100
    val batteryLevel: Int = 100, // 0-100
    val lastSeen: Long = System.currentTimeMillis(),
    val isActive: Boolean = true,
    val isSOS: Boolean = false,
    val distance: Float = 0f, // Distance from user in meters
    val additionalInfo: String? = null
)

enum class NodeType {
    CURRENT_USER,      // Blue - Current user device
    NEARBY_DEVICE,     // Green - Other trekker/guard device
    HUB,              // Purple - Central hub/base station
    HARDWARE_NODE,    // Yellow - Hardware relay node
    SOS_ALERT         // Red pulsing - Emergency alert
}

/**
 * Represents a connection between two nodes for mesh visualization
 */
data class MeshConnection(
    val fromDeviceId: String,
    val toDeviceId: String,
    val signalQuality: Int = 100, // 0-100
    val hopCount: Int = 1,
    val isActive: Boolean = true
)

/**
 * User's breadcrumb trail for tracking movement
 */
@Entity(tableName = "breadcrumbs")
data class BreadcrumbPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long = System.currentTimeMillis(),
    val accuracy: Float = 0f
)
