#!/usr/bin/env python3
"""
Synthetic Node Health Time-Series Dataset Generator
Models battery discharge slope (dV/dt), BME280 temperature drift,
antenna/canopy RSSI variance, and missed heartbeat telemetry.
"""

import os
import csv
import random
import argparse
import numpy as np

def generate_health_samples(num_samples: int = 4000, seed: int = 42):
    np.random.seed(seed)
    random.seed(seed)
    rows = []

    for i in range(num_samples):
        node_id = random.randint(1, 24)

        # Health profile distribution:
        # 60% Healthy, 25% Degrading, 15% Critical
        profile = random.choices(['healthy', 'degrading', 'critical'], weights=[0.60, 0.25, 0.15])[0]

        if profile == 'healthy':
            battery_pct = random.uniform(50.0, 100.0)
            battery_mv = int(3750 + (battery_pct - 50.0) * (4200 - 3750) / 50.0 + np.random.normal(0, 10))
            dv_dt = random.uniform(-15.0, -3.0)  # Slow nominal discharge: -3 to -15 mV/hr
            temp_c = random.uniform(18.0, 36.0)  # Comfortable operating temperature
            rssi_variance = random.uniform(0.5, 6.0) # Stable RF link
            missed_heartbeats = random.choices([0, 1, 2], weights=[0.85, 0.12, 0.03])[0]
            health_status = 0 # HEALTHY
            hours_to_failure = random.uniform(18.0, 72.0)

        elif profile == 'degrading':
            battery_pct = random.uniform(18.0, 48.0)
            battery_mv = int(3450 + (battery_pct - 18.0) * (3750 - 3450) / 30.0 + np.random.normal(0, 15))
            dv_dt = random.uniform(-45.0, -18.0) # Elevated discharge rate: -18 to -45 mV/hr
            temp_c = random.uniform(34.0, 46.0)  # Elevated warmth under heavy TX load
            rssi_variance = random.uniform(8.0, 22.0) # Unstable antenna/foliage link
            missed_heartbeats = random.choices([1, 2, 3, 4], weights=[0.40, 0.35, 0.15, 0.10])[0]
            health_status = 1 # DEGRADING
            hours_to_failure = random.uniform(3.0, 16.0)

        else: # critical
            battery_pct = random.uniform(1.0, 17.0)
            battery_mv = int(3050 + (battery_pct / 17.0) * (3450 - 3050) + np.random.normal(0, 20))
            dv_dt = random.uniform(-140.0, -50.0) # Rapid knee cliff discharge
            # Either extreme cold (freezing LiPo lockout) or thermal danger (>48C)
            if random.random() < 0.3:
                temp_c = random.uniform(-8.0, 4.0) # Sub-zero wilderness freeze
            else:
                temp_c = random.uniform(47.0, 62.0) # Thermal stress / runaway danger
            rssi_variance = random.uniform(20.0, 45.0) # Severely fluctuating / broken antenna
            missed_heartbeats = random.randint(4, 15) # Multiple consecutive lost beacons
            health_status = 2 # CRITICAL
            hours_to_failure = random.uniform(0.1, 2.5)

        rows.append({
            'node_id': f"NODE_{node_id:03d}",
            'battery_voltage_mv': battery_mv,
            'battery_pct': round(battery_pct, 1),
            'dv_dt_mv_per_hr': round(dv_dt, 2),
            'temperature_c': round(temp_c, 1),
            'rssi_variance': round(rssi_variance, 2),
            'missed_heartbeats': missed_heartbeats,
            'hours_to_failure': round(hours_to_failure, 2),
            'health_status': health_status # 0=Healthy, 1=Degrading, 2=Critical
        })

    return rows

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--samples", type=int, default=4000)
    parser.add_argument("--out", type=str, default="ai_layer/section4_node_health/synthetic_health.csv")
    args = parser.parse_args()

    rows = generate_health_samples(args.samples)
    with open(args.out, 'w', newline='', encoding='utf-8') as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)
    print(f"[HealthData] Generated {len(rows)} samples saved to {args.out}")

if __name__ == '__main__':
    main()
