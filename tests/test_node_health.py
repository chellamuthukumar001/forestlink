import unittest
import os
import sys
import joblib
import numpy as np

class TestNodeHealth(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        model_path = os.path.join(os.path.dirname(__file__), '../ai_layer/section4_node_health/health_classifier.joblib')
        cls.clf = joblib.load(model_path)

    def test_healthy_state_prediction(self):
        # 4100mV, 90%, slow discharge -5 mV/hr, 25C, low variance 2.0, 0 missed
        feats = np.array([[4100, 90, -5.0, 25.0, 2.0, 0]], dtype=np.float32)
        pred = self.clf.predict(feats)[0]
        self.assertEqual(pred, 0) # 0 = HEALTHY

    def test_degrading_state_prediction(self):
        # 3550mV, 35%, moderate discharge -30 mV/hr, 38C, moderate variance 14.0, 2 missed
        feats = np.array([[3550, 35, -30.0, 38.0, 14.0, 2]], dtype=np.float32)
        pred = self.clf.predict(feats)[0]
        self.assertEqual(pred, 1) # 1 = DEGRADING

    def test_critical_state_prediction(self):
        # 3150mV, 8%, rapid discharge -90 mV/hr, 52C (thermal stress), high variance 35.0, 6 missed
        feats = np.array([[3150, 8, -90.0, 52.0, 35.0, 6]], dtype=np.float32)
        pred = self.clf.predict(feats)[0]
        self.assertEqual(pred, 2) # 2 = CRITICAL

if __name__ == '__main__':
    unittest.main()
