package com.forest.offgrid.data.model

import android.bluetooth.BluetoothDevice

data class ScannedDevice(
    val device: BluetoothDevice,
    val name: String?,
    val rssi: Int,
    val lastSeen: Long = System.currentTimeMillis()
)
