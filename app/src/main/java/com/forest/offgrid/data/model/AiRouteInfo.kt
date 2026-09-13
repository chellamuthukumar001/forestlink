package com.forest.offgrid.data.model

enum class RoutingMode {
    AI_DIRECT,
    EMERGENCY_PRIORITY,
    DETERMINISTIC_FALLBACK
}

data class AiRouteInfo(
    val destNodeId: String = "ALL",
    val nextHopNodeId: String = "DIRECT",
    val reliabilityScore: Float = 1.0f,
    val mode: RoutingMode = RoutingMode.AI_DIRECT,
    val reason: String = "Direct link active",
    val lastUpdatedMillis: Long = System.currentTimeMillis()
)
