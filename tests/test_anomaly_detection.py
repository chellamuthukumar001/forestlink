import unittest
import os
import sys
import time

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
from ai_layer.section5_anomaly_detection.anomaly_detector import NetworkAnomalyDetector

class TestAnomalyDetection(unittest.TestCase):
    def setUp(self):
        self.detector = NetworkAnomalyDetector()

    def test_mass_node_dropout(self):
        fleet = [f"NODE_{i:03d}" for i in range(1, 11)] # 10 nodes
        # Only 4 nodes heard (6 dropped = 60% loss)
        heard = [f"NODE_{i:03d}" for i in range(1, 5)]

        alert = self.detector.check_mass_dropout(fleet, heard, window_sec=60.0)
        self.assertIsNotNone(alert)
        self.assertEqual(alert.alert_type, "MASS_DROPOUT")
        self.assertEqual(alert.severity, "CRITICAL")
        self.assertEqual(len(alert.affected_nodes), 6)

    def test_message_storm(self):
        # Baseline = 2 pps, Current = 25 pps (12.5x surge)
        alert = self.detector.check_message_storm(current_pps=25.0, baseline_pps=2.0)
        self.assertIsNotNone(alert)
        self.assertEqual(alert.alert_type, "MESSAGE_STORM")
        self.assertEqual(alert.severity, "CRITICAL")

        # Normal traffic should NOT alert
        no_alert = self.detector.check_message_storm(current_pps=3.0, baseline_pps=2.0)
        self.assertIsNone(no_alert)

    def test_relay_black_hole(self):
        records = {
            'NODE_005': {'forward_requests': 20, 'acks_received': 2}, # 90% failure
            'NODE_006': {'forward_requests': 20, 'acks_received': 18} # 10% failure (normal)
        }
        alerts = self.detector.check_relay_blackholes(records)
        self.assertEqual(len(alerts), 1)
        self.assertEqual(alerts[0].alert_type, "RELAY_BLACK_HOLE")
        self.assertIn("NODE_005", alerts[0].affected_nodes)

    def test_gps_jump_teleportation(self):
        # Coordinate jump from (11.0000, 76.0000) to (11.0200, 76.0200) (~3.1 km) in 5 seconds
        t0 = time.time()
        alert = self.detector.check_gps_jump(
            node_id="NODE_001",
            prev_lat=11.0000, prev_lon=76.0000, prev_time=t0,
            curr_lat=11.0200, curr_lon=76.0200, curr_time=t0 + 5.0
        )
        self.assertIsNotNone(alert)
        self.assertEqual(alert.alert_type, "GPS_JUMP")
        self.assertGreater(alert.metric_value, 500.0) # >500 km/h

if __name__ == '__main__':
    unittest.main()
