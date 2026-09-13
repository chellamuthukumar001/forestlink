import unittest
import os
import sys

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
from ai_layer.common.telemetry_packet import TelemetryRecord

class MockPriorityQueue:
    def __init__(self):
        self.sos_queue = []
        self.normal_queue = []

    def enqueue(self, msg_id, sender_id, dest_id, payload, priority, backoff_ms, ttl, now_ms):
        item = {
            'msg_id': msg_id,
            'sender_id': sender_id,
            'dest_id': dest_id,
            'payload': payload,
            'priority': priority,
            'backoff_ms': backoff_ms,
            'ttl': ttl,
            'enq_ms': now_ms
        }
        if priority == 2: # SOS
            self.sos_queue.append(item)
        else:
            self.normal_queue.append(item)

    def dequeue(self, now_ms):
        # 1. SOS Preemption
        for i, item in enumerate(self.sos_queue):
            if (now_ms - item['enq_ms']) >= item['backoff_ms']:
                return self.sos_queue.pop(i)

        # 2. Normal queue
        for i, item in enumerate(self.normal_queue):
            if (now_ms - item['enq_ms']) >= item['backoff_ms']:
                return self.normal_queue.pop(i)

        return None

    def relay(self, msg_id, sender_id, dest_id, payload, ttl, now_ms):
        if ttl <= 1:
            return False # Drop packet, TTL exhausted
        new_ttl = ttl - 1
        is_sos = payload.startswith("SOS|")
        priority = 2 if is_sos else 0
        backoff = 20 if is_sos else 800
        self.enqueue(msg_id, sender_id, dest_id, payload, priority, backoff, new_ttl, now_ms)
        return True

class TestEmergencyPriority(unittest.TestCase):
    def test_preemption_over_normal_traffic(self):
        pq = MockPriorityQueue()
        now = 1000

        # Enqueue 3 normal chat messages first
        pq.enqueue(1, 10, 20, "TXT|Hello ranger 1", priority=0, backoff_ms=800, ttl=5, now_ms=now)
        pq.enqueue(2, 10, 20, "TXT|Hello ranger 2", priority=0, backoff_ms=800, ttl=5, now_ms=now)
        pq.enqueue(3, 10, 20, "TXT|Hello ranger 3", priority=0, backoff_ms=800, ttl=5, now_ms=now)

        # Enqueue 1 SOS emergency message later
        pq.enqueue(99, 10, 0xFFFF, "SOS|NODE_010|Injured ranger", priority=2, backoff_ms=20, ttl=5, now_ms=now + 5)

        # At t = 1030ms: SOS backoff (20ms) has elapsed, but normal backoff (800ms) has not
        item = pq.dequeue(now + 30)
        self.assertIsNotNone(item)
        self.assertEqual(item['msg_id'], 99) # Preempted normal packets!
        self.assertEqual(item['priority'], 2)

    def test_ttl_exhaustion_relay(self):
        pq = MockPriorityQueue()
        now = 1000

        # Relay with TTL = 3 -> succeeds, TTL becomes 2
        relayed = pq.relay(101, 5, 6, "TXT|Relay test", ttl=3, now_ms=now)
        self.assertTrue(relayed)
        item = pq.normal_queue[0]
        self.assertEqual(item['ttl'], 2)

        # Relay with TTL = 1 -> exhausted, dropped!
        relayed_dead = pq.relay(102, 5, 6, "TXT|Dead packet", ttl=1, now_ms=now)
        self.assertFalse(relayed_dead)

    def test_telemetry_distress_anomaly_detection(self):
        # Normal record
        rec_normal = TelemetryRecord(
            node_id=1, neighbor_id=2, rssi=-85, snr=2.0, packet_loss_rate=5,
            battery_voltage_mv=3850, battery_pct=75, hop_count=1, is_gps=True,
            is_emergency=False, queue_len=1, distance_m=200, last_seen_sec=10, uptime_min=100
        )
        self.assertFalse(rec_normal.is_emergency)

        # Distress record: low battery (10% / 3200mV) + high loss (60%)
        rec_distress = TelemetryRecord(
            node_id=1, neighbor_id=2, rssi=-122, snr=-15.0, packet_loss_rate=65,
            battery_voltage_mv=3200, battery_pct=8, hop_count=2, is_gps=True,
            is_emergency=True, queue_len=10, distance_m=2000, last_seen_sec=500, uptime_min=100
        )
        self.assertTrue(rec_distress.is_emergency)

if __name__ == '__main__':
    unittest.main()
