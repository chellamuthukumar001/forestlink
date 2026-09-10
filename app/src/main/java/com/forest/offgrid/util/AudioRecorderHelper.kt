package com.forest.offgrid.util

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * Wraps MediaRecorder for voice message recording with off-grid constraints.
 * 
 * Records in low-bitrate AAC format (.m4a) to minimize file size for LoRa
 * transmission. Enforces a maximum recording duration (default 10 seconds).
 */
class AudioRecorderHelper(private val context: Context) {

    private val TAG = "AudioRecorderHelper"
    
    private var mediaRecorder: MediaRecorder? = null
    private var currentFilePath: String? = null
    private var isRecording = false
    private var startTime: Long = 0L
    
    private val handler = Handler(Looper.getMainLooper())
    
    // Max recording duration in milliseconds
    var maxDurationMs: Long = 10_000L // 10 seconds default
    
    // Callback for recording events
    var onRecordingComplete: ((filePath: String, durationSeconds: Int) -> Unit)? = null
    var onRecordingCancelled: (() -> Unit)? = null
    var onDurationUpdate: ((seconds: Int) -> Unit)? = null
    var onMaxDurationReached: (() -> Unit)? = null
    
    // Duration timer
    private val durationRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                val elapsed = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                onDurationUpdate?.invoke(elapsed)
                
                if (elapsed >= (maxDurationMs / 1000).toInt()) {
                    onMaxDurationReached?.invoke()
                    stopRecording()
                    return
                }
                handler.postDelayed(this, 500)
            }
        }
    }
    
    /**
     * Starts recording a voice message.
     * @return The file path where the recording is being saved, or null if failed.
     */
    fun startRecording(): String? {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return currentFilePath
        }
        
        try {
            val mediaDir = File(context.filesDir, "media")
            if (!mediaDir.exists()) mediaDir.mkdirs()
            
            val outputFile = File(mediaDir, "voice_${System.currentTimeMillis()}.m4a")
            currentFilePath = outputFile.absolutePath
            
            mediaRecorder = createMediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1) // Mono for smaller file
                setAudioSamplingRate(8000) // Low sample rate for LoRa
                setAudioEncodingBitRate(12000) // ~1.5 KB/s — very compact
                setOutputFile(currentFilePath)
                setMaxDuration(maxDurationMs.toInt())
                
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        Log.d(TAG, "Max duration reached by MediaRecorder")
                        stopRecording()
                    }
                }
                
                prepare()
                start()
            }
            
            isRecording = true
            startTime = System.currentTimeMillis()
            handler.post(durationRunnable)
            
            Log.d(TAG, "Recording started: $currentFilePath")
            return currentFilePath
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}")
            cleanupRecorder()
            return null
        }
    }
    
    /**
     * Stops recording and triggers the onRecordingComplete callback.
     */
    fun stopRecording() {
        if (!isRecording) return
        
        isRecording = false
        handler.removeCallbacks(durationRunnable)
        
        val durationSeconds = ((System.currentTimeMillis() - startTime) / 1000).toInt()
        
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recorder: ${e.message}")
        }
        mediaRecorder = null
        
        currentFilePath?.let { path ->
            val file = File(path)
            if (file.exists() && file.length() > 0) {
                Log.d(TAG, "Recording complete: $path (${durationSeconds}s, ${file.length()} bytes)")
                onRecordingComplete?.invoke(path, durationSeconds.coerceAtLeast(1))
            } else {
                Log.e(TAG, "Recording file is empty or missing")
                onRecordingCancelled?.invoke()
            }
        }
    }
    
    /**
     * Cancels the current recording and deletes the partial file.
     */
    fun cancelRecording() {
        if (!isRecording) return
        
        isRecording = false
        handler.removeCallbacks(durationRunnable)
        
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling recorder: ${e.message}")
        }
        mediaRecorder = null
        
        // Delete partial file
        currentFilePath?.let { path ->
            File(path).delete()
        }
        currentFilePath = null
        
        Log.d(TAG, "Recording cancelled")
        onRecordingCancelled?.invoke()
    }
    
    fun isCurrentlyRecording(): Boolean = isRecording
    
    fun getElapsedSeconds(): Int {
        return if (isRecording) {
            ((System.currentTimeMillis() - startTime) / 1000).toInt()
        } else 0
    }
    
    private fun cleanupRecorder() {
        try {
            mediaRecorder?.release()
        } catch (e: Exception) { /* ignore */ }
        mediaRecorder = null
        isRecording = false
        handler.removeCallbacks(durationRunnable)
    }
    
    @Suppress("DEPRECATION")
    private fun createMediaRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
    }
    
    fun release() {
        cancelRecording()
        handler.removeCallbacksAndMessages(null)
    }
}
