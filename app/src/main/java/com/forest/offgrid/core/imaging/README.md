# Off-Grid LoRa Photo Transmission & Super-Resolution Pipeline (`core/imaging`)

This module enables transmitting photos over ultra-low-bandwidth LoRa mesh networks (~0.3–5.5 kbps, ~200–250 byte packet limit) with high perceived visual fidelity using aggressive source compression, systematic Reed-Solomon Forward Error Correction (FEC), and on-device Super-Resolution (ESPCN / FSRCNN 4x).

---

## 1. Architecture Overview

```
 [Sender Device]                                      [Receiver Device]
 ┌─────────────────────────┐                         ┌─────────────────────────┐
 │ Camera / Gallery Image  │                         │ Render High-Res Image   │
 └───────────┬─────────────┘                         └────────────▲────────────┘
             │                                                    │
             ▼                                                    │ 4x Upscaling
 ┌─────────────────────────┐                         ┌────────────┴────────────┐
 │ ImageCaptureCompressor  │                         │ SuperResolutionEngine   │
 │ - 320x240 Aspect Scale  │                         │ - TFLite ESPCN/FSRCNN   │
 │ - WebP (Quality 30-40)  │                         │ - Bilinear+Unsharp Fall │
 │ - Grayscale Toggle (3x) │                         │ - LRU Memory Cache      │
 └───────────┬─────────────┘                         └────────────▲────────────┘
             │ (~1.0 - 4.5 KB)                                    │
             ▼                                                    │ Bit-exact WebP
 ┌─────────────────────────┐                         ┌────────────┴────────────┐
 │ ProgressiveChunker      │                         │ ImageReassembler        │
 │ - 180-Byte Shards       │                         │ - Out-of-order Buffer   │
 │ - 10:3 Systematic RS FEC│                         │ - Progressive Preview   │
 │ - Base Shards Priority  │                         │ - Early FEC Recovery >=K│
 └───────────┬─────────────┘                         └────────────▲────────────┘
             │                                                    │
             ▼                                                    │
 ┌─────────────────────────┐                         ┌────────────┴────────────┐
 │ 13-Byte Binary Packets  │                         │ BleManager (Transceiver)│
 │ (<= 193 Bytes Total)    │                         │ - MTU 517 / Pacing      │
 └───────────┬─────────────┘                         │ - 0xA1 Binary Detection │
             │                                       └────────────▲────────────┘
             ▼                                                    │
 ┌─────────────────────────┐       LoRa Mesh         ┌────────────┴────────────┐
 │ ESP32 Transceiver (BLE) ├────────────────────────►│ ESP32 Transceiver (BLE) │
 └─────────────────────────┘      (SF7 - SF12)       └─────────────────────────┘
```

---

## 2. 13-Byte Compact Binary Header Layout

To fit comfortably within the 200–250 byte maximum LoRa packet payload while reserving **180 bytes for compressed payload data**, this pipeline specifies a 13-byte compact binary header:

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|  Magic & Ver  |     Flags     |          Image ID             |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|       Sequence Number         |         Total Shards (N)      |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|        Data Shards (K)        | Payload Length|     CRC-16    |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|    CRC-16     |             Payload Bytes (<= 180 B) ...      |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

### Detailed Field Breakdown
| Byte Offset | Size | Name | Type | Description |
| :--- | :--- | :--- | :--- | :--- |
| `0x00` | 1 | `magicVersion` | `uint8` | High 4-bits: `0xA` (Protocol Magic), Low 4-bits: `0x1` (v1) $\rightarrow$ `0xA1` |
| `0x01` | 1 | `flags` | `uint8` | Bit 0: Grayscale (`1` = mono, `0` = color)<br>Bit 1: Format (`0` = WebP, `1` = JPEG)<br>Bit 2: IsParity (`1` = RS Parity Shard, `0` = Data)<br>Bit 3: PreviewReady (`1` = Header/Base shard) |
| `0x02`–`0x03` | 2 | `imageId` | `uint16_be` | Unique session photo identifier (`0 .. 65535`) |
| `0x04`–`0x05` | 2 | `sequenceNumber`| `uint16_be` | Shard index: `0 .. K-1` (data), `K .. N-1` (parity) |
| `0x06`–`0x07` | 2 | `totalShards` | `uint16_be` | Total transmitted shards ($N = K + M$) |
| `0x08`–`0x09` | 2 | `dataShards` | `uint16_be` | Minimum shards required for RS recovery ($K$) |
| `0x0A` | 1 | `payloadLength`| `uint8` | Length of data payload in this packet ($1 \le L \le 180$) |
| `0x0B`–`0x0C` | 2 | `crc16` | `uint16_be` | CRC-16-CCITT across header (bytes 0–10) + payload bytes |

* **Header Size**: 13 bytes
* **Maximum Payload**: 180 bytes
* **Total Maximum Wire Packet**: $13 + 180 = 193$ bytes
* **Meshtastic Compatibility**: Formatted as a raw binary payload compatible with custom portnums or `PRIVATE_APP` (`portnum = 256`).

---

## 3. Reed-Solomon Forward Error Correction ($GF(2^8)$)

* **Field**: Galois Field $GF(2^8)$ with primitive polynomial $p(x) = x^8 + x^4 + x^3 + x^2 + 1$ (`0x11D`).
* **Matrix**: Systematic Cauchy generator matrix $G = \begin{bmatrix} I_K \\ C_{M \times K} \end{bmatrix}$, ensuring that the first $K$ shards are bit-exact copies of the original data.
* **Ratio**: Default $10 : 3$ (30% parity overhead).
* **Recovery Rule**: **Any $K$ distinct shards** out of the $N = K + M$ transmitted shards allow 100% bit-exact reconstruction using Gaussian elimination matrix inversion.
* **Mesh Resilience**: Recovers from up to **23%–30% packet loss** across multi-hop LoRa hops with zero retransmission round-trips (eliminates ARQ storms over half-duplex radio).

---

## 4. On-Device Super-Resolution Engine

* **Model**: Lightweight ESPCN / FSRCNN 4x upscaler (`models/fsrcnn_4x.tflite`).
* **Input**: 320x240 low-resolution bitmap.
* **Output**: 1280x960 high-fidelity bitmap.
* **Fallback Strategy**: If TFLite interpreter initialization is unavailable or unsupported on the device, the engine gracefully degrades to Android hardware bilinear filtering combined with unsharp edge contrast enhancement without crashing or dropping frames.
* **LRU Caching**: Memory cached using `LruCache<String, Bitmap>` (1/8th max heap) and saved to persistent app storage.

---

## 5. Performance & LoRa Airtime Benchmarks

| Mode | Input Size | Compressed Size | Shards ($K+M$) | Est. Airtime (SF7 / 125kHz) | Est. Airtime (SF10 / 125kHz) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Color WebP (q=35)** | 320x240 | ~3.6 KB | 20 data + 6 parity = 26 | **~5.2 seconds** | ~31 seconds |
| **Grayscale WebP** | 320x240 | ~1.2 KB | 7 data + 2 parity = 9 | **~1.8 seconds** | ~11 seconds |
| *Uncompressed Raw* | 320x240 | 230 KB | > 1200 shards | > 4 minutes (Unfeasible) | > 25 minutes |

---

## 6. Verification & Automated Tests

All core algorithms are verified with automated unit tests under `app/src/test/java/com/forest/offgrid/core/imaging/`:
* `ReedSolomonFecTest.kt`: Tests $GF(2^8)$ arithmetic, Cauchy generator matrix inversion, and bit-exact recovery under 30% artificial packet loss.
* `ImageChunkingTest.kt`: Validates binary header pack/unpack, CRC-16-CCITT rejection on corrupted bytes, and progressive chunk partitioning.
* `ReassemblyIntegrationTest.kt`: Verifies end-to-end chunking, out-of-order shard arrival, and early FEC recovery at $\ge K$ shards.
