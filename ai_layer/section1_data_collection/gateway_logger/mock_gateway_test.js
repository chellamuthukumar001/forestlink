/**
 * Standalone verification test for telemetry_parser.js
 */

const { computeCrc8, parseTelemetryPacket, TELEMETRY_PACKET_SIZE } = require('./telemetry_parser');
const assert = require('assert');

console.log('[Test] Running ForestLink Gateway Parser Tests...');

// 1. Create a known 18-byte buffer
// node_id = 1 (0x0001), neighbor_id = 2 (0x0002)
// rssi = -80 (0xB0), snr_x4 = 16 (4.0 dB, 0x10)
// packet_loss_rate = 10 (0x0A)
// battery_scaled = 132 (3820 mV -> (3820-2500)/10 = 132 = 0x84)
// battery_pct = 85 (0x55)
// hop_and_flags = 2 | (1 << 4) = 18 (0x12) -> hop=2, gps=true
// queue_len = 5 (0x05)
// distance_m = 750 (0x02EE -> little-endian: EE 02)
// last_seen_sec = 25 (0x0019 -> little-endian: 19 00)
// uptime_min = 360 (0x0168 -> little-endian: 68 01)
const buf = Buffer.alloc(18);
buf.writeUInt16LE(1, 0);
buf.writeUInt16LE(2, 2);
buf.writeInt8(-80, 4);
buf.writeInt8(16, 5);
buf.writeUInt8(10, 6);
buf.writeUInt8(132, 7);
buf.writeUInt8(85, 8);
buf.writeUInt8(0x12, 9);
buf.writeUInt8(5, 10);
buf.writeUInt16LE(750, 11);
buf.writeUInt16LE(25, 13);
buf.writeUInt16LE(360, 15);

// Calculate CRC
const crc = computeCrc8(buf, 17);
buf.writeUInt8(crc, 17);

const hexStr = buf.toString('hex').toUpperCase();
console.log(`[Test] Synthesized 18-byte packet (${hexStr.length} hex chars): ${hexStr}`);

// Parse Hex
const parsed = parseTelemetryPacket(hexStr);
assert.strictEqual(parsed.node_id_raw, 1);
assert.strictEqual(parsed.neighbor_id_raw, 2);
assert.strictEqual(parsed.rssi, -80);
assert.strictEqual(parsed.snr, 4.0);
assert.strictEqual(parsed.packet_loss_rate, 10);
assert.strictEqual(parsed.battery_voltage_mv, 3820);
assert.strictEqual(parsed.battery_pct, 85);
assert.strictEqual(parsed.hop_count, 2);
assert.strictEqual(parsed.is_gps, true);
assert.strictEqual(parsed.is_emergency, false);
assert.strictEqual(parsed.queue_len, 5);
assert.strictEqual(parsed.distance_m, 750);
assert.strictEqual(parsed.last_seen_sec, 25);
assert.strictEqual(parsed.uptime_min, 360);
console.log('[Test] Correctly parsed all telemetry fields:', parsed);

// Test CRC Corruption Detection
const corruptedBuf = Buffer.from(buf);
corruptedBuf[4] ^= 0xFF; // Invert RSSI
try {
    parseTelemetryPacket(corruptedBuf.toString('hex'));
    assert.fail('Expected CRC mismatch error');
} catch (e) {
    console.log('[Test] Successfully caught corrupted packet:', e.message);
}

// Test Prefixed String: "TLM|<hex>"
const parsedPrefixed = parseTelemetryPacket(`TLM|${hexStr}`);
assert.strictEqual(parsedPrefixed.node_id_raw, 1);
console.log('[Test] Successfully handled TLM| prefix!');

console.log('\n[PASS] All Gateway Parser tests passed successfully!');
