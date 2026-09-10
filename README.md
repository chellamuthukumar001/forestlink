# Off-Grid Forest Communication App

A native Android application designed for off-grid communication using external hardware (ESP32 + LoRa). This app enables secure Text and SOS signaling in reserve forests without cellular connectivity.

## Features
- **Offline Messaging**: Send and receive encrypted text messages via LoRa.
- **SOS Beacon**: High-priority emergency signaling with GPS location.
- **🗺️ Offline Map System**: Real-time node tracking with fully offline maps
  - True offline maps using OpenStreetMap (no internet required)
  - Real-time GPS tracking and user position
  - Live node visualization (devices, hubs, hardware, SOS alerts)
  - Mesh network visualization showing device connections
  - Breadcrumb trail tracking movement history
  - Emergency SOS mode with auto-centering
  - Interactive node details with distance and signal strength
  - Hardware integration via BLE for remote node GPS data
- **Hardware Integration**: BLE interface to ESP32-based hardware.
- **Simulation Mode**: Built-in demo mode to test UI flows without physical hardware.

## Architecture
- **Tech Stack**: Kotlin, Android Jetpack (Navigation, Room, ViewModel, Coroutines).
- **Pattern**: MVVM + Clean Architecture principles.
- **Security**: AES-128 Encryption for message content.

## Setup Instructions
1.  **Open in Android Studio**: Import the project from the `off grid communication` folder.
2.  **Sync Gradle**: Ensure all dependencies are downloaded.
3.  **Run**: Connect an Android device (Android 8.0+) or Emulator.
4.  **Permissions**: Grant Location and Notification permissions when prompted.

## Hardware Protocol (BLE) (For Reference)
- **Service UUID**: `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`
- **TX Char (App -> Device)**: `6E400002...`
- **RX Char (Device -> App)**: `6E400003...`

### Commands
- `TXT|<message>`: Send text message.
- `SOS|<message>`: Send SOS.

### Telementry (RX)
- `GPS|<lat>,<lng>|BAT|<%>`: Update location and battery status.

## Demo Mode
If no hardware is available, enable "Simulate Hardware" in the Dashboard. The app will:
- Simulate a BLE connection.
- Generate random Battery/GPS updates.
- Allow sending messages (loopback).

## 🗺️ Offline Map System

For complete documentation on the offline map system, including:
- Hardware integration protocols
- GPS coordinate formats
- Customization options
- Performance tuning
- Troubleshooting guide

**See: [OFFLINE_MAP_GUIDE.md](./OFFLINE_MAP_GUIDE.md)**
