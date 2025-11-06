package com.okumi.qlabcontroller

import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class NetworkScanActivity : AppCompatActivity() {
    private lateinit var nsdManager: NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val discoveredDevices = mutableListOf<QLabDevice>()
    private lateinit var adapter: QLabDeviceAdapter

    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var emptyText: TextView
    private lateinit var recyclerView: RecyclerView

    companion object {
        private const val TAG = "NetworkScanActivity"
        private const val SERVICE_TYPE = "_qlab._tcp."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network_scan)

        nsdManager = (getSystemService(Context.NSD_SERVICE) as NsdManager)

        initializeViews()
        setupRecyclerView()
        startDiscovery()
    }

    private fun initializeViews() {
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        emptyText = findViewById(R.id.emptyText)
        recyclerView = findViewById(R.id.devicesRecyclerView)

        findViewById<Button>(R.id.cancelButton).setOnClickListener {
            finish()
        }
    }

    private fun setupRecyclerView() {
        adapter = QLabDeviceAdapter(discoveredDevices) { device ->
            onDeviceSelected(device)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun startDiscovery() {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                LogManager.d(TAG, "Service discovery started")
                runOnUiThread {
                    statusText.text = "Scanning for QLab instances..."
                }
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                LogManager.d(TAG, "Service found: ${service.serviceName} type: ${service.serviceType}")

                // Accept all _qlab._tcp services (don't filter by name)
                if (service.serviceType.contains("_qlab._tcp", ignoreCase = true)) {
                    LogManager.d(TAG, "QLab service found: ${service.serviceName}")

                    try {
                        nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                LogManager.e(TAG, "Resolve failed: ${serviceInfo.serviceName}, error: $errorCode")
                            }

                            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                LogManager.d(TAG, "Service resolved: ${serviceInfo.serviceName} at ${serviceInfo.host}:${serviceInfo.port}")
                                val device = QLabDevice(
                                    name = serviceInfo.serviceName,
                                    host = serviceInfo.host.hostAddress ?: "",
                                    port = serviceInfo.port
                                )
                                runOnUiThread {
                                    addDevice(device)
                                }
                            }
                        })
                    } catch (e: Exception) {
                        LogManager.e(TAG, "Failed to resolve service: ${e.message}")
                    }
                } else {
                    LogManager.d(TAG, "Not a QLab service: ${service.serviceType}")
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                LogManager.d(TAG, "Service lost: $service")
                runOnUiThread {
                    removeDevice(service.serviceName)
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                LogManager.d(TAG, "Discovery stopped: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                LogManager.e(TAG, "Discovery failed: Error code:$errorCode")
                nsdManager.stopServiceDiscovery(this)
                runOnUiThread {
                    statusText.text = "Failed to start network scan"
                    progressBar.visibility = View.GONE
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                LogManager.e(TAG, "Discovery failed: Error code:$errorCode")
                nsdManager.stopServiceDiscovery(this)
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            LogManager.e(TAG, "Failed to start discovery", e)
            statusText.text = "Failed to start network scan: ${e.message}"
            progressBar.visibility = View.GONE
        }
    }

    private fun addDevice(device: QLabDevice) {
        // Check if device already exists
        val existingIndex = discoveredDevices.indexOfFirst { it.host == device.host && it.port == device.port }
        if (existingIndex >= 0) {
            discoveredDevices[existingIndex] = device
            adapter.notifyItemChanged(existingIndex)
        } else {
            discoveredDevices.add(device)
            adapter.notifyItemInserted(discoveredDevices.size - 1)
        }

        updateEmptyState()
    }

    private fun removeDevice(serviceName: String) {
        val index = discoveredDevices.indexOfFirst { it.name == serviceName }
        if (index >= 0) {
            discoveredDevices.removeAt(index)
            adapter.notifyItemRemoved(index)
            updateEmptyState()
        }
    }

    private fun updateEmptyState() {
        if (discoveredDevices.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyText.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }

    private fun onDeviceSelected(device: QLabDevice) {
        // Navigate to connection screen with pre-filled information
        val intent = Intent(this, ConnectionActivity::class.java)
        intent.putExtra("IP_ADDRESS", device.host)
        intent.putExtra("PORT", device.port)
        intent.putExtra("AUTO_CONNECT", true)
        startActivity(intent)
        finish()
    }

    override fun onPause() {
        super.onPause()
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                LogManager.e(TAG, "Failed to stop discovery", e)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (discoveryListener != null) {
            try {
                nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            } catch (e: Exception) {
                LogManager.e(TAG, "Failed to restart discovery", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                LogManager.e(TAG, "Failed to stop discovery in onDestroy", e)
            }
        }
    }
}

// Data class for QLab device
data class QLabDevice(
    val name: String,
    val host: String,
    val port: Int
)

// RecyclerView Adapter
class QLabDeviceAdapter(
    private val devices: List<QLabDevice>,
    private val onDeviceClick: (QLabDevice) -> Unit
) : RecyclerView.Adapter<QLabDeviceAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.deviceNameText)
        val addressText: TextView = view.findViewById(R.id.deviceAddressText)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_qlab_device, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = devices[position]
        holder.nameText.text = device.name
        holder.addressText.text = "${device.host}:${device.port}"
        holder.itemView.setOnClickListener {
            onDeviceClick(device)
        }
    }

    override fun getItemCount() = devices.size
}
