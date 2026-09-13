/**
 * ForestLink AI-Assisted Telemetry & Next-Hop Routing Add-on
 * Demonstrates how to integrate the AI Layer into esp32_forestlink_transceiver.ino
 *
 * Hardware: ESP32-S3 + LoRa SX1278 (433MHz)
 * Features added:
 * - 18-byte compact bit-packed telemetry emission (<20 bytes target)
 * - Transpiled C++ Decision Tree inference on SX1278 packet arrival (<10 us, 0 dynamic RAM)
 * - AI Next-Hop candidate evaluation vs. deterministic fallback
 * - Emergency SOS queue preemption
 */

#include <SPI.h>
#include <LoRa.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include "../ai_layer/common/telemetry_packet.h"
#include "../ai_layer/section2_route_reliability/embedded/route_scorer_model.h"
#include "../ai_layer/section2_route_reliability/embedded/mesh_router.h"

// Node Configuration
#define NODE_NUM_ID           1
#define NODE_CALLSIGN         "FOREST_NODE_001"
#define TELEMETRY_INTERVAL_MS 15000 // Send beacon every 15 seconds

// State variables
static unsigned long lastTelemetryBroadcast = 0;
static uint8_t simulatedQueueLength = 0;
static float nodeBatteryPct = 92.0;

// LoRa Pin definitions (ESP32-S3)
#define LORA_SCK     12
#define LORA_MISO    13
#define LORA_MOSI    11
#define LORA_SS      10
#define LORA_RST     14
#define LORA_DIO0    2

/**
 * Builds and broadcasts the 18-byte bit-packed telemetry record over LoRa.
 */
void broadcastTelemetryBeacon() {
    ForestLinkTelemetryPacket pkt;
    memset(&pkt, 0, sizeof(ForestLinkTelemetryPacket));

    pkt.node_id = NODE_NUM_ID;
    pkt.neighbor_id = 0xFFFF; // Broadcast beacon
    pkt.rssi = (int8_t)LoRa.packetRssi(); // Last heard RSSI
    pkt.snr_x4 = fl_pack_snr(LoRa.packetSnr());
    pkt.packet_loss_rate = 3; // Rolling loss %
    pkt.battery_mv_scaled = fl_pack_battery_mv(3950); // ~3.95V
    pkt.battery_pct = (uint8_t)nodeBatteryPct;
    pkt.hop_and_flags = fl_pack_hop_and_flags(1, true, false); // 1 hop, GPS fix=true, SOS=false
    pkt.queue_len = simulatedQueueLength;
    pkt.distance_m = 420; // Estimated distance to center
    pkt.last_seen_sec = 10;
    pkt.uptime_min = (uint16_t)(millis() / 60000);

    // Compute CRC-8
    fl_finalize_packet(&pkt);

    // Convert to 36-char HEX string for LoRa ASCII frame: "TLM|<HEX>"
    char hexBuffer[37];
    fl_packet_to_hex(&pkt, hexBuffer);

    LoRa.beginPacket();
    LoRa.print(String(NODE_CALLSIGN) + "|TLM|");
    LoRa.print(hexBuffer);
    LoRa.endPacket();

    Serial.print(">> [AI Telemetry Beacon Sent] 18 bytes packed: ");
    Serial.println(hexBuffer);
}

/**
 * Ingests incoming LoRa packets, parsing telemetry records and running
 * on-device C++ inference to update the neighbor table.
 */
void handleIncomingLoRaPacket(const String &incoming) {
    int firstPipe = incoming.indexOf('|');
    if (firstPipe <= 0) return;

    String senderStr = incoming.substring(0, firstPipe);
    String payload = incoming.substring(firstPipe + 1);

    // Check for AI Telemetry Packet
    if (payload.startsWith("TLM|")) {
        String hexStr = payload.substring(4);
        ForestLinkTelemetryPacket pkt;
        if (fl_hex_to_packet(hexStr.c_str(), &pkt)) {
            // Update Mesh Router (Runs transpiled C++ decision trees in <10 microseconds!)
            mesh_router_update_neighbor(&pkt, millis());

            float reliability = route_scorer_predict_packet(&pkt);
            Serial.printf(">> [AI Telemetry RX] From Node %u: RSSI=%d dBm, SNR=%.2f dB, Reliability=%.1f%%\n",
                          pkt.node_id, pkt.rssi, fl_unpack_snr(pkt.snr_x4), reliability * 100.0f);
        } else {
            Serial.println(">> [AI Telemetry RX] Checksum verification failed!");
        }
    }
}

/**
 * Routes an outgoing message using AI Next-Hop Scoring with fail-safe fallback.
 */
void routeOutgoingMessage(uint16_t targetDestNode, const String &msgContent, bool isEmergency) {
    uint32_t now = millis();
    NextHopResult routing = mesh_router_select_next_hop(targetDestNode, isEmergency, now);

    Serial.println("\n--- [ForestLink Routing Decision] ---");
    Serial.printf("Target: Node %u | Priority: %s\n", targetDestNode, isEmergency ? "EMERGENCY (SOS)" : "NORMAL");
    Serial.printf("Decision: %s | Next-Hop: %u | Score: %.2f\n",
                  (routing.decision_type == ROUTING_DECISION_AI_DIRECT) ? "AI_DIRECT" :
                  (routing.decision_type == ROUTING_EMERGENCY_PRIORITY) ? "EMERGENCY_PRIORITY" : "DETERMINISTIC_FALLBACK",
                  routing.next_hop_node_id, routing.reliability_score);
    Serial.printf("Reason: %s\n", routing.reason);

    // Transmit over LoRa
    LoRa.beginPacket();
    if (routing.decision_type == ROUTING_FALLBACK_DETERMINISTIC) {
        // Fallback: Flood broadcast to ensure reachability
        LoRa.print(String(NODE_CALLSIGN) + "|FLOOD|" + msgContent);
    } else {
        // Unicast towards optimal next-hop
        LoRa.print(String(NODE_CALLSIGN) + "|NEXT:" + String(routing.next_hop_node_id) + "|" + msgContent);
    }
    LoRa.endPacket();
    Serial.println(">> Packet transmitted over LoRa air interface.\n");
}

void setup() {
    Serial.begin(115200);
    while (!Serial);

    Serial.println("ForestLink Node Initializing with AI Telemetry Layer...");

    // 1. Initialize Mesh Router
    mesh_router_init(NODE_NUM_ID);

    // 2. Initialize LoRa Radio
    SPI.begin(LORA_SCK, LORA_MISO, LORA_MOSI, LORA_SS);
    LoRa.setPins(LORA_SS, LORA_RST, LORA_DIO0);
    if (!LoRa.begin(433E6)) {
        Serial.println("LoRa initialization failed!");
    } else {
        Serial.println("LoRa SX1278 (433MHz) Active!");
    }
}

void loop() {
    // 1. Periodic Telemetry Beacon
    if (millis() - lastTelemetryBroadcast >= TELEMETRY_INTERVAL_MS) {
        broadcastTelemetryBeacon();
        lastTelemetryBroadcast = millis();
    }

    // 2. Process incoming LoRa packets
    int packetSize = LoRa.parsePacket();
    if (packetSize) {
        String incoming = "";
        while (LoRa.available()) {
            incoming += (char)LoRa.read();
        }
        handleIncomingLoRaPacket(incoming);
    }
}
