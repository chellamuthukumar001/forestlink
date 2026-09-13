#!/usr/bin/env python3
"""
Transpiles trained scikit-learn RandomForestClassifier into pure Kotlin (AiRouteScorer.kt)
for zero-dependency, native on-device inference inside the Android application.
"""

import os
import sys
import joblib
import argparse

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '../..')))
from ai_layer.section2_route_reliability.train_route_scorer import FEATURE_NAMES

def tree_to_kotlin(tree, tree_idx: int) -> str:
    left = tree.children_left
    right = tree.children_right
    threshold = tree.threshold
    feature = tree.feature
    value = tree.value

    lines = []
    lines.append(f"    // Tree {tree_idx}")
    lines.append(f"    private fun evalTree{tree_idx}(f: FloatArray): Float {{")

    def recurse(node_id: int, depth: int):
        indent = "    " * (depth + 2)
        if left[node_id] == -1 and right[node_id] == -1:
            val = value[node_id][0]
            total = val.sum()
            prob = float(val[1] / total) if total > 0 else 0.0
            lines.append(f"{indent}return {prob:.6f}f")
        else:
            feat_idx = feature[node_id]
            thresh = threshold[node_id]
            lines.append(f"{indent}if (f[{feat_idx}] <= {thresh:.6f}f) {{")
            recurse(left[node_id], depth + 1)
            lines.append(f"{indent}}} else {{")
            recurse(right[node_id], depth + 1)
            lines.append(f"{indent}}}")

    recurse(0, 0)
    lines.append("    }\n")
    return "\n".join(lines)

def generate_kotlin_class(rf_model) -> str:
    n_estimators = len(rf_model.estimators_)
    trees_kt = [tree_to_kotlin(est.tree_, i) for i, est in enumerate(rf_model.estimators_)]
    tree_calls = [f"        sum += evalTree{i}(f)" for i in range(n_estimators)]

    trees_body = "\n".join(trees_kt)
    eval_calls = "\n".join(tree_calls)

    return f"""package com.forest.offgrid.util

import com.forest.offgrid.data.model.AiRouteInfo
import com.forest.offgrid.data.model.RoutingMode
import kotlin.math.min

/**
 * Native Android in-app AI Route Reliability Scorer.
 * Zero-dependency pure Kotlin evaluation of trained ForestLink Decision Forest.
 */
object AiRouteScorer {{

    const val CONFIDENCE_THRESHOLD = 0.35f
    private const val LORA_SF7_SNR_LIMIT = -7.5f

    fun extractFeatures(
        rssi: Int,
        snr: Float,
        packetLossRate: Int,
        batteryPct: Int,
        batteryVoltageMv: Int,
        queueLen: Int,
        distanceM: Int,
        hopCount: Int,
        lastSeenSec: Int
    ): FloatArray {{
        val linkMargin = snr - LORA_SF7_SNR_LIMIT
        val congestionRatio = min(2.0f, queueLen / 32.0f)
        val freshness = 1.0f / (1.0f + (lastSeenSec / 60.0f))

        return floatArrayOf(
            rssi.toFloat(),
            snr,
            linkMargin,
            packetLossRate.toFloat(),
            batteryPct.toFloat(),
            batteryVoltageMv.toFloat(),
            queueLen.toFloat(),
            congestionRatio,
            distanceM.toFloat(),
            hopCount.toFloat(),
            lastSeenSec.toFloat(),
            freshness
        )
    }}

    fun predictReliability(features: FloatArray): Float {{
        var sum = 0.0f
{eval_calls}
        return sum / {float(n_estimators):.1f}f
    }}

    fun evaluateNextHop(
        destNodeId: String,
        candidateNextHopId: String,
        rssi: Int,
        snr: Float,
        packetLossRate: Int,
        batteryPct: Int,
        batteryVoltageMv: Int,
        queueLen: Int,
        distanceM: Int,
        hopCount: Int,
        lastSeenSec: Int,
        isEmergency: Boolean = false
    ): AiRouteInfo {{
        val features = extractFeatures(
            rssi, snr, packetLossRate, batteryPct, batteryVoltageMv,
            queueLen, distanceM, hopCount, lastSeenSec
        )
        val score = predictReliability(features)

        val mode = when {{
            isEmergency -> RoutingMode.EMERGENCY_PRIORITY
            score < CONFIDENCE_THRESHOLD -> RoutingMode.DETERMINISTIC_FALLBACK
            else -> RoutingMode.AI_DIRECT
        }}

        val reason = when (mode) {{
            RoutingMode.EMERGENCY_PRIORITY -> "Emergency SOS override (max reliability)"
            RoutingMode.DETERMINISTIC_FALLBACK -> "Confidence below 35% -> Fallback to flood"
            RoutingMode.AI_DIRECT -> "Optimal AI next-hop selected"
        }}

        return AiRouteInfo(
            destNodeId = destNodeId,
            nextHopNodeId = if (mode == RoutingMode.DETERMINISTIC_FALLBACK) "FLOOD" else candidateNextHopId,
            reliabilityScore = score,
            mode = mode,
            reason = reason,
            lastUpdatedMillis = System.currentTimeMillis()
        )
    }}

{trees_body}
}}
"""

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", type=str, default="ai_layer/section2_route_reliability/route_scorer_rf.joblib")
    parser.add_argument("--out", type=str, default="app/src/main/java/com/forest/offgrid/util/AiRouteScorer.kt")
    args = parser.parse_args()

    rf_model = joblib.load(args.model)
    kt_code = generate_kotlin_class(rf_model)

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, 'w', encoding='utf-8') as f:
        f.write(kt_code)

    print(f"[ExportKotlin] Exported native Android AI route scorer to: {args.out}")

if __name__ == '__main__':
    main()
