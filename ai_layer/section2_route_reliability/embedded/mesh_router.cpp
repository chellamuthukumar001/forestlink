#include "mesh_router.h"
#include <string.h>

static uint16_t s_self_node_id = 0;
static bool s_ai_routing_enabled = ENABLE_AI_ROUTING;
static NeighborEntry s_neighbors[MAX_NEIGHBORS];

void mesh_router_init(uint16_t self_node_id) {
    s_self_node_id = self_node_id;
    s_ai_routing_enabled = ENABLE_AI_ROUTING;
    memset(s_neighbors, 0, sizeof(s_neighbors));
}

void mesh_router_set_ai_enabled(bool enabled) {
    s_ai_routing_enabled = enabled;
}

bool mesh_router_is_ai_enabled(void) {
    return s_ai_routing_enabled;
}

void mesh_router_update_neighbor(const ForestLinkTelemetryPacket *pkt, uint32_t current_millis) {
    if (!pkt || pkt->node_id == s_self_node_id) {
        return; // Ignore invalid or own loopback packets
    }

    // 1. Check if neighbor already exists in table
    int target_idx = -1;
    int oldest_idx = 0;
    uint32_t oldest_time = 0xFFFFFFFF;

    for (int i = 0; i < MAX_NEIGHBORS; i++) {
        if (s_neighbors[i].is_active && s_neighbors[i].node_id == pkt->node_id) {
            target_idx = i;
            break;
        }
        if (!s_neighbors[i].is_active && target_idx == -1) {
            target_idx = i;
        }
        if (s_neighbors[i].last_heard_millis < oldest_time) {
            oldest_time = s_neighbors[i].last_heard_millis;
            oldest_idx = i;
        }
    }

    // If table is completely full, evict oldest entry
    if (target_idx == -1) {
        target_idx = oldest_idx;
    }

    // 2. Evaluate link reliability score using C++ Decision Forest
    float score = route_scorer_predict_packet(pkt);

    // 3. Store record
    s_neighbors[target_idx].node_id = pkt->node_id;
    memcpy(&s_neighbors[target_idx].last_telemetry, pkt, sizeof(ForestLinkTelemetryPacket));
    s_neighbors[target_idx].last_heard_millis = current_millis;
    s_neighbors[target_idx].cached_reliability = score;
    s_neighbors[target_idx].is_active = true;
}

uint8_t mesh_router_get_active_neighbor_count(uint32_t current_millis) {
    uint8_t count = 0;
    for (int i = 0; i < MAX_NEIGHBORS; i++) {
        if (s_neighbors[i].is_active) {
            if ((current_millis - s_neighbors[i].last_heard_millis) <= NEIGHBOR_EXPIRATION_MS) {
                count++;
            } else {
                s_neighbors[i].is_active = false; // Expire stale neighbor
            }
        }
    }
    return count;
}

NextHopResult mesh_router_select_next_hop(
    uint16_t dest_id,
    bool is_emergency,
    uint32_t current_millis
) {
    NextHopResult res;
    res.decision_type = ROUTING_FALLBACK_DETERMINISTIC;
    res.next_hop_node_id = 0xFFFF; // Broadcast
    res.reliability_score = 0.0f;
    res.reason = "Default fallback";

    // 1. Check feature toggle: If AI routing is disabled, return deterministic fallback
    if (!s_ai_routing_enabled) {
        res.decision_type = ROUTING_FALLBACK_DETERMINISTIC;
        res.reason = "AI routing disabled by feature flag";
        return res;
    }

    // 2. Check if destination is a direct neighbor with an active link
    for (int i = 0; i < MAX_NEIGHBORS; i++) {
        if (s_neighbors[i].is_active && s_neighbors[i].node_id == dest_id) {
            if ((current_millis - s_neighbors[i].last_heard_millis) <= NEIGHBOR_EXPIRATION_MS) {
                res.decision_type = is_emergency ? ROUTING_EMERGENCY_PRIORITY : ROUTING_DECISION_AI_DIRECT;
                res.next_hop_node_id = dest_id;
                res.reliability_score = s_neighbors[i].cached_reliability;
                res.reason = "Direct neighbor link active";
                return res;
            }
        }
    }

    // 3. Evaluate candidate next-hop neighbors
    int best_idx = -1;
    float best_composite_score = -100.0f;

    for (int i = 0; i < MAX_NEIGHBORS; i++) {
        if (!s_neighbors[i].is_active) continue;

        // Skip expired neighbor records
        uint32_t elapsed = current_millis - s_neighbors[i].last_heard_millis;
        if (elapsed > NEIGHBOR_EXPIRATION_MS) {
            s_neighbors[i].is_active = false;
            continue;
        }

        float reliability = s_neighbors[i].cached_reliability;
        float hops = (float)fl_get_hop_count(s_neighbors[i].last_telemetry.hop_and_flags);
        float queue = (float)s_neighbors[i].last_telemetry.queue_len;

        float composite_score = 0.0f;

        if (is_emergency) {
            // EMERGENCY / SOS MODE:
            // Maximize raw link reliability, zero penalty for slight hop overhead
            composite_score = reliability;
        } else {
            // NORMAL OPERATIONAL TRAFFIC:
            // Multi-objective optimization:
            // + 65% weight on AI link reliability
            // - 20% weight on normalized hop count penalty
            // - 15% weight on node queue congestion
            float hop_penalty = hops / 10.0f;
            float queue_penalty = queue / (float)MAX_QUEUE_CAPACITY;
            if (queue_penalty > 1.0f) queue_penalty = 1.0f;

            composite_score = (0.65f * reliability) - (0.20f * hop_penalty) - (0.15f * queue_penalty);
        }

        if (composite_score > best_composite_score) {
            best_composite_score = composite_score;
            best_idx = i;
        }
    }

    // 4. Check if any valid neighbor was found
    if (best_idx == -1) {
        res.decision_type = ROUTING_FALLBACK_DETERMINISTIC;
        res.reason = "Neighbor table empty or all entries expired";
        return res;
    }

    // 5. CRITICAL FAIL-SAFE / GRACEFUL DEGRADATION:
    // If best candidate reliability is below safety threshold, the link is too weak/noisy.
    // Fall back to deterministic multi-path flooding to ensure the packet is not dropped!
    if (s_neighbors[best_idx].cached_reliability < ROUTING_CONFIDENCE_THRESHOLD) {
        res.decision_type = ROUTING_FALLBACK_DETERMINISTIC;
        res.next_hop_node_id = 0xFFFF; // Broadcast / Flood
        res.reliability_score = s_neighbors[best_idx].cached_reliability;
        res.reason = "Fail-safe triggered: Top candidate reliability below safety threshold";
        return res;
    }

    // 6. Return optimal AI-selected next-hop
    res.decision_type = is_emergency ? ROUTING_EMERGENCY_PRIORITY : ROUTING_DECISION_AI_DIRECT;
    res.next_hop_node_id = s_neighbors[best_idx].node_id;
    res.reliability_score = s_neighbors[best_idx].cached_reliability;
    res.reason = is_emergency ? "Emergency priority next-hop selected" : "Optimal AI next-hop selected";

    return res;
}
