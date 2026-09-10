package com.forest.offgrid.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String, // Encrypted or Plain text for Text msgs
    val senderId: String,
    val receiverId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isIncoming: Boolean,
    val type: MessageType = MessageType.TEXT,
    val status: MessageStatus = MessageStatus.PENDING,
    
    // Environmental sensor data
    val temperature: Float? = null, // Temperature in Celsius
    val humidity: Float? = null, // Humidity percentage
    
    // Media fields for Voice/Image messages
    val mediaFilePath: String? = null, // Local file path for voice/image
    val mediaDuration: Int? = null, // Duration in seconds (voice messages)
    val mediaSize: Long? = null // File size in bytes
)

enum class MessageType {
    TEXT, SOS, LOCATION, VOICE, IMAGE
}

enum class MessageStatus {
    PENDING, SENDING, SENT, DELIVERED, FAILED
}
