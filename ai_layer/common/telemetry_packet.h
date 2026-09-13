#ifndef FORESTLINK_TELEMETRY_PACKET_H
#define FORESTLINK_TELEMETRY_PACKET_H

#include <stdint.h>
#include <stdbool.h>
#include <string.h>

#ifdef __cplusplus
extern "C" {
#endif

#define FORESTLINK_TELEMETRY_PACKET_SIZE 18
#define FORESTLINK_TELEMETRY_PREFIX "TLM|"

/**
 * ForestLink Compact Telemetry Packet (18 Bytes Total)
 * 
 * Target: <20 bytes for maximum LoRa link budget and minimum airtime.
 * Designed for ESP32-S3 + SX1278 (433MHz) mesh nodes.
 *
 * Byte Layout:
 * [0..1]   node_id           : uint16_t  - Sender / reporting node ID (0-65535)
 * [2..3]   neighbor_id       : uint16_t  - Candidate neighbor ID (0-65535)
 * [4]      rssi              : int8_t    - Received Signal Strength (-128 to 0 dBm)
 * [5]      snr_x4            : int8_t    - SNR in dB * 4 (-32.0 to +31.75 dB, 0.25 dB step)
 * [6]      packet_loss_rate  : uint8_t   - Rolling packet loss rate (0 - 100%)
 * [7]      battery_mv_scaled : uint8_t   - Battery mV: (mV - 2500) / 10 (2500mV to 5050mV)
 * [8]      battery_pct       : uint8_t   - Battery State of Charge (0 - 100%)
 * [9]      hop_and_flags     : uint8_t   - Bits 0..3: hop count (0-15)
 *                                          Bit 4: distance method (0 = RSSI, 1 = GPS)
 *                                          Bit 5: emergency / SOS flag (1 = active)
 *                                          Bits 6..7: reserved
 * [10]     queue_len         : uint8_t   - Node transmission queue length (0 - 255)
 * [11..12] distance_m        : uint16_t  - Estimated neighbor distance (0 - 65535 meters)
 * [13..14] last_seen_sec     : uint16_t  - Seconds elapsed since last contact (0 - 65535 s)
 * [15..16] uptime_min        : uint16_t  - Node uptime in minutes (0 - 65535 min ~= 45 days)
 * [17]     checksum          : uint8_t   - CRC-8 checksum over bytes 0..16
 */
#pragma pack(push, 1)
typedef struct {
    uint16_t node_id;
    uint16_t neighbor_id;
    int8_t   rssi;
    int8_t   snr_x4;
    uint8_t  packet_loss_rate;
    uint8_t  battery_mv_scaled;
    uint8_t  battery_pct;
    uint8_t  hop_and_flags;
    uint8_t  queue_len;
    uint16_t distance_m;
    uint16_t last_seen_sec;
    uint16_t uptime_min;
    uint8_t  checksum;
} ForestLinkTelemetryPacket;
#pragma pack(pop)

// Compile-time check to guarantee 18 bytes
#if defined(__cplusplus)
static_assert(sizeof(ForestLinkTelemetryPacket) == FORESTLINK_TELEMETRY_PACKET_SIZE, 
              "ForestLinkTelemetryPacket must be exactly 18 bytes!");
#elif defined(__STDC_VERSION__) && __STDC_VERSION__ >= 201112L
_Static_assert(sizeof(ForestLinkTelemetryPacket) == FORESTLINK_TELEMETRY_PACKET_SIZE, 
               "ForestLinkTelemetryPacket must be exactly 18 bytes!");
#endif

// ================= Bitfield and Unit Conversion Helpers =================

static inline uint8_t fl_pack_battery_mv(uint16_t mv) {
    if (mv < 2500) return 0;
    if (mv > 5050) return 255;
    return (uint8_t)((mv - 2500) / 10);
}

static inline uint16_t fl_unpack_battery_mv(uint8_t scaled) {
    return (uint16_t)(2500 + ((uint16_t)scaled * 10));
}

static inline int8_t fl_pack_snr(float snr_db) {
    int val = (int)(snr_db * 4.0f + (snr_db >= 0 ? 0.5f : -0.5f));
    if (val < -128) return -128;
    if (val > 127)  return 127;
    return (int8_t)val;
}

static inline float fl_unpack_snr(int8_t snr_x4) {
    return (float)snr_x4 / 4.0f;
}

static inline uint8_t fl_pack_hop_and_flags(uint8_t hop_count, bool is_gps, bool is_emergency) {
    uint8_t val = (hop_count & 0x0F);
    if (is_gps) val |= (1 << 4);
    if (is_emergency) val |= (1 << 5);
    return val;
}

static inline uint8_t fl_get_hop_count(uint8_t hop_and_flags) {
    return hop_and_flags & 0x0F;
}

static inline bool fl_is_gps_distance(uint8_t hop_and_flags) {
    return (hop_and_flags & (1 << 4)) != 0;
}

static inline bool fl_is_emergency(uint8_t hop_and_flags) {
    return (hop_and_flags & (1 << 5)) != 0;
}

// ================= CRC-8 Checksum (Polynomial: 0x07, init: 0x00) =================

static inline uint8_t fl_compute_crc8(const uint8_t *data, size_t len) {
    uint8_t crc = 0x00;
    for (size_t i = 0; i < len; i++) {
        crc ^= data[i];
        for (uint8_t j = 0; j < 8; j++) {
            if (crc & 0x80) {
                crc = (uint8_t)((crc << 1) ^ 0x07);
            } else {
                crc <<= 1;
            }
        }
    }
    return crc;
}

static inline void fl_finalize_packet(ForestLinkTelemetryPacket *pkt) {
    pkt->checksum = fl_compute_crc8((const uint8_t *)pkt, FORESTLINK_TELEMETRY_PACKET_SIZE - 1);
}

static inline bool fl_verify_packet(const ForestLinkTelemetryPacket *pkt) {
    uint8_t expected = fl_compute_crc8((const uint8_t *)pkt, FORESTLINK_TELEMETRY_PACKET_SIZE - 1);
    return (pkt->checksum == expected);
}

// ================= Hex Serialization for ASCII-framed LoRa Links =================

static inline void fl_packet_to_hex(const ForestLinkTelemetryPacket *pkt, char *out_hex_37_chars) {
    static const char hex_digits[] = "0123456789ABCDEF";
    const uint8_t *bytes = (const uint8_t *)pkt;
    for (size_t i = 0; i < FORESTLINK_TELEMETRY_PACKET_SIZE; i++) {
        out_hex_37_chars[i * 2]     = hex_digits[(bytes[i] >> 4) & 0x0F];
        out_hex_37_chars[i * 2 + 1] = hex_digits[bytes[i] & 0x0F];
    }
    out_hex_37_chars[FORESTLINK_TELEMETRY_PACKET_SIZE * 2] = '\0';
}

static inline bool fl_hex_to_packet(const char *in_hex, ForestLinkTelemetryPacket *out_pkt) {
    if (strlen(in_hex) < (FORESTLINK_TELEMETRY_PACKET_SIZE * 2)) return false;
    uint8_t *bytes = (uint8_t *)out_pkt;
    for (size_t i = 0; i < FORESTLINK_TELEMETRY_PACKET_SIZE; i++) {
        char h1 = in_hex[i * 2];
        char h2 = in_hex[i * 2 + 1];
        uint8_t b1 = 0, b2 = 0;
        if (h1 >= '0' && h1 <= '9') b1 = h1 - '0';
        else if (h1 >= 'A' && h1 <= 'F') b1 = h1 - 'A' + 10;
        else if (h1 >= 'a' && h1 <= 'f') b1 = h1 - 'a' + 10;
        else return false;

        if (h2 >= '0' && h2 <= '9') b2 = h2 - '0';
        else if (h2 >= 'A' && h2 <= 'F') b2 = h2 - 'A' + 10;
        else if (h2 >= 'a' && h2 <= 'f') b2 = h2 - 'a' + 10;
        else return false;

        bytes[i] = (uint8_t)((b1 << 4) | b2);
    }
    return fl_verify_packet(out_pkt);
}

#ifdef __cplusplus
}
#endif

#endif // FORESTLINK_TELEMETRY_PACKET_H
