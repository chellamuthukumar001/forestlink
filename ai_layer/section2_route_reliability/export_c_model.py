#!/usr/bin/env python3
"""
ForestLink Decision-Tree-to-C++ Code Exporter
Transpiles trained scikit-learn RandomForestClassifier into zero-dependency,
zero-heap-allocation C++ source files for instant execution on ESP32-S3.
"""

import os
import sys
import joblib
import argparse
import numpy as np

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '../..')))
from ai_layer.section2_route_reliability.train_route_scorer import FEATURE_NAMES


def tree_to_cpp(tree, tree_idx: int) -> str:
    """Recursively formats a single DecisionTreeClassifier into a C++ static function."""
    left = tree.children_left
    right = tree.children_right
    threshold = tree.threshold
    feature = tree.feature
    value = tree.value

    lines = []
    lines.append(f"// Tree {tree_idx} (Max Depth {tree.max_depth})")
    lines.append(f"static inline float eval_tree_{tree_idx}(const float *f) {{")

    def recurse(node_id: int, depth: int):
        indent = "    " * (depth + 1)
        if left[node_id] == -1 and right[node_id] == -1:  # Leaf node
            val = value[node_id][0]
            total = val.sum()
            prob_positive = float(val[1] / total) if total > 0 else 0.0
            lines.append(f"{indent}return {prob_positive:.6f}f;")
        else:
            feat_idx = feature[node_id]
            feat_name = FEATURE_NAMES[feat_idx]
            thresh = threshold[node_id]
            lines.append(f"{indent}// f[{feat_idx}] = {feat_name}")
            lines.append(f"{indent}if (f[{feat_idx}] <= {thresh:.6f}f) {{")
            recurse(left[node_id], depth + 1)
            lines.append(f"{indent}}} else {{")
            recurse(right[node_id], depth + 1)
            lines.append(f"{indent}}}")

    recurse(0, 0)
    lines.append("}\n")
    return "\n".join(lines)


def generate_header() -> str:
    return """#ifndef FORESTLINK_ROUTE_SCORER_MODEL_H
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
"""


def generate_source(rf_model) -> str:
    n_estimators = len(rf_model.estimators_)

    trees_cpp = []
    tree_calls = []
    for i, est in enumerate(rf_model.estimators_):
        trees_cpp.append(tree_to_cpp(est.tree_, i))
        tree_calls.append(f"    sum += eval_tree_{i}(features);")

    trees_body = "\n".join(trees_cpp)
    eval_body = "\n".join(tree_calls)

    return f"""#include "route_scorer_model.h"

#define LORA_SF7_SNR_LIMIT -7.5f

void route_scorer_extract_features(const ForestLinkTelemetryPacket *pkt, float *f) {{
    float rssi = (float)pkt->rssi;
    float snr = fl_unpack_snr(pkt->snr_x4);
    float link_margin = snr - LORA_SF7_SNR_LIMIT;
    float packet_loss = (float)pkt->packet_loss_rate;
    float battery_pct = (float)pkt->battery_pct;
    float battery_mv = (float)fl_unpack_battery_mv(pkt->battery_mv_scaled);
    float queue_len = (float)pkt->queue_len;
    float congestion_ratio = queue_len / 32.0f;
    if (congestion_ratio > 2.0f) congestion_ratio = 2.0f;
    float distance_m = (float)pkt->distance_m;
    float hop_count = (float)fl_get_hop_count(pkt->hop_and_flags);
    float last_seen = (float)pkt->last_seen_sec;
    float freshness = 1.0f / (1.0f + (last_seen / 60.0f));

    // Array order strictly matches FEATURE_NAMES:
    f[0]  = rssi;
    f[1]  = snr;
    f[2]  = link_margin;
    f[3]  = packet_loss;
    f[4]  = battery_pct;
    f[5]  = battery_mv;
    f[6]  = queue_len;
    f[7]  = congestion_ratio;
    f[8]  = distance_m;
    f[9]  = hop_count;
    f[10] = last_seen;
    f[11] = freshness;
}}

// ================= Transpiled Decision Trees =================
{trees_body}

float route_scorer_predict_features(const float *features) {{
    float sum = 0.0f;
{eval_body}
    return sum / {float(n_estimators):.1f}f;
}}

float route_scorer_predict_packet(const ForestLinkTelemetryPacket *pkt) {{
    float features[ROUTE_SCORER_NUM_FEATURES];
    route_scorer_extract_features(pkt, features);
    return route_scorer_predict_features(features);
}}
"""


def main():
    parser = argparse.ArgumentParser(description="Export trained model to C++ code")
    parser.add_argument("--model", type=str, default="ai_layer/section2_route_reliability/route_scorer_rf.joblib")
    parser.add_argument("--out_dir", type=str, default="ai_layer/section2_route_reliability/embedded")
    args = parser.parse_args()

    os.makedirs(args.out_dir, exist_ok=True)
    print(f"[ExportC] Loading model from: {args.model}")
    rf_model = joblib.load(args.model)

    header_path = os.path.join(args.out_dir, "route_scorer_model.h")
    source_path = os.path.join(args.out_dir, "route_scorer_model.cpp")

    with open(header_path, 'w', encoding='utf-8') as f:
        f.write(generate_header())

    with open(source_path, 'w', encoding='utf-8') as f:
        f.write(generate_source(rf_model))

    print(f"[ExportC] Successfully generated {len(rf_model.estimators_)} trees to:")
    print(f"  - Header: {header_path}")
    print(f"  - Source: {source_path}")
    source_size_kb = os.path.getsize(source_path) / 1024.0
    print(f"  - Source footprint: ~{source_size_kb:.1f} KB flash, 0 B dynamic RAM")


if __name__ == '__main__':
    main()
