package com.forest.offgrid.ui.devices

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import android.view.View
import com.forest.offgrid.R
import com.forest.offgrid.data.model.ScannedDevice
import com.forest.offgrid.databinding.ItemDeviceScanBinding
import java.text.DecimalFormat
import kotlin.math.pow

class DeviceAdapter(private val onDeviceClick: (ScannedDevice) -> Unit) :
    ListAdapter<ScannedDevice, DeviceAdapter.DeviceViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val binding = ItemDeviceScanBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return DeviceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    private var connectedDeviceAddress: String? = null

    fun setConnectedDevice(address: String?) {
        connectedDeviceAddress = address
        notifyDataSetChanged()
    }

    inner class DeviceViewHolder(private val binding: ItemDeviceScanBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(device: ScannedDevice) {
            val name = device.name ?: "Unknown Device"
            val address = device.device.address
            val rssi = device.rssi

            // Determine Type and Icon
            var iconRes = R.drawable.ic_mesh_node
            val type = when {
                name.contains("HUB", true) -> {
                    iconRes = R.drawable.ic_marker_hub
                    "HUB"
                }
                name.contains("NODE", true) -> {
                    iconRes = R.drawable.ic_marker_hardware
                    "NODE"
                }
                name.contains("FOREST", true) -> {
                    iconRes = R.drawable.ic_marker_hardware
                    "FOREST"
                }
                else -> "DEVICE"
            }
            
            val displayName = if (name != "Unknown Device") name else "NODE-${address.takeLast(4)}"

            // Estimate Distance
            val txPower = -59
            val n = 2.0
            val distance = 10.0.pow((txPower - rssi) / (10 * n))
            val df = DecimalFormat("#.0")
            
            binding.textDeviceName.text = displayName
            binding.textDeviceAddress.text = "$address • ${df.format(distance)}m"
            binding.textRssi.text = "$rssi dBm"
            
            // Signal Percent
            val signalPercent = ((rssi + 100) * 2).coerceIn(0, 100)
            binding.textSignalPercent.text = "SIGNAL: $signalPercent%"
            binding.textSignalPercent.setTextColor(when {
                signalPercent > 75 -> android.graphics.Color.parseColor("#39FF14")
                signalPercent > 40 -> android.graphics.Color.parseColor("#FFD600")
                else -> android.graphics.Color.parseColor("#FF3131")
            })

            // Highlight Connected Device
            val card = binding.root as com.google.android.material.card.MaterialCardView
            val isConnected = address == connectedDeviceAddress
            
            if (isConnected) {
                card.strokeWidth = 4
                card.setStrokeColor(android.graphics.Color.parseColor("#00D4FF"))
                card.cardElevation = 12f
                binding.textDeviceName.setTextColor(android.graphics.Color.parseColor("#00D4FF"))
                binding.signalGlow.visibility = View.VISIBLE
                binding.signalGlow.animate().alpha(0.4f).setDuration(1000).start()
            } else {
                card.strokeWidth = 1
                card.setStrokeColor(android.graphics.Color.parseColor("#26FFFFFF"))
                card.cardElevation = 0f
                binding.textDeviceName.setTextColor(android.graphics.Color.WHITE)
                binding.signalGlow.visibility = View.GONE
            }

            binding.imgDeviceIcon.setImageResource(iconRes)
            binding.imgDeviceIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor(if (isConnected) "#00D4FF" else "#9E9E9E")
            )

            binding.root.setOnClickListener {
                onDeviceClick(device)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<ScannedDevice>() {
        override fun areItemsTheSame(oldItem: ScannedDevice, newItem: ScannedDevice): Boolean {
            return oldItem.device.address == newItem.device.address
        }

        override fun areContentsTheSame(oldItem: ScannedDevice, newItem: ScannedDevice): Boolean {
             return oldItem == newItem
        }
    }
}
