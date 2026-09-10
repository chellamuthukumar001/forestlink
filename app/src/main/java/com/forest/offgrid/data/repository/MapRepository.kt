package com.forest.offgrid.data.repository

import androidx.lifecycle.LiveData
import com.forest.offgrid.data.local.BreadcrumbDao
import com.forest.offgrid.data.local.MapNodeDao
import com.forest.offgrid.data.model.BreadcrumbPoint
import com.forest.offgrid.data.model.MapNode
import com.forest.offgrid.data.model.MeshConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class MapRepository(
    private val mapNodeDao: MapNodeDao,
    private val breadcrumbDao: BreadcrumbDao
) {
    
    // Node operations
    val allActiveNodes: LiveData<List<MapNode>> = mapNodeDao.getAllActiveNodes()
    val activeSOSNodes: LiveData<List<MapNode>> = mapNodeDao.getActiveSOSNodes()
    
    suspend fun insertNode(node: MapNode) = withContext(Dispatchers.IO) {
        mapNodeDao.insertNode(node)
    }
    
    suspend fun insertNodes(nodes: List<MapNode>) = withContext(Dispatchers.IO) {
        mapNodeDao.insertNodes(nodes)
    }
    
    suspend fun updateNode(node: MapNode) = withContext(Dispatchers.IO) {
        mapNodeDao.updateNode(node)
    }
    
    suspend fun getNode(deviceId: String): MapNode? = withContext(Dispatchers.IO) {
        mapNodeDao.getNode(deviceId)
    }
    
    suspend fun deactivateOldNodes(timeoutMillis: Long = 5 * 60 * 1000) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - timeoutMillis
        mapNodeDao.deactivateOldNodes(cutoff)
    }
    
    suspend fun cleanupOldNodes(maxAgeMillis: Long = 24 * 60 * 60 * 1000) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        mapNodeDao.deleteOldInactiveNodes(cutoff)
    }
    
    // Breadcrumb operations
    val allBreadcrumbs: LiveData<List<BreadcrumbPoint>> = breadcrumbDao.getAllBreadcrumbs()
    
    suspend fun addBreadcrumb(breadcrumb: BreadcrumbPoint) = withContext(Dispatchers.IO) {
        breadcrumbDao.insertBreadcrumb(breadcrumb)
    }
    
    suspend fun cleanupOldBreadcrumbs(maxAgeMillis: Long = 24 * 60 * 60 * 1000) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        breadcrumbDao.deleteOldBreadcrumbs(cutoff)
    }
    
    /**
     * Calculate distance between two points in meters using Haversine formula
     */
    fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val earthRadius = 6371000.0 // meters
        
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        
        return (earthRadius * c).toFloat()
    }
    
    /**
     * Update distances from user location for all nodes
     */
    suspend fun updateNodeDistances(userLat: Double, userLon: Double) = withContext(Dispatchers.IO) {
        val nodes = mapNodeDao.getActiveNodesSync()
        val updatedNodes = nodes.map { node ->
            val distance = calculateDistance(userLat, userLon, node.latitude, node.longitude)
            node.copy(distance = distance)
        }
        mapNodeDao.insertNodes(updatedNodes)
    }
    
    /**
     * Generate mesh connections based on node proximity and signal strength
     * This creates the visual representation of how the mesh network is connected
     */
    fun generateMeshConnections(
        nodes: List<MapNode>,
        maxConnectionDistance: Float = 5000f // 5km max
    ): List<MeshConnection> {
        val connections = mutableListOf<MeshConnection>()
        
        // Find all nodes within range of each other
        for (i in nodes.indices) {
            for (j in i + 1 until nodes.size) {
                val node1 = nodes[i]
                val node2 = nodes[j]
                
                val distance = calculateDistance(
                    node1.latitude, node1.longitude,
                    node2.latitude, node2.longitude
                )
                
                if (distance <= maxConnectionDistance && node1.isActive && node2.isActive) {
                    // Calculate signal quality based on distance
                    val signalQuality = ((1 - (distance / maxConnectionDistance)) * 100).toInt()
                    
                    connections.add(
                        MeshConnection(
                            fromDeviceId = node1.deviceId,
                            toDeviceId = node2.deviceId,
                            signalQuality = signalQuality.coerceIn(0, 100)
                        )
                    )
                }
            }
        }
        
        return connections
    }
}
