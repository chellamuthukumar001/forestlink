package com.forest.offgrid.ui.chat

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.launch
import com.forest.offgrid.R
import com.forest.offgrid.databinding.FragmentChatBinding
import com.forest.offgrid.ui.MainViewModel
import com.forest.offgrid.util.AudioRecorderHelper
import java.io.File

class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var chatAdapter: ChatAdapter
    private lateinit var audioRecorder: AudioRecorderHelper

    // Recording dot animation
    private var dotAnimator: ObjectAnimator? = null

    // Typing indicator animation
    private var typingAnimators = listOf<ObjectAnimator>()

    // Selected channel prefix
    private var channelPrefix = "" // empty = PRIMARY
    private var isGrayscaleSelected = false

    // Camera photo URI
    private var pendingCameraUri: Uri? = null
    
    // Camera launcher
    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && pendingCameraUri != null) {
            viewModel.sendImageMessage(pendingCameraUri!!, isGrayscaleSelected)
            val modeStr = if (isGrayscaleSelected) "Grayscale" else "Color (4x SR)"
            Toast.makeText(context, "📷 Sending photo [$modeStr]...", Toast.LENGTH_SHORT).show()
        }
    }
    
    // Gallery picker launcher
    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.sendImageMessage(it, isGrayscaleSelected)
            val modeStr = if (isGrayscaleSelected) "Grayscale" else "Color (4x SR)"
            Toast.makeText(context, "📷 Sending photo [$modeStr]...", Toast.LENGTH_SHORT).show()
        }
    }
    
    // Permission launcher for audio recording
    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startVoiceRecording()
        } else {
            Toast.makeText(context, "Microphone permission required for voice messages", Toast.LENGTH_LONG).show()
        }
    }
    
    // Permission launcher for camera
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCamera()
        } else {
            Toast.makeText(context, "Camera permission required to take photos", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        
        audioRecorder = AudioRecorderHelper(requireContext())
        
        setupRecyclerView()
        setupListeners()
        setupObservers()
        setupAudioRecorder()
        
        return binding.root
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter()
        binding.recyclerView.apply {
            adapter = chatAdapter
            layoutManager = LinearLayoutManager(context).apply {
                stackFromEnd = true
            }
        }
    }

    private fun setupListeners() {
        binding.toolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        // Channel chip selection
        binding.channelChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            channelPrefix = when {
                checkedIds.contains(R.id.chipChannelSecondary) -> "[SEC] "
                checkedIds.contains(R.id.chipChannelEmergency) -> "[EMRG] "
                else -> ""
            }
        }

        // Send text message (with channel prefix)
        binding.btnSend.setOnClickListener {
            val text = binding.editMessage.text.toString()
            if (text.isNotBlank()) {
                viewModel.sendMessage("$channelPrefix$text")
                binding.editMessage.setText("")
            }
        }
        
        // Attachment button — show bottom sheet with Camera/Gallery options
        binding.btnAttach.setOnClickListener {
            showAttachmentOptions()
        }
        
        // Microphone button — start voice recording
        binding.btnMic.setOnClickListener {
            if (hasAudioPermission()) {
                startVoiceRecording()
            } else {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        
        // Cancel recording
        binding.btnCancelRecording.setOnClickListener {
            audioRecorder.cancelRecording()
            hideRecordingUI()
        }
        
        // Stop and send recording
        binding.btnStopRecording.setOnClickListener {
            audioRecorder.stopRecording()
            // Callback will handle sending and hiding UI
        }
    }
    
    private fun setupAudioRecorder() {
        audioRecorder.onRecordingComplete = { filePath, durationSeconds ->
            activity?.runOnUiThread {
                hideRecordingUI()
                viewModel.sendVoiceMessage(filePath, durationSeconds)
                Toast.makeText(context, "🎤 Sending voice message...", Toast.LENGTH_SHORT).show()
            }
        }
        
        audioRecorder.onRecordingCancelled = {
            activity?.runOnUiThread {
                hideRecordingUI()
            }
        }
        
        audioRecorder.onDurationUpdate = { seconds ->
            activity?.runOnUiThread {
                binding.textRecordingDuration.text = "${seconds}s / 10s"
                viewModel.setRecordingDuration(seconds)
            }
        }
        
        audioRecorder.onMaxDurationReached = {
            activity?.runOnUiThread {
                Toast.makeText(context, "Max recording duration reached", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupObservers() {
        viewModel.allMessages.observe(viewLifecycleOwner) { messages ->
            chatAdapter.submitList(messages)
            if (messages.isNotEmpty()) {
                binding.recyclerView.smoothScrollToPosition(messages.size - 1)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.connectedDevice.collect { device ->
                if (device != null) {
                    binding.toolbar.subtitle = "🟢 Connected: ${device.name ?: "Unknown Node"}"
                } else {
                    binding.toolbar.subtitle = "🔴 Offline"
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.hardwareState.collect { state ->
                val isConnected = state.connectionState == com.forest.offgrid.data.model.ConnectionState.CONNECTED

                if (isConnected) {
                    binding.statusBannerRow.visibility = View.GONE
                } else {
                    binding.statusBannerRow.visibility = View.VISIBLE
                    when (state.connectionState) {
                        com.forest.offgrid.data.model.ConnectionState.CONNECTING -> {
                            binding.textChatStatus.text = "CONNECTING TO MESH..."
                            binding.statusBannerRow.setBackgroundColor(resources.getColor(com.forest.offgrid.R.color.cyber_blue, null))
                        }
                        com.forest.offgrid.data.model.ConnectionState.SCANNING -> {
                            binding.textChatStatus.text = "SEARCHING FOR NODES..."
                            binding.statusBannerRow.setBackgroundColor(resources.getColor(com.forest.offgrid.R.color.cyber_blue, null))
                        }
                        else -> {
                            binding.textChatStatus.text = "OFFLINE – MESSAGES QUEUED"
                            binding.statusBannerRow.setBackgroundColor(resources.getColor(com.forest.offgrid.R.color.pulse_red, null))
                        }
                    }
                }
            }
        }

        // Reconnect countdown in status bar
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.reconnectCountdown.collect { seconds ->
                if (seconds > 0) {
                    binding.statusBannerRow.visibility = View.VISIBLE
                    binding.textChatStatus.text = "RECONNECTING IN ${seconds}s..."
                    binding.statusBannerRow.setBackgroundColor(resources.getColor(com.forest.offgrid.R.color.cyber_blue, null))
                }
            }
        }

        // Write queue depth
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.writeQueuePending.collect { pending ->
                if (pending > 0) {
                    binding.tvQueueDepth.visibility = View.VISIBLE
                    binding.tvQueueDepth.text = "$pending ↑"
                } else {
                    binding.tvQueueDepth.visibility = View.GONE
                }
            }
        }

        // Observe live LoRa photo reconstruction & Super-Resolution progress
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.photoTransferStates.collect { states ->
                chatAdapter.updateTransferStates(states)
            }
        }

        // Typing dots animation trigger: show typing when a new message arrives and is short
        // (Approximation – in a real mesh protocol you'd send a typing packet)
        startTypingDotsAnimation()
    }

    private fun startTypingDotsAnimation() {
        val dots = listOf(binding.typingDot1, binding.typingDot2, binding.typingDot3)
        typingAnimators = dots.mapIndexed { i, dot ->
            ObjectAnimator.ofFloat(dot, "alpha", 0.2f, 1f).apply {
                duration = 400
                startDelay = (i * 150).toLong()
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            }
        }
        // Typing indicator hidden by default; show/hide via ViewModel when protocol sends typing packet
    }
    
    // --- Attachment Options ---
    
    private fun showAttachmentOptions() {
        val bottomSheet = BottomSheetDialog(requireContext(), R.style.Theme_OffGridCommunication)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_attach, null)

        val switchGrayscale = sheetView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_grayscale)
        val textSpeed = sheetView.findViewById<TextView>(R.id.text_speed_estimate)

        switchGrayscale?.isChecked = isGrayscaleSelected
        switchGrayscale?.setOnCheckedChangeListener { _, isChecked ->
            isGrayscaleSelected = isChecked
            if (isChecked) {
                textSpeed?.text = "⚡ WebP Mono: ~1-2 KB (Est: ~6s @ SF7) - 3x Faster!"
                textSpeed?.setTextColor(resources.getColor(R.color.neon_green, null))
            } else {
                textSpeed?.text = "⚡ WebP Color: ~2-5 KB (Est: ~18s @ SF7)"
                textSpeed?.setTextColor(resources.getColor(R.color.text_secondary, null))
            }
        }
        
        sheetView.findViewById<View>(R.id.option_camera)?.setOnClickListener {
            bottomSheet.dismiss()
            if (hasCameraPermission()) {
                launchCamera()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
        
        sheetView.findViewById<View>(R.id.option_gallery)?.setOnClickListener {
            bottomSheet.dismiss()
            galleryLauncher.launch("image/*")
        }
        
        bottomSheet.setContentView(sheetView)
        bottomSheet.show()
    }
    
    private fun launchCamera() {
        try {
            val photoFile = File(
                requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                "photo_${System.currentTimeMillis()}.jpg"
            )
            pendingCameraUri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                photoFile
            )
            cameraLauncher.launch(pendingCameraUri)
        } catch (e: Exception) {
            Toast.makeText(context, "Error launching camera: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    // --- Voice Recording ---
    
    private fun startVoiceRecording() {
        val filePath = audioRecorder.startRecording()
        if (filePath != null) {
            showRecordingUI()
            viewModel.setRecordingState(true)
        } else {
            Toast.makeText(context, "Failed to start recording", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showRecordingUI() {
        binding.layoutInput.visibility = View.GONE
        binding.layoutRecording.visibility = View.VISIBLE
        binding.textRecordingDuration.text = "0s / 10s"
        
        // Pulsing animation on the recording dot
        dotAnimator = ObjectAnimator.ofFloat(binding.recordingDot, "alpha", 1f, 0.2f).apply {
            duration = 600
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }
    
    private fun hideRecordingUI() {
        binding.layoutRecording.visibility = View.GONE
        binding.layoutInput.visibility = View.VISIBLE
        viewModel.setRecordingState(false)
        viewModel.setRecordingDuration(0)
        
        dotAnimator?.cancel()
        dotAnimator = null
    }
    
    // --- Permissions ---
    
    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroyView() {
        super.onDestroyView()
        chatAdapter.releasePlayer()
        audioRecorder.release()
        dotAnimator?.cancel()
        typingAnimators.forEach { it.cancel() }
        _binding = null
    }
}
