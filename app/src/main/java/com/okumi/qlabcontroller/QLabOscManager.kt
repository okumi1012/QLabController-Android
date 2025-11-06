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

    private var savedPasscode: String? = null

    /**
     * Connect to QLab
     */
    suspend fun connect(ipAddress: String, port: Int = DEFAULT_PORT, passcode: String? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            disconnect()

            qLabAddress = InetAddress.getByName(ipAddress)
            qLabPort = port
            savedPasscode = passcode

            // Create send socket
            socket = DatagramSocket()

            // Create receive socket on a fixed port
            receiveSocket = DatagramSocket(RECEIVE_PORT)
            isConnected = true

            Log.d(TAG, "Connected to QLab at $ipAddress:$port")

            // Start listening for replies
            startReceiveLoop()

            // First, get workspaces
            delay(100)
            requestWorkspaces()

            // Wait for workspace ID to be set, then authenticate
            delay(500)

            // Send passcode authentication if provided
            if (!passcode.isNullOrEmpty() && workspaceId != null) {
                val authSuccess = sendOscMessageWithArg("/workspace/$workspaceId/connect", passcode)
                if (!authSuccess) {
                    Log.w(TAG, "Failed to send passcode authentication")
                    return@withContext false
                }
                delay(200)
            }

            // Enable real-time updates from QLab
            sendOscMessageWithArg("/updates", "1")
            delay(100)

            // Fetch initial cue info
            requestCueLists()
            delay(300)

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

            // Extract OSC address (first null-terminated string)
            val firstNull = message.indexOf('\u0000')
            val address = if (firstNull > 0) message.substring(0, firstNull) else ""

            Log.d(TAG, "Received OSC address: $address")

            // Handle real-time update messages from QLab
            if (address.startsWith("/update/")) {
                handleUpdateMessage(address, message)
                return
            }

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

    /**
     * Handle real-time update messages from QLab
     */
    private fun handleUpdateMessage(address: String, fullMessage: String) {
        try {
            Log.d(TAG, "Update message: $address")

            when {
                // Playback position changed: /update/workspace/{id}/cueList/{cueListId}/playbackPosition {cueId}
                address.contains("/playbackPosition") -> {
                    // Extract cue ID from the message arguments
                    val parts = address.split("/")
                    // Try to find the cue ID in the message data
                    val typeTagStart = fullMessage.indexOf(',')
                    if (typeTagStart > 0) {
                        // Look for string argument after type tag
                        val afterTypeTag = fullMessage.substring(typeTagStart + 4) // Skip ",s\0\0"
                        val cueIdEnd = afterTypeTag.indexOf('\u0000')
                        if (cueIdEnd > 0) {
                            currentCueId = afterTypeTag.substring(0, cueIdEnd)
                            Log.d(TAG, "Playback position updated to cue: $currentCueId")
                        } else {
                            // No cue ID means playback position is cleared
                            currentCueId = null
                            Log.d(TAG, "Playback position cleared")
                        }
                    }
                }

                // Workspace updated: /update/workspace/{id}
                address.matches(Regex("/update/workspace/[^/]+$")) -> {
                    Log.d(TAG, "Workspace updated, reloading cue lists")
                    requestCueLists()
                }

                // Specific cue updated: /update/workspace/{id}/cue_id/{cueId}
                address.contains("/cue_id/") -> {
                    Log.d(TAG, "Cue updated, may need to reload cue data")
                    // Could reload specific cue here if needed
                }

                // Disconnect requested: /update/workspace/{id}/disconnect
                address.endsWith("/disconnect") -> {
                    Log.w(TAG, "QLab requested disconnect")
                    disconnect()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling update message", e)
        }
    }

    private fun handleCueListResponse(json: JSONObject) {
        try {
            Log.d(TAG, "Full JSON response: ${json.toString(2)}")

            val data = json.optJSONArray("data")
            if (data != null && data.length() > 0) {
                val firstItem = data.getJSONObject(0)

                // Check if this is workspace info
                if (firstItem.has("hasPasscode")) {
                    // This is workspace data
                    workspaceId = firstItem.optString("uniqueID")
                    Log.d(TAG, "Got workspace ID: $workspaceId")
                    // Don't request cue lists here, wait for connect() to do it
                    return
                }

                // Check if this is cue lists data
                if (firstItem.has("type") && firstItem.optString("type") == "Cue List") {
                    mainCueListId = firstItem.optString("uniqueID")
                    Log.d(TAG, "Got main cue list ID: $mainCueListId")
                    // Request the actual cues from this cue list
                    mainCueListId?.let { requestCuesFromList(it) }
                    return
                }

                // Otherwise, it's actual cue data (children of cue list)
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
     * Request cue lists from workspace
     */
    private fun requestCueLists() {
        workspaceId?.let { wsId ->
            sendOscMessage("/workspace/$wsId/cueLists")
        }
    }

    /**
     * Request cues from a specific cue list
     */
    private fun requestCuesFromList(cueListId: String) {
        workspaceId?.let { wsId ->
            sendOscMessage("/workspace/$wsId/cue_id/$cueListId/children")
        }
    }

    /**
     * Request current playback position
     */
    private fun requestPlaybackPosition() {
        workspaceId?.let { wsId ->
            sendOscMessage("/workspace/$wsId/playbackPosition")
        }
    }

    /**
     * Disconnect from QLab
     */
    fun disconnect() {
        try {
            // Disable updates before disconnecting
            if (isConnected) {
                sendOscMessageWithArg("/updates", "0")
            }

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
            savedPasscode = null
            Log.d(TAG, "Disconnected from QLab")
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting from QLab", e)
        }
    }

    /**
     * Send GO command to QLab
     */
    suspend fun sendGo(): Boolean = withContext(Dispatchers.IO) {
        workspaceId?.let { wsId ->
            val result = sendOscMessage("/workspace/$wsId/go")
            if (result) {
                delay(100)
                requestPlaybackPosition()
            }
            result
        } ?: false
    }

    /**
     * Send PANIC command to QLab (stops all cues)
     */
    suspend fun sendPanic(): Boolean = withContext(Dispatchers.IO) {
        workspaceId?.let { wsId ->
            sendOscMessage("/workspace/$wsId/panic")
        } ?: false
    }

    /**
     * Send Previous command to QLab
     */
    suspend fun sendPrevious(): Boolean = withContext(Dispatchers.IO) {
        workspaceId?.let { wsId ->
            val result = sendOscMessage("/workspace/$wsId/playhead/previous")
            if (result) {
                delay(100)
                requestPlaybackPosition()
            }
            result
        } ?: false
    }

    /**
     * Send Next command to QLab
     */
    suspend fun sendNext(): Boolean = withContext(Dispatchers.IO) {
        workspaceId?.let { wsId ->
            val result = sendOscMessage("/workspace/$wsId/playhead/next")
            if (result) {
                delay(100)
                requestPlaybackPosition()
            }
            result
        } ?: false
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
        workspaceId?.let { wsId ->
            sendOscMessageWithArg("/workspace/$wsId/cue_id/$cueId/name", newName)
        } ?: false
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
