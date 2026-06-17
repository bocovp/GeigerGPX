package com.github.bocovp.geigergpx

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton

class DeviceListFragment : Fragment(R.layout.fragment_device_list) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerViewDevices)
        val fab = view.findViewById<FloatingActionButton>(R.id.fabNewDevice)

        val allDevices = DeviceConfigManager.devices(requireContext())
        val currentDeviceName = DeviceConfigManager.currentDevice(requireContext())?.name

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = DeviceAdapter(allDevices, currentDeviceName) { selected ->
            DeviceConfigManager.selectDevice(requireContext(), selected.name)
            parentFragmentManager.popBackStack()
        }

        fab.setOnClickListener {
            showCloneDialog(allDevices)
        }
    }

    private fun showCloneDialog(allDevices: List<DeviceConfigManager.Device>) {
        val deviceNames = allDevices.map { it.name }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("Choose base device to copy from")
            .setItems(deviceNames) { _, which ->
                val baseName = deviceNames[which]
                showNewNameDialog(baseName)
            }
            .show()
    }

    private fun showNewNameDialog(baseName: String) {
        val input = EditText(requireContext()).apply {
            hint = "New device name"
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(requireContext()).apply {
            setPadding(padding, padding, padding, padding)
            addView(input)
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Enter name for new device")
            .setView(container)
            .setPositiveButton("Create", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) {
                    input.error = "Name cannot be empty"
                    return@setOnClickListener
                }

                val success = DeviceConfigManager.cloneDevice(requireContext(), baseName, newName)
                if (success) {
                    Toast.makeText(requireContext(), "Device created and selected", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    parentFragmentManager.popBackStack()
                } else {
                    input.error = "Device name already exists"
                }
            }
        }
        dialog.show()
    }

    private inner class DeviceAdapter(
        private val devices: List<DeviceConfigManager.Device>,
        private val currentActiveName: String?,
        private val onSelect: (DeviceConfigManager.Device) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.textDeviceName)
            val subtitle: TextView = view.findViewById(R.id.textDeviceType)

            init {
                view.setOnClickListener {
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        onSelect(devices[position])
                    }
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val device = devices[position]
            holder.title.text = device.name

            val typeStr = if (device.isCustom) "Custom" else "Built-in"
            val activeStr = if (device.name == currentActiveName) " • ACTIVE" else ""
            holder.subtitle.text = "$typeStr$activeStr"

            val context = holder.itemView.context
            val color = if (device.name == currentActiveName)
                androidx.core.content.ContextCompat.getColor(context, R.color.status_working)
            else
                androidx.core.content.ContextCompat.getColor(context, android.R.color.darker_gray)

            holder.title.setTextColor(color)
        }

        override fun getItemCount() = devices.size
    }
}