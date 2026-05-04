package com.okumi.qlabcontroller

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class NetworkScanActivity : AppCompatActivity() {
    private lateinit var nsdManager: NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var isDiscovering = false
    private var isResolving = false
    private val pendingResolveServices = mutableListOf<NsdServiceInfo>()
    private val discoveredDevices = mutableListOf<QLabDevice>()
    private lateinit var adapter: QLabDeviceAdapter

    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var emptyText: TextView
    private lateinit var recyclerView: RecyclerView

    companion object {
        private const val TAG = "NetworkScanActivity"
        private const val SERVICE_TYPE = "_qlab._tcp."
        private const val DEFAULT_OSC_PORT = 53000
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network_scan)

        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        initializeViews()
        setupRecyclerView()
    }

    private fun initializeViews() {
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        emptyText = findViewById(R.id.emptyText)
        recyclerView = findViewById(R.id.devicesRecyclerView)

        findViewById<Button>(R.id.cancelButton).setOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        adapter = QLabDeviceAdapter(discoveredDevices) { device -> onDeviceSelected(device) }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun startDiscovery() {
        if (isDiscovering) return

        acquireMulticastLock()
        isDiscovering = true
        runOnUiThread {
            statusText.text = "Scanning for QLab instances..."
            progressBar.visibility = View.VISIBLE
        }

        val listener = discoveryListener ?: createDiscoveryListener().also {
            discoveryListener = it
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            LogManager.e(TAG, "Failed to start discovery for $SERVICE_TYPE", e)
            isDiscovering = false
            releaseMulticastLock()
            runOnUiThread {
                statusText.text = "Failed to start network scan"
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun createDiscoveryListener(): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                LogManager.d(TAG, "Bonjour discovery started for $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType != SERVICE_TYPE) return
                LogManager.d(TAG, "QLab Bonjour service found: $service")
                queueResolve(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                LogManager.d(TAG, "Service lost: $service")
                runOnUiThread { removeDevice(service.serviceName) }
            }

            override fun onDiscoveryStopped(stoppedServiceType: String) {
                LogManager.d(TAG, "Discovery stopped: $stoppedServiceType")
                isDiscovering = false
                runOnUiThread { progressBar.visibility = View.GONE }
                releaseMulticastLock()
            }

            override fun onStartDiscoveryFailed(failedServiceType: String, errorCode: Int) {
                LogManager.e(TAG, "Discovery start failed ($failedServiceType): $errorCode")
                try {
                    nsdManager.stopServiceDiscovery(this)
                } catch (e: Exception) {
                    LogManager.e(TAG, "Failed to stop discovery after start failure", e)
                }
                isDiscovering = false
                releaseMulticastLock()
                runOnUiThread {
                    statusText.text = "Failed to start network scan"
                    progressBar.visibility = View.GONE
                }
            }

            override fun onStopDiscoveryFailed(failedServiceType: String, errorCode: Int) {
                LogManager.e(TAG, "Discovery stop failed ($failedServiceType): $errorCode")
                isDiscovering = false
                releaseMulticastLock()
                runOnUiThread { progressBar.visibility = View.GONE }
            }
        }
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return

        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("qlab-controller-mdns").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "Failed to acquire multicast lock", e)
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "Failed to release multicast lock", e)
        }
        multicastLock = null
    }

    private fun queueResolve(service: NsdServiceInfo) {
        val isQueued = pendingResolveServices.any {
            it.serviceName == service.serviceName && it.serviceType == service.serviceType
        }
        if (!isQueued) {
            pendingResolveServices.add(service)
        }
        resolveNextService()
    }

    private fun resolveNextService() {
        if (isResolving || pendingResolveServices.isEmpty()) return

        val service = pendingResolveServices.removeAt(0)
        isResolving = true
        try {
            nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    LogManager.e(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                    isResolving = false
                    if (isDiscovering) resolveNextService()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    isResolving = false
                    val hostAddress = serviceInfo.host?.hostAddress.orEmpty()
                    if (hostAddress.isNotEmpty()) {
                        val device = QLabDevice(
                            name = serviceInfo.serviceName,
                            host = hostAddress,
                            port = if (serviceInfo.port > 0) serviceInfo.port else DEFAULT_OSC_PORT
                        )
                        runOnUiThread { addDevice(device) }
                    }
                    if (isDiscovering) resolveNextService()
                }
            })
        } catch (e: Exception) {
            LogManager.e(TAG, "Failed to resolve ${service.serviceName}", e)
            isResolving = false
            if (isDiscovering) resolveNextService()
        }
    }

    private fun stopDiscovery() {
        if (!isDiscovering) return

        discoveryListener?.let { listener ->
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                LogManager.e(TAG, "Failed to stop discovery", e)
            }
        }
        pendingResolveServices.clear()
        isResolving = false
        isDiscovering = false
        progressBar.visibility = View.GONE
        releaseMulticastLock()
    }

    private fun addDevice(device: QLabDevice) {
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
        val intent = Intent(this, ConnectionActivity::class.java)
        intent.putExtra("IP_ADDRESS", device.host)
        intent.putExtra("PORT", device.port)
        intent.putExtra("AUTO_CONNECT", true)
        startActivity(intent)
        finish()
    }

    override fun onPause() {
        super.onPause()
        stopDiscovery()
    }

    override fun onResume() {
        super.onResume()
        startDiscovery()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDiscovery()
    }
}

data class QLabDevice(
    val name: String,
    val host: String,
    val port: Int
)

class QLabDeviceAdapter(
    private val devices: List<QLabDevice>,
    private val onDeviceClick: (QLabDevice) -> Unit
) : RecyclerView.Adapter<QLabDeviceAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.deviceNameText)
        val addressText: TextView = view.findViewById(R.id.deviceAddressText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_qlab_device, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = devices[position]
        holder.nameText.text = device.name
        holder.addressText.text = "${device.host}:${device.port}"
        holder.itemView.setOnClickListener { onDeviceClick(device) }
    }

    override fun getItemCount() = devices.size
}
