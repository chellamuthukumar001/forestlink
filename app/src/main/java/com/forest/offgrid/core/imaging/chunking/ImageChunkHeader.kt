package com.forest.offgrid.core.imaging.chunking

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 13-byte compact binary header for LoRa photo chunks.
 *
 * Wire Layout:
 * [0]     Magic (high 4-bits = 0xA) & Version (low 4-bits = 0x1) -> 0xA1
 * [1]     Flags: Bit 0 = Grayscale (1=mono, 0=color)
 *                Bit 1 = Format (0=WebP, 1=JPEG)
 *                Bit 2 = IsParityShard (1=FEC Parity, 0=Data)
 *                Bit 3 = PreviewReady (1=Base/header shard)
 * [2..3]  ImageId (uint16_be, 0..65535)
 * [4..5]  SequenceNumber (uint16_be, 0..totalShards-1)
 * [6..7]  TotalShards N (uint16_be)
 * [8..9]  DataShards K (uint16_be)
 * [10]    PayloadLength (uint8, 1..180)
 * [11..12] CRC16-CCITT (uint16_be across header bytes 0..10 + payload bytes)
 */
data class ImageChunkHeader(
    val imageId: Int,
    val sequenceNumber: Int,
    val totalShards: Int,
    val dataShards: Int,
    val payloadLength: Int,
    val isGrayscale: Boolean = false,
    val isJpeg: Boolean = false,
    val isParity: Boolean = false,
    val isPreviewReady: Boolean = false,
    val crc16: Int = 0
) {
    companion object {
        const val HEADER_SIZE = 13
        const val MAX_PAYLOAD_SIZE = 180
        const val MAGIC_VERSION: Byte = 0xA1.toByte()

        const val FLAG_GRAYSCALE = 0x01
        const val FLAG_JPEG = 0x02
        const val FLAG_PARITY = 0x04
        const val FLAG_PREVIEW_READY = 0x08

        /**
         * Calculates CRC16-CCITT (polynomial 0x1021, init 0xFFFF).
         */
        fun computeCrc16(headerBytes0To10: ByteArray, payload: ByteArray): Int {
            var crc = 0xFFFF
            val polynomial = 0x1021

            val updateCrc = { byteVal: Int ->
                for (i in 0 until 8) {
                    val bit = ((byteVal shr (7 - i)) and 1) == 1
                    val c15 = ((crc shr 15) and 1) == 1
                    crc = (crc shl 1) and 0xFFFF
                    if (c15 xor bit) {
                        crc = crc xor polynomial
                    }
                }
            }

            for (b in headerBytes0To10) {
                updateCrc(b.toInt() and 0xFF)
            }
            for (b in payload) {
                updateCrc(b.toInt() and 0xFF)
            }

            return crc and 0xFFFF
        }

        /**
         * Deserializes a 13-byte header from the beginning of a packet byte array.
         */
        fun parse(packet: ByteArray): ImageChunkHeader? {
            if (packet.size < HEADER_SIZE) return null

            val magicVer = packet[0]
            if (magicVer != MAGIC_VERSION) return null // Unknown protocol magic or version

            val flags = packet[1].toInt() and 0xFF
            val isGrayscale = (flags and FLAG_GRAYSCALE) != 0
            val isJpeg = (flags and FLAG_JPEG) != 0
            val isParity = (flags and FLAG_PARITY) != 0
            val isPreviewReady = (flags and FLAG_PREVIEW_READY) != 0

            val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
            buffer.position(2)
            val imageId = buffer.short.toInt() and 0xFFFF
            val sequenceNumber = buffer.short.toInt() and 0xFFFF
            val totalShards = buffer.short.toInt() and 0xFFFF
            val dataShards = buffer.short.toInt() and 0xFFFF
            val payloadLength = buffer.get().toInt() and 0xFF
            val crc16 = buffer.short.toInt() and 0xFFFF

            // Verify packet bounds
            if (packet.size < HEADER_SIZE + payloadLength) return null

            // Validate CRC16 checksum
            val header0To10 = packet.copyOfRange(0, 11)
            val payloadBytes = packet.copyOfRange(HEADER_SIZE, HEADER_SIZE + payloadLength)
            val expectedCrc = computeCrc16(header0To10, payloadBytes)
            if (crc16 != expectedCrc) {
                return null // Corrupted packet, discard
            }

            return ImageChunkHeader(
                imageId = imageId,
                sequenceNumber = sequenceNumber,
                totalShards = totalShards,
                dataShards = dataShards,
                payloadLength = payloadLength,
                isGrayscale = isGrayscale,
                isJpeg = isJpeg,
                isParity = isParity,
                isPreviewReady = isPreviewReady,
                crc16 = crc16
            )
        }
    }

    /**
     * Serializes this header along with its payload into a complete wire packet.
     * @return Complete packet byte array of length 13 + payload.size
     */
    fun serialize(payload: ByteArray): ByteArray {
        val length = minOf(payload.size, MAX_PAYLOAD_SIZE)
        val packet = ByteArray(HEADER_SIZE + length)

        packet[0] = MAGIC_VERSION

        var flags = 0
        if (isGrayscale) flags = flags or FLAG_GRAYSCALE
        if (isJpeg) flags = flags or FLAG_JPEG
        if (isParity) flags = flags or FLAG_PARITY
        if (isPreviewReady) flags = flags or FLAG_PREVIEW_READY
        packet[1] = flags.toByte()

        val buffer = ByteBuffer.wrap(packet, 2, 8).order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(imageId.toShort())
        buffer.putShort(sequenceNumber.toShort())
        buffer.putShort(totalShards.toShort())
        buffer.putShort(dataShards.toShort())

        packet[10] = (length and 0xFF).toByte()

        // Copy payload
        System.arraycopy(payload, 0, packet, HEADER_SIZE, length)

        // Compute CRC16 across header bytes 0..10 and payload
        val header0To10 = packet.copyOfRange(0, 11)
        val payloadSlice = packet.copyOfRange(HEADER_SIZE, HEADER_SIZE + length)
        val computedCrc = computeCrc16(header0To10, payloadSlice)

        val crcBuffer = ByteBuffer.wrap(packet, 11, 2).order(ByteOrder.BIG_ENDIAN)
        crcBuffer.putShort(computedCrc.toShort())

        return packet
    }
}
