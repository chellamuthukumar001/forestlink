import unittest
import os
import sys
import joblib
import numpy as np

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
from ai_layer.common.telemetry_packet import TelemetryRecord
from ai_layer.section2_route_reliability.train_route_scorer import extract_features, FEATURE_NAMES

class TestModelInference(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        model_path = os.path.join(os.path.dirname(__file__), '../ai_layer/section2_route_reliability/route_scorer_rf.joblib')
        cls.rf_model = joblib.load(model_path)

    def test_feature_extraction(self):
        sample = {
            'rssi': -85,
            'snr': 2.5,
            'packet_loss_rate': 4,
            'battery_pct': 90,
            'battery_voltage_mv': 3900,
            'queue_len': 2,
            'distance_m': 300,
            'hop_count': 1,
            'last_seen_sec': 10
        }
        feats = extract_features(sample)
        self.assertEqual(len(feats), 12)
        self.assertAlmostEqual(feats[0], -85.0)
        self.assertAlmostEqual(feats[1], 2.5)
        self.assertAlmostEqual(feats[2], 10.0) # link_margin = 2.5 - (-7.5) = 10.0

    def test_prediction_range(self):
        # Good link
        good_sample = {
            'rssi': -65, 'snr': 8.0, 'packet_loss_rate': 0, 'battery_pct': 95,
            'battery_voltage_mv': 4100, 'queue_len': 0, 'distance_m': 100,
            'hop_count': 1, 'last_seen_sec': 5
        }
        f_good = np.array([extract_features(good_sample)], dtype=np.float32)
        score_good = self.rf_model.predict_proba(f_good)[0, 1]

        # Terrible link (deep foliage, dead battery, high queue)
        bad_sample = {
            'rssi': -125, 'snr': -18.0, 'packet_loss_rate': 85, 'battery_pct': 5,
            'battery_voltage_mv': 3100, 'queue_len': 40, 'distance_m': 4500,
            'hop_count': 5, 'last_seen_sec': 1800
        }
        f_bad = np.array([extract_features(bad_sample)], dtype=np.float32)
        score_bad = self.rf_model.predict_proba(f_bad)[0, 1]

        self.assertGreater(score_good, 0.60, f"Good link score too low: {score_good}")
        self.assertLess(score_bad, 0.30, f"Bad link score too high: {score_bad}")
        self.assertGreater(score_good, score_bad)

if __name__ == '__main__':
    unittest.main()
