/**
 * ForestLink Telemetry Binary Parser (Node.js)
 * Decodes 18-byte packed telemetry packets with CRC-8 validation.
 */

const TELEMETRY_PACKET_SIZE = 18;

/**
 * CRC-8 with polynomial 0x07 (Matches C & Python implementations)
 * @param {Buffer} buffer 
 * @param {number} len 
 * @returns {number} 8-bit checksum
 */
function computeCrc8(buffer, len = buffer.length) {
    let crc = 0x00;
    for (let i = 0; i < len; i++) {
        crc ^= buffer[i];
        for (let j = 0; j < 8; j++) {
            if ((crc & 0x80) !== 0) {
                crc = ((crc << 1) ^ 0x07) & 0xFF;
            } else {
                crc = (crc << 1) & 0xFF;
            }
        }
    }
    return crc;
}

/**
 * Parses an 18-byte binary Buffer or 36-char HEX string.
 * @param {Buffer|string} input 
 * @returns {Object} Parsed telemetry record
 */
function parseTelemetryPacket(input) {
    let buf;
    if (typeof input === 'string') {
        const cleaned = input.trim().replace(/^TLM\|/, '');
        if (cleaned.length !== TELEMETRY_PACKET_SIZE * 2) {
            throw new Error(`Invalid hex length: expected ${TELEMETRY_PACKET_SIZE * 2} characters, got ${cleaned.length}`);
        }
        buf = Buffer.from(cleaned, 'hex');
    } else if (Buffer.isBuffer(input)) {
        buf = input;
    } else {
        throw new Error('Input must be a hex string or Buffer');
    }

    if (buf.length !== TELEMETRY_PACKET_SIZE) {
        throw new Error(`Invalid buffer size: expected ${TELEMETRY_PACKET_SIZE} bytes, got ${buf.length}`);
    }

    // CRC-8 Verification
    const expectedCrc = computeCrc8(buf, TELEMETRY_PACKET_SIZE - 1);
    const actualCrc = buf[TELEMETRY_PACKET_SIZE - 1];
    if (expectedCrc !== actualCrc) {
        throw new Error(`CRC-8 checksum mismatch: expected 0x${expectedCrc.toString(16).toUpperCase()}, got 0x${actualCrc.toString(16).toUpperCase()}`);
    }

    // Little-Endian unpacking
    const nodeId = buf.readUInt16LE(0);
    const neighborId = buf.readUInt16LE(2);
    const rssi = buf.readInt8(4);
    const snrX4 = buf.readInt8(5);
    const snr = Number((snrX4 / 4.0).toFixed(2));
    const packetLossRate = buf.readUInt8(6);
    const batteryScaled = buf.readUInt8(7);
    const batteryVoltageMv = 2500 + (batteryScaled * 10);
    const batteryPct = buf.readUInt8(8);
    const hopAndFlags = buf.readUInt8(9);
    const queueLen = buf.readUInt8(10);
    const distanceM = buf.readUInt16LE(11);
    const lastSeenSec = buf.readUInt16LE(13);
    const uptimeMin = buf.readUInt16LE(15);

    const hopCount = hopAndFlags & 0x0F;
    const isGps = (hopAndFlags & (1 << 4)) !== 0;
    const isEmergency = (hopAndFlags & (1 << 5)) !== 0;

    return {
        node_id: `NODE_${String(nodeId).padStart(3, '0')}`,
        neighbor_id: `NODE_${String(neighborId).padStart(3, '0')}`,
        node_id_raw: nodeId,
        neighbor_id_raw: neighborId,
        rssi,
        snr,
        packet_loss_rate: packetLossRate,
        battery_voltage_mv: batteryVoltageMv,
        battery_pct: batteryPct,
        hop_count: hopCount,
        is_gps: isGps,
        is_emergency: isEmergency,
        queue_len: queueLen,
        distance_m: distanceM,
        last_seen_sec: lastSeenSec,
        uptime_min: uptimeMin,
        raw_hex: buf.toString('hex').toUpperCase()
    };
}

module.exports = {
    TELEMETRY_PACKET_SIZE,
    computeCrc8,
    parseTelemetryPacket
};
