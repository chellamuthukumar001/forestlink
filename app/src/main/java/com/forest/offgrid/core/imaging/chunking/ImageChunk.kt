package com.forest.offgrid.core.imaging.chunking

/**
 * Model representing an image chunk packet transmitted over LoRa/BLE.
 */
data class ImageChunk(
    val header: ImageChunkHeader,
    val payload: ByteArray
) {
    /**
     * Serializes this chunk into raw wire bytes (13 header bytes + payload).
     */
    fun toWireBytes(): ByteArray = header.serialize(payload)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ImageChunk
        if (header != other.header) return false
        if (!payload.contentEquals(other.payload)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = header.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        /**
         * Parses a raw wire packet into an ImageChunk.
         * Returns null if header magic, length, or CRC16 validation fails.
         */
        fun fromWireBytes(bytes: ByteArray): ImageChunk? {
            val header = ImageChunkHeader.parse(bytes) ?: return null
            val payload = bytes.copyOfRange(
                ImageChunkHeader.HEADER_SIZE,
                ImageChunkHeader.HEADER_SIZE + header.payloadLength
            )
            return ImageChunk(header, payload)
        }
    }
}
