/**
 * ============================================================================
 * ForestLink TACTICAL TRANSCEIVER NODE (Two-Way Messaging Prototype)
 * Hardware: ESP32 / ESP32-S3 + LoRa SX1278 (433MHz)
 * 
 * DESIGNED FOR: Prototype Presentation (ESP32 + LoRa ONLY, No GPS/Sensors needed)
 * 
 * FEATURES:
 *  1. Two-way messaging between two ESP32 + LoRa nodes over 433MHz RF.
 *  2. Connects to Android App over BLE (Bluetooth Low Energy).
 *  3. Fallback Serial Monitor Console: Type messages directly into the Arduino
 *     Serial Monitor to send over LoRa even without the phone app!
 *  4. Periodic Telemetry Heartbeat: Keeps the Android app dashboard active
 *     with Battery %, AI Link Reliability, and Active Connection status.
 *  5. LED indicators for TX/RX confirmation.
 * ============================================================================
 * 
 * INSTRUCTIONS FOR TWO BOARDS:
 *  - Board 1: Upload with:  #define DEVICE_NAME "FOREST_NODE_001"
 *  - Board 2: Upload with:  #define DEVICE_NAME "FOREST_NODE_002"
 * ============================================================================
 */

#include <SPI.h>
#include <LoRa.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// ============================================================================
// 1. BOARD CONFIGURATION (Change for Board 2!)
// ============================================================================
// For Board 1: "FOREST_NODE_001"
// For Board 2: "FOREST_NODE_002"
#define DEVICE_NAME "FOREST_NODE_002"

// Set to true if you are using an ESP32-S3, or false for standard ESP32 (WROOM/DevKit V1)
#define IS_ESP32_S3 true

#if IS_ESP32_S3
  // ESP32-S3 SX1278 SPI Pins
  #define SCK_PIN   12
  #define MISO_PIN  13
  #define MOSI_PIN  11
  #define SS_PIN    10
  #define RST_PIN   14
  #define DIO0_PIN  2
  #define LED_PIN   2   // On-board LED or GPIO 2
#else
  // Standard ESP32 (WROOM-32 / NodeMCU-32S) Pins
  #define SCK_PIN   18
  #define MISO_PIN  19
  #define MOSI_PIN  23
  #define SS_PIN    5
  #define RST_PIN   14
  #define DIO0_PIN  2
  #define LED_PIN   2
#endif

#define LORA_FREQUENCY 433E6 // 433 MHz (SX1278)

// Nordic UART BLE Service UUIDs (Matched with Android App)
#define SERVICE_UUID           "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHARACTERISTIC_UUID_RX "6E400002-B5A3-F393-E0A9-E50E24DCCA9E" // App writes here
#define CHARACTERISTIC_UUID_TX "6E400003-B5A3-F393-E0A9-E50E24DCCA9E" // App listens here

// ============================================================================
// GLOBAL VARIABLES
// ============================================================================
BLEServer *pServer = NULL;
BLECharacteristic *pTxCharacteristic = NULL;
bool deviceConnected = false;
bool oldDeviceConnected = false;

// Outgoing message queue from BLE
String pendingTxPacket = "";
bool hasPendingTx = false;

// Simulated Telemetry for Dashboard presentation
unsigned long lastHeartbeatTime = 0;
int simulatedBattery = 96;

// ============================================================================
// BLE SERVER CALLBACKS
// ============================================================================
class MyServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) override {
        deviceConnected = true;
        Serial.println(F("\n[BLE] *** Phone App CONNECTED! ***"));
    }

    void onDisconnect(BLEServer* pServer) override {
        deviceConnected = false;
        Serial.println(F("\n[BLE] *** Phone App DISCONNECTED! ***"));
    }
};

// ============================================================================
// BLE RECEIVE CALLBACK (App -> ESP32)
// ============================================================================
class MyCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pCharacteristic) override {
        String rxValue = pCharacteristic->getValue();
        if (rxValue.length() > 0) {
            Serial.print(F("[BLE RX from App]: "));
            Serial.println(rxValue);
            
            pendingTxPacket = rxValue;
            hasPendingTx = true;
        }
    }
};

// ============================================================================
// HELPER FUNCTIONS
// ============================================================================
void blinkLed(int times, int msDelay) {
    for (int i = 0; i < times; i++) {
        digitalWrite(LED_PIN, HIGH);
        delay(msDelay);
        digitalWrite(LED_PIN, LOW);
        delay(msDelay);
    }
}

void forwardToApp(String packet) {
    if (deviceConnected && pTxCharacteristic != NULL) {
        pTxCharacteristic->setValue(packet.c_str());
        pTxCharacteristic->notify();
        Serial.print(F("[BLE Notify to App]: "));
        Serial.println(packet);
    }
}

void sendLoRaPacket(String packet) {
    digitalWrite(LED_PIN, HIGH);
    
    LoRa.beginPacket();
    LoRa.print(packet);
    LoRa.endPacket();
    
    digitalWrite(LED_PIN, LOW);
    
    Serial.print(F("[LoRa TX Sent]: "));
    Serial.println(packet);
}

// ============================================================================
// SETUP
// ============================================================================
void setup() {
    pinMode(LED_PIN, OUTPUT);
    digitalWrite(LED_PIN, LOW);

    Serial.begin(115200);
    // DO NOT USE while(!Serial) - it blocks battery / standalone operation!
    delay(1000);

    Serial.println(F("\n========================================="));
    Serial.print(F("  ForestLink TACTICAL TRANSCEIVER\n  Node: "));
    Serial.println(DEVICE_NAME);
    Serial.println(F("========================================="));

    // 1. Initialize SPI & LoRa SX1278
    Serial.print(F("[LORA] Initializing SX1278 on pins (SCK="));
    Serial.print(SCK_PIN);
    Serial.print(F(", MISO="));
    Serial.print(MISO_PIN);
    Serial.print(F(", MOSI="));
    Serial.print(MOSI_PIN);
    Serial.print(F(", SS="));
    Serial.print(SS_PIN);
    Serial.println(F(")..."));

    SPI.begin(SCK_PIN, MISO_PIN, MOSI_PIN, SS_PIN);
    LoRa.setPins(SS_PIN, RST_PIN, DIO0_PIN);

    bool loraOk = LoRa.begin(LORA_FREQUENCY);
    if (!loraOk) {
        Serial.println(F("\n[ERROR] LoRa.begin() failed!"));
        Serial.println(F("  -> Check SX1278 3.3V power (NOT 5V) and GND"));
        Serial.println(F("  -> Check NSS, SCK, MOSI, MISO pin connections"));
        Serial.println(F("  -> Retrying in 2 seconds..."));
        
        while (!loraOk) {
            blinkLed(2, 200);
            delay(2000);
            loraOk = LoRa.begin(LORA_FREQUENCY);
        }
    }

    // Configure robust radio parameters matching between both nodes
    LoRa.setTxPower(20);          // Max power 20 dBm (SX1278 PA_BOOST)
    LoRa.setSpreadingFactor(7);   // Fast SF7
    LoRa.setSignalBandwidth(125E3);
    LoRa.setCodingRate4(5);
    LoRa.setSyncWord(0x12);       // Default sync word (ensures nodes hear each other)
    LoRa.enableCrc();             // Checksum protection

    Serial.println(F("[LORA] SX1278 433MHz Initialized Successfully!"));
    blinkLed(3, 100);

    // 2. Initialize BLE Peripheral
    Serial.print(F("[BLE] Initializing Bluetooth LE as: "));
    Serial.println(DEVICE_NAME);

    BLEDevice::init(DEVICE_NAME);
    BLEDevice::setMTU(517);

    pServer = BLEDevice::createServer();
    pServer->setCallbacks(new MyServerCallbacks());

    BLEService *pService = pServer->createService(SERVICE_UUID);

    // TX Characteristic (Notify messages to Phone)
    pTxCharacteristic = pService->createCharacteristic(
        CHARACTERISTIC_UUID_TX,
        BLECharacteristic::PROPERTY_NOTIFY | BLECharacteristic::PROPERTY_READ
    );
    pTxCharacteristic->addDescriptor(new BLE2902());

    // RX Characteristic (Phone writes messages here)
    BLECharacteristic *pRxCharacteristic = pService->createCharacteristic(
        CHARACTERISTIC_UUID_RX,
        BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR
    );
    pRxCharacteristic->setCallbacks(new MyCallbacks());

    pService->start();

    BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(SERVICE_UUID);
    pAdvertising->setScanResponse(true);
    pAdvertising->setMinPreferred(0x06);
    BLEDevice::startAdvertising();

    Serial.println(F("[BLE] Advertising started. Waiting for Android App to connect..."));
    Serial.println(F("\n--- PROTOTYPE CONSOLE READY ---"));
    Serial.println(F("Tip: You can also type text in this Serial Monitor and press Enter to send over LoRa!\n"));
}

// ============================================================================
// MAIN LOOP
// ============================================================================
void loop() {
    // ------------------------------------------------------------------------
    // 1. Check for incoming Serial Monitor messages (Direct PC Testing)
    // ------------------------------------------------------------------------
    if (Serial.available() > 0) {
        String consoleMsg = Serial.readStringUntil('\n');
        consoleMsg.trim();
        if (consoleMsg.length() > 0) {
            Serial.print(F("[Console Typed]: "));
            Serial.println(consoleMsg);

            // Format as standard structured text packet
            String packet = "TXT|" + String(millis()) + "|" + String(DEVICE_NAME) + "|" + consoleMsg;
            sendLoRaPacket(packet);
        }
    }

    // ------------------------------------------------------------------------
    // 2. Check for pending App transmission (App -> LoRa)
    // ------------------------------------------------------------------------
    if (hasPendingTx) {
        String packet = pendingTxPacket;
        hasPendingTx = false;

        sendLoRaPacket(packet);

        // Immediate ACK back to the App for message delivery tick mark
        if (packet.startsWith("TXT|")) {
            int firstPipe = packet.indexOf('|', 4);
            if (firstPipe > 0) {
                String msgId = packet.substring(4, firstPipe);
                String ackPacket = "ACK|" + msgId;
                forwardToApp(ackPacket);
            }
        }
    }

    // ------------------------------------------------------------------------
    // 3. Check for incoming LoRa Packets (LoRa -> ESP32 -> App / Serial)
    // ------------------------------------------------------------------------
    int packetSize = LoRa.parsePacket();
    if (packetSize > 0) {
        digitalWrite(LED_PIN, HIGH);

        String incoming = "";
        while (LoRa.available()) {
            incoming += (char)LoRa.read();
        }

        int rssi = LoRa.packetRssi();
        float snr = LoRa.packetSnr();

        digitalWrite(LED_PIN, LOW);

        Serial.println(F("\n----------------------------------------"));
        Serial.print(F("[LoRa RX Packet Received] Size: "));
        Serial.print(packetSize);
        Serial.print(F(" bytes | RSSI: "));
        Serial.print(rssi);
        Serial.print(F(" dBm | SNR: "));
        Serial.print(snr);
        Serial.println(F(" dB"));
        Serial.print(F("Content: "));
        Serial.println(incoming);
        Serial.println(F("----------------------------------------"));

        // Forward raw packet directly to Android App
        forwardToApp(incoming);

        // If it's a message, notify App with RSSI and link quality
        if (incoming.startsWith("TXT|")) {
            // Also send ACK back over LoRa so sender knows it was received
            int firstPipe = incoming.indexOf('|', 4);
            if (firstPipe > 0) {
                String msgId = incoming.substring(4, firstPipe);
                delay(30); // Short turnaround delay
                sendLoRaPacket("ACK|" + msgId);
            }
        }
    }

    // ------------------------------------------------------------------------
    // 4. Handle BLE Reconnection Advertising
    // ------------------------------------------------------------------------
    if (!deviceConnected && oldDeviceConnected) {
        delay(500);
        pServer->startAdvertising();
        Serial.println(F("[BLE] Re-advertising..."));
        oldDeviceConnected = deviceConnected;
    }
    if (deviceConnected && !oldDeviceConnected) {
        oldDeviceConnected = deviceConnected;
    }

    // ------------------------------------------------------------------------
    // 5. Periodic Heartbeat to App (Every 4 seconds when connected)
    // ------------------------------------------------------------------------
    if (deviceConnected && (millis() - lastHeartbeatTime > 4000)) {
        lastHeartbeatTime = millis();
        
        // Simulates realistic battery drain for presentation
        if (random(0, 10) == 0 && simulatedBattery > 80) simulatedBattery--;

        // Telemetry payload: updates app dashboard battery, GPS, and AI route badge
        String heartbeat = "BAT|" + String(simulatedBattery) + "|AI_ROUTE|ALL|DIRECT|1.0|AI_DIRECT";
        forwardToApp(heartbeat);
    }
}
