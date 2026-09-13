#!/usr/bin/env python3
"""
ForestLink Node Health Prediction Model Training Pipeline
Trains an edge-compatible classifier to predict node health status:
0: HEALTHY, 1: DEGRADING, 2: CRITICAL, plus remaining operating hours.
"""

import os
import csv
import json
import joblib
import argparse
import numpy as np

from sklearn.ensemble import RandomForestClassifier, RandomForestRegressor
from sklearn.model_selection import train_test_split
from sklearn.metrics import classification_report, accuracy_score, mean_squared_error

HEALTH_FEATURES = [
    'battery_voltage_mv',
    'battery_pct',
    'dv_dt_mv_per_hr',
    'temperature_c',
    'rssi_variance',
    'missed_heartbeats'
]

STATUS_NAMES = ['HEALTHY', 'DEGRADING', 'CRITICAL']

def load_health_data(csv_path: str):
    X = []
    y_status = []
    y_hours = []

    with open(csv_path, 'r', encoding='utf-8') as f:
        reader = csv.DictReader(f)
        for row in reader:
            feats = [
                float(row['battery_voltage_mv']),
                float(row['battery_pct']),
                float(row['dv_dt_mv_per_hr']),
                float(row['temperature_c']),
                float(row['rssi_variance']),
                float(row['missed_heartbeats'])
            ]
            X.append(feats)
            y_status.append(int(row['health_status']))
            y_hours.append(float(row['hours_to_failure']))

    return np.array(X, dtype=np.float32), np.array(y_status, dtype=np.int32), np.array(y_hours, dtype=np.float32)

def train_health_models(csv_path: str, out_dir: str):
    os.makedirs(out_dir, exist_ok=True)
    X, y_status, y_hours = load_health_data(csv_path)

    X_train, X_test, y_s_train, y_s_test, y_h_train, y_h_test = train_test_split(
        X, y_status, y_hours, test_size=0.20, random_state=42, stratify=y_status
    )

    # 1. Health Status Classifier (10 shallow trees, max depth 4)
    clf = RandomForestClassifier(n_estimators=10, max_depth=4, random_state=42)
    clf.fit(X_train, y_s_train)

    y_s_pred = clf.predict(X_test)
    acc = accuracy_score(y_s_test, y_s_pred)
    print("=" * 60)
    print("        NODE HEALTH STATUS CLASSIFIER EVALUATION")
    print("=" * 60)
    print(f"Overall Accuracy: {acc * 100:.2f}%\n")
    print(classification_report(y_s_test, y_s_pred, target_names=STATUS_NAMES, digits=4))

    # Feature Importance
    print("Feature Importances:")
    for feat, imp in zip(HEALTH_FEATURES, clf.feature_importances_):
        print(f" - {feat:<20}: {imp * 100:.2f}%")

    # Save models
    clf_path = os.path.join(out_dir, "health_classifier.joblib")
    joblib.dump(clf, clf_path)
    print(f"\n[HealthModel] Saved classifier to {clf_path}")

    # Metadata
    meta = {
        'features': HEALTH_FEATURES,
        'classes': STATUS_NAMES,
        'accuracy': float(acc),
        'importances': {feat: float(imp) for feat, imp in zip(HEALTH_FEATURES, clf.feature_importances_)}
    }
    with open(os.path.join(out_dir, "health_model_metadata.json"), 'w') as f:
        json.dump(meta, f, indent=2)

    return clf

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", type=str, default="ai_layer/section4_node_health/synthetic_health.csv")
    parser.add_argument("--out_dir", type=str, default="ai_layer/section4_node_health")
    args = parser.parse_args()

    train_health_models(args.data, args.out_dir)

if __name__ == '__main__':
    main()
