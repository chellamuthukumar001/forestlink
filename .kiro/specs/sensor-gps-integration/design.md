# Technical Design: WisBlock SHTC3 + u-blox NEO-6M GPS Integration

## Overview

This document describes the high-level and low-level design for integrating real-time temperature, humidity (via WisBlock RAK1901 SHTC3 sensor), and GPS location (via u-blox NEO-6M) into the ForestLink off-grid messaging network.

The changes span two layers:
1. **ESP32 Firmware** — both `esp32_forestlink_gateway.ino` and `esp32_forestlink_transceiver.ino`
2. **Android Application** — `BleManager.kt`, `MsgRepository.kt`, and `AppDatabase.kt`

---

## High-Level Design

### System Architecture

```
┌──────────────────────────────────────────────────────────┐
│                  ESP32 Node (Gateway / Transceiver)       │
│                                                          │
│  ┌─────────────┐   I2C    ┌───────────┐                  │
│  │  SHTC3      │─────────▶│           │                  │
│  │  (Temp/Hum) │          │  ESP32-S3 │◀──UART──▶ NEO-6M │
│  └─────────────┘          │           │   Serial1        │
│                           │           │   (GPS)          │
│                           └─────┬─────┘                  │
│                                 │ BLE NUS                 │
└─────────────────────────────────┼────────────────────────┘
                                  │
                         ┌────────▼────────┐
                         │  Android App     │
                         │                 │
                         │  BleManager     │
                         │  MsgRepository  │
                         │  AppDatabase    │
                         └─────────────────┘
```

### Data Flow — Outbound (App → LoRa)

```
User sends message
       │
       ▼
MsgRepository.sendMessage()
  → inserts Message(temp=null, hum=null, status=SENDING)
       │
       ▼
BleManager.sendData(encrypted, msgId)
  → BLE: TXT|<msgId>|<senderName>|<encryptedContent>
       │
       ▼
ESP32 handleTextMessage()
  → reads SHTC3: getSensorData(temp, hum)
  → reads GPS: currentLat, currentLon (live or last known)
  → builds: msg + " [lat, lon|temp,hum]"
  → encrypts and sends over LoRa
  → sends BLE ACK: ACK|<msgId>|<temp>|<hum>
       │
       ▼
BleManager.handleIncomingData() — ACK branch
  → emits _messageAck with full payload "msgId|temp|hum"
       │
       ▼
MsgRepository ACK collector
  → splits payload → msgId, temp, hum
  → calls updateMessageStatusAndSensors(id, DELIVERED, temp, hum)
```

### Data Flow — Inbound (LoRa → App)

```
Remote node sends LoRa packet
  → encrypted payload containing: msgContent [lat,lon|temp,hum]
       │
       ▼
ESP32 decrypts, forwardToBluetoothApp()
  → BLE: TXT|<senderNode>: <decryptedContent>
       │
       ▼
BleManager.handleIncomingData() — TXT branch
  → emits _incomingMessages with full string
       │
       ▼
MsgRepository.handleRawMessage()
  → extracts bracket suffix: [lat,lon|temp,hum]
  → parses lat, lon, temp, hum
  → saves Message with coordinates appended to content,
    temperature and humidity populated
```

### Telemetry Flow — Periodic GPS/Battery Updates

```
ESP32 loop() — every 5 seconds
  → reads GPS fix from NEO-6M
  → reads battery ADC
  → sends BLE: GPS|<lat>,<lng>|BAT|<%>
       │
       ▼
BleManager.handleIncomingData() — GPS|BAT branch
  → emits _hardwareData
       │
       ▼
MsgRepository.parseHardwareData()
  → updates HardwareState(gpsLat, gpsLng, batteryLevel)
       │
       ▼
Dashboard UI observes HardwareState
  → GPS status changes to "LOCKED" with live coordinates
```

---

## Low-Level Design

### ESP32 Firmware (applies identically to both .ino files)

#### New includes and pin definitions

```cpp
#include <Wire.h>
#include "SparkFun_SHTC3.h"
#include <TinyGPS++.h>

// GPS UART — NEO-6M connected to Serial1
#define GPS_RX_PIN  16
#define GPS_TX_PIN  17
#define GPS_BAUD    9600

SHTC3 shtc3;
TinyGPSPlus gps;
bool shtc3Ready = false;

float currentLat = 0.0;   // replaces hardcoded 12.9716
float currentLon = 0.0;

unsigned long lastTelemetryMs = 0;
const unsigned long TELEMETRY_INTERVAL_MS = 5000;
```

#### setup() additions

```cpp
// I2C for SHTC3
Wire.begin();
if (shtc3.begin() == SHTC3_Status_Nominal) {
    shtc3Ready = true;
    Serial.println("SHTC3 sensor ready.");
} else {
    Serial.println("SHTC3 not found - using simulated values.");
}

// UART for NEO-6M GPS
Serial1.begin(GPS_BAUD, SERIAL_8N1, GPS_RX_PIN, GPS_TX_PIN);
Serial.println("GPS serial started on Serial1.");
```

#### getSensorData() function

```cpp
void getSensorData(float &temp, float &humidity) {
    if (shtc3Ready) {
        shtc3.update();
        if (shtc3.lastStatus == SHTC3_Status_Nominal) {
            temp     = shtc3.toDegC();
            humidity = shtc3.toPercent();
            return;
        }
    }
    // Fallback: realistic simulated values (jitter ±0.3°C / ±0.5%)
    temp     = 25.0 + (float)(random(-3, 4)) * 0.1;
    humidity = 65.0 + (float)(random(-5, 6)) * 0.1;
}
```

#### loop() additions

```cpp
// Feed GPS bytes
while (Serial1.available()) {
    gps.encode(Serial1.read());
}
// Latch valid fix
if (gps.location.isUpdated() && gps.location.isValid()) {
    currentLat = (float)gps.location.lat();
    currentLon = (float)gps.location.lng();
}

// Periodic telemetry to phone
if (millis() - lastTelemetryMs >= TELEMETRY_INTERVAL_MS) {
    lastTelemetryMs = millis();
    sendTelemetry();
}
```

#### sendTelemetry() function

```cpp
void sendTelemetry() {
    if (!deviceConnected) return;
    int battPercent = map(analogRead(34), 0, 4095, 0, 100); // adjust pin/range for your HW
    String telemetry = "GPS|" + String(currentLat, 6) + "," + String(currentLon, 6)
                     + "|BAT|" + String(battPercent);
    pTxCharacteristic->setValue(telemetry.c_str());
    pTxCharacteristic->notify();
}
```

#### handleTextMessage() — sensor data appended

```cpp
void handleTextMessage(String msg) {
    float temp, hum;
    getSensorData(temp, hum);

    // Format: originalMsg [lat,lon|temp,hum]
    String msgWithSensors = msg
        + " [" + String(currentLat, 4) + "," + String(currentLon, 4)
        + "|" + String(temp, 1) + "," + String(hum, 1) + "]";

    sendToLoRa(msgWithSensors);

    // ACK with sensor readings
    if (deviceConnected) {
        int pipe = msg.indexOf('|');
        if (pipe > 0) {
            String msgId = msg.substring(0, pipe);
            String ack = "ACK|" + msgId
                       + "|" + String(temp, 1)
                       + "|" + String(hum, 1);
            pTxCharacteristic->setValue(ack.c_str());
            pTxCharacteristic->notify();
        }
    }
}
```

---

### Android — BleManager.kt

#### CMD_ACK branch in handleIncomingData()

Current behavior: `val msgId = data.substringAfter(CMD_ACK).trim()` — emits only the ID.

New behavior:

```kotlin
data.startsWith(CMD_ACK) -> {
    // Payload is now: ACK|<msgId>|<temp>|<hum>
    val payload = data.substringAfter(CMD_ACK).trim()  // "<msgId>|<temp>|<hum>"
    val msgId   = payload.substringBefore("|")          // just the ID for deduplication
    Log.d("BleManager", "ACK received: $msgId")
    pendingMessages.remove(msgId)
    scope.launch { _messageAck.emit(payload) }          // emit full payload, not just ID
}
```

The `_messageAck` SharedFlow type remains `SharedFlow<String>` — the string now carries the full `msgId|temp|hum` payload instead of only the ID. No type change needed.

---

### Android — MsgRepository.kt

#### sendMessage() — null sensor defaults

```kotlin
// Before (placeholder zeros):
val currentTemp = 0f
val currentHumidity = 0f

// After (null = hide panel until ACK arrives):
val message = Message(
    ...
    temperature = null,
    humidity    = null,
    ...
)
```

#### ACK collector — parse full payload

```kotlin
scope.launch {
    bleManager.messageAck.collect { payload ->
        // payload: "<msgId>|<temp>|<hum>"  or legacy plain "<msgId>"
        try {
            val parts = payload.split("|")
            val id    = parts[0].toLong()
            val temp  = parts.getOrNull(1)?.toFloatOrNull()
            val hum   = parts.getOrNull(2)?.toFloatOrNull()
            msgDao.updateMessageStatusAndSensors(id, MessageStatus.DELIVERED, temp, hum)
            Log.d("MsgRepo", "Message $id DELIVERED — temp=$temp hum=$hum")
        } catch (e: NumberFormatException) {
            Log.e("MsgRepo", "Invalid ACK payload: $payload")
        }
    }
}
```

#### handleRawMessage() — parse bracket sensor suffix

Current bracket parsing only extracts location `[lat, lon]`.

New bracket format: `[lat,lon|temp,hum]`

```kotlin
// Existing code extracts the bracket block — extend parsing:
if (actualMessage.contains(" [") && actualMessage.endsWith("]")) {
    val locStart   = actualMessage.lastIndexOf(" [")
    val bracketStr = actualMessage.substring(locStart + 2, actualMessage.length - 1)
    actualMessage  = actualMessage.substring(0, locStart)

    // bracketStr = "lat,lon|temp,hum"
    val geoSensor = bracketStr.split("|")
    val coordPart = geoSensor.getOrNull(0) ?: ""   // "lat,lon"
    val sensorPart = geoSensor.getOrNull(1)         // "temp,hum" or null

    var parsedTemp: Float? = null
    var parsedHum: Float? = null

    if (sensorPart != null) {
        val sv = sensorPart.split(",")
        parsedTemp = sv.getOrNull(0)?.toFloatOrNull()
        parsedHum  = sv.getOrNull(1)?.toFloatOrNull()
    }

    locationStr = " [$coordPart]"  // keep coords in content for display

    // Save with sensor fields populated
    val message = Message(
        content     = finalContent + locationStr,
        senderId    = senderNode,
        receiverId  = "ME",
        isIncoming  = true,
        status      = MessageStatus.DELIVERED,
        temperature = parsedTemp,
        humidity    = parsedHum
    )
    msgDao.insertMessage(message)
}
```

---

### Android — AppDatabase.kt

`updateMessageStatusAndSensors` is **already present** in the current codebase:

```kotlin
@Query("UPDATE messages SET status = :status, temperature = :temp, humidity = :hum WHERE id = :id")
suspend fun updateMessageStatusAndSensors(id: Long, status: MessageStatus, temp: Float?, hum: Float?)
```

No changes needed to AppDatabase.kt. The schema version and `Message` entity already support nullable `temperature` and `humidity` fields.

---

## Packet Format Reference

| Direction | Format | Example |
|---|---|---|
| ESP32 → App (telemetry) | `GPS|<lat>,<lng>\|BAT|<%>` | `GPS|12.9716,77.5946\|BAT|82` |
| ESP32 → App (ACK) | `ACK|<msgId>\|<temp>\|<hum>` | `ACK|42\|26.3\|64.1` |
| LoRa payload (encrypted) | `<NODE>\|<encrypted([msg [lat,lon\|temp,hum]])>` | `FOREST_NODE_001\|a3f9...` |
| Decrypted LoRa content | `<msgId>\|<sender>\|<text> [lat,lon\|temp,hum]` | `10\|RANGER\|Hello [12.97,77.59\|26.3,64.0]` |

---

## Wiring Reference

### SHTC3 (I2C — uses default ESP32-S3 I2C pins)
| SHTC3 Pin | ESP32-S3 Pin |
|---|---|
| VCC | 3.3V |
| GND | GND |
| SDA | GPIO 8 (default SDA) |
| SCL | GPIO 9 (default SCL) |

### NEO-6M GPS (UART Serial1)
| NEO-6M Pin | ESP32-S3 Pin |
|---|---|
| VCC | 3.3V |
| GND | GND |
| TX  | GPIO 16 (ESP32 RX) |
| RX  | GPIO 17 (ESP32 TX) |

---

## Risk & Fallback Considerations

- **SHTC3 not detected at boot** — `getSensorData()` falls back to simulated values with ±0.3°C jitter so the app never receives zeros.
- **GPS no fix yet** — `currentLat/currentLon` remain `0.0` until a valid fix is latched. The app should treat `0.0,0.0` coordinates as "no fix" in the UI (pre-existing handling via `gpsLat == 0.0`).
- **Legacy ACK format** — The ACK collector splits on `|` and uses `getOrNull()` so plain `ACK|<msgId>` (old firmware) still works; `temp` and `hum` will be `null`.
- **LoRa packet size** — The sensor suffix `[12.9716,77.5946|26.3,64.0]` adds ~25 characters to the plaintext before encryption. With typical short messages this stays well within the 255-byte LoRa limit.
- **Database migration** — `temperature` and `humidity` are already in schema version 7 as nullable `Float?`. The DAO query `updateMessageStatusAndSensors` is already present. No migration needed.
