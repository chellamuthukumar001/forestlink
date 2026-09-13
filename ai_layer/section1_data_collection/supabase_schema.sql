-- ====================================================================
-- ForestLink Tactical Mesh - Supabase Telemetry & Routing Schema
-- Stores 18-byte telemetry records ingested from field nodes via Gateway
-- ====================================================================

-- 1. Create Telemetry Table
CREATE TABLE IF NOT EXISTS forestlink_telemetry (
    id BIGSERIAL PRIMARY KEY,
    node_id VARCHAR(32) NOT NULL,
    neighbor_id VARCHAR(32) NOT NULL,
    rssi SMALLINT NOT NULL CHECK (rssi BETWEEN -128 AND 0),
    snr NUMERIC(5, 2) NOT NULL CHECK (snr BETWEEN -35.0 AND 35.0),
    packet_loss_rate SMALLINT NOT NULL CHECK (packet_loss_rate BETWEEN 0 AND 100),
    battery_voltage_mv INTEGER NOT NULL CHECK (battery_voltage_mv BETWEEN 2000 AND 5500),
    battery_pct SMALLINT NOT NULL CHECK (battery_pct BETWEEN 0 AND 100),
    hop_count SMALLINT NOT NULL CHECK (hop_count BETWEEN 0 AND 15),
    is_gps BOOLEAN DEFAULT TRUE,
    is_emergency BOOLEAN DEFAULT FALSE,
    queue_len SMALLINT NOT NULL CHECK (queue_len BETWEEN 0 AND 255),
    distance_m INTEGER NOT NULL CHECK (distance_m >= 0),
    last_seen_sec INTEGER NOT NULL CHECK (last_seen_sec >= 0),
    uptime_min INTEGER NOT NULL CHECK (uptime_min >= 0),
    raw_hex VARCHAR(64),
    route_success BOOLEAN DEFAULT NULL, -- NULL until ACK returns; TRUE=Delivered, FALSE=Dropped
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 2. Performance Indices for Real-Time Topology Queries & ML Dataset Extraction
CREATE INDEX IF NOT EXISTS idx_fl_tlm_node_time 
    ON forestlink_telemetry(node_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_fl_tlm_neighbor_time 
    ON forestlink_telemetry(neighbor_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_fl_tlm_pair 
    ON forestlink_telemetry(node_id, neighbor_id);

CREATE INDEX IF NOT EXISTS idx_fl_tlm_labeled 
    ON forestlink_telemetry(route_success) WHERE route_success IS NOT NULL;

-- 3. View: Latest Link Telemetry (Snapshot of Current Mesh Topography)
CREATE OR REPLACE VIEW v_latest_mesh_links AS
SELECT DISTINCT ON (node_id, neighbor_id)
    id,
    node_id,
    neighbor_id,
    rssi,
    snr,
    packet_loss_rate,
    battery_voltage_mv,
    battery_pct,
    hop_count,
    is_gps,
    is_emergency,
    queue_len,
    distance_m,
    last_seen_sec,
    uptime_min,
    created_at AS last_report_at
FROM forestlink_telemetry
ORDER BY node_id, neighbor_id, created_at DESC;

-- 4. View: Feature Matrix for Offline Training Export
CREATE OR REPLACE VIEW v_route_training_features AS
SELECT
    node_id,
    neighbor_id,
    rssi,
    snr,
    (snr - (-7.5)) AS link_margin_db,
    packet_loss_rate,
    battery_pct,
    battery_voltage_mv,
    queue_len,
    (queue_len::float / 32.0) AS congestion_ratio,
    distance_m,
    hop_count,
    last_seen_sec,
    (1.0 / (1.0 + last_seen_sec::float)) AS freshness_factor,
    uptime_min,
    route_success
FROM forestlink_telemetry
WHERE route_success IS NOT NULL;

-- 5. Enable Row Level Security (RLS)
ALTER TABLE forestlink_telemetry ENABLE ROW LEVEL SECURITY;

-- Allow read access for authenticated apps and gateway
CREATE POLICY "Allow read access to all authenticated users"
    ON forestlink_telemetry FOR SELECT
    TO authenticated
    USING (true);

-- Allow insert access for service role and gateway
CREATE POLICY "Allow insert for service role and gateway"
    ON forestlink_telemetry FOR INSERT
    TO authenticated, service_role
    WITH CHECK (true);
