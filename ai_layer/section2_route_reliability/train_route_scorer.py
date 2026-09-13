#!/usr/bin/env python3
"""
ForestLink Route-Reliability Scoring Model Training Pipeline
Trains an edge-optimized ensemble model using scikit-learn.
Computes comprehensive evaluation metrics (Precision, Recall, ROC-AUC, Brier score)
and feature importances, then saves the model artifacts for embedded C++ and TFLite export.
"""

import os
import sys
import csv
import json
import joblib
import argparse
import numpy as np

from sklearn.ensemble import RandomForestClassifier, GradientBoostingClassifier
from sklearn.model_selection import train_test_split
from sklearn.metrics import (
    accuracy_score,
    precision_score,
    recall_score,
    f1_score,
    roc_auc_score,
    brier_score_loss,
    confusion_matrix,
    classification_report
)

FEATURE_NAMES = [
    'rssi',
    'snr',
    'link_margin',
    'packet_loss_rate',
    'battery_pct',
    'battery_voltage_mv',
    'queue_len',
    'congestion_ratio',
    'distance_m',
    'hop_count',
    'last_seen_sec',
    'freshness_factor'
]

LORA_SF7_SNR_LIMIT = -7.5  # SX1278 demodulation threshold in dB


def extract_features(row: dict) -> list:
    """Extracts and engineers numerical features from a raw telemetry dictionary."""
    rssi = float(row['rssi'])
    snr = float(row['snr'])
    link_margin = snr - LORA_SF7_SNR_LIMIT
    packet_loss_rate = float(row['packet_loss_rate'])
    battery_pct = float(row['battery_pct'])
    battery_voltage_mv = float(row['battery_voltage_mv'])
    queue_len = float(row['queue_len'])
    congestion_ratio = min(2.0, queue_len / 32.0)
    distance_m = float(row['distance_m'])
    hop_count = float(row['hop_count'])
    last_seen_sec = float(row['last_seen_sec'])
    freshness_factor = 1.0 / (1.0 + last_seen_sec / 60.0)

    return [
        rssi,
        snr,
        link_margin,
        packet_loss_rate,
        battery_pct,
        battery_voltage_mv,
        queue_len,
        congestion_ratio,
        distance_m,
        hop_count,
        last_seen_sec,
        freshness_factor
    ]


def load_dataset(csv_path: str):
    """Loads CSV dataset and produces feature matrix X and label vector y."""
    X = []
    y = []
    probabilities = []

    with open(csv_path, 'r', encoding='utf-8') as f:
        reader = csv.DictReader(f)
        for row in reader:
            if 'route_success' not in row or row['route_success'] == '':
                continue
            feats = extract_features(row)
            X.append(feats)
            y.append(int(row['route_success']))
            if 'delivery_probability' in row:
                probabilities.append(float(row['delivery_probability']))

    return np.array(X, dtype=np.float32), np.array(y, dtype=np.int32), np.array(probabilities, dtype=np.float32) if probabilities else None


def train_and_evaluate(
    csv_path: str,
    output_dir: str,
    n_estimators: int = 12,
    max_depth: int = 4,
    test_size: float = 0.20,
    seed: int = 42
):
    os.makedirs(output_dir, exist_ok=True)
    print(f"[RouteScorer] Loading dataset from: {csv_path}")
    X, y, true_probs = load_dataset(csv_path)
    print(f"[RouteScorer] Total samples: {len(X)}, Positive rate: {y.mean() * 100:.2f}%")

    # Stratified Train/Test Split
    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=test_size, random_state=seed, stratify=y
    )

    print(f"[RouteScorer] Training split: {len(X_train)} samples, Test split: {len(X_test)} samples")
    print(f"[RouteScorer] Model: Edge-optimized Random Forest ({n_estimators} trees, max_depth={max_depth})")

    # Train Random Forest
    rf_model = RandomForestClassifier(
        n_estimators=n_estimators,
        max_depth=max_depth,
        min_samples_split=8,
        min_samples_leaf=4,
        max_features='sqrt',
        random_state=seed,
        n_jobs=-1
    )
    rf_model.fit(X_train, y_train)

    # Inferences on Test Set
    y_pred_proba = rf_model.predict_proba(X_test)[:, 1]
    y_pred_class = (y_pred_proba >= 0.50).astype(int)

    # Compute Evaluation Metrics
    acc = accuracy_score(y_test, y_pred_class)
    prec = precision_score(y_test, y_pred_class, zero_division=0)
    rec = recall_score(y_test, y_pred_class, zero_division=0)
    f1 = f1_score(y_test, y_pred_class, zero_division=0)
    roc_auc = roc_auc_score(y_test, y_pred_proba)
    brier = brier_score_loss(y_test, y_pred_proba)
    cm = confusion_matrix(y_test, y_pred_class)

    print("\n" + "=" * 60)
    print("      FORESTLINK ROUTE-RELIABILITY MODEL EVALUATION")
    print("=" * 60)
    print(f"Accuracy:          {acc * 100:.2f}%")
    print(f"Precision:         {prec * 100:.2f}% (How reliable are routes flagged good)")
    print(f"Recall:            {rec * 100:.2f}% (How many successful routes detected)")
    print(f"F1-Score:          {f1:.4f}")
    print(f"ROC-AUC:           {roc_auc:.4f}")
    print(f"Brier Score Loss:  {brier:.4f} (Lower is better; measures probability calibration)")
    print("\nConfusion Matrix [TN, FP / FN, TP]:")
    print(f"  [ {cm[0][0]:4d}  {cm[0][1]:4d} ]")
    print(f"  [ {cm[1][0]:4d}  {cm[1][1]:4d} ]")
    print("\nClassification Report:")
    print(classification_report(y_test, y_pred_class, digits=4))

    # Feature Importance
    importances = rf_model.feature_importances_
    sorted_idx = np.argsort(importances)[::-1]

    print("=" * 60)
    print("                FEATURE IMPORTANCE RANKING")
    print("=" * 60)
    importance_dict = {}
    for rank, idx in enumerate(sorted_idx, 1):
        feat = FEATURE_NAMES[idx]
        imp = importances[idx]
        importance_dict[feat] = float(imp)
        bar = "#" * int(imp * 40)
        print(f"{rank:2d}. {feat:<20} : {imp * 100:6.2f}% | {bar}")

    # Save Model Artifacts
    model_path = os.path.join(output_dir, "route_scorer_rf.joblib")
    joblib.dump(rf_model, model_path)
    print(f"\n[RouteScorer] Saved trained model to: {model_path}")

    metrics_meta = {
        'model_type': 'RandomForestClassifier',
        'n_estimators': n_estimators,
        'max_depth': max_depth,
        'accuracy': float(acc),
        'precision': float(prec),
        'recall': float(rec),
        'f1_score': float(f1),
        'roc_auc': float(roc_auc),
        'brier_score': float(brier),
        'confusion_matrix': cm.tolist(),
        'feature_names': FEATURE_NAMES,
        'feature_importances': importance_dict
    }

    meta_path = os.path.join(output_dir, "model_metadata.json")
    with open(meta_path, 'w', encoding='utf-8') as f:
        json.dump(metrics_meta, f, indent=2)
    print(f"[RouteScorer] Saved metadata to: {meta_path}")

    return rf_model, metrics_meta


def main():
    parser = argparse.ArgumentParser(description="Train ForestLink Route-Reliability Scoring Model")
    parser.add_argument("--data", type=str, default="ai_layer/section1_data_collection/synthetic_telemetry.csv")
    parser.add_argument("--out_dir", type=str, default="ai_layer/section2_route_reliability")
    parser.add_argument("--trees", type=int, default=12, help="Number of trees in ensemble")
    parser.add_argument("--max_depth", type=int, default=4, help="Maximum tree depth for edge efficiency")
    args = parser.parse_args()

    train_and_evaluate(
        csv_path=args.data,
        output_dir=args.out_dir,
        n_estimators=args.trees,
        max_depth=args.max_depth
    )


if __name__ == '__main__':
    main()
