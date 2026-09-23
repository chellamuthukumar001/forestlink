package com.forest.offgrid.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.forest.offgrid.data.ble.BleManager
import com.forest.offgrid.data.model.HardwareState
import com.forest.offgrid.data.model.Message
import com.forest.offgrid.data.repository.MsgRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // Simple manual injection for now, in a real app use Hilt/Koin
    private val repository: MsgRepository

    init {
        // Initializing dependencies manually for simplicity
        val deviceBleManager = BleManager.getInstance(application)
        repository = MsgRepository.getInstance(application, deviceBleManager)
    }

    val allMessages: LiveData<List<Message>> = repository.allMessages
    
    val hardwareState: StateFlow<HardwareState> = repository.hardwareState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HardwareState())

    val scannedDevices = repository.scannedDevices
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val connectedDevice = repository.connectedDevice
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    /** Live countdown (seconds) until next reconnect attempt. -1 means not reconnecting. */
    val reconnectCountdown = BleManager.getInstance(application).reconnectCountdown
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), -1)

    /** Number of messages waiting in the BLE write queue. */
    val writeQueuePending = BleManager.getInstance(application).writeQueuePending

    /** Live off-grid image transfer & super-resolution pipeline states. */
    val photoTransferStates = repository.imagingPipeline.transferStates
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // Recording state for UI
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording
    
    private val _recordingDuration = MutableStateFlow(0)
    val recordingDuration: StateFlow<Int> = _recordingDuration

    fun sendMessage(content: String) {
        viewModelScope.launch {
            repository.sendMessage(content)
        }
    }

    fun sendSos() {
        viewModelScope.launch {
            repository.sendMessage("SOS! I NEED HELP!", isSos = true)
        }
    }
    
    /**
     * Sends a voice message from a recorded file.
     */
    fun sendVoiceMessage(filePath: String, durationSeconds: Int) {
        viewModelScope.launch {
            repository.sendVoiceMessage(filePath, durationSeconds)
        }
    }
    
    /**
     * Sends an image message from a content URI with optional grayscale compression.
     */
    fun sendImageMessage(uri: Uri, isGrayscale: Boolean = false) {
        viewModelScope.launch {
            repository.sendImageMessage(uri, isGrayscale)
        }
    }
    
    /**
     * Updates recording state for UI observation.
     */
    fun setRecordingState(recording: Boolean) {
        _isRecording.value = recording
    }
    
    fun setRecordingDuration(seconds: Int) {
        _recordingDuration.value = seconds
    }

    fun startScanning() {
        repository.startScanning()
    }

    fun connect(device: android.bluetooth.BluetoothDevice) {
        repository.connect(device)
    }

    fun disconnect() {
        repository.disconnect()
    }
}
