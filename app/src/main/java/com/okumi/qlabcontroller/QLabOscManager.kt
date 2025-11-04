package com.okumi.qlabcontroller

import android.util.Log
import com.illposed.osc.OSCMessage
import com.illposed.osc.transport.udp.OSCPortOut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

class QLabOscManager {
    private var oscPortOut: OSCPortOut? = null
    private var isConnected = false
    private var qLabAddress: InetAddress? = null
    private var qLabPort: Int = 53000

    companion object {
        private const val TAG = "QLabOscManager"
        private const val DEFAULT_PORT = 53000
    }

    /**
     * Connect to QLab
     * @param ipAddress QLab IP address
     * @param port QLab OSC port (default: 53000)
     * @return true if connection successful
     */
    suspend fun connect(ipAddress: String, port: Int = DEFAULT_PORT): Boolean = withContext(Dispatchers.IO) {
        try {
            disconnect()

            qLabAddress = InetAddress.getByName(ipAddress)
            qLabPort = port

            oscPortOut = OSCPortOut(qLabAddress, qLabPort)
            isConnected = true

            Log.d(TAG, "Connected to QLab at $ipAddress:$port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to QLab", e)
            isConnected = false
            false
        }
    }

    /**
     * Disconnect from QLab
     */
    fun disconnect() {
        try {
            oscPortOut?.close()
            oscPortOut = null
            isConnected = false
            Log.d(TAG, "Disconnected from QLab")
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting from QLab", e)
        }
    }

    /**
     * Send GO command to QLab
     */
    suspend fun sendGo(): Boolean = withContext(Dispatchers.IO) {
        sendOscMessage("/go")
    }

    /**
     * Send PANIC command to QLab (stops all cues)
     */
    suspend fun sendPanic(): Boolean = withContext(Dispatchers.IO) {
        sendOscMessage("/panic")
    }

    /**
     * Send a custom OSC message to QLab
     * @param address OSC address pattern
     * @param arguments Optional arguments for the OSC message
     */
    private fun sendOscMessage(address: String, vararg arguments: Any): Boolean {
        if (!isConnected || oscPortOut == null) {
            Log.w(TAG, "Not connected to QLab")
            return false
        }

        return try {
            val message = if (arguments.isEmpty()) {
                OSCMessage(address)
            } else {
                OSCMessage(address, arguments.toList())
            }

            oscPortOut?.send(message)
            Log.d(TAG, "Sent OSC message: $address")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send OSC message: $address", e)
            false
        }
    }

    /**
     * Check if currently connected to QLab
     */
    fun isConnected(): Boolean = isConnected

    /**
     * Get the current connection address
     */
    fun getConnectionInfo(): String {
        return if (isConnected && qLabAddress != null) {
            "${qLabAddress?.hostAddress}:$qLabPort"
        } else {
            "Not connected"
        }
    }
}
