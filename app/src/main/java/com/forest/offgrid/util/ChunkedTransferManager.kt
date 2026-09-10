package com.forest.offgrid.util

import android.util.Log

/**
 * Manages chunked media transfer over BLE for large payloads (voice/image).
 * 
 * BLE MTU is typically 20-512 bytes. LoRa packets are ~250 bytes.
 * This splits Base64 media data into manageable chunks and reassembles on receive.
 * 
 * Protocol format:
 * Send:    MEDIA|<msgId>|<chunkIdx>|<totalChunks>|<mediaType>|<base64ChunkData>
 * 
 * mediaType: "VOICE" or "IMAGE"
 */
object ChunkedTransferManager {

    private const val TAG = "ChunkedTransfer"
    
    // Chunk size in characters of Base64 data per packet
    // BLE MTU is negotiated up to 517, but LoRa relay limits us
    // Header "MEDIA|xxxx|xxx|xxx|VOICE|" ≈ ~30 chars, leaving ~150 for data
    const val CHUNK_DATA_SIZE = 150
    
    const val CMD_MEDIA = "MEDIA|"
    
    // Pending reassembly buffers: msgId -> ChunkBuffer
    private val reassemblyBuffers = HashMap<String, ChunkBuffer>()
    
    data class ChunkBuffer(
        val totalChunks: Int,
        val mediaType: String,
        val chunks: Array<String?>,
        var receivedCount: Int = 0,
        val createdAt: Long = System.currentTimeMillis()
    ) {
        fun isComplete(): Boolean = receivedCount >= totalChunks
        
        fun getFullData(): String {
            return chunks.filterNotNull().joinToString("")
        }
    }
    
    /**
     * Splits a Base64-encoded media string into BLE-sized chunks.
     * @return List of full packet strings ready to send via BLE.
     */
    fun createChunks(msgId: String, base64Data: String, mediaType: String): List<String> {
        val chunks = mutableListOf<String>()
        val totalChunks = (base64Data.length + CHUNK_DATA_SIZE - 1) / CHUNK_DATA_SIZE
        
        for (i in 0 until totalChunks) {
            val start = i * CHUNK_DATA_SIZE
            val end = minOf(start + CHUNK_DATA_SIZE, base64Data.length)
            val chunkData = base64Data.substring(start, end)
            
            // Format: MEDIA|<msgId>|<chunkIdx>|<totalChunks>|<mediaType>|<data>
            val packet = "$CMD_MEDIA$msgId|$i|$totalChunks|$mediaType|$chunkData"
            chunks.add(packet)
        }
        
        Log.d(TAG, "Created ${chunks.size} chunks for $mediaType message $msgId (${base64Data.length} chars)")
        return chunks
    }
    
    /**
     * Processes an incoming MEDIA chunk packet.
     * @return Pair of (mediaType, fullBase64Data) if reassembly is complete, null otherwise.
     */
    fun processChunk(rawData: String): Triple<String, String, String>? {
        // Parse: MEDIA|<msgId>|<chunkIdx>|<totalChunks>|<mediaType>|<data>
        val withoutPrefix = rawData.removePrefix(CMD_MEDIA)
        val parts = withoutPrefix.split("|", limit = 5)
        
        if (parts.size < 5) {
            Log.e(TAG, "Invalid media chunk format: $rawData")
            return null
        }
        
        val msgId = parts[0]
        val chunkIdx = parts[1].toIntOrNull() ?: return null
        val totalChunks = parts[2].toIntOrNull() ?: return null
        val mediaType = parts[3]
        val chunkData = parts[4]
        
        Log.d(TAG, "Received chunk $chunkIdx/$totalChunks for $mediaType msg $msgId")
        
        // Get or create buffer
        val buffer = reassemblyBuffers.getOrPut(msgId) {
            ChunkBuffer(
                totalChunks = totalChunks,
                mediaType = mediaType,
                chunks = arrayOfNulls(totalChunks)
            )
        }
        
        // Store chunk (skip duplicates)
        if (chunkIdx < buffer.chunks.size && buffer.chunks[chunkIdx] == null) {
            buffer.chunks[chunkIdx] = chunkData
            buffer.receivedCount++
        }
        
        // Check if complete
        if (buffer.isComplete()) {
            val fullData = buffer.getFullData()
            reassemblyBuffers.remove(msgId)
            Log.d(TAG, "Reassembly complete for $mediaType msg $msgId (${fullData.length} chars)")
            return Triple(msgId, mediaType, fullData)
        }
        
        return null
    }
    
    /**
     * Cleans up stale reassembly buffers (older than 60 seconds).
     */
    fun cleanupStaleBuffers() {
        val threshold = System.currentTimeMillis() - 60_000
        val stale = reassemblyBuffers.filter { it.value.createdAt < threshold }
        stale.forEach { (key, _) ->
            reassemblyBuffers.remove(key)
            Log.d(TAG, "Cleaned up stale buffer: $key")
        }
    }
    
    /**
     * Gets the reassembly progress for a given message ID.
     * @return Pair of (received, total) or null if no buffer exists.
     */
    fun getProgress(msgId: String): Pair<Int, Int>? {
        val buffer = reassemblyBuffers[msgId] ?: return null
        return Pair(buffer.receivedCount, buffer.totalChunks)
    }
}
