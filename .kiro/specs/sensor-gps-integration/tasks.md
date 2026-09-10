# Tasks: WisBlock SHTC3 + u-blox NEO-6M GPS Integration

## Task List

- [~] 1. Update `esp32_forestlink_gateway.ino` with SHTC3 and NEO-6M integration
  - Add `#include <Wire.h>`, `#include "SparkFun_SHTC3.h"`, `#include <TinyGPS++.h>`
  - Define GPS pin constants (`GPS_RX_PIN 16`, `GPS_TX_PIN 17`, `GPS_BAUD 9600`)
  - Declare global `SHTC3 shtc3`, `TinyGPSPlus gps`, `bool shtc3Ready`, telemetry timer
  - Replace hardcoded `currentLat/currentLon` with `0.0` defaults
  - In `setup()`: call `Wire.begin()`, initialize SHTC3, start `Serial1` for GPS
  - Add `getSensorData(float &temp, float &humidity)` with real sensor read + simulated fallback
  - In `loop()`: feed `Serial1` bytes to `gps.encode()`, latch valid fix into `currentLat/currentLon`
  - In `loop()`: call `sendTelemetry()` every 5 seconds
  - Add `sendTelemetry()` function sending `GPS|<lat>,<lng>|BAT|<%>` via BLE notify
  - Update `handleTextMessage()` to call `getSensorData()` and append `[lat,lon|temp,hum]` to payload
  - Update ACK string in `handleTextMessage()` to format `ACK|<msgId>|<temp>|<hum>`
  - _Requirement: REQ-1, REQ-2, REQ-3, REQ-4, REQ-5_

- [~] 2. Update `esp32_forestlink_transceiver.ino` with identical sensor integration
  - Apply all the same changes from Task 1 to the transceiver firmware
  - Add the same includes, pin definitions, globals, `getSensorData()`, `sendTelemetry()`
  - Update `loop()` with GPS byte feeding, fix latching, and telemetry timer
  - Update `handleTextMessage()` with sensor suffix and ACK with temp/hum
  - _Requirement: REQ-1, REQ-2, REQ-3, REQ-4, REQ-5_

- [~] 3. Update `BleManager.kt` — extend ACK parsing to emit full sensor payload
  - In the `CMD_ACK` branch of `handleIncomingData()`, change from emitting only `msgId` to emitting the full payload string `<msgId>|<temp>|<hum>`
  - Extract `msgId = payload.substringBefore("|")` for `pendingMessages.remove()`
  - Emit full `payload` (not just `msgId`) to `_messageAck`
  - _Requirement: REQ-5_

- [~] 4. Update `MsgRepository.kt` — null sensor defaults on send
  - In `sendMessage()`, remove `val currentTemp = 0f` and `val currentHumidity = 0f`
  - Set `temperature = null` and `humidity = null` when constructing the outbound `Message`
  - _Requirement: REQ-6_

- [~] 5. Update `MsgRepository.kt` — ACK collector splits full payload
  - Replace the existing ACK collector `idStr.toLong()` logic
  - Split payload on `|` → `msgId` (index 0), `temp` (index 1, nullable float), `hum` (index 2, nullable float)
  - Call `msgDao.updateMessageStatusAndSensors(id, MessageStatus.DELIVERED, temp, hum)`
  - _Requirement: REQ-5_

- [~] 6. Update `MsgRepository.kt` — parse sensor data from inbound LoRa message bracket suffix
  - In `handleRawMessage()`, extend the existing bracket extraction block
  - Split bracket contents on `|` to separate coordinate part from sensor part
  - Parse `temp` and `hum` from sensor part (gracefully handle old `[lat, lon]` format with no pipe)
  - Save `Message` with `temperature` and `humidity` populated from parsed values
  - Keep coordinates appended to `content` as `[lat,lon]` string for UI display
  - _Requirement: REQ-7_

- [~] 7. Verify Android build compiles without errors
  - Build the Android app (`./gradlew assembleDebug`)
  - Confirm zero Room annotation processor errors
  - Confirm zero Kotlin compilation errors
  - _Requirement: REQ-8_
