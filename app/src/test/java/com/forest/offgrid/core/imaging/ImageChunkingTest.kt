package com.forest.offgrid.core.imaging

import com.forest.offgrid.core.imaging.chunking.ImageChunk
import com.forest.offgrid.core.imaging.chunking.ImageChunkHeader
import com.forest.offgrid.core.imaging.chunking.ProgressiveChunker
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ImageChunkingTest {

    @Test
    fun testHeaderSerializationAndDeserialization() {
        val payload = ByteArray(120) { it.toByte() }
        val header = ImageChunkHeader(
            imageId = 1234,
            sequenceNumber = 5,
            totalShards = 13,
            dataShards = 10,
            payloadLength = payload.size,
            isGrayscale = true,
            isJpeg = false,
            isParity = false,
            isPreviewReady = true
        )

        val packet = header.serialize(payload)
        assertEquals(ImageChunkHeader.HEADER_SIZE + payload.size, packet.size)
        assertEquals(ImageChunkHeader.MAGIC_VERSION, packet[0])

        val parsed = ImageChunkHeader.parse(packet)
        assertNotNull("Parsed header must not be null", parsed)
        assertEquals(1234, parsed!!.imageId)
        assertEquals(5, parsed.sequenceNumber)
        assertEquals(13, parsed.totalShards)
        assertEquals(10, parsed.dataShards)
        assertEquals(payload.size, parsed.payloadLength)
        assertTrue(parsed.isGrayscale)
        assertFalse(parsed.isJpeg)
        assertFalse(parsed.isParity)
        assertTrue(parsed.isPreviewReady)
    }

    @Test
    fun testCrc16CorruptionRejection() {
        val payload = ByteArray(150) { (it * 3).toByte() }
        val header = ImageChunkHeader(
            imageId = 999,
            sequenceNumber = 0,
            totalShards = 5,
            dataShards = 4,
            payloadLength = payload.size
        )

        val wireBytes = header.serialize(payload)
        assertNotNull(ImageChunkHeader.parse(wireBytes))

        // Corrupt a byte in payload
        wireBytes[ImageChunkHeader.HEADER_SIZE + 10] = (wireBytes[ImageChunkHeader.HEADER_SIZE + 10] + 1).toByte()
        assertNull("Corrupted payload must fail CRC check", ImageChunkHeader.parse(wireBytes))

        // Corrupt a byte in header
        val wireBytes2 = header.serialize(payload)
        wireBytes2[2] = (wireBytes2[2] + 1).toByte()
        assertNull("Corrupted header byte must fail CRC check", ImageChunkHeader.parse(wireBytes2))
    }

    @Test
    fun testProgressiveChunkerGeneration() {
        // 3500 bytes of dummy compressed image data
        val dummyData = ByteArray(3500)
        Random.nextBytes(dummyData)

        val chunks = ProgressiveChunker.createChunks(
            imageId = 777,
            compressedData = dummyData,
            isGrayscale = true,
            isJpeg = false,
            fecParityRatio = 0.3
        )

        // 3500 bytes / 180 bytes per shard = 20 data shards (K)
        // 20 * 0.3 = 6 parity shards (M) -> Total N = 26 shards
        val k = chunks[0].header.dataShards
        val n = chunks[0].header.totalShards
        assertEquals(20, k)
        assertEquals(26, n)
        assertEquals(26, chunks.size)

        // Verify data shards vs parity shards
        for (i in 0 until k) {
            val chunk = chunks[i]
            assertEquals(i, chunk.header.sequenceNumber)
            assertFalse(chunk.header.isParity)
            assertTrue(chunk.header.isGrayscale)
            if (i <= 5) {
                assertTrue("First base shards should have isPreviewReady", chunk.header.isPreviewReady)
            }
        }

        for (p in k until n) {
            val chunk = chunks[p]
            assertEquals(p, chunk.header.sequenceNumber)
            assertTrue(chunk.header.isParity)
            assertEquals(ProgressiveChunker.CHUNK_PAYLOAD_SIZE, chunk.payload.size)
        }
    }

    @Test
    fun testImageChunkWireRoundtrip() {
        val payload = "Hello Off-Grid LoRa Mesh!".toByteArray()
        val header = ImageChunkHeader(
            imageId = 42,
            sequenceNumber = 1,
            totalShards = 2,
            dataShards = 1,
            payloadLength = payload.size
        )
        val originalChunk = ImageChunk(header, payload)
        val wire = originalChunk.toWireBytes()

        val restoredChunk = ImageChunk.fromWireBytes(wire)
        assertNotNull(restoredChunk)
        assertEquals(originalChunk.header.imageId, restoredChunk!!.header.imageId)
        assertEquals(originalChunk.header.sequenceNumber, restoredChunk.header.sequenceNumber)
        assertArrayEquals(originalChunk.payload, restoredChunk.payload)
    }
}
