package com.forest.offgrid.data.model

enum class AlertSeverity {
    LOW,
    MEDIUM,
    CRITICAL
}

data class NetworkAnomalyAlert(
    val alertId: String,
    val alertType: String, // MASS_DROPOUT, MESSAGE_STORM, RELAY_BLACK_HOLE, GPS_JUMP
    val severity: AlertSeverity,
    val description: String,
    val affectedNodes: List<String> = emptyList(),
    val recommendedAction: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
