package com.okumi.qlabcontroller

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

            LogManager.d(TAG, "Connected to QLab at $ipAddress:$port")

            // Start listening for replies
            startReceiveLoop()

            // Send passcode authentication if provided (try without workspace ID first)
            if (!passcode.isNullOrEmpty()) {
                delay(100)
                val authSuccess = sendOscMessageWithArg("/connect", passcode)
                if (!authSuccess) {
                    LogManager.w(TAG, "Failed to send passcode authentication")
                }
                delay(200)
            }

            // Try to get workspaces for future use
            delay(100)
            requestWorkspaces()
            delay(500)

            // If we got workspace ID, re-authenticate with workspace-specific path
            if (!passcode.isNullOrEmpty() && workspaceId != null) {
                LogManager.d(TAG, "Re-authenticating with workspace ID: $workspaceId")
                sendOscMessageWithArg("/workspace/$workspaceId/connect", passcode)
                delay(200)
            }

            // Fetch initial cue info
            if (workspaceId != null) {
                requestCueLists()
            } else {
                // Try fallback without workspace ID
                LogManager.w(TAG, "No workspace ID, trying fallback cue list request")
                sendOscMessage("/cueLists")
            }
            delay(300)

            true
        } catch (e: Exception) {
            LogManager.e(TAG, "Failed to connect to QLab", e)
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
                        LogManager.e(TAG, "Error receiving OSC reply", e)
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

            LogManager.d(TAG, "Received OSC message (${data.size} bytes), address: '$address'")

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

                    try {
                        val json = JSONObject(jsonStr)
                        LogManager.d(TAG, "Parsed JSON successfully, has 'data': ${json.has("data")}, has 'status': ${json.has("status")}")

                        // Handle different response types
                        when {
                            json.has("data") -> handleCueListResponse(json)
                            json.has("uniqueID") -> handleCueResponse(json)
                            else -> LogManager.w(TAG, "Unknown JSON format: ${json.keys().asSequence().toList()}")
                        }
                    } catch (jsonE: Exception) {
                        LogManager.e(TAG, "Failed to parse JSON: $jsonStr", jsonE)
                    }
                } else {
                    LogManager.w(TAG, "No valid JSON found in message")
                }
            } else {
                LogManager.d(TAG, "No JSON in message, raw content (first 100 chars): ${message.take(100)}")
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "Error parsing OSC reply", e)
        }
    }

    /**
     * Handle real-time update messages from QLab
     */
    private fun handleUpdateMessage(address: String, fullMessage: String) {
        try {
            LogManager.d(TAG, "Update message: $address")

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
                            LogManager.d(TAG, "Playback position updated to cue: $currentCueId")
                        } else {
                            // No cue ID means playback position is cleared
                            currentCueId = null
                            LogManager.d(TAG, "Playback position cleared")
                        }
                    }
                }

                // Workspace updated: /update/workspace/{id}
                address.matches(Regex("/update/workspace/[^/]+$")) -> {
                    LogManager.d(TAG, "Workspace updated, reloading cue lists")
                    requestCueLists()
                }

                // Specific cue updated: /update/workspace/{id}/cue_id/{cueId}
                address.contains("/cue_id/") -> {
                    LogManager.d(TAG, "Cue updated, may need to reload cue data")
                    // Could reload specific cue here if needed
                }

                // Disconnect requested: /update/workspace/{id}/disconnect
                address.endsWith("/disconnect") -> {
                    LogManager.w(TAG, "QLab requested disconnect")
                    disconnect()
                }
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "Error handling update message", e)
        }
    }

    private fun handleCueListResponse(json: JSONObject) {
        try {
            LogManager.d(TAG, "Full JSON response: ${json.toString(2)}")

            val data = json.optJSONArray("data")
            if (data != null && data.length() > 0) {
                val firstItem = data.getJSONObject(0)
                LogManager.d(TAG, "First item keys: ${firstItem.keys().asSequence().toList()}")

                // Check if this is workspace info (has uniqueID, displayName, etc)
                // Workspace objects typically have: uniqueID, displayName, hasPasscode, version, etc
                if (firstItem.has("displayName") || firstItem.has("hasPasscode") || firstItem.has("version")) {
                    // This is workspace data
                    workspaceId = firstItem.optString("uniqueID")
                    val workspaceName = firstItem.optString("displayName", "Unknown")
                    LogManager.d(TAG, "Got workspace: $workspaceName (ID: $workspaceId)")
                    // Don't request cue lists here, wait for connect() to do it
                    return
                }

                // Check if this is cue lists data (array of Cue List objects)
                if (firstItem.has("type")) {
                    val itemType = firstItem.optString("type")
                    LogManager.d(TAG, "Item type: $itemType")

                    if (itemType == "Cue List") {
                        mainCueListId = firstItem.optString("uniqueID")
                        val cueListName = firstItem.optString("name", "Main")
                        LogManager.d(TAG, "Got cue list: $cueListName (ID: $mainCueListId)")
                        // Request the actual cues from this cue list
                        mainCueListId?.let { requestCuesFromList(it) }
                        return
                    }
                }

                // Otherwise, assume it's actual cue data (children of cue list)
                LogManager.d(TAG, "Parsing as cue data (${data.length()} items)")
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
                    LogManager.d(TAG, "Cue ${cueData.number}: ${cueData.name}, type: ${cueData.type}, notes: '${cueData.notes}'")
                }
                LogManager.d(TAG, "Loaded ${cueList.size} cues")

                // After loading cues, get playback position
                requestPlaybackPosition()
            } else {
                LogManager.w(TAG, "Response has no data array or data is empty")
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "Error handling cue list response", e)
        }
    }

    private fun handleCueResponse(json: JSONObject) {
        try {
            currentCueId = json.optString("uniqueID")
            LogManager.d(TAG, "Current cue ID: $currentCueId")
        } catch (e: Exception) {
            LogManager.e(TAG, "Error handling cue response", e)
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
        val command = workspaceId?.let { "/workspace/$it/playbackPosition" } ?: "/playbackPosition"
        sendOscMessage(command)
    }

    /**
     * Disconnect from QLab
     */
    fun disconnect() {
        try {
            LogManager.d(TAG, "Disconnecting from QLab...")
            // Disable updates before disconnecting (removed as it may not be needed)
            // if (isConnected) {
            //     sendOscMessageWithArg("/updates", "0")
            // }

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
            LogManager.d(TAG, "Disconnected from QLab successfully")
        } catch (e: Exception) {
            LogManager.e(TAG, "Error disconnecting from QLab", e)
        }
    }

    /**
     * Send GO command to QLab
     */
    suspend fun sendGo(): Boolean = withContext(Dispatchers.IO) {
        val command = workspaceId?.let { "/workspace/$it/go" } ?: "/go"
        LogManager.d(TAG, "Sending GO command: $command")
        val result = sendOscMessage(command)
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
        val command = workspaceId?.let { "/workspace/$it/panic" } ?: "/panic"
        LogManager.d(TAG, "Sending PANIC command: $command")
        sendOscMessage(command)
    }

    /**
     * Send Previous command to QLab
     */
    suspend fun sendPrevious(): Boolean = withContext(Dispatchers.IO) {
        val command = workspaceId?.let { "/workspace/$it/playhead/previous" } ?: "/previous"
        LogManager.d(TAG, "Sending PREVIOUS command: $command")
        val result = sendOscMessage(command)
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
        val command = workspaceId?.let { "/workspace/$it/playhead/next" } ?: "/next"
        LogManager.d(TAG, "Sending NEXT command: $command")
        val result = sendOscMessage(command)
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

        LogManager.d(TAG, "Current cue info - Index: $currentIndex, Notes: '$notes'")

        CueInfo(previous, current, next, notes)
    }

    /**
     * Rename a cue
     */
    suspend fun renameCue(cueId: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        val command = workspaceId?.let { "/workspace/$it/cue_id/$cueId/name" } ?: "/cue_id/$cueId/name"
        LogManager.d(TAG, "Renaming cue: $command to '$newName'")
        sendOscMessageWithArg(command, newName)
    }

    /**
     * Send a custom OSC message to QLab
     */
    private fun sendOscMessage(address: String): Boolean {
        if (!isConnected || socket == null || qLabAddress == null) {
            LogManager.w(TAG, "Not connected to QLab")
            return false
        }

        return try {
            val oscBytes = buildOscMessage(address)
            val packet = DatagramPacket(oscBytes, oscBytes.size, qLabAddress, qLabPort)
            socket?.send(packet)
            LogManager.d(TAG, "Sent OSC message: $address")
            true
        } catch (e: Exception) {
            LogManager.e(TAG, "Failed to send OSC message: $address", e)
            false
        }
    }

    /**
     * Send OSC message with string argument
     */
    private fun sendOscMessageWithArg(address: String, arg: String): Boolean {
        if (!isConnected || socket == null || qLabAddress == null) {
            LogManager.w(TAG, "Not connected to QLab")
            return false
        }

        return try {
            val oscBytes = buildOscMessageWithStringArg(address, arg)
            val packet = DatagramPacket(oscBytes, oscBytes.size, qLabAddress, qLabPort)
            socket?.send(packet)
            LogManager.d(TAG, "Sent OSC message: $address with arg: $arg")
            true
        } catch (e: Exception) {
            LogManager.e(TAG, "Failed to send OSC message: $address", e)
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
