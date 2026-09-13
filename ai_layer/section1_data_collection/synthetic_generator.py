#!/usr/bin/env python3
"""
ForestLink Synthetic Mesh Data Generator
Simulates realistic wilderness RF propagation, foliage canopy loss,
SX1278 demodulator waterfall curves, battery discharge dynamics,
and queue congestion collisions to generate high-fidelity training data.
Uses standard Python library + numpy.
"""

import os
import sys
import csv
import math
import random
import argparse
import numpy as np

# Ensure ai_layer is importable
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '../..')))
from ai_layer.common.telemetry_packet import TelemetryRecord


def simulate_lipo_voltage(battery_pct: float, is_transmitting: bool = False) -> int:
    """
    Simulates non-linear LiPo discharge curve from 0% to 100%.
    Includes transient voltage sag under 120mA SX1278 transmit pulses.
    """
    pct = max(0.0, min(100.0, battery_pct)) / 100.0

    # Non-linear 3-phase LiPo discharge model:
    # 1. High-state plateau: 4.20V down to 3.85V
    # 2. Linear discharge plateau: 3.85V down to 3.65V
    # 3. Low-state knee cliff: 3.65V down to 3.00V
    if pct > 0.8:
        v_nom = 3.85 + (pct - 0.8) * (4.20 - 3.85) / 0.2
    elif pct > 0.15:
        v_nom = 3.65 + (pct - 0.15) * (3.85 - 3.65) / 0.65
    else:
        v_nom = 3.00 + (pct / 0.15) * (3.65 - 3.00)

    # Add small thermal noise / ADC jitter (+- 15mV)
    jitter = float(np.random.normal(0, 0.015))
    v_nom += jitter

    # Battery internal resistance sag under load (R_int rises steeply at low SoC)
    if is_transmitting:
        r_internal = 0.15 + (1.0 - pct) * 0.45  # 150mOhm to 600mOhm
        i_tx = 0.120  # 120mA TX current
        v_nom -= (r_internal * i_tx)

    mv = int(round(v_nom * 1000.0))
    return max(2600, min(4250, mv))


def simulate_rf_link(
    distance_m: float,
    canopy_density: str = 'medium',
    tx_power_dbm: float = 17.0,
    carrier_freq_mhz: float = 433.0
):
    """
    Simulates RF path loss using Log-Distance model with ITU-R P.833
    foliage canopy attenuation and log-normal shadow fading.
    """
    # Canopy-specific parameters
    # Open / Light canopy: gamma ~ 2.6, shadow std ~ 4 dB
    # Medium forest: gamma ~ 3.2, shadow std ~ 5.5 dB, excess foliage ~ 0.08 dB/m
    # Dense wet canopy: gamma ~ 3.8, shadow std ~ 7.0 dB, excess foliage ~ 0.18 dB/m
    if canopy_density == 'light':
        gamma = 2.6
        shadow_std = 4.0
        foliage_att_per_m = 0.03
    elif canopy_density == 'dense':
        gamma = 3.8
        shadow_std = 7.0
        foliage_att_per_m = 0.18
    else:  # medium
        gamma = 3.2
        shadow_std = 5.5
        foliage_att_per_m = 0.08

    # Free-space path loss at 1m reference distance for 433 MHz
    pl_d0 = 20.0 * math.log10(carrier_freq_mhz) - 27.55  # ~25.18 dB

    d = max(1.0, distance_m)
    path_loss_basic = pl_d0 + (10.0 * gamma * math.log10(d))

    # Excess vegetation loss (saturates around 200m depth into thick forest)
    canopy_depth = min(d, 200.0)
    foliage_loss = foliage_att_per_m * canopy_depth

    # Log-normal shadowing (multipath, tree trunks, terrain undulation)
    shadowing = float(np.random.normal(0, shadow_std))

    total_path_loss = path_loss_basic + foliage_loss + shadowing

    antenna_gain_tx = 2.15  # dBi (standard 1/4 wave whip)
    antenna_gain_rx = 2.15

    rx_power = tx_power_dbm + antenna_gain_tx + antenna_gain_rx - total_path_loss
    rssi = max(-128.0, min(-25.0, rx_power))

    # Thermal noise floor for 125 kHz BW SX1278 + 6 dB Noise Figure
    noise_floor = -117.0 + float(np.random.normal(0, 1.8))
    snr = rssi - noise_floor
    snr = max(-22.0, min(15.0, snr))

    return round(rssi, 1), round(snr, 2)


def calculate_delivery_probability(
    rssi: float,
    snr: float,
    queue_len: int,
    battery_mv: int,
    packet_loss_rate: float,
    hop_count: int,
    snr_threshold_db: float = -7.5  # SX1278 SF7 demodulation limit
) -> float:
    """
    Computes ground-truth physical delivery success probability based on:
    1. LoRa demodulator waterfall curve (link margin)
    2. ALOHA channel contention / queue collisions
    3. TX voltage sag brownout probability
    4. Moving window packet loss history
    """
    # 1. Demodulation Waterfall Probability
    link_margin = snr - snr_threshold_db
    p_rf = 1.0 / (1.0 + math.exp(-1.1 * link_margin))

    # 2. RSSI Sensitivity Cutoff (SX1278 SF7 limit ~ -123 dBm)
    if rssi < -124.0:
        p_rf *= max(0.0, (rssi + 128.0) / 4.0)

    # 3. Queue Congestion & Collision Probability
    queue_norm = min(1.0, queue_len / 40.0)
    p_no_collision = math.exp(-1.4 * queue_norm)

    # 4. Battery Sag Brownout Probability
    if battery_mv < 3200:
        p_power = 0.20
    elif battery_mv < 3400:
        p_power = 0.65 + (battery_mv - 3200) / 200.0 * 0.30
    else:
        p_power = 1.0

    # 5. Historical Loss Factor
    p_history = 1.0 - (min(100.0, packet_loss_rate) / 100.0) * 0.6

    # 6. Hop count degradation (accumulator)
    hop_factor = max(0.6, 1.0 - (hop_count * 0.05))

    p_success = p_rf * p_no_collision * p_power * p_history * hop_factor
    return max(0.01, min(0.99, p_success))


def generate_synthetic_rows(
    num_samples: int = 6000,
    seed: int = 42
):
    """
    Generates a realistic multi-node dataset covering varied forest canopy
    conditions, distances, battery depletion stages, and traffic spikes.
    """
    np.random.seed(seed)
    random.seed(seed)

    rows = []
    num_nodes = 16
    canopy_types = ['light', 'medium', 'dense']

    for i in range(num_samples):
        node_id = random.randint(1, num_nodes)
        neighbor_id = random.choice([n for n in range(1, num_nodes + 1) if n != node_id])

        # Distance distribution: mostly 100m - 2500m, occasionally up to 4500m
        if random.random() < 0.75:
            true_dist = float(np.random.exponential(scale=650.0)) + 80.0
        else:
            true_dist = float(np.random.uniform(1200.0, 4200.0))
        true_dist = min(5500.0, true_dist)

        # Environmental canopy
        canopy = random.choices(canopy_types, weights=[0.25, 0.50, 0.25])[0]

        # Battery percentage distribution (mix of fresh, drained, critical)
        r_bat = random.random()
        if r_bat < 0.10:
            bat_pct = random.uniform(2.0, 18.0)  # Critical
        elif r_bat < 0.35:
            bat_pct = random.uniform(18.0, 55.0)  # Mid-drain
        else:
            bat_pct = random.uniform(55.0, 100.0) # Healthy

        bat_mv = simulate_lipo_voltage(bat_pct, is_transmitting=True)

        # RF Link Simulation
        rssi, snr = simulate_rf_link(true_dist, canopy_density=canopy)

        # Hop count (1-4 hops typical in tactical forest meshes)
        hop_count = random.choices([1, 2, 3, 4, 5], weights=[0.45, 0.30, 0.15, 0.07, 0.03])[0]

        # Traffic congestion / queue length
        if random.random() < 0.15:
            queue_len = random.randint(15, 50)  # Congestion burst / storm
        else:
            queue_len = random.randint(0, 12)   # Nominal traffic

        # Rolling packet loss rate correlated with RSSI and queue
        base_loss = max(0.0, min(100.0, (-rssi - 80.0) * 1.5 + (queue_len * 1.2)))
        loss_rate = max(0, min(100, int(base_loss + float(np.random.normal(0, 5.0)))))

        # Distance estimation: GPS fix available 75% of the time, RSSI-based 25%
        is_gps = random.random() < 0.75
        if is_gps:
            # GPS error: 5m to 25m jitter under trees
            est_distance = max(10, int(true_dist + float(np.random.normal(0, 12.0))))
        else:
            # RSSI-based inversion error: log-normal spread (+- 40% to 150%)
            err_factor = math.exp(float(np.random.normal(0, 0.45)))
            est_distance = max(10, min(65000, int(true_dist * err_factor)))

        last_seen_sec = random.choices(
            [random.randint(1, 30), random.randint(31, 300), random.randint(301, 3600)],
            weights=[0.70, 0.22, 0.08]
        )[0]

        uptime_min = random.randint(5, 7200) # Up to 5 days
        is_emergency = (random.random() < 0.03)

        # Calculate delivery probability & ground-truth binary outcome
        prob_success = calculate_delivery_probability(
            rssi=rssi,
            snr=snr,
            queue_len=queue_len,
            battery_mv=bat_mv,
            packet_loss_rate=loss_rate,
            hop_count=hop_count
        )

        delivery_success = 1 if (random.random() < prob_success) else 0

        # Create binary packet to verify pack/unpack compatibility
        telemetry_obj = TelemetryRecord(
            node_id=node_id,
            neighbor_id=neighbor_id,
            rssi=int(rssi),
            snr=snr,
            packet_loss_rate=loss_rate,
            battery_voltage_mv=bat_mv,
            battery_pct=int(bat_pct),
            hop_count=hop_count,
            is_gps=is_gps,
            is_emergency=is_emergency,
            queue_len=queue_len,
            distance_m=est_distance,
            last_seen_sec=last_seen_sec,
            uptime_min=uptime_min
        )
        hex_wire = telemetry_obj.to_hex()

        rows.append({
            'node_id': node_id,
            'neighbor_id': neighbor_id,
            'rssi': int(rssi),
            'snr': round(snr, 2),
            'packet_loss_rate': loss_rate,
            'battery_voltage_mv': bat_mv,
            'battery_pct': int(bat_pct),
            'hop_count': hop_count,
            'is_gps': int(is_gps),
            'is_emergency': int(is_emergency),
            'queue_len': queue_len,
            'distance_m': est_distance,
            'true_distance_m': round(true_dist, 1),
            'last_seen_sec': last_seen_sec,
            'uptime_min': uptime_min,
            'canopy_density': canopy,
            'wire_hex': hex_wire,
            'delivery_probability': round(prob_success, 4),
            'route_success': delivery_success
        })

    return rows


def save_to_csv(rows, filepath):
    fieldnames = list(rows[0].keys())
    with open(filepath, 'w', newline='', encoding='utf-8') as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def main():
    parser = argparse.ArgumentParser(description="ForestLink Synthetic Mesh Telemetry Generator")
    parser.add_argument("--samples", type=int, default=6000, help="Number of telemetry samples to generate")
    parser.add_argument("--out", type=str, default="synthetic_telemetry.csv", help="Output CSV filename")
    parser.add_argument("--seed", type=int, default=42, help="Random seed for reproducibility")
    args = parser.parse_args()

    out_path = os.path.abspath(args.out)
    print(f"[ForestLink Generator] Synthesizing {args.samples} mesh link records (seed={args.seed})...")
    rows = generate_synthetic_rows(num_samples=args.samples, seed=args.seed)

    save_to_csv(rows, out_path)
    print(f"[ForestLink Generator] Saved {len(rows)} records to {out_path}")

    # Compute quick stats
    success_count = sum(r['route_success'] for r in rows)
    rssi_vals = [r['rssi'] for r in rows]
    snr_vals = [r['snr'] for r in rows]
    dist_vals = [r['distance_m'] for r in rows]
    crit_bat = sum(1 for r in rows if r['battery_pct'] < 20)

    print(f"[ForestLink Generator] Summary Statistics:")
    print(f" - Success Rate (Overall): {success_count / len(rows) * 100:.1f}%")
    print(f" - Mean RSSI: {sum(rssi_vals) / len(rows):.1f} dBm, Mean SNR: {sum(snr_vals) / len(rows):.2f} dB")
    print(f" - Mean Distance: {sum(dist_vals) / len(rows):.1f} m")
    print(f" - Critical Battery (<20%): {crit_bat} samples")
    print(f" - Wire payload size: 18 bytes ({len(rows[0]['wire_hex'])} hex chars)")


if __name__ == '__main__':
    main()
