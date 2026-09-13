#!/usr/bin/env python3
"""
ForestLink Network Anomaly Detector (Backend / Gateway)
Implements statistical thresholding & explainable anomaly scoring for:
1. Sudden Mass Node Dropout
2. Message Storm (Broadcast Flood)
3. Repeated Failed Relays (Black Hole Node)
4. GPS Jump / Spoofing Anomalies
"""

import math
import time
from dataclasses import dataclass
from typing import List, Dict, Optional

# Severity Constants
SEVERITY_LOW = "LOW"
SEVERITY_MEDIUM = "MEDIUM"
SEVERITY_CRITICAL = "CRITICAL"

@dataclass
class AnomalyAlert:
    alert_id: str
    alert_type: str        # 'MASS_DROPOUT', 'MESSAGE_STORM', 'RELAY_BLACK_HOLE', 'GPS_JUMP'
    severity: str          # 'LOW', 'MEDIUM', 'CRITICAL'
    description: str
    affected_nodes: List[str]
    metric_value: float
    threshold_value: float
    recommended_action: str
    timestamp: float

def haversine_distance_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Calculates great-circle distance between two GPS coordinates in meters."""
    R = 6371000.0 # Earth radius in meters
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    delta_phi = math.radians(lat2 - lat1)
    delta_lambda = math.radians(lon2 - lon1)

    a = (math.sin(delta_phi / 2.0) ** 2 +
         math.cos(phi1) * math.cos(phi2) * (math.sin(delta_lambda / 2.0) ** 2))
    c = 2.0 * math.atan2(math.sqrt(a), math.sqrt(1.0 - a))
    return R * c

class NetworkAnomalyDetector:
    def __init__(
        self,
        dropout_threshold_ratio: float = 0.40,     # >40% node drop is a mass dropout
        storm_multiplier: float = 4.5,             # 4.5x baseline rate is a packet storm
        blackhole_failure_rate: float = 0.75,      # >75% ACK failure indicates black hole
        max_human_speed_kmh: float = 120.0        # Max realistic speed in wilderness
    ):
        self.dropout_threshold_ratio = dropout_threshold_ratio
        self.storm_multiplier = storm_multiplier
        self.blackhole_failure_rate = blackhole_failure_rate
        self.max_human_speed_kmh = max_human_speed_kmh

    def check_mass_dropout(
        self,
        known_active_nodes: List[str],
        currently_heard_nodes: List[str],
        window_sec: float = 60.0
    ) -> Optional[AnomalyAlert]:
        """Detects sudden mass dropouts across the mesh fleet."""
        total_known = len(known_active_nodes)
        if total_known < 4:
            return None # Need minimum network size to declare mass dropout

        heard_set = set(currently_heard_nodes)
        missing_nodes = [n for n in known_active_nodes if n not in heard_set]
        dropout_ratio = len(missing_nodes) / float(total_known)

        if dropout_ratio >= self.dropout_threshold_ratio:
            severity = SEVERITY_CRITICAL if dropout_ratio >= 0.60 else SEVERITY_MEDIUM
            return AnomalyAlert(
                alert_id=f"DROP_{int(time.time())}",
                alert_type="MASS_DROPOUT",
                severity=severity,
                description=f"Mass node dropout: {len(missing_nodes)}/{total_known} nodes silent ({dropout_ratio * 100:.1f}%) within {window_sec:.0f}s",
                affected_nodes=missing_nodes,
                metric_value=round(dropout_ratio, 3),
                threshold_value=self.dropout_threshold_ratio,
                recommended_action="Display red alert banner on mobile map; check gateway power & regional RF interference",
                timestamp=time.time()
            )
        return None

    def check_message_storm(
        self,
        current_pps: float,
        baseline_pps: float
    ) -> Optional[AnomalyAlert]:
        """Detects broadcast storms or runaway packet floods."""
        if baseline_pps <= 0.1:
            baseline_pps = 1.0

        ratio = current_pps / baseline_pps
        if ratio >= self.storm_multiplier and current_pps > 15.0:
            severity = SEVERITY_CRITICAL if ratio >= 8.0 else SEVERITY_MEDIUM
            return AnomalyAlert(
                alert_id=f"STORM_{int(time.time())}",
                alert_type="MESSAGE_STORM",
                severity=severity,
                description=f"Packet storm detected: {current_pps:.1f} packets/sec ({ratio:.1f}x baseline {baseline_pps:.1f} pps)",
                affected_nodes=["NETWORK_BROADCAST"],
                metric_value=round(current_pps, 1),
                threshold_value=round(baseline_pps * self.storm_multiplier, 1),
                recommended_action="Activate node rate limiting; throttle duplicate forwarding; log offending nodes",
                timestamp=time.time()
            )
        return None

    def check_relay_blackholes(
        self,
        relay_records: Dict[str, Dict[str, int]]
    ) -> List[AnomalyAlert]:
        """
        Detects malicious or broken relay nodes that drop packets instead of forwarding.
        relay_records: { 'NODE_003': {'forward_requests': 20, 'acks_received': 2} }
        """
        alerts = []
        for node_id, stats in relay_records.items():
            reqs = stats.get('forward_requests', 0)
            acks = stats.get('acks_received', 0)

            if reqs >= 10:
                failure_rate = (reqs - acks) / float(reqs)
                if failure_rate >= self.blackhole_failure_rate:
                    alerts.append(AnomalyAlert(
                        alert_id=f"HOLE_{node_id}_{int(time.time())}",
                        alert_type="RELAY_BLACK_HOLE",
                        severity=SEVERITY_CRITICAL,
                        description=f"Relay Black Hole: {node_id} failed {reqs - acks}/{reqs} forwards ({failure_rate * 100:.1f}% loss)",
                        affected_nodes=[node_id],
                        metric_value=round(failure_rate, 3),
                        threshold_value=self.blackhole_failure_rate,
                        recommended_action=f"Quarantine {node_id} from next-hop routing table; reroute through alternate neighbors",
                        timestamp=time.time()
                    ))
        return alerts

    def check_gps_jump(
        self,
        node_id: str,
        prev_lat: float,
        prev_lon: float,
        prev_time: float,
        curr_lat: float,
        curr_lon: float,
        curr_time: float
    ) -> Optional[AnomalyAlert]:
        """Detects impossible GPS teleportation or multipath glitch / spoofing."""
        dt = curr_time - prev_time
        if dt <= 0.5:
            return None # Time too short to establish speed

        dist_m = haversine_distance_m(prev_lat, prev_lon, curr_lat, curr_lon)
        speed_mps = dist_m / dt
        speed_kmh = speed_mps * 3.6

        if speed_kmh > self.max_human_speed_kmh or (dist_m > 800.0 and dt < 10.0):
            return AnomalyAlert(
                alert_id=f"GPS_{node_id}_{int(time.time())}",
                alert_type="GPS_JUMP",
                severity=SEVERITY_MEDIUM,
                description=f"GPS Teleportation / Jump on {node_id}: moved {dist_m:.0f}m in {dt:.1f}s (speed: {speed_kmh:.1f} km/h)",
                affected_nodes=[node_id],
                metric_value=round(speed_kmh, 1),
                threshold_value=self.max_human_speed_kmh,
                recommended_action=f"Ignore invalid GPS update for {node_id}; retain last-known reliable coordinate",
                timestamp=time.time()
            )
        return None
