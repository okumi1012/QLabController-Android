package com.okumi.qlabcontroller

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Helper class for Network Service Discovery (Bonjour/mDNS)
 * Discovers QLab instances on the local network using _qlab._tcp service
 */
class NsdHelper(private val context: Context) {

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    data class DiscoveredService(
        val serviceName: String,
        val host: String,
        val port: Int,
        val attributes: Map<String, String> = emptyMap()
    )

    companion object {
        private const val TAG = "NsdHelper"
        private const val SERVICE_TYPE = "_qlab._tcp."
    }

    /**
     * Start discovering QLab services on the network
     * Returns a Flow of discovered services
     */
    fun discoverServices(): Flow<DiscoveredService> = callbackFlow {
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                LogManager.d(TAG, "Service discovery started: $serviceType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                LogManager.d(TAG, "Service found: ${service.serviceName}")

                // Resolve the service to get IP and port
                val resolveListener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        LogManager.e(TAG, "Resolve failed: ${serviceInfo.serviceName}, error: $errorCode")
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        LogManager.d(TAG, "Service resolved: ${serviceInfo.serviceName} at ${serviceInfo.host}:${serviceInfo.port}")

                        // Extract attributes if any
                        val attributes = mutableMapOf<String, String>()
                        serviceInfo.attributes?.forEach { (key, value) ->
                            attributes[key] = String(value)
                        }

                        val discovered = DiscoveredService(
                            serviceName = serviceInfo.serviceName,
                            host = serviceInfo.host.hostAddress ?: "",
                            port = serviceInfo.port,
                            attributes = attributes
                        )

                        trySend(discovered)
                    }
                }

                try {
                    nsdManager.resolveService(service, resolveListener)
                } catch (e: Exception) {
                    LogManager.e(TAG, "Failed to resolve service: ${e.message}")
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                LogManager.d(TAG, "Service lost: ${service.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                LogManager.d(TAG, "Discovery stopped: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                LogManager.e(TAG, "Discovery start failed: $serviceType, error: $errorCode")
                close(Exception("Discovery failed: $errorCode"))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                LogManager.e(TAG, "Discovery stop failed: $serviceType, error: $errorCode")
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            LogManager.e(TAG, "Failed to start discovery: ${e.message}")
            close(e)
        }

        awaitClose {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) {
                LogManager.e(TAG, "Failed to stop discovery: ${e.message}")
            }
        }
    }
}
