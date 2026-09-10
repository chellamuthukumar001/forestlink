package com.forest.offgrid

import android.app.Application
import com.forest.offgrid.data.local.AppDatabase

class ForestApplication : Application() {
    
    // Lazy DB initialization
    val database: AppDatabase by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate() {
        super.onCreate()
        // Initialize other global setups if needed (e.g. Timber, Notifications channel global config)
    }
}
