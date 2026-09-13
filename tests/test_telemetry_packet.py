import unittest
import sys
import os

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
from ai_layer.common.telemetry_packet import TelemetryRecord, TELEMETRY_PACKET_SIZE, compute_crc8

class TestTelemetryPacket(unittest.TestCase):
    def test_packet_size(self):
        self.assertEqual(TELEMETRY_PACKET_SIZE, 18)

    def test_roundtrip_pack_unpack(self):
        record = TelemetryRecord(
            node_id=101,
            neighbor_id=202,
            rssi=-78,
            snr=4.25,
            packet_loss_rate=5,
            battery_voltage_mv=3820,
            battery_pct=85,
            hop_count=2,
            is_gps=True,
            is_emergency=False,
            queue_len=3,
            distance_m=450,
            last_seen_sec=12,
            uptime_min=140
        )
        packed = record.pack()
        self.assertEqual(len(packed), 18)

        # Unpack
        recovered = TelemetryRecord.unpack(packed)
        self.assertEqual(recovered.node_id, 101)
        self.assertEqual(recovered.neighbor_id, 202)
        self.assertEqual(recovered.rssi, -78)
        self.assertAlmostEqual(recovered.snr, 4.25, places=2)
        self.assertEqual(recovered.packet_loss_rate, 5)
        # Scaled voltage resolution is 10mV
        self.assertEqual(recovered.battery_voltage_mv, 3820)
        self.assertEqual(recovered.battery_pct, 85)
        self.assertEqual(recovered.hop_count, 2)
        self.assertTrue(recovered.is_gps)
        self.assertFalse(recovered.is_emergency)
        self.assertEqual(recovered.queue_len, 3)
        self.assertEqual(recovered.distance_m, 450)
        self.assertEqual(recovered.last_seen_sec, 12)
        self.assertEqual(recovered.uptime_min, 140)

    def test_hex_conversion(self):
        record = TelemetryRecord(
            node_id=1,
            neighbor_id=2,
            rssi=-90,
            snr=-5.5,
            packet_loss_rate=12,
            battery_voltage_mv=3600,
            battery_pct=45,
            hop_count=1,
            is_gps=False,
            is_emergency=True,
            queue_len=7,
            distance_m=1200,
            last_seen_sec=55,
            uptime_min=720
        )
        hex_str = record.to_hex()
        self.assertEqual(len(hex_str), 36) # 18 bytes * 2
        recovered = TelemetryRecord.from_hex(hex_str)
        self.assertEqual(recovered.node_id, 1)
        self.assertTrue(recovered.is_emergency)
        self.assertFalse(recovered.is_gps)

    def test_crc_corruption(self):
        record = TelemetryRecord(
            node_id=1, neighbor_id=2, rssi=-80, snr=0.0,
            packet_loss_rate=0, battery_voltage_mv=4000, battery_pct=90,
            hop_count=1, is_gps=True, is_emergency=False,
            queue_len=0, distance_m=100, last_seen_sec=5, uptime_min=10
        )
        packed = bytearray(record.pack())
        packed[4] ^= 0xFF # corrupt RSSI
        with self.assertRaises(ValueError):
            TelemetryRecord.unpack(bytes(packed))

if __name__ == '__main__':
    unittest.main()
