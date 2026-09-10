/**
 * ForestLink RECEIVER NODE (Endpoint)
 * Hardware: ESP32-S3 Dev Module + LoRa SX1278 (433MHz)
 * 
 * FLOW: 
 * LORA (From Sender) -> ESP32-S3 -> SERIAL MONITOR & RECEIVER APP (via BLE)
 * 
 * Wiring (ESP32-S3):
 * - SCK:  12
 * - MISO: 13
 * - MOSI: 11
 * - NSS:  10
 * - RST:  14
 * - DIO0: 2
 */

#include <SPI.h>
#include <LoRa.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// ===== CONFIGURATION =====
#define DEVICE_NAME "FOREST_NODE_RECEIVER" 
#define SERVICE_UUID           "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHARACTERISTIC_UUID_RX "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHARACTERISTIC_UUID_TX "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

// LoRa Pins (ESP32-S3 Specific)
#define SCK     12
#define MISO    13
#define MOSI    11
#define SS      10
#define RST     14
#define DIO0    2

// ===== GLOBAL VARS =====
BLEServer *pServer = NULL;
BLECharacteristic *pTxCharacteristic = NULL;
bool deviceConnected = false;
bool oldDeviceConnected = false;

// Forward Declarations
void displayMessage(String sender, String content);

#include <mbedtls/aes.h>

// AES Key (MUST MATCH SENDER) - 16 bytes
const unsigned char aes_key[16] = { 'F','O','R','E','S','T','L','I','N','K','2','0','2','6','!','!' };

byte hexToByte(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    return 0;
}

String decryptData(String hexString) {
    int len = hexString.length();
    // Validate length (must be multiple of 32 for hex representation of 16-byte blocks)
    if (len == 0 || len % 32 != 0) return hexString; // Return original if not valid hex-aes
    
    int dataLen = len / 2;
    unsigned char input[dataLen];
    unsigned char output[dataLen];
    
    // Hex -> Binary
    for (int i = 0; i < len; i += 2) {
        input[i/2] = (byte)((hexToByte(hexString[i]) << 4) | hexToByte(hexString[i+1]));
    }
    
    mbedtls_aes_context aes;
    mbedtls_aes_init(&aes);
    mbedtls_aes_setkey_dec(&aes, aes_key, 128);
    
    for (int i = 0; i < dataLen; i += 16) {
        mbedtls_aes_crypt_ecb(&aes, MBEDTLS_AES_DECRYPT, input + i, output + i);
    }
    mbedtls_aes_free(&aes);
    
    // Binary -> String (Remove padding and non-printable chars)
    String result = "";
    for (int i = 0; i < dataLen; i++) {
        // Only valid printable ASCII (and skip NULL 0x00)
        if (output[i] > 0x00 && output[i] < 0x7F) {
             result += (char)output[i]; 
        }
    }
    return result;
}

// ===== BLE CALLBACKS =====
class MyServerCallbacks: public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
      deviceConnected = true;
      Serial.println(">> Receiver App Connected <<");
    }

    void onDisconnect(BLEServer* pServer) {
      deviceConnected = false;
      Serial.println(">> Receiver App Disconnected <<");
    }
};

void setup() {
  Serial.begin(115200);
  while (!Serial);
  Serial.println("ForestLink Receiver Starting...");

  // 1. Initialize BLE
  BLEDevice::init(DEVICE_NAME);
  BLEDevice::setMTU(517); // Set MTU size to support large payloads
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());
  BLEService *pService = pServer->createService(SERVICE_UUID);

  pTxCharacteristic = pService->createCharacteristic(
                      CHARACTERISTIC_UUID_TX,
                      BLECharacteristic::PROPERTY_NOTIFY
                    );
  pTxCharacteristic->addDescriptor(new BLE2902());

  // RX just for compatibility, we mostly SEND data to app here
  BLECharacteristic *pRxCharacteristic = pService->createCharacteristic(
                      CHARACTERISTIC_UUID_RX,
                      BLECharacteristic::PROPERTY_WRITE
                    );
  
  pService->start();
  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->setScanResponse(true);
  pAdvertising->setMinPreferred(0x06); 
  BLEDevice::startAdvertising();
  Serial.println("BLE Ready - Waiting for App...");

  // 2. Initialize LoRa
  // SPI.begin(SCK, MISO, MOSI, SS); // Initialize explicit SPI pins for S3
  SPI.begin(SCK, MISO, MOSI, SS);
  LoRa.setPins(SS, RST, DIO0);
  
  Serial.println("Initializing LoRa...");
  // Replace 433E6 with 868E6 or 915E6 depending on your region/module!
  if (!LoRa.begin(433E6)) {
    Serial.println("Starting LoRa failed!");
    // while (1); // Don't freeze! Let BLE work for testing
  }
  Serial.println("LoRa Initialized! Listening for packets...");
}

void loop() {
  // Handle BLE Reconnection
  if (!deviceConnected && oldDeviceConnected) {
      delay(500); 
      pServer->startAdvertising(); 
      Serial.println("BLE Advertising restarted");
      oldDeviceConnected = deviceConnected;
  }
  if (deviceConnected && !oldDeviceConnected) {
      oldDeviceConnected = deviceConnected;
  }

  // 3. LISTEN FOR LORA PACKET
  int packetSize = LoRa.parsePacket();
  if (packetSize) {
    // Received a packet
    String incoming = "";
    while (LoRa.available()) {
      incoming += (char)LoRa.read();
    }
    
    // 4. DISPLAY & FORWARD
    Serial.print("RAW RX PACKET: ");
    Serial.println(incoming);

    // Parse: "SENDER_ID|ENCRYPTED_Message"
    int split = incoming.indexOf('|');
    
    // FILTER NOISE
    if (split < 0) {
        Serial.println("[Warning] No Pipe '|' separator found. Ignoring."); 
        return; 
    }

    String sender = incoming.substring(0, split);
    String encryptedMsg = incoming.substring(split + 1);
    
    // Decrypt the payload
    String decryptedMsg = decryptData(encryptedMsg);
    
    Serial.print("Raw Encrypted: "); Serial.println(encryptedMsg);
    Serial.print("Decrypted: ");     Serial.println(decryptedMsg);

    displayMessage(sender, decryptedMsg);
  }
}

// ===== OUTPUT LOGIC =====
void displayMessage(String sender, String content) {
    // A. Show on Serial Monitor
    Serial.println("\n--------------------------------");
    Serial.println(" 📡 INCOMING LORA TRANSMISSION ");
    Serial.println("--------------------------------");
    Serial.print(" FROM:    "); Serial.println(sender);
    Serial.print(" MESSAGE: "); Serial.println(content);
    Serial.print(" RSSI:    "); Serial.println(LoRa.packetRssi());
    Serial.println("--------------------------------\n");

    // B. Send to Receiver App via BLE
    if (deviceConnected) {
        // Format: "TXT|Sender: Content"
        String blePacket = "TXT|" + sender + ": " + content;
        pTxCharacteristic->setValue(blePacket.c_str());
        pTxCharacteristic->notify();
        Serial.println(">> Forwarded to App via BLE");
    } else {
        Serial.println("(App not connected - Message stored in logs)");
    }
}
