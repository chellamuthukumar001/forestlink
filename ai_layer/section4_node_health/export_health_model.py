#!/usr/bin/env python3
"""
Transpiles trained Node Health Classifier into embedded C++ for ESP32-S3.
"""

import os
import sys
import joblib
import argparse
import numpy as np

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '../..')))
from ai_layer.section4_node_health.train_health_predictor import HEALTH_FEATURES

def tree_to_cpp(tree, idx: int) -> str:
    left = tree.children_left
    right = tree.children_right
    threshold = tree.threshold
    feature = tree.feature
    value = tree.value

    lines = []
    lines.append(f"static inline void eval_health_tree_{idx}(const float *f, float *out_probs) {{")

    def recurse(node_id: int, depth: int):
        indent = "    " * (depth + 1)
        if left[node_id] == -1 and right[node_id] == -1:
            val = value[node_id][0]
            total = val.sum()
            p0 = float(val[0] / total) if total > 0 else 0.0
            p1 = float(val[1] / total) if total > 0 else 0.0
            p2 = float(val[2] / total) if total > 0 else 0.0
            lines.append(f"{indent}out_probs[0] += {p0:.6f}f;")
            lines.append(f"{indent}out_probs[1] += {p1:.6f}f;")
            lines.append(f"{indent}out_probs[2] += {p2:.6f}f;")
        else:
            feat_idx = feature[node_id]
            feat_name = HEALTH_FEATURES[feat_idx]
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
    return """#ifndef FORESTLINK_NODE_HEALTH_MODEL_H
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
"""

def generate_source(clf) -> str:
    n_trees = len(clf.estimators_)
    trees_cpp = []
    tree_calls = []
    for i, est in enumerate(clf.estimators_):
        trees_cpp.append(tree_to_cpp(est.tree_, i))
        tree_calls.append(f"    eval_health_tree_{i}(f, probs);")

    trees_body = "\n".join(trees_cpp)
    eval_calls = "\n".join(tree_calls)

    return f"""#include "node_health_model.h"
#include <string.h>

// ================= Transpiled Health Trees =================
{trees_body}

NodeHealthResult node_health_predict(
    uint16_t battery_mv,
    uint8_t battery_pct,
    float dv_dt_mv_per_hr,
    float temperature_c,
    float rssi_variance,
    uint8_t missed_heartbeats
) {{
    float f[6];
    f[0] = (float)battery_mv;
    f[1] = (float)battery_pct;
    f[2] = dv_dt_mv_per_hr;
    f[3] = temperature_c;
    f[4] = rssi_variance;
    f[5] = (float)missed_heartbeats;

    float probs[3] = {{0.0f, 0.0f, 0.0f}};
{eval_calls}

    probs[0] /= {float(n_trees):.1f}f;
    probs[1] /= {float(n_trees):.1f}f;
    probs[2] /= {float(n_trees):.1f}f;

    int best_class = 0;
    float max_p = probs[0];
    for (int i = 1; i < 3; i++) {{
        if (probs[i] > max_p) {{
            max_p = probs[i];
            best_class = i;
        }}
    }}

    NodeHealthResult res;
    res.status = (NodeHealthStatus)best_class;
    res.confidence = max_p;

    if (best_class == 0) {{
        res.status_str = "HEALTHY";
        res.hours_remaining_est = (dv_dt_mv_per_hr < -0.1f) ? (float)(battery_mv - 3300) / (-dv_dt_mv_per_hr) : 48.0f;
    }} else if (best_class == 1) {{
        res.status_str = "DEGRADING";
        res.hours_remaining_est = (dv_dt_mv_per_hr < -0.1f) ? (float)(battery_mv - 3250) / (-dv_dt_mv_per_hr) : 8.0f;
    }} else {{
        res.status_str = "CRITICAL";
        res.hours_remaining_est = (dv_dt_mv_per_hr < -0.1f) ? (float)(battery_mv - 3100) / (-dv_dt_mv_per_hr) : 1.0f;
    }}

    if (res.hours_remaining_est < 0.1f) res.hours_remaining_est = 0.1f;
    if (res.hours_remaining_est > 72.0f) res.hours_remaining_est = 72.0f;

    return res;
}}
"""

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", type=str, default="ai_layer/section4_node_health/health_classifier.joblib")
    parser.add_argument("--out_dir", type=str, default="ai_layer/section4_node_health/embedded")
    args = parser.parse_args()

    clf = joblib.load(args.model)
    os.makedirs(args.out_dir, exist_ok=True)

    header_path = os.path.join(args.out_dir, "node_health_model.h")
    source_path = os.path.join(args.out_dir, "node_health_model.cpp")

    with open(header_path, 'w', encoding='utf-8') as f:
        f.write(generate_header())

    with open(source_path, 'w', encoding='utf-8') as f:
        f.write(generate_source(clf))

    print(f"[ExportHealth] Exported health prediction C++ engine to {source_path}")

if __name__ == '__main__':
    main()
