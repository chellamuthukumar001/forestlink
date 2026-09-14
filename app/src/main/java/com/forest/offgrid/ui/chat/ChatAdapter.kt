package com.forest.offgrid.ui.chat

import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.forest.offgrid.R
import com.forest.offgrid.data.model.Message
import com.forest.offgrid.data.model.MessageStatus
import com.forest.offgrid.data.model.MessageType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    private val messages = mutableListOf<Message>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    
    // Track currently playing audio
    private var mediaPlayer: MediaPlayer? = null
    private var currentlyPlayingId: Long = -1L
    
    private var attachedRecyclerView: RecyclerView? = null
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            mediaPlayer?.let { player ->
                if (player.isPlaying && currentlyPlayingId != -1L) {
                    val duration = player.duration.toFloat()
                    if (duration > 0f) {
                        val progressPercent = player.currentPosition.toFloat() / duration
                        val pos = messages.indexOfFirst { it.id == currentlyPlayingId }
                        if (pos != -1) {
                            attachedRecyclerView?.findViewHolderForAdapterPosition(pos)?.let { holder ->
                                if (holder is MessageViewHolder) {
                                    holder.updateProgress(progressPercent)
                                }
                            }
                        }
                    }
                    progressHandler.postDelayed(this, 50) // smooth update
                }
            }
        }
    }

    fun submitList(newMessages: List<Message>) {
        val oldList = ArrayList(messages)
        val diffCallback = object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldList.size
            override fun getNewListSize(): Int = newMessages.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldList[oldItemPosition].id == newMessages[newItemPosition].id
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldList[oldItemPosition] == newMessages[newItemPosition]
            }
        }
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        messages.clear()
        messages.addAll(newMessages)
        diffResult.dispatchUpdatesTo(this)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        attachedRecyclerView = null
        stopProgressTracker()
    }

    private fun startProgressTracker() {
        progressHandler.removeCallbacks(progressRunnable)
        progressHandler.post(progressRunnable)
    }

    private fun stopProgressTracker() {
        progressHandler.removeCallbacks(progressRunnable)
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isIncoming) 1 else 0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val layout = if (viewType == 0) R.layout.item_message_sent else R.layout.item_message_received
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun getItemCount() = messages.size

    inner class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textContent: TextView = itemView.findViewById(R.id.text_content)
        private val textTime: TextView = itemView.findViewById(R.id.text_time)
        private val imgStatus: ImageView? = itemView.findViewById(R.id.img_status)
        private val textSender: TextView? = itemView.findViewById(R.id.text_sender)
        
        // Media views
        private val imgMedia: ImageView? = itemView.findViewById(R.id.img_media)
        private val layoutVoice: LinearLayout? = itemView.findViewById(R.id.layout_voice)
        private val btnPlayVoice: ImageButton? = itemView.findViewById(R.id.btn_play_voice)
        private val textVoiceDuration: TextView? = itemView.findViewById(R.id.text_voice_duration)
        private val voiceWaveform: VoiceWaveformView? = itemView.findViewById(R.id.voice_waveform)
        
        // Sensor data views
        private val layoutSensorData: View? = itemView.findViewById(R.id.layout_sensor_data)
        private val textTemperature: TextView? = itemView.findViewById(R.id.text_temperature)
        private val textHumidity: TextView? = itemView.findViewById(R.id.text_humidity)

        fun updateProgress(progressPercent: Float) {
            voiceWaveform?.setProgress(progressPercent)
        }

        fun bind(message: Message) {
            textTime.text = timeFormat.format(Date(message.timestamp))
            textSender?.text = message.senderId
            
            // Reset all media views to default state
            textContent.visibility = View.GONE
            imgMedia?.visibility = View.GONE
            layoutVoice?.visibility = View.GONE
            
            when (message.type) {
                MessageType.IMAGE -> {
                    bindImageMessage(message)
                }
                MessageType.VOICE -> {
                    bindVoiceMessage(message)
                }
                else -> {
                    bindTextMessage(message)
                }
            }

            // Display sensor data if available
            if (message.temperature != null || message.humidity != null) {
                layoutSensorData?.visibility = View.VISIBLE
                
                message.temperature?.let { temp ->
                    textTemperature?.text = String.format("%.1f°C", temp)
                }
                
                message.humidity?.let { humidity ->
                    textHumidity?.text = String.format("%.0f%%", humidity)
                }
            } else {
                layoutSensorData?.visibility = View.GONE
            }

            // Status indicator (sent messages only)
            imgStatus?.let {
                it.visibility = View.VISIBLE
                when (message.status) {
                    MessageStatus.SENDING -> {
                        it.setImageResource(android.R.drawable.presence_away)
                        it.setColorFilter(itemView.resources.getColor(R.color.status_queued, null))
                    }
                    MessageStatus.SENT -> {
                        it.setImageResource(android.R.drawable.checkbox_on_background)
                        it.setColorFilter(itemView.resources.getColor(R.color.status_sent, null))
                    }
                    MessageStatus.DELIVERED -> {
                        it.setImageResource(android.R.drawable.checkbox_on_background)
                        it.setColorFilter(itemView.resources.getColor(R.color.status_ack, null))
                    }
                    else -> it.visibility = View.GONE
                }
            }
        }
        
        private fun bindTextMessage(message: Message) {
            textContent.visibility = View.VISIBLE
            textContent.text = message.content
        }
        
        private fun bindImageMessage(message: Message) {
            textContent.visibility = View.VISIBLE
            textContent.text = message.content
            
            imgMedia?.let { imageView ->
                val filePath = message.mediaFilePath
                if (filePath != null && File(filePath).exists()) {
                    try {
                        val bitmap = BitmapFactory.decodeFile(filePath)
                        if (bitmap != null) {
                            imageView.setImageBitmap(bitmap)
                            imageView.visibility = View.VISIBLE
                            textContent.visibility = View.GONE
                            
                            // Tapping image opens fullscreen preview
                            imageView.setOnClickListener {
                                showImagePreviewDialog(itemView.context, filePath)
                            }
                        }
                    } catch (e: Exception) {
                        textContent.visibility = View.VISIBLE
                        textContent.text = "📷 [Image file not found]"
                    }
                } else {
                    textContent.visibility = View.VISIBLE
                    textContent.text = "📷 [Image loading...]"
                }
            }
        }
        
        private fun bindVoiceMessage(message: Message) {
            textContent.visibility = View.VISIBLE
            textContent.text = message.content
            
            layoutVoice?.let { voiceLayout ->
                val filePath = message.mediaFilePath
                if (filePath != null && File(filePath).exists()) {
                    voiceLayout.visibility = View.VISIBLE
                    textContent.visibility = View.GONE
                    
                    // Setup waveform rendering
                    voiceWaveform?.setMessageId(message.id)
                    val isIncoming = message.isIncoming
                    val activeColor = if (isIncoming) Color.parseColor("#00D4FF") else Color.parseColor("#39FF14")
                    val inactiveColor = if (isIncoming) Color.argb(60, 0, 212, 255) else Color.argb(60, 57, 255, 20)
                    voiceWaveform?.setColors(activeColor, inactiveColor)
                    
                    // Restore progress if currently playing
                    if (currentlyPlayingId == message.id && mediaPlayer?.isPlaying == true) {
                        val progressPercent = mediaPlayer?.let { it.currentPosition.toFloat() / it.duration } ?: 0f
                        voiceWaveform?.setProgress(progressPercent)
                    } else {
                        voiceWaveform?.setProgress(0f)
                    }
                    
                    // Format duration
                    val duration = message.mediaDuration ?: 0
                    val minutes = duration / 60
                    val seconds = duration % 60
                    textVoiceDuration?.text = String.format("%d:%02d", minutes, seconds)
                    
                    // Update play/pause icon
                    val isPlaying = currentlyPlayingId == message.id
                    btnPlayVoice?.setImageResource(
                        if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                    )
                    
                    btnPlayVoice?.setOnClickListener {
                        if (currentlyPlayingId == message.id && mediaPlayer?.isPlaying == true) {
                            stopPlayback()
                        } else {
                            playVoice(message.id, filePath)
                        }
                    }
                } else {
                    textContent.visibility = View.VISIBLE
                    textContent.text = "🎤 [Voice file not found]"
                }
            }
        }
        
        private fun playVoice(messageId: Long, filePath: String) {
            stopPlayback()
            try {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(filePath)
                    prepare()
                    start()
                    
                    setOnCompletionListener {
                        currentlyPlayingId = -1L
                        btnPlayVoice?.setImageResource(R.drawable.ic_play)
                        voiceWaveform?.setProgress(0f)
                        release()
                        mediaPlayer = null
                        stopProgressTracker()
                        notifyDataSetChanged()
                    }
                }
                currentlyPlayingId = messageId
                btnPlayVoice?.setImageResource(R.drawable.ic_pause)
                startProgressTracker()
            } catch (e: Exception) {
                android.util.Log.e("ChatAdapter", "Playback error: ${e.message}")
            }
        }
        
        private fun stopPlayback() {
            try {
                mediaPlayer?.let {
                    if (it.isPlaying) it.stop()
                    it.release()
                }
            } catch (e: Exception) { /* ignore */ }
            mediaPlayer = null
            currentlyPlayingId = -1L
            stopProgressTracker()
            notifyDataSetChanged()
        }
    }
    
    private fun showImagePreviewDialog(context: android.content.Context, filePath: String) {
        val dialog = android.app.Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_image_preview)
        
        val imageView = dialog.findViewById<ImageView>(R.id.img_preview)
        val btnClose = dialog.findViewById<View>(R.id.btn_close_preview)
        
        try {
            val bitmap = BitmapFactory.decodeFile(filePath)
            imageView.setImageBitmap(bitmap)
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "Error loading full image", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        
        btnClose.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }
    
    fun releasePlayer() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (e: Exception) { /* ignore */ }
        mediaPlayer = null
        currentlyPlayingId = -1L
        stopProgressTracker()
    }
}
