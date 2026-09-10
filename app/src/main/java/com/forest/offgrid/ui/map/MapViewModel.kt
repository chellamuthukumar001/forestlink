package com.forest.offgrid.ui.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.forest.offgrid.data.local.AppDatabase
import com.forest.offgrid.data.model.BreadcrumbPoint
import com.forest.offgrid.data.model.MapNode
import com.forest.offgrid.data.model.MeshConnection
import com.forest.offgrid.data.model.NodeType
import com.forest.offgrid.data.repository.MapRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

class MapViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    private val repository = MapRepository(
        database.mapNodeDao(),
        database.breadcrumbDao()
    )

    // LiveData
    val activeNodes: LiveData<List<MapNode>> = repository.allActiveNodes
    val sosNodes: LiveData<List<MapNode>> = repository.activeSOSNodes
    val breadcrumbs: LiveData<List<BreadcrumbPoint>> = repository.allBreadcrumbs
    
    private val _meshConnections = MutableLiveData<List<MeshConnection>>()
    val meshConnections: LiveData<List<MeshConnection>> = _meshConnections
    
    private var userLatitude: Double = 0.0
    private var userLongitude: Double = 0.0
    
    private var cleanupJob: Job? = null
    private var meshUpdateJob: Job? = null

    init {
        startPeriodicCleanup()
        startMeshUpdates()
        

    }

    /**
     * Update user's current location
     */
    fun updateUserLocation(lat: Double, lon: Double, accuracy: Float) {
        userLatitude = lat
        userLongitude = lon
        
        // Update distances for all nodes
        viewModelScope.launch {
            repository.updateNodeDistances(lat, lon)
        }
    }

    /**
     * Add a breadcrumb point
     */
    fun addBreadcrumb(lat: Double, lon: Double, accuracy: Float) {
        viewModelScope.launch {
            val breadcrumb = BreadcrumbPoint(
                latitude = lat,
                longitude = lon,
                accuracy = accuracy
            )
            repository.addBreadcrumb(breadcrumb)
        }
    }

    /**
     * Add or update a node (called when hardware sends node data via BLE)
     */
    fun addOrUpdateNode(node: MapNode) {
        viewModelScope.launch {
            repository.insertNode(node)
        }
    }

    /**
     * Update node distances from current user position
     */
    private fun startMeshUpdates() {
        meshUpdateJob = viewModelScope.launch {
            while (true) {
                delay(3000) // Update mesh every 3 seconds
                
                val nodes = activeNodes.value ?: emptyList()
                if (nodes.isNotEmpty()) {
                    val connections = repository.generateMeshConnections(nodes)
                    _meshConnections.postValue(connections)
                }
            }
        }
    }

    /**
     * Periodic cleanup of old inactive nodes and breadcrumbs
     */
    private fun startPeriodicCleanup() {
        cleanupJob = viewModelScope.launch {
            while (true) {
                delay(60000) // Cleanup every minute
                
                repository.deactivateOldNodes(5 * 60 * 1000) // 5 minutes timeout
                repository.cleanupOldNodes(24 * 60 * 60 * 1000) // Remove after 24 hours
                repository.cleanupOldBreadcrumbs(6 * 60 * 60 * 1000) // Keep 6 hours of breadcrumbs
            }
        }
    }

    /**
     * Trigger SOS alert for user's device
     */
    fun triggerSOS(message: String = "Emergency assistance needed") {
        viewModelScope.launch {
            val sosNode = MapNode(
                deviceId = "USER_DEVICE",
                deviceName = "My Device (SOS)",
                latitude = userLatitude,
                longitude = userLongitude,
                nodeType = NodeType.SOS_ALERT,
                signalStrength = 100,
                isSOS = true,
                additionalInfo = message
            )
            repository.insertNode(sosNode)
        }
    }

    /**
     * Clear SOS alert
     */
    fun clearSOS() {
        viewModelScope.launch {
            val node = repository.getNode("USER_DEVICE")
            node?.let {
                val updatedNode = it.copy(
                    isSOS = false,
                    nodeType = NodeType.CURRENT_USER
                )
                repository.updateNode(updatedNode)
            }
        }
    }



    /**
     * Parse incoming hardware data and create/update nodes
     * Call this when BLE receives node data from hardware
     */
    fun processHardwareNodeData(
        deviceId: String,
        deviceName: String,
        latitude: Double,
        longitude: Double,
        nodeType: NodeType,
        signalStrength: Int,
        batteryLevel: Int,
        isSOS: Boolean = false
    ) {
        // Calculate distance from user
        val distance = if (userLatitude != 0.0 && userLongitude != 0.0) {
            repository.calculateDistance(userLatitude, userLongitude, latitude, longitude)
        } else {
            0f
        }

        val node = MapNode(
            deviceId = deviceId,
            deviceName = deviceName,
            latitude = latitude,
            longitude = longitude,
            nodeType = nodeType,
            signalStrength = signalStrength,
            batteryLevel = batteryLevel,
            isSOS = isSOS,
            distance = distance,
            lastSeen = System.currentTimeMillis()
        )
        
        addOrUpdateNode(node)
    }

    override fun onCleared() {
        super.onCleared()
        cleanupJob?.cancel()
        meshUpdateJob?.cancel()
    }
}
