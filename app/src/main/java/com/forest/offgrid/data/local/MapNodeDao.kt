package com.forest.offgrid.data.local

import androidx.lifecycle.LiveData
import androidx.room.*
import com.forest.offgrid.data.model.BreadcrumbPoint
import com.forest.offgrid.data.model.MapNode

@Dao
interface MapNodeDao {
    
    @Query("SELECT * FROM map_nodes WHERE isActive = 1 ORDER BY lastSeen DESC")
    fun getAllActiveNodes(): LiveData<List<MapNode>>

    @Query("SELECT * FROM map_nodes WHERE isActive = 1")
    suspend fun getActiveNodesSync(): List<MapNode>
    
    @Query("SELECT * FROM map_nodes WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getNode(deviceId: String): MapNode?
    
    @Query("SELECT * FROM map_nodes WHERE isSOS = 1 AND isActive = 1")
    fun getActiveSOSNodes(): LiveData<List<MapNode>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNode(node: MapNode)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNodes(nodes: List<MapNode>)
    
    @Update
    suspend fun updateNode(node: MapNode)
    
    @Query("UPDATE map_nodes SET isActive = 0 WHERE lastSeen < :timeout")
    suspend fun deactivateOldNodes(timeout: Long)
    
    @Query("DELETE FROM map_nodes WHERE lastSeen < :cutoffTime AND isActive = 0")
    suspend fun deleteOldInactiveNodes(cutoffTime: Long)
    
    @Query("DELETE FROM map_nodes")
    suspend fun deleteAll()
}

@Dao
interface BreadcrumbDao {
    
    @Query("SELECT * FROM breadcrumbs ORDER BY timestamp ASC")
    fun getAllBreadcrumbs(): LiveData<List<BreadcrumbPoint>>
    
    @Query("SELECT * FROM breadcrumbs WHERE timestamp > :since ORDER BY timestamp ASC")
    fun getBreadcrumbsSince(since: Long): LiveData<List<BreadcrumbPoint>>
    
    @Insert
    suspend fun insertBreadcrumb(breadcrumb: BreadcrumbPoint)
    
    @Query("DELETE FROM breadcrumbs WHERE timestamp < :cutoffTime")
    suspend fun deleteOldBreadcrumbs(cutoffTime: Long)
    
    @Query("DELETE FROM breadcrumbs")
    suspend fun deleteAll()
}
