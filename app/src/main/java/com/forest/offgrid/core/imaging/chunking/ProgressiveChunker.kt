package com.forest.offgrid.core.imaging.chunking

import com.forest.offgrid.core.imaging.fec.ReedSolomonErasureCoder
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Splits compressed image bytes into ~180-byte chunks with Reed-Solomon FEC parity shards.
 * Orders low-frequency/base progressive data shards first for early preview generation.
 */
object ProgressiveChunker {

    const val CHUNK_PAYLOAD_SIZE = 180

    /**
     * Chunks a compressed image byte array and generates Reed-Solomon parity shards.
     *
     * @param imageId Unique uint16 identifier for this image transfer.
     * @param compressedData The WebP/JPEG byte array.
     * @param isGrayscale Whether image is grayscale.
     * @param isJpeg Whether image format is JPEG (false = WebP).
     * @param fecParityRatio Ratio of parity shards to data shards (default 0.3 = ~10:3).
     * @return Ordered list of ImageChunks (data shards first, followed by FEC parity shards).
     */
    fun createChunks(
        imageId: Int,
        compressedData: ByteArray,
        isGrayscale: Boolean = false,
        isJpeg: Boolean = false,
        fecParityRatio: Double = 0.3
    ): List<ImageChunk> {
        val totalBytes = compressedData.size
        require(totalBytes > 0) { "compressedData cannot be empty" }

        // 1. Calculate number of data shards K
        val k = ceil(totalBytes.toDouble() / CHUNK_PAYLOAD_SIZE).toInt().coerceAtLeast(1)

        // 2. Calculate number of parity shards M (e.g. 10:3 -> ~30%)
        val m = (k * fecParityRatio).roundToInt().coerceAtLeast(1)
        val n = k + m

        val coder = ReedSolomonErasureCoder(dataShards = k, parityShards = m)

        // 3. Partition data into K shards of uniform length CHUNK_PAYLOAD_SIZE (zero-padded if needed)
        val dataShardsArray = Array(k) { shardIdx ->
            val start = shardIdx * CHUNK_PAYLOAD_SIZE
            val end = minOf(start + CHUNK_PAYLOAD_SIZE, totalBytes)
            val shard = ByteArray(CHUNK_PAYLOAD_SIZE)
            if (start < totalBytes) {
                System.arraycopy(compressedData, start, shard, 0, end - start)
            }
            shard
        }

        // 4. Compute M Reed-Solomon parity shards
        val parityShardsArray = coder.encodeParity(dataShardsArray)

        val resultChunks = mutableListOf<ImageChunk>()

        // 5. Build data chunks (shards 0 .. K-1)
        for (i in 0 until k) {
            val start = i * CHUNK_PAYLOAD_SIZE
            val actualPayloadLength = if (i == k - 1) {
                val rem = totalBytes % CHUNK_PAYLOAD_SIZE
                if (rem == 0) CHUNK_PAYLOAD_SIZE else rem
            } else {
                CHUNK_PAYLOAD_SIZE
            }

            // Flag first 1-2 shards as PreviewReady (WebP headers + initial DC scan)
            val isPreview = (i == 0 || i <= (k / 4).coerceAtLeast(1))

            val header = ImageChunkHeader(
                imageId = imageId,
                sequenceNumber = i,
                totalShards = n,
                dataShards = k,
                payloadLength = actualPayloadLength,
                isGrayscale = isGrayscale,
                isJpeg = isJpeg,
                isParity = false,
                isPreviewReady = isPreview
            )

            // Extract the actual payload bytes (excluding padding)
            val payload = dataShardsArray[i].copyOfRange(0, actualPayloadLength)
            resultChunks.add(ImageChunk(header, payload))
        }

        // 6. Build FEC parity chunks (shards K .. N-1)
        for (p in 0 until m) {
            val seq = k + p
            val header = ImageChunkHeader(
                imageId = imageId,
                sequenceNumber = seq,
                totalShards = n,
                dataShards = k,
                payloadLength = CHUNK_PAYLOAD_SIZE,
                isGrayscale = isGrayscale,
                isJpeg = isJpeg,
                isParity = true,
                isPreviewReady = false
            )
            resultChunks.add(ImageChunk(header, parityShardsArray[p]))
        }

        return resultChunks
    }
}
