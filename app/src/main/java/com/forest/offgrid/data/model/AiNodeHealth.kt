package com.forest.offgrid.data.model

enum class HealthStatus {
    HEALTHY,
    DEGRADING,
    CRITICAL
}

data class AiNodeHealth(
    val nodeId: String = "LOCAL_NODE",
    val status: HealthStatus = HealthStatus.HEALTHY,
    val batteryPct: Int = 100,
    val batteryVoltageMv: Int = 4100,
    val dischargeRateMvPerHr: Float = -5.0f,
    val temperatureC: Float = 25.0f,
    val hoursRemainingEst: Float = 48.0f,
    val lastUpdatedMillis: Long = System.currentTimeMillis()
)
