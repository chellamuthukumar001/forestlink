package com.forest.offgrid.core.imaging.api

import android.graphics.Bitmap

/**
 * Lifecycle stages of an off-grid photo transfer.
 */
enum class ImageStage {
    IDLE,
    COMPRESSING,
    CHUNKING,
    TRANSMITTING,
    REASSEMBLING,
    SUPER_RESOLVING,
    COMPLETED,
    FAILED
}

/**
 * Real-time progress and telemetry model for sending or receiving an off-grid image.
 */
data class ImageTransferState(
    val imageId: Int,
    val isIncoming: Boolean,
    val stage: ImageStage = ImageStage.IDLE,
    val progress: Float = 0f,
    val currentShard: Int = 0,
    val dataShards: Int = 0,
    val totalShards: Int = 0,
    val isGrayscale: Boolean = false,
    val previewBitmap: Bitmap? = null,
    val completedBitmap: Bitmap? = null,
    val savedFilePath: String? = null,
    val originalSizeBytes: Long = 0L,
    val compressedSizeBytes: Int = 0,
    val estimatedTimeSeconds: Int = 0,
    val errorMessage: String? = null
) {
    val isFinished: Boolean get() = stage == ImageStage.COMPLETED || stage == ImageStage.FAILED
    val progressPercentage: Int get() = (progress * 100).toInt().coerceIn(0, 100)
}
