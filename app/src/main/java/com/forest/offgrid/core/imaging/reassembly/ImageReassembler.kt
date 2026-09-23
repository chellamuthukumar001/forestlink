package com.forest.offgrid.core.imaging.reassembly

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.forest.offgrid.core.imaging.chunking.ImageChunk
import com.forest.offgrid.core.imaging.chunking.ProgressiveChunker
import com.forest.offgrid.core.imaging.fec.ReedSolomonErasureCoder
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Reassembles incoming chunks by imageId, tracking progress, generating rough previews,
 * and performing early Reed-Solomon FEC erasure recovery at >= K shards.
 */
class ImageReassembler {

    companion object {
        private const val TAG = "ImageReassembler"
    }

    data class SessionState(
        val imageId: Int,
        val totalShards: Int,
        val dataShards: Int,
        val receivedShards: ConcurrentHashMap<Int, ByteArray> = ConcurrentHashMap(),
        val isGrayscale: Boolean,
        val isJpeg: Boolean,
        val createdAt: Long = System.currentTimeMillis(),
        @Volatile var isCompleted: Boolean = false,
        @Volatile var previewBitmap: Bitmap? = null
    ) {
        val progress: Float
            get() = (receivedShards.size.toFloat() / dataShards.toFloat()).coerceIn(0f, 1f)
    }

    sealed class ReassemblyEvent {
        data class Progress(
            val imageId: Int,
            val receivedCount: Int,
            val dataShardsRequired: Int,
            val totalShards: Int,
            val progress: Float,
            val previewBitmap: Bitmap?
        ) : ReassemblyEvent()

        data class Completed(
            val imageId: Int,
            val reconstructedBytes: ByteArray,
            val isGrayscale: Boolean,
            val isJpeg: Boolean,
            val receivedShardsCount: Int,
            val dataShards: Int,
            val parityShardsUsed: Int
        ) : ReassemblyEvent()

        data class Error(val imageId: Int, val message: String) : ReassemblyEvent()
    }

    private val sessions = ConcurrentHashMap<Int, SessionState>()

    private val _events = MutableSharedFlow<ReassemblyEvent>(replay = 1)
    val events: SharedFlow<ReassemblyEvent> = _events

    /**
     * Ingests an incoming ImageChunk.
     * @return Completed reconstructed byte array if this chunk satisfied recovery, or null.
     */
    suspend fun processChunk(chunk: ImageChunk): ByteArray? {
        val header = chunk.header
        val imageId = header.imageId

        cleanOldSessions()

        val session = sessions.getOrPut(imageId) {
            SessionState(
                imageId = imageId,
                totalShards = header.totalShards,
                dataShards = header.dataShards,
                isGrayscale = header.isGrayscale,
                isJpeg = header.isJpeg
            )
        }

        if (session.isCompleted) {
            return null // Already recovered
        }

        // Store shard (ignore duplicate sequence numbers)
        val shardSize = ProgressiveChunker.CHUNK_PAYLOAD_SIZE
        val uniformPayload = if (chunk.payload.size == shardSize) {
            chunk.payload
        } else {
            // Pad to uniform shard size for RS matrix decoding
            val padded = ByteArray(shardSize)
            System.arraycopy(chunk.payload, 0, padded, 0, chunk.payload.size)
            padded
        }

        session.receivedShards.putIfAbsent(header.sequenceNumber, uniformPayload)

        val receivedCount = session.receivedShards.size
        val k = session.dataShards

        // 1. Try progressive preview generation if not yet complete
        if (session.previewBitmap == null && session.receivedShards.containsKey(0)) {
            tryGeneratePreview(session)
        }

        // 2. Emit progress event
        _events.emit(
            ReassemblyEvent.Progress(
                imageId = imageId,
                receivedCount = receivedCount,
                dataShardsRequired = k,
                totalShards = session.totalShards,
                progress = session.progress,
                previewBitmap = session.previewBitmap
            )
        )

        // 3. Early FEC Recovery: Check if we have >= K shards
        if (receivedCount >= k && !session.isCompleted) {
            val m = session.totalShards - k
            val coder = ReedSolomonErasureCoder(dataShards = k, parityShards = m)

            val recoveredShards = coder.decode(session.receivedShards, shardSize)
            if (recoveredShards != null) {
                session.isCompleted = true

                // Concatenate recovered shards into full original byte array
                val reconstructedBytes = assembleFullBytes(recoveredShards, session)

                val parityUsed = session.receivedShards.keys.count { it >= k }

                _events.emit(
                    ReassemblyEvent.Completed(
                        imageId = imageId,
                        reconstructedBytes = reconstructedBytes,
                        isGrayscale = session.isGrayscale,
                        isJpeg = session.isJpeg,
                        receivedShardsCount = receivedCount,
                        dataShards = k,
                        parityShardsUsed = parityUsed
                    )
                )

                Log.d(TAG, "Image $imageId fully reconstructed with $receivedCount/$k shards (Parity used: $parityUsed)")
                return reconstructedBytes
            }
        }

        return null
    }

    /**
     * Assembles reconstructed shards into exact compressed image bytes by stripping padding.
     */
    private fun assembleFullBytes(shards: Array<ByteArray>, session: SessionState): ByteArray {
        val out = ByteArrayOutputStream()
        for (i in 0 until session.dataShards) {
            out.write(shards[i])
        }
        val rawCombined = out.toByteArray()

        // Strip trailing zero-padding if WebP / JPEG has known container termination
        return stripTrailingPadding(rawCombined, session.isJpeg)
    }

    private fun stripTrailingPadding(bytes: ByteArray, isJpeg: Boolean): ByteArray {
        if (isJpeg) {
            // JPEG ends with EOI marker 0xFF 0xD9
            for (i in bytes.size - 2 downTo 0) {
                if (bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xD9.toByte()) {
                    return bytes.copyOfRange(0, i + 2)
                }
            }
        } else {
            // WebP container header: bytes 4..7 indicate total file size - 8 in Little Endian
            if (bytes.size >= 8 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()
            ) {
                val riffSize = (bytes[4].toInt() and 0xFF) or
                        ((bytes[5].toInt() and 0xFF) shl 8) or
                        ((bytes[6].toInt() and 0xFF) shl 16) or
                        ((bytes[7].toInt() and 0xFF) shl 24)
                val expectedTotalSize = riffSize + 8
                if (expectedTotalSize in 8..bytes.size) {
                    return bytes.copyOfRange(0, expectedTotalSize)
                }
            }
        }
        // Fallback: trim trailing zeroes
        var lastNonZero = bytes.size - 1
        while (lastNonZero > 0 && bytes[lastNonZero] == 0.toByte()) {
            lastNonZero--
        }
        return bytes.copyOfRange(0, lastNonZero + 1)
    }

    private fun tryGeneratePreview(session: SessionState) {
        try {
            // If shard 0 is available, assemble what we have so far
            val out = ByteArrayOutputStream()
            for (i in 0 until session.dataShards) {
                val shard = session.receivedShards[i] ?: break
                out.write(shard)
            }
            val partialBytes = out.toByteArray()
            if (partialBytes.size >= 300) {
                val bitmap = BitmapFactory.decodeByteArray(partialBytes, 0, partialBytes.size)
                if (bitmap != null) {
                    session.previewBitmap = bitmap
                }
            }
        } catch (_: Exception) {
            // Partial decode may fail gracefully until sufficient bitstream arrives
        }
    }

    private fun cleanOldSessions() {
        val now = System.currentTimeMillis()
        val timeout = 10 * 60 * 1000 // 10 minutes
        val iter = sessions.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (now - entry.value.createdAt > timeout) {
                iter.remove()
            }
        }
    }

    fun getSession(imageId: Int): SessionState? = sessions[imageId]
}
