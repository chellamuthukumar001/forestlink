package com.forest.offgrid.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Helper to manage offline map assets bundled with the APK.
 * Copies .mbtiles or .zip files from assets/ to the device storage for Osmdroid.
 */
object MapAssetsManager {
    private const val TAG = "MapAssetsManager"
    
    // Supported extensions for offline maps
    private val MAP_EXTENSIONS = listOf(".mbtiles", ".zip", ".sqlite", ".gemf")
    
    suspend fun checkAndCopyMapAssets(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                // Determine destination
                val osmdroidBasePath = File(context.getExternalFilesDir(null), "osmdroid")
                val archivesPath = File(osmdroidBasePath, "archives") 
                
                if (!archivesPath.exists()) {
                    archivesPath.mkdirs()
                }

                // 1. Find map file in assets
                val assetManager = context.assets
                val assets = assetManager.list("") ?: return@withContext
                
                val mapAsset = assets.firstOrNull { fileName -> 
                    MAP_EXTENSIONS.any { ext -> fileName.endsWith(ext, ignoreCase = true) }
                }

                if (mapAsset == null) {
                    Log.w(TAG, "No offline map file found in assets (looked for: $MAP_EXTENSIONS)")
                    return@withContext
                }

                val destFile = File(archivesPath, mapAsset)

                // 2. Check if map already exists in destination
                if (destFile.exists()) {
                    Log.d(TAG, "Offline map already exists at: ${destFile.absolutePath}")
                    // Optional: Compare sizes or versions here if update needed
                    return@withContext
                }

                Log.d(TAG, "Copying offline map '$mapAsset' from assets to: ${destFile.absolutePath}")
                
                // 3. Perform Copy
                copyAsset(context, mapAsset, destFile)
                
                Log.d(TAG, "Map asset copy successful!")

            } catch (e: Exception) {
                Log.e(TAG, "Error copying map assets: ${e.message}")
            }
        }
    }

    private fun copyAsset(context: Context, assetName: String, destFile: File) {
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null
        try {
            inputStream = context.assets.open(assetName)
            outputStream = FileOutputStream(destFile)
            
            val buffer = ByteArray(1024 * 8) // 8KB buffer
            var length: Int
            while (inputStream.read(buffer).also { length = it } > 0) {
                outputStream.write(buffer, 0, length)
            }
            outputStream.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to copy asset: $assetName", e)
        } finally {
            try {
                inputStream?.close()
                outputStream?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }
}
