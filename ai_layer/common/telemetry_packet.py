"""
ForestLink Compact Telemetry Packet Codec (Python Mirror)
Matches ai_layer/common/telemetry_packet.h byte-for-byte (18 bytes total).
"""

import struct
from dataclasses import dataclass
from typing import Optional

TELEMETRY_PACKET_SIZE = 18
# Fields:
# node_id (H), neighbor_id (H), rssi (b), snr_x4 (b),
# packet_loss_rate (B), battery_scaled (B), battery_pct (B), hop_and_flags (B), queue_len (B),
# distance_m (H), last_seen_sec (H), uptime_min (H), checksum (B)
TELEMETRY_FORMAT = '<HHbbBBBBBHHHB'  # Exactly 18 bytes, little-endian

assert struct.calcsize(TELEMETRY_FORMAT) == TELEMETRY_PACKET_SIZE, f"Format must be exactly 18 bytes, got {struct.calcsize(TELEMETRY_FORMAT)}"


def compute_crc8(data: bytes) -> int:
    """CRC-8 with polynomial 0x07, initial value 0x00 (matches C implementation)."""
    crc = 0x00
    for byte in data:
        crc ^= byte
        for _ in range(8):
            if crc & 0x80:
                crc = ((crc << 1) ^ 0x07) & 0xFF
            else:
                crc = (crc << 1) & 0xFF
    return crc


@dataclass
class TelemetryRecord:
    node_id: int              # 0 - 65535
    neighbor_id: int          # 0 - 65535
    rssi: int                 # -128 to 0 dBm
    snr: float                # dB (-32.0 to +31.75 dB, 0.25 dB resolution)
    packet_loss_rate: int     # 0 - 100%
    battery_voltage_mv: int   # 2500 - 5050 mV (10 mV resolution)
    battery_pct: int          # 0 - 100%
    hop_count: int            # 0 - 15
    is_gps: bool              # True = GPS, False = RSSI distance estimate
    is_emergency: bool        # True = SOS / emergency active
    queue_len: int            # 0 - 255
    distance_m: int           # 0 - 65535 meters
    last_seen_sec: int        # 0 - 65535 seconds
    uptime_min: int           # 0 - 65535 minutes (~45 days)
    checksum: Optional[int] = None

    def pack(self) -> bytes:
        """Serializes the record into an 18-byte binary buffer with CRC-8."""
        # Quantize and clamp
        snr_x4 = int(round(self.snr * 4.0))
        snr_x4 = max(-128, min(127, snr_x4))

        clamped_mv = max(2500, min(5050, self.battery_voltage_mv))
        battery_scaled = int((clamped_mv - 2500) // 10)

        hop_and_flags = (self.hop_count & 0x0F)
        if self.is_gps:
            hop_and_flags |= (1 << 4)
        if self.is_emergency:
            hop_and_flags |= (1 << 5)

        loss_clamped = max(0, min(100, int(self.packet_loss_rate)))
        bat_pct_clamped = max(0, min(100, int(self.battery_pct)))
        queue_clamped = max(0, min(255, int(self.queue_len)))
        dist_clamped = max(0, min(65535, int(self.distance_m)))
        last_seen_clamped = max(0, min(65535, int(self.last_seen_sec)))
        uptime_clamped = max(0, min(65535, int(self.uptime_min)))

        payload_without_crc = struct.pack(
            '<HHbbBBBBBHHH',
            self.node_id & 0xFFFF,
            self.neighbor_id & 0xFFFF,
            max(-128, min(0, int(self.rssi))),
            snr_x4,
            loss_clamped,
            battery_scaled,
            bat_pct_clamped,
            hop_and_flags,
            queue_clamped,
            dist_clamped,
            last_seen_clamped,
            uptime_clamped
        )

        crc = compute_crc8(payload_without_crc)
        self.checksum = crc
        return payload_without_crc + bytes([crc])

    def to_hex(self) -> str:
        """Encodes to 36-character uppercase HEX string."""
        return self.pack().hex().upper()

    @classmethod
    def unpack(cls, data: bytes) -> 'TelemetryRecord':
        """Unpacks 18 bytes into a TelemetryRecord and verifies CRC-8."""
        if len(data) != TELEMETRY_PACKET_SIZE:
            raise ValueError(f"Expected {TELEMETRY_PACKET_SIZE} bytes, got {len(data)}")

        expected_crc = compute_crc8(data[:17])
        actual_crc = data[17]
        if expected_crc != actual_crc:
            raise ValueError(f"CRC-8 mismatch: expected 0x{expected_crc:02X}, got 0x{actual_crc:02X}")

        fields = struct.unpack(TELEMETRY_FORMAT, data)
        (
            node_id,
            neighbor_id,
            rssi,
            snr_x4,
            loss_rate,
            battery_scaled,
            battery_pct,
            hop_and_flags,
            queue_len,
            distance_m,
            last_seen_sec,
            uptime_min,
            checksum
        ) = fields

        snr = snr_x4 / 4.0
        battery_mv = 2500 + (battery_scaled * 10)
        hop_count = hop_and_flags & 0x0F
        is_gps = bool(hop_and_flags & (1 << 4))
        is_emergency = bool(hop_and_flags & (1 << 5))

        return cls(
            node_id=node_id,
            neighbor_id=neighbor_id,
            rssi=rssi,
            snr=snr,
            packet_loss_rate=loss_rate,
            battery_voltage_mv=battery_mv,
            battery_pct=battery_pct,
            hop_count=hop_count,
            is_gps=is_gps,
            is_emergency=is_emergency,
            queue_len=queue_len,
            distance_m=distance_m,
            last_seen_sec=last_seen_sec,
            uptime_min=uptime_min,
            checksum=checksum
        )

    @classmethod
    def from_hex(cls, hex_str: str) -> 'TelemetryRecord':
        """Decodes from 36-character HEX string."""
        raw = bytes.fromhex(hex_str.strip())
        return cls.unpack(raw)
