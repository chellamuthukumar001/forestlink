#ifndef FORESTLINK_MESH_ROUTER_H
#define FORESTLINK_MESH_ROUTER_H

#include <stdint.h>
#include <stdbool.h>
#include "route_scorer_model.h"
#include "../../common/telemetry_packet.h"

#ifdef __cplusplus
extern "C" {
#endif

// ================= Routing Configuration =================
#define ENABLE_AI_ROUTING                   true  // Feature flag to enable/disable AI routing
#define MAX_NEIGHBORS                       16    // Max neighbor tracking table size
#define NEIGHBOR_EXPIRATION_MS              120000 // 2 minutes link timeout
#define ROUTING_CONFIDENCE_THRESHOLD        0.35f // Fail-safe fallback cutoff (tau_safe)
#define MAX_QUEUE_CAPACITY                  32

typedef enum {
    ROUTING_DECISION_AI_DIRECT = 0,      // Unicast to single optimal AI-selected next-hop
    ROUTING_FALLBACK_DETERMINISTIC = 1,  // AI confidence low or table empty: fallback to flood / shortest-hop
    ROUTING_EMERGENCY_PRIORITY = 2       // SOS priority path (highest reliability, preemption)
} RoutingDecisionType;

typedef struct {
    uint16_t node_id;
    ForestLinkTelemetryPacket last_telemetry;
    uint32_t last_heard_millis;
    float cached_reliability;
    bool is_active;
} NeighborEntry;

typedef struct {
    RoutingDecisionType decision_type;
    uint16_t next_hop_node_id;
    float reliability_score;
    const char *reason;
} NextHopResult;

// ================= Router API =================

/**
 * Initializes the mesh router state and neighbor table.
 */
void mesh_router_init(uint16_t self_node_id);

/**
 * Ingests an incoming 18-byte telemetry packet overheard from a neighbor node.
 * Evaluates reliability using the C++ decision trees and caches in table.
 */
void mesh_router_update_neighbor(const ForestLinkTelemetryPacket *pkt, uint32_t current_millis);

/**
 * Selects the best next-hop neighbor for packet forwarding.
 * If AI routing is disabled or confidence < threshold, automatically falls back
 * to deterministic shortest-hop/flood routing.
 */
NextHopResult mesh_router_select_next_hop(
    uint16_t dest_id,
    bool is_emergency,
    uint32_t current_millis
);

/**
 * Returns current count of fresh, active neighbors.
 */
uint8_t mesh_router_get_active_neighbor_count(uint32_t current_millis);

/**
 * Toggles AI routing at runtime (for A/B testing or fallback).
 */
void mesh_router_set_ai_enabled(bool enabled);
bool mesh_router_is_ai_enabled(void);

#ifdef __cplusplus
}
#endif

#endif // FORESTLINK_MESH_ROUTER_H
