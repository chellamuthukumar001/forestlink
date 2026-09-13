#include "emergency_classifier.h"
#include <string.h>

#define MAX_SOS_BURST_TRACKING 8
#define SOS_BURST_WINDOW_MS    30000 // 30 seconds burst window

typedef struct {
    uint16_t sender_node_id;
    uint32_t timestamp_ms;
    uint8_t  burst_count;
} SosBurstRecord;

static SosBurstRecord s_burst_records[MAX_SOS_BURST_TRACKING];

void emergency_classifier_init(void) {
    memset(s_burst_records, 0, sizeof(s_burst_records));
}

static uint8_t track_sos_burst(uint16_t sender_node_id, uint32_t current_millis) {
    int target_idx = -1;
    int oldest_idx = 0;
    uint32_t oldest_time = 0xFFFFFFFF;

    for (int i = 0; i < MAX_SOS_BURST_TRACKING; i++) {
        if (s_burst_records[i].sender_node_id == sender_node_id) {
            target_idx = i;
            break;
        }
        if (s_burst_records[i].sender_node_id == 0 && target_idx == -1) {
            target_idx = i;
        }
        if (s_burst_records[i].timestamp_ms < oldest_time) {
            oldest_time = s_burst_records[i].timestamp_ms;
            oldest_idx = i;
        }
    }

    if (target_idx == -1) {
        target_idx = oldest_idx;
    }

    if (s_burst_records[target_idx].sender_node_id == sender_node_id) {
        if ((current_millis - s_burst_records[target_idx].timestamp_ms) <= SOS_BURST_WINDOW_MS) {
            s_burst_records[target_idx].burst_count++;
        } else {
            s_burst_records[target_idx].burst_count = 1;
        }
    } else {
        s_burst_records[target_idx].sender_node_id = sender_node_id;
        s_burst_records[target_idx].burst_count = 1;
    }

    s_burst_records[target_idx].timestamp_ms = current_millis;
    return s_burst_records[target_idx].burst_count;
}

bool emergency_is_telemetry_distress(const ForestLinkTelemetryPacket *tlm) {
    if (!tlm) return false;

    // Rule 1: Explicit emergency bit set in telemetry packet
    if (fl_is_emergency(tlm->hop_and_flags)) {
        return true;
    }

    // Rule 2: Critical battery (<15% or <3350 mV) + high packet loss (>45%)
    uint16_t mv = fl_unpack_battery_mv(tlm->battery_mv_scaled);
    if ((tlm->battery_pct < 15 || mv < 3350) && (tlm->packet_loss_rate > 45)) {
        return true;
    }

    // Rule 3: Extreme RF link distress (RSSI < -120 dBm, SNR < -14 dB, packet loss > 75%)
    float snr = fl_unpack_snr(tlm->snr_x4);
    if (tlm->rssi < -120 && snr < -14.0f && tlm->packet_loss_rate > 75) {
        return true;
    }

    return false;
}

PriorityClassification emergency_classify(
    const char *payload_str,
    uint16_t sender_node_id,
    const ForestLinkTelemetryPacket *optional_tlm,
    uint32_t current_millis
) {
    PriorityClassification result;
    result.level = PRIORITY_NORMAL;
    result.is_preemptive = false;
    result.backoff_delay_ms = 800; // Default CSMA/CA backoff
    result.reason = "Standard message";

    if (!payload_str) return result;

    // 1. Direct SOS Message Prefix
    if (strncmp(payload_str, "SOS|", 4) == 0 || strstr(payload_str, "EMERGENCY") != NULL) {
        uint8_t bursts = track_sos_burst(sender_node_id, current_millis);
        result.level = PRIORITY_EMERGENCY_SOS;
        result.is_preemptive = true;
        result.backoff_delay_ms = (bursts > 2) ? 0 : 20; // Zero backoff for repeated bursts
        result.reason = (bursts > 1) ? "Repeated SOS burst detected (preemptive, zero-backoff)" 
                                     : "Direct SOS message (preemptive)";
        return result;
    }

    // 2. Telemetry Distress Anomaly
    if (optional_tlm && emergency_is_telemetry_distress(optional_tlm)) {
        result.level = PRIORITY_EMERGENCY_SOS;
        result.is_preemptive = true;
        result.backoff_delay_ms = 30;
        result.reason = "Telemetry distress anomaly detected (critical battery + loss)";
        return result;
    }

    // 3. Elevated Priority for low-battery warnings or ACK packets
    if (strncmp(payload_str, "ACK|", 4) == 0) {
        result.level = PRIORITY_ELEVATED;
        result.is_preemptive = false;
        result.backoff_delay_ms = 150;
        result.reason = "ACK packet priority";
        return result;
    }

    if (optional_tlm && (optional_tlm->battery_pct < 25 || optional_tlm->packet_loss_rate > 30)) {
        result.level = PRIORITY_ELEVATED;
        result.is_preemptive = false;
        result.backoff_delay_ms = 250;
        result.reason = "Degraded node link state";
        return result;
    }

    return result;
}
