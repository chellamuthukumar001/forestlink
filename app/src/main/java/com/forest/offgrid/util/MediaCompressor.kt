package com.forest.offgrid.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Compresses media files for efficient BLE/LoRa transmission.
 * 
 * LoRa has extremely limited bandwidth (~250 bytes/packet), so media must be
 * aggressively compressed:
 * - Images: downscaled to 160x120, JPEG quality 25 → ~2-8 KB
 * - Voice: already recorded at low bitrate by AudioRecorderHelper
 */
object MediaCompressor {

    private const val TAG = "MediaCompressor"
    
    // Image compression settings (extremely aggressive for LoRa)
    private const val MAX_IMAGE_WIDTH = 160
    private const val MAX_IMAGE_HEIGHT = 120
    private const val JPEG_QUALITY = 25
    
    /**
     * Compresses an image from a URI to a tiny thumbnail suitable for LoRa transmission.
     * Output: ~2-8 KB JPEG file
     */
    fun compressImage(uri: Uri, context: Context): File? {
        return try {
            // Read the original image with subsampling
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            
            // First pass: decode bounds only
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            val tempStream = context.contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(tempStream, null, options)
            tempStream?.close()
            
            // Calculate sample size for efficient memory use
            options.inSampleSize = calculateInSampleSize(options, MAX_IMAGE_WIDTH, MAX_IMAGE_HEIGHT)
            options.inJustDecodeBounds = false
            
            // Second pass: decode with subsampling
            val inputStream2 = context.contentResolver.openInputStream(uri) ?: return null
            val bitmap = BitmapFactory.decodeStream(inputStream2, null, options)
            inputStream2.close()
            inputStream.close()
            
            if (bitmap == null) {
                Log.e(TAG, "Failed to decode bitmap")
                return null
            }
            
            // Scale to target dimensions
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, MAX_IMAGE_WIDTH, MAX_IMAGE_HEIGHT, true)
            if (scaledBitmap != bitmap) {
                bitmap.recycle()
            }
            
            // Compress to JPEG
            val outputFile = File(context.cacheDir, "img_${System.currentTimeMillis()}.jpg")
            val fos = FileOutputStream(outputFile)
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)
            fos.flush()
            fos.close()
            scaledBitmap.recycle()
            
            Log.d(TAG, "Image compressed: ${outputFile.length()} bytes")
            outputFile
        } catch (e: Exception) {
            Log.e(TAG, "Image compression failed: ${e.message}")
            null
        }
    }
    
    /**
     * Saves a compressed image to a permanent location (not cache).
     */
    fun saveMediaFile(sourceFile: File, context: Context, prefix: String): File {
        val mediaDir = File(context.filesDir, "media")
        if (!mediaDir.exists()) mediaDir.mkdirs()
        
        val destFile = File(mediaDir, "${prefix}_${System.currentTimeMillis()}.${sourceFile.extension}")
        sourceFile.copyTo(destFile, overwrite = true)
        return destFile
    }
    
    /**
     * Encodes a file to Base64 string for BLE transmission.
     */
    fun fileToBase64(file: File): String {
        val bytes = file.readBytes()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
    
    /**
     * Decodes a Base64 string back to a file.
     */
    fun base64ToFile(base64Data: String, outputFile: File): File {
        val bytes = Base64.decode(base64Data, Base64.NO_WRAP)
        FileOutputStream(outputFile).use { it.write(bytes) }
        return outputFile
    }
    
    /**
     * Gets file size in a human-readable format.
     */
    fun getReadableFileSize(sizeBytes: Long): String {
        return when {
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
            else -> "${sizeBytes / (1024 * 1024)} MB"
        }
    }
    
    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1
        
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
