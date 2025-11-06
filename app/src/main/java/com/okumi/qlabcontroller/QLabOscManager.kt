package com.okumi.qlabcontroller

import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class QLabOscManager private constructor() {
    private var socket: DatagramSocket? = null
    private var receiveSocket: DatagramSocket? = null
    private var isConnected = false
    private var qLabAddress: InetAddress? = null
    private var qLabPort: Int = 53000
    private var receiveJob: Job? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Real cue tracking
    private var currentCueId: String? = null
    private var cueList = mutableListOf<CueData>()
    private var workspaceId: String? = null
    private var mainCueListId: String? = null

    companion object {
        private const val TAG = "QLabOscManager"
        private const val DEFAULT_PORT = 53000
        private const val RECEIVE_PORT = 53001  // Port for receiving replies

        @Volatile
        private var instance: QLabOscManager? = null

        fun getInstance(): QLabOscManager {
            return instance ?: synchronized(this) {
                instance ?: QLabOscManager().also { instance = it }
            }
        }
    }

    data class CueData(
        val uniqueId: String,
        val number: String,
        val name: String,
        val type: String,
        val notes: String = ""
    )

    /**
     * Connect to QLab
     */
    suspend fun connect(ipAddress: String, port: Int = DEFAULT_PORT, passcode: String? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            disconnect()

            qLabAddress = InetAddress.getByName(ipAddress)
            qLabPort = port

            // Create send socket
            socket = DatagramSocket()

            // Create receive socket on a fixed port
            receiveSocket = DatagramSocket(RECEIVE_PORT)
            isConnected = true

            Log.d(TAG, "Connected to QLab at $ipAddress:$port")

            // Start listening for replies
            startReceiveLoop()

            // Send passcode authentication if provided
            if (!passcode.isNullOrEmpty()) {
                delay(100)
                val authSuccess = sendOscMessageWithArg("/connect", passcode)
                if (!authSuccess) {
                    Log.w(TAG, "Failed to send passcode authentication")
                    return@withContext false
                }
                // Wait a bit for authentication
                delay(200)
            }

            // Test connection and fetch initial cue info
            delay(100)
            requestWorkspaces()
            delay(200)  // Wait for workspace info

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to QLab", e)
            isConnected = false
            false
        }
    }

    /**
     * Start receiving OSC replies from QLab
     */
    private fun startReceiveLoop() {
        receiveJob?.cancel()
        receiveJob = scope.launch {
            val buffer = ByteArray(8192)
            while (isActive && receiveSocket != null) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    receiveSocket?.receive(packet)

                    val data = packet.data.copyOf(packet.length)
                    parseOscReply(data)
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Error receiving OSC reply", e)
                    }
                }
            }
        }
    }

    /**
     * Parse OSC reply from QLab
     */
    private fun parseOscReply(data: ByteArray) {
        try {
            // Simple OSC parsing - look for JSON in the message
            val message = String(data, StandardCharsets.UTF_8)

            // QLab sends JSON data in OSC messages
            val jsonStart = message.indexOf('{')
            if (jsonStart >= 0) {
                val jsonEnd = message.lastIndexOf('}')
                if (jsonEnd > jsonStart) {
                    val jsonStr = message.substring(jsonStart, jsonEnd + 1)
                    val json = JSONObject(jsonStr)

                    Log.d(TAG, "Received JSON: $jsonStr")

                    // Handle different response types
                    when {
                        json.has("data") -> handleCueListResponse(json)
                        json.has("uniqueID") -> handleCueResponse(json)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing OSC reply", e)
        }
    }

    private fun handleCueListResponse(json: JSONObject) {
        try {
            Log.d(TAG, "Full JSON response: ${json.toString(2)}")

            val data = json.optJSONArray("data")
            if (data != null) {
                // Check if this is workspace info
                if (data.length() > 0) {
                    val firstItem = data.getJSONObject(0)

                    // If it has 'cueLists', it's workspace data
                    if (firstItem.has("cueLists")) {
                        workspaceId = firstItem.optString("uniqueID")
                        Log.d(TAG, "Got workspace ID: $workspaceId")

                        val cueLists = firstItem.optJSONArray("cueLists")
                        if (cueLists != null && cueLists.length() > 0) {
                            mainCueListId = cueLists.getJSONObject(0).optString("uniqueID")
                            Log.d(TAG, "Got main cue list ID: $mainCueListId")

                            // Now request the actual cues
                            mainCueListId?.let { requestCuesFromList(it) }
                        }
                        return
                    }

                    // Otherwise, it's actual cue data
                    cueList.clear()
                    for (i in 0 until data.length()) {
                        val cue = data.getJSONObject(i)
                        val cueData = CueData(
                            uniqueId = cue.optString("uniqueID", ""),
                            number = cue.optString("number", ""),
                            name = cue.optString("name", "Untitled"),
                            type = cue.optString("type", ""),
                            notes = cue.optString("notes", "")
                        )
                        cueList.add(cueData)
                        Log.d(TAG, "Cue ${cueData.number}: ${cueData.name}, notes: '${cueData.notes}'")
                    }
                    Log.d(TAG, "Loaded ${cueList.size} cues")

                    // After loading cues, get playback position
                    requestPlaybackPosition()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling cue list response", e)
        }
    }

    private fun handleCueResponse(json: JSONObject) {
        try {
            currentCueId = json.optString("uniqueID")
            Log.d(TAG, "Current cue ID: $currentCueId")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling cue response", e)
        }
    }

    /**
     * Request workspaces from QLab
     */
    private fun requestWorkspaces() {
        sendOscMessage("/workspaces")
    }

    /**
     * Request cues from a specific cue list
     */
    private fun requestCuesFromList(cueListId: String) {
        // Need to use workspace-specific path
        workspaceId?.let { wsId ->
            sendOscMessage("/workspace/$wsId/cue_id/$cueListId/children")
        } ?: run {
            // Fallback without workspace ID
            sendOscMessage("/cue_id/$cueListId/children")
        }
    }

    /**
     * Request current playback position
     */
    private fun requestPlaybackPosition() {
        workspaceId?.let { wsId ->
            sendOscMessage("/workspace/$wsId/playbackPosition")
        } ?: run {
            sendOscMessage("/playbackPosition")
        }
    }

    /**
     * Disconnect from QLab
     */
    fun disconnect() {
        try {
            receiveJob?.cancel()
            receiveJob = null
            socket?.close()
            socket = null
            receiveSocket?.close()
            receiveSocket = null
            isConnected = false
            cueList.clear()
            currentCueId = null
            workspaceId = null
            mainCueListId = null
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
        if (result) {
            delay(100)
            requestPlaybackPosition()
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
        if (result) {
            delay(100)
            requestPlaybackPosition()
        }
        result
    }

    /**
     * Send Next command to QLab
     */
    suspend fun sendNext(): Boolean = withContext(Dispatchers.IO) {
        val result = sendOscMessage("/playhead/next")
        if (result) {
            delay(100)
            requestPlaybackPosition()
        }
        result
    }

    /**
     * Get current cue information
     */
    suspend fun getCurrentCueInfo(): CueInfo = withContext(Dispatchers.IO) {
        requestPlaybackPosition()
        delay(50)  // Wait for response

        val currentIndex = cueList.indexOfFirst { it.uniqueId == currentCueId }

        val previous = if (currentIndex > 0) {
            val cue = cueList[currentIndex - 1]
            "${cue.number} ${cue.name}"
        } else "---"

        val current = if (currentIndex >= 0 && currentIndex < cueList.size) {
            val cue = cueList[currentIndex]
            "${cue.number} ${cue.name}"
        } else "---"

        val next = if (currentIndex >= 0 && currentIndex < cueList.size - 1) {
            val cue = cueList[currentIndex + 1]
            "${cue.number} ${cue.name}"
        } else "---"

        val notes = if (currentIndex >= 0 && currentIndex < cueList.size) {
            cueList[currentIndex].notes
        } else ""

        Log.d(TAG, "Current cue info - Index: $currentIndex, Notes: '$notes'")

        CueInfo(previous, current, next, notes)
    }

    /**
     * Rename a cue
     */
    suspend fun renameCue(cueId: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        sendOscMessageWithArg("/cue_id/$cueId/name", newName)
    }

    /**
     * Send a custom OSC message to QLab
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
     */
    private fun buildOscMessage(address: String): ByteArray {
        val addressBytes = address.toByteArray(StandardCharsets.UTF_8)
        val paddedSize = ((addressBytes.size + 1 + 3) / 4) * 4

        val buffer = ByteBuffer.allocate(paddedSize + 4)

        buffer.put(addressBytes)
        repeat(paddedSize - addressBytes.size) {
            buffer.put(0)
        }

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

        val typeTagSize = 4

        val buffer = ByteBuffer.allocate(addressPaddedSize + typeTagSize + argPaddedSize)

        buffer.put(addressBytes)
        repeat(addressPaddedSize - addressBytes.size) {
            buffer.put(0)
        }

        buffer.put(','.code.toByte())
        buffer.put('s'.code.toByte())
        buffer.put(0)
        buffer.put(0)

        buffer.put(argBytes)
        repeat(argPaddedSize - argBytes.size) {
            buffer.put(0)
        }

        return buffer.array()
    }

    fun isConnected(): Boolean = isConnected

    fun getConnectionInfo(): String {
        return if (isConnected && qLabAddress != null) {
            "${qLabAddress?.hostAddress}:$qLabPort"
        } else {
            "Not connected"
        }
    }
}
