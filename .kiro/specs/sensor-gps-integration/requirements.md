# Requirements: WisBlock SHTC3 + u-blox NEO-6M GPS Integration

## Introduction

The ForestLink off-grid messaging network currently uses simulated/hardcoded GPS coordinates and placeholder sensor values (0°C / 0% humidity). This feature replaces those stubs with real hardware: the WisBlock RAK1901 (SHTC3) environmental sensor for temperature and humidity, and the u-blox NEO-6M for live GPS coordinates. Both Android and ESP32 layers must be updated to produce, transmit, and display the real data.

## Requirements

### REQ-1: SHTC3 Sensor Initialization

**User Story:** As a field operator, I want the device to automatically read real temperature and humidity from the attached SHTC3 sensor so that environmental data in my messages reflects actual conditions.

#### Acceptance Criteria

1. GIVEN the ESP32 boots with an SHTC3 connected over I2C, WHEN `setup()` completes, THEN the sensor is initialized and `shtc3Ready` is `true`.
2. GIVEN the SHTC3 is not connected or fails to initialize, WHEN `getSensorData()` is called, THEN it returns realistic simulated values (not zeros) so the app still receives plausible readings.
3. GIVEN the sensor is ready, WHEN `getSensorData()` is called, THEN temperature is returned in degrees Celsius and humidity as a percentage (0–100).

---

### REQ-2: NEO-6M GPS Initialization and Live Fix

**User Story:** As a field operator, I want the device to track my real GPS coordinates so that messages and map displays show my actual location.

#### Acceptance Criteria

1. GIVEN the NEO-6M is wired to ESP32 Serial1 (RX=16, TX=17), WHEN `setup()` completes, THEN `Serial1` is initialized at 9600 baud.
2. GIVEN the GPS module is producing NMEA sentences, WHEN the main `loop()` runs, THEN GPS bytes are fed to TinyGPS++ each iteration.
3. GIVEN TinyGPS++ reports a valid location fix, WHEN `gps.location.isValid()` is true, THEN `currentLat` and `currentLon` are updated to the live coordinates.
4. GIVEN no GPS fix has been acquired yet, THEN `currentLat` and `currentLon` remain at their default values (0.0) and the app treats that as "no fix."

---

### REQ-3: Periodic Telemetry to Android App

**User Story:** As a field operator, I want the dashboard to show my live GPS position and battery level, updating automatically without needing to send a message.

#### Acceptance Criteria

1. GIVEN the app is BLE-connected, WHEN 5 seconds have elapsed since the last telemetry send, THEN the ESP32 sends a BLE notification in the format `GPS|<lat>,<lng>|BAT|<%>`.
2. GIVEN the telemetry notification is received, WHEN `BleManager.handleIncomingData()` processes it, THEN `_hardwareData` emits the raw string.
3. GIVEN `_hardwareData` emits the telemetry string, WHEN `MsgRepository.parseHardwareData()` processes it, THEN `HardwareState.gpsLat`, `gpsLng`, and `batteryLevel` are updated.
4. GIVEN coordinates are non-zero, WHEN the Dashboard UI observes `HardwareState`, THEN the GPS status indicator shows "LOCKED" and displays the coordinates.

---

### REQ-4: Sensor Data Appended to Outbound LoRa Messages

**User Story:** As a remote receiver, I want messages I receive to include the sender's GPS position and environmental conditions so I have situational awareness.

#### Acceptance Criteria

1. GIVEN the ESP32 handles an outbound text message, WHEN `handleTextMessage()` is called, THEN `getSensorData()` is called to fetch current temperature and humidity.
2. GIVEN sensor data is available, WHEN the LoRa payload is assembled, THEN the plaintext message is appended with ` [<lat>,<lon>|<temp>,<hum>]` before encryption.
3. GIVEN the message is `Hello world` and coordinates are `12.9716, 77.5946` with temp `26.3` and humidity `64.0`, THEN the plaintext before encryption is `Hello world [12.9716,77.5946|26.3,64.0]`.

---

### REQ-5: ACK Contains Sensor Readings

**User Story:** As a message sender, I want to see the temperature and humidity recorded at the time my message was sent, displayed on the message bubble after delivery confirmation.

#### Acceptance Criteria

1. GIVEN the ESP32 has sent a message over LoRa, WHEN the auto-ACK is sent back to the phone, THEN the format is `ACK|<msgId>|<temp>|<hum>`.
2. GIVEN the ACK is received by `BleManager`, WHEN `handleIncomingData()` processes it, THEN `pendingMessages` removes the `msgId` key, and `_messageAck` emits the full string `<msgId>|<temp>|<hum>`.
3. GIVEN `MsgRepository`'s ACK collector receives the payload, WHEN it is processed, THEN `updateMessageStatusAndSensors(id, DELIVERED, temp, hum)` is called on the DAO.
4. GIVEN the DAO update runs, WHEN the message is re-observed from the database, THEN the message has `status = DELIVERED`, `temperature` set, and `humidity` set.

---

### REQ-6: Outbound Messages Start with Null Sensor Values

**User Story:** As a user, I don't want to see "0°C / 0%" on a message bubble before the ACK has arrived, as that is misleading.

#### Acceptance Criteria

1. GIVEN the user sends a message, WHEN `MsgRepository.sendMessage()` inserts the message into the database, THEN `temperature` and `humidity` are `null` (not `0f`).
2. GIVEN `temperature` is `null`, WHEN the chat UI renders the message bubble, THEN the sensor panel is hidden.

---

### REQ-7: Inbound LoRa Messages Parse Sensor Data from Bracket Suffix

**User Story:** As a message receiver, I want to see the sender's temperature, humidity, and location on their received message bubble.

#### Acceptance Criteria

1. GIVEN a received LoRa message decrypts to `<msgId>|<sender>|<text> [lat,lon|temp,hum]`, WHEN `handleRawMessage()` processes it, THEN the bracket suffix is extracted.
2. GIVEN the bracket suffix is `[12.9716,77.5946|26.3,64.0]`, WHEN parsed, THEN `temperature = 26.3f`, `humidity = 64.0f`, and coordinates are retained in the `content` field as `[12.9716,77.5946]`.
3. GIVEN the bracket suffix contains only coordinates (old format `[lat, lon]` without sensor pipe), WHEN parsed, THEN `temperature` and `humidity` are `null` and no crash occurs.
4. GIVEN the message is saved to the database, THEN the received `Message` entity has `isIncoming = true`, `status = DELIVERED`, and populated sensor fields.

---

### REQ-8: Database and DAO Compatibility

**User Story:** As a developer, I want the Room database to correctly store and retrieve nullable sensor fields so the app compiles and runs without schema errors.

#### Acceptance Criteria

1. GIVEN the existing `Message` entity has `temperature: Float?` and `humidity: Float?`, THEN no schema migration is required for this feature.
2. GIVEN `MessageDao.updateMessageStatusAndSensors(id, status, temp, hum)` exists, WHEN called with `null` values for temp and hum, THEN the database column is set to NULL (not 0).
3. GIVEN the Android app is built, THEN there are no Room annotation processor errors or Kotlin compilation errors.
