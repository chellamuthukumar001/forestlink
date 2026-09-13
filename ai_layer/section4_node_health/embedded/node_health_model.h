#ifndef FORESTLINK_NODE_HEALTH_MODEL_H
#define FORESTLINK_NODE_HEALTH_MODEL_H

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    NODE_HEALTH_HEALTHY = 0,   // Normal operation, low failure risk
    NODE_HEALTH_DEGRADING = 1, // Battery/link dropping, early maintenance alert
    NODE_HEALTH_CRITICAL = 2   // Imminent shutdown (<2h), brownout or thermal risk
} NodeHealthStatus;

typedef struct {
    NodeHealthStatus status;
    float confidence;
    const char *status_str;
    float hours_remaining_est;
} NodeHealthResult;

/**
 * Evaluates node health from sensor features:
 * f[0] = battery_voltage_mv
 * f[1] = battery_pct
 * f[2] = dv_dt_mv_per_hr (discharge slope)
 * f[3] = temperature_c (BME280)
 * f[4] = rssi_variance (antenna stability)
 * f[5] = missed_heartbeats
 */
NodeHealthResult node_health_predict(
    uint16_t battery_mv,
    uint8_t battery_pct,
    float dv_dt_mv_per_hr,
    float temperature_c,
    float rssi_variance,
    uint8_t missed_heartbeats
);

#ifdef __cplusplus
}
#endif

#endif // FORESTLINK_NODE_HEALTH_MODEL_H
