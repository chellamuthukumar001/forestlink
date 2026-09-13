#ifndef FORESTLINK_ROUTE_SCORER_MODEL_H
#define FORESTLINK_ROUTE_SCORER_MODEL_H

#include <stdint.h>
#include <stdbool.h>
#include "../../common/telemetry_packet.h"

#ifdef __cplusplus
extern "C" {
#endif

#define ROUTE_SCORER_NUM_FEATURES 12
#define ROUTE_SCORER_CONFIDENCE_THRESHOLD 0.35f

/**
 * Engineers 12 numerical features from an 18-byte packed telemetry packet.
 * Output array must have size at least ROUTE_SCORER_NUM_FEATURES (12 floats).
 */
void route_scorer_extract_features(const ForestLinkTelemetryPacket *pkt, float *out_features);

/**
 * Predicts next-hop route reliability probability in range [0.0, 1.0]
 * using transpiled Decision Forest. Zero heap allocation, microsecond execution.
 */
float route_scorer_predict_features(const float *features);

/**
 * Convenience wrapper: extracts features from packet and predicts reliability.
 */
float route_scorer_predict_packet(const ForestLinkTelemetryPacket *pkt);

#ifdef __cplusplus
}
#endif

#endif // FORESTLINK_ROUTE_SCORER_MODEL_H
