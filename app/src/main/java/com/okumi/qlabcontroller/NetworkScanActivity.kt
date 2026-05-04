package com.okumi.qlabcontroller

import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class NetworkScanActivity : AppCompatActivity() {
    private lateinit var nsdManager: NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var isDiscovering = false
    private var isResolving = false
    private var subnetScanJob: Job? = null
    private val pendingResolveServices = mutableListOf<NsdServiceInfo>()
    private val discoveredDevices = mutableListOf<QLabDevice>()
    private lateinit var adapter: QLabDeviceAdapter

    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var emptyText: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var manualButton: Button

    companion object {
        private const val TAG = "NetworkScanActivity"
        private const val SERVICE_TYPE = "_qlab._tcp."
        private const val DEFAULT_OSC_PORT = 53000
        private const val OSC_SCAN_TIMEOUT_MS = 2_800L
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
        manualButton = findViewById(R.id.manualButton)

        findViewById<Button>(R.id.cancelButton).setOnClickListener { finish() }
        manualButton.setOnClickListener {
            startActivity(Intent(this, ConnectionActivity::class.java))
            finish()
        }
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
            statusText.text = "Searching with Bonjour and OSC fallback..."
            progressBar.visibility = View.VISIBLE
            updateEmptyState()
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
                statusText.text = "Bonjour scan failed. Try manual IP connection."
                progressBar.visibility = View.GONE
            }
        }

        startOscSubnetScan()
    }

    private fun createDiscoveryListener(): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                LogManager.d(TAG, "Bonjour discovery started for $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (!isQlabService(service.serviceType)) {
                    LogManager.d(TAG, "Ignoring non-QLab service: ${service.serviceType}")
                    return
                }
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
                    val hostAddress = getResolvedHostAddress(serviceInfo)
                    if (hostAddress.isNotEmpty()) {
                        val device = QLabDevice(
                            name = serviceInfo.serviceName.ifBlank { "QLab Workspace" },
                            host = hostAddress,
                            port = DEFAULT_OSC_PORT,
                            source = "Bonjour"
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

    private fun isQlabService(serviceType: String): Boolean {
        val normalizedType = serviceType
            .lowercase()
            .removeSuffix(".")
            .removeSuffix(".local")
            .removeSuffix(".")
        return normalizedType == "_qlab._tcp" || normalizedType.endsWith("._sub._qlab._tcp")
    }

    private fun getResolvedHostAddress(serviceInfo: NsdServiceInfo): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            serviceInfo.hostAddresses.firstOrNull()?.hostAddress.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            val host = serviceInfo.host
            host?.hostAddress.orEmpty()
        }
    }

    private fun startOscSubnetScan() {
        if (subnetScanJob?.isActive == true) return

        subnetScanJob = lifecycleScope.launch(Dispatchers.IO) {
            val hosts = try {
                localSubnetHosts()
            } catch (e: Exception) {
                LogManager.e(TAG, "Failed to read local network interfaces", e)
                emptyList()
            }
            if (hosts.isEmpty()) {
                LogManager.w(TAG, "No local IPv4 subnet found for OSC fallback scan")
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    statusText.text = "No local Wi-Fi subnet found. Enter the QLab Mac IP manually."
                    updateEmptyState()
                }
                return@launch
            }

            try {
                DatagramSocket().use { scanSocket ->
                    scanSocket.soTimeout = 250
                    val replyPort = scanSocket.localPort
                    val replyPortPacket = buildOscMessage("/udpReplyPort", "i", ByteBuffer.allocate(4).putInt(replyPort).array())
                    val workspacePacket = buildOscMessage("/workspaces", "", ByteArray(0))

                    hosts.forEach { host ->
                        if (!isActive) return@forEach
                        val address = InetAddress.getByName(host)
                        scanSocket.send(DatagramPacket(replyPortPacket, replyPortPacket.size, address, DEFAULT_OSC_PORT))
                        scanSocket.send(DatagramPacket(workspacePacket, workspacePacket.size, address, DEFAULT_OSC_PORT))
                    }

                    val deadline = System.currentTimeMillis() + OSC_SCAN_TIMEOUT_MS
                    val buffer = ByteArray(8192)
                    while (isActive && System.currentTimeMillis() < deadline) {
                        try {
                            val packet = DatagramPacket(buffer, buffer.size)
                            scanSocket.receive(packet)
                            parseWorkspaceReply(packet.data, packet.length)?.let { workspaceName ->
                                val device = QLabDevice(
                                    name = workspaceName,
                                    host = packet.address.hostAddress.orEmpty(),
                                    port = DEFAULT_OSC_PORT,
                                    source = "OSC scan"
                                )
                                runOnUiThread { addDevice(device) }
                            }
                        } catch (_: SocketTimeoutException) {
                            // Keep listening until the overall scan deadline.
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogManager.e(TAG, "OSC fallback scan failed", e)
            } finally {
                if (isDiscovering) {
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        statusText.text = if (discoveredDevices.isEmpty()) {
                            "No QLab instance found automatically. Enter the IP address manually."
                        } else {
                            "Select a workspace, then confirm port and passcode."
                        }
                        updateEmptyState()
                    }
                }
            }
        }
    }

    private fun localSubnetHosts(): List<String> {
        val localAddresses = mutableListOf<String>()
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (!networkInterface.isUp || networkInterface.isLoopback) continue

            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && address.isSiteLocalAddress) {
                    localAddresses.add(address.hostAddress)
                }
            }
        }

        return localAddresses.flatMap { localAddress ->
            val parts = localAddress.split('.')
            if (parts.size != 4) return@flatMap emptyList<String>()
            val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
            (1..254).map { "$prefix.$it" }.filterNot { it == localAddress }
        }.distinct()
    }

    private fun parseWorkspaceReply(data: ByteArray, length: Int): String? {
        val message = String(data, 0, length, StandardCharsets.UTF_8)
        val jsonStart = message.indexOf('{')
        val jsonEnd = message.lastIndexOf('}')
        if (jsonStart < 0 || jsonEnd <= jsonStart) return null

        return try {
            val json = JSONObject(message.substring(jsonStart, jsonEnd + 1))
            val firstWorkspace = json.optJSONArray("data")?.optJSONObject(0) ?: return null
            firstWorkspace.optString("displayName", "QLab Workspace").ifBlank { "QLab Workspace" }
        } catch (e: Exception) {
            LogManager.e(TAG, "Failed to parse OSC scan reply", e)
            null
        }
    }

    private fun buildOscMessage(address: String, typeTags: String, payload: ByteArray): ByteArray {
        val addressBytes = paddedOscBytes(address)
        val typeTagBytes = paddedOscBytes(",$typeTags")
        return ByteBuffer.allocate(addressBytes.size + typeTagBytes.size + payload.size).apply {
            put(addressBytes)
            put(typeTagBytes)
            put(payload)
        }.array()
    }

    private fun paddedOscBytes(value: String): ByteArray {
        val rawBytes = value.toByteArray(StandardCharsets.UTF_8)
        val paddedSize = ((rawBytes.size + 1 + 3) / 4) * 4
        return ByteBuffer.allocate(paddedSize).apply { put(rawBytes) }.array()
    }

    private fun stopDiscovery() {
        subnetScanJob?.cancel()
        subnetScanJob = null
        if (!isDiscovering) {
            progressBar.visibility = View.GONE
            releaseMulticastLock()
            return
        }

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
        intent.putExtra("DEVICE_NAME", device.name)
        intent.putExtra("DEVICE_SOURCE", device.source)
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
    val port: Int,
    val source: String
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
        holder.addressText.text = "${device.host}:${device.port} - ${device.source}"
        holder.itemView.setOnClickListener { onDeviceClick(device) }
    }

    override fun getItemCount() = devices.size
}
