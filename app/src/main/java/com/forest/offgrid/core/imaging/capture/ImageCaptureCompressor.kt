package com.forest.offgrid.core.imaging.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Image capture and aggressive compression engine designed for LoRa mesh transmission.
 *
 * Capabilities:
 * - Downsamples image to 320x240 (or custom target dimensions preserving aspect ratio).
 * - Optional grayscale mode: reduces payload ~3x by discarding color saturation.
 * - Encodes using WebP (quality 30-40) with automatic fallback to JPEG.
 * - Targets 2-8 KB compressed output for rapid LoRa multi-hop transmission.
 */
object ImageCaptureCompressor {

    private const val TAG = "ImageCompressor"

    const val DEFAULT_TARGET_WIDTH = 320
    const val DEFAULT_TARGET_HEIGHT = 240
    const val DEFAULT_QUALITY = 35 // Quality range 30..40

    data class CompressionResult(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val isGrayscale: Boolean,
        val isJpeg: Boolean,
        val originalSizeBytes: Long
    ) {
        val compressedSizeBytes: Int get() = bytes.size

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as CompressionResult
            if (!bytes.contentEquals(other.bytes)) return false
            if (width != other.width) return false
            if (height != other.height) return false
            if (isGrayscale != other.isGrayscale) return false
            if (isJpeg != other.isJpeg) return false
            return true
        }

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + width
            result = 31 * result + height
            result = 31 * result + isGrayscale.hashCode()
            result = 31 * result + isJpeg.hashCode()
            return result
        }
    }

    /**
     * Compresses a Bitmap into an aggressive low-bandwidth WebP/JPEG payload.
     */
    fun compressBitmap(
        source: Bitmap,
        targetWidth: Int = DEFAULT_TARGET_WIDTH,
        targetHeight: Int = DEFAULT_TARGET_HEIGHT,
        isGrayscale: Boolean = false,
        quality: Int = DEFAULT_QUALITY
    ): CompressionResult {
        // 1. Calculate aspect-ratio scaling
        val (scaledW, scaledH) = calculateScaledDimensions(source.width, source.height, targetWidth, targetHeight)
        val scaled = Bitmap.createScaledBitmap(source, scaledW, scaledH, true)

        // 2. Apply Grayscale conversion if requested
        val processedBitmap = if (isGrayscale) {
            toGrayscale(scaled).also {
                if (it != scaled) scaled.recycle()
            }
        } else {
            scaled
        }

        // 3. Compress to WebP (with fallback to JPEG)
        var isJpeg = false
        val stream = ByteArrayOutputStream()

        val webpFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }

        val webpSuccess = processedBitmap.compress(webpFormat, quality.coerceIn(10, 100), stream)
        val finalBytes = if (webpSuccess && stream.size() > 0) {
            stream.toByteArray()
        } else {
            // Fallback to JPEG
            Log.w(TAG, "WebP encoding unavailable or failed, falling back to JPEG")
            isJpeg = true
            stream.reset()
            processedBitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 100), stream)
            stream.toByteArray()
        }

        if (processedBitmap != source) {
            processedBitmap.recycle()
        }

        Log.d(TAG, "Compressed: ${finalBytes.size} bytes (${scaledW}x${scaledH}, Gray=$isGrayscale, JPEG=$isJpeg)")

        return CompressionResult(
            bytes = finalBytes,
            width = scaledW,
            height = scaledH,
            isGrayscale = isGrayscale,
            isJpeg = isJpeg,
            originalSizeBytes = (source.width * source.height * 4).toLong()
        )
    }

    /**
     * Compresses an image from an Android content Uri.
     */
    fun compressFromUri(
        context: Context,
        uri: Uri,
        targetWidth: Int = DEFAULT_TARGET_WIDTH,
        targetHeight: Int = DEFAULT_TARGET_HEIGHT,
        isGrayscale: Boolean = false,
        quality: Int = DEFAULT_QUALITY
    ): CompressionResult? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }

            options.inSampleSize = calculateInSampleSize(options.outWidth, options.outHeight, targetWidth, targetHeight)
            options.inJustDecodeBounds = false

            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null

            compressBitmap(bitmap, targetWidth, targetHeight, isGrayscale, quality).also {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compress from URI: ${e.message}", e)
            null
        }
    }

    /**
     * Converts a Bitmap to Grayscale.
     */
    private fun toGrayscale(bmpOriginal: Bitmap): Bitmap {
        val width = bmpOriginal.width
        val height = bmpOriginal.height
        val bmpGrayscale = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        val c = Canvas(bmpGrayscale)
        val paint = Paint()
        val cm = ColorMatrix()
        cm.setSaturation(0f)
        val f = ColorMatrixColorFilter(cm)
        paint.colorFilter = f
        c.drawBitmap(bmpOriginal, 0f, 0f, paint)
        return bmpGrayscale
    }

    private fun calculateScaledDimensions(srcW: Int, srcH: Int, maxW: Int, maxH: Int): Pair<Int, Int> {
        val ratio = minOf(maxW.toFloat() / srcW, maxH.toFloat() / srcH)
        val targetW = (srcW * ratio).toInt().coerceAtLeast(1)
        val targetH = (srcH * ratio).toInt().coerceAtLeast(1)
        return Pair(targetW, targetH)
    }

    private fun calculateInSampleSize(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        var inSampleSize = 1
        if (srcH > reqH || srcW > reqW) {
            val halfH = srcH / 2
            val halfW = srcW / 2
            while ((halfH / inSampleSize) >= reqH && (halfW / inSampleSize) >= reqW) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
