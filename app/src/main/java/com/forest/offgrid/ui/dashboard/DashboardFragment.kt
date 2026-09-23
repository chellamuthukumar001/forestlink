package com.forest.offgrid.ui.dashboard

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.forest.offgrid.R
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.view.animation.Animation
import androidx.navigation.fragment.findNavController
import com.forest.offgrid.data.model.ConnectionState
import com.forest.offgrid.databinding.FragmentDashboardBinding
import com.forest.offgrid.ui.MainViewModel
import kotlinx.coroutines.launch

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        
        setupObservers()
        setupListeners()
        startSosPulse()
        animateDashboardEntry()
        
        return binding.root
    }

    private fun animateDashboardEntry() {
        // Initial state - hide all elements
        binding.layoutStatus.alpha = 0f
        binding.layoutStatus.translationY = 50f
        
        binding.cardMesh.alpha = 0f
        binding.cardMesh.translationY = 100f
        
        binding.btnSosContainer.alpha = 0f
        binding.btnSosContainer.scaleX = 0.5f
        binding.btnSosContainer.scaleY = 0.5f
        
        binding.layoutQuickActions.alpha = 0f
        binding.layoutQuickActions.translationY = 50f

        // Animate status cards
        binding.layoutStatus.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(600)
            .setStartDelay(100)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()

        // Animate mesh card
        binding.cardMesh.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(600)
            .setStartDelay(200)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()

        // Animate SOS button with bounce
        binding.btnSosContainer.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(800)
            .setStartDelay(400)
            .setInterpolator(android.view.animation.OvershootInterpolator())
            .start()

        // Animate quick actions
        binding.layoutQuickActions.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(600)
            .setStartDelay(600)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    private fun startSosPulse() {
        val pulseOuter = ObjectAnimator.ofPropertyValuesHolder(
            binding.sosPulseOuter,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.5f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.5f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.6f, 0f)
        ).apply {
            duration = 2000
            repeatCount = Animation.INFINITE
            start()
        }

        val pulseInner = ObjectAnimator.ofPropertyValuesHolder(
            binding.sosPulseInner,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.3f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.3f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.4f, 0f)
        ).apply {
            duration = 2000
            startDelay = 500
            repeatCount = Animation.INFINITE
            start()
        }
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.hardwareState.collect { state ->
                val isConnected = state.connectionState == ConnectionState.CONNECTED
                val isConnecting = state.connectionState == ConnectionState.CONNECTING
                val isScanning = state.connectionState == ConnectionState.SCANNING
                
                // Connection Status
                binding.textConnectionStatus.text = when (state.connectionState) {
                    ConnectionState.CONNECTED -> "CONNECTED"
                    ConnectionState.CONNECTING -> "CONNECTING..."
                    ConnectionState.SCANNING -> "SCANNING..."
                    ConnectionState.DISCONNECTED -> "DISCONNECTED"
                }

                val statusColor = if (isConnected) R.color.neon_green 
                                  else if (isConnecting || isScanning) R.color.cyber_blue
                                  else R.color.pulse_red
                
                binding.textConnectionStatus.setTextColor(resources.getColor(statusColor, null))
                
                // Signal Indicator Logic based on RSSI
                val signalColor = if (!isConnected) R.color.text_secondary
                                  else if (state.rssi > -60) R.color.neon_green
                                  else if (state.rssi > -80) R.color.cyber_blue
                                  else R.color.pulse_red
                
                binding.imgSignal.setColorFilter(resources.getColor(signalColor, null))
                
                // Battery Status (Only if connected)
                if (isConnected && state.batteryLevel > 0) {
                    binding.textBattery.text = "${state.batteryLevel}%"
                    binding.imgBattery.setColorFilter(
                        resources.getColor(if (state.batteryLevel > 20) R.color.neon_green else R.color.pulse_red, null)
                    )
                } else {
                    binding.textBattery.text = "--%"
                    binding.imgBattery.setColorFilter(resources.getColor(R.color.text_secondary, null))
                }

                // GPS Status
                binding.textGpsSummary.text = if (state.gpsLat != 0.0) "LOCKED" else "AWAITING FIX"
                binding.textGpsSummary.setTextColor(
                     resources.getColor(if (state.gpsLat != 0.0) R.color.neon_green else R.color.text_secondary, null)
                )
                
                // Mesh / Routing Status
                binding.textRoutingStatus.text = if (isConnected) {
                    "ACTIVE LINK ESTABLISHED"
                } else if (isScanning || isConnecting) {
                    "SEARCHING FOR FOREST NODES..."
                } else {
                    "OFFLINE - CHECK DEVICE POWER"
                }

                // AI Route & Next-Hop status
                val route = state.aiRouteInfo
                if (isConnected) {
                    val scorePct = (route.reliabilityScore * 100).toInt()
                    binding.textAiReliability.text = "AI: ${scorePct}% -> ${route.nextHopNodeId}"
                    val aiColor = if (scorePct >= 70) R.color.neon_green 
                                  else if (scorePct >= 40) R.color.cyber_blue 
                                  else R.color.pulse_red
                    binding.textAiReliability.setTextColor(resources.getColor(aiColor, null))
                } else {
                    binding.textAiReliability.text = "AI LINK: --"
                    binding.textAiReliability.setTextColor(resources.getColor(R.color.text_secondary, null))
                }

                // Node Health status
                val health = state.nodeHealth
                binding.textNodeHealth.text = "HEALTH: ${health.status.name}"
                val healthColor = when (health.status) {
                    com.forest.offgrid.data.model.HealthStatus.HEALTHY -> R.color.neon_green
                    com.forest.offgrid.data.model.HealthStatus.DEGRADING -> R.color.cyber_blue
                    com.forest.offgrid.data.model.HealthStatus.CRITICAL -> R.color.pulse_red
                }
                binding.textNodeHealth.setTextColor(resources.getColor(healthColor, null))

                // Active Anomaly Alert Banner
                if (state.activeAnomalies.isNotEmpty()) {
                    val latestAlert = state.activeAnomalies.first()
                    binding.layoutAnomalyAlert.visibility = View.VISIBLE
                    binding.textAnomalyBanner.text = "⚠️ [${latestAlert.severity}] ${latestAlert.description}"
                } else {
                    binding.layoutAnomalyAlert.visibility = View.GONE
                }
                
                // Update Mesh Visual based on state
                binding.imgMeshVisual.alpha = if (isConnected) 1.0f else 0.2f
                if (isConnected) {
                    binding.imgMeshVisual.animate().alpha(1f).setDuration(500).start()
                }
            }
        }
    }

    private fun setupListeners() {
        binding.btnSos.setOnClickListener {
            // Animate button press
            it.animate()
                .scaleX(0.9f)
                .scaleY(0.9f)
                .setDuration(100)
                .withEndAction {
                    it.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(100)
                }
            
            viewModel.sendSos()
            com.google.android.material.snackbar.Snackbar.make(binding.root, "SOS SIGNAL BROADCASTED!", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .setBackgroundTint(resources.getColor(R.color.pulse_red, null))
                .setTextColor(resources.getColor(R.color.white, null))
                .show()
        }

        binding.btnQuickText.setOnClickListener { v ->
            v.animate()
                .scaleX(0.95f)
                .scaleY(0.95f)
                .setDuration(100)
                .withEndAction {
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(100)
                        .withEndAction {
                            findNavController().navigate(R.id.navigation_chat)
                        }
                }
        }

        binding.cardSignalStatus.setOnClickListener {
            findNavController().navigate(R.id.navigation_devices)
        }

        binding.cardBatteryStatus.setOnClickListener {
            val state = viewModel.hardwareState.value
            val hours = state.nodeHealth.hoursRemainingEst
            val status = state.nodeHealth.status.name
            com.google.android.material.snackbar.Snackbar.make(
                binding.root,
                "⚡ BATTERY: ${state.batteryLevel}% | HEALTH: $status | EST: ${hours.toInt()}h",
                com.google.android.material.snackbar.Snackbar.LENGTH_LONG
            ).setBackgroundTint(resources.getColor(R.color.forest_green, null))
             .setTextColor(resources.getColor(R.color.white, null))
             .show()
        }

        binding.cardGpsStatus.setOnClickListener {
            findNavController().navigate(R.id.navigation_map)
        }
    }



    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
