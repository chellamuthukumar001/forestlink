package com.forest.offgrid.core.imaging.api

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.forest.offgrid.core.imaging.capture.ImageCaptureCompressor
import com.forest.offgrid.core.imaging.chunking.ImageChunk
import com.forest.offgrid.core.imaging.chunking.ImageChunkHeader
import com.forest.offgrid.core.imaging.chunking.ProgressiveChunker
import com.forest.offgrid.core.imaging.reassembly.ImageReassembler
import com.forest.offgrid.core.imaging.superres.SuperResolutionEngine
import com.forest.offgrid.data.ble.BleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * High-level coordinator for low-bandwidth LoRa photo transfers.
 * Bridges Capture/Compression, FEC Chunking, BLE/LoRa Transceiver, Reassembly, and Super-Resolution.
 */
class ImagingPipeline private constructor(
    private val context: Context,
    private val bleManager: BleManager
) {
    companion object {
        private const val TAG = "ImagingPipeline"

        @Volatile
        private var instance: ImagingPipeline? = null

        fun getInstance(context: Context, bleManager: BleManager): ImagingPipeline {
            return instance ?: synchronized(this) {
                instance ?: ImagingPipeline(context.applicationContext, bleManager).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val superResEngine = SuperResolutionEngine.getInstance(context)
    private val reassembler = ImageReassembler()

    private val idCounter = AtomicInteger((System.currentTimeMillis() and 0x7FFF).toInt())

    private val _transferStates = MutableStateFlow<Map<Int, ImageTransferState>>(emptyMap())
    val transferStates: StateFlow<Map<Int, ImageTransferState>> = _transferStates.asStateFlow()

    private val _completedTransfers = MutableSharedFlow<ImageTransferState>(replay = 1)
    val completedTransfers: SharedFlow<ImageTransferState> = _completedTransfers.asSharedFlow()

    private val transferMap = ConcurrentHashMap<Int, ImageTransferState>()

    init {
        // Listen to reassembly events from incoming chunks
        scope.launch {
            reassembler.events.collect { event ->
                handleReassemblyEvent(event)
            }
        }

        // Listen to raw binary packets arriving over BLE
        scope.launch {
            bleManager.rawBytesReceived.collect { rawBytes ->
                processIncomingRawPacket(rawBytes)
            }
        }
    }

    /**
     * Processes an incoming raw packet. If it starts with Magic 0xA1, it is routed to imaging pipeline.
     */
    fun processIncomingRawPacket(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes[0] != ImageChunkHeader.MAGIC_VERSION) {
            return false
        }

        val chunk = ImageChunk.fromWireBytes(bytes) ?: run {
            Log.w(TAG, "Received packet with magic 0xA1 but header or CRC16 check failed")
            return false
        }

        scope.launch {
            reassembler.processChunk(chunk)
        }
        return true
    }

    private suspend fun handleReassemblyEvent(event: ImageReassembler.ReassemblyEvent) {
        when (event) {
            is ImageReassembler.ReassemblyEvent.Progress -> {
                val state = transferMap.getOrPut(event.imageId) {
                    ImageTransferState(
                        imageId = event.imageId,
                        isIncoming = true,
                        dataShards = event.dataShardsRequired,
                        totalShards = event.totalShards
                    )
                }.copy(
                    stage = ImageStage.REASSEMBLING,
                    progress = event.progress,
                    currentShard = event.receivedCount,
                    previewBitmap = event.previewBitmap ?: transferMap[event.imageId]?.previewBitmap
                )

                updateTransferState(event.imageId, state)
            }

            is ImageReassembler.ReassemblyEvent.Completed -> {
                val current = transferMap[event.imageId]
                val recoveringState = (current ?: ImageTransferState(
                    imageId = event.imageId,
                    isIncoming = true
                )).copy(
                    stage = ImageStage.SUPER_RESOLVING,
                    progress = 1.0f,
                    currentShard = event.receivedShardsCount
                )
                updateTransferState(event.imageId, recoveringState)

                withContext(Dispatchers.Default) {
                    try {
                        // 1. Decode reconstructed bytes into baseline Bitmap
                        val baselineBitmap = BitmapFactory.decodeByteArray(
                            event.reconstructedBytes,
                            0,
                            event.reconstructedBytes.size
                        )

                        if (baselineBitmap != null) {
                            // 2. Perform 4x Super-Resolution
                            val srBitmap = superResEngine.upscale(baselineBitmap, cacheKey = "sr_${event.imageId}")

                            // 3. Save to disk cache
                            val mediaDir = File(context.filesDir, "media")
                            if (!mediaDir.exists()) mediaDir.mkdirs()
                            val destFile = File(mediaDir, "lora_sr_${event.imageId}_${System.currentTimeMillis()}.webp")
                            FileOutputStream(destFile).use { fos ->
                                srBitmap.compress(Bitmap.CompressFormat.WEBP, 85, fos)
                            }

                            val completedState = recoveringState.copy(
                                stage = ImageStage.COMPLETED,
                                completedBitmap = srBitmap,
                                savedFilePath = destFile.absolutePath,
                                compressedSizeBytes = event.reconstructedBytes.size
                            )
                            updateTransferState(event.imageId, completedState)
                            _completedTransfers.emit(completedState)
                            Log.i(TAG, "Super-Resolution complete for image ${event.imageId}: ${srBitmap.width}x${srBitmap.height}")
                        } else {
                            val errorState = recoveringState.copy(
                                stage = ImageStage.FAILED,
                                errorMessage = "Failed to decode reconstructed image bytes"
                            )
                            updateTransferState(event.imageId, errorState)
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "Error in SR pipeline for image ${event.imageId}", e)
                        val errorState = recoveringState.copy(
                            stage = ImageStage.FAILED,
                            errorMessage = e.message ?: "Super-resolution error"
                        )
                        updateTransferState(event.imageId, errorState)
                    }
                }
            }

            is ImageReassembler.ReassemblyEvent.Error -> {
                val state = transferMap[event.imageId]?.copy(
                    stage = ImageStage.FAILED,
                    errorMessage = event.message
                ) ?: ImageTransferState(
                    imageId = event.imageId,
                    isIncoming = true,
                    stage = ImageStage.FAILED,
                    errorMessage = event.message
                )
                updateTransferState(event.imageId, state)
            }
        }
    }

    /**
     * Sends an image from a content URI over LoRa via BLE.
     *
     * @param uri The image URI (gallery or camera).
     * @param isGrayscale Toggle 3x smaller grayscale mode.
     * @param quality WebP compression quality (30..40).
     * @param spreadingFactor LoRa spreading factor (default 7 for speed estimates).
     */
    suspend fun sendPhoto(
        uri: Uri,
        isGrayscale: Boolean = false,
        quality: Int = ImageCaptureCompressor.DEFAULT_QUALITY,
        spreadingFactor: Int = 7
    ): Result<Int> = withContext(Dispatchers.IO) {
        val imageId = idCounter.incrementAndGet() and 0xFFFF

        var currentState = ImageTransferState(
            imageId = imageId,
            isIncoming = false,
            stage = ImageStage.COMPRESSING,
            isGrayscale = isGrayscale
        )
        updateTransferState(imageId, currentState)

        // 1. Capture & Compress to 320x240 WebP (with fallback to JPEG)
        val compressionResult = ImageCaptureCompressor.compressFromUri(
            context = context,
            uri = uri,
            targetWidth = ImageCaptureCompressor.DEFAULT_TARGET_WIDTH,
            targetHeight = ImageCaptureCompressor.DEFAULT_TARGET_HEIGHT,
            isGrayscale = isGrayscale,
            quality = quality
        )

        if (compressionResult == null) {
            val failed = currentState.copy(stage = ImageStage.FAILED, errorMessage = "Failed to compress image")
            updateTransferState(imageId, failed)
            return@withContext Result.failure(Exception("Failed to compress image"))
        }

        currentState = currentState.copy(
            stage = ImageStage.CHUNKING,
            originalSizeBytes = compressionResult.originalSizeBytes,
            compressedSizeBytes = compressionResult.compressedSizeBytes
        )
        updateTransferState(imageId, currentState)

        // 2. Chunk and generate Reed-Solomon Parity (10:3 FEC ratio)
        val chunks = ProgressiveChunker.createChunks(
            imageId = imageId,
            compressedData = compressionResult.bytes,
            isGrayscale = isGrayscale,
            isJpeg = compressionResult.isJpeg,
            fecParityRatio = 0.3
        )

        val totalShards = chunks.size
        val dataShards = chunks.firstOrNull()?.header?.dataShards ?: totalShards
        val estimatedSeconds = estimateAirtimeSeconds(totalShards, spreadingFactor)

        currentState = currentState.copy(
            stage = ImageStage.TRANSMITTING,
            dataShards = dataShards,
            totalShards = totalShards,
            estimatedTimeSeconds = estimatedSeconds
        )
        updateTransferState(imageId, currentState)

        // 3. Serialize to wire bytes and queue in BleManager
        val wirePackets = chunks.map { it.toWireBytes() }
        bleManager.sendChunkedRawBytes(wirePackets)

        currentState = currentState.copy(
            stage = ImageStage.COMPLETED,
            progress = 1.0f,
            currentShard = totalShards
        )
        updateTransferState(imageId, currentState)
        _completedTransfers.emit(currentState)

        Log.i(TAG, "Queued photo $imageId ($totalShards packets, ${compressionResult.compressedSizeBytes} bytes, est: ${estimatedSeconds}s)")
        return@withContext Result.success(imageId)
    }

    /**
     * Estimates transmission duration over LoRa based on packet count and spreading factor.
     */
    fun estimateAirtimeSeconds(totalPackets: Int, spreadingFactor: Int = 7): Int {
        // Approximate time-on-air per 193-byte packet at 125kHz bandwidth:
        // SF7: ~150ms per packet
        // SF8: ~260ms per packet
        // SF9: ~480ms per packet
        // SF10: ~950ms per packet
        // SF11: ~1800ms per packet
        // SF12: ~3400ms per packet
        val msPerPacket = when (spreadingFactor) {
            7 -> 150
            8 -> 260
            9 -> 480
            10 -> 950
            11 -> 1800
            12 -> 3400
            else -> 200
        }
        val totalMs = totalPackets * (msPerPacket + 50) // Include BLE write queue pacing
        return (totalMs / 1000).coerceAtLeast(1)
    }

    private fun updateTransferState(imageId: Int, state: ImageTransferState) {
        transferMap[imageId] = state
        _transferStates.value = transferMap.toMap()
    }

    fun getTransferState(imageId: Int): ImageTransferState? = transferMap[imageId]
}
