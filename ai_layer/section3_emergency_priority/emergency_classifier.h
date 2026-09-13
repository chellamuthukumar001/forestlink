#ifndef FORESTLINK_EMERGENCY_CLASSIFIER_H
#define FORESTLINK_EMERGENCY_CLASSIFIER_H

#include <stdint.h>
#include <stdbool.h>
#include "../common/telemetry_packet.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    PRIORITY_NORMAL = 0,         // Routine chat, periodic telemetry (Standard backoff: 500-1800ms)
    PRIORITY_ELEVATED = 1,       // Low battery, degraded link warning (Reduced backoff: 100-300ms)
    PRIORITY_EMERGENCY_SOS = 2   // Critical distress SOS (Preemption, Zero backoff: 0-20ms)
} PriorityLevel;

typedef struct {
    PriorityLevel level;
    const char *reason;
    bool is_preemptive;
    uint16_t backoff_delay_ms;
} PriorityClassification;

/**
 * Initializes the emergency classifier state and history buffers.
 */
void emergency_classifier_init(void);

/**
 * Classifies a packet based on message payload string, sender node ID,
 * and optional telemetry record.
 */
PriorityClassification emergency_classify(
    const char *payload_str,
    uint16_t sender_node_id,
    const ForestLinkTelemetryPacket *optional_tlm,
    uint32_t current_millis
);

/**
 * Checks whether a sender node exhibits telemetry distress patterns
 * (e.g. stationary location + critical battery + severe packet loss).
 */
bool emergency_is_telemetry_distress(const ForestLinkTelemetryPacket *tlm);

#ifdef __cplusplus
}
#endif

#endif // FORESTLINK_EMERGENCY_CLASSIFIER_H
