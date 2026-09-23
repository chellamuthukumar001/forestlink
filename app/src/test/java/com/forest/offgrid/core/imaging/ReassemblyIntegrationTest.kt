package com.forest.offgrid.core.imaging

import com.forest.offgrid.core.imaging.chunking.ProgressiveChunker
import com.forest.offgrid.core.imaging.reassembly.ImageReassembler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ReassemblyIntegrationTest {

    @Test
    fun testEndToEndChunkDropAndRecoverWithReedSolomonFec() = runBlocking {
        // Construct a synthetic 1800-byte test file (10 shards * 180 bytes)
        // With a synthetic RIFF WebP container header so padding stripping is tested
        val totalSize = 1800
        val originalBytes = ByteArray(totalSize)
        Random.nextBytes(originalBytes)

        // WebP container header: "RIFF" <size-8> "WEBP"
        originalBytes[0] = 'R'.code.toByte()
        originalBytes[1] = 'I'.code.toByte()
        originalBytes[2] = 'F'.code.toByte()
        originalBytes[3] = 'F'.code.toByte()
        val riffPayload = totalSize - 8
        originalBytes[4] = (riffPayload and 0xFF).toByte()
        originalBytes[5] = ((riffPayload shr 8) and 0xFF).toByte()
        originalBytes[6] = ((riffPayload shr 16) and 0xFF).toByte()
        originalBytes[7] = ((riffPayload shr 24) and 0xFF).toByte()

        // 1. Chunk with 10:3 RS FEC ratio
        val imageId = 8888
        val allChunks = ProgressiveChunker.createChunks(
            imageId = imageId,
            compressedData = originalBytes,
            isGrayscale = false,
            isJpeg = false,
            fecParityRatio = 0.3
        )

        val k = allChunks[0].header.dataShards
        val n = allChunks[0].header.totalShards
        assertEquals(10, k) // 1800 / 180 = 10 data shards
        assertEquals(13, n) // 10 data + 3 parity = 13 shards

        // 2. Simulate 23% packet loss over LoRa mesh:
        // Intentionally drop 3 data shards (e.g. shards 1, 4, 7)
        val droppedSeqNumbers = setOf(1, 4, 7)
        val survivingChunks = allChunks.filterNot { droppedSeqNumbers.contains(it.header.sequenceNumber) }.shuffled()

        assertEquals(10, survivingChunks.size) // 13 - 3 = 10 surviving shards (exactly K shards!)

        // 3. Feed surviving chunks into ImageReassembler
        val reassembler = ImageReassembler()
        var recoveredBytes: ByteArray? = null

        for (chunk in survivingChunks) {
            val result = reassembler.processChunk(chunk)
            if (result != null) {
                recoveredBytes = result
            }
        }

        // 4. Verify that early FEC recovery succeeded
        assertNotNull("Reassembler must successfully recover original bytes at K shards", recoveredBytes)
        assertEquals(originalBytes.size, recoveredBytes!!.size)
        assertArrayEquals("Recovered payload must be 100% bit-exact with original", originalBytes, recoveredBytes)
    }

    @Test
    fun testReassemblyWithoutLoss() = runBlocking {
        val totalSize = 900 // 5 shards
        val originalBytes = ByteArray(totalSize) { (it % 256).toByte() }

        val allChunks = ProgressiveChunker.createChunks(
            imageId = 5555,
            compressedData = originalBytes,
            isGrayscale = true,
            isJpeg = false,
            fecParityRatio = 0.3
        )

        val reassembler = ImageReassembler()
        var recoveredBytes: ByteArray? = null

        // Feed data shards in order
        for (chunk in allChunks.take(5)) {
            val result = reassembler.processChunk(chunk)
            if (result != null) {
                recoveredBytes = result
            }
        }

        assertNotNull(recoveredBytes)
        assertArrayEquals(originalBytes, recoveredBytes)
    }
}
