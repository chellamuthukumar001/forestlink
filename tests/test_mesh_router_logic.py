"""
Python-level logic validation for the Mesh Router next-hop decision algorithm.
Matches embedded C++ implementation in mesh_router.cpp.
"""

import unittest
import os
import sys

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
from ai_layer.common.telemetry_packet import TelemetryRecord

ROUTING_CONFIDENCE_THRESHOLD = 0.35

class MockNeighbor:
    def __init__(self, node_id, reliability, hops, queue_len, last_heard_sec):
        self.node_id = node_id
        self.reliability = reliability
        self.hops = hops
        self.queue_len = queue_len
        self.last_heard_sec = last_heard_sec

def select_next_hop(neighbors, is_emergency=False, ai_enabled=True, max_age_sec=120):
    if not ai_enabled:
        return {'type': 'FALLBACK_DETERMINISTIC', 'next_hop': None, 'reason': 'AI disabled'}

    active = [n for n in neighbors if n.last_heard_sec <= max_age_sec]
    if not active:
        return {'type': 'FALLBACK_DETERMINISTIC', 'next_hop': None, 'reason': 'No active neighbors'}

    best_cand = None
    best_score = -999.0

    for n in active:
        if is_emergency:
            score = n.reliability
        else:
            hop_penalty = n.hops / 10.0
            queue_penalty = min(1.0, n.queue_len / 32.0)
            score = (0.65 * n.reliability) - (0.20 * hop_penalty) - (0.15 * queue_penalty)

        if score > best_score:
            best_score = score
            best_cand = n

    if best_cand.reliability < ROUTING_CONFIDENCE_THRESHOLD:
        return {
            'type': 'FALLBACK_DETERMINISTIC',
            'next_hop': 0xFFFF,
            'reliability': best_cand.reliability,
            'reason': 'Low confidence fail-safe'
        }

    return {
        'type': 'EMERGENCY_PRIORITY' if is_emergency else 'AI_DIRECT',
        'next_hop': best_cand.node_id,
        'reliability': best_cand.reliability,
        'score': best_score,
        'reason': 'Optimal selected'
    }

class TestMeshRouterLogic(unittest.TestCase):
    def test_optimal_route_selection(self):
        # Neighbor A: Excellent link (rel=0.85, hops=2, queue=1)
        # Neighbor B: Poor link (rel=0.40, hops=1, queue=15)
        neighbors = [
            MockNeighbor(node_id=10, reliability=0.85, hops=2, queue_len=1, last_heard_sec=5),
            MockNeighbor(node_id=20, reliability=0.40, hops=1, queue_len=15, last_heard_sec=8)
        ]
        decision = select_next_hop(neighbors, is_emergency=False)
        self.assertEqual(decision['type'], 'AI_DIRECT')
        self.assertEqual(decision['next_hop'], 10)

    def test_emergency_preemption(self):
        # Neighbor A: Shorter hops but lower reliability (hops=1, rel=0.60)
        # Neighbor B: Slightly longer hops but rock-solid link (hops=3, rel=0.95)
        neighbors = [
            MockNeighbor(node_id=10, reliability=0.60, hops=1, queue_len=0, last_heard_sec=2),
            MockNeighbor(node_id=20, reliability=0.95, hops=3, queue_len=0, last_heard_sec=2)
        ]
        decision = select_next_hop(neighbors, is_emergency=True)
        self.assertEqual(decision['type'], 'EMERGENCY_PRIORITY')
        # In emergency mode, highest reliability is chosen regardless of hop penalty
        self.assertEqual(decision['next_hop'], 20)

    def test_failsafe_degradation(self):
        # All neighbors have terrible reliability below 0.35
        neighbors = [
            MockNeighbor(node_id=10, reliability=0.22, hops=1, queue_len=20, last_heard_sec=10),
            MockNeighbor(node_id=20, reliability=0.28, hops=2, queue_len=25, last_heard_sec=15)
        ]
        decision = select_next_hop(neighbors, is_emergency=False)
        self.assertEqual(decision['type'], 'FALLBACK_DETERMINISTIC')
        self.assertIn('fail-safe', decision['reason'].lower())

    def test_feature_flag_toggle(self):
        neighbors = [
            MockNeighbor(node_id=10, reliability=0.90, hops=1, queue_len=0, last_heard_sec=5)
        ]
        decision = select_next_hop(neighbors, is_emergency=False, ai_enabled=False)
        self.assertEqual(decision['type'], 'FALLBACK_DETERMINISTIC')
        self.assertIn('AI disabled', decision['reason'])

    def test_stale_neighbor_expiration(self):
        neighbors = [
            MockNeighbor(node_id=10, reliability=0.90, hops=1, queue_len=0, last_heard_sec=250) # Expired (>120s)
        ]
        decision = select_next_hop(neighbors, is_emergency=False)
        self.assertEqual(decision['type'], 'FALLBACK_DETERMINISTIC')
        self.assertIn('No active neighbors', decision['reason'])

if __name__ == '__main__':
    unittest.main()
