package com.forest.offgrid.data.local

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.room.*
import com.forest.offgrid.data.model.BreadcrumbPoint
import com.forest.offgrid.data.model.MapNode
import com.forest.offgrid.data.model.Message
import com.forest.offgrid.data.model.MessageStatus

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    fun getAllMessages(): LiveData<List<Message>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: Message): Long

    @Update
    suspend fun updateMessage(message: Message)
    
    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun updateMessageStatus(id: Long, status: MessageStatus)

    @Query("UPDATE messages SET status = :status, temperature = :temp, humidity = :hum WHERE id = :id")
    suspend fun updateMessageStatusAndSensors(id: Long, status: MessageStatus, temp: Float?, hum: Float?)
}

@Database(
    entities = [Message::class, MapNode::class, BreadcrumbPoint::class], 
    version = 7,  // Updated for Voice/Image media support
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun mapNodeDao(): MapNodeDao
    abstract fun breadcrumbDao(): BreadcrumbDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "off_grid_db"
                )
                    .fallbackToDestructiveMigration() // For demo: clears DB on schema change
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
