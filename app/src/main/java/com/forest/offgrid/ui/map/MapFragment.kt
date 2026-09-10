package com.forest.offgrid.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.os.Environment
import java.io.File
import android.view.animation.AnimationUtils
import android.widget.EditText
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.forest.offgrid.R
import com.forest.offgrid.data.model.MapNode
import com.forest.offgrid.data.model.MeshConnection
import com.forest.offgrid.data.model.NodeType
import com.forest.offgrid.databinding.FragmentMapBinding
import com.forest.offgrid.ui.MainViewModel
import com.forest.offgrid.ui.chat.ChatAdapter
import com.google.android.gms.location.*
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

/**
 * Fully Offline Map Fragment with Real-Time Node Tracking
 * 
 * Features:
 * - Offline OSM map tiles
 * - Real-time GPS tracking
 * - Node visualization (users, devices, hubs, hardware, SOS)
 * - Mesh network visualization
 * - Breadcrumb trail
 * - Emergency SOS mode
 * - Node detail views
 */
class MapFragment : Fragment() {

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MapViewModel by viewModels()
    private val mainViewModel: MainViewModel by activityViewModels()

    private lateinit var mapView: MapView
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private var myLocationOverlay: MyLocationNewOverlay? = null
    private val nodeMarkers = mutableMapOf<String, Marker>()
    private val meshLines = mutableListOf<Polyline>()
    private var breadcrumbPolyline: Polyline? = null

    private lateinit var compassOverlay: org.osmdroid.views.overlay.compass.CompassOverlay
    private lateinit var rotationGestureOverlay: org.osmdroid.views.overlay.gestures.RotationGestureOverlay

    private var currentLocation: Location? = null
    private var showMeshVisualization = true
    private var showBreadcrumbs = true
    private var isCompassMode = false

    // Map-Chat overlay
    private lateinit var mapChatBehavior: BottomSheetBehavior<*>
    private var mapChatAdapter: ChatAdapter? = null

    // Pending "message this node" target
    private var pendingMessageNodeName: String? = null

    private val dateFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupMap()
        setupLocationTracking()
        setupUI()
        setupMapChatOverlay()
        observeData()
        observeMapChat()

        // Start GPS updates
        startLocationUpdates()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        compassOverlay.enableCompass()
        myLocationOverlay?.enableMyLocation()
        if (isCompassMode) myLocationOverlay?.enableFollowLocation()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        compassOverlay.disableCompass()
        myLocationOverlay?.disableMyLocation()
        myLocationOverlay?.disableFollowLocation()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }    
    
    private fun setupMap() {
        // Run asset copy logic (blocking only on first run, async conceptually but synchronous here for setup)
        // Ideally should be async in ViewModel or Coroutine, let's use global scope or IO context for setup
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.forest.offgrid.util.MapAssetsManager.checkAndCopyMapAssets(requireContext())
            
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                initializeOsmdroid()
            }
        }
    }

    private fun initializeOsmdroid() {
        // Configure OSMDroid with app-specific storage
        val osmConfig = Configuration.getInstance()
        osmConfig.load(
            requireContext(),
            requireContext().getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        )
        
        // precise cache configuration
        val osmPath = File(requireContext().getExternalFilesDir(null), "osmdroid")
        osmConfig.osmdroidBasePath = osmPath
        osmConfig.osmdroidTileCache = File(osmPath, "tiles")
        
        // Set user agent
        osmConfig.userAgentValue = requireContext().packageName
        
        mapView = binding.mapView
        
        // Use offline tiles - MapQuest or OpenStreetMap
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        
        // CHECK FOR OFFLINE ARCHIVES
        val offlineDir = File(osmPath, "archives")
        if (!offlineDir.exists()) offlineDir.mkdirs()
        
        // Also check public storage for manually copied maps
        val publicOfflineDir = File(Environment.getExternalStorageDirectory(), "osmdroid")
        
        val archives = mutableListOf<File>()
        
        // Helper to collect archives
        fun collectArchives(dir: File) {
            if (dir.exists()) {
                dir.listFiles { _, name -> 
                    name.endsWith(".mbtiles") || name.endsWith(".gemf") || name.endsWith(".sqlite") || name.endsWith(".zip")
                }?.let { archives.addAll(it) }
            }
        }
        
        collectArchives(offlineDir)
        collectArchives(publicOfflineDir)

        if (archives.isNotEmpty()) {
            val archive = archives[0]
            Log.d(TAG, "Using offline archive: ${archive.name}")
            
             val offlineProvider = org.osmdroid.tileprovider.modules.OfflineTileProvider(
                 org.osmdroid.tileprovider.util.SimpleRegisterReceiver(requireContext()), 
                 arrayOf(archive)
             )
             mapView.setTileProvider(offlineProvider)
             
             // Try to guess source from archive content or just defaults
             val source = org.osmdroid.tileprovider.tilesource.FileBasedTileSource.getSource(archive.name)
             if (source != null) {
                  mapView.setTileSource(source)
             }
             Toast.makeText(context, "Using Offline Map: ${archive.name}", Toast.LENGTH_LONG).show()
        } else {
             // If no offline map, enable cache to save tiles for later
             mapView.setUseDataConnection(true) // Allow internet initially to download tiles
        }
        
        // Enable multi-touch and zooming
        mapView.setMultiTouchControls(true)
        mapView.setBuiltInZoomControls(false)
        
        // Add Overlays
        addMapOverlays()
        
        // Set default zoom and center (adjust for your forest location)
        mapView.controller.setZoom(15.0)
        
        // Default to India forest area (can be changed)
        val defaultLocation = GeoPoint(12.9716, 77.5946) // Bangalore as example
        mapView.controller.setCenter(defaultLocation)
        
        // Add my location overlay
        val provider = GpsMyLocationProvider(requireContext())
        provider.addLocationSource(LocationManager.GPS_PROVIDER)
        provider.addLocationSource(LocationManager.NETWORK_PROVIDER)
        
        myLocationOverlay = MyLocationNewOverlay(provider, mapView)
        myLocationOverlay?.enableMyLocation()
        myLocationOverlay?.enableFollowLocation()
        myLocationOverlay?.isDrawAccuracyEnabled = true
        mapView.overlays.add(myLocationOverlay)
        
        Log.d(TAG, "Map initialized successfully")
    }

    private fun addMapOverlays() {
        // Compass
        compassOverlay = org.osmdroid.views.overlay.compass.CompassOverlay(
            requireContext(),
            org.osmdroid.views.overlay.compass.InternalCompassOrientationProvider(requireContext()),
            mapView
        )
        compassOverlay.enableCompass()
        mapView.overlays.add(compassOverlay)

        // Scale Bar
        val scaleBarOverlay = org.osmdroid.views.overlay.ScaleBarOverlay(mapView)
        scaleBarOverlay.setCentred(true)
        scaleBarOverlay.setScaleBarOffset(dm.widthPixels / 2, 10)
        mapView.overlays.add(scaleBarOverlay)

        // Rotation
        rotationGestureOverlay = org.osmdroid.views.overlay.gestures.RotationGestureOverlay(mapView)
        rotationGestureOverlay.isEnabled = true
        mapView.overlays.add(rotationGestureOverlay)
    }

    private val dm get() = requireContext().resources.displayMetrics

    @SuppressLint("MissingPermission")
    private fun setupLocationTracking() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    currentLocation = location
                    onLocationUpdate(location)
                }
            }
        }
    }

    private fun setupUI() {
        // Compass Toggle
        binding.fabCompass.setOnClickListener {
            toggleCompassMode()
        }

        // Center on user button
        binding.fabCenterUser.setOnClickListener {
            if (isCompassMode) {
                myLocationOverlay?.enableFollowLocation()
            } else {
                currentLocation?.let { location ->
                    val userPoint = GeoPoint(location.latitude, location.longitude)
                    mapView.controller.animateTo(userPoint)
                    mapView.controller.setZoom(16.0)
                    mapView.mapOrientation = 0f
                } ?: run {
                    Toast.makeText(context, "Waiting for GPS...", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Toggle mesh visualization
        binding.fabToggleMesh.setOnClickListener {
            showMeshVisualization = !showMeshVisualization
            updateMeshVisualization(showMeshVisualization)
            val message = if (showMeshVisualization) "Mesh connections shown" else "Mesh connections hidden"
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }

        // Toggle breadcrumb trail
        binding.fabToggleBreadcrumbs.setOnClickListener {
            showBreadcrumbs = !showBreadcrumbs
            updateBreadcrumbVisibility(showBreadcrumbs)
            val message = if (showBreadcrumbs) "Breadcrumb trail shown" else "Breadcrumb trail hidden"
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }

        // Close node detail card
        binding.nodeDetailContainer.setOnClickListener {
            binding.nodeDetailContainer.visibility = View.GONE
        }
    }

    /** Setup the map-chat bottom sheet and its mini chat list. */
    private fun setupMapChatOverlay() {
        // Wire BottomSheetBehavior
        mapChatBehavior = BottomSheetBehavior.from(binding.mapChatSheet)
        mapChatBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        mapChatBehavior.peekHeight = resources.getDimensionPixelSize(R.dimen.map_chat_peek_height)
            .takeIf { it > 0 } ?: 220 // fallback 220px

        // Mini chat adapter (subset of the main ChatAdapter)
        mapChatAdapter = ChatAdapter()
        binding.mapChatRecycler.apply {
            adapter = mapChatAdapter
            layoutManager = LinearLayoutManager(context).apply { stackFromEnd = true }
        }

        // Open full chat when button tapped
        binding.btnOpenFullChat.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed() // pop to chat tab
            // Navigate to chat tab via nav component
            try {
                val navController = androidx.navigation.fragment.NavHostFragment
                    .findNavController(this)
                navController.navigate(R.id.navigation_chat)
            } catch (e: Exception) {
                Log.w(TAG, "Nav to chat failed: ${e.message}")
            }
        }

        // Quick-send from map
        binding.btnMapSend.setOnClickListener {
            val text = binding.editMapMessage.text.toString().trim()
            if (text.isNotBlank()) {
                val prefix = pendingMessageNodeName?.let { "[@$it] " } ?: ""
                mainViewModel.sendMessage("$prefix$text")
                binding.editMapMessage.setText("")
                pendingMessageNodeName = null
                Toast.makeText(context, "📡 Message queued", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Observe recent messages and write-queue for the mini chat and queue indicator. */
    private fun observeMapChat() {
        mainViewModel.allMessages.observe(viewLifecycleOwner) { messages ->
            val recent = messages.takeLast(8)
            mapChatAdapter?.submitList(recent)
            if (recent.isNotEmpty()) {
                binding.mapChatRecycler.smoothScrollToPosition(recent.size - 1)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            mainViewModel.writeQueuePending.collectLatest { pending ->
                if (pending > 0) {
                    binding.tvMapQueueCount.visibility = View.VISIBLE
                    binding.tvMapQueueCount.text = "$pending QUEUED"
                } else {
                    binding.tvMapQueueCount.visibility = View.GONE
                }
            }
        }
    }

    private fun observeData() {
        // Observe active nodes
        viewModel.activeNodes.observe(viewLifecycleOwner) { nodes ->
            updateNodes(nodes)
            binding.tvActiveNodes.text = nodes.size.toString()
        }
        
        // Observe mesh connections
        viewModel.meshConnections.observe(viewLifecycleOwner) { connections ->
            updateMeshLines(connections)
            binding.tvMeshConnections.text = connections.size.toString()
        }
        
        // Observe SOS alerts
        viewModel.sosNodes.observe(viewLifecycleOwner) { sosNodes ->
            binding.tvSOSCount.text = sosNodes.size.toString()
            
            if (sosNodes.isNotEmpty()) {
                showSOSAlert(sosNodes.first())
            } else {
                binding.sosAlertCard.visibility = View.GONE
            }
        }
        
        // Observe breadcrumbs
        viewModel.breadcrumbs.observe(viewLifecycleOwner) { breadcrumbs ->
            updateBreadcrumbTrail(breadcrumbs.map { GeoPoint(it.latitude, it.longitude) })
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            5000L // Update every 5 seconds (adjust for battery saving)
        ).apply {
            setMinUpdateIntervalMillis(2000L)
            setMaxUpdateDelayMillis(10000L)
        }.build()
        
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            requireActivity().mainLooper
        )
    }

    private fun onLocationUpdate(location: Location) {
        // Update GPS accuracy indicator
        val accuracy = location.accuracy.roundToInt()
        binding.tvGpsAccuracy.text = "GPS: ±${accuracy}m"
        
        // Update view model
        viewModel.updateUserLocation(location.latitude, location.longitude, location.accuracy)
        
        // Add breadcrumb
        if (showBreadcrumbs) {
            viewModel.addBreadcrumb(location.latitude, location.longitude, location.accuracy)
        }
        
        Log.d(TAG, "Location updated: ${location.latitude}, ${location.longitude}, accuracy: ${accuracy}m")
    }

    private fun updateNodes(nodes: List<MapNode>) {
        // Remove old markers
        val currentNodeIds = nodes.map { it.deviceId }.toSet()
        nodeMarkers.keys.toList().forEach { deviceId ->
            if (deviceId !in currentNodeIds) {
                mapView.overlays.remove(nodeMarkers[deviceId])
                nodeMarkers.remove(deviceId)
            }
        }
        
        // Add/update markers
        nodes.forEach { node ->
            val marker = nodeMarkers.getOrPut(node.deviceId) {
                Marker(mapView).also { mapView.overlays.add(it) }
            }
            
            updateMarker(marker, node)
        }
        
        mapView.invalidate()
    }

    private fun updateMarker(marker: Marker, node: MapNode) {
        marker.position = GeoPoint(node.latitude, node.longitude)
        marker.title = node.deviceName
        
        // Set icon based on node type
        val iconRes = when (node.nodeType) {
            NodeType.CURRENT_USER -> R.drawable.ic_marker_user
            NodeType.NEARBY_DEVICE -> R.drawable.ic_marker_device
            NodeType.HUB -> R.drawable.ic_marker_hub
            NodeType.HARDWARE_NODE -> R.drawable.ic_marker_hardware
            NodeType.SOS_ALERT -> R.drawable.ic_marker_sos
        }
        
        marker.icon = ContextCompat.getDrawable(requireContext(), iconRes)
        
        // Create info window snippet
        val lastSeenTime = dateFormatter.format(Date(node.lastSeen))
        val timeDiff = System.currentTimeMillis() - node.lastSeen
        val isStale = timeDiff > 60 * 1000 // 1 minute
        
        // Fade out stale markers
        marker.alpha = if (isStale) 0.6f else 1.0f
        
        val snippet = buildString {
            append("Type: ${node.nodeType.name}\n")
            append("Distance: ${node.distance.roundToInt()}m\n")
            append("Signal: ${node.signalStrength}%\n")
            append("Battery: ${node.batteryLevel}%\n")
            append("Last Seen: $lastSeenTime")
            if (isStale) append(" (Offline)")
        }
        marker.snippet = snippet
        
        // Set marker click listener
        marker.setOnMarkerClickListener { clickedMarker, _ ->
            showNodeDetail(node)
            true
        }
        
        // Apply pulsing animation for SOS
        if (node.isSOS) {
            val pulseAnim = AnimationUtils.loadAnimation(requireContext(), R.anim.marker_pulse)
            marker.icon?.let { icon ->
                // Note: OSMDroid markers don't directly support animation,
                // but we can trigger a redraw with animation
            }
        }
    }

    private fun updateMeshLines(connections: List<MeshConnection>) {
        // Remove old lines
        meshLines.forEach { mapView.overlays.remove(it) }
        meshLines.clear()
        
        if (!showMeshVisualization) return
        
        // Draw new lines
        connections.forEach { connection ->
            val fromMarker = nodeMarkers[connection.fromDeviceId]
            val toMarker = nodeMarkers[connection.toDeviceId]
            
            if (fromMarker != null && toMarker != null) {
                val polyline = Polyline(mapView).apply {
                    addPoint(fromMarker.position)
                    addPoint(toMarker.position)
                    
                    // Color based on signal quality
                    val lineColor = when {
                        connection.signalQuality > 70 -> Color.argb(180, 76, 175, 80) // Green
                        connection.signalQuality > 40 -> Color.argb(180, 255, 152, 0) // Orange
                        else -> Color.argb(180, 244, 67, 54) // Red
                    }
                    
                    outlinePaint.color = lineColor
                    outlinePaint.strokeWidth = 4f
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                }
                
                mapView.overlays.add(0, polyline) // Add at bottom layer
                meshLines.add(polyline)
            }
        }
        
        mapView.invalidate()
    }

    private fun updateBreadcrumbTrail(points: List<GeoPoint>) {
        breadcrumbPolyline?.let { mapView.overlays.remove(it) }
        
        if (!showBreadcrumbs || points.isEmpty()) {
            breadcrumbPolyline = null
            mapView.invalidate()
            return
        }
        
        breadcrumbPolyline = Polyline(mapView).apply {
            setPoints(points)
            outlinePaint.color = Color.argb(180, 33, 150, 243) // Semi-transparent blue
            outlinePaint.strokeWidth = 6f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            title = "Your Path"
        }
        
        mapView.overlays.add(0, breadcrumbPolyline) // Add at bottom layer
        mapView.invalidate()
    }

    private fun updateMeshVisualization(show: Boolean) {
        meshLines.forEach { 
            it.isEnabled = show
        }
        mapView.invalidate()
    }

    private fun updateBreadcrumbVisibility(show: Boolean) {
        breadcrumbPolyline?.isEnabled = show
        mapView.invalidate()
    }

    private fun showNodeDetail(node: MapNode) {
        binding.nodeDetailContainer.visibility = View.VISIBLE
        binding.tvNodeName.text = node.deviceName

        val details = buildString {
            append("Device ID: ${node.deviceId}\n")
            append("Distance: ${node.distance.roundToInt()}m away\n")
            append("Signal: ${node.signalStrength}% | Battery: ${node.batteryLevel}%\n")
            append("Last Seen: ${dateFormatter.format(Date(node.lastSeen))}")
            node.additionalInfo?.let { append("\n$it") }
        }
        binding.tvNodeDetails.text = details

        binding.btnNavigateToNode.setOnClickListener {
            navigateToNode(node)
        }

        // "Message this node" – pre-fills the map chat overlay with node tag
        binding.btnMessageNode.setOnClickListener {
            binding.nodeDetailContainer.visibility = View.GONE
            pendingMessageNodeName = node.deviceName
            mapChatBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            binding.editMapMessage.requestFocus()
            binding.editMapMessage.hint = "Message to ${node.deviceName}..."
            Toast.makeText(context, "Type a message for ${node.deviceName}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun navigateToNode(node: MapNode) {
        val nodePoint = GeoPoint(node.latitude, node.longitude)
        mapView.controller.animateTo(nodePoint)
        mapView.controller.setZoom(17.0)
        binding.nodeDetailContainer.visibility = View.GONE
        
        Toast.makeText(context, "Navigating to ${node.deviceName}", Toast.LENGTH_SHORT).show()
    }

    private fun showSOSAlert(sosNode: MapNode) {
        binding.sosAlertCard.visibility = View.VISIBLE
        binding.tvSOSDetails.text = "SOS from ${sosNode.deviceName} - ${sosNode.distance.roundToInt()}m away"
        
        // Auto-center on SOS after a delay
        lifecycleScope.launch {
            delay(1000)
            val sosPoint = GeoPoint(sosNode.latitude, sosNode.longitude)
            mapView.controller.animateTo(sosPoint)
            mapView.controller.setZoom(16.0)
        }
        
        // Pulse animation
        val pulseAnim = AnimationUtils.loadAnimation(requireContext(), R.anim.marker_pulse)
        binding.sosAlertCard.startAnimation(pulseAnim)
    }

    private fun toggleCompassMode() {
        isCompassMode = !isCompassMode
        
        if (isCompassMode) {
            // Enable Compass Mode (Map rotates with bearing)
            compassOverlay.enableCompass()
            myLocationOverlay?.enableFollowLocation()
            mapView.mapOrientation = 0f // Reset initially
            
            Toast.makeText(context, "Compass Mode: On", Toast.LENGTH_SHORT).show()
            binding.fabCompass.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.accent)
        } else {
            // Disable Compass Mode (North Up)
            mapView.mapOrientation = 0f
            compassOverlay.enableCompass() // Keep compass arrow, but map is fixed North up
            
            Toast.makeText(context, "Compass Mode: Off (North Up)", Toast.LENGTH_SHORT).show()
            binding.fabCompass.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.primary)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mapView.onDetach()
        _binding = null
    }

    companion object {
        private const val TAG = "MapFragment"
    }
}
