/**
 * ForestLink TRANSCEIVER NODE (Two-Way Communication)
 * Hardware: ESP32-S3 Dev Module + LoRa SX1278 (433MHz)
 * 
 * FLOW: 
 * Android App <-> BLE <-> ESP32-S3 <-> LoRa (TX/RX)
 * 
 * SUPPORTED COMMANDS:
 * - TXT|<data>         : Text message (encrypted for LoRa)
 * - SOS|<data>         : Emergency SOS (broadcast, fire-and-forget)
 * - ACK|<id>           : Message acknowledgment
 * - MEDIA|<id>|<idx>|<total>|<type>|<base64data> : Chunked media (voice/image)
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
#include <mbedtls/aes.h>
#include <queue>
#include <freertos/semphr.h>

// ===== CONFIGURATION =====
// Change this to "FOREST_NODE_002" on the second device
#define DEVICE_NAME "FOREST_NODE_002" 

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

// Media chunk settings
#define MAX_LORA_PAYLOAD   200   // Max bytes per LoRa packet for media
#define MEDIA_CHUNK_DELAY  50    // ms delay between LoRa media chunks
#define MAX_MEDIA_CHUNKS   200   // Max chunks we'll buffer for reassembly

// ===== GLOBAL VARS =====
BLEServer *pServer = NULL;
BLECharacteristic *pTxCharacteristic = NULL; // TX from ESP32 -> App (Notify)
bool deviceConnected = false;
bool oldDeviceConnected = false;

// Thread-safe queue for decoupling BLE writes from LoRa transmission
std::queue<String> bleRxQueue;
SemaphoreHandle_t bleRxQueueMutex = NULL;

// Simulated GPS Coordinates
float currentLat = 12.9716;
float currentLon = 77.5946;

// ===== MEDIA REASSEMBLY BUFFER (for LoRa -> BLE direction) =====
struct MediaReassembly {
    bool active;
    String msgId;
    String mediaType;
    int totalChunks;
    int receivedCount;
    String chunks[MAX_MEDIA_CHUNKS];
    unsigned long lastChunkTime;
};

MediaReassembly reassemblyBuffer = { false, "", "", 0, 0, {}, 0 };

// Forward Declarations
void handleTextMessage(String msg);
void handleMediaChunk(String mediaPacket);
void sendToLoRa(String msg);
void sendMediaChunkToLoRa(String chunk);
void forwardToBluetoothApp(String sender, String content);
void forwardMediaChunkToBLE(String chunk);
void handleLoRaMediaChunk(String sender, String rawPayload);

// ===== AES ENCRYPTION / DECRYPTION =====
const unsigned char aes_key[16] = { 'F','O','R','E','S','T','L','I','N','K','2','0','2','6','!','!' };

byte hexToByte(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    return 0;
}

String encryptData(String plainText) {
    mbedtls_aes_context aes;
    mbedtls_aes_init(&aes);
    mbedtls_aes_setkey_enc(&aes, aes_key, 128);
    
    int len = plainText.length();
    int padded_len = (len / 16 + 1) * 16;
    unsigned char input[padded_len];
    unsigned char output[padded_len];
    
    memset(input, 0, padded_len);
    plainText.getBytes(input, len + 1);
    
    for (int i = 0; i < padded_len; i += 16) {
        mbedtls_aes_crypt_ecb(&aes, MBEDTLS_AES_ENCRYPT, input + i, output + i);
    }
    mbedtls_aes_free(&aes);
    
    String hexString = "";
    for (int i = 0; i < padded_len; i++) {
        if (output[i] < 16) hexString += "0";
        hexString += String(output[i], HEX);
    }
    return hexString;
}

String decryptData(String hexString) {
    int len = hexString.length();
    if (len == 0 || len % 32 != 0) return hexString; 
    
    int dataLen = len / 2;
    unsigned char input[dataLen];
    unsigned char output[dataLen];
    
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
    
    String result = "";
    for (int i = 0; i < dataLen; i++) {
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
      Serial.println(">> App Connected <<");
    }

    void onDisconnect(BLEServer* pServer) {
      deviceConnected = false;
      Serial.println(">> App Disconnected <<");
    }
};

class MyCallbacks: public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pCharacteristic) {
      String rxValue = pCharacteristic->getValue();

      if (rxValue.length() > 0) {
        Serial.print("App sent (queued): ");
        Serial.println(rxValue.substring(0, min((int)rxValue.length(), 40)));

        if (bleRxQueueMutex != NULL) {
            if (xSemaphoreTake(bleRxQueueMutex, portMAX_DELAY) == pdTRUE) {
                bleRxQueue.push(rxValue);
                xSemaphoreGive(bleRxQueueMutex);
            }
        }
      }
    }
};

void setup() {
  Serial.begin(115200);
  while (!Serial); 
  Serial.println("ForestLink TRANSCEIVER v2.0 (Voice/Image) Starting...");

  // Initialize queue mutex
  bleRxQueueMutex = xSemaphoreCreateMutex();

  // 1. Initialize BLE
  BLEDevice::init(DEVICE_NAME);
  BLEDevice::setMTU(517); // Set MTU size to support large payloads
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());
  BLEService *pService = pServer->createService(SERVICE_UUID);

  // Characteristic where ESP32 writes data to App
  pTxCharacteristic = pService->createCharacteristic(
                      CHARACTERISTIC_UUID_TX,
                      BLECharacteristic::PROPERTY_NOTIFY
                    );
  pTxCharacteristic->addDescriptor(new BLE2902());

  // Characteristic where App writes data to ESP32
  BLECharacteristic *pRxCharacteristic = pService->createCharacteristic(
                      CHARACTERISTIC_UUID_RX,
                      BLECharacteristic::PROPERTY_WRITE
                    );
  pRxCharacteristic->setCallbacks(new MyCallbacks());

  pService->start();
  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->setScanResponse(true);
  pAdvertising->setMinPreferred(0x06); 
  BLEDevice::startAdvertising();
  Serial.println("BLE Ready - Waiting for App...");

  // 2. Initialize LoRa
  SPI.begin(SCK, MISO, MOSI, SS);
  LoRa.setPins(SS, RST, DIO0);
  
  if (!LoRa.begin(433E6)) { // Use 433E6, 868E6, or 915E6 matching your LoRa chip
    Serial.println("Starting LoRa failed! Check wiring.");
  } else {
    Serial.println("LoRa Initialized (433MHz)! Listening for packets...");
  }
}

void loop() {
  // 1. Process queued BLE messages (Decoupled to avoid blocking BLE stack)
  String packetToProcess = "";
  if (bleRxQueueMutex != NULL && xSemaphoreTake(bleRxQueueMutex, 0) == pdTRUE) {
      if (!bleRxQueue.empty()) {
          packetToProcess = bleRxQueue.front();
          bleRxQueue.pop();
      }
      xSemaphoreGive(bleRxQueueMutex);
  }

  if (packetToProcess.length() > 0) {
      // Route based on command prefix
      if (packetToProcess.startsWith("TXT|")) {
         handleTextMessage(packetToProcess.substring(4));
      }
      else if (packetToProcess.startsWith("SOS|")) {
         // SOS: broadcast immediately without encryption for maximum reach
         String sosPayload = packetToProcess.substring(4);
         Serial.println(">> SOS ALERT <<");
         LoRa.beginPacket();
         LoRa.print(String(DEVICE_NAME) + "|SOS|");
         LoRa.print(sosPayload);
         LoRa.endPacket();
         Serial.println(">> SOS broadcast on LoRa");
      }
      else if (packetToProcess.startsWith("MEDIA|")) {
         // Media chunk from App -> relay to LoRa
         handleMediaChunk(packetToProcess);
      }
      else if (packetToProcess.startsWith("ACK|")) {
         // ACK from App -> relay to LoRa (for remote node)
         LoRa.beginPacket();
         LoRa.print(String(DEVICE_NAME) + "|" + packetToProcess);
         LoRa.endPacket();
      }
  }

  // 2. Handle BLE Reconnection
  if (!deviceConnected && oldDeviceConnected) {
      delay(500); 
      pServer->startAdvertising(); 
      oldDeviceConnected = deviceConnected;
  }
  if (deviceConnected && !oldDeviceConnected) {
      oldDeviceConnected = deviceConnected;
  }
  
  // 3. Listen For Incoming LoRa Packets
  int packetSize = LoRa.parsePacket();
  if (packetSize) {
    String incoming = "";
    while (LoRa.available()) {
      incoming += (char)LoRa.read();
    }
    
    Serial.print("RAW RX PACKET: "); Serial.println(incoming);

    int split = incoming.indexOf('|');
    if (split > 0) {
        String sender = incoming.substring(0, split);
        String payload = incoming.substring(split + 1);
        
        // Check if it's a media chunk relayed over LoRa
        if (payload.startsWith("MEDIA|")) {
            handleLoRaMediaChunk(sender, payload);
        }
        // Check if it's an SOS broadcast
        else if (payload.startsWith("SOS|")) {
            String sosContent = payload.substring(4);
            Serial.println("\n!! SOS RECEIVED !!");
            Serial.print("From: "); Serial.println(sender);
            Serial.print("Message: "); Serial.println(sosContent);
            
            // Forward SOS to app
            if (deviceConnected) {
                String blePacket = "SOS|" + sender + ": " + sosContent;
                pTxCharacteristic->setValue(blePacket.c_str());
                pTxCharacteristic->notify();
            }
        }
        // Check if it's an ACK
        else if (payload.startsWith("ACK|")) {
            if (deviceConnected) {
                pTxCharacteristic->setValue(payload.c_str());
                pTxCharacteristic->notify();
                Serial.println(">> ACK forwarded to App");
            }
        }
        // Regular encrypted text message
        else {
            String decryptedMsg = decryptData(payload);
            Serial.print("Decrypted: "); Serial.println(decryptedMsg);
            forwardToBluetoothApp(sender, decryptedMsg);
        }
    }
  }
  
  // 3. Cleanup stale reassembly buffers (timeout: 30 seconds)
  if (reassemblyBuffer.active && 
      (millis() - reassemblyBuffer.lastChunkTime > 30000)) {
      Serial.println("Media reassembly timeout - clearing buffer");
      reassemblyBuffer.active = false;
      reassemblyBuffer.receivedCount = 0;
  }
}

// ===== TEXT SENDING LOGIC =====
void handleTextMessage(String msg) {
    // Append Location to the message payload
    String msgWithLoc = msg + " [" + String(currentLat, 4) + ", " + String(currentLon, 4) + "]";
    
    sendToLoRa(msgWithLoc);
    
    // Auto-ACK to App
    if (deviceConnected) {
        int pipe = msg.indexOf('|');
        if (pipe > 0) {
            String msgId = msg.substring(0, pipe);
            String ack = "ACK|" + msgId;
            pTxCharacteristic->setValue(ack.c_str());
            pTxCharacteristic->notify();
        }
    }
}

void sendToLoRa(String msg) {
    String encryptedHex = encryptData(msg);
    
    LoRa.beginPacket();
    LoRa.print(String(DEVICE_NAME) + "|"); // Sender ID
    LoRa.print(encryptedHex);              // Payload (Encrypted)
    LoRa.endPacket();
    
    Serial.print(">> Sent to LoRa: ");
    Serial.println(msg);
}

// ===== MEDIA CHUNK HANDLING (BLE App -> LoRa) =====
/**
 * Receives a MEDIA chunk from the Android app via BLE and relays it over LoRa.
 * Format: MEDIA|<msgId>|<chunkIdx>|<totalChunks>|<type>|<base64data>
 * 
 * Media chunks are sent as-is (NOT encrypted) because:
 * 1. Base64 data is already opaque
 * 2. Encryption would increase size beyond LoRa limits
 * 3. Speed is critical for multi-chunk transfers
 */
void handleMediaChunk(String mediaPacket) {
    Serial.print(">> Media chunk from App: ");
    Serial.println(mediaPacket.substring(0, min((int)mediaPacket.length(), 60)) + "...");
    
    // Relay the MEDIA chunk directly over LoRa with sender prefix
    LoRa.beginPacket();
    LoRa.print(String(DEVICE_NAME) + "|");
    LoRa.print(mediaPacket);
    LoRa.endPacket();
    
    Serial.println(">> Media chunk relayed to LoRa");
    
    delay(MEDIA_CHUNK_DELAY); // Pacing to avoid LoRa congestion
}

// ===== MEDIA CHUNK HANDLING (LoRa -> BLE App) =====
/**
 * Receives a MEDIA chunk from LoRa and forwards it to the connected app via BLE.
 * No reassembly needed on ESP32 — the app handles reassembly itself.
 */
void handleLoRaMediaChunk(String sender, String rawPayload) {
    Serial.print(">> Media chunk from LoRa (");
    Serial.print(sender);
    Serial.print("): ");
    Serial.println(rawPayload.substring(0, min((int)rawPayload.length(), 60)) + "...");
    
    // Forward directly to BLE App — app's ChunkedTransferManager handles reassembly
    if (deviceConnected) {
        pTxCharacteristic->setValue(rawPayload.c_str());
        pTxCharacteristic->notify();
        Serial.println(">> Media chunk forwarded to App via BLE");
    } else {
        Serial.println("(App not connected - media chunk dropped)");
    }
}

// ===== TEXT RECEIVING LOGIC =====
void forwardToBluetoothApp(String sender, String content) {
    if (deviceConnected) {
        // App's BleManager expects: TXT|Sender: Content
        String blePacket = "TXT|" + sender + ": " + content;
        pTxCharacteristic->setValue(blePacket.c_str());
        pTxCharacteristic->notify();
        Serial.println(">> Forwarded to App via BLE");
    } else {
        Serial.println("(App not connected - Drop message)");
    }
}
