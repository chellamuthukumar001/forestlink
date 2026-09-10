package com.forest.offgrid.ui.devices

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.forest.offgrid.R
import com.forest.offgrid.databinding.FragmentDevicesBinding
import com.forest.offgrid.ui.MainViewModel
import com.google.android.material.snackbar.Snackbar

class DevicesFragment : Fragment() {
    private var _binding: FragmentDevicesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: DeviceAdapter

    private var outerSonarAnimator: ObjectAnimator? = null
    private var innerSonarAnimator: ObjectAnimator? = null

    // Permission Launcher
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                checkBluetoothAndStart()
            } else {
                Toast.makeText(context, "Permissions required for BLE scanning", Toast.LENGTH_SHORT).show()
            }
        }

    // Bluetooth Enable Launcher
    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                startScanning()
            } else {
                 Toast.makeText(context, "Bluetooth is required", Toast.LENGTH_SHORT).show()
                 showScanningIndicator(true, "BLUETOOTH DISABLED")
                 binding.recyclerDevices.isVisible = false
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDevicesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        observeDevices()
        
        // Initial permission check
        checkPermissionsAndStartScan()
    }

    private fun checkPermissionsAndStartScan() {
        val permissions = mutableListOf<String>()
        
        // Android 12+ (API 31+) permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } 
        
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            checkBluetoothAndStart()
        } else {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun checkBluetoothAndStart() {
        val bluetoothManager = requireContext().getSystemService(android.content.Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        val adapter = bluetoothManager.adapter
        
        if (adapter == null || !adapter.isEnabled) {
             val enableBtIntent = android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
             enableBluetoothLauncher.launch(enableBtIntent)
        } else {
             startScanning()
        }
    }


    private fun startScanning() {
        showScanningIndicator(true, "INITIALIZING SCAN...")
        viewModel.startScanning()
    }

    private fun setupRecyclerView() {
        adapter = DeviceAdapter { device ->
            // Connect on click, disconnect if already connected (Retouch)
            if (device.device.address == viewModel.connectedDevice.value?.address) {
                viewModel.disconnect()
                Snackbar.make(binding.root, "DISCONNECTING...", Snackbar.LENGTH_SHORT).show()
            } else {
                 viewModel.connect(device.device)
                 showScanningIndicator(true, "CONNECTING TARGET...")
            }
        }
        binding.recyclerDevices.layoutManager = LinearLayoutManager(context)
        binding.recyclerDevices.adapter = adapter
    }

    private fun observeDevices() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.scannedDevices.collectLatest { devices ->
                adapter.submitList(devices)
                
                // Show scanning indicator only if list is empty AND not connected
                if (devices.isEmpty()) {
                    if (viewModel.connectedDevice.value == null) {
                        val isAdv = viewModel.hardwareState.value.isAdvertising
                        val advStatus = if (isAdv) "ACTIVE" else "INITIALIZING..."
                        showScanningIndicator(true, "SCANNING SECTOR...\n(Broadcasting: $advStatus)")
                        binding.recyclerDevices.isVisible = false
                    }
                } else {
                    binding.recyclerDevices.isVisible = true
                    if (viewModel.hardwareState.value.connectionState != com.forest.offgrid.data.model.ConnectionState.CONNECTING) {
                         showScanningIndicator(false)
                    }
                }
            }
        }
        
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.connectedDevice.collectLatest { device ->
                adapter.setConnectedDevice(device?.address)
            }
        }
        
        viewLifecycleOwner.lifecycleScope.launch {
             viewModel.hardwareState.collectLatest { state ->
                 when (state.connectionState) {
                     com.forest.offgrid.data.model.ConnectionState.CONNECTING -> {
                         showScanningIndicator(true, "CONNECTING...")
                     }
                     com.forest.offgrid.data.model.ConnectionState.CONNECTED -> {
                         showScanningIndicator(false)
                         Snackbar.make(binding.root, "CONNECTION ESTABLISHED", Snackbar.LENGTH_SHORT).show()
                     }
                     com.forest.offgrid.data.model.ConnectionState.DISCONNECTED -> {
                         if (adapter.currentList.isEmpty()) {
                             val isAdv = state.isAdvertising
                             val advStatus = if (isAdv) "ACTIVE" else "FAILED/RETRY"
                             val buildModel = android.os.Build.MODEL
                             showScanningIndicator(true, "SCANNING SECTOR...\n(Broadcasting $advStatus - $buildModel)")
                         }
                     }
                     else -> {}
                 }
                 
                 // Also ensure status is updated if just advertising changes
                 if (adapter.currentList.isEmpty() && state.connectionState != com.forest.offgrid.data.model.ConnectionState.CONNECTED && state.connectionState != com.forest.offgrid.data.model.ConnectionState.CONNECTING) {
                     val isAdv = state.isAdvertising
                     val advStatus = if (isAdv) "ACTIVE" else "INITIALIZING..."
                     showScanningIndicator(true, "SCANNING SECTOR...\n(Broadcasting: $advStatus)")
                 }
             }
        }

        // Observe reconnect countdown from BleManager
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.reconnectCountdown.collectLatest { seconds ->
                if (seconds > 0) {
                    showScanningIndicator(true, "SIGNAL LOST\nRECONNECTING IN ${seconds}s...")
                }
            }
        }
    }

    private fun showScanningIndicator(show: Boolean, statusText: String? = null) {
        binding.layoutScanningIndicator.isVisible = show
        statusText?.let { binding.textScanningStatus.text = it }
        if (show) {
            startScanningAnimations()
        } else {
            stopScanningAnimations()
        }
    }

    private fun startScanningAnimations() {
        outerSonarAnimator?.cancel()
        innerSonarAnimator?.cancel()

        outerSonarAnimator = ObjectAnimator.ofPropertyValuesHolder(
            binding.sonarPulseOuter,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.5f, 1.2f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.5f, 1.2f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.4f, 0.0f)
        ).apply {
            duration = 1800
            repeatCount = ObjectAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            start()
        }

        innerSonarAnimator = ObjectAnimator.ofPropertyValuesHolder(
            binding.sonarPulseInner,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.5f, 1.1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.5f, 1.1f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.6f, 0.0f)
        ).apply {
            duration = 1800
            startDelay = 600
            repeatCount = ObjectAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private fun stopScanningAnimations() {
        outerSonarAnimator?.cancel()
        innerSonarAnimator?.cancel()
        outerSonarAnimator = null
        innerSonarAnimator = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopScanningAnimations()
        _binding = null
    }
}
