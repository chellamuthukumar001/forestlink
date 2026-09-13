/**
 * ForestLink Gateway Telemetry Logging Service
 * Ingests 18-byte packed telemetry from ESP32-S3 gateways via Serial/HTTP,
 * validates integrity, and streams into Supabase for ML training.
 */

const express = require('express');
const { createClient } = require('@supabase/supabase-js');
const { parseTelemetryPacket } = require('./telemetry_parser');
require('dotenv').config();

const app = express();
app.use(express.json());

const PORT = process.env.PORT || 3000;
const SUPABASE_URL = process.env.SUPABASE_URL || '';
const SUPABASE_KEY = process.env.SUPABASE_SERVICE_ROLE_KEY || process.env.SUPABASE_ANON_KEY || '';

let supabase = null;
if (SUPABASE_URL && SUPABASE_KEY) {
    supabase = createClient(SUPABASE_URL, SUPABASE_KEY);
    console.log('[ForestLink Gateway] Connected to Supabase at:', SUPABASE_URL);
} else {
    console.warn('[ForestLink Gateway] WARNING: SUPABASE_URL or SUPABASE_KEY not set. Running in local dry-run mode.');
}

// In-memory buffer for local testing / fallback
const localTelemetryBuffer = [];

/**
 * Persists parsed telemetry record to Supabase or local memory
 */
async function persistTelemetryRecord(record, routeSuccess = null) {
    const payload = {
        node_id: record.node_id,
        neighbor_id: record.neighbor_id,
        rssi: record.rssi,
        snr: record.snr,
        packet_loss_rate: record.packet_loss_rate,
        battery_voltage_mv: record.battery_voltage_mv,
        battery_pct: record.battery_pct,
        hop_count: record.hop_count,
        is_gps: record.is_gps,
        is_emergency: record.is_emergency,
        queue_len: record.queue_len,
        distance_m: record.distance_m,
        last_seen_sec: record.last_seen_sec,
        uptime_min: record.uptime_min,
        raw_hex: record.raw_hex,
        route_success: routeSuccess,
        created_at: new Date().toISOString()
    };

    if (supabase) {
        const { data, error } = await supabase
            .from('forestlink_telemetry')
            .insert([payload])
            .select();

        if (error) {
            console.error('[Supabase Ingest Error]:', error.message);
            throw error;
        }
        return data ? data[0] : payload;
    } else {
        payload.id = localTelemetryBuffer.length + 1;
        localTelemetryBuffer.push(payload);
        if (localTelemetryBuffer.length > 500) localTelemetryBuffer.shift();
        return payload;
    }
}

// ================= HTTP Routes =================

// Health check
app.get('/health', (req, res) => {
    res.json({
        status: 'healthy',
        timestamp: new Date().toISOString(),
        supabase_connected: !!supabase,
        buffered_records: localTelemetryBuffer.length
    });
});

// Single Telemetry Ingest (Accepts raw hex or parsed JSON)
app.post('/api/telemetry/ingest', async (req, res) => {
    try {
        const { raw_hex, route_success } = req.body;
        let record;

        if (raw_hex) {
            record = parseTelemetryPacket(raw_hex);
        } else if (req.body.node_id && req.body.rssi !== undefined) {
            record = req.body;
        } else {
            return res.status(400).json({ error: 'Payload must contain raw_hex or structured telemetry fields' });
        }

        const saved = await persistTelemetryRecord(record, route_success !== undefined ? route_success : null);
        res.status(201).json({ success: true, record: saved });
    } catch (err) {
        res.status(400).json({ error: err.message });
    }
});

// Batch Telemetry Ingest
app.post('/api/telemetry/batch', async (req, res) => {
    try {
        const { packets } = req.body;
        if (!Array.isArray(packets)) {
            return res.status(400).json({ error: 'packets must be an array' });
        }

        const results = [];
        for (const item of packets) {
            const raw = typeof item === 'string' ? item : item.raw_hex;
            const successVal = (typeof item === 'object' && item.route_success !== undefined) ? item.route_success : null;
            if (raw) {
                const parsed = parseTelemetryPacket(raw);
                const saved = await persistTelemetryRecord(parsed, successVal);
                results.push(saved);
            }
        }

        res.status(201).json({ success: true, count: results.length, records: results });
    } catch (err) {
        res.status(500).json({ error: err.message });
    }
});

// Record Ground Truth Route Success / ACK Event
app.post('/api/telemetry/ack', async (req, res) => {
    try {
        const { telemetry_id, route_success } = req.body;
        if (!telemetry_id || route_success === undefined) {
            return res.status(400).json({ error: 'telemetry_id and route_success (boolean) are required' });
        }

        if (supabase) {
            const { data, error } = await supabase
                .from('forestlink_telemetry')
                .update({ route_success: !!route_success })
                .eq('id', telemetry_id)
                .select();

            if (error) throw error;
            return res.json({ success: true, data });
        } else {
            const record = localTelemetryBuffer.find(r => r.id === telemetry_id);
            if (record) record.route_success = !!route_success;
            return res.json({ success: true, record });
        }
    } catch (err) {
        res.status(500).json({ error: err.message });
    }
});

// Query Latest Mesh Links
app.get('/api/telemetry/latest', async (req, res) => {
    try {
        if (supabase) {
            const { data, error } = await supabase
                .from('v_latest_mesh_links')
                .select('*');
            if (error) throw error;
            return res.json({ success: true, links: data });
        } else {
            return res.json({ success: true, links: localTelemetryBuffer.slice(-20) });
        }
    } catch (err) {
        res.status(500).json({ error: err.message });
    }
});

// In-memory active anomaly alert store
const activeAnomalies = [];

// Fleet-wide Node Health Query
app.get('/api/nodes/health', (req, res) => {
    // Aggregates health from buffer / supabase
    const nodeMap = new Map();
    for (const record of localTelemetryBuffer) {
        const mv = record.battery_voltage_mv;
        const pct = record.battery_pct;
        let status = 'HEALTHY';
        let hoursEst = 48.0;

        if (pct < 15 || mv < 3300) {
            status = 'CRITICAL';
            hoursEst = 1.2;
        } else if (pct < 45 || mv < 3650) {
            status = 'DEGRADING';
            hoursEst = 12.0;
        }

        nodeMap.set(record.node_id, {
            node_id: record.node_id,
            battery_pct: pct,
            battery_voltage_mv: mv,
            status: status,
            hours_remaining_est: hoursEst,
            last_seen_sec: record.last_seen_sec,
            uptime_min: record.uptime_min
        });
    }

    res.json({
        success: true,
        nodes: Array.from(nodeMap.values())
    });
});

// Best Next-Hop Routing Decisions
app.get('/api/routes/best', (req, res) => {
    const bestRoutes = [];
    const grouped = new Map();

    for (const record of localTelemetryBuffer) {
        if (!grouped.has(record.node_id)) {
            grouped.set(record.node_id, []);
        }
        grouped.get(record.node_id).push(record);
    }

    for (const [nodeId, links] of grouped.entries()) {
        // Sort by link margin & queue
        links.sort((a, b) => (b.snr - a.snr));
        const topLink = links[0];
        const relScore = Math.min(0.99, Math.max(0.1, (topLink.snr + 15.0) / 25.0));

        bestRoutes.push({
            origin_node: nodeId,
            candidate_next_hop: topLink.neighbor_id,
            reliability_score: Number(relScore.toFixed(3)),
            routing_mode: relScore >= 0.35 ? 'AI_DIRECT' : 'DETERMINISTIC_FALLBACK',
            rssi: topLink.rssi,
            snr: topLink.snr,
            hop_count: topLink.hop_count
        });
    }

    res.json({
        success: true,
        routes: bestRoutes
    });
});

// Active Network Anomaly Alerts
app.get('/api/anomalies/active', (req, res) => {
    res.json({
        success: true,
        count: activeAnomalies.length,
        anomalies: activeAnomalies
    });
});

// Trigger / Log Anomaly Alert
app.post('/api/anomalies/trigger', (req, res) => {
    const { alert_type, severity, description, affected_nodes, recommended_action } = req.body;
    if (!alert_type || !severity || !description) {
        return res.status(400).json({ error: 'alert_type, severity, and description are required' });
    }

    const alert = {
        alert_id: `ALERT_${Date.now()}`,
        alert_type,
        severity,
        description,
        affected_nodes: affected_nodes || [],
        recommended_action: recommended_action || 'Review network topology',
        timestamp: new Date().toISOString()
    };

    activeAnomalies.unshift(alert);
    if (activeAnomalies.length > 20) activeAnomalies.pop();

    res.status(201).json({ success: true, alert });
});

// ================= Optional Serial Port Listener =================
if (process.env.SERIAL_PORT) {
    try {
        const { SerialPort, ReadlineParser } = require('serialport');
        const port = new SerialPort({ path: process.env.SERIAL_PORT, baudRate: 115200 });
        const parser = port.pipe(new ReadlineParser({ delimiter: '\r\n' }));

        console.log(`[Serial Gateway] Listening on port ${process.env.SERIAL_PORT}...`);

        parser.on('data', async (line) => {
            const trimmed = line.trim();
            if (trimmed.startsWith('TLM|')) {
                try {
                    const hexPayload = trimmed.substring(4);
                    const record = parseTelemetryPacket(hexPayload);
                    console.log(`[Serial RX TLM] From ${record.node_id} -> ${record.neighbor_id}, RSSI: ${record.rssi}dBm, SNR: ${record.snr}dB`);
                    await persistTelemetryRecord(record);
                } catch (e) {
                    console.error('[Serial Parse Error]:', e.message);
                }
            }
        });
    } catch (e) {
        console.warn('[Serial Gateway] serialport module not initialized:', e.message);
    }
}

if (require.main === module) {
    app.listen(PORT, () => {
        console.log(`[ForestLink Gateway] Server active on http://localhost:${PORT}`);
    });
}

module.exports = { app, persistTelemetryRecord };
