package com.okumi.qlabcontroller

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class QLabOscManager private constructor() {
    private var socket: DatagramSocket? = null
    private var isConnected = false
    private var qLabAddress: InetAddress? = null
    private var qLabPort: Int = 53000

    // Mock cue tracking for demo purposes
    private var currentCueIndex = 0
    private val mockCues = listOf(
        "Opening", "Scene 1", "Scene 2", "Scene 3",
        "Intermission", "Scene 4", "Scene 5", "Finale"
    )

    companion object {
        private const val TAG = "QLabOscManager"
        private const val DEFAULT_PORT = 53000

        @Volatile
        private var instance: QLabOscManager? = null

        fun getInstance(): QLabOscManager {
            return instance ?: synchronized(this) {
                instance ?: QLabOscManager().also { instance = it }
            }
        }
    }

    /**
     * Connect to QLab
     * @param ipAddress QLab IP address
     * @param port QLab OSC port (default: 53000)
     * @param passcode QLab passcode (optional)
     * @return true if connection successful
     */
    suspend fun connect(ipAddress: String, port: Int = DEFAULT_PORT, passcode: String? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            disconnect()

            qLabAddress = InetAddress.getByName(ipAddress)
            qLabPort = port
            socket = DatagramSocket()
            isConnected = true

            Log.d(TAG, "Connected to QLab at $ipAddress:$port")

            // Send passcode authentication if provided
            if (!passcode.isNullOrEmpty()) {
                delay(100) // Small delay to ensure socket is ready
                val authSuccess = sendOscMessageWithArg("/connect", passcode)
                if (!authSuccess) {
                    Log.w(TAG, "Failed to send passcode authentication")
                }
            }

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
            socket?.close()
            socket = null
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
        val result = sendOscMessage("/go")
        if (result && currentCueIndex < mockCues.size - 1) {
            currentCueIndex++
        }
        result
    }

    /**
     * Send PANIC command to QLab (stops all cues)
     */
    suspend fun sendPanic(): Boolean = withContext(Dispatchers.IO) {
        sendOscMessage("/panic")
    }

    /**
     * Send Previous command to QLab
     */
    suspend fun sendPrevious(): Boolean = withContext(Dispatchers.IO) {
        val result = sendOscMessage("/playhead/previous")
        if (result && currentCueIndex > 0) {
            currentCueIndex--
        }
        result
    }

    /**
     * Send Next command to QLab (move playhead without triggering)
     */
    suspend fun sendNext(): Boolean = withContext(Dispatchers.IO) {
        val result = sendOscMessage("/playhead/next")
        if (result && currentCueIndex < mockCues.size - 1) {
            currentCueIndex++
        }
        result
    }

    /**
     * Get current cue information
     * Note: This is a simplified version. Real implementation would query QLab
     */
    suspend fun getCurrentCueInfo(): CueInfo = withContext(Dispatchers.IO) {
        val previous = if (currentCueIndex > 0) mockCues[currentCueIndex - 1] else "---"
        val current = if (currentCueIndex < mockCues.size) mockCues[currentCueIndex] else "---"
        val next = if (currentCueIndex < mockCues.size - 1) mockCues[currentCueIndex + 1] else "---"

        CueInfo(previous, current, next)
    }

    /**
     * Send a custom OSC message to QLab
     * @param address OSC address pattern
     */
    private fun sendOscMessage(address: String): Boolean {
        if (!isConnected || socket == null || qLabAddress == null) {
            Log.w(TAG, "Not connected to QLab")
            return false
        }

        return try {
            val oscBytes = buildOscMessage(address)
            val packet = DatagramPacket(oscBytes, oscBytes.size, qLabAddress, qLabPort)
            socket?.send(packet)
            Log.d(TAG, "Sent OSC message: $address")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send OSC message: $address", e)
            false
        }
    }

    /**
     * Send OSC message with string argument
     * @param address OSC address pattern
     * @param arg String argument
     */
    private fun sendOscMessageWithArg(address: String, arg: String): Boolean {
        if (!isConnected || socket == null || qLabAddress == null) {
            Log.w(TAG, "Not connected to QLab")
            return false
        }

        return try {
            val oscBytes = buildOscMessageWithStringArg(address, arg)
            val packet = DatagramPacket(oscBytes, oscBytes.size, qLabAddress, qLabPort)
            socket?.send(packet)
            Log.d(TAG, "Sent OSC message: $address with arg: $arg")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send OSC message: $address", e)
            false
        }
    }

    /**
     * Build OSC message bytes
     * Simple implementation for address-only messages (no arguments)
     */
    private fun buildOscMessage(address: String): ByteArray {
        val addressBytes = address.toByteArray(StandardCharsets.UTF_8)
        // OSC strings are null-terminated and padded to 4-byte boundary
        val paddedSize = ((addressBytes.size + 1 + 3) / 4) * 4

        val buffer = ByteBuffer.allocate(paddedSize + 4) // +4 for type tag string

        // Write address string (null-terminated and padded)
        buffer.put(addressBytes)
        repeat(paddedSize - addressBytes.size) {
            buffer.put(0)
        }

        // Write type tag string ",\0\0\0" (no arguments)
        buffer.put(','.code.toByte())
        buffer.put(0)
        buffer.put(0)
        buffer.put(0)

        return buffer.array()
    }

    /**
     * Build OSC message with string argument
     */
    private fun buildOscMessageWithStringArg(address: String, arg: String): ByteArray {
        val addressBytes = address.toByteArray(StandardCharsets.UTF_8)
        val addressPaddedSize = ((addressBytes.size + 1 + 3) / 4) * 4

        val argBytes = arg.toByteArray(StandardCharsets.UTF_8)
        val argPaddedSize = ((argBytes.size + 1 + 3) / 4) * 4

        // Type tag ",s\0\0" for one string argument
        val typeTagSize = 4

        val buffer = ByteBuffer.allocate(addressPaddedSize + typeTagSize + argPaddedSize)

        // Write address string (null-terminated and padded)
        buffer.put(addressBytes)
        repeat(addressPaddedSize - addressBytes.size) {
            buffer.put(0)
        }

        // Write type tag string ",s\0\0" (one string argument)
        buffer.put(','.code.toByte())
        buffer.put('s'.code.toByte())
        buffer.put(0)
        buffer.put(0)

        // Write argument string (null-terminated and padded)
        buffer.put(argBytes)
        repeat(argPaddedSize - argBytes.size) {
            buffer.put(0)
        }

        return buffer.array()
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
