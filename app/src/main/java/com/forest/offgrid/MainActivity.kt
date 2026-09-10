
package com.forest.offgrid

import android.Manifest
import android.util.Log
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.forest.offgrid.databinding.ActivityMainBinding
import com.forest.offgrid.service.CommunicationService
import com.forest.offgrid.ui.MainViewModel
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                startCommunicationService()
            } else {
                Toast.makeText(this, "Permissions required for connectivity", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.d("MainActivity", "onCreate started")
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        Log.d("MainActivity", "View inflated, setting up navigation")

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        
        Log.d("MainActivity", "NavController obtained: ${navController.currentDestination?.label}")
        
        val navView: BottomNavigationView = binding.navView
        navView.setupWithNavController(navController)
        
        // Hide bottom navigation on landing page
        navController.addOnDestinationChangedListener { _, destination, _ ->
            Log.d("MainActivity", "Navigated to: ${destination.label}")
            when (destination.id) {
                R.id.navigation_landing -> navView.visibility = android.view.View.GONE
                else -> navView.visibility = android.view.View.VISIBLE
            }
        }
        
        // Initialize permissions and service
        checkPermissions()
        
        
        Log.d("MainActivity", "onCreate completed")
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissionLauncher.launch(missing.toTypedArray())
        } else {
            startCommunicationService()
        }
    }

    private fun startCommunicationService() {
        val intent = Intent(this, CommunicationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
