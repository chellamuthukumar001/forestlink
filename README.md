<div align="center">

# 🌲 ForestLink

### *Tactical Off-Grid Mesh Communication & Wilderness Rescue Network*

[![Android](https://img.shields.io/badge/Platform-Android%208.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin%201.9-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![ESP32-S3](https://img.shields.io/badge/Hardware-ESP32--S3-E7352C?style=for-the-badge&logo=espressif&logoColor=white)](https://www.espressif.com)
[![LoRa](https://img.shields.io/badge/RF-LoRa%20SX1278%20(433MHz)-FF6F00?style=for-the-badge)](https://www.semtech.com)
[![Security](https://img.shields.io/badge/Encryption-AES--128-00D4FF?style=for-the-badge&logo=lock&logoColor=white)](#-security--encryption)
[![Offline Maps](https://img.shields.io/badge/Maps-OpenStreetMap%20(100%25%20Offline)-39FF14?style=for-the-badge&logo=openstreetmap&logoColor=white)](#-offline-map-system)

<p align="center">
  <b>Zero Cellular. Zero Internet. Complete Situational Awareness.</b><br>
  ForestLink transforms commercial Android smartphones and pocket-sized ESP32-S3 + LoRa transceivers into an encrypted tactical communications and search-and-rescue grid for dense forest canopies and remote wilderness operations.
</p>

---

[Key Highlights](#-key-capabilities) •
[System Architecture](#-system-architecture) •
[Hardware & Pinout](#-hardware-specifications--wiring) •
[Protocol Specs](#-packet-protocol-specification) •
[Quickstart](#-getting-started) •
[Hardware Simulator](#-built-in-hardware-simulator) •
[Roadmap](#-roadmap)

---

</div>

## 🧭 Overview

In dense reserve forests, mountainous ravines, and national parks, traditional communication infrastructure is non-existent. **ForestLink** bridges this critical gap by decoupling communication from cellular towers and satellite subscriptions. 

Using ultra-long-range **LoRa RF (433MHz / 868MHz / 915MHz)** and local **Bluetooth Low Energy (BLE)** links, rangers, field researchers, and search-and-rescue teams can coordinate seamlessly with live encrypted text, push-to-talk voice notes, real-time GPS telemetry, and emergency SOS beacons.

```
       [ Android Smartphone ]
                 │
           (BLE 5.0 UART)
                 ▼
       [ ESP32-S3 Pocket Node ] ◄── (I2C) ── [ WisBlock SHTC3 Sensor ]
                 │              ◄── (UART) ─ [ u-blox NEO-6M GPS ]
           (SPI Bus)
                 ▼
       [ LoRa SX1278 Radio ]
                 │
       (433 MHz RF Air Interface)
                 ▼
   ┌───────────────────────────┐
   │    LoRa Off-Grid Mesh     │
   └─────────────┬─────────────┘
                 │
     ┌───────────┴───────────┐
     ▼                       ▼
[ Ranger Node 2 ]     [ Base Gateway Node ]
```

---

## ⚡ Key Capabilities

<table>
  <tr>
    <td width="50%">
      <h3>🗺️ 100% Offline Vector & Tile Maps</h3>
      <ul>
        <li>Full OpenStreetMap (OSMDroid) tile rendering without internet.</li>
        <li>Dynamic node blips (rangers, base hubs, and SOS beacons).</li>
        <li>Active breadcrumb trails tracking movement history.</li>
        <li>Mesh network link visualizer connecting operational units.</li>
      </ul>
    </td>
    <td width="50%">
      <h3>📡 Tactical Cyber Radar</h3>
      <ul>
        <li>Dynamic 360° sweeping radar UI for proximity detection.</li>
        <li>Relative bearing and distance calculations for nearby nodes.</li>
        <li>Live RSSI/SNR signal strength indicators.</li>
        <li>Instant node focus and quick-chat overlay.</li>
      </ul>
    </td>
  </tr>
  <tr>
    <td width="50%">
      <h3>🚨 High-Priority SOS Beacon</h3>
      <ul>
        <li>Zero-latency emergency broadcast over long-range LoRa.</li>
        <li>Automated GPS coordinate lock and battery telemetry tag.</li>
        <li>Audible and high-visibility pulsing beacon on receiver maps.</li>
        <li>Dedicated SOS override channel ignoring queue delays.</li>
      </ul>
    </td>
    <td width="50%">
      <h3>🎙️ Compressed Voice Notes & Media</h3>
      <ul>
        <li>Integrated voice recorder with real-time waveform visualizer.</li>
        <li>Adaptive audio compression tailored for low-bandwidth LoRa.</li>
        <li>Intelligent packet chunking and reliable reassembly engine.</li>
        <li>Support for tactical field photos and map sketches.</li>
      </ul>
    </td>
  </tr>
  <tr>
    <td width="50%">
      <h3>🔒 AES-128 Cryptographic Security</h3>
      <ul>
        <li>Pre-shared operational key cryptographic protection.</li>
        <li>Zero plain-text transmission over public RF bands.</li>
        <li>Anti-replay sequence tagging and integrity validation.</li>
        <li>Hardened mbedTLS integration on ESP32 firmware.</li>
      </ul>
    </td>
    <td width="50%">
      <h3>🌡️ Live Environmental Telemetry</h3>
      <ul>
        <li>WisBlock RAK1901 / SHTC3 ambient temperature and humidity.</li>
        <li>u-blox NEO-6M live satellite fix and altitude updates.</li>
        <li>Battery state-of-charge percentage monitoring.</li>
        <li>Node health telemetry broadcast at configurable intervals.</li>
      </ul>
    </td>
  </tr>
</table>

---

## 🏗️ System Architecture

### Mobile Client (Android Jetpack + Clean MVVM)

```
┌─────────────────────────────────────────────────────────────┐
│                       UI Presentation                       │
│  [DashboardRadar]  [ChatFragment]  [MapFragment]  [Devices] │
│         ▲                 ▲              ▲            ▲     │
│         └─────────────────┼──────────────┴────────────┘     │
│                     [MainViewModel]                         │
└───────────────────────────┬─────────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────────┐
│                     Repository Layer                        │
│             [MsgRepository]     [MapRepository]             │
└───────────────────┬──────────────────────┬──────────────────┘
                    │                      │
┌───────────────────▼───────┐      ┌───────▼──────────────────┐
│        Local SQLite       │      │   Communication Service  │
│   (Room DB + MapNodeDao)  │      │  [BleManager] [WriteQ]   │
└───────────────────────────┘      └───────────┬──────────────┘
                                               │
                                       (BLE Nordic UART)
```

- **Framework**: Modern Android Architecture Components (Coroutines, StateFlow, LiveData, Room).
- **Communication Engine**: Asynchronous `BleWriteQueue` preventing buffer overrun on MTU negotiation.
- **Mapping Engine**: Custom OSMDroid overlays with offline sqlite/zip tile caching.
- **Payload Pipeline**: `ChunkedTransferManager` + `MediaCompressor` for splitting binary data into ~150-byte packets.

---

## 🔌 Hardware Specifications & Wiring

### ESP32-S3 Dev Module to LoRa SX1278 (433MHz)

<details open>
<summary><b>View SPI & Control Pin Configuration</b></summary>

| SX1278 Pin | ESP32-S3 GPIO | Function | Description |
|:---|:---:|:---|:---|
| **VCC** | `3.3V` | Power | **Warning:** Must use 3.3V (5V damages transceiver) |
| **GND** | `GND` | Ground | Common system ground |
| **SCK** | `GPIO 12` | SPI Clock | SPI Clock Line |
| **MISO** | `GPIO 13` | SPI Data In | Master In Slave Out |
| **MOSI** | `GPIO 11` | SPI Data Out | Master Out Slave In |
| **NSS / SS** | `GPIO 10` | Chip Select | Active LOW Slave Select |
| **RST** | `GPIO 14` | Reset | Hardware Reset Pin |
| **DIO0** | `GPIO 2` | Interrupt | Packet Rx/Tx Done Interrupt |

</details>

### Sensor & GPS Integration

<details>
<summary><b>View SHTC3 & NEO-6M Pin Configuration</b></summary>

| Component | Component Pin | ESP32-S3 GPIO | Protocol | Notes |
|:---|:---|:---:|:---|:---|
| **SHTC3 Sensor** | SDA | `GPIO 8` | I2C Data | 100kHz / 400kHz pullup enabled |
| | SCL | `GPIO 9` | I2C Clock | RAK1901 WisBlock module |
| | VCC / GND | `3.3V / GND` | Power | Low power sleep enabled |
| **NEO-6M GPS** | TX | `GPIO 16 (RX1)` | UART RX | NMEA 9600 baud stream |
| | RX | `GPIO 17 (TX1)` | UART TX | GPS configuration |
| | VCC / GND | `3.3V / GND` | Power | Connect external active antenna |

</details>

---

## 📡 Packet Protocol Specification

The air interface operates on a framed, delimiter-separated, encrypted transmission standard.

<details open>
<summary><b>Packet Framing Standards</b></summary>

### 1. Plaintext & Encrypted Messaging (`TXT`)
```
Format: TXT|<ciphertext_hex>
Example Payload: TXT|7f9a2b88c3e410a5621f8dbbc42a001e
Decrypted Content: "Team Alpha reached Ridge Point 4"
```

### 2. Emergency Distress Signaling (`SOS`)
```
Format: SOS|<node_id>|<lat>|<lon>|<timestamp>|<optional_note>
Example: SOS|NODE_001|11.0168|76.9558|1725968400|Ranger down near water body
```

### 3. Chunked Voice & Binary Media (`MEDIA`)
```
Format: MEDIA|<msgId>|<chunkIdx>|<totalChunks>|<type>|<base64Chunk>
Example: MEDIA|V914|0|18|VOICE|UklGRi4AAABXQVZFZm10IBAAAA...
```

### 4. Hardware Telemetry Beacon (`TELEMETRY`)
```
Format: GPS|<lat>,<lng>|BAT|<pct>|TEMP|<degC>|HUM|<pct>
Example: GPS|11.0245,76.9621|BAT|94%|TEMP|26.4|HUM|68%
```

</details>

---

## 🚀 Getting Started

### Prerequisites

- **Android Device**: Android 8.0 (API 26) or newer with Bluetooth 4.2+ (BLE) support.
- **IDE**: Android Studio Hedgehog / Iguana / Jellyfish (JDK 17).
- **Embedded Toolchain**: Arduino IDE 2.x or VS Code + PlatformIO.
- **Hardware (Optional)**:
  - 2x ESP32-S3 Development Boards.
  - 2x SX1278 (433MHz) or SX1276 (868/915MHz) LoRa Modules.
  - 1x u-blox NEO-6M GPS module & 1x WisBlock SHTC3 module.

---

### Step 1: Clone the Repository

```bash
git clone https://github.com/chellamuthukumar001/forestlink.git
cd forestlink
```

---

### Step 2: Flash the ESP32 Firmware

1. Launch **Arduino IDE**.
2. Install required libraries via **Library Manager**:
   - `LoRa` by Sandeep Mistry
   - `TinyGPSPlus` by Mikal Hart
   - `Adafruit SHTC3 Library`
3. Open [`esp32_forestlink_transceiver.ino`](./esp32_forestlink_transceiver.ino).
4. For Node 1, leave `#define DEVICE_NAME "FOREST_NODE_001"`. For Node 2, change to `"FOREST_NODE_002"`.
5. Connect your ESP32-S3 over USB, select your board (`ESP32S3 Dev Module`), and click **Upload**.

---

### Step 3: Build & Install the Android App

1. Open Android Studio and select **Open Project** -> Choose the `forestlink` directory.
2. Let Gradle sync and download dependencies.
3. Connect your Android device or start an Emulator.
4. Click **Run** (`Shift + F10`) to deploy the app.
5. Grant Bluetooth, Location, and Audio permissions when prompted.

---

## 🕹️ Built-in Hardware Simulator

> [!TIP]
> **No ESP32 or LoRa hardware on hand? No problem!**
> ForestLink includes a full **Virtual Radio Simulation Engine** baked directly into the app.

1. Open the ForestLink app on your device or emulator.
2. Navigate to the **Dashboard** tab.
3. Toggle the **"Simulate Hardware"** switch to `ON`.
4. The app will immediately:
   - Instantiate a virtual BLE transceiver.
   - Generate realistic dynamic GPS movements and battery drains.
   - Loop back transmitted messages, SOS triggers, and voice waveforms.
   - Plot simulated team nodes on the offline map.

---

## 📁 Repository Structure

```
forestlink/
├── app/                              # Android Native Kotlin Application
│   ├── src/main/
│   │   ├── java/com/forest/offgrid/
│   │   │   ├── data/
│   │   │   │   ├── ble/              # BleManager & Thread-safe BleWriteQueue
│   │   │   │   ├── local/            # Room Database & Node DAOs
│   │   │   │   ├── model/            # Data entities (Messages, Nodes, Telemetry)
│   │   │   │   └── repository/       # Data mediation & offline cache
│   │   │   ├── service/              # Foreground Communication Service
│   │   │   ├── ui/
│   │   │   │   ├── chat/             # Chat UI & Voice Waveform Visualizer
│   │   │   │   ├── dashboard/        # Dashboard & Cyber Radar Sweep View
│   │   │   │   ├── devices/          # BLE Scanner & Device Pairing
│   │   │   │   └── map/              # OSMDroid Offline Map System
│   │   │   └── util/                 # Audio, Chunking, Encryption & GPS Helpers
│   │   └── res/                      # Layouts, Cyber drawables, Vectors & Themes
├── esp32_forestlink_gateway/         # Base station gateway node firmware
├── esp32_forestlink_transceiver.ino  # Full-duplex field node transceiver firmware
├── esp32_receiver_lora.ino           # Dedicated receiver & test harness
├── .kiro/specs/                      # Technical specifications & design documents
├── build.gradle                      # Root Gradle build script
└── README.md                         # Project documentation
```

---

## 🗺️ Offline Map System

ForestLink features a completely self-contained offline mapping system built on top of OSMDroid. For in-depth instructions on pre-caching regional satellite imagery or vector topo maps into assets, custom coordinates, and mesh overlay tuning:

👉 **Read the comprehensive guide:** [`.kiro/specs/sensor-gps-integration/design.md`](./.kiro/specs/sensor-gps-integration/design.md)

---

## 🗺️ Roadmap

- [x] BLE 5.0 high-throughput pipeline with write queues
- [x] Full-duplex LoRa transceiver firmware on ESP32-S3
- [x] SHTC3 environmental telemetry & NEO-6M live GPS
- [x] Dynamic tactical radar sweep and node proximity engine
- [x] Offline map caching and breadcrumb trail navigation
- [x] Low-bandwidth voice note compression and packet chunking
- [x] AES-128 end-to-end payload encryption
- [ ] Multi-hop mesh routing (Flooding & Directed AODV)
- [ ] Low-frequency satellite uplink fallback (Iridium / InReach relay)
- [ ] Automated drone airborne relay compatibility

---

## 👨‍💻 Author

**P. Chella Muthu Kumar**
- GitHub: [@chellamuthukumar001](https://github.com/chellamuthukumar001)
- Email: annamayilannamayil32@gmail.com

---

<div align="center">
  <sub>Built with ❤️ for Wildlife Conservationists, Forest Rangers, and Wilderness Search & Rescue Teams.</sub>
</div>
